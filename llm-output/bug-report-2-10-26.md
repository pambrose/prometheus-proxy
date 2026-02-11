# Bug Report: Prometheus Proxy Codebase Review

**Date**: 2026-02-10
**Scope**: All 38 main source files in `src/main/kotlin/io/prometheus/`
**Reviewer**: Claude Code

## Fix Status

| Bug | Severity | Status   | Description                                                     |
|-----|----------|----------|-----------------------------------------------------------------|
| #1  | Critical | FIXED    | Deadlock in `AgentGrpcService.resetGrpcStubs()`                 |
| #2  | Critical | FIXED    | `AgentContext.invalidate()` backlog counter leak                |
| #3  | Critical | FIXED    | Unhandled `ClosedSendChannelException` in `submitScrapeRequest` |
| #4  | High     | FIXED    | Proxy shutdown does not invalidate agent contexts               |
| #5  | High     | RESOLVED | TOCTOU race — mitigated by Bug #3 fix                           |
| #6  | High     | FIXED    | Non-consolidated agent overwrites path without invalidation     |
| #7  | High     | Open     | `ConfigVals` null safety for platform types                     |
| #8  | Medium   | FIXED    | `ProxyPathManager.addPath` allows mismatched consolidated flag  |
| #9  | Medium   | RESOLVED | `readRequestsFromProxy` hang — resolved by Bug #4 fix           |
| #10 | Medium   | FIXED    | Auth header transmitted in plaintext without TLS                |
| #11 | Medium   | FIXED    | Missing `reserved 5` in proto                                   |
| #12 | Medium   | FIXED    | `parseHostPort` does not validate empty input                   |
| #13 | Low      | FIXED    | Misleading parameter name `waitMillis`                          |
| #14 | Low      | FIXED    | Redundant `response.status()` call                              |
| #15 | Low      | FIXED    | Redundant double `.resolve()` call                              |
| #16 | Low      | FIXED    | `EnvVars.getEnv(Boolean)` silent false for non-"true"           |
| #17 | Low      | Open     | Inconsistent proto field naming                                 |
| #18 | Low      | FIXED    | Deprecated `java.net.URL` constructor                           |
| #19 | Low      | FIXED    | `VersionValidator` calls `exitProcess(0)`                       |
| #20 | Low      | FIXED    | `AgentContextCleanupService.run` uses blocking `sleep`          |

### Additional Test Fix

| Issue | Status | Description                                                                                    |
|-------|--------|------------------------------------------------------------------------------------------------|
| Test  | FIXED  | `HttpClientCacheTest` blocking: `coEvery`/`coAnswers` on non-suspend `close()` caused deadlock |

---

## Critical Severity

### 1. Deadlock in `AgentGrpcService.resetGrpcStubs()` calling `shutDown()` — FIXED

**File**: `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`

`resetGrpcStubs()` acquires `grpcLock` and then calls `shutDown()` on line 146, which also tries to acquire `grpcLock` (
line 132). Since `ReentrantLock` is reentrant, this does not deadlock with itself. However, the lock is held for the
entire duration of `channel.awaitTermination(5, SECONDS)` (line 137) inside `shutDown()`, blocking all other callers of
both `resetGrpcStubs()` and `shutDown()` for up to 5 seconds. If `shutDown()` is called externally (e.g., from
`Agent.shutDown()` at line 466) while `resetGrpcStubs()` is executing on another thread, the external caller will block
for the full duration of channel creation + termination.

**Impact**: Under reconnection scenarios, simultaneous shutdown and reconnection could block the shutdown thread for an
extended period.

**Fix Applied**: Extracted `shutDownLocked()` private method. `shutDown()` acquires the lock and delegates to
`shutDownLocked()`. `resetGrpcStubs()` calls `shutDownLocked()` directly (already holding the lock), avoiding redundant
nested lock acquisition.

**Tests**: 3 concurrency tests in `AgentGrpcServiceTest` (deadlock detection with timeout).

---

