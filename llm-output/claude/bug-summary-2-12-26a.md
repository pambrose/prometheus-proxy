# Bug Summary - 2/12/2026

Comprehensive review of the prometheus-proxy codebase (`src/main/kotlin/io/prometheus/`).

---

## HIGH Severity

### 1. HttpClientCache.removeEntry leaks HTTP clients when entries are not in use

**File:** `agent/HttpClientCache.kt:209-213`

When an entry is evicted (cache full), cleaned up (expired), or replaced (stale on access),
`removeEntry()` calls `markForClose()` but never actually closes the underlying `HttpClient`:

```kotlin
private fun removeEntry(keyString: String) {
  val entry = cache.remove(keyString)
  entry?.markForClose()       // Marks for close...
  accessOrder.remove(keyString)
  // ...but if entry is NOT in-use, nobody ever calls entry.client.close()
}
```

The `close()` method on the cache handles this correctly by checking `!entry.isInUse()` and
collecting clients to close. But the eviction/cleanup paths do not. If an entry is idle (no active
scrape using it) when it is removed, the `HttpClient` and its underlying connections are leaked.
`onFinishedWithClient()` is the only path that closes marked-for-close entries, but it is only
called by scrape code that is actively using a client.

**Impact:** Connection/resource leak. Over time, evicted idle clients accumulate without being
closed, exhausting connection pools or file descriptors.

**Fix:** In `removeEntry()`, after `markForClose()`, check `!entry.isInUse()` and close the client
(outside the mutex, similar to `close()`), or return the client for the caller to close.

---

### 2. Chunk validation failure leaves HTTP handler waiting until timeout

**File:** `proxy/ProxyServiceImpl.kt:221-227`

When `applyChunk()` throws a `ChunkValidationException`, the chunked context is removed but the
corresponding scrape request in `ScrapeRequestManager` is never notified:

```kotlin
try {
  context.applyChunk(chunkBytes.toByteArray(), chunkByteCount, chunkCount, chunkChecksum)
} catch (e: ChunkValidationException) {
  logger.error(e) { "Chunk validation failed for scrapeId: $chunkScrapeId, discarding context" }
  contextManager.removeChunkedContext(chunkScrapeId)
  activeScrapeIds -= chunkScrapeId
  // Missing: notify the waiting HTTP handler via scrapeRequestManager
}
```

The HTTP handler in `submitScrapeRequest()` (ProxyHttpRoutes.kt:215) is suspended in a loop calling
`awaitCompleted()`. Since `assignScrapeResults()` is never called for this scrapeId, the handler
blocks until the full scrape timeout expires.

**Impact:** Degraded latency. Chunk integrity failures cause the proxy to return HTTP 503 only after
the full timeout (default: seconds), instead of failing fast.

**Fix:** After catching the validation exception, call `scrapeRequestManager.assignScrapeResults()`
with an error `ScrapeResults`, or directly look up and close the wrapper's channel.

---

## MEDIUM Severity

### 3. AgentContext.writeScrapeRequest counter leak on coroutine cancellation

**File:** `proxy/AgentContext.kt:80-88`

```kotlin
suspend fun writeScrapeRequest(scrapeRequest: ScrapeRequestWrapper) {
  channelBacklogSize += 1          // Incremented BEFORE send
  try {
    scrapeRequestChannel.send(scrapeRequest)
  } catch (e: ClosedSendChannelException) {
    channelBacklogSize -= 1        // Only this exception is caught
    throw e
  }
  // CancellationException is NOT caught -- counter leaks
}
```

If the coroutine is cancelled between the `channelBacklogSize += 1` and the completion of `send()`,
the `CancellationException` propagates without decrementing the counter. Over time, the backlog size
metric drifts upward.

**Impact:** `scrapeRequestBacklogSize` reported by metrics/monitoring becomes permanently inflated,
producing misleading operational data.

**Fix:** Use a `try/finally` pattern: increment before send, and decrement in `finally` on any
failure, or increment only after the send succeeds.

---

### 4. SslSettings.getKeyStore does not zero password char array

**File:** `agent/SslSettings.kt:35-38`

```kotlin
FileInputStream(fileName).use { keyStoreFile ->
  val keyStorePassword = password.toCharArray()
  load(keyStoreFile, keyStorePassword)
  // keyStorePassword is never zeroed
}
```

