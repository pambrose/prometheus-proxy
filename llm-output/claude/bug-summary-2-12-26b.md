# Bug Summary - 2/12/2026 (Comprehensive Review)

Thorough review of the prometheus-proxy codebase (`src/main/kotlin/io/prometheus/`).
This supersedes the earlier `bug-summary-2-12-26.md`. Issues from the prior report that
have since been fixed are noted at the end.

---

## HIGH Severity

### 1. AgentContext.writeScrapeRequest backlog counter race -- increment after send

**File:** `proxy/AgentContext.kt:79-82`

```kotlin
suspend fun writeScrapeRequest(scrapeRequest: ScrapeRequestWrapper) {
  scrapeRequestChannel.send(scrapeRequest)   // item available immediately
  channelBacklogSize += 1                     // increment happens AFTER send
}
```

And the corresponding read (`proxy/AgentContext.kt:84-88`):

```kotlin
suspend fun readScrapeRequest(): ScrapeRequestWrapper? =
  scrapeRequestChannel.receiveCatching().getOrNull()
    ?.apply {
      channelBacklogSize -= 1                 // decrement happens AFTER receive
    }
```

The counter is incremented *after* the message is sent to the channel. Because the channel
is `UNLIMITED`, `send()` never suspends -- the item is available to consumers immediately.
A consumer can `receive` and decrement the counter *before* the producer executes `+= 1`,
driving `channelBacklogSize` negative. The `invalidate()` drain loop (line 100-103) also
decrements the counter for each drained item, compounding the drift.

**Impact:** The `scrapeRequestBacklogSize` metric reported via health checks
(`Proxy.kt:282-294`) can show negative or inaccurate values, producing misleading
operational data and potentially wrong health check verdicts.

**Fix:** Either increment *before* send (with a try/finally to decrement on failure), or
remove the manual counter entirely and rely on channel APIs if available.

---

### 2. Boolean option assignment pattern prevents overriding to `false`

**File:** `common/BaseOptions.kt:176-179, 188-190, 194-196, 206-208` (and corresponding
methods in `AgentOptions.kt`)

```kotlin
protected fun assignAdminEnabled(defaultVal: Boolean) {
  if (!adminEnabled)                          // once true, this is never entered
    adminEnabled = ADMIN_ENABLED.getEnv(defaultVal)
}
```

This `if (!value)` guard is repeated for `adminEnabled`, `metricsEnabled`, `debugEnabled`,
`transportFilterDisabled`, `trustAllX509Certificates`, `consolidated`,
`keepAliveWithoutCalls`, and `reflectionDisabled`.

The problem: once a boolean is `true` (e.g., set via CLI flag `--admin`), the guard
prevents the env-var / config-file value from being consulted. More critically, there is
no mechanism to override a config-file `true` back to `false` via environment variable.
Setting `ADMIN_ENABLED=false` is silently ignored if the config file says `enabled = true`.

**Impact:** Configuration layering is broken for boolean options. Users cannot disable
features from a higher-priority configuration source if a lower-priority source enables
them.

**Fix:** Use a tri-state sentinel (e.g., `null` / unset) for the CLI default so the
assignment logic can distinguish "user explicitly set false" from "user didn't specify".

---

### 3. `isTlsEnabled` uses OR instead of AND -- partial TLS config treated as enabled

**File:** `common/BaseOptions.kt:123-124`

```kotlin
val isTlsEnabled: Boolean
get() = certChainFilePath.isNotEmpty() || privateKeyFilePath.isNotEmpty()
```

TLS requires *both* a certificate chain and a private key. If a user specifies only one
(e.g., `--cert /path/to/cert.pem` but forgets `--key`), `isTlsEnabled` returns `true`,
but the TLS context construction in `AgentGrpcService.kt:113-126` and
`ProxyGrpcService.kt:55-63` will attempt to build a context with a missing file, failing
with an obscure runtime error.

**Impact:** Confusing runtime failure. The agent or proxy starts up believing TLS is
enabled but cannot establish connections.