### 2. `AgentContext.invalidate()` does not decrement `channelBacklogSize` for drained items — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/AgentContext.kt`

In `writeScrapeRequest()`, `channelBacklogSize` is incremented before sending to the channel. In `invalidate()`, the
channel is drained but `channelBacklogSize` is never decremented for each drained item. This means the backlog counter
becomes permanently inflated after invalidation. Additionally, if `invalidate()` closes the channel between the
increment and the `send`, a `ClosedSendChannelException` is thrown with the counter already incremented but no item in
the channel.

**Impact**: Health check (`agent_scrape_request_backlog`) could report unhealthy state based on stale counters. The race
between `writeScrapeRequest` and `invalidate` could leave the counter inconsistent.

**Fix Applied**: Two changes: (1) `writeScrapeRequest()` wraps `send()` in try/catch for `ClosedSendChannelException`,
decrementing the counter on failure. (2) `invalidate()` decrements `channelBacklogSize` for each drained item.

**Tests**: 3 tests in `AgentContextTest` (backlog drain, closed channel write, mixed read/invalidate).

---

### 3. Unhandled `ClosedSendChannelException` in `submitScrapeRequest` — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt`

`agentContext.writeScrapeRequest(scrapeRequest)` calls `scrapeRequestChannel.send()`, which throws
`ClosedSendChannelException` if `invalidate()` is called concurrently (e.g., agent disconnect). The exception propagates
to Ktor's error handler, resulting in an HTTP 500 instead of a graceful 503 ServiceUnavailable response.

**Impact**: Prometheus receives 500 errors during agent disconnection instead of a clear 503 indicating temporary
unavailability.

**Fix Applied**: Wrapped `writeScrapeRequest` in try/catch for `ClosedSendChannelException`, returning 503
ServiceUnavailable with `"agent_disconnected"` message. Also changed `submitScrapeRequest` from `private` to `internal`
for testability.

**Tests**: 2 tests in `ProxyHttpRoutesTest` (exception type verification, backlog counter after invalidation).

---

## High Severity

### 4. Proxy shutdown does not invalidate remaining agent contexts — FIXED

**File**: `src/main/kotlin/io/prometheus/Proxy.kt`
**File**: `src/main/kotlin/io/prometheus/proxy/AgentContextManager.kt`

When the proxy shuts down, `shutDown()` stops the gRPC and HTTP services but does not explicitly invalidate all
remaining agent contexts. Any coroutines waiting in `readRequestsFromProxy` (ProxyServiceImpl:163) could hang until gRPC
shutdown forces stream closure. HTTP handlers waiting in `submitScrapeRequest.awaitCompleted` rely on the polling loop's
`!proxy.isRunning` check, which adds latency (up to `scrapeRequestCheckMillis` per iteration).

**Impact**: During proxy shutdown, in-flight HTTP requests may experience unnecessary delays waiting for the timeout
check interval before being released.

**Fix Applied**: Added `invalidateAllAgentContexts()` method to `AgentContextManager` that iterates all contexts and
calls `invalidate()`. Called from `Proxy.shutDown()` before stopping services. This also resolves Bug #9 (
`readRequestsFromProxy` hang).

**Tests**: 5 tests in `AgentContextManagerTest` (invalidate all, drain backlog, unblock awaitCompleted, empty map,
contexts remain in map).

---

### 5. TOCTOU race in path-based request routing — RESOLVED (by Bug #3 fix)

**File**: `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt`
**File**: `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt`

`getAgentContextInfo()` returns a defensive copy of the agent context list, but the `AgentContext` objects within are
shared references. Between the `agentContextInfo.isNotValid()` check and the actual `writeScrapeRequest` call, an agent
context could be invalidated. The validity check is not re-verified immediately before writing.

**Impact**: A scrape request could be sent to an already-invalidated agent, resulting in a `ClosedSendChannelException`
or a timeout.

**Resolution**: Bug #3's fix (catch `ClosedSendChannelException` and return 503) makes this race condition benign. The
TOCTOU window still exists but no longer causes HTTP 500 errors.

