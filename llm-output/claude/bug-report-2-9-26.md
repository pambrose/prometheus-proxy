# Bug Report - Prometheus Proxy Codebase Review

**Date:** February 10, 2026
**Scope:** Full source code review of `src/main/kotlin/io/prometheus/` and `src/test/kotlin/io/prometheus/`
**Reviewer:** Claude Code

---

## Summary

| Severity  | Count  |
|-----------|--------|
| High      | 6      |
| Medium    | 11     |
| Low       | 14     |
| **Total** | **31** |

---

## High Severity

### H1: ChunkedContext memory leak on gRPC stream cancellation -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:183-249`
**Related:** `src/main/kotlin/io/prometheus/proxy/AgentContextManager.kt:33-34`

In `writeChunkedResponsesToProxy`, when a "header" message arrives, a `ChunkedContext` is created and placed in
`chunkedContextMap`. It is removed when the "summary" message arrives. If the gRPC stream is cancelled or the agent
disconnects between sending the header and the summary, the `onFailure` handler logs the error but does **not** clean up
`chunkedContextMap`. Each leaked `ChunkedContext` retains a `ByteArrayOutputStream` with accumulated chunk data. There
is no periodic cleanup mechanism for stale entries.

**Impact:** Unbounded memory leak under adverse network conditions (agent disconnections during chunked transfers).

**Suggested fix:** Add cleanup logic in the `onFailure` block to remove in-progress `ChunkedContext` entries for the
disconnecting agent, or add a periodic sweep for stale entries keyed by age.

---

### H2: Single exception in `writeResponsesToProxy` kills the entire result stream -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:166-181`

```kotlin
override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
  runCatchingCancellable {
    requests.collect { response ->
      val scrapeResults = response.toScrapeResults()
      proxy.scrapeRequestManager.assignScrapeResults(scrapeResults)
    }
  }.onFailure { ... }
  return EMPTY_INSTANCE
}
```

If `toScrapeResults()` or `assignScrapeResults()` throws for a single message, `runCatchingCancellable` catches it and
flow collection stops entirely. All subsequent scrape results in the stream are lost. The method returns
`EMPTY_INSTANCE` as if everything succeeded, so the agent sees a successful completion and continues operating -- but
the proxy has stopped collecting results. All pending `ScrapeRequestWrapper` entries for in-flight scrapes will time
out.

**Impact:** A single malformed scrape response silently halts all result processing for an agent until reconnection.

**Suggested fix:** Wrap individual message processing in try-catch so one bad message doesn't kill the stream:

```kotlin
requests.collect { response ->
  runCatching {
    proxy.scrapeRequestManager.assignScrapeResults(response.toScrapeResults())
  }.onFailure { e -> logger.error(e) { "Failed to process scrape response" } }
}
```

---

### H3: No deadlines on agent unary gRPC calls -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt` (multiple call sites)

None of the unary gRPC calls from agent to proxy (`registerAgent`, `registerPath`, `unregisterPath`, `pathMapSize`,
`sendHeartBeat`) have deadlines set. If the proxy becomes unresponsive (GC pause, network partition that doesn't break
TCP, server overload), these calls hang indefinitely. This is especially dangerous for `registerAgent` (blocks
connection setup) and `sendHeartBeat` (blocks heartbeat loop, preventing connectivity detection).

**Impact:** Agent can hang indefinitely on any unary RPC, preventing reconnection to a healthy proxy.

**Suggested fix:** Apply deadlines to the stub:

```kotlin
val deadlineStub = grpcStub.withDeadlineAfter(30, SECONDS)
```

---

### H4: No disconnect cleanup when `transportFilterDisabled = true` -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:68-82`

When `transportFilterDisabled = true`, the `ProxyServerTransportFilter` is not installed, so `transportTerminated()` is
never called. Agent contexts are never removed on disconnect. The `AgentContextCleanupService` will eventually evict
stale agents, but there is a window where disconnected agents leave orphaned `AgentContext` entries with associated
paths in `ProxyPathManager`. HTTP requests routed to these orphaned contexts will time out because the gRPC stream is
broken.

**Impact:** Orphaned agent contexts accumulate and cause scrape timeouts until the cleanup service evicts them.

**Suggested fix:** When `transportFilterDisabled` is true, detect stream termination in `readRequestsFromProxy` or
`writeResponsesToProxy` and trigger cleanup from the proxy side.

---

### H5: Tautological test assertions (compare value to itself) -- FIXED

