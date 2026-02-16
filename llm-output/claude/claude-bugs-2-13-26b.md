# Prometheus Proxy Bug Review (Round 2) - 2026-02-13

## Medium-High Impact Bugs

### 1. Consolidated agent metrics use multi-line label values, causing cardinality explosion

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt:142, 101`

```kotlin
val updateMsgs: String = results.joinToString("\n") { it.updateMsg }  // line 142
// ...
incrementScrapeRequestCount(proxy, responseResults.updateMsg)          // line 101
```

For consolidated paths with multiple agents, `updateMsgs` joins results with `"\n"` — producing values like
`"success\nsuccess"` or `"success\ntimed_out"`. This string is passed as a Prometheus label value at
`ProxyUtils.kt:111`:

```kotlin
proxy.metrics { scrapeRequestCount.labels(type).inc() }
```

Each unique combination creates a separate time series. With N agents, there are 2^N possible result permutations (
success/failure per agent), leading to unbounded label cardinality. A consolidated scrape with 3 successful agents
increments the `"success\nsuccess\nsuccess"` series once instead of incrementing `"success"` three times.

**Fix:** Either count each agent result individually, or use only the aggregate result (e.g., "success" if all OK, "
partial_failure" if mixed).

## Medium Impact Bugs

### 2. Gzip threshold uses character count instead of byte count (incomplete fix from Round 1 Bug #6)

**File:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:202`

```kotlin
val zipped = content.length > agent.options.minGzipSizeBytes
```

`content.length` returns character count, but `minGzipSizeBytes` is a byte threshold. The Round 1 bug fix (Bug #6)
corrected the analogous `maxContentLength` check at line 189 to use `content.encodeToByteArray().size.toLong()`, but
this gzip threshold comparison was not updated to match. For multi-byte UTF-8 content, the character count can be
significantly less than the byte count, causing content that exceeds the byte threshold to skip gzip compression.

**Fix:** Change `content.length` to `content.encodeToByteArray().size` (the byte array is already computed on line 189,
so store it in a variable and reuse it).

### 3. HTTP retry can cause total scrape time to far exceed proxy timeout

**File:** `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:251-264`

```kotlin
install(HttpRequestRetry) {
  retryOnException(maxRetries)
  retryIf(maxRetries) { _, response -> response.status.value in 500..599 }
  exponentialDelay()
}
```

The `timeout { requestTimeoutMillis = scrapeTimeout }` at line 155 applies per attempt, not to total retry time. With
`maxRetries=3` and `scrapeTimeoutSecs=10`, the total time could be `3 * 10s + exponential delays ≈ 37s+`. Meanwhile, the
proxy's `scrapeRequestTimeoutSecs` will expire and return a timeout to Prometheus while the agent continues retrying in
the background, wasting resources and potentially creating stale responses.

**Fix:** Either set a total timeout wrapper around the retry loop, or document that
`scrapeMaxRetries * scrapeTimeoutSecs` must be less than `scrapeRequestTimeoutSecs`.

### 4. Compression priorities inverted: deflate preferred over gzip

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyHttpConfig.kt:97-104`

```kotlin
gzip {
  priority = 1.0
}
deflate {
  priority = 10.0
  minimumSize(1024)
}
```

In Ktor, higher priority values are preferred. This makes `deflate` the preferred compression, but `gzip` is the
standard for Prometheus metrics. Additionally, the `minimumSize(1024)` only applies to deflate, so responses under 1024
bytes get no deflate compression and fall through to gzip — creating inconsistent compression behavior depending on
payload size.

**Fix:** Swap the priorities (gzip=10.0, deflate=1.0), or remove deflate entirely since Prometheus tooling universally
expects gzip.

### 5. `scrapeRequestBacklogSize` inflated during disconnect

**File:** `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt:314-318`, `AgentConnectionContext.kt:60`

The backlog size is incremented at line 314 and decremented at `Agent.kt:306` when the action completes. However,
`AgentConnectionContext.close()` calls `scrapeRequestActionsChannel.cancel()` (line 60), which discards all buffered
items. Any actions already sent to the channel but not yet consumed have their increments recorded but their decrements
will never execute. While `scrapeRequestBacklogSize` is reset on reconnect, the inflated value during the disconnect
window could trigger false unhealthy health check results.

**Fix:** Reset `scrapeRequestBacklogSize` to 0 when `AgentConnectionContext.close()` is called, or switch from
`cancel()` to `close()` and drain remaining items with decrement.

## Low Impact Bugs

### 6. SD disabled log message double-prepends "/"

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt:75`

```kotlin
logger.info { "Not adding /${proxy.options.sdPath} service discovery endpoint" }
```

When SD is enabled (line 67), the path goes through `ensureLeadingSlash()`. When disabled, the log manually prepends
`/`. If `sdPath` already starts with `/`, the log shows `//discovery` instead of `/discovery`.

**Fix:** Use `proxy.options.sdPath.ensureLeadingSlash()` in the disabled log message too.

### 7. `permitKeepAliveWithoutCalls` can only be enabled, never explicitly disabled

**File:** `src/main/kotlin/io/prometheus/proxy/ProxyGrpcService.kt:112-113`

```kotlin
if (options.permitKeepAliveWithoutCalls)
  permitKeepAliveWithoutCalls(options.permitKeepAliveWithoutCalls)
```

Unlike other settings that use `-1L` as a sentinel, this boolean guard means the gRPC setting is only called when
`true`. If the gRPC library's default ever changes from `false`, there would be no way to explicitly set it to `false`.
Currently harmless because the gRPC default matches.

**Fix:** Always call `permitKeepAliveWithoutCalls(options.permitKeepAliveWithoutCalls)` unconditionally, or add a
tri-state sentinel similar to the Long options.

### 8. `hasTimeoutCause` could infinite-loop on circular exception chains

**File:** `src/main/kotlin/io/prometheus/common/ScrapeResults.kt:120-133`

```kotlin
private fun hasTimeoutCause(e: Throwable): Boolean {
  var current: Throwable? = e
  while (current != null) {
    // ... type checks ...
    current = current.cause
  }
  return false
}
```

The `while (current != null) { current = current.cause }` loop has no cycle detection. If a third-party library
constructs a circular cause chain, this hangs the scrape thread. While rare, a visited-set or iteration limit would make
this defensive.

**Fix:** Add a `val visited = mutableSetOf<Throwable>()` guard, or cap iterations at a reasonable limit (e.g., 20).

## Summary

| # | Severity        | Issue                                                                             | Location                      |
|---|-----------------|-----------------------------------------------------------------------------------|-------------------------------|
| 1 | **Medium-High** | Consolidated metrics cardinality explosion from multi-line labels                 | `ProxyHttpRoutes.kt:142`      |
| 2 | **Medium**      | Gzip threshold uses char count vs byte count (incomplete fix from Round 1 Bug #6) | `AgentHttpService.kt:202`     |
| 3 | **Medium**      | Retry timeout can far exceed proxy scrape timeout                                 | `AgentHttpService.kt:251-264` |
| 4 | **Medium**      | Compression priorities inverted (deflate > gzip)                                  | `ProxyHttpConfig.kt:97-104`   |
| 5 | **Medium**      | Backlog size inflated during disconnect                                           | `AgentGrpcService.kt:314`     |
| 6 | Low             | SD disabled log double-prepends "/"                                               | `ProxyHttpRoutes.kt:75`       |
| 7 | Low             | `permitKeepAliveWithoutCalls` guard pattern                                       | `ProxyGrpcService.kt:112`     |
| 8 | Low             | `hasTimeoutCause` no cycle detection                                              | `ScrapeResults.kt:120`        |
