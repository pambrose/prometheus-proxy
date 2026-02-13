# Test Coverage Gap Analysis

## Overview

Analysis of unit test coverage gaps across 39 source files and 59 test files in the
prometheus-proxy codebase. Gaps are organized into four tiers by severity and impact.

---

## Tier 1 -- No Dedicated Test File

These major classes have no unit tests. They are exercised indirectly by the integration
harness tests but have zero dedicated tests for their individual methods.

### `Proxy.kt` (`io.prometheus.Proxy`) -- DONE

Created `src/test/kotlin/io/prometheus/proxy/ProxyTest.kt` (15 tests):

| Test                                                                            | Method Covered                |
|---------------------------------------------------------------------------------|-------------------------------|
| `buildServiceDiscoveryJson should return empty array when no paths registered`  | `buildServiceDiscoveryJson()` |
| `buildServiceDiscoveryJson should return correct structure for single path`     | `buildServiceDiscoveryJson()` |
| `buildServiceDiscoveryJson should produce multiple entries for multiple paths`  | `buildServiceDiscoveryJson()` |
| `buildServiceDiscoveryJson should include custom labels from agent`             | `buildServiceDiscoveryJson()` |
| `buildServiceDiscoveryJson should skip invalid JSON labels gracefully`          | `buildServiceDiscoveryJson()` |
| `removeAgentContext should throw on empty agentId`                              | `removeAgentContext()`        |
| `removeAgentContext should delegate to both managers`                           | `removeAgentContext()`        |
| `removeAgentContext should return null for unknown agentId`                     | `removeAgentContext()`        |
| `isBlitzRequest should return false when blitz is disabled`                     | `isBlitzRequest()`            |
| `isBlitzRequest should return true when blitz enabled and path matches`         | `isBlitzRequest()`            |
| `isBlitzRequest should return false when blitz enabled but path does not match` | `isBlitzRequest()`            |
| `metrics should not invoke lambda when metrics disabled`                        | `metrics()`                   |
| `metrics should invoke lambda when metrics enabled`                             | `metrics()`                   |
| `logActivity should add timestamped entry without error`                        | `logActivity()`               |
| `toString should contain proxyPort and service info`                            | `toString()`                  |

Still not covered: `registerHealthChecks()`, `startUp()`/`shutDown()` ordering.

### `Agent.kt` (`io.prometheus.Agent`) -- DONE

Created `src/test/kotlin/io/prometheus/agent/AgentTest.kt` (14 tests):

| Test                                                                           | Method Covered                   |
|--------------------------------------------------------------------------------|----------------------------------|
| `awaitInitialConnection should return false when timeout expires`              | `awaitInitialConnection()`       |
| `awaitInitialConnection should return true after latch countdown`              | `awaitInitialConnection()`       |
| `awaitInitialConnection should return false immediately with zero timeout`     | `awaitInitialConnection()`       |
| `updateScrapeCounter should not fail for empty type`                           | `updateScrapeCounter()`          |
| `updateScrapeCounter should not fail for non-empty type with metrics disabled` | `updateScrapeCounter()`          |
| `markMsgSent should complete without error`                                    | `markMsgSent()`                  |
| `serviceName should return Agent agentName format`                             | `serviceName()` (via reflection) |
| `agentName should use provided name from options`                              | `agentName`                      |
| `agentName should fallback to Unnamed-hostname when name is blank`             | `agentName`                      |
| `proxyHost should return hostname colon port format`                           | `proxyHost`                      |
| `metrics should not invoke lambda when metrics disabled`                       | `metrics()`                      |
| `metrics should invoke lambda when metrics enabled`                            | `metrics()`                      |
| `launchId should be a 15-character string`                                     | `launchId`                       |
| `toString should contain agentName and proxyHost`                              | `toString()`                     |

Still not covered: `startHeartBeat()`, `connectToProxy()` reconnection loop,
`registerHealthChecks()`, scrape processing coroutine.

---

## Tier 2 -- Entire Methods with No Test Coverage

### `AgentGrpcService.kt` -- DONE

Added 10 new tests to `src/test/kotlin/io/prometheus/agent/AgentGrpcServiceTest.kt`
(total now 38 tests):

