# Prometheus Proxy Bug Review - 2026-02-13

## High-Impact Bugs

### 1. Integer overflow in chunked content size calculation -- FIXED

**Files:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:216`, `ChunkedContext.kt:29`

```kotlin
val maxZippedSize = proxy.proxyConfigVals.internal.maxZippedContentSizeMBytes * 1024 * 1024
```

The multiplication uses `Int` arithmetic, which silently overflows for config values >= 2048 MB (produces a negative
number). This would cause every chunked response to be rejected since any positive byte count is greater than a negative
limit. The unzipped version at `ProxyHttpRoutes.kt:293` correctly uses `Long`:

```kotlin
val maxSize = proxy.proxyConfigVals.internal.maxUnzippedContentSizeMBytes * 1024L * 1024L
```

**Fix applied:** Changed `* 1024 * 1024` to `* 1024L * 1024L` in `ProxyServiceImpl.kt` and changed
`ChunkedContext.maxZippedContentSize` from `Int` to `Long`. Tests added in `ChunkedContextTest.kt` to verify values >=
2048 MB and > `Int.MAX_VALUE` work correctly.

### 2. Agent enters zombie state when proxy evicts its context -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt:279-289`

When the heartbeat response indicates the agentId is not found on the proxy, a
`StatusRuntimeException(Status.NOT_FOUND)` is thrown, but it's caught by `runCatchingCancellable`'s `onFailure` handler,
which just logs the error and returns. The heartbeat loop in `Agent.startHeartBeat()` keeps running, so the agent
continues sending heartbeats that will keep failing, while no scrape requests are ever routed to it. The agent never
triggers a reconnection -- it enters a permanent degraded state until an external event occurs.

**Fix applied:** In `sendHeartBeat()`'s `onFailure` handler, re-throw `StatusRuntimeException` when the status code is
`NOT_FOUND`. This propagates up to `startHeartBeat()`, where the coroutine's
`invokeOnCompletion { connectionContext.close() }` closes the connection context, terminating all coroutines and
triggering a reconnect. Tests added in `AgentGrpcServiceTest.kt` to verify the exception propagates and the connection
context is closed.

### 3. `RequestFailureException` not wrapped in proper gRPC Status -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:62, 73`

When a transport filter mismatch is detected, `RequestFailureException` (which extends `Exception`, not
`StatusException`) is thrown on the server side. gRPC converts this to `Status.UNKNOWN` on the wire. The agent's error
handler at `Agent.kt:339` catches `RequestFailureException` specifically, but it will never see that type -- it receives
a `StatusRuntimeException` with `Status.UNKNOWN` instead. The wrong error-handling code path is taken, producing
misleading log messages.

**Fix applied:** Replaced `throw RequestFailureException(msg)` with
`throw StatusException(Status.FAILED_PRECONDITION.withDescription(msg))` in both `connectAgent()` and
`connectAgentWithTransportFilterDisabled()`. gRPC now preserves the `FAILED_PRECONDITION` status code and description on
the wire instead of converting to `UNKNOWN`. Tests updated in `ProxyServiceImplTest.kt` to verify the exception type,
status code, and description.

## Medium-Impact Bugs

### 4. Throwing from gRPC `onHeaders` callback violates listener contract -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentClientInterceptor.kt:62-63`

When `META_AGENT_ID_KEY` is missing from response headers, a `StatusRuntimeException` is thrown directly inside
`ClientCall.Listener.onHeaders()`. gRPC listener callbacks should not throw -- the correct approach is to cancel the
call via `call.cancel()`. This can cause undefined transport behavior.

**Fix applied:** Replaced `throw StatusRuntimeException(...)` with `delegate.cancel(msg, StatusRuntimeException(...))`
followed by an early `return`. The interceptor now captures a reference to the delegate call and uses it to cancel the
RPC properly when headers are missing. `super.onHeaders()` is not called, preventing the error from propagating to the
original listener. Tests updated in `AgentClientInterceptorTest.kt` to verify `cancel()` is called with INTERNAL status
and descriptive message, and that no exception is thrown from the callback.

### 5. Potential credential leak in URL logging -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:87, 117-118, 177, 194, 209`

If metrics endpoints use `user:password@host` URL format, the raw URL (including credentials) is logged at debug/warn
level and potentially returned in scrape error responses. The `ClientKey` class properly masks credentials, but the URL
string passed to loggers does not.

**Fix applied:** Added `sanitizeUrl()` utility to `Utils.kt` that strips `user:password@` credentials from URLs using
regex replacement (`://[^@/?#]+@` → `://***@`). Applied in `AgentHttpService.kt`: `fetchContentFromUrl()` creates a
`logUrl` for all logging and error results, `fetchContent()` sanitizes in `requireNotNull` message, and
`buildScrapeResults()` sanitizes all `srUrl` fields. Tests added in `UtilsTest.kt` verifying credential stripping,
user-only stripping, no-op for clean URLs, HTTPS handling, and edge cases.

### 6. Content length check uses character count instead of byte count -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:186`

```kotlin
if (content.length > maxContentLength) {
```

`content.length` returns character count, but `maxContentLength` is a byte-based limit. For multi-byte UTF-8 content,
this check is too permissive. The `Content-Length` header check on line 170 is correct (it uses bytes).

