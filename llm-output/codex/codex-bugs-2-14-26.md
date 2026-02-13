# Bug Fix Summary (2026-02-14)

## 1) Query param concatenation bug

**Symptom:** Agent requests broke when the configured scrape URL already included query params, producing URLs like
`...?a=b?c=d`.
**Fix:** Added `Utils.appendQueryParams()` to append decoded params using `?` or `&` as appropriate, and updated the
agent fetch path to use it.
**Files:**

- `src/main/kotlin/io/prometheus/common/Utils.kt`
- `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt`

**Tests:**

- Added unit coverage for the new helper (`UtilsTest`).
- Added integration coverage for appending params to a URL that already has a query string (`AgentHttpServiceTest`).

---

## 2) Invalid gzip response handling

**Symptom:** Corrupted gzipped payloads could throw and bubble out of `submitScrapeRequest`, causing a 500 and losing
the error context.
**Fix:** Catch `IOException` during unzip and return a controlled `502 Bad Gateway` with `updateMsg = "invalid_gzip"`.
**Files:**

- `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt`

**Tests:**

- Added a test that feeds invalid gzip bytes and asserts a `BadGateway` response (`ProxyHttpRoutesTest`).

---

## 3) gRPC stream failure cleanup

**Symptom:** If one of the streaming RPCs (`writeResponsesToProxy` / `writeChunkedResponsesToProxy`) failed, the
producer could keep sending into an unconsumed channel, risking unbounded memory growth and hanging coroutine scopes.
**Fix:** On non-cancellation failures, proactively close the connection context and both channels to terminate all flows
cleanly.
**Files:**

- `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`

**Tests:**

- Added a test that forces a writer failure and asserts the connection context is closed (`AgentGrpcServiceTest`).

---

## Lint/Detekt cleanups (requested)

**Actions:**

- Suppressed `LargeClass` in `AgentHttpServiceTest` (pre-existing size, unchanged scope).
- Removed unused local collection to satisfy detekt (`HttpClientCacheTest`).
- Reduced `mergeContentTexts` return count by removing an unnecessary early return (`ProxyHttpRoutes.kt`).

**Notes:**

- Existing test warnings in `AgentContextTest` and `ProxyPathManagerTest` remain unchanged (pre-existing).