**Fix:** Either validate that both are present together (fail-fast with a clear message),
or change to `&&` so TLS only activates when fully configured.

---

## MEDIUM Severity

### 4. Orphaned chunked transfers do not fail their scrape requests

**File:** `proxy/ProxyServiceImpl.kt:277-285`

```kotlin
// Clean up any in-progress chunked contexts that were not completed with a summary
if (activeScrapeIds.isNotEmpty()) {
  val contextManager = proxy.agentContextManager
  activeScrapeIds.forEach { scrapeId ->
    contextManager.removeChunkedContext(scrapeId)
      ?.also { logger.warn { "Cleaned up orphaned ChunkedContext for scrapeId: $scrapeId" } }
  }
}
```

When the chunked response stream terminates abnormally (agent disconnect mid-transfer),
orphaned `ChunkedContext` entries are cleaned up, but the corresponding
`ScrapeRequestWrapper` in `ScrapeRequestManager` is *not* notified. The HTTP handler in
`submitScrapeRequest()` (`ProxyHttpRoutes.kt:215`) will block in its `awaitCompleted()`
loop until the full `scrapeRequestTimeoutSecs` expires.

Compare with the chunk validation failure handling on lines 227-230, which correctly calls
`proxy.scrapeRequestManager.failScrapeRequest()`.

**Impact:** When an agent disconnects during a chunked transfer, the corresponding
Prometheus scrape request hangs until the full timeout instead of failing fast.

**Fix:** Call `proxy.scrapeRequestManager.failScrapeRequest(scrapeId, reason)` for each
orphaned scrape ID, mirroring the chunk validation failure path.

---

### 5. Agent `scrapeRequestBacklogSize` can go negative on reconnect

**File:** `Agent.kt:235, 301-313`

```kotlin
// On reconnect:
scrapeRequestBacklogSize.store(0)       // line 235 -- reset to 0
```

```kotlin
// In scrape processing loop:
launch {
  scrapeRequestBacklogSize += 1       // line 303
  try {
    semaphore.withPermit { ... }
  } finally {
    scrapeRequestBacklogSize -= 1   // line 311
  }
}
```

If the connection resets (line 235 sets backlog to 0) while in-flight scrape coroutines
from the previous connection iteration have not yet reached their `finally` block, those
coroutines will decrement the counter past zero. The `store(0)` does not wait for previous
coroutines to complete.

**Impact:** Health check (`Agent.kt:372-377`) compares `scrapeRequestBacklogSize` against
a threshold. A negative value always appears healthy, masking a real backlog problem. The
Prometheus metric also reports an incorrect value.

**Fix:** Wait for all launched scrape coroutines to complete before resetting the counter,
or use a separate counter per connection iteration.

---

### 6. `AgentGrpcService.resetGrpcStubs` crash if channel creation fails on first call

**File:** `agent/AgentGrpcService.kt:146-183`

```kotlin
fun resetGrpcStubs() =
  grpcLock.withLock {
    if (grpcStarted)
      shutDownLocked()              // subsequent calls: shut down existing channel
    else
      grpcStarted = true            // first call: mark started BEFORE channel creation

    channel = channel(...)            // channel created here -- may throw
  }
```

On the first call, `grpcStarted` is set to `true` before `channel` is assigned. If the
`channel(...)` builder throws (e.g., invalid hostname, DNS failure), `grpcStarted` remains
`true` but `channel` is an uninitialized `lateinit var`. The next call enters the
`if (grpcStarted)` branch and calls `shutDownLocked()`, which invokes
`channel.shutdownNow()` on the uninitialized property, throwing
`UninitializedPropertyAccessException`.

**Impact:** After a failed first connection attempt, all subsequent connection attempts
crash instead of retrying gracefully.

**Fix:** Set `grpcStarted = true` *after* `channel` is successfully assigned, or guard
`shutDownLocked()` with `::channel.isInitialized`.

---

### 7. In-flight scrape results silently lost on agent disconnect

**File:** `agent/AgentConnectionContext.kt:46-58`