Three tests contain assertions that compare a property to itself (`x shouldBe x`), which always pass regardless of the
actual value:

**File:** `src/test/kotlin/io/prometheus/common/ConfigWrappersTest.kt:117`

```kotlin
zipkinConfig.port shouldBe zipkinConfig.port // non-negative
```

Fixed: `zipkinConfig.port shouldBeGreaterThan 0`

**File:** `src/test/kotlin/io/prometheus/agent/AgentOptionsTest.kt:162`

```kotlin
options.configVals.agent.name shouldBe options.configVals.agent.name // non-null access
```

Fixed: `options.configVals.agent.name.shouldNotBeNull()`

**File:** `src/test/kotlin/io/prometheus/agent/AgentHttpServiceTest.kt:141`

```kotlin
service.httpClientCache shouldBe service.httpClientCache // exists and is stable
```

Fixed: `service.httpClientCache.shouldNotBeNull()`

**Impact:** These tests provide false confidence -- they pass even if the values are incorrect.

---

### H6: Incorrect hashCode contract test -- FIXED

**File:** `src/test/kotlin/io/prometheus/agent/AgentContextTest.kt:336-347`

```kotlin
if (context1 == context2) {
  context1.hashCode() shouldBe context2.hashCode()
} else {
  context1.hashCode() shouldNotBe context2.hashCode()  // WRONG
}
```

The `else` branch asserts that unequal objects must have different hash codes. This violates the Java/Kotlin hashCode
contract: unequal objects **may** have the same hash code (collisions are legal). While unlikely to fail with UUID-based
IDs, the test logic is fundamentally incorrect.

**Impact:** Encodes a wrong invariant; could produce flaky failures on hash collision.

**Suggested fix:** Remove the `else` branch. The hashCode contract only guarantees `equal => same hashCode`.

---

## Medium Severity

### M1: Unsafe cast of `List` to `MutableList` in ProxyPathManager -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:79, 125, 161`

`AgentContextInfo.agentContexts` is typed as `List<AgentContext>` but is cast to `MutableList` at multiple sites:

```kotlin
(agentInfo.agentContexts as MutableList) += agentContext
```

This relies on the implementation detail that the list was created with `mutableListOf()`. The defensive copy in
`getAgentContextInfo()` returns a truly immutable list via `.toList()`, so if any code path attempted the mutable cast
on the copy, it would throw `ClassCastException`.

**Suggested fix:** Change the type to `MutableList<AgentContext>` to make the mutable usage explicit.

---

### M2: Fragile string-based dispatch on protobuf oneOf case names -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:188-238`

```kotlin
when (ooc.name.lowercase()) {
  "header" -> {
    ...
  }
  "chunk" -> {
    ...
  }
  "summary" -> {
    ...
  }
  else -> {
    error("Invalid field name")
  }
}
```

Matches on lowercased enum names instead of enum constants. If a `CHUNKONEOF_NOT_SET` message arrives (empty/default
protobuf), it hits the `else` branch and throws `IllegalStateException`, crashing the entire chunked response stream for
that agent.

**Suggested fix:** Match on enum constants directly and handle `NOT_SET` gracefully:

```kotlin
when (response.chunkOneOfCase) {
  ChunkedScrapeResponse.ChunkOneOfCase.HEADER -> {
    ...
  }
  ChunkedScrapeResponse.ChunkOneOfCase.CHUNK -> {
    ...
  }
  ChunkedScrapeResponse.ChunkOneOfCase.SUMMARY -> {
    ...
  }
  ChunkedScrapeResponse.ChunkOneOfCase.CHUNKONEOF_NOT_SET ->
    logger.warn { "Received empty chunked response" }
}
```

---

### M3: Orphaned scrape requests delay HTTP responses on agent disconnect -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/AgentContextManager.kt:72-85`
**Related:** `src/main/kotlin/io/prometheus/proxy/AgentContext.kt:94-97`

When an agent is removed, `invalidate()` sets `valid = false` and closes the scrape request channel. Pending
`ScrapeRequestWrapper` instances already in the channel are abandoned. HTTP handler threads waiting on `awaitCompleted`
for these requests will wait until the full scrape timeout expires.

**Suggested fix:** When invalidating an agent context, drain the scrape request channel and call `closeChannel()` on
each pending wrapper so HTTP handlers are notified immediately.

---

### M4: `parseHostPort` throws unhandled `NumberFormatException` for non-numeric ports -- FIXED

**File:** `src/main/kotlin/io/prometheus/common/Utils.kt:98, 121`

