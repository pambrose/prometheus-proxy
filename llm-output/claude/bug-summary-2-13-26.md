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

### 2. Overly aggressive HTTP retry policy retries client errors

**File:** `agent/AgentHttpService.kt:220-221`

The retry policy retries on **all** non-success status codes except 404:

```kotlin
retryIf(maxRetries) { _, response ->
  !response.status.isSuccess() && response.status != HttpStatusCode.NotFound
}
```

This means 400 Bad Request, 401 Unauthorized, 403 Forbidden, 405 Method Not Allowed, etc. are all
retried. Client errors (4xx) are typically not transient — the request won't change between retries.
Only server errors (5xx) and connection-level failures should be retried.

**Impact:** Unnecessary load on target services, added latency for non-transient failures, and
potential rate limiting from repeated 401/403 requests.

**Fix:** Change the condition to only retry server errors:

```kotlin
retryIf(maxRetries) { _, response ->
  response.status.value in 500..599
}
```

---

## LOW Severity

### 3. `runCatching` swallows `CancellationException` in HttpClientCache cleanup

**File:** `agent/HttpClientCache.kt:62`

The cleanup coroutine uses `runCatching` instead of `runCatchingCancellable`. This is a well-known
Kotlin anti-pattern — `runCatching` catches `CancellationException`, breaking structured concurrency.
When `scope.cancel()` is called during shutdown, the cancellation is caught and logged as an error
rather than propagated immediately.

```kotlin
val clientsToClose =
  runCatching {                    // should be runCatchingCancellable
    accessMutex.withLock {
      cleanupExpiredEntries()
      drainPendingCloses()
    }
  }.getOrElse { e ->
    logger.error(e) { "Error during HTTP client cache cleanup" }
    emptyList()
  }
```

The `while (isActive)` loop mitigates the impact (the coroutine exits on the next iteration check),
but the cancellation is still swallowed for the current iteration.

**Impact:** Slightly delayed shutdown of the cleanup coroutine; CancellationException logged as an
error during normal shutdown.

---

### 4. mergeContentTexts includes empty content from failed agents

**File:** `proxy/ProxyHttpRoutes.kt:162-179`

When consolidated paths have multiple agents and some fail, `processRequests` merges all results
including those with empty `contentText` from failed agents. This produces extra newlines in the
merged output:

```kotlin
// If agent A returns "metric_a 1\n" and agent B fails (contentText=""):
// Result: "metric_a 1\n\n"  (extra newline from empty string join)
```

**Impact:** Extra blank lines in scraped metrics output. Usually harmless for Prometheus exposition
format, but could be more problematic with strict OpenMetrics parsers.

**Fix:** Filter out results with non-success status codes before merging, or skip empty contentText
entries in the join.

---

### 5. Misleading ERROR log level for normal timeout condition

**File:** `proxy/ScrapeRequestManager.kt:50`

When a scrape request times out, `submitScrapeRequest`'s finally block removes the wrapper from
the map. If the agent's late response then arrives, `assignScrapeResults` logs at **ERROR** level:

```kotlin
scrapeRequestMap[scrapeId]
  ?.also { wrapper -> ... }
  ?: logger.error { "Missing ScrapeRequestWrapper for scrape_id: $scrapeId" }
```

This is a normal, expected condition during timeouts and should be logged at WARN or DEBUG.

**Impact:** Log noise in production; may trigger unnecessary alerts.

---

### 6. Redundant double channel close in AgentGrpcService

**File:** `agent/AgentGrpcService.kt:404-405, 431-432`

The `nonChunkedChannel` and `chunkedChannel` are closed in the inner `finally` block (lines 404-405)
and again in the outer `finally` block (lines 431-432). While `Channel.close()` is idempotent, this
indicates unclear ownership of the channel lifecycle.

```kotlin
launch(Dispatchers.IO) {
  try {
    ...
  } finally {
    nonChunkedChannel.close()   // first close (producer — correct owner)
    chunkedChannel.close()
  }
}
// ...
} finally {
nonChunkedChannel.close()       // redundant close
chunkedChannel.close()
}
```

**Impact:** No functional impact, but the redundancy obscures which code path owns channel cleanup.

**Fix:** Remove the outer finally close calls; the inner finally (producer) is the correct owner.

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