```kotlin
fun close() {
  synchronized(closeLock) {
    if (!disconnected) {
      disconnected = true
      scrapeRequestActionsChannel.cancel()   // drops buffered actions
      scrapeResultsChannel.close()           // allows drain, but...
    }
  }
}
```

When `close()` is called, `scrapeRequestActionsChannel.cancel()` terminates the `for` loop
in `Agent.kt:301`. However, `launch` coroutines (line 302) that were already spawned and
are still executing their scrape may complete their work and attempt to call
`connectionContext.sendScrapeResults()` on the closed `scrapeResultsChannel`. The `send()`
will throw `ClosedSendChannelException`, which is caught by `runCatchingCancellable` -- but
the successfully computed scrape result is silently dropped. The proxy side will time out
waiting for results that were actually computed.

**Impact:** During agent disconnects, already-computed scrape results are lost, causing
unnecessary timeouts on the proxy side.

---

### 8. Agent.run() catches all Throwables including JVM Errors

**File:** `Agent.kt:328-354`

```kotlin
while (isRunning) {
  try {
    runCatchingCancellable {
      runBlocking { connectToProxy() }
    }.onFailure { e ->
      when (e) {
          ...
        else -> {
          logger.warn(e) { "Throwable caught ${e.simpleClassName} ${e.message}" }
        }
      }
    }
  } finally {
    logger.info { "Waited ${reconnectLimiter.acquire().roundToInt().seconds} to reconnect" }
  }
}
```

`runCatchingCancellable` catches all `Throwable` including `Error` subclasses
(`OutOfMemoryError`, `StackOverflowError`). The `else` branch logs at `warn` level and
continues the reconnect loop. Critical JVM errors are swallowed, and the agent continues
running in a potentially corrupted state.

**Impact:** The agent will not terminate on fatal JVM errors, running in a degraded state
and potentially producing corrupt scrape results.

**Fix:** Re-throw `Error` subclasses (or at least `VirtualMachineError`) instead of
logging them.

---

### 9. `chunkContentSizeBytes` multiplication has no overflow protection

**File:** `agent/AgentOptions.kt:165-168`

```kotlin
if (chunkContentSizeBytes == -1)
  chunkContentSizeBytes = CHUNK_CONTENT_SIZE_KBS.getEnv(agentConfigVals.chunkContentSizeKbs)
// Multiply the value time KB
chunkContentSizeBytes *= 1024
```

The `*= 1024` multiplication is always applied unconditionally. For large values
(e.g., env var set to `2097152`), the multiplication overflows `Int.MAX_VALUE` silently,
producing a negative or incorrect value. There is no validation after the multiplication.

The field naming is also confusing: `chunkContentSizeBytes` holds a KB value until line 168
converts it. The CLI parameter description says "KBs" but the field name says "Bytes".

**Impact:** Integer overflow produces an incorrect chunk size, which could cause the
chunked transfer protocol to malfunction (e.g., sending enormous or tiny chunks).

**Fix:** Add a `require` check after the multiplication, or switch to `Long`.

---

## LOW Severity

### 10. Service discovery `__metrics_path__` missing leading slash

**File:** `Proxy.kt:409`

```kotlin
put("__metrics_path__", JsonPrimitive(path))
```

Paths are stored in `pathMap` without leading slashes (the HTTP route handler in
`ProxyHttpRoutes.kt:85` calls `call.request.path().drop(1)` to strip it). However,
Prometheus expects `__metrics_path__` to be a full path starting with `/` (e.g.,
`/metrics`, not `metrics`).

**Impact:** Some Prometheus SD implementations may not correctly construct scrape URLs
from the path without a leading slash. Standard Prometheus behavior typically adds a `/`,
so this may work in practice but violates the convention.

**Fix:** Prepend `/` to the path: `put("__metrics_path__", JsonPrimitive("/$path"))`.

---

### 11. `registerPath` error message reports "Invalid agentId" for consolidated mismatch

**File:** `proxy/ProxyServiceImpl.kt:117-118`