**Fix applied:** Changed `content.length` to `content.encodeToByteArray().size.toLong()` in `buildScrapeResults()`. The
comparison now correctly uses byte count against the byte-based `maxContentLength` limit. Tests added in
`AgentHttpServiceTest.kt` demonstrating that multi-byte UTF-8 characters produce different byte and character counts.

## Low-Impact Bugs / Design Issues

### 7. `resolveBoolean()` cannot disable a feature via CLI -- FIXED

**File:** `src/main/kotlin/io/prometheus/common/BaseOptions.kt:334-344`

When `cliValue` is `false` (JCommander default), it falls through to env var or config default. There is no way to use
the CLI to disable something enabled in config. The comment says "Priority: CLI > env > config" but CLI can only enable,
never disable.

**Fix applied:** Added a new `resolveBoolean` overload that accepts a `cliExplicitlySet` parameter, correctly returning
`cliValue` (whether true or false) when the CLI flag was explicitly provided. Added `isFlagInArgs()` helper in
`BaseOptions` that scans the saved CLI args to detect explicit boolean flag presence. Updated `resolveBooleanOption`
with a new overload accepting CLI flag names. Updated all callers in `BaseOptions`, `ProxyOptions`, and `AgentOptions`
to pass their CLI flag names. The legacy `resolveBoolean` overload is preserved for backward compatibility. Tests added
in `BaseOptionsTest.kt` verifying that `cliExplicitlySet=true` with `cliValue=false` correctly returns `false`,
overriding both env vars and config defaults.

### 8. SD path/prefix values not logged when service discovery is enabled -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyOptions.kt:113-125`

The `logger.info` calls for `sdPath` and `sdTargetPrefix` are in the `else` branch (when `sdEnabled` is false). When SD
is enabled -- precisely when you'd want to see these values in logs -- they are not logged.

**Fix applied:** Moved `logger.info` calls for `sdPath` and `sdTargetPrefix` outside the `if/else` blocks so they are
logged unconditionally, regardless of whether service discovery is enabled or disabled. Tests added in
`ProxyOptionsTest.kt` verifying values are correctly accessible when SD is both enabled and disabled.

### 9. Invalid path returns 503 instead of 404 -- FIXED

**File:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:285-294`

`handleInvalidPath()` returns the default status code `ServiceUnavailable` (503). A 404 (Not Found) would be
semantically more appropriate for an unregistered path.

**Fix applied:** Added `srStatusCode = HttpStatusCode.NotFound.value` to the `ScrapeResults` constructor in
`handleInvalidPath()`. The method now returns 404 instead of the default 503 when an unregistered path is requested.
Test added in `AgentHttpServiceTest.kt` verifying the status code is 404.

### 10. `readRequestsFromProxy()` returns empty flow instead of error when agentId not found -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:162-172`

When `getAgentContext(agentId)` returns null, the method logs a warning and the flow completes normally. The agent sees
an ambiguous empty stream rather than an explicit `Status.NOT_FOUND` error, making debugging harder.

**Fix applied:** Replaced `?: logger.warn { ... }` with
`?: throw StatusException(Status.NOT_FOUND.withDescription("No AgentContext found for agentId: $agentId"))`. The agent
now receives an explicit `NOT_FOUND` error instead of an ambiguous empty stream. Test updated in
`ProxyServiceImplTest.kt` to verify `StatusException` with `NOT_FOUND` status code and descriptive message.

### 11. Wrong `CancellationException` import -- FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:51`

Imports `java.util.concurrent.CancellationException` instead of `kotlinx.coroutines.CancellationException`. Functionally
correct due to inheritance, but technically imprecise in a coroutines context.

**Fix applied:** Changed `import java.util.concurrent.CancellationException` to
`import kotlinx.coroutines.CancellationException`. Test added in `ProxyServiceImplTest.kt` verifying that
`kotlinx.coroutines.CancellationException` is properly rethrown in `writeResponsesToProxy()`.

## Summary

| #  | Severity | Issue                                            | Location                       | Status |
|----|----------|--------------------------------------------------|--------------------------------|--------|
| 1  | **High** | Int overflow in zipped content size calculation  | `ProxyServiceImpl.kt:216`      | FIXED  |
| 2  | **High** | Agent zombie state on proxy eviction             | `AgentGrpcService.kt:279-289`  | FIXED  |
| 3  | **High** | gRPC status mismatch for transport filter errors | `ProxyServiceImpl.kt:62,73`    | FIXED  |
| 4  | Medium   | Throwing from gRPC listener callback             | `AgentClientInterceptor.kt:62` | FIXED  |
| 5  | Medium   | Credential leak in URL logging                   | `AgentHttpService.kt`          | FIXED  |
| 6  | Medium   | Char vs byte content length check                | `AgentHttpService.kt:186`      | FIXED  |
| 7  | Low      | CLI can't disable config-enabled booleans        | `BaseOptions.kt:334`           | FIXED  |
| 8  | Low      | Missing SD config logging when enabled           | `ProxyOptions.kt:113`          | FIXED  |
| 9  | Low      | Wrong HTTP status for invalid path               | `AgentHttpService.kt:285`      | FIXED  |
| 10 | Low      | Empty flow instead of NOT_FOUND error            | `ProxyServiceImpl.kt:162`      | FIXED  |
| 11 | Low      | Wrong CancellationException import               | `ProxyServiceImpl.kt:51`       | FIXED  |
