# Codebase Bug Report

## 1. Unbounded Concurrency in Agent (Fixed)

**File:** `src/main/kotlin/io/prometheus/Agent.kt`, `src/main/kotlin/io/prometheus/agent/AgentConnectionContext.kt`,
`src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`

**Description:**
The Agent previously processed incoming scrape requests from the Proxy using an unbounded loop that launched a new
coroutine for every request, regardless of the configured concurrency limits.

**Solution:**

1. **Bounded Channel:** Modified `AgentConnectionContext` to use a bounded `scrapeRequestActionsChannel` (default
   capacity 128, or `scrapeRequestBacklogUnhealthySize * 2`). This provides physical backpressure to the gRPC reader.
2. **Semaphore Before Launch:** Updated the loop in `Agent.kt` to call `semaphore.acquire()` **before**
   `launch { ... }`. This ensures that coroutine objects are only created when there is a free execution slot (or when
   one is about to become free), preventing the accumulation of thousands of waiting coroutines in memory.
3. **Accurate Backlog Tracking:** Updated `AgentGrpcService.kt` to increment `scrapeRequestBacklogSize` before sending
   to the channel, and `Agent.kt` to decrement it in the `finally` block of the processing coroutine. This ensures the
   health check correctly reflects requests waiting in the bounded channel + requests currently executing.

**Status:** Fixed. Verified with unit tests in `AgentTest.kt` and `AgentConnectionContextTest.kt`.

## 2. Inefficient Polling Loop in Proxy Scrape Request Handling (Fixed)

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt`, `src/main/kotlin/io/prometheus/Proxy.kt`,
`src/main/kotlin/io/prometheus/proxy/ScrapeRequestManager.kt`

**Description:**
The Proxy previously used a polling loop with a 500ms interval (`scrapeRequestCheckMillis`) to wait for scrape results,
checking `scrapeRequest.awaitCompleted(checkTime)` repeatedly.

**Solution:**

1. **Event-Driven Unblocking:** Refactored `submitScrapeRequest` to use a single, non-polling call to
   `scrapeRequest.awaitCompleted(timeoutTime)`.
2. **Immediate Disconnect Handling:** Added `failAllScrapeRequests(agentId)` to `ScrapeRequestManager`. This is called
   from `Proxy.removeAgentContext()` whenever an agent disconnects, immediately failing all in-flight requests for that
   agent with a `502 Bad Gateway` status and unblocking the HTTP handlers.
3. **Clean Shutdown:** Added `failAllInFlightScrapeRequests()` to `ScrapeRequestManager`, called during
   `Proxy.shutDown()` to immediately notify all waiting Prometheus clients that the proxy is stopping.

These changes significantly reduce CPU overhead and context switching while improving responsiveness to network events.

**Status:** Fixed. Verified with `ProxyHttpRoutesTest.kt` (including new disconnect and shutdown test cases).

## 3. Potential Exception Swallowing in Agent HTTP Client (Fixed)

**File:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt`

**Description:**
The `fetchContent` previously used `runCatching` which caught all `Throwable` including `CancellationException`. This
swallowed system-level cancellations (like shutdown) and treated them as scrape failures.

**Solution:**
Refactored the exception handling to explicitly distinguish between Ktor's `HttpRequestTimeoutException` (which should
be caught and reported as an HTTP 408) and other `CancellationException` types (which should be rethrown to support
structured concurrency and clean shutdown). Added a robust cause-chain search to identify timeouts even when wrapped.

**Status:** Fixed. Verified with `AgentHttpServiceTest.kt` (including new cancellation and timeout-propagation test
cases).

## 4. Missing Protection Against "Zip Bombs" (Fixed)

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt`, `src/main/kotlin/io/prometheus/proxy/ProxyUtils.kt`,
`src/main/java/io/prometheus/common/ConfigVals.java`, `config/config.conf`

**Description:**
The Proxy previously unzipped gzipped content from the Agent in memory without size limits, making it vulnerable to "zip
bomb" attacks where small compressed payloads expand into massive uncompressed data, causing OOM errors.

**Solution:**

1. **Size-Limited Unzipping:** Enhanced `ProxyUtils.unzip()` (now accepting a `Long` limit) to use a byte-counting
   buffer during decompression. If the unzipped size exceeds the limit, it throws a `ZipBombException`.
2. **Configurable Threshold:** Added `proxy.internal.maxUnzippedContentSizeMBytes` (defaulting to 10MB) to `config.conf`
   and `ConfigVals.java` to allow tuning the safety threshold.
3. **Graceful Error Handling:** Updated `ProxyHttpRoutes.kt` to catch `ZipBombException` and return an
   `HTTP 413 Payload Too Large` response to the client, preventing a crash.

**Status:** Fixed. Verified with `ProxyHttpRoutesTest.kt` using a synthetic zip bomb payload.

## 5. Fatal Error Swallowing in Agent (Fixed)

**File:** `src/main/kotlin/io/prometheus/Agent.kt`

**Description:**
The Agent's main reconnection loop used a catch-all block that caught `Throwable`, including JVM `Error` subclasses like
`OutOfMemoryError`. This caused the Agent to attempt to reconnect even when in a fatally corrupted state.

**Solution:**
Updated the exception handling in `Agent.kt` to explicitly re-throw `Error` subclasses. This ensures that the JVM
terminates when a fatal error occurs, rather than continuing in a degraded or unpredictable state.

**Status:** Fixed. Verified by code review and manual inspection of exception handling logic.