```kotlin
if (!isValid) {
  reason = "Invalid agentId: ${request.agentId} (registerPath)"
}
```

When `addPath()` returns `false` due to a consolidated/non-consolidated mismatch
(`ProxyPathManager.kt:75-80` or `84-86`), the response sent back to the agent says
"Invalid agentId" -- which is incorrect and confusing. The agentId is valid; the path
registration was rejected due to a mode mismatch.

**Impact:** Misleading error message makes debugging consolidated path issues harder.

**Fix:** Have `addPath()` return a result object that includes the rejection reason, and
propagate it to the response.

---

### 12. `runBlocking` in health check callback can stall admin service

**File:** `Agent.kt:383-384`

```kotlin
healthCheck {
  val currentSize = runBlocking { agentHttpService.httpClientCache.getCacheStats().totalEntries }
```

`runBlocking` inside a health check callback blocks the health check thread while waiting
to acquire `accessMutex` inside `getCacheStats()`. If the mutex is held by the cleanup
coroutine or a concurrent `getOrCreateClient` call, the health check can stall for the
duration of the cleanup or I/O operation.

**Impact:** Health check responses may be delayed or time out under load, producing false
negative health results.

**Fix:** Use a non-blocking approach (e.g., cache the stats atomically and read the
cached value in the health check).

---

### 13. Consolidated OpenMetrics responses may be malformed -- FIXED

**File:** `proxy/ProxyHttpRoutes.kt:153`

```kotlin
contentText = results.joinToString("\n") { it.contentText },
```

When consolidated paths have multiple agents, metrics from all agents are joined with a
single newline. If any agents return OpenMetrics format (which requires a `# EOF` marker
at the end of the stream), the joined response will contain `# EOF` markers in the middle,
producing an invalid OpenMetrics document.

**Impact:** Malformed response for consolidated endpoints using OpenMetrics format. Does
not affect the traditional Prometheus text exposition format.

**Fix:** Added `mergeContentTexts()` helper that strips trailing `# EOF` markers from each
result, joins the stripped content, and appends a single `# EOF` at the end if any result
contained one.

---

### 14. `AgentPathManager.registerPath` -- timing window during registration

**File:** `agent/AgentPathManager.kt:77-82`

```kotlin
suspend fun registerPath(pathVal: String, url: String, labels: String = "{}") {
  // ...
  val pathId = agent.grpcService.registerPathOnProxy(path, labelsJson).pathId  // gRPC call
  pathMutex.withLock {
    pathContextMap[path] = PathContext(pathId, path, url, labelsJson)         // local update
  }
}
```

The gRPC call happens outside the mutex. After the proxy registers the path but before the
local map is updated, a scrape request for that path could arrive. The agent's
`fetchScrapeUrl` calls `agent.pathManager[path]` (which reads from `pathContextMap`
without the mutex), gets `null`, and returns an "invalid path" error -- even though the
proxy knows the path is valid.

**Impact:** Brief timing window during path registration can cause spurious "invalid path"
errors. Very unlikely in practice since registration happens at startup.

---

### 15. `ScrapeRequestManager.assignScrapeResults` has no double-assignment protection -- FIXED

**File:** `proxy/ScrapeRequestManager.kt:43-50`

```kotlin
fun assignScrapeResults(scrapeResults: ScrapeResults) {
  val scrapeId = scrapeResults.srScrapeId
  scrapeRequestMap[scrapeId]
    ?.also { wrapper ->
      wrapper.scrapeResults = scrapeResults
      wrapper.markComplete()
      wrapper.agentContext.markActivityTime(true)
    } ?: logger.error { "Missing ScrapeRequestWrapper for scrape_id: $scrapeId" }
}
```

If called twice with the same `scrapeId` (e.g., due to gRPC retries or agent bugs), the
second call overwrites `scrapeResults` and calls `markComplete()` again.
`markComplete()` invokes `requestTimer?.observeDuration()`, recording a duplicate latency
observation and skewing metrics.

**Impact:** Latency metrics can be inaccurate if duplicate responses are received. Low
probability but no protection against it.