| Test                                                                                 | Method Covered                                                     |
|--------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| `readRequestsFromProxy should forward scrape requests to connectionContext`          | `readRequestsFromProxy()`                                          |
| `readRequestsFromProxy should handle empty flow from proxy`                          | `readRequestsFromProxy()`                                          |
| `processScrapeResults should route non-zipped result to nonChunkedChannel`           | `processScrapeResults()` -- non-zipped branch                      |
| `processScrapeResults should route zipped small result to nonChunkedChannel`         | `processScrapeResults()` -- zipped < chunkSize branch              |
| `processScrapeResults should route zipped large result to chunkedChannel with CRC32` | `processScrapeResults()` -- chunked branch with CRC32 verification |
| `processScrapeResults should handle multiple results in sequence`                    | `processScrapeResults()` -- sequential processing                  |
| `sendHeartBeat should handle NOT_FOUND status from proxy`                            | `sendHeartBeat()` -- NOT_FOUND error path                          |
| `sendHeartBeat should handle generic exception without throwing`                     | `sendHeartBeat()` -- generic exception path                        |
| `registerAgent should throw on empty agentId`                                        | `registerAgent()` -- require guard                                 |

The `processScrapeResults` tests use Kotlin reflection (`callSuspend`) to invoke the private
method directly with controlled channels, verifying all three routing branches and CRC32
checksum correctness for chunked responses.

### `ProxyHttpRoutes.kt` -- DONE

Added 12 new tests to `src/test/kotlin/io/prometheus/proxy/ProxyHttpRoutesTest.kt`
(total now 26 tests):

| Test                                                                                 | Method Covered                                                     |
|--------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| `logActivityForResponse should format success status without failure reason`         | `logActivityForResponse()` -- success branch                       |
| `logActivityForResponse should include failure reason for non-success status`        | `logActivityForResponse()` -- failure branch (404)                 |
| `logActivityForResponse should include failure reason for ServiceUnavailable`        | `logActivityForResponse()` -- failure branch (503)                 |
| `authHeaderWithoutTlsWarned should be a single-fire AtomicBoolean`                   | `createScrapeRequest()` -- auth warning logic                      |
| `submitScrapeRequest should return agent_disconnected on ClosedSendChannelException` | `submitScrapeRequest()` -- ClosedSendChannelException during write |
| `submitScrapeRequest should return timed_out when scrape request times out`          | `submitScrapeRequest()` -- timeout branch                          |
| `submitScrapeRequest should return timed_out when agent disconnects during scrape`   | `submitScrapeRequest()` -- agent disconnect during loop            |
| `submitScrapeRequest should return timed_out when proxy stops during scrape`         | `submitScrapeRequest()` -- proxy stopped during loop               |
| `submitScrapeRequest should return success for valid non-zipped response`            | `submitScrapeRequest()` -- happy path (non-zipped)                 |
| `submitScrapeRequest should unzip zipped response content`                           | `submitScrapeRequest()` -- zipped content branch                   |
| `submitScrapeRequest should fallback to plain text on content type parse error`      | `submitScrapeRequest()` -- ContentType.parse() error fallback      |
| `submitScrapeRequest should return path_not_found for non-success status`            | `submitScrapeRequest()` -- non-success status branch               |

The `logActivityForResponse` tests use reflection to call the private method and capture
the formatted string via a mock `Proxy.logActivity()` slot.

The `submitScrapeRequest` tests use `spyk(Proxy(...))` with config overrides for timeout
values, real `AgentContext` instances, and coroutine-based async scrape result completion
via `ScrapeRequestManager.assignScrapeResults()` to exercise all major branches.

Still not covered:

- `processRequests()` empty-results branch (returns 503) -- requires multiple concurrent agents
- `submitScrapeRequest()` missing-results branch -- defensive check for race condition

Now covered by Tier 4 integration tests:

- `processRequestsBasedOnPath()` null branch -- tested via
  `handleClientRequests should return NotFound for unregistered path`
- `processRequestsBasedOnPath()` invalid branch -- tested via
  `handleClientRequests should return NotFound for invalidated agent context`

### `ProxyHttpConfig.kt` -- DONE

Added 6 new tests to `src/test/kotlin/io/prometheus/proxy/ProxyHttpConfigTest.kt`
(total now 21 tests):

| Test                                                               | Method Covered                               |
|--------------------------------------------------------------------|----------------------------------------------|
| `getFormattedLog should include Location header for Found status`  | `getFormattedLog()` -- Found (302) branch    |
| `getFormattedLog should not include Location for non-Found status` | `getFormattedLog()` -- else branch (200 OK)  |
| `getFormattedLog should handle null status gracefully`             | `getFormattedLog()` -- null status edge case |
| `callLogging filter should accept paths starting with slash`       | `configureCallLogging()` filter logic        |
| `callLogging filter should accept root path`                       | `configureCallLogging()` filter logic        |
| `callLogging filter should reject paths not starting with slash`   | `configureCallLogging()` filter logic        |

