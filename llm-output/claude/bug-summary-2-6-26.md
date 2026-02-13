# Bug Review: prometheus-proxy

## BUG-1: `IOException` mapped to 404 Not Found instead of 503 Service Unavailable

**File:** `ScrapeResults.kt:124-127`

When an `IOException` occurs during a scrape (e.g., connection refused, DNS failure, network unreachable), the error code returned is `NotFound.value` (404). This is semantically incorrect -- a 404 means the resource doesn't exist, while an `IOException` means the agent couldn't reach the target. The correct status would be `ServiceUnavailable` (503).

```kotlin
is IOException -> {
  logger.warn { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
  NotFound.value  // Should be ServiceUnavailable.value
}
```

**Impact:** Prometheus may interpret 404 responses as "endpoint doesn't exist" and stop scraping, rather than retrying a temporarily unavailable target.

---

## BUG-2: `EnvVars.getEnv()` throws uncaught exceptions on malformed input

**File:** `EnvVars.kt:81-85`

The `getEnv(Int)` and `getEnv(Long)` overloads call `.toInt()` and `.toLong()` on environment variable values with no error handling. If an operator sets e.g. `PROXY_PORT=abc`, the application crashes with a `NumberFormatException` during startup with no helpful error message.

```kotlin
fun getEnv(defaultVal: Int) = getenv(name)?.toInt() ?: defaultVal
fun getEnv(defaultVal: Long) = getenv(name)?.toLong() ?: defaultVal
```

**Impact:** Deployment failures with opaque stack traces when environment variables contain invalid numeric values.

---

## BUG-3: `SslSettings.getTrustManagerFactory()` returns nullable but never returns null

**File:** `SslSettings.kt:41-48`

`getTrustManagerFactory()` is declared as returning `TrustManagerFactory?` but its body always returns a non-null value (the `.apply{}` block returns the receiver). This cascading nullability causes:

1. `getSslContext()` at line 56 passes `?.trustManagers` (unnecessarily nullable) to `SSLContext.init()`.
2. `getTrustManager()` at line 63 uses `?.trustManagers?.first{...} as X509TrustManager` -- if the `first{}` predicate finds no match, it throws `NoSuchElementException` (not a cast failure). The unsafe cast `as X509TrustManager` will succeed only if `first{}` succeeds.

```kotlin
fun getTrustManagerFactory(...): TrustManagerFactory? =  // Should be non-nullable
    TrustManagerFactory.getInstance(...)
      .apply { init(getKeyStore(fileName, password)) }
```

**Impact:** Misleading API that forces callers to handle nulls that can never occur, and masks real failure modes (missing trust manager type).

---

## BUG-4: `AgentGrpcService` hostname parsing breaks on IPv6 addresses

**File:** `AgentGrpcService.kt:101-108`

The proxy hostname is split on `:` to extract host and port. This fails for IPv6 addresses like `[::1]:50051` or `[2001:db8::1]:50051`, producing incorrect host/port values.

```kotlin
if (":" in schemeStripped) {
  val vals = schemeStripped.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
  agentHostName = vals[0]
  agentPort = Integer.valueOf(vals[1])
}
```

For input `[::1]:50051`, this splits into `["[", "", "1]", "50051"]`, setting `agentHostName = "["` and `agentPort = 0` (empty string after first colon converted via Integer.valueOf on an empty string -- actually crashes with NumberFormatException).

**Impact:** Agent cannot connect to proxy when using IPv6 addresses.

---

## BUG-5: Same IPv6 parsing issue in `AgentOptions`

**File:** `AgentOptions.kt:137-140`

The same colon-based host:port detection is used for configuration hostname resolution:

```kotlin
val str = if (":" in configHostname)
  configHostname
else
  "$configHostname:${agentConfigVals.proxy.port}"
```

An IPv6 address without a port (e.g., `::1`) contains colons and would be treated as already having a port, skipping the port append. A bracketed IPv6 like `[::1]` also contains colons.

**Impact:** Misconfigured proxy hostname when using IPv6 in configuration files.

---

## BUG-6: `chunkContentSizeKbs` is multiplied by 1024 even when set via CLI

**File:** `AgentOptions.kt:161-164`

The KB-to-byte conversion `chunkContentSizeKbs *= 1024` runs unconditionally after the config/env assignment block. If the value was set via the `--chunk` CLI argument (which bypasses the `if (chunkContentSizeKbs == -1)` guard), the user-provided value is still multiplied. But the parameter description says "KBs", so if a user passes `--chunk 32`, they get `32768` bytes, which is correct.

However, if the same option is set via the `CHUNK_CONTENT_SIZE_KBS` environment variable and the config file value is already in KBs (which it is -- the config says `chunk-content-size-kbs`), the multiplication is correct. **The real bug is: the field name says `Kbs` but after line 164 it holds bytes.** This is confusing and error-prone for any future code that reads `chunkContentSizeKbs` expecting KB units.

**Impact:** Misleading field name; low risk of actual runtime bug but high risk of future maintenance errors.

---

## BUG-7: Non-atomic multi-field update in `AgentContext.assignProperties()`

**File:** `AgentContext.kt:72-77`

Four properties are updated sequentially without synchronization:

```kotlin
fun assignProperties(request: RegisterAgentRequest) {
  launchId = request.launchId
  agentName = request.agentName
  hostName = request.hostName
  consolidated = request.consolidated
}
```

