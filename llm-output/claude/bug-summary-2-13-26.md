# Bug Summary - 2/13/2026

Comprehensive review of the prometheus-proxy codebase (`src/main/kotlin/io/prometheus/`).

---

## MEDIUM Severity

### 1. ~~errorCode() doesn't walk cause chain for wrapped timeout exceptions~~ FIXED

**File:** `common/ScrapeResults.kt`
**Status:** FIXED — Extracted a `hasTimeoutCause()` helper that walks the exception cause chain
looking for any of the four timeout types. `errorCode()` now calls this first, returning 408
RequestTimeout if any exception in the chain is a timeout. Added 6 tests covering wrapped,
deeply nested, and non-timeout cause chains.

---

### 2. ~~Overly aggressive HTTP retry policy retries client errors~~ FIXED

**File:** `agent/AgentHttpService.kt`
**Status:** FIXED — Changed `retryIf` condition from `!response.status.isSuccess() && response.status
!= HttpStatusCode.NotFound` to `response.status.value in 500..599` so only server errors are retried.
Added 6 tests confirming 4xx errors (400, 401, 403, 404) are not retried and 5xx errors (500, 503)
are retried.

---

## LOW Severity

### 3. ~~`runCatching` swallows `CancellationException` in HttpClientCache cleanup~~ FIXED

**File:** `agent/HttpClientCache.kt`
**Status:** FIXED — Changed `runCatching` to `runCatchingCancellable` in the cleanup coroutine so
`CancellationException` is properly rethrown instead of being caught and logged as an error during
normal shutdown. Added 2 tests: one confirming `close()` terminates the cleanup coroutine promptly
via cancellation propagation, and one confirming non-cancellation errors are still handled by the
`getOrElse` handler.

---

### 4. ~~mergeContentTexts includes empty content from failed agents~~ FIXED

**File:** `proxy/ProxyHttpRoutes.kt`
**Status:** FIXED — Added a filter in `mergeContentTexts` to skip results with empty `contentText`
before merging. When all results are empty the function returns `""`. When only one non-empty result
remains after filtering, it is returned directly (preserving the single-result fast path). Added 5
tests covering: single failed agent excluded, multiple failed agents with one success, all agents
failed, mixed successes with a failed agent in between, and failed agents combined with OpenMetrics
EOF handling.

---

### 5. ~~Misleading ERROR log level for normal timeout condition~~ FIXED

**File:** `proxy/ScrapeRequestManager.kt:50`
**Status:** FIXED — Changed `logger.error` to `logger.warn` and improved the message to include
"(likely timed out)" for clarity. This is a normal, expected condition when a scrape request times
out and the agent's late response arrives after the wrapper has been removed from the map. Added 3
tests: one confirming the log is at WARN level, one confirming no ERROR-level log is emitted, and
one confirming the scrapeId appears in the log message.

---

### 6. ~~Redundant double channel close in AgentGrpcService~~ FIXED

**File:** `agent/AgentGrpcService.kt`
**Status:** FIXED — Removed the redundant outer `try/finally` block from
`writeResponsesToProxyUntilDisconnected`. The inner finally (producer coroutine) is the sole owner
of channel lifecycle and is sufficient to signal consumers' `consumeAsFlow()` to complete. Added 3
tests: one verifying producer-side close terminates `consumeAsFlow` consumers, one verifying
producer-side close on error still terminates consumers, and one verifying the full
`writeResponsesToProxyUntilDisconnected` function completes correctly with only the inner close.

---

## Investigated and Cleared

- **ScrapeRequestManager ConcurrentHashMap TOCTOU:** The race between timeout removal and late
  result assignment is benign by design — late results are logged and discarded.
- **AgentContext queue/channel coordination:** The UNLIMITED channel and ConcurrentLinkedQueue
  maintain FIFO correspondence correctly. `invalidate()` drains the queue properly.
- **ProxyPathManager snapshot isolation:** `getAgentContextInfo` returns a copy of the agent list,
  which is correct. Stale agents in the snapshot are handled by `ClosedSendChannelException` catches.
- **HttpClientCache.close() vs cleanup coroutine:** `scope.cancel()` before mutex acquisition is
  correct and prevents deadlock as documented.
- **ChunkedContext thread safety:** Each `ChunkedContext` is accessed sequentially within a single
  flow collection — no concurrent access occurs.
- **AgentConnectionContext.sendScrapeResults race:** Uses `trySend()` with closed-channel check,
  correctly avoiding `ClosedSendChannelException` during disconnect.