The `getFormattedLog` tests use reflection to call the private method with mocked
`ApplicationCall` objects, verifying both the Found (with Location header) and
non-Found (without Location) formatting branches.

---

## Tier 3 -- Untested Branches Within Otherwise-Tested Methods

### `ProxyHttpRoutes.submitScrapeRequest()` -- MOSTLY DONE

This is the core scrape request handler. Seven of eight branches now have dedicated unit tests:

| Branch                     | Line(s) | Condition                                                | Status                                       |
|----------------------------|---------|----------------------------------------------------------|----------------------------------------------|
| ClosedSendChannelException | 206-211 | Agent channel closed during write                        | **DONE**                                     |
| Timeout                    | 217     | `scrapeRequest.ageDuration() >= timeoutTime`             | **DONE**                                     |
| Agent disconnected         | 217     | `!scrapeRequest.agentContext.isValid()`                  | **DONE**                                     |
| Proxy stopped              | 217     | `!proxy.isRunning` during scrape                         | **DONE**                                     |
| Missing results            | 234-238 | `scrapeRequest.scrapeResults == null` after completion   | Not covered (defensive race condition check) |
| Content type parse error   | 243-250 | `ContentType.parse()` throws, falls back to `Text.Plain` | **DONE**                                     |
| Zipped content             | 271     | `srZipped` triggers `srContentAsZipped.unzip()`          | **DONE**                                     |
| Non-success status         | 254-264 | `!statusCode.isSuccess()` returns failure with reason    | **DONE**                                     |

### `ProxyHttpRoutes.processRequestsBasedOnPath()` -- DONE (via integration tests)

Both branches are now covered by Tier 4 integration tests:

| Branch                          | Status   | Description                                                                           |
|---------------------------------|----------|---------------------------------------------------------------------------------------|
| `agentContextInfo == null`      | **DONE** | Tested in `handleClientRequests should return NotFound for unregistered path`         |
| `agentContextInfo.isNotValid()` | **DONE** | Tested in `handleClientRequests should return NotFound for invalidated agent context` |

### `ProxyHttpRoutes.processRequests()`

| Branch               | Description                                                                                                             |
|----------------------|-------------------------------------------------------------------------------------------------------------------------|
| Mixed status codes   | Multiple agents return different HTTP statuses; logic selects OK if any agent returned OK, otherwise uses first status. |
| Null `okContentType` | Falls back to first content type when no agent returned 200 OK.                                                         |

### `AgentHttpService.kt` -- `newHttpClient()` (private, but exercises key logic)

| Branch                     | Description                                                                                  |
|----------------------------|----------------------------------------------------------------------------------------------|
| TLS trust-all              | `trustAllX509Certificates = true` installs `TrustAllX509TrustManager` SSL bypass.            |
| Retry configuration        | `maxRetries > 0` enables exponential backoff retry, excluding 404 responses.                 |
| CIO timeout fallback       | `cioTimeoutSecs != 90 && httpClientTimeoutSecs == 90` selects deprecated timeout.            |
| Basic auth client key      | `ClientKey.hasAuth()` triggers basic auth installation in HTTP client.                       |
| Response handler assertion | `requireNotNull(result)` on line 116 -- defensive check if response handler is never called. |

### `AgentGrpcService.kt` -- PARTIALLY COVERED

| Branch                                                   | Status      | Description                                                                                    |
|----------------------------------------------------------|-------------|------------------------------------------------------------------------------------------------|
| `sendHeartBeat()` -- `StatusRuntimeException(NOT_FOUND)` | **DONE**    | Tested in `sendHeartBeat should handle NOT_FOUND status from proxy`                            |
| `sendHeartBeat()` -- generic exception                   | **DONE**    | Tested in `sendHeartBeat should handle generic exception without throwing`                     |
| `sendHeartBeat()` -- `valid=false` response              | **DONE**    | Tested in `sendHeartBeat should handle NOT_FOUND status from proxy` (triggers NOT_FOUND throw) |
| `registerAgent()` -- `agentId.isEmpty()`                 | **DONE**    | Tested in `registerAgent should throw on empty agentId`                                        |
| `connectAgent()` -- metrics recording                    | Not covered | Success/failure counter labels not verified.                                                   |
| `resetGrpcStubs()` -- old channel shutdown               | Not covered | Verified for deadlock safety but not for actual resource cleanup.                              |

### `EnvVars.kt`