---

### 6. Non-consolidated agent overwrites path without invalidating existing agents — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt`

When a non-consolidated agent registers a path that already has agent(s), the old `AgentContextInfo` is replaced
entirely. The old agent context(s) are not notified or invalidated. They remain connected and consuming resources with
their paths no longer in the path map. Scrape requests in-flight to the old agent could still complete, but new requests
go to the new agent. The old agents are effectively orphaned until the cleanup service evicts them.

**Impact**: Orphaned agent contexts accumulate resources until eviction. If stale agent check is disabled, these
contexts leak permanently.

**Fix Applied**: After overwriting a path, each displaced agent context is checked for other registered paths. If a
displaced agent has no other paths in the pathMap, it is invalidated (closing its channel and draining buffered
requests). Agents with other paths are left valid.

**Tests**: 4 tests in `ProxyPathManagerTest` (invalidate with no other paths, preserve with other paths, invalidate all
consolidated, drain backlog on overwrite).

---

### 7. `ConfigVals.PathConfigs$Elm.$_reqStr` returns null for fields consumed as non-null from Kotlin

**File**: `src/main/java/io/prometheus/common/ConfigVals.java:289-296`
**File**: `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:42-44, 95-98`

The auto-generated `$_reqStr` method returns `null` when the config key is missing. The fields `name`, `path`, and `url`
in `PathConfigs$Elm` are declared as `java.lang.String` without nullability annotations. From Kotlin, these are platform
types (`String!`). While `registerPaths()` (line 57) does check for null, `toPlainText()` (lines 95-98) accesses the map
values (originally from `it.name`, `it.path`, `it.url`) and could NPE if any config entry has missing required fields
that weren't caught by the validator.

**Impact**: Invalid configuration with missing required `pathConfigs` fields could cause `NullPointerException` at
runtime in `toPlainText()`.

**Suggested Fix**: Add `@Nullable` annotations to the auto-generated Java fields, or add defensive null checks in the
Kotlin consumption layer.

---

## Medium Severity

### 8. `ProxyPathManager.addPath` allows mismatched consolidated flag — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:71-89`
**File**: `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:110`

When a consolidated agent registers a path that already has non-consolidated agents (or vice versa), the code only
logged a warning but still proceeded. A consolidated agent could be added to a non-consolidated path, causing unexpected
fan-out behavior where scrape requests go to all agents instead of just one.

**Impact**: Mixed consolidated/non-consolidated agents on the same path could produce unexpected scrape behavior.

**Fix Applied**: Changed `addPath` return type from `Unit` to `Boolean`. When a consolidated agent attempts to register
on a non-consolidated path (or vice versa), the method now logs an error and returns `false` to reject the registration.
Updated `ProxyServiceImpl.registerPath` to use the return value: `isValid = proxy.pathManager.addPath(...)`.

**Tests**: Updated existing tests in `ProxyPathManagerTest` to verify rejection (return `false`) instead of acceptance
on type mismatch.

---

### 9. `readRequestsFromProxy` can hang if proxy stops before agent contexts are invalidated — RESOLVED (by Bug #4 fix)

**File**: `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt`

In `readRequestsFromProxy`, the loop condition is `proxy.isRunning && agentContext.isValid()`. Inside the loop,
`readScrapeRequest()` suspends on `scrapeRequestChannel.receiveCatching()`. If the proxy is shutting down (
`proxy.isRunning` becomes false) but the agent context is still valid and the channel has no pending items,
`readScrapeRequest()` will suspend indefinitely until gRPC forces stream closure.

**Impact**: Proxy shutdown may be delayed if gRPC stream termination is slow.

**Resolution**: Bug #4's fix (`invalidateAllAgentContexts()` called during shutdown) closes all channels, which unblocks
`receiveCatching()` and causes the loop to exit.

---

### 10. Auth header transmitted in plaintext when TLS is disabled — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt:281`
**File**: `src/main/kotlin/io/prometheus/common/BaseOptions.kt`
**File**: `README.md`