Port string is parsed with `.toInt()` with no validation or error handling. Input like `"host:abc"` or `"host:99999"`
throws `NumberFormatException` or produces an out-of-range port with no warning.

**Suggested fix:** Use `toIntOrNull()` and throw a descriptive `IllegalArgumentException`, and validate the port is in
0-65535.

---

### M5: `System.setProperty` side effects in `readConfig` are global and never cleaned up -- FIXED

**File:** `src/main/kotlin/io/prometheus/common/BaseOptions.kt:240-248`

`readConfig` calls `System.setProperty(k, qval)` for each dynamic parameter, mutating global JVM state. These properties
are never restored. In testing or multi-instance scenarios, properties from a previous invocation leak into subsequent
ones.

**Suggested fix:** Avoid `System.setProperty` since the config override via `ConfigFactory.parseString` is already
applied to the config object, or restore original values during shutdown.

---

### M6: `HttpClient.close()` called while holding `accessMutex` in HttpClientCache -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/HttpClientCache.kt:88-92`

`CacheEntry.onDoneWithClient()` calls `client.close()` (a potentially slow I/O operation) while the `accessMutex` is
held. This blocks all other cache operations for the duration of the HTTP client shutdown.

**Suggested fix:** Collect entries needing close into a list, release the mutex, then close them outside the lock.

---

### M7: `HttpClientCache.close()` forcibly closes in-use HTTP clients -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/HttpClientCache.kt:223-233`

The `close()` method calls `client.close()` on all cached clients, including those with `inUseCount > 0`. In-flight HTTP
requests will fail abruptly with I/O exceptions.

**Suggested fix:** Mark entries for close and wait for in-flight operations to complete, or accept as intentional
shutdown behavior and suppress resulting errors.

---

### M8: `Channel.cancel()` discards buffered scrape results in AgentConnectionContext -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentConnectionContext.kt:50-51`

```kotlin
scrapeRequestActionsChannel.cancel()
scrapeResultsChannel.cancel()
```

`cancel()` on an `UNLIMITED` channel discards all buffered items. Computed scrape results waiting in
`scrapeResultsChannel` are silently dropped. The proxy will time out waiting for these results.

**Suggested fix:** Use `close()` instead of `cancel()` on `scrapeResultsChannel` to allow draining already-queued
results.

---

### M9: `channel.shutdownNow()` without `awaitTermination` causes resource leaks -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt:130`

In `shutDown()` and `resetGrpcStubs()`, `channel.shutdownNow()` is called but `awaitTermination()` is never called. A
new channel is created immediately after, before the old channel's resources (threads, connections) are fully released.
Under rapid reconnection cycles, this can accumulate resources.

**Suggested fix:**

```kotlin
channel.shutdownNow()
channel.awaitTermination(5, SECONDS)
```

---

### M10: `error()` in AgentClientInterceptor throws wrong exception type for gRPC context -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentClientInterceptor.kt:60`

When the `AGENT_ID` header is missing, `error("Headers missing AGENT_ID key")` throws `IllegalStateException` inside a
gRPC interceptor callback. This may cause the channel to enter an unexpected error state rather than cleanly failing the
single RPC.

**Suggested fix:** Throw a `StatusRuntimeException` instead:

```kotlin
throw StatusRuntimeException(
  Status.INTERNAL.withDescription("Headers missing AGENT_ID key")
)
```

---

### M11: Test name does not match assertion in EnvVarsTest -- FIXED

**File:** `src/test/kotlin/io/prometheus/common/EnvVarsTest.kt:200-203`

```kotlin
fun `EnvVars enum should have exactly 38 entries`() {
  EnvVars.entries.size shouldBe 43
}
```

Test name says "38" but assertion checks for 43.

**Suggested fix:** Update the test name to `EnvVars enum should have exactly 43 entries`.

---

## Low Severity

### L1: Malformed IPv6 with unclosed bracket silently propagated -- FIXED

**File:** `src/main/kotlin/io/prometheus/common/Utils.kt:91-93`

Input like `"[::1"` (unclosed bracket) is returned as-is in the host field, which will likely cause downstream
connection failures with an unhelpful error.

**Suggested fix:** Throw `IllegalArgumentException` for malformed IPv6 notation.

---

### L2: Hardcoded gRPC default values in log messages -- FIXED

**File:** `src/main/kotlin/io/prometheus/common/BaseOptions.kt:157-169`

Log messages hardcode `"default (7200)"` and `"default (20)"` for keepalive values. These may not match actual gRPC
library defaults and are confusing when the sentinel value `-1L` means "use gRPC default."