The `keyStorePassword` char array retains the plaintext password in memory after use. The Java
`KeyStore.load()` API accepts `char[]` specifically so callers can zero it after use, avoiding
long-lived cleartext secrets in the heap.

**Impact:** Passwords remain in memory until GC, increasing the window for memory-dump attacks.

**Fix:** Zero the array in a `finally` block: `keyStorePassword.fill('\u0000')`.

---

## LOW Severity

### 5. AgentOptions cache validations reject valid size of 1

**File:** `agent/AgentOptions.kt:241,246,251,256`

```kotlin
require(maxCacheSize > 1) { "http.clientCache.maxSize must be > 1: ($maxCacheSize)" }
require(maxCacheAgeMins > 1) { "http.clientCache.maxCacheAgeMins must be > 1: ($maxCacheAgeMins)" }
require(maxCacheIdleMins > 1) { "http.clientCache.maxCacheIdleMins must be > 1: ($maxCacheIdleMins)" }
require(cacheCleanupIntervalMins > 1) { ... }
```

All four validations use `> 1` instead of `> 0` or `>= 1`. A cache size of 1 and duration values
of 1 minute are valid configurations that are incorrectly rejected.

**Impact:** Users cannot configure minimal cache sizes or short durations. Minor usability issue.

**Fix:** Change to `> 0` (or `>= 1`) to allow single-entry caches and 1-minute durations.

---

### 6. AgentPathManager.registerPath -- local map update not atomic with gRPC registration

**File:** `agent/AgentPathManager.kt:63-79`

```kotlin
suspend fun registerPath(pathVal: String, url: String, labels: String = "{}") {
  // ...
  val pathId = agent.grpcService.registerPathOnProxy(path, labelsJson).pathId  // gRPC call outside lock
  synchronized(pathContextMap) {
    pathContextMap[path] = PathContext(pathId, path, url, labelsJson)            // map update inside lock
  }
}
```

The gRPC call to register the path on the proxy happens outside the synchronized block. If two
coroutines call `registerPath()` with the same path concurrently, both gRPC calls may succeed on
the proxy side, but the local map will only reflect whichever writes last. The proxy-side
`addPath()` is properly synchronized, so this primarily causes a local inconsistency if the path
IDs differ.

**Impact:** Possible local map inconsistency for the path ID when paths are registered concurrently.
Unlikely in practice since path registration happens at startup.

---

### 7. ProxyPathManager.addPath logs `agentContexts[0]` without empty-list guard

**File:** `proxy/ProxyPathManager.kt:90`

```kotlin
if (agentInfo != null) {
  logger.info { "Overwriting path /$path for ${agentInfo.agentContexts[0]}" }
}
```

This assumes the `agentContexts` list is non-empty. While the code flow makes it very unlikely to
be empty (entries are always created with at least one element and removed from the map when
reaching zero), there is no structural guarantee. An `IndexOutOfBoundsException` would crash the
path registration.

**Impact:** Potential crash on an edge case. Very low probability given current code flow.

**Fix:** Use `agentInfo.agentContexts.firstOrNull()` or `agentContexts.first()` with a descriptive
message.

---

## NOTES (Not bugs, but worth reviewing)

### TrustAllX509TrustManager is a security anti-pattern

**File:** `agent/TrustAllX509TrustManager.kt`

This trust manager accepts all certificates without validation. It is gated behind the
`--trust_all_x509` flag. Consider logging a warning at startup when this is enabled, and ensure
it cannot be accidentally enabled in production.

### BaseOptions.readConfig uses Kotlin multi-dollar string interpolation

**File:** `common/BaseOptions.kt:278`

```kotlin
logger.error { $$"A configuration file or url must be specified with --config or $$$envConfig" }
```

This uses Kotlin 2.x `$$"..."` raw string interpolation syntax. The `$$$` resolves to a literal `$`
followed by the interpolated value of `envConfig`. This is correct but may confuse developers
unfamiliar with the syntax. A comment would help.

### HttpClientCache cleanup coroutine uses `while(true)` instead of `while(isActive)`

**File:** `agent/HttpClientCache.kt:53`

The infinite loop relies on `delay()` throwing `CancellationException` when the scope is cancelled.
This works correctly because `delay` is a cancellation point, but `while(isActive)` would be more
idiomatic and explicit about cancellation intent.