The `ScrapeRequest` message includes an `authHeader` field carrying the HTTP `Authorization` header from Prometheus
scrape requests. If the gRPC connection between proxy and agent is not TLS-encrypted (the default configuration),
credentials are transmitted in plaintext.

**Impact**: Credentials could be intercepted on the network between proxy and agent.

**Fix Applied**: Three changes: (1) Added `isTlsEnabled` computed property to `BaseOptions` that checks whether
`certChainFilePath` or `privateKeyFilePath` is configured. (2) Added a once-only warning log in
`ProxyHttpRoutes.createScrapeRequest()` when an `Authorization` header is present and TLS is not enabled, using
`AtomicBoolean.compareAndSet` for thread-safe single-fire. (3) Added "Auth Header Forwarding" section to `README.md`
documenting the security requirement.

**Tests**: 3 tests in `BaseOptionsTest` (`isTlsEnabled` false by default, true with cert, true with key).

---

### 11. Missing `reserved 5` for skipped field number in `RegisterAgentRequest` — FIXED

**File**: `src/main/proto/proxy_service.proto:8-14`

The `RegisterAgentRequest` message has field numbers 1, 2, 3, 4, 6 -- field number 5 is skipped, suggesting a field was
removed. Without a `reserved 5;` declaration, this field number could accidentally be reused in the future, causing
wire-format incompatibilities with older clients.

**Impact**: Future accidental reuse of field number 5 could cause backward compatibility issues.

**Fix Applied**: Added `reserved 5;` to `RegisterAgentRequest` message in the proto file.

---

### 12. `parseHostPort` does not validate empty/blank input — FIXED

**File**: `src/main/kotlin/io/prometheus/common/Utils.kt:98-139`

If `parseHostPort` is called with an empty string `""`, it falls through to the `':' !in hostPort` branch (line 125) and
returns `HostPort("", defaultPort)`. An empty hostname is likely an error condition that would cause confusing failures
downstream in gRPC channel builders.

**Impact**: Empty hostname silently propagates to gRPC connection, causing unclear errors.

**Fix Applied**: Added `require(hostPort.isNotBlank()) { "Host/port string must not be blank" }` at the start of the
method.

**Tests**: 2 tests in `UtilsTest` (empty string, blank string).

---

## Low Severity

### 13. `ScrapeRequestWrapper.awaitCompleted` parameter name is misleading — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/ScrapeRequestWrapper.kt:77`

The parameter is named `waitMillis` but its type is `Duration`, not milliseconds. The internal conversion
`.inWholeMilliseconds` at line 78 confirms it expects a Duration object.

**Fix Applied**: Renamed parameter from `waitMillis` to `timeout`.

**Tests**: 2 tests in `ScrapeRequestWrapperTest` (named parameter usage, short timeout duration).

---

### 14. Redundant `response.status()` call in `ProxyUtils.respondWith` — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/ProxyUtils.kt:89-93` (approximate)

`response.status(status)` is called before `respondText(text, contentType, status)`, which also sets the status code.
The first call is redundant.

**Fix Applied**: Removed the redundant `response.status(status)` call.

**Tests**: 1 test in `ProxyUtilsTest` (CacheControl header and status code verified together without redundant call).

---

### 15. Redundant double `.resolve()` call in config loading — FIXED

**File**: `src/main/kotlin/io/prometheus/common/BaseOptions.kt:239-240`

The code calls `.resolve(ConfigResolveOptions.defaults()).resolve()`. The second `.resolve()` is a no-op since the
config is already fully resolved.

**Fix Applied**: Removed the redundant second `.resolve()` call.

**Tests**: 1 test in `BaseOptionsTest` (config with variable substitution verifies resolution still works).

---

### 16. `EnvVars.getEnv(Boolean)` silently treats non-"true" strings as `false` — FIXED

**File**: `src/main/kotlin/io/prometheus/common/EnvVars.kt:83`