---

### L3: Deprecated `URLDecoder.decode(String, String)` overload -- FIXED

**File:** `src/main/kotlin/io/prometheus/common/Utils.kt:50-51`

Uses `URLDecoder.decode(encodedQueryParams, UTF_8.name())` instead of the `Charset` overload preferred since Java 10.
Functional but not idiomatic for Java 17+.

---

### L4: Unsafe cast of SLF4J root logger to Logback `Logger` -- FIXED

**File:** `src/main/kotlin/io/prometheus/common/Utils.kt:71`

```kotlin
val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
```

Throws `ClassCastException` if the SLF4J binding is not Logback.

**Suggested fix:** Use `as?` with a warning log when the cast fails.

---

### L5: `readRequestsFromProxy` silently returns empty flow on missing AgentContext -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:156-164`

If `getAgentContext(request.agentId)` returns null, the flow completes silently with no logging and no error to the
agent. The agent sees a completed stream and reconnects with no diagnostic information.

**Suggested fix:** Log a warning and throw `StatusException(Status.NOT_FOUND)`.

---

### L6: `AgentContext.markActivityTime` compound writes are not atomic -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/AgentContext.kt:44-54`

`markActivityTime` writes both `lastActivityTimeMark` and `lastRequestTimeMark` using `nonNullableReference` delegates (
likely backed by `AtomicReference`). The two writes are not atomic with respect to concurrent reads from
`inactivityDuration` or `lastRequestDuration`.

---

### L7: `ScrapeRequestWrapper.awaitCompleted` returns true on channel close regardless of actual completion -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ScrapeRequestWrapper.kt:77-81`

If the `finally` block closes the channel (without `markComplete()` being called), `awaitCompleted` returns `true` even
though `scrapeResults` was never assigned. Mitigated by a downstream null check.

---

### L8: `LinkedHashMap` used without access-ordering in HttpClientCache -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/HttpClientCache.kt:48, 193-201`

Comment says "maintain access order" but the default `LinkedHashMap()` constructor uses insertion order. LRU eviction
uses `minByOrNull` (O(n) scan), so a plain `HashMap` would work identically.

---

### L9: `result!!` in `fetchContent` could produce confusing NPE -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:109-116`

If Ktor's `get()` somehow returns without calling the response handler, `result!!` throws `KotlinNullPointerException`.
Caught by `runCatching` but produces a confusing error message.

**Suggested fix:** Use `requireNotNull(result) { "Response handler was not called for $url" }`.

---

### L10: Cleanup coroutine in HttpClientCache silently dies on exception -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/HttpClientCache.kt:52-61`

The `while (true)` cleanup coroutine has no error handling. An unexpected exception kills the coroutine silently with no
restart or logging.

---

### L11: Mutable `var` in `data class CacheEntry` affects `equals`/`hashCode` -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/HttpClientCache.kt:63-67`

`CacheEntry` is a `data class` with a mutable `lastAccessedAt` field, which participates in `equals()`/`hashCode()`. If
ever used as a map key or set element, mutation would make it unfindable.

---

### L12: `SslSettings.getTrustManager` throws unhelpful `NoSuchElementException` -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/SslSettings.kt:59-63`

Uses `.first { it is X509TrustManager }` which throws `NoSuchElementException` with no context if no X509TrustManager is
found.

**Suggested fix:** Use `firstOrNull { ... } ?: throw IllegalStateException("No X509TrustManager found")`.

---

### L13: Misleading comment in HttpClientCacheTest -- FIXED

**File:** `src/test/kotlin/io/prometheus/agent/HttpClientCacheTest.kt:121`

Comment says "All should be different clients" but assertions verify they are the **same** client. The assertions are
correct; the comment is wrong.

---

### L14: Typo "Overide" in configuration comment -- FIXED

**File:** `config/config.conf:145` and `src/main/java/io/prometheus/common/ConfigVals.java:321`

```
overrideAuthority = ""  // Overide authority
```

Should be "Override authority".

---

## Notes

- Bugs H5 and H6 are test-only issues that don't affect production runtime but reduce test reliability.
- Bugs M3 and M8 describe related symptoms: both involve lost/orphaned scrape requests during agent disconnection.
- Bug H4 compounds with proxy bug M3: when `transportFilterDisabled` is true, orphaned contexts persist longer since
  there is no transport-level cleanup trigger.
- Several low-severity items (L3, L4, L8, L11) are code quality issues rather than functional bugs.
