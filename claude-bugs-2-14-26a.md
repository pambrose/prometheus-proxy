# Prometheus Proxy Bug Review — 2/14/26a

## Confirmed Bugs (Fixed)

### 1. `Utils.setLogLevel()` missing "all" level — FIXED

**File:** `src/main/kotlin/io/prometheus/common/Utils.kt:60-73`

The config documentation (`config/config.conf:9,116`) lists `"all"` as a valid log level, but `setLogLevel()`
didn't handle it — it threw `IllegalArgumentException` at runtime.

**Fix:** Added `"all" -> Level.ALL` to the `when` expression in `setLogLevel()`.

**Tests:** Added `"all"` to existing valid-level and case-insensitive tests; added dedicated test verifying
`Level.ALL` is set on the root logger (`UtilsTest.kt`).

### 2. `scrapeRequestBacklogSize` can go negative during disconnect — FIXED

**File:** `src/main/kotlin/io/prometheus/Agent.kt`, `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`

Both the `connectionContext.close()` drain and the scrape-processing `finally` block can decrement the counter for
the same request. When `close()` drains an item from the channel AND the coroutine that already consumed that item
completes its `finally` block, the counter is double-decremented. This produces misleading negative metric values
until reconnect resets it to 0.

**Fix:** Added `decrementBacklog(delta)` method using a CAS loop that clamps at zero. Replaced all bare `-=`
operations on `scrapeRequestBacklogSize` with `decrementBacklog()` in both `Agent.kt` and `AgentGrpcService.kt`.

**Tests:** 4 tests in `AgentTest.kt` — clamp at zero, normal decrement, from-zero safety, concurrent decrement
with 100 coroutines.

### 3. Trailing brace typo in test path — FIXED

**File:** `src/test/kotlin/io/prometheus/harness/support/BasicHarnessTests.kt:113`

```kotlin
val path = "test-$i}"  // was — stray "}" character
val path = "test-$i"   // fixed — matches addRemovePathsTest at line 69
```

**Fix:** Removed the stray `}` character. Both `addRemovePathsTest` and `threadedAddRemovePathsTest` now use
the same `"test-$i"` format.

**Tests:** Existing `threadedAddRemovePathsTest` exercises the corrected path.

### 4. Proxy shutdown ordering — FIXED

**File:** `src/main/kotlin/io/prometheus/Proxy.kt:243-251`

`invalidateAllAgentContexts()` was called *before* `failAllInFlightScrapeRequests()`. This meant HTTP handlers
waiting on `awaitCompleted()` got woken with a generic "missing_results" instead of the more informative
"Proxy is shutting down" error.

**Fix:** Swapped the call order in `shutDown()` — `failAllInFlightScrapeRequests()` now runs first so wrappers
receive the informative failure reason before agent contexts are invalidated.

**Tests:** 2 tests in `ProxyTest.kt` — correct ordering delivers "Proxy is shutting down" message; old ordering
comparison demonstrates the lost error message.

### 5. Resource leak with `transportFilterDisabled` — FIXED

**File:** `src/main/kotlin/io/prometheus/Proxy.kt` (startUp/shutDown)

If an agent calls `connectAgentWithTransportFilterDisabled()` but never opens a `readRequestsFromProxy()` stream
(e.g., crashes between the two calls), the `AgentContext` leaks until the stale agent eviction timer fires. If
`staleAgentCheckEnabled` is false, it leaks forever.

**Fix:** In `Proxy.startUp()`, force-enable the `AgentContextCleanupService` when `transportFilterDisabled` is
true, even if `staleAgentCheckEnabled` is false. Updated `shutDown()` to stop the cleanup service in both cases.

**Tests:** 2 tests in `ProxyServiceImplTest.kt` — verifies `removeAgentContext` is called on stream termination
when `transportFilterDisabled=true`, and NOT called when `false` (transport filter handles cleanup instead).

## Design Concerns / Minor Issues (Fixed)

### 6. Inconsistent compression `minimumSize` — FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyHttpConfig.kt:97-105`

`minimumSize(1024)` is applied only to `deflate`, not `gzip`. Tiny responses (even a few bytes) will be
gzip-compressed, adding unnecessary overhead.

**Fix:** Added `minimumSize(1024)` to the `gzip` block in `configureCompression()`, matching `deflate`.