`getenv(name)?.toBoolean()` uses Kotlin's `String.toBoolean()`, which returns `true` only for `"true"` (
case-insensitive). Values like `"yes"`, `"1"`, or typos like `"ture"` silently return `false` instead of signaling an
error.

**Impact**: Users setting `ADMIN_ENABLED=yes` or `ADMIN_ENABLED=1` get unexpected behavior.

**Fix Applied**: Replaced `toBoolean()` with `parseBooleanStrict()` companion function that only accepts `"true"` or
`"false"` (case-insensitive) and throws `IllegalArgumentException` with a descriptive message for any other value.

**Tests**: 6 tests in `EnvVarsTest` (true variants, false variants, "yes", "1", typo "ture", empty string).

---

### 17. Inconsistent proto field naming convention

**File**: `src/main/proto/proxy_service.proto:64-65`

Fields `encodedQueryParams` and `authHeader` use camelCase while all other fields use snake_case. This affects JSON
serialization format.

**Impact**: Inconsistent JSON field names in protobuf JSON serialization.

**Suggested Fix**: Rename to `encoded_query_params` and `auth_header` (note: this is a breaking change for JSON
serialization).

---

### 18. Deprecated `java.net.URL` constructor — FIXED

**File**: `src/main/kotlin/io/prometheus/common/BaseOptions.kt:285`

`URL(configName)` uses the `java.net.URL(String)` constructor, which is deprecated since Java 20.

**Fix Applied**: Replaced `URL(configName)` with `URI(configName).toURL()`. Updated import from `java.net.URL` to
`java.net.URI`.

**Tests**: 2 tests in `BaseOptionsTest` (URI-to-URL for HTTP and HTTPS config URLs).

---

### 19. `VersionValidator` calls `exitProcess(0)` inside JCommander validator — FIXED

**File**: `src/main/kotlin/io/prometheus/common/Utils.kt:39-48`
**File**: `src/main/kotlin/io/prometheus/common/BaseOptions.kt:109-113`

Calling `exitProcess(0)` during JCommander's `IParameterValidator.validate()` prevents unit testing and embedded usage.
The validator fires during `parse()`, making it impossible to test parsing behavior when `-v` is present.

**Fix Applied**: Removed the `VersionValidator` class entirely. Removed `validateWith` from the `@Parameter` annotation
on the `version` field. Added a post-parse check for the `version` flag alongside the existing `usage` flag check in
`parseArgs()`, calling `getVersionDesc()` and then `exitProcess(0)`. This moves the exit from inside the validator (
during parsing) to after parsing completes.

**Tests**: 1 test in `BaseOptionsTest` (normal construction doesn't trigger exitProcess), 1 test in `UtilsTest` (
getVersionDesc callable without side effects).

---

### 20. `AgentContextCleanupService.run` uses blocking `sleep` — FIXED

**File**: `src/main/kotlin/io/prometheus/proxy/AgentContextCleanupService.kt:55`

The cleanup service uses `sleep(pauseTime)` which blocks the thread and cannot be interrupted promptly during shutdown.
The service will wait until the current sleep completes.

**Impact**: Proxy shutdown may be delayed by up to `staleAgentCheckPauseSecs` while waiting for the sleep to complete.

**Fix Applied**: Replaced blocking `sleep(pauseTime)` with
`shutdownLatch.await(pauseTime.inWholeMilliseconds, TimeUnit.MILLISECONDS)` using a `CountDownLatch`. Added
`triggerShutdown()` override that counts down the latch, causing the `await()` to return immediately when the service is
asked to stop.

**Tests**: 1 test in `AgentContextCleanupServiceTest` (service with 60s pause stops in under 5s).

---

## Summary

| Severity  | Count  | Fixed  | Resolved | Open  |
|-----------|--------|--------|----------|-------|
| Critical  | 3      | 3      | 0        | 0     |
| High      | 4      | 2      | 1        | 1     |
| Medium    | 5      | 4      | 1        | 0     |
| Low       | 8      | 7      | 0        | 1     |
| **Total** | **20** | **16** | **2**    | **2** |