**Fix:** Added an `AtomicBoolean` guard in `ScrapeRequestWrapper.markComplete()` using
`compareAndSet(false, true)` so `observeDuration()` and `closeChannel()` are only called
once, even if `markComplete()` is invoked multiple times.

---

## NOTES

### ProxyOptions lacks `exitOnMissingConfig` parameter

**File:** `proxy/ProxyOptions.kt:42`

`ProxyOptions` does not accept an `exitOnMissingConfig` parameter and always uses the
default `false`. Compare with `AgentOptions` which accepts and passes it through. The
proxy's `main()` function creates `ProxyOptions(args)` directly, so it will silently use
default config values if no config file is specified, rather than exiting with an error.

### `ProxyPathManager.addPath` orphan invalidation may be aggressive -- FIXED

**File:** `proxy/ProxyPathManager.kt:96-104`

When a non-consolidated agent overwrites a path, displaced agent contexts with no other
registered paths are immediately invalidated. If the displaced agent was in the process of
registering additional paths concurrently, the invalidation disconnects it prematurely.

**Fix:** Added `&& displacedContext.isNotValid()` to the invalidation guard so only
dead/disconnected agents are invalidated on displacement. Live agents that may still be
mid-registration are left alone and cleaned up later by heartbeat eviction.

### Agent's `scrapeResultsChannel` drain on disconnect

**File:** `agent/AgentConnectionContext.kt:54`

Using `close()` (not `cancel()`) for `scrapeResultsChannel` allows buffered results to
drain. This is correct and intentional per the comment, but it means the
`writeResponsesToProxy` gRPC stream will continue sending results to the proxy even after
the logical disconnect. This is generally desirable (avoid wasting computed results).

---

## Previously Reported Issues -- Now Fixed

The following issues from `bug-summary-2-12-26.md` have been fixed in the current code:

1. **HttpClientCache.removeEntry leak** (was #1 HIGH) -- Fixed: `removeEntry()` now uses
   `markForClose()` + `pendingCloses` pattern (`HttpClientCache.kt:225-234`). Idle entries
   are added to `pendingCloses` and closed outside the mutex.

2. **Chunk validation failure timeout** (was #2 HIGH) -- Fixed: The catch block now calls
   `proxy.scrapeRequestManager.failScrapeRequest()` (`ProxyServiceImpl.kt:227-230`).

3. **AgentOptions cache validations reject size=1** (was #5 LOW) -- Fixed: Validations
   now use `> 0` instead of `> 1` (`AgentOptions.kt:247,252,257,262`).

4. **ProxyPathManager.addPath `agentContexts[0]`** (was #7 LOW) -- Fixed: Now uses
   `firstOrNull()` (`ProxyPathManager.kt:90`).

5. **SslSettings password zeroing** (was #4 MEDIUM) -- Was already handled: the internal
   `getKeyStore` overload zeros the password in a `finally` block
   (`SslSettings.kt:46`). The public overload passes `password.toCharArray()`, so the
   original `String` is unaffected (Strings are immutable).

6. **Consolidated OpenMetrics EOF markers** (#13 LOW) -- Fixed: `mergeContentTexts()` in
   `ProxyHttpRoutes.kt` strips intermediate `# EOF` markers and appends a single one at the
   end when consolidating multiple agent responses.

7. **ScrapeRequestWrapper double markComplete** (#15 LOW) -- Fixed: `markComplete()` in
   `ScrapeRequestWrapper.kt` uses an `AtomicBoolean` guard so `observeDuration()` is only
   called once.

8. **ProxyPathManager orphan invalidation too aggressive** (NOTE) -- Fixed: Added
   `&& displacedContext.isNotValid()` guard so only dead agents are invalidated on path
   displacement. Live agents mid-registration are left alone (`ProxyPathManager.kt:105`).

9. **HttpClientCache cleanup `while(true)`** (was NOTE) -- Fixed: Now uses
   `while(isActive)` (`HttpClientCache.kt:59`).
