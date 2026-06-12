# Prometheus-Proxy Code Review — Prioritized Findings

**Process:** 9 review dimensions → 53 raw findings → adversarial verifiers re-read the cited code for each → **44 confirmed, 9 rejected** as false positives.

The adversarial layer killed several scary-sounding-but-wrong findings, e.g. a claimed `grpcStub` data race (refuted: structured concurrency's join-before-return already orders it), an "orphaned scrape request" hang (refuted: the await is timeout-bounded and `failAllScrapeRequests` runs before `invalidate()`), and a backlog double-decrement (refuted: paths are mutually exclusive). The verifiers' corrections are folded into the recommendations below (severities downgraded, mislabeled files/methods fixed).

**Headline:** this is a mature, clean codebase — there are essentially no active production bugs. The findings are hardening, consistency, and polish. Exactly one leaks credentials in real logs today.

---

## ✅ Resolution status (updated 2026-06-12)

The findings below were worked through across PRs #149–#163. As of 2026-06-11 the campaign is
essentially complete — verified against current `master`.

**Fixed (26 of the 31 numbered items + the lower-value sub-bullets):**

- **Top priorities:** 1 (query-param value redaction in `sanitizeUrl`), 2 (`connectAgent` rethrows
  JVM `Error`s + logs stack traces), 3 (`readConfig` honors `exitOnMissingConfig`, throws
  `ConfigLoadException` for embedded agents).
- **Concurrency & lifecycle:** 4 (`HttpClientCache` `closed` flag under lock), 5 (single-coroutine
  invariant now documented at `ProxyServiceImpl.kt:227`), 6 (`removeChunkedContextsForAgent`
  proactively reclaims chunk buffers on eviction).
- **Error handling:** 7 (`writeResponsesToProxy` fail-fasts via `failScrapeRequest`), 8 (dropped
  scrape results now increment a `"dropped"` metric), 9 (content-type parse failure raised to warn).
- **Performance:** 10 (encode-once gzip via `ByteArray.zip()`), 11 (`unzip` returns a precomputed
  byte count; metric no longer re-encodes the zipped payload).
- **Idioms & API:** 16 (`chunkContentSizeKbs`/`Bytes` unit split), 17 (`PathConfig` data class),
  18 (`assignCommonOptions` dedup), 22 (`Agent.run()` decomposed via `launchConnectionTask`); plus
  lower-value: `consolidated` now `by atomicBoolean(false)`, `ClientKey` now wraps a `Credentials`
  value object.
- **Duplication:** 19 + 23 (`SslSettings` kept and **wired** into the new per-CA HTTPS trust store at
  `AgentHttpService.kt:346` rather than deleted), 20 (commented-out CT/`__test__` blocks removed),
  21 (`ConfigWrappers` overloads collapsed onto shared builders).
- **Security & TLS:** 24 (query params appended verbatim, no decode — PR #160), 25 (basic-auth cache
  key now a salted HMAC-SHA256 — PR #161).
- **Observability & config:** 26 (`CallLogging` lowered to `Level.DEBUG`), 27 (`ProxyOptions`
  port/timeout bounds validation), plus dead config keys `maxThreads`/`minThreads`/
  `scrapeRequestCheckMillis` removed (PR #157).
- **Testing gaps:** 28 (mutual-TLS rejection test), 29 (`resolveTimeoutSecs` precedence tests),
  30 (unknown-scrapeId header-drop test), 31 (wrapped-timeout→408 test), plus the ChunkedContext
  exact-boundary acceptance test.

- **12 — per-chunk `withContext(Dispatchers.IO)` over an in-memory `ByteArrayInputStream`**
  (`AgentGrpcService.kt:381`): fixed 2026-06-12. The wrapper was pure per-iteration continuation
  overhead (the enclosing coroutine already runs on IO and `ByteArrayInputStream.read()` never
  blocks); the loop now reads directly. The now-unused `withContext` import was removed.

**Still open / deferred:**

- **13–15** (micro-opts) and the **`ScrapeResults` 11-arg remodel**: intentionally deferred
  (profiling-gated / cleanup-not-fix).

**New findings fixed 2026-06-11/12 (not in the original 31):**

- **Unbounded response-body buffering on the agent** (`AgentHttpService.kt:210-218`): `bodyAsText()`
  materialized the entire response into the heap *before* the post-read size check, so a target with
  no `Content-Length` (chunked transfer encoding) or an understated one could push the agent toward
  OOM regardless of `maxContentLengthMBytes`. Fixed by reading at most `maxContentLength + 1` bytes
  via `bodyAsChannel().readRemaining(...)`, so the size guard runs against a bounded buffer. Added two
  tests (full chunked body under the limit round-trips intact; multi-byte UTF-8 round-trips on the
  text path after the `bodyAsText`→`decodeToString` switch).
- **Agent shutdown stalled on the reconnect rate-limiter** (`Agent.kt:321`): the reconnect loop's
  `finally` called the non-interruptible `reconnectLimiter.acquire()` even after shutdown flipped
  `isRunning` to false, stalling `shutDown()`/`awaitTerminated()` for up to `reconnectPauseSecs`.
  Now guarded with `if (isRunning)` — there is no reconnect to pace when the agent is stopping.
- **Dead `Utils.decodeParams`** (`common/Utils.kt`): leftover from PR #160 with zero production
  callers; its decode-then-prepend behavior was exactly the bug #160 fixed. Removed along with its
  `URLDecoder`/`UTF_8` imports and the `UtilsTest` block.
- **`keepAliveTimeSecs`/`keepAliveTimeoutSecs` skipped the gRPC-timeout bounds check** that item 27
  added for the other timeouts (`BaseOptions.kt:277-291`): a `0` slipped past the `> -1L` guard in
  `ProxyGrpcService` and died as an opaque Netty exception at startup. Now `require`d to be `-1`
  (default) or `> 0`, mirroring `ProxyOptions.requireGrpcTimeout`. Added rejection + sentinel tests.
- **`removeChunkedContextsForAgent` returned the pre-removal snapshot** (`AgentContextManager.kt:86`):
  a concurrent `writeChunkedResponsesToProxy` completing a matching context between the `filter` and
  the `remove` inflated the caller's "Reclaimed N" log. Now returns only the IDs actually removed via
  `mapNotNull`.
- **`ByteArrayOutputStream.toString(Charsets.UTF_8.name())`** (`ProxyUtils.kt:64`): switched to the
  JDK 10+ `toString(Charset)` overload (no suppressed `UnsupportedEncodingException`).

---

> **Checkbox legend:** `[x]` = fixed/addressed · `[ ]` = open or deliberately deferred. Each
> finding heading below carries its status; see the Resolution status section above for the fix
> details and PR references. Status as of 2026-06-12.

## 🔴 Top priorities

### 1. [x] Query-string secrets leak into agent logs at WARN
`common/Utils.kt:39` — **security, medium**

`sanitizeUrl` only redacts `user:pass@` userinfo (`Regex("(://)[^@/?#]+@")` stops at `?`), so `?token=…`/`?api_key=…` survive. The failure path logs the full URL at **WARN** (`ScrapeResults.kt:104/110/115`), so a token leaks into normal production logs whenever an authed endpoint is merely slow. With `debugEnabled` it's also echoed back to Prometheus in `srUrl`/`srFailureReason` and logged at `ProxyHttpRoutes.kt:99-101`.

**Fix:** blanket-redact query-param *values* (not a key allowlist — allowlists miss custom names) at every logged/echoed URL site: `AgentHttpService.kt:102` (`logUrl`), `:156`, `:180` (`safeUrl`), the URL passed into `ScrapeResults.errorCode`, and `ProxyHttpRoutes.kt:99-101`. Never touch the raw URL used for the actual fetch (`AgentHttpService.kt:109/146`). Low effort, real payoff.

### 2. [x] `connectAgent()` swallows JVM `Error`s and retries fatal failures forever
`agent/AgentGrpcService.kt:200-217` — **error-handling, medium**

`runCatchingCancellable { … }.getOrElse { e -> … false }` collapses *everything* except `CancellationException` — including `OutOfMemoryError`, `StackOverflowError`, and structural NPE/config bugs — into a routine `false`, which the caller treats as "couldn't connect, will retry." This bypasses the codebase's own `handleConnectionFailure()` (`Agent.kt:368-394`) that exists to rethrow `Error`s so the agent terminates instead of running corrupted. The message-only log lambda also means **no stack trace** is ever captured.

**Fix:** (a) pass the throwable so a stack trace is logged — `logger.error(e) { "Cannot connect…: ${e.simpleClassName}" }`; (b) `if (e is Error) throw e` before returning `false`, so fatal errors reach the existing `handleConnectionFailure()`. Transient network/`StatusException` failures keep returning `false`.

### 3. [x] `readConfig()` calls `exitProcess(1)` on parse failure even for embedded agents
`common/BaseOptions.kt:400-426` — **error-handling / public-API, medium**

Both the URL and file branches log on failure and fall through to an unconditional `exitProcess(1)`. The `exitOnMissingConfig` flag — documented (`Agent.kt:577-578`) as the embedded-vs-standalone switch and honored by the `isBlank()` branch — is **ignored** on the parse-failure path. A transient remote-config fetch blip, a locked file, or a HOCON syntax error will kill the **host application's JVM** when an agent is embedded via the public `startAsyncAgent()`.

**Fix:** make the parse-failure path honor `exitOnMissingConfig` — log + `exitProcess(1)` when true; throw a dedicated `ConfigLoadException(cause)` when false so host apps can catch it. Also include the offending cause in the log for non-`FileNotFoundException` failures.

---

## Concurrency & lifecycle

### 4. [x] Embedded shutdown can leak an `HttpClient`; `HttpClientCache.close()` lacks a terminal flag
`agent/HttpClientCache.kt:143-177, 270-293` and `Agent.kt:501-505` — **lifecycle, medium**

Same defect from both ends. `close()` cancels the cleanup scope and clears the map but sets no `closed` flag. On the **embedded** stop path (`EmbeddedAgentInfo.shutdown()` → `Agent.stop()` → `shutDown()`), `shutDown()` runs on the caller's thread while in-flight scrape coroutines are still live on `Dispatchers.IO`; gRPC channel teardown doesn't cancel/join them. A scrape calling `getOrCreateClient()` *after* `close()` cleared the map will `createAndCacheClient()` a fresh Ktor client into the now-dead cache — the cleanup coroutine is cancelled, so that client (holding an event-loop/selector) leaks until JVM exit. Standalone Guava path is unaffected (structured concurrency completes the per-scrape `coroutineScope` before `shutDown()`).

**Fix:** add a `closed` flag set inside the same `withLock` that clears the map (no TOCTOU gap). `getOrCreateClient()` checks it first and throws `IllegalStateException("HttpClientCache is closed")` — callers already wrap in try/finally and a failed scrape during shutdown is acceptable. Optionally also cancel/join the scrape scope in `Agent.shutDown()` before `agentHttpService.close()`.

### 5. [x] `writeChunkedResponsesToProxy` relies on an undocumented single-coroutine invariant
`proxy/ProxyServiceImpl.kt:220-294` — **concurrency, low**

The per-invocation `activeScrapeIds = mutableSetOf<Long>()` is mutated inside the `collect` lambda and post-collect cleanup. Safe **only** because grpc-kotlin confines a client-streaming RPC's `collect` to one coroutine — an implicit invariant. No `launch`/`async`/`flowOn` exists today, and the shared `chunkedContextMap` is already a `ConcurrentHashMap`, so this is not a bug.

**Fix:** add a one-line comment documenting the confinement so a future fan-out refactor knows to swap in a thread-safe set. The "offload CRC32/baos off the gRPC thread" half is **not worth doing** without profiling evidence of thread starvation.

### 6. [x] Evicted agents don't proactively reclaim chunked contexts
`proxy/ProxyServiceImpl.kt:220-322` (with `AgentContext.invalidate()` at `:109-119`) — **resource, low**

`invalidate()` drains the scrape queue but never touches `chunkedContextMap`. A `ChunkedContext` (holding a `ByteArrayOutputStream` up to `maxZippedContentSizeMBytes`) for an evicted agent stays resident until its `writeChunkedResponsesToProxy` stream ends and the orphan-sweep runs. Self-healing and bounded; `chunking_map_check` surfaces it.

**Fix:** low priority. If addressed, filter `chunkedContextMap` by `header.headerAgentId == agentId` in `removeAgentContext`/`invalidate`. At minimum, a KDoc note that reclamation is bounded by stream lifecycle, not eviction.

---

## Error handling

### 7. [x] `writeResponsesToProxy()` swallows per-response errors → slow 503 instead of fast fail
`proxy/ProxyServiceImpl.kt:199-208` — **medium**

If `toScrapeResults()`/`assignScrapeResults()` throws for one scrapeId, it's logged and the loop continues — the `ScrapeRequestWrapper` is never `markComplete()`'d, so the HTTP handler blocks until `scrapeRequestTimeoutSecs` and returns a misleading 503 "timed_out". The sibling `writeChunkedResponsesToProxy()` already calls `failScrapeRequest()` on every error path (lines 252/280/312); this non-chunked path is the outlier.

**Fix:** in the `catch (e: Exception)` at line 205, add `proxy.scrapeRequestManager.failScrapeRequest(response.scrapeId, "Error processing scrape response: ${e.message}")`. Safe (no-ops on a missing wrapper) and brings the two paths into parity. Rare in practice — `toScrapeResults()` is pure protobuf copying — so frame as defensive fail-fast.

### 8. [x] Dropped scrape results on connection-close are invisible
`agent/AgentConnectionContext.kt:54-64` — **medium**

When `trySend()` returns `isClosed` (connection torn down mid-scrape), a fully-computed valid result is dropped with only a `warn` and no metric; the proxy-side wrapper then resolves via timeout. The `close()`-not-`cancel()` design deliberately bounds this to a narrow race, so it's an observability gap, not corruption.

**Fix:** have `sendScrapeResults` return the `ChannelResult`/`Boolean` and increment `agent.metrics { scrapeResultCount.labels(agent.launchId, "dropped").inc() }` at the `Agent.kt:331` call site. Reuses the existing counter's `type` label; purely additive.

### 9. [x] Content-type parse failure logged only at debug
`proxy/ProxyHttpRoutes.kt:279-288` — **low**

A malformed `Content-Type` from a target endpoint falls back to `text/plain` with only a debug log; in production (debug off) this silent re-typing is invisible.

**Fix:** raise to `warn` (or `info`), including path and the offending `srContentType`. Pure logging-verbosity change.

---

## Performance

*All performance findings are bounded and off any tight inner loop (scrapes are seconds apart).*

### 10. [x] Response body encoded to bytes twice (measure, then gzip)
`agent/AgentHttpService.kt:198-222` — **medium (downgraded)**

`content.encodeToByteArray().size` does a full UTF-8 encode (array discarded), then `content.zip()` re-encodes the same String before compressing — two full encodings per gzipped scrape.

**Fix:** encode once: `val bytes = content.encodeToByteArray()`, use `bytes.size` for the threshold and `bytes.zip()` for compression. **Caveat:** guava-utils only exposes `String.zip()`, so this requires adding a `ByteArray.zip()` overload to `com.pambrose:common-utils` (which the maintainer controls) or a small local gzip helper. Worth doing only if that overload can be added.

### 11. [x] Full payload re-encoded just to record a metric size
`proxy/ProxyHttpRoutes.kt:328-331` — **medium (downgraded)**

`scrapeResponseBytes.observe(contentText.toByteArray().size.toDouble())` allocates a complete UTF-8 byte array of the decoded payload only to read `.size`, then discards it.

**Fix:** have `ProxyUtils.unzip` return the decoded `String` plus its already-computed `totalBytes` (computed for the zip-bomb guard anyway) and observe that. **Do not** use `contentText.length` — that's UTF-16 code units and corrupts the metric for non-ASCII. The encode is already gated behind `proxy.metrics{}`.

### 12–15. Optional micro-optimizations (item 12 done; 13–15 deferred — profiling-gated)

- [x] **Per-chunk `withContext(Dispatchers.IO)` over an in-memory stream** (item 12, `AgentGrpcService.kt:381`): the
  enclosing coroutine is already on IO and `ByteArrayInputStream.read()` never blocks, so the wrapper was pure
  per-iteration continuation overhead. **Done 2026-06-12** — wrapper dropped, unused `withContext` import removed.
- [ ] **Per-chunk `chunkBytes.toByteArray()` copy on reassembly** (item 13, `ProxyServiceImpl.kt:247`): pass the
  protobuf `ByteString` into `ChunkedContext.applyChunk` and use `writeTo(baos)` / `asReadOnlyByteBuffer()`. **Note:**
  pre-sizing is infeasible — `HeaderData` carries no total size and `summaryByteCount` arrives last. Dominated by
  gzip+network cost anyway.
- [ ] **Eager `contentAsZipped.toByteArray()` in `toScrapeResults`** (item 14, `ScrapeResults.kt:83-95`): an
  `InputStream`-based `unzip` fed `ByteString.newInput()` avoids one copy on the small non-chunked path. Limited win.
- [ ] **`mergeContentTexts` / `distinct()` allocations** (item 15, `ProxyHttpRoutes.kt:174-195` and `:151-163`):
  consolidated-mode string-copy chain and per-request `map`/`distinct`. Correct today; only matters under heavy
  consolidated load. A `results.size == 1` short-circuit on the `processRequests` path is the cheapest meaningful win if
  touched.

---

## Kotlin idioms & API

### 16. [x] `chunkContentSizeBytes` shifts units (KB → bytes) in place
`agent/AgentOptions.kt:117-119, 260-269` — **medium**

A single public-API mutable `Int` carries three meanings over its lifecycle: sentinel (-1), kilobytes (input/validation), then bytes (after `assignConfigVals`). The KDoc admits "converted in-place to bytes." An external reader can't tell the unit without knowing the lifecycle phase.

**Fix:** split into two fields — keep the `@Parameter("--chunk")`-bound input as `chunkContentSizeKbs` (mirroring the env var and config key) and expose a derived `chunkContentSizeBytes` assigned once. This is a public CLI-field rename, so document/migrate accordingly.

### 17. [x] Stringly-typed `List<Map<String,String>>` path configs
`agent/AgentPathManager.kt:51-74, 109-114` — **medium**

`pathConfigs` uses magic string keys (`NAME`/`PATH`/`URL`/`LABELS`) and nullable lookups for values the tscfg validator guarantees non-null — including a dead `else logger.error{...}` branch for a logically-impossible null. `pathConfigs` is `private` and used only here.

**Fix:** introduce `private data class PathConfig(val name, path, url, labels: String)`. `registerPaths()` collapses to `pathConfigs.forEach { registerPath(it.path, it.url, it.labels) }` (dead branch deleted), `toPlainText()` drops the `.orEmpty()`/`?.padEnd()` noise. Preserve the name's double-quote wrapping.

### 18. [x] Duplicated common-option assignment between AgentOptions and ProxyOptions
`agent/AgentOptions.kt:294-326` (mirror in `proxy/ProxyOptions.kt:225-253`) — **medium**

An identical block — eight `assign*` calls in the same order, the cert/key/trust + `validateTlsConfig` trio, and the log-level idiom — is copy-pasted because `ConfigVals.Agent` and `ConfigVals.Proxy2` are distinct generated types. Any new common option must be edited in two places and can drift.

**Fix:** the `assign*` helpers are already `protected` on `BaseOptions` and take primitives, so add `protected fun assignCommonOptions(... primitives ...)` (centralizing ordering + `validateTlsConfig`) and `protected fun assignLogLevel(role, envVar, configDefault)`. Call sites read role-specific config values and pass primitives in. Keep role-specific `logger.info` lines at the call sites.

### Lower-value idiom cleanups (low) — all done

- [x] **`consolidated` uses `nonNullableReference(false)` (boxes a Boolean) while sibling `valid`
  uses `atomicBoolean(true)`** (`AgentContext.kt:64`): now `var consolidated: Boolean by atomicBoolean(false)`. The
  `isValid()`→property conversion was correctly left alone.
- [x] **`-1`/`-1L` sentinel label derivation repeated** (`ProxyOptions`/`BaseOptions`): extracted
  `protected fun Long.grpcDefaultLabel(default: String)` in `BaseOptions.kt:275`.
- [x] **`isValid = true` assigned inside `getAgentContext(...)?.apply { … }`** (`ProxyServiceImpl.kt`): now computes
  `val isValid = agentContext != null` outside the side-effecting block.
- [x] **`ClientKey` models a credential pair as two independent nullables, forcing `!!`** (`HttpClientCache.kt`): now
  wraps a single `Credentials(username, password)` value object with the both-or-neither gate preserved.

### Not worth restructuring now

- [ ] **`ScrapeResults` 11-arg `sr`-prefixed class** (`ScrapeResults.kt:37-49`): the `sr` prefix is redundant and the
  zipped/text triple is an unenforced invariant duplicated across ~4 sites. A sealed `Payload` type would be the right
  model, but this is cleanup, not a fix — **deferred**. Beware ByteArray structural equality if converting to a
  `data class`.

---

## Duplication & maintainability

### 19. [x] Delete dead `SslSettings` object and its test
`agent/SslSettings.kt:27-74` — **low**

The entire `internal object SslSettings` is unreferenced by production code (only `SslSettingsTest.kt` and one commented-out line exercise it); `@Suppress("unused")` confirms the author knows. ~74 LOC + ~168 test LOC of coverage noise.

**Fix:** delete `SslSettings.kt` and `SslSettingsTest.kt`, remove the stale comment at `AgentHttpService.kt:256`, drop the `docs/TESTING.md` references (lines 69, 144). `internal`, so no public API impact. *Caveat:* if keystore-based trust is planned, wire `getTrustManager(file, password)` behind a real config flag instead — see item 23.

### 20. [x] Remove four commented-out "CT check" / `__test__` blocks
`proxy/ProxyHttpRoutes.kt:70-73, 113-114, 156-159, 281-282` — **low**

Stale debugging scaffolding. The `/__test__` block (70-73) wouldn't even compile (no `delay`/`Plain`/`OK` imports). Misleads readers into thinking content-type tracing is wired up.

**Fix:** delete all four. If CT tracing is still wanted, replace with a single real `logger.debug { … }`.

### 21. [x] Collapse six near-identical `ConfigWrappers` overloads
`common/ConfigWrappers.kt:26-102` — **low**

Three pairs (`newAdminConfig`/`newMetricsConfig`/`newZipkinConfig`) have byte-identical bodies differing only in the tscfg-generated param type (`Proxy2.*` vs `Agent.*`). Metrics duplicates 7 field mappings twice. All six are live.

**Fix:** extract `private fun adminConfig(...primitives...) = AdminConfig(...)` builders; each public overload destructures and delegates in one line. The deeper fix (sharing sub-schemas in `config.conf`) ripples into call sites and `DataClassTest` — defer. The `@Suppress("unused")` is warranted (linter can't trace static imports).

### 22. [x] Decompose `Agent.run()`; factor the 4× drain-and-decrement handler
`Agent.kt:238-352` — **low**

A 114-line nested `connectToProxy()` launches four child coroutines, each repeating the identical `invokeOnCompletion { val drained = connectionContext.close(); if (drained > 0) decrementBacklog(drained) }` and the `if (agent.isRunning) Status.fromThrowable(e)…` logging.

**Fix:** extract `private fun CoroutineScope.launchConnectionTask(ctx, name, block: suspend CoroutineScope.() -> Unit): Job`. **The block must be `suspend CoroutineScope.() -> Unit`** (not `suspend () -> Unit`) — the scrape-processing task at line 316 calls an inner `launch {}` (line 327) against the scope receiver.

---

## Security & TLS

### 23. [x] `trustAllX509Certificates` is process-global all-or-nothing
`agent/AgentHttpService.kt:254-259` — **medium**

`TrustAllX509TrustManager` (empty `checkServerTrusted`) disables cert validation for **every** HTTPS scrape target. Well-mitigated: off by default, explicit opt-in, startup warning. The residual gap is that trusting one self-signed internal endpoint forces dropping validation for all.

**Fix:** (1) document the process-global/all-or-nothing scope in option docs and README; (2) remove the misleading commented `// trustManager = SslSettings.getTrustManager()` at line 256 (wouldn't compile — real signature is `getTrustManager(fileName, password)`); (3) if per-target trust is wanted, add a config-driven trust store keyed per path. *(Ties into item 19 — delete SslSettings or wire it up here.)*

### 24. [x] Prometheus query params decoded-then-concatenated into the scrape URL
`common/Utils.kt:41-54` — **low**

`appendQueryParams` runs `URLDecoder.decode` on the whole `key=val&key=val` blob then concatenates, so an encoded delimiter inside one value expands into new params (or a `#` fragment). Not SSRF — the host is fixed by agent-side HOCON `pathConfigs` and Prometheus is trusted — but fragile.

**Fix:** don't decode the blob. Append the already-encoded string directly, or re-apply via `URLBuilder(baseUrl).parameters.appendAll(parseQueryString(encodedQueryParams))` so each value is individually re-encoded. **Update `UtilsTest.kt:91-95`**, which asserts the splitting behavior.

### 25. [x] Basic-auth credentials stored as plaintext map keys, never zeroed
`agent/HttpClientCache.kt:121-138` — **low**

`cacheKey()` builds `"$username:$password"` as the live `ConcurrentHashMap` key, heap-resident as an immutable String for the cache lifetime (up to 30m) — contrast `SslSettings.getKeyStore` which zeroes the password array. Agent runs inside the firewall and basic-auth is low-grade, so this is defense-in-depth/consistency, not an exploitable leak.

**Fix:** low priority. Prefer the existing Authorization-header path (`AgentHttpService.kt:170-171`) so creds never enter the URL-derived key. If caching must key on creds, use a salted HMAC digest. Document the residual limitation.

---

## Observability & config

### 26. [x] Per-request INFO call logging on by default → scrape-rate log spam
`config/config.conf:22` — **medium**

`requestLoggingEnabled = true` installs Ktor `CallLogging` at `Level.INFO` with a match-everything filter (`ProxyHttpConfig.kt:79-83`). A proxy fronting many targets emits a continuous INFO stream (one per scrape, ~15s each), burying real WARN/ERROR. The only knob is hard on/off. The per-request handler's own log is already `logger.debug`, making the INFO level here inconsistent.

**Fix:** change `level = Level.INFO` to `level = Level.DEBUG` in `configureCallLogging()`. Smallest blast radius — keeps config default `true` (no test changes), quiets routine logging at the default INFO root, keeps WARN/ERROR visible, and lets operators opt in by raising the level. Avoid flipping the config default to `false` (would break `OptionsTest.kt:346-348`, `ConfigValsTest.kt:95`, and the docs snippet).

### 27. [x] `ProxyOptions` does no bounds validation on ports/timeouts (AgentOptions does)
`proxy/ProxyOptions.kt:153-223` — **medium**

`AgentOptions` guards nearly every resolved value with `require(... > 0)`; `ProxyOptions` does none. `proxyPort`/`proxyAgentPort` accept 0/negative, and the five gRPC timeout fields accept any value (only exact `-1L` is the "use default" sentinel; the `> -1L` guards in `ProxyGrpcService` pass `0`/`-5` straight to the builder). Result: opaque Ktor/gRPC builder exceptions instead of a clear startup error.

**Fix:** add fail-fast checks in `assignConfigVals()` after each value resolves: `require(proxyPort in 1..65535)`, same for `proxyAgentPort`, and `require(field == -1L || field > 0)` for each gRPC timeout (preserving the sentinel the `> -1L` guards rely on). No behavior change for valid configs.

### Lower-value config/observability (low)

- [x] **`scrapeRequestTimeoutSecs` bounds check** (`ProxyOptions.kt:241`):
  `require(internal.scrapeRequestTimeoutSecs > 0)` added in `assignConfigVals`. (Env/CLI exposure was the optional
  half — not done, low value.)
- [x] **Dead config keys `maxThreads`/`minThreads`/`scrapeRequestCheckMillis`**: removed + ConfigVals regenerated, with
  referencing tests updated (PR #157).
- [ ] **No metric distinguishes proxy scrape failure modes** (`ProxyHttpRoutes.kt:112, 244-340`): the rich `updateMsg`
  taxonomy feeds only the count; the latency histogram has no outcome label, and the timeout (`:254`) and
  `ClosedSendChannelException` write-failure (`:244`) paths never record latency. Add a status label to the histogram
  and observe on those two branches. **Open** — metric-schema change, own PR.
- [ ] **Inconsistent `launch_id` labeling** (`ProxyMetrics.kt:30-94`): AgentMetrics tags every series with `launch_id`;
  ProxyMetrics tags none, so proxy counters reset silently on restart. Mostly consistency — PromQL `rate()`/`increase()`
  tolerate resets. **Open / accepted** — low priority.

---

## Testing gaps

### 28. [x] No negative-path mutual-TLS rejection test
`src/test/kotlin/io/prometheus/harness/TlsWithMutualAuthTest.kt:45-90` — **medium**

Both TLS harness tests only exercise the success path. Nothing asserts the proxy **rejects** an agent presenting no client cert when mutual auth is required. A regression silently disabling client-cert verification — a security boundary — would pass the entire suite.

**Fix:** add `TlsMutualAuthRejectionTest`: proxy with `--trust` (mutual auth required), agent started **without** `--cert`/`--key`, asserting it fails to connect/register (expect `StatusException`/`SSLHandshakeException`, not a successful scrape). Needs no new fixtures.

### 29. [x] `cioTimeoutSecs` vs `httpClientTimeoutSecs` override resolution untested
`agent/AgentHttpService.kt:242-252` — **medium**

The precedence logic justifying the deprecated `cioTimeoutSecs` knob is never exercised — every test sets both to the default 90, so only the else-branch runs.

**Fix:** extract `internal fun resolveTimeoutSecs(cio, httpClient, default = 90)` and unit-test both branches: (a) `cio` overridden, `httpClient` default → `cio` wins; (b) `httpClient` overridden → `httpClient` wins.

### 30. [x] "Unknown scrapeId" header-drop branch untested
`proxy/ProxyServiceImpl.kt:226-236` — **medium**

`ProxyServiceImplTest` hard-codes `containsScrapeRequest(any()) returns true` (line 72), so the guard that drops a chunked HEADER for a stale/timed-out scrapeId never runs. A regression creating a context unconditionally (leaking `ChunkedContext` entries) wouldn't be caught.

**Fix:** add a test stubbing `containsScrapeRequest(scrapeId) returns false`, send a single HEADER, and assert `verify(exactly = 0) { contextManager.putChunkedContext(any(), any()) }` (the load-bearing assertion) and result is `EMPTY_INSTANCE`.

### 31. [x] Wrapped/nested timeout detection partially untested
`agent/AgentHttpService.kt:111-134` — **medium**

The `fetchContentFromUrl` cause-walk + `e is CancellationException && !isTimeout` guard exists to convert a `CancellationException`-whose-cause-is-a-timeout into a 408 rather than rethrowing. That exact case has **no** coverage (the `ScrapeResults.errorCode` cause-walk is well tested, but that's a different loop).

**Fix:** add a test where `fetchContent` throws `CancellationException("timed out").apply { initCause(HttpRequestTimeoutException("url", 1000L)) }` and assert `srStatusCode == 408` and no rethrow. The generic-RuntimeException-wrapping case is already covered at the `errorCode` level — skip it.

### Lower-value test gaps (low)

- [x] **ChunkedContext exact-boundary acceptance** (`ChunkedContextTest.kt:183`): added a test asserting
  `totalByteCount == maxZippedContentSize` is accepted (guards an off-by-one `>` → `>=` regression).
- [ ] **`writeScrapeRequest`/`invalidate` send-failure race** (`AgentContext.kt:90-119`): **won't do.** The finding's
  motivating hang scenario is *wrong* — the sole caller's `finally` (`ProxyHttpRoutes.kt:262`) unconditionally
  `closeChannel()`s, so no hang occurs in any interleaving. Skipped as not worth pure defense-in-depth coverage.

---

## Suggested sequencing

1. **Item 1 (query-string secret redaction)** — the only finding that leaks credentials into production logs *today* at WARN. Small, isolated, highest real-world payoff.
2. **Items 2 + 3 (swallowed Errors; embedded `exitProcess`)** — both are public-API robustness bugs with tiny, safe fixes that align code with its own documented intent.
3. **Item 4 (HttpClientCache `closed` flag)** — closes the embedded-agent leak; a single guarded flag fixes both halves.
4. **Items 26 + 27 + the `scrapeRequestTimeoutSecs` bounds check** — config/observability quick wins. The `Level.DEBUG` change and the `require(...)` validations are one-liners with near-zero risk and no test churn (except #27 if you also expose env vars).
5. **Items 7 + 8 (fail-fast on response errors; drop metric)** — consistency/observability improvements that make degraded-agent behavior diagnosable.
6. **Testing items 28–31** — lock in the security boundary (mutual-TLS rejection) and the three untested branches *before* refactoring near them; cheap and prevent silent regressions.
7. **Items 16–18, 22 (idiom/duplication refactors)** — do these once behavioral fixes land. The two `assign*` extractions (18) and `AgentOptions` unit split (16) have the best maintainability return; the `Agent.run()` decomposition (22) pairs well with touching that file.
8. **Dead-code deletions (19, 20) + low-value polish** — batch opportunistically. Decide SslSettings's fate (delete vs wire into item 23) as one call.
9. **Skip / defer:** the gRPC-thread offload half of item 5, the `ScrapeResults` remodel, pre-sizing in the chunk-copy item, and the AgentContext race test (its premise is incorrect). The performance micro-opts (12–15) are profiling-gated.