While each individual property uses an atomic delegate, a concurrent reader (e.g., the cleanup service iterating `agentContextMap`, or the service discovery JSON builder reading `agentName`/`hostName`) could see a partially-updated state where some fields are from the old agent registration and some from the new one.

**Impact:** Potentially inconsistent agent metadata in service discovery responses or log messages during agent re-registration. Low probability in practice because registration happens once per connection.

---

## BUG-8: `ProxyPathManager.AgentContextInfo.agentContexts` is a `MutableList` accessed without synchronization

**File:** `ProxyPathManager.kt:38`

```kotlin
class AgentContextInfo(
  val isConsolidated: Boolean,
  val labels: String,
  val agentContexts: MutableList<AgentContext>,  // Exposed mutable list
)
```

This list is modified in `addPath()` (line 76: `agentInfo.agentContexts += agentContext`), `removePath()` (line 115: `agentInfo.agentContexts.remove(agentContext)`), and `removeFromPathManager()` (line 154: `v.agentContexts.removeIf { ... }`).

These mutations happen inside `synchronized(pathMap)` blocks, which is good. However, `getAgentContextInfo()` at line 48 returns the `AgentContextInfo` without synchronization, and the caller in `ProxyHttpRoutes.executeScrapeRequests()` at line 172 iterates `agentContextInfo.agentContexts` outside any lock. If an agent disconnects during a scrape, the list could be modified concurrently.

**Impact:** Potential `ConcurrentModificationException` during scrape request fan-out if an agent disconnects at the same time. Race window is small but exists under load.

---

## BUG-9: `ScrapeRequestWrapper.scrapeResults` accessed before assignment

**File:** `ScrapeRequestWrapper.kt:62`

```kotlin
var scrapeResults: ScrapeResults by nonNullableReference()
```

This property has no initial value. It's assigned in `ScrapeRequestManager.assignScrapeResults()` (line 43). If the scrape times out and the code at `ProxyHttpRoutes.kt:228` (`scrapeRequest.scrapeResults`) is reached before `assignScrapeResults()` is called, it would throw an `IllegalStateException`.

In practice, the timeout path at lines 213-218 returns early before reaching line 228, so this is safe. But the code relies on control flow rather than type safety to prevent the error.

**Impact:** No runtime bug currently, but fragile -- a refactoring that changes the early-return logic could introduce an `IllegalStateException`.

---

## BUG-10: `buildServiceDiscoveryJson()` reads `agentContexts` list without synchronization

**File:** `Proxy.kt:416`

```kotlin
val agentContexts = agentContextInfo.agentContexts
put("agentName", JsonPrimitive(agentContexts.joinToString { it.agentName }))
put("hostName", JsonPrimitive(agentContexts.joinToString { it.hostName }))
```

This iterates the mutable `agentContexts` list from `AgentContextInfo` without holding the `pathMap` lock. If an agent disconnects concurrently, the list may be modified during iteration.

**Impact:** `ConcurrentModificationException` in the service discovery endpoint under concurrent agent disconnect/reconnect.

---

## BUG-11: `AgentGrpcService.processScrapeResults()` variable shadowing in while loop

**File:** `AgentGrpcService.kt:338`

```kotlin
while (bais.read(buffer).also { bytesRead -> readByteCount = bytesRead } > 0) {
```

The lambda parameter is named `bytesRead` but the outer scope variable being assigned is `readByteCount`. The `also` lambda receives the result of `bais.read(buffer)` as `bytesRead`, then assigns it to `readByteCount`. This works correctly but the naming mismatch (`bytesRead` vs `readByteCount`) suggests a copy-paste issue. The variable `readByteCount` on line 336 is declared but the `also` block's `bytesRead` parameter shadows nothing -- it's just confusing.

**Impact:** No runtime bug, but the naming confusion could lead to maintenance errors.

---

## BUG-12: `AgentContextCleanupService` iterates a `ConcurrentMap` while removing entries

**File:** `AgentContextCleanupService.kt:46-57`

```kotlin
proxy.agentContextManager.agentContextMap
  .forEach { (agentId, agentContext) ->
    ...
    proxy.removeAgentContext(agentId, "Eviction")
    ...
  }
```

`removeAgentContext()` calls `agentContextMap.remove(agentId)`. While `ConcurrentHashMap.forEach` is weakly consistent and won't throw `ConcurrentModificationException`, the iteration may skip entries or visit entries that were already removed by another thread (e.g., `transportTerminated()` running concurrently).

**Impact:** Minor -- could double-log eviction messages or skip checking some agents in a single pass. Self-correcting on next iteration.

---

## BUG-13: Duplicate `Cache-Control` header in HTTP responses

**File:** `ProxyHttpRoutes.kt:86` and `ProxyUtils.kt:127`

The `handleClientRequests()` handler at line 86 sets:
```kotlin
call.response.header(HttpHeaders.CacheControl, CACHE_CONTROL_VALUE)
```

Then `respondWith()` at `ProxyUtils.kt:127` sets it again:
```kotlin
response.header(HttpHeaders.CacheControl, CACHE_CONTROL_VALUE)
```

This results in the `Cache-Control` header being sent twice in every response.

**Impact:** Technically valid HTTP (duplicate headers) but wasteful and could confuse strict HTTP clients or proxies.