| Branch                                   | Description                                                                                                       |
|------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `getEnv(Int)` with non-numeric env var   | Throws `IllegalArgumentException`. Not testable without env var manipulation (requires JUnit Pioneer or similar). |
| `getEnv(Long)` with non-numeric env var  | Throws `IllegalArgumentException`. Not testable without env var manipulation.                                     |
| `getEnv(String)` with actual env var set | Only default-value fallback path is tested.                                                                       |

### `ScrapeRequestWrapper.kt` -- DONE

Added 2 new tests to `src/test/kotlin/io/prometheus/proxy/ScrapeRequestWrapperTest.kt`
(total now 21 tests):

| Branch                                                                          | Status          | Description                                                                                        |
|---------------------------------------------------------------------------------|-----------------|----------------------------------------------------------------------------------------------------|
| Constructor with empty `agentId`                                                | **DONE**        | Tested via mocked `AgentContext` with empty agentId -- throws `IllegalArgumentException`           |
| Multiple `markComplete()` calls                                                 | **DONE**        | Verified idempotency -- second call does not throw                                                 |
| `awaitCompleted()` after `markComplete()` but before `scrapeResults` assignment | Already covered | Existing test `awaitCompleted should return false when channel closed without results` covers this |

### `ChunkedContext.kt` -- DONE

Added 4 new tests to `src/test/kotlin/io/prometheus/proxy/ChunkedContextTest.kt`
(total now 22 tests):

| Branch                                             | Status   | Description                                                             |
|----------------------------------------------------|----------|-------------------------------------------------------------------------|
| `applySummary()` called before any chunks          | **DONE** | Tested with 0 chunks vs expected 1 -- throws `ChunkValidationException` |
| `applySummary()` with zero expected chunks         | **DONE** | Tested with both sides agreeing on 0 chunks -- succeeds                 |
| `applyChunk()` with empty data array               | **DONE** | Tested with zero-length `ByteArray` -- succeeds with 0 bytes            |
| `applyChunk()` with `chunkByteCount > data.length` | **DONE** | Throws `ArrayIndexOutOfBoundsException`                                 |

---

## Tier 4 -- Weak or Superficial Test Files

### `ProxyHttpConfigTest.kt` -- SIGNIFICANTLY IMPROVED (was 15 tests, now 24 tests)

Added `getFormattedLog()` tests covering both branches (Found vs non-Found),
`configureCallLogging()` filter logic tests, and 3 `configureKtorServer()` integration
tests using embedded servers with a real `Proxy` instance:

| Test                                                                  | Method Covered                                           |
|-----------------------------------------------------------------------|----------------------------------------------------------|
| `getFormattedLog should include Location header for Found status`     | `getFormattedLog()` -- Found (302) branch                |
| `getFormattedLog should not include Location for non-Found status`    | `getFormattedLog()` -- else branch (200 OK)              |
| `getFormattedLog should handle null status gracefully`                | `getFormattedLog()` -- null status edge case             |
| `callLogging filter should accept paths starting with slash`          | `configureCallLogging()` filter logic                    |
| `callLogging filter should accept root path`                          | `configureCallLogging()` filter logic                    |
| `callLogging filter should reject paths not starting with slash`      | `configureCallLogging()` filter logic                    |
| `configureKtorServer should add X-Engine default header to responses` | `configureKtorServer()` -- DefaultHeaders plugin         |
| `configureKtorServer should handle NotFound via StatusPages`          | `configureKtorServer()` -- StatusPages 404 handler       |
| `configureKtorServer should handle exceptions via StatusPages`        | `configureKtorServer()` -- StatusPages exception handler |

The `configureKtorServer` integration tests run embedded HTTP servers with the full plugin
configuration applied, verifying DefaultHeaders (X-Engine), StatusPages (404/500), and
Compression are all installed correctly.

Still not covered:

- `configureCompression()` actual compression behavior on responses

### `ProxyHttpRoutesTest.kt` -- SIGNIFICANTLY IMPROVED (was 14 tests, now 33 tests)

Added `logActivityForResponse()` tests, `authHeaderWithoutTlsWarned` single-fire AtomicBoolean
test, 8 `submitScrapeRequest()` tests, and 7 integration tests using embedded HTTP servers
with `handleRequests()`:

| Test                                                                                 | Method Covered                                                        |
|--------------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| `logActivityForResponse should format success status without failure reason`         | `logActivityForResponse()` -- success branch                          |
| `logActivityForResponse should include failure reason for non-success status`        | `logActivityForResponse()` -- failure branch (404)                    |
| `logActivityForResponse should include failure reason for ServiceUnavailable`        | `logActivityForResponse()` -- failure branch (503)                    |
| `authHeaderWithoutTlsWarned should be a single-fire AtomicBoolean`                   | `createScrapeRequest()` -- auth warning logic                         |
| `submitScrapeRequest should return agent_disconnected on ClosedSendChannelException` | `submitScrapeRequest()` -- ClosedSendChannelException                 |
| `submitScrapeRequest should return timed_out when scrape request times out`          | `submitScrapeRequest()` -- timeout                                    |
| `submitScrapeRequest should return timed_out when agent disconnects during scrape`   | `submitScrapeRequest()` -- agent disconnect                           |
| `submitScrapeRequest should return timed_out when proxy stops during scrape`         | `submitScrapeRequest()` -- proxy stopped                              |
| `submitScrapeRequest should return success for valid non-zipped response`            | `submitScrapeRequest()` -- happy path                                 |
| `submitScrapeRequest should unzip zipped response content`                           | `submitScrapeRequest()` -- zipped content                             |
| `submitScrapeRequest should fallback to plain text on content type parse error`      | `submitScrapeRequest()` -- ContentType fallback                       |
| `submitScrapeRequest should return path_not_found for non-success status`            | `submitScrapeRequest()` -- non-success status                         |
| `handleClientRequests should return ServiceUnavailable when proxy is not running`    | `handleClientRequests()` -- proxy stopped branch                      |
| `handleClientRequests should return NotFound for favicon request`                    | `handleClientRequests()` -- favicon branch                            |
| `handleClientRequests should return NotFound for unregistered path`                  | `handleClientRequests()` -- processRequestsBasedOnPath null branch    |
| `handleClientRequests should return 42 for blitz request`                            | `handleClientRequests()` -- blitz branch                              |
| `handleClientRequests should return NotFound for invalidated agent context`          | `handleClientRequests()` -- processRequestsBasedOnPath invalid branch |
| `handleServiceDiscoveryEndpoint should return JSON when enabled`                     | `handleServiceDiscoveryEndpoint()` -- SD enabled                      |
| `handleServiceDiscoveryEndpoint should not register when sdEnabled is false`         | `handleServiceDiscoveryEndpoint()` -- SD disabled                     |

The integration tests use embedded Ktor servers with `ProxyHttpRoutes.handleRequests()` and
`spyk(Proxy(...))` instances, exercising the full HTTP request dispatch pipeline end-to-end.

Still not covered:

- `processRequests()` -- response aggregation from multiple agents (mixed status codes, empty results)
- `executeScrapeRequests()` -- concurrent scrape dispatch (tested indirectly via integration tests)

---

## Summary

| Priority   | Original | Now Covered | Remaining | Description                                                                                                                                                         |
|------------|----------|-------------|-----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| High       | 8        | 7           | 1         | Streaming gRPC methods, `Proxy`, `Agent`, and `submitScrapeRequest` error branches all covered. Remaining: `Agent.connectToProxy()`                                 |
| Medium     | 9        | 7           | 2         | Auth warning, heartbeat errors, registerAgent guard, timeout/disconnect/stopped all covered. Remaining: HTTP client config branches, missing-results race           |
| Low        | 8        | 8           | 0         | All low-priority items covered: `getFormattedLog`, `configureCallLogging`, `logActivityForResponse`, `ScrapeRequestWrapper` edge cases, `ChunkedContext` edge cases |
| Weak tests | 2        | 2 improved  | 0         | Both `ProxyHttpConfigTest` and `ProxyHttpRoutesTest` significantly improved with integration tests                                                                  |

### New Tests Added

| File                          | New Tests        | Total Tests |
|-------------------------------|------------------|-------------|
| `ProxyTest.kt`                | 15 (new file)    | 15          |
| `AgentTest.kt`                | 14 (new file)    | 14          |
| `AgentGrpcServiceTest.kt`     | 10 added         | 38          |
| `ProxyHttpRoutesTest.kt`      | 19 added         | 33          |
| `ProxyHttpConfigTest.kt`      | 9 added          | 24          |
| `ScrapeRequestWrapperTest.kt` | 2 added          | 21          |
| `ChunkedContextTest.kt`       | 4 added          | 22          |
| **Total**                     | **73 new tests** |             |

### Remaining Priority Order

1. `ProxyHttpRoutes.processRequests()` mixed status codes and empty results -- requires multiple concurrent agents
2. `Agent.startHeartBeat()` -- timing-sensitive but testable with mocked clock/delays
3. `Agent.connectToProxy()` reconnection loop
4. `EnvVars.getEnv(Int/Long)` error paths -- requires env var manipulation (JUnit Pioneer or similar)