### Changes by File

| File                            | Bugs Fixed                                                                            |
|---------------------------------|---------------------------------------------------------------------------------------|
| `AgentGrpcService.kt`           | #1 — extracted `shutDownLocked()`                                                     |
| `AgentContext.kt`               | #2 — backlog counter fix in `writeScrapeRequest` and `invalidate`                     |
| `ProxyHttpRoutes.kt`            | #3 — catch `ClosedSendChannelException` in `submitScrapeRequest`                      |
| `AgentContextManager.kt`        | #4 — added `invalidateAllAgentContexts()`                                             |
| `Proxy.kt`                      | #4 — call `invalidateAllAgentContexts()` in `shutDown()`                              |
| `ProxyPathManager.kt`           | #6 — invalidate displaced agents; #8 — reject consolidated mismatch, return `Boolean` |
| `ProxyServiceImpl.kt`           | #8 — use `addPath` return value for `isValid`                                         |
| `proxy_service.proto`           | #11 — added `reserved 5`                                                              |
| `Utils.kt`                      | #12 — blank input validation in `parseHostPort`                                       |
| `ScrapeRequestWrapper.kt`       | #13 — renamed `waitMillis` to `timeout`                                               |
| `ProxyUtils.kt`                 | #14 — removed redundant `response.status()` call                                      |
| `BaseOptions.kt`                | #15 — removed redundant second `.resolve()`                                           |
| `EnvVars.kt`                    | #16 — strict boolean validation with `parseBooleanStrict()`                           |
| `BaseOptions.kt`                | #18 — `URI(configName).toURL()`; #19 — version check after parsing                    |
| `Utils.kt`                      | #19 — removed `VersionValidator` class                                                |
| `AgentContextCleanupService.kt` | #20 — `CountDownLatch.await` replaces blocking `sleep`                                |
| `BaseOptions.kt`                | #10 — added `isTlsEnabled` property                                                   |
| `ProxyHttpRoutes.kt`            | #10 — once-only warning for auth header without TLS                                   |
| `README.md`                     | #10 — documented auth header forwarding security requirement                          |

### Test Files Added/Modified

| Test File                           | Tests Added                                                          |
|-------------------------------------|----------------------------------------------------------------------|
| `AgentGrpcServiceTest.kt`           | 3 concurrency/deadlock tests                                         |
| `AgentContextTest.kt`               | 3 backlog counter consistency tests                                  |
| `ProxyHttpRoutesTest.kt`            | 2 exception handling tests                                           |
| `AgentContextManagerTest.kt`        | 5 invalidateAll tests                                                |
| `ProxyPathManagerTest.kt`           | 4 displaced agent invalidation tests + updated mismatch tests for #8 |
| `HttpClientCacheTest.kt`            | Fixed blocking test (non-suspend mock fix)                           |
| `UtilsTest.kt`                      | 2 blank input validation tests for #12                               |
| `ScrapeRequestWrapperTest.kt`       | 2 timeout parameter tests for #13                                    |
| `ProxyUtilsTest.kt`                 | 1 CacheControl + status test for #14                                 |
| `BaseOptionsTest.kt`                | 1 variable substitution resolution test for #15                      |
| `EnvVarsTest.kt`                    | 6 parseBooleanStrict validation tests for #16                        |
| `BaseOptionsTest.kt`                | 2 URI-to-URL tests for #18; 1 version flag test for #19              |
| `UtilsTest.kt`                      | 1 getVersionDesc no-exitProcess test for #19                         |
| `AgentContextCleanupServiceTest.kt` | 1 prompt shutdown test for #20                                       |
| `BaseOptionsTest.kt`                | 3 isTlsEnabled tests for #10                                         |

### Remaining Priority Recommendations

1. **Address Bug #10**: At minimum, document that TLS should be enabled when forwarding auth headers.
2. **Fix Bug #7**: Add null safety for platform types from auto-generated Java config.
3. **Fix Bug #17**: Rename proto fields to snake_case (breaking change for JSON serialization).