**Tests:** Integration test in `ProxyHttpConfigTest.kt` verifying small responses are not gzip-compressed
when below the 1024-byte threshold.

### 7. `AgentPathManager` holds mutex during gRPC calls — FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:77-82`

Path registration/unregistration holds `pathMutex` while making a network round-trip gRPC call. Concurrent path
operations are blocked for the full RPC duration (potentially up to `unaryDeadlineSecs`).

**Fix:** Moved the gRPC calls (`registerPathOnProxy`, `unregisterPathOnProxy`) outside the `pathMutex.withLock`
block. The mutex now only protects the local `pathContextMap` update, allowing concurrent registrations for
different paths to proceed in parallel.

**Tests:** Updated concurrency test in `AgentPathManagerTest.kt` verifying that concurrent registrations for
different paths run their gRPC calls in parallel (maxConcurrent=2), not serialized by the mutex.

### 8. Displaced non-consolidated agents stay alive with zero paths — FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:99-112`

When a non-consolidated agent's path is overwritten by another agent, the displaced agent is only invalidated if
it's already invalid. A live displaced agent keeps its connection, heartbeats succeed, and it may never be
evicted — consuming resources indefinitely.

**Fix:** Removed the `displacedContext.isNotValid()` guard in `addPath()`. All displaced agents with zero remaining
paths are now invalidated, regardless of connection state. The agent will reconnect and re-register if needed.
Agents with other remaining paths are still not invalidated.

**Tests:** Updated 2 tests in `ProxyPathManagerTest.kt` — displaced live agents with zero paths are now
invalidated; displaced live agents with backlogs are invalidated and their backlogs are drained.

### 9. Double agent removal generates spurious logs — FIXED

**Files:** `AgentContextCleanupService.kt`, `ProxyServerTransportFilter.kt`

Both the stale-agent cleanup timer and gRPC transport termination can fire for the same agent, producing duplicate
"missing agent context" warnings. Functionally safe (idempotent operations) but noisy.

**Fix:** Changed log levels for the double-removal case: `AgentContextManager.removeFromContextManager` uses
`debug` instead of `warn`; `ProxyPathManager.removeFromPathManager` uses `debug` instead of `warn`;
`ProxyServerTransportFilter.transportTerminated` uses `info` instead of `error` with updated message
"Agent already removed before transport terminated".

**Tests:** 1 test in `AgentContextManagerTest.kt` verifying double `removeFromContextManager` returns null
on second call without exceptions; 1 test in `ProxyServerTransportFilterTest.kt` verifying
`transportTerminated` handles already-removed agents gracefully.

### 10. Chunked response header lacks `zipped` flag

**Files:** `proxy_service.proto:93-101`, `ChunkedContext.kt:85`

`ChunkedContext.applySummary()` hardcodes `srZipped = true`. This works because only zipped content is currently
chunked, but it's fragile — if someone ever chunks non-zipped content, the proxy will incorrectly mark it as
zipped.

## Test Issues

### 11. Tests that don't test what they claim

- `ProxyHttpConfigTest.kt:143-156` — Compression priority tests compare hardcoded local constants to themselves,
  never reading actual config values. Changes to production code won't be caught.
- `ScrapeRequestManagerTest.kt:267-292` — Test named "markComplete should only call observeDuration once" asserts
  `markCompleteCallCount shouldBe 2`.

### 12. Non-thread-safe shared map in harness tests

**File:** `HarnessTests.kt:75`

`contentMap` is a plain `mutableMapOf` written to by multiple concurrent coroutines in the parallel proxy call
tests with no synchronization.

### 13. Silent timeout swallowing

**File:** `HarnessTests.kt:191-210,213-236`

Tests wrap work in `withTimeoutOrNull(1.minutes)`. If a timeout occurs, assertions are skipped and the test
silently passes.

### 14. `ProxyHttpRoutesTest` proxy instances never cleaned up

**File:** `ProxyHttpRoutesTest.kt:75-108`

Two `Proxy` spy instances start internal services but are never stopped, potentially leaking threads and ports
across test runs.

### 15. Reflection-based field nulling is fragile

**File:** `AgentGrpcServiceTest.kt:982-998`

Uses reflection to null a `lateinit` field by name. Will break at runtime with a confusing error if the field is
ever renamed.
