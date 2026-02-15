# Codebase Bug Review — 2026-02-15

## Confirmed Bugs (Both Fixed)

### Bug 1: `mergeContentTexts()` inconsistent trailing whitespace (Medium) — FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt:178`
**Status:** Fixed and verified with tests

When merging OpenMetrics content from multiple consolidated agents, lines with `# EOF` get their
trailing whitespace stripped via `trimEnd()`, but lines without `# EOF` preserve raw trailing
whitespace. Since `joinToString("\n")` is used to combine entries, any trailing `\n` on individual
items produces double-newlines in the merged output.

**Before (buggy):**

```kotlin
// lines 171-183
var hasEof = false
val stripped = nonEmpty.map { result ->
  val text = result.contentText
  val trimmed = text.trimEnd()
  if (trimmed.endsWith("# EOF")) {
    hasEof = true
    trimmed.removeSuffix("# EOF").trimEnd()
  } else {
    text   // BUG: should be `trimmed`
  }
}
```

**After (fixed):**

```kotlin
  } else {
trimmed   // Fixed: use trimmed instead of text
}
```

**Example of the bug:**

- Agent1 returns: `"metric1 42\n# EOF\n"` — stripped to `"metric1 42"`
- Agent2 returns: `"metric2 99\n"` — kept as `"metric2 99\n"` (trailing newline preserved)
- Joined: `"metric1 42\nmetric2 99\n"`
- Final: `"metric1 42\nmetric2 99\n\n# EOF"` (double newline before EOF)

**Impact:** Produces malformed OpenMetrics output in consolidated mode. Some Prometheus parsers
may reject or misinterpret the extra blank line.

**Fix:** Changed line 178 from `text` to `trimmed`.

**Tests added** in `ProxyHttpRoutesTest.kt`:

- `"Bug #14: mergeContentTexts should not produce double newlines with trailing whitespace"`
- `"Bug #14: mergeContentTexts should trim trailing whitespace from non-EOF results"`
- `"Bug #14: mergeContentTexts should handle mixed trailing whitespace with EOF"`
- `"Bug #14: mergeContentTexts should produce clean output with no trailing whitespace per entry"`

---

### Bug 2: Integer overflow in `ChunkedContext.totalByteCount` (Low-Medium) — FIXED

**File:** `src/main/kotlin/io/prometheus/proxy/ChunkedContext.kt:36-49`
**Status:** Fixed and verified with tests

**Before (buggy):**

```kotlin
var totalByteCount = 0       // Int (max ~2.1 GB)
private set

fun applyChunk(...) {
  totalChunkCount++
  totalByteCount += chunkByteCount    // could overflow Int.MAX_VALUE

  if (totalByteCount > maxZippedContentSize)   // negative int < Long -> check passes
    throw ChunkValidationException(...)
```

**After (fixed):**

```kotlin
var totalByteCount = 0L      // Long — prevents overflow
private set
```

Also required updating `applySummary` comparison to use explicit `.toLong()` conversion:

```kotlin
if (totalByteCount != summaryByteCount.toLong())
```

`totalByteCount` was `Int` but compared against `maxZippedContentSize` (a `Long`). If accumulated
bytes exceeded `Int.MAX_VALUE` (~2.1 GB), the value would wrap negative, bypassing the size limit
check. The `maxZippedContentSize` config limit would be silently ignored, allowing unbounded memory
allocation in the `ByteArrayOutputStream`.

**Impact:** Theoretical zip-bomb-style DoS for very large chunked transfers. Unlikely to trigger
under normal configs but technically exploitable.

**Fix:** Changed `totalByteCount` from `Int` to `Long`, added `.toLong()` conversion in `applySummary`.

**Tests added** in `ChunkedContextTest.kt`:

- `"Bug #15: totalByteCount should not overflow with large accumulated chunks"`
- `"Bug #15: totalByteCount as Long should reject content exceeding maxZippedContentSize"`
- `"Bug #15: totalByteCount should correctly compare against Long maxZippedContentSize"`

---

## Build Verification

All fixes verified: `./gradlew build` — BUILD SUCCESSFUL (detekt, lint, and all tests pass).

---

## Items Reviewed and Found Correct

| Area                                                                 | Assessment                                                                                                                             |
|----------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `ScrapeRequestWrapper.awaitCompleted()`                              | Correct: timeout returns null/false; completion and failure both set `scrapeResults` appropriately                                     |
| `readRequestsFromProxy` cleanup logic                                | Correct: `ProxyServerTransportFilter.transportTerminated()` handles the normal case; `finally` block handles `transportFilterDisabled` |
| `AgentContext.markActivityTime()` two non-atomic writes              | Benign: stale-agent cleanup uses a 60s window; sub-ms inconsistency is irrelevant                                                      |
| `ScrapeRequestManager` TOCTOU on `ConcurrentHashMap` get+use         | Safe: wrapper reference remains valid after map removal; `markComplete()` uses CAS for idempotency                                     |
| `ByteArrayOutputStream` not closed in `ChunkedContext`               | BAOS `close()` is a documented no-op                                                                                                   |
| `writeChunkedResponsesToProxy` returns `EMPTY_INSTANCE` on error     | Individual failures handled by `failScrapeRequest()`; stream-level errors detected by both sides                                       |
| `AgentConnectionContext.close()` synchronization                     | Correct: `synchronized(closeLock)` + `disconnected` flag ensures idempotent close                                                      |
| `decrementBacklog()` CAS loop                                        | Correct: standard lock-free pattern; contention bounded by `maxConcurrentHttpClients`                                                  |
| Channel close redundancy in `writeResponsesToProxyUntilDisconnected` | Safe: `Channel.close()` is idempotent; multiple close paths are defensive                                                              |
| `registerPath` non-null assertion `addPathResult!!`                  | Safe: guarded by `!isValid` which is true only when `addPathResult != null`                                                            |
| Displaced agent invalidation in `ProxyPathManager.addPath()`         | Safe: entire block runs inside `synchronized(pathMap)`                                                                                 |
| `HttpClientCache` cleanup ordering                                   | Correct: `scope.cancel()` before `accessMutex.withLock` avoids deadlock (documented in comments)                                       |
