# Prometheus-Proxy Code Review — July 2026 Findings

**Date:** 2026-07-03

**Process:** four parallel deep reviews over all 42 main-source files (~7.6k lines) — proxy-side
concurrency, agent-side concurrency, Kotlin idioms, and general code quality — each instructed to
trace failure scenarios through the code rather than pattern-match. Every high-severity finding was
then independently re-verified against the source (including the `service-utils`/`guava-utils`
2.9.3 dependency sources, where a finding hinged on library semantics). Confidence is marked per
finding: **confirmed** (traced end-to-end and re-verified) or **plausible** (mechanism traced,
triggering condition environmental or rare).

**Headline:** the codebase remains in good shape — the proxy-side concurrency machinery in
particular held up under adversarial tracing (see the "Checked and safe" appendix). But this pass
found **five confirmed high-severity correctness bugs**, all small targeted fixes: an agent
shutdown deadlock, a half-open-connection zombie state, a reconnect rate limit that a log level
silently disables, JVM `Error`s swallowed into scrape results, and `exitProcess()` calls that kill
embedded host JVMs.

---

## 📋 Findings index

✅ = fixed/addressed · ⬜ = open

| # | Finding | Severity | Status |
|---|---------|----------|--------|
| 1 | Agent shutdown deadlocks on the idle `readRequestsFromProxy` stream | high | ✅ |
| 2 | Half-open connection ⇒ permanent zombie; heartbeat failures never trigger teardown | high | ✅ |
| 3 | Reconnect rate limit disabled when log level > INFO (`acquire()` inside log lambda) | high | ✅ |
| 4 | `fetchContentFromUrl` swallows JVM `Error`s into 503 scrape results | high | ✅ |
| 5 | `parseArgs` `exitProcess()` kills embedded host JVMs | high | ✅ |
| 6 | `heartbeatEnabled=false` closes every connection immediately after connect | medium | ✅ |
| 7 | `registerPath` races agent removal → permanently dead path in `pathMap` | medium | ✅ |
| 8 | Zipkin tracing closed on first reconnect, then reused | medium | ✅ |
| 9 | Unguarded `HttpClient.close()` can permanently kill the cache sweeper | medium | ✅ |
| 10 | All non-2xx upstream responses mislabeled `path_not_found` | medium | ✅ |
| 11 | Config-value validation gaps (`reconnectPauseSecs`, backlog capacity, zipped size, gzip min) | medium | ✅ |
| 12 | `submitScrapeRequest` is a ~125-line god method with `.also { return }` control flow | medium | ✅ |
| 13 | `sendHeartBeat` error handling: ERROR noise, lost stack traces, exception-as-goto | medium | ✅ (via finding 2) |
| 14 | Polled scrape request lost on stream-only RPC cancellation | low | ✅ |
| 15 | Disconnect-vs-submit races mislabel agent-disconnect results as `timed_out` | low | ✅ |
| 16 | `scrapeResults` write not atomic with completion CAS — fail can clobber a real result | low | ✅ |
| 17 | Chunked HEADER check-then-act buffers a transfer nobody awaits | low | ⬜ (deferred — optional, self-healing/size-capped) |
| 18 | `closeAll()` discards drained count → inflated backlog gauge until reconnect | low | ✅ |
| 19 | `AgentPathManager` RPC+map updates not atomic; `clear()` bypasses the mutex | low | ✅ |
| 20 | Ignored `awaitTermination` → stale agentId can poison the next connection | low | ✅ (awaitTermination logged; interceptor-generation token deferred) |
| 21 | Routine client errors and normal shutdown logged at ERROR | low | ✅ |
| 22 | `handleConnectionFailure` discards the gRPC status | low | ✅ |
| 23 | Stream-failure logging block duplicated four times (already drifted) | low | ✅ |
| 24 | `http_client_cache_size_check` health check is unreachable | low | ✅ |
| 25 | Hand-rolled LRU: `ConcurrentHashMap` under a mutex + parallel recency map | low | ✅ |
| 26 | Public API leaks beyond the documented surface | low | ✅ |
| 27 | Commented-out code remnants (one comment contradicts the line below it) | low | ✅ |
| 28 | Magic numbers (idle timeout, backlog multiplier, retry delays, grace periods) | low | ✅ |
| 29 | Duplicate `Cache-Control` header on every scrape response | low | ✅ |
| 30 | Doubled exception rendering in scrape-failure warn logging | low | ✅ |
| 31 | Dead parameter flexibility (`parseArgs(Array<String>?)`, unused `backlogCapacity` default) | low | ✅ |
| 32 | Cleanup-service gating predicate duplicated between `startUp`/`shutDown` | low | ✅ |
| 33 | `HttpClientCache` uses epoch millis instead of monotonic `TimeMark`s | idiom | ✅ |
| 34 | Two hand-rolled cause-chain walks with different exception sets (drifted) | idiom | ✅ |
| 35 | The `-1`-sentinel resolve/validate/log stanza copy-pasted ~25× | idiom | ⬜ (deferred — large precedence-sensitive refactor) |
| 36 | Scope-function misuse cluster (`apply` receiver smuggling, consumer `apply`, shadowing) | idiom | ✅ (apply-misuse sites; 65–120-line options `apply` blocks deferred) |
| 37 | `readConfig` control flow: nullable `Throwable` + non-local returns from `runCatching` | idiom | ✅ |
| 38 | Manual CAS loop in `decrementBacklog` — stdlib `update` now exists | idiom | ✅ |
| 39 | `mergeContentTexts`: `var` flag mutated inside `map` + repeated `"# EOF"` literal | idiom | ✅ |
| 40 | `reconnectLimiter` split declaration/`init`-assignment | idiom | ✅ |
| 41 | Manual `while(true)/poll/break` drain loops | idiom | ✅ |
| 42 | Scheme-stripping `when` reducible to `removePrefix` chain (+ case-sensitivity gap) | idiom | ✅ |

---

## 🔴 High severity — confirmed correctness bugs

### 1. [x] Agent shutdown deadlocks on the idle `readRequestsFromProxy` stream
`Agent.kt:240-329, 492-506` · `agent/AgentGrpcService.kt:326-347` — **concurrency/lifecycle, high,
confirmed**

Nothing cancels the server-streaming `grpcStub.readRequestsFromProxy(...).collect` when
`stopSync()` flips `isRunning` to false. The code that would break the stream —
`grpcService.shutDown()` → `channel.shutdownNow()` in `Agent.shutDown()` (`Agent.kt:492-496`) —
only runs *after* `run()` returns, because Guava's `AbstractExecutionThreadService` invokes the
`shutDown()` hook on the service thread strictly after `run()` completes, and its `doStop` only
calls `triggerShutdown()`, which is a no-op: verified against the `guava-utils` 2.9.3 sources that
`GenericExecutionThreadService` → `AbstractGenericService` → `GenericService` → `Agent` overrides
it nowhere. Circular wait: shutdown waits for `run()`, `run()` waits for a stream only shutdown can
break.

**Failure scenario (traced):** an embedder calls `EmbeddedAgentInfo.shutdown()` → `stopSync()`
(30 s timeout) while the connection is healthy but idle. The heartbeat task exits within
`heartbeatCheckPauseMillis` and its `invokeOnCompletion` closes the connection context; the scrape
loop and write RPCs finish. But the read task stays suspended in `collect` — its
`ClosedSendChannelException` exit (`AgentGrpcService.kt:341`) is only reachable when a message
*arrives*. `coroutineScope` never completes → `runBlocking` never returns → `run()` never returns →
`shutDown()` never executes → `stopSync()` throws `TimeoutException`. The thread unwedges only when
a scrape happens to arrive, the proxy evicts the agent for inactivity (60 s default), or — on a
half-open transport with keepalive off (the default) — the OS TCP retransmission timeout
(~15-30 min). The JVM shutdown hook (`stopAsync(); awaitTerminated()` with **no timeout**) blocks
SIGTERM processing for the same period.

**Why tests miss it:** `HarnessSetup.takeDownProxyAndAgent()` stops proxy and agent concurrently,
so proxy teardown errors the stream and releases the agent.

**Fix:** override `triggerShutdown()` in `Agent` to tear down the gRPC channel (or cancel the
connection scope). The pattern already exists in this codebase —
`proxy/AgentContextCleanupService.kt:82` overrides `triggerShutdown()` for exactly this reason.
Add a shutdown-while-idle test (agent stopped alone, no proxy teardown).

### 2. [x] Half-open connection ⇒ permanent zombie; heartbeat failures never trigger teardown
`agent/AgentGrpcService.kt:292-324` · `Agent.kt:423-430` — **concurrency/error-handling, high,
confirmed (code paths); triggering condition environmental**

`sendHeartBeat()` rethrows only a `NOT_FOUND` *response* (the proxy evicted the agent). Every
heartbeat *failure* — `DEADLINE_EXCEEDED`, `UNAVAILABLE`, or any non-gRPC exception — is logged and
swallowed (`AgentGrpcService.kt:309-321`), so the heartbeat loop keeps spinning and the connection
context never closes.

**Failure scenario (traced):** a NAT/firewall silently drops the TCP connection — the exact
environment this product targets. Heartbeat unary RPCs fail with `DEADLINE_EXCEEDED`
(`unaryDeadlineSecs` = 30) every cycle; the agent believes it is connected. The
`readRequestsFromProxy` collect cannot fail because gRPC keepalive is **off by default**
(`keepAliveTimeSecs = -1`, `config.conf`). The proxy evicts the agent after 60 s, Prometheus
scrapes fail, and the outage persists until the OS TCP retransmission timeout errors the socket
(~15-30 min) — instead of the ~5 s detection the heartbeat exists to provide. The zombie-state fix
comment at `AgentGrpcService.kt:313-318` covers only the healthy-transport eviction case.

**Fix:** treat N consecutive heartbeat failures (any status) as a disconnect signal — rethrow to
end the heartbeat task, which closes the connection context and forces a reconnect. Note this alone
does not unblock the read stream (see finding 1); both fixes are needed for the reconnect to
actually proceed promptly. Consider also enabling gRPC keepalive by default, or documenting the
interaction prominently.

### 3. [x] Reconnect rate limit disabled when log level > INFO
`Agent.kt:325-326` — **correctness, high, confirmed**

```kotlin
if (isRunning)
  logger.info { "Waited ${reconnectLimiter.acquire().roundToInt().seconds} to reconnect" }
```

`reconnectLimiter.acquire()` — the entire reconnect pacing mechanism — executes inside a
kotlin-logging lambda that is only evaluated when INFO is enabled. Run the agent with
`--log_level warn` (a supported option) and the pause silently disappears: on a down proxy the
agent reconnects in a hot loop, burning CPU and hammering the proxy.

**Fix:** hoist the side effect out of the lambda:
`val waited = reconnectLimiter.acquire(); logger.info { "Waited ${waited.roundToInt().seconds} to reconnect" }`.

### 4. [x] `fetchContentFromUrl` swallows JVM `Error`s into 503 scrape results
`agent/AgentHttpService.kt:124-146` — **error-handling, high, confirmed**

The scrape path catches `Throwable` and converts everything except non-timeout
`CancellationException` into a routine 503 `ScrapeResults` — so `OutOfMemoryError` /
`StackOverflowError` become "scrape failed" and the agent keeps running in a corrupted state. This
directly contradicts the codebase's own documented `Error`-rethrow policy at `Agent.kt:370-375` and
`AgentGrpcService.kt:217-221` ("so the agent terminates instead of running in a corrupted state"),
which the June 2026 review (finding 2) established for `connectAgent()`.

**Fix:** add an `if (e is Error) throw e` guard (after the cause-chain timeout walk, before
building the failure `ScrapeResults`), mirroring the existing policy sites.

### 5. [x] `parseArgs` `exitProcess()` kills embedded host JVMs
`common/BaseOptions.kt:252-264` (also `459-461`, `496-497`) — **API design/error path, high,
confirmed**

`parseArgs` calls `exitProcess(1)` on any `ParameterException` and `exitProcess(0)` for `-u`/`-v`,
unconditionally — ignoring `exitOnMissingConfig = false`, the flag whose documented purpose
(`ConfigLoadException` KDoc, `startAsyncAgent` KDoc) is letting embedding hosts survive startup
failures. A host app constructing `AgentOptions(args, exitOnMissingConfig = false)` with one bad
flag has its entire JVM terminated. Only the config-load path honors embedded mode (fixed in the
June review, finding 3); the option-parse path was missed.

**Fix:** in embedded mode (`exitOnMissingConfig = false`), throw `ConfigLoadException` (or a new
`OptionParseException`) instead of `exitProcess(1)`; for `-u`/`-v` in embedded mode, either throw or
document that usage/version remain CLI-only. Keep `exitProcess` behavior for the CLI path.

---

## 🟠 Medium severity

### 6. [x] `heartbeatEnabled=false` closes every connection immediately after connect
`Agent.kt:274-276, 349-354, 416-436` — **lifecycle, medium (non-default config, total breakage),
confirmed**

`launchConnectionTask` closes the shared connection context whenever *any* of the four
connection-lifetime tasks completes (`invokeOnCompletion`, `Agent.kt:350-353`). With
`heartbeatEnabled=false`, `startHeartBeat` returns immediately from its `else` branch
(`Agent.kt:432-434`), so the context is closed right after connect/registration. First scrape
request → `ClosedSendChannelException` inside the read collect (`AgentGrpcService.kt:341`) →
reconnect. The agent flaps connect/disconnect forever and every scrape fails, with no error that
points at the config flag. Deterministic.

**Fix:** when heartbeat is disabled, the heartbeat task should suspend until cancellation
(`awaitCancellation()`) instead of returning — or `launchConnectionTask` should not treat
*successful completion* of the heartbeat task as a disconnect. Add a container/harness test running
with `heartbeatEnabled=false`.

### 7. [x] `registerPath` races agent removal → permanently dead path
`proxy/ProxyServiceImpl.kt:117-127` · `proxy/ProxyPathManager.kt:87-131, 191-200` ·
`Proxy.kt:339-348` — **concurrency (TOCTOU), medium, confirmed**

`registerPath` checks `getAgentContext(agentId)` *outside* the `pathMap` lock, and
`addValidatedPath` never re-checks `agentContext.isValid()` inside it. If agent removal
(`transportTerminated` or cleanup-service eviction) interleaves — sweeping `pathMap` before the
insert and removing the context from `AgentContextManager` — the new path is inserted pointing at
an invalidated context, and **no code path ever removes it**: `removeFromPathManager` early-returns
when the context is already gone from the manager (`ProxyPathManager.kt:197-200`), and the stale
cleanup only scans `agentContextMap`. Result: the path 404s (`invalid_agent_context`) on every
scrape while still advertised by service discovery, until a non-consolidated agent happens to
re-register the same path. For consolidated paths it is worse — a new agent *appends* to the list
(`ProxyPathManager.kt:98`), so the dead member stays in every fan-out, producing a spurious
`agent_disconnected` 503 sub-result per scrape, forever.

**Fix:** re-check `agentContext.isValid()` inside the `synchronized(pathMap)` block in
`addValidatedPath` and reject registration for an invalidated context; and/or make
`removeFromPathManager` sweep by agentId even when the context is already absent from the manager.

**Follow-up (both fixes above were ordered wrong):** PR #198 landed both halves, but
`Proxy.removeAgentContext` swept the `pathMap` *before* `removeFromContextManager` invalidated the
context — so the in-lock re-check read a flag the remover had not set yet, and the sweep ran before
the racing insert. A `registerPath` blocked on the `pathMap` monitor during the sweep was released
directly into the unguarded gap. Closed by hoisting `removeFromContextManager` above
`removeFromPathManager`, so a registration racing teardown is either rejected by the in-lock check
or undone by the sweep. Pinned by `ProxyTest` → "removeAgentContext should invalidate the agent
context before sweeping the path map".

### 8. [x] Zipkin tracing closed on first reconnect, then reused
`agent/AgentGrpcService.kt:147-154, 161-201` — **resource lifecycle, medium, confirmed (code
path); impact plausible**

`shutDownLocked()` closes `tracing` whenever Zipkin is enabled and is called from
`resetGrpcStubs()` on every reconnect — but `tracing`/`grpcTracing` are one-shot `lazy` fields, so
every channel built after the first reconnect installs an interceptor over a closed `Tracing`.
Tracing silently dies for the remainder of the agent's life in Zipkin-enabled deployments.

**Fix:** either don't close `tracing` in `resetGrpcStubs()` (close it only in final shutdown), or
make the tracing objects re-creatable per connection generation.

### 9. [x] Unguarded `HttpClient.close()` can permanently kill the cache sweeper
`agent/HttpClientCache.kt:80-97 (94), 216, 228, 337` — **error path/resource leak, medium,
plausible**

`clientsToClose.forEach { it.close() }` in the cleanup loop has no per-item try/catch; the
coroutine's `runCatchingCancellable` covers only the mutex-held section. One throwing `close()`
propagates out of the `while (isActive)` loop and the background sweeper dies silently — expired
clients are never evicted again (unbounded resource growth). The same unguarded pattern aborts
remaining closes in `close()` and `getOrCreateClient`.

**Fix:** wrap each `client.close()` in its own `runCatching { }` (log failures), at all three
sites.

**Follow-up (coverage gap):** PR #198's fix was correct, but its only test called `closeQuietly`
directly — reverting the sweeper call site to `it.close()` left the whole suite green, so nothing
pinned the wiring. Covered by `HttpClientCacheTest` → "Finding 9: a throwing close in the sweeper
must not kill the cleanup loop", which drives a throwing close through the background loop and
asserts a *subsequent* entry is still evicted. Verified to fail when the call site is reverted.

### 10. [x] All non-2xx upstream responses mislabeled `path_not_found`
`proxy/ProxyHttpRoutes.kt:305-315 (312), 216-219` — **observability, medium, confirmed**

In `submitScrapeRequest`, any non-success status from the agent — target endpoint 500/503, 408
timeout, 413 payload-too-large — produces `updateMsg = "path_not_found"`, which feeds the
`proxy_scrape_requests{type}` counter, the latency histogram's outcome label, and the debug-servlet
activity log. Operators cannot distinguish a genuinely unknown path from a down target.

**Fix:** derive the label from the status code (e.g. `upstream_error`, `timed_out`,
`content_too_large`, with `path_not_found` reserved for the actual 404-unknown-path case).

**Follow-up (label collision + stale docs):** the 408/504 case reused `timed_out`, the same string
the proxy emits when the agent never answers at all — two faults with opposite remediations in one
series, and since `agent.scrapeTimeoutSecs` (15s) trips well before the proxy's
`scrapeRequestTimeoutSecs` (90s), the agent-side leg was the dominant contributor. Split out as
`upstream_timed_out`, matching the `content_too_large` / `payload_too_large` convention this
finding already established. The proxy-side literal is now the `PROXY_TIMEOUT_LABEL` constant so
the two can't silently converge again. The label tables in `docs/metrics-and-grafana.md` and
`website/prometheus-proxy/docs/monitoring.md` were never updated when this finding landed — they
still described `path_not_found` as "Agent returned a non-200 status" and omitted `upstream_error`
and `content_too_large` entirely; both are now correct and document the proxy/agent pairs.

### 11. [x] Config-value validation gaps for values used in arithmetic/capacity
`Agent.kt:204, 265` · `proxy/ProxyServiceImpl.kt:241` vs `proxy/ProxyOptions.kt:267-271` ·
`agent/AgentOptions.kt:317-319` — **input validation, medium, confirmed**

- `RateLimiter.create(1.0 / reconnectPauseSecs)` — `0` yields an infinite rate (hot reconnect
  loop); negative yields an opaque Guava IAE at startup.
- `AgentConnectionContext(scrapeRequestBacklogUnhealthySize * 2)` — `0` silently produces a
  rendezvous channel; negative throws at connect time.
- `maxZippedContentSizeMBytes` is used in size math unvalidated even though its sibling
  `maxUnzippedContentSizeMBytes` *is* validated; a 0/negative value makes every chunked transfer
  fail with a confusing `ChunkValidationException`.
- `minGzipSizeBytes` is never `require`d (negative → everything gzipped).

**Fix:** add `require(... > 0)` checks in the respective options classes, mirroring the existing
sibling validations (June review finding 27 established the pattern).

### 12. [x] `submitScrapeRequest` is a ~125-line god method with `.also { return }` control flow
`proxy/ProxyHttpRoutes.kt:238-363 (288)` — **maintainability, medium, confirmed**

One suspend function handles wrapper creation, map registration, channel write + disconnect
fallback, timeout await, finally-cleanup, content-type parsing/fallback, gzip decode with two typed
error returns, metrics observation, and response assembly — with four early-return
`ScrapeRequestResponse` shapes. Its tail is
`HttpStatusCode.fromValue(...).also { statusCode -> ... return ... }`: the `.also` result is
discarded and the last ~75 lines live inside a lambda exiting via non-local `return`s plus nested
`scrapeResults.run {}` blocks. The `@Suppress("ReturnCount")` is the tell. This is the single most
trafficked function in the proxy HTTP path.

**Fix:** `val statusCode = HttpStatusCode.fromValue(...)` + plain `if/else` expression returns;
extract the await/timeout leg, the gzip-decode leg, and response assembly into private helpers.

### 13. [x] `sendHeartBeat` error handling: ERROR noise, lost stack traces, exception-as-goto
`agent/AgentGrpcService.kt:292-324` — **error handling/logging, medium, confirmed**

Three defects in one function: (a) routine transient failures are logged at ERROR; (b) the non-gRPC
branch logs `e.message` only — the throwable is not passed, so the stack trace is lost — and then
swallows the exception entirely; (c) the invalid-response path throws
`StatusRuntimeException(NOT_FOUND)` at line 306 solely for the function's own `onFailure` to catch
three lines later, log a second time, and selectively rethrow — an exception used as a goto within
a single function. Additionally `.also { anAgentId -> ... }` wraps the whole body where a guard
clause (`if (agentId.isEmpty()) return`) is flatter.

**Fix:** restructure with a guard clause; return/throw the eviction signal directly instead of
throw-and-recatch; log transient failures at WARN with the throwable attached. (Interacts with
finding 2 — fix together.)

---

## 🟡 Low severity — concurrency

All traced; all bounded (no hangs or leaks) — these cost accuracy or a timeout window, not
availability.

### 14. [x] Polled scrape request lost on stream-only RPC cancellation
`proxy/AgentContext.kt:100-103` · `proxy/ProxyServiceImpl.kt:183-185` — **low, confirmed**

`readScrapeRequest` receives the notifier token then `poll()`s the wrapper; if the
`readRequestsFromProxy` RPC is cancelled (agent-side cancel/deadline, transport still up) between
the poll and the `emit`, the wrapper is neither delivered, re-queued, nor failed. The waiting HTTP
handler blocks the full `scrapeRequestTimeoutSecs` and returns 503 `timed_out` instead of a prompt
error, and the latency histogram records a full timeout. On full disconnect the wrapper *is*
promptly failed via `transportTerminated → failAllScrapeRequests`; this bites only on RPC-level
cancellation.

**Fix:** re-queue or fail the polled wrapper from a `finally`/cancellation handler around the emit.

### 15. [x] Disconnect-vs-submit races mislabel agent-disconnect results as `timed_out`
`proxy/ScrapeRequestManager.kt:80-87` · `proxy/AgentContext.kt:109-119` ·
`proxy/ProxyHttpRoutes.kt:252-271` · `proxy/ProxyPathManager.kt:117-125` — **low, confirmed**

Two legs: (a) a wrapper added to `scrapeRequestMap` concurrently with `failAllScrapeRequests`'
weakly-consistent iteration can be missed and, if emitted to the dying agent, waits the full scrape
timeout; (b) `invalidate()` drains the queue calling `wrapper.closeChannel()` **without** setting a
failure result, so `awaitCompleted` returns instantly with `scrapeResults == null` and
`submitScrapeRequest` labels it `timed_out` even though the truthful cause is agent
disconnect/displacement. Metrics labels are skewed accordingly.

**Fix:** have `invalidate()`'s drain (and the displaced-agent drain in `addValidatedPath`) fail the
wrapper with an agent-disconnected result instead of bare-closing the channel; distinguish
"completed with null results" from "await timed out" in `submitScrapeRequest`.

### 16. [x] `scrapeResults` write not atomic with completion CAS
`proxy/ScrapeRequestManager.kt:53-78` · `proxy/ScrapeRequestWrapper.kt:58-71` — **low, confirmed**

`markComplete()`'s CAS makes completion idempotent, but both `assignScrapeResults` and
`failScrapeRequest` write `wrapper.scrapeResults` *before* the CAS, last-writer-wins. A losing
`failScrapeRequest` (agent response and `transportTerminated` racing) can clobber the winning real
result before the HTTP handler reads it — Prometheus receives 502 "Agent disconnected" for a scrape
that actually succeeded. Only observable during genuine disconnect races.

**Fix:** only publish the result if the CAS is won (write result → CAS → close channel; skip the
write when the CAS fails), or fold result publication into the wrapper behind the CAS.

### 17. [ ] Chunked HEADER check-then-act buffers a transfer nobody awaits
`proxy/ProxyServiceImpl.kt:237-247` — **low, confirmed**

`containsScrapeRequest(scrapeId)` then `putChunkedContext(...)` races the HTTP timeout path's
`removeFromScrapeRequestMap`: the proxy can accept and buffer an entire chunked payload (up to
`maxZippedContentSizeMBytes`) for a request that already timed out. Freed at SUMMARY or the
end-of-stream orphan sweep — transient memory waste only, self-healing and size-capped.

**Fix (optional):** re-check `containsScrapeRequest` at SUMMARY time or on each CHUNK and abandon
early; low priority given the cap.

### 18. [x] `closeAll()` discards the drained-request count
`agent/AgentGrpcService.kt:450-454` vs `Agent.kt:350-353` — **low, confirmed**

On write-stream failure, `closeAll()` wins the close race and drains N buffered scrape actions but
discards the count; the later `invokeOnCompletion` closes get 0. `scrapeRequestBacklogSize` stays
inflated by N until the next connect resets it, so the `scrape_request_backlog_check` health check
can falsely report unhealthy during the disconnect window.

**Fix:** `val drained = connectionContext.close(); if (drained > 0) agent.decrementBacklog(drained)`
inside `closeAll()`, mirroring the `invokeOnCompletion` handler.

### 19. [x] `AgentPathManager` RPC+map updates not atomic; `clear()` bypasses the mutex
`agent/AgentPathManager.kt:47, 60-91` · `Agent.kt:250` — **low, confirmed lock-scope tracing;
interleavings plausible**

`registerPath`/`unregisterPath` perform the proxy RPC outside `pathMutex` and the map write inside
it, so concurrent dynamic register/unregister of the same path can leave the local map and the
proxy's view disagreeing (agent 404s as `invalid_path`, or holds an entry the proxy doesn't know).
A dynamic `registerPath` racing a reconnect can insert a stale `PathContext` into the fresh map
because `clear()` doesn't take the mutex. Requires concurrent dynamic path management — uncommon.

**Fix:** hold `pathMutex` across RPC+map (or serialize path mutations onto a single actor/channel),
and route `clear()` through the mutex.

### 20. [x] Ignored `awaitTermination` → stale agentId can poison the next connection
`agent/AgentGrpcService.kt:151-152` · `Agent.kt:243-247` · `agent/AgentClientInterceptor.kt:52-74`
— **low, plausible**

`channel.shutdownNow()` + `awaitTermination(5, SECONDS)` ignores the boolean result (and logs
nothing on failure). If the old channel fails to terminate in 5 s, a late `onHeaders` from a
leftover call sees the freshly-reset empty `agentId` and re-assigns the **stale** id; the new
connection's `onHeaders` then skips assignment and `registerAgent` is sent with the stale id →
rejected → one wasted reconnect cycle (self-healing). The interceptor's `synchronized(agent)` is
also not taken by the other `agentId` writers, so the check-then-act isn't actually exclusive.

**Fix:** log/handle a false `awaitTermination` return; tie interceptor id-assignment to the channel
generation (e.g. pass a generation token) or synchronize all `agentId` writers consistently.

---

## 🟡 Low severity — quality & observability

### 21. [x] Routine client errors and normal shutdown logged at ERROR
`proxy/ProxyUtils.kt:79, 91, 109` — **logging, low, confirmed**

Every request for an unknown path logs at ERROR — a misconfigured Prometheus or a port scanner
floods the error log on every scrape interval — and `proxyNotRunningResponse` logs "Proxy stopped"
at ERROR once per in-flight request during normal shutdown. **Fix:** warn (or info) for these
expected conditions.

### 22. [x] `handleConnectionFailure` discards the gRPC status
`Agent.kt:362-364` — **logging, low, confirmed**

The `StatusRuntimeException` branch logs only "Disconnected from proxy at $proxyHost", dropping
`e.status` entirely — UNAVAILABLE vs UNAUTHENTICATED vs RESOURCE_EXHAUSTED reconnect loops are
indistinguishable in logs. **Fix:** include `e.status` (and message) in the log line.

### 23. [x] Stream-failure logging block duplicated four times (already drifted)
`Agent.kt:344-348` · `agent/AgentGrpcService.kt:464-470` · `proxy/ProxyServiceImpl.kt:215-222,
306-313` — **duplication, low, confirmed**

The `runCatchingCancellable { ... }.onFailure { if (isRunning) Status.fromThrowable(e) ... }`
pattern recurs with variations that have already diverged (some suppress CANCELLED, some don't;
some close channels). **Fix:** extract one shared helper with explicit knobs.

### 24. [x] `http_client_cache_size_check` health check is unreachable
`Agent.kt:403-413` · `agent/HttpClientCache.kt:239-241` — **dead code/boundary, low, plausible**

Threshold is `maxCacheSize + 1` with `>=` (i.e. `size > maxCacheSize`), but `createAndCacheClient`
evicts before insert and `removeEntry` removes from the map immediately even for in-use entries, so
`cache.size` never exceeds `maxCacheSize` — the unhealthy branch can never fire. **Fix:** either
track (and alert on) in-use-but-evicted clients pending close, or remove the check.

### 25. [x] Hand-rolled LRU: `ConcurrentHashMap` under a mutex + parallel recency map
`agent/HttpClientCache.kt:63-66` — **design, low, confirmed**

`cache` is a `ConcurrentHashMap` yet every mutation is serialized under `accessMutex` alongside a
plain `HashMap` (`accessOrder`) tracking recency — the concurrent map type advertises a lock-free
contract that isn't the real one, and `evictLeastRecentlyUsed` is O(n) per insert-at-capacity.
**Fix:** a single `LinkedHashMap(accessOrder = true)` under the mutex (or Caffeine) replaces both
structures. (Interacts with finding 33 — do together.)

### 26. [x] Public API leaks beyond the documented surface
`Proxy.kt:391, 427` · `common/BaseOptions.kt:201` — **API surface, low, confirmed (policy
documented in CLAUDE.md)**

`Proxy.isBlitzRequest()` and `Proxy.buildServiceDiscoveryJson()` are public but consumed only by
proxy internals — and the latter exposes `kotlinx.serialization.json.JsonArray` in a public
signature. `BaseOptions.configVals` / `Proxy.proxyConfigVals` / `Agent.agentConfigVals` publicly
expose the generated `ConfigVals` type, which is not in the enumerated public-API list. **Fix:**
demote to `internal` (checking `docs/packages.md` per the promotion/demotion policy).

### 27. [x] Commented-out code remnants
`Proxy.kt:364` · `proxy/ProxyHttpService.kt:77-81, 95` · `proxy/AgentContext.kt:139` ·
`common/BaseOptions.kt:50, 422` — **dead code, low, confirmed**

Includes one actively misleading case: `common/BaseOptions.kt:422` has the comment
`// .resolve() Unnecessary` directly above/beside a line that *does* call `.resolve(...)`.
**Fix:** delete the remnants; correct or remove the contradictory comment.

### 28. [x] Magic numbers
`proxy/ProxyHttpService.kt:56` (`45` idle-timeout fallback) · `Agent.kt:265` (`* 2` backlog
multiplier) · `agent/AgentGrpcService.kt:152` (`awaitTermination(5, SECONDS)`) ·
`agent/AgentHttpService.kt:287` (`exponentialDelay(maxDelayMs = 5000L)`) · `Proxy.kt:273`
(`delay(500.milliseconds)`) · `proxy/ProxyGrpcService.kt:77, 152` (`2.seconds` grace) — **low,
confirmed**

**Fix:** name them as constants with a one-line rationale where non-obvious (especially the `* 2`).

### 29. [x] Duplicate `Cache-Control` header on every scrape response
`proxy/ProxyHttpRoutes.kt:93` + `proxy/ProxyUtils.kt:128` — **duplication, low, duplication
confirmed / append semantics plausible**

`handleClientRequests` sets `Cache-Control` on entry, then `respondWith` appends the same header
again (Ktor's `response.header` appends rather than replaces). **Fix:** one site owns the header.

### 30. [x] Doubled exception rendering in scrape-failure warn logging
`common/ScrapeResults.kt:104, 115` — **logging, low, confirmed**

`logger.warn(e) { "fetchScrapeUrl() $e - $url" }` interpolates the exception's `toString()` into
the message *and* passes it for a full stack trace — every scrape of a down endpoint (the most
common failure) prints the exception twice plus a stack at WARN. **Fix:** message without `$e`
(keep the throwable argument), matching the deliberately compact IOException branch at line 110.

### 31. [x] Dead parameter flexibility
`common/BaseOptions.kt:242-249` · `agent/AgentConnectionContext.kt:39` — **dead code, low,
confirmed**

Inner `parseArgs(args: Array<String>?)` declares a nullable parameter (requiring `orEmpty()`) that
shadows the constructor's non-null `args` and is called exactly once with the non-null value.
`AgentConnectionContext`'s `backlogCapacity: Int = 128` default is never used in production and
silently diverges from config if a new call site forgets to pass it. **Fix:** drop the nullability
and the default.

### 32. [x] Cleanup-service gating predicate duplicated between `startUp`/`shutDown`
`Proxy.kt:247-254` vs `265-266` — **duplication, low, confirmed**

The predicate deciding whether `agentCleanupService` runs is written twice in different shapes; an
edit to one that misses the other yields a service started-but-never-stopped (or Guava `stopSync`
on a never-started service, which throws). **Fix:** compute the predicate once into a `val` used by
both.

---

## 🔵 Kotlin idioms

### 33. [x] `HttpClientCache` uses epoch millis instead of monotonic `TimeMark`s
`agent/HttpClientCache.kt:99-103, 189, 258-265, 301, 342` — **consistency + minor correctness**

`CacheEntry(createdAt/lastAccessedAt: Long = System.currentTimeMillis())` with manual `now - then`
vs `maxAge.inWholeMilliseconds` arithmetic, while the rest of the project uses
`TimeSource.Monotonic` marks (`Agent`, `AgentContext`, `ScrapeRequestWrapper`). Beyond consistency,
an NTP wall-clock step means mass eviction or immortal entries. **Fix:**
`val createdAt: TimeMark = markNow()` + `createdAt.elapsedNow() < maxAge`.

### 34. [x] Two hand-rolled cause-chain walks with different exception sets
`agent/AgentHttpService.kt:124-137` · `common/ScrapeResults.kt:121-134` — **duplication with
behavioral drift**

Both walk `Throwable.cause` to detect a wrapped timeout, but check *different* exception sets
(`ScrapeResults.hasTimeoutCause` also matches `HttpConnectTimeoutException` /
`SocketTimeoutException`; `AgentHttpService.isTimeoutException` doesn't) — so the catch branch and
the status-code mapping can disagree about the same throwable. **Fix:** one shared
`fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }` and one
timeout predicate used by both. (Also: `AgentHttpService.kt:135` spells out
`kotlinx.coroutines.CancellationException` inline instead of importing.)

### 35. [ ] The `-1`-sentinel resolve/validate/log stanza copy-pasted ~25×
`agent/AgentOptions.kt:296-431` · `proxy/ProxyOptions.kt:166-235` · `common/BaseOptions.kt:277-354`
— **maintainability**

`if (x == -1) x = ENV.getEnv(default); require(x > 0) {...}; logger.info { "x: $x" }` recurs with
only names varying, and validation has already drifted (`scrapeMaxRetries`, `minGzipSizeBytes` get
no `require` while siblings do — see finding 11). JCommander requires the mutable annotated fields,
but resolution can collapse to a helper:
`scrapeTimeoutSecs = resolvePositiveInt(scrapeTimeoutSecs, SCRAPE_TIMEOUT_SECS, default, "scrapeTimeoutSecs")`
— mirroring the existing `resolveBooleanOption`, which proves the pattern works here.

### 36. [x] Scope-function misuse cluster
Multiple sites — **readability**

- `Agent.kt:347` / `agent/AgentGrpcService.kt:467`: `Status.fromThrowable(e).apply { logger.error(e)
  { "...${exceptionDetails(e)}" } }` — `apply` exists only to smuggle a receiver for one extension
  call; the result is discarded. Clearer: `val status = Status.fromThrowable(e)` + explicit
  `status.exceptionDetails(e)`.
- `proxy/ProxyServiceImpl.kt:250-251, 275-276`: `response.chunk.apply { ...30 lines... }` — `apply`
  conventionally *configures* its receiver; these are pure consumers → `with(...)`.
- `proxy/ProxyServiceImpl.kt:151`: `removePath(...).apply { agentContext.markActivityTime(false) }`
  — side effect unrelated to the receiver → `.also { }` or two statements.
- `agent/AgentOptions.kt:368-433` / `proxy/ProxyOptions.kt:164-288`: 65-120-line
  `apply`/`also` blocks shadow same-named properties, forcing `this@AgentOptions.` qualifiers three
  times — the classic sign the scope function is fighting you. → `val http = agentConfigVals.http`
  and plain member access.

### 37. [x] `readConfig` control flow: nullable `Throwable` + non-local returns
`common/BaseOptions.kt:447-500` — **readability**

Success paths `return` non-locally from inside `runCatchingCancellable { }`, while
`exceptionOrNull()?.also{}` feeds a `failureCause: Throwable?` consumed after the `when`. **Fix:** a
local `fun fail(configName: String, e: Throwable?): Nothing` + per-branch
`runCatchingCancellable { parse... }.getOrElse { e -> log(e); fail(configName, e) }` — no nullable
throwable, no non-local returns. (Restructure together with finding 5, which touches the same
function family.)

### 38. [x] Manual CAS loop in `decrementBacklog`
`Agent.kt:187-193` — **readability**

The hand-written `while (true) { load; compareAndSet }` loop predates the API; Kotlin 2.4's
`kotlin.concurrent.atomics` has `update` (verified present in the resolved stdlib):
`scrapeRequestBacklogSize.update { maxOf(it - delta, 0) }` — 7 lines become 1; keep the KDoc
rationale.

### 39. [x] `mergeContentTexts`: `var` flag mutated inside `map` + repeated literal
`proxy/ProxyHttpRoutes.kt:183-193` — **readability**

`var hasEof = false` is set as a side effect of the `nonEmpty.map { }` transformation, and
`"# EOF"` appears three times. **Fix:** split into `val trimmed = ...map`, `val hasEof =
trimmed.any { it.endsWith(EOF) }`, `val stripped = trimmed.map { it.removeSuffix(EOF).trimEnd() }`;
hoist an `EOF` constant.

### 40. [x] `reconnectLimiter` split declaration/`init`-assignment
`Agent.kt:174, 204` — **readability**

`private val reconnectLimiter: RateLimiter` declared, assigned 30 lines later in `init` — nothing
forces the split. Initialize at the declaration (with the finding-11 validation added first).

### 41. [x] Manual `while(true)/poll/break` drain loops
`proxy/AgentContext.kt:115-118` · `agent/AgentConnectionContext.kt:81-84` — **readability**

→ `generateSequence { scrapeRequestQueue.poll() }.forEach { it.closeChannel() }` and
`generateSequence { tryReceive().getOrNull() }.count()` respectively.

### 42. [x] Scheme-stripping `when` reducible to `removePrefix` chain
`agent/AgentGrpcService.kt:117-125` — **readability + tiny consistency gap**

The `when { startsWith... }` reduces to
`options.proxyHostname.removePrefix(HTTPS_PREFIX).removePrefix(HTTP_PREFIX)` (prefixes are mutually
exclusive; `removePrefix` no-ops on non-matches). Note the current code is case-sensitive while
`BaseOptions.isUrlPrefix` is case-insensitive — align while touching it.

---

## 📝 Additional notes (no action item)

- **Chunk-boundary quirk** (`agent/AgentGrpcService.kt:388`): a payload *exactly equal to*
  `chunkContentSizeBytes` takes the 3-message chunked path due to `<` rather than `<=`. Harmless,
  but inconsistent with the "larger than threshold" documentation — worth a one-character look if
  ever in that file.
- The `CountDownLatch`/`ReentrantLock`/`synchronized` usages were deliberately **not** flagged:
  they sit at blocking boundaries (Guava service lifecycle, gRPC channel teardown, servlet threads)
  where coroutine equivalents would be a wash or worse.
- `ScrapeResults` not being a `data class` is correct as-is (it carries a `ByteArray`; structural
  equality would be wrong).

---

## ✅ Checked and safe (adversarially traced — no re-review needed)

The concurrency reviewers explicitly traced and cleared the following; recorded so future reviews
don't re-litigate them:

**Proxy side**

- `ProxyPathManager.pathMap`: every access under `synchronized(pathMap)`;
  `AgentContextInfo.agentContexts` is immutable copy-on-write; no nested monitors anywhere → no
  deadlock potential.
- `AgentContext` queue+notifier protocol: token/entry accounting balances in every path, including
  send-failure removal and close-then-drain ordering; `readScrapeRequest` never busy-spins.
- `ScrapeRequestWrapper` completion machinery: volatile-write-before-close /
  read-after-receive happens-before is correct; double-fail across
  transportTerminated/eviction/shutdown/orphan-sweep is idempotent, exactly as the comment at
  `ProxyServiceImpl.kt:317-323` claims (modulo finding 16's narrow overwrite window).
- `activeScrapeIds` plain set: genuinely confined to the single collect coroutine.
- `ChunkedContext` internals: mutated only by the owning stream's serial collect.
- Cleanup service heartbeat TOCTOU: mitigated by the documented re-check; evicting an agent with
  in-flight scrapes does **not** hang HTTP waiters (`failAllScrapeRequests` completes them with
  502).
- HTTP-handler cancellation, shutdown ordering, ID generators, `recentReqs` synchronization, no
  `GlobalScope`/orphan jobs, no blocking I/O in coroutine handlers (`unzip` is memory-only).

**Agent side**

- `HttpClientCache` eviction/refcounting: all bookkeeping under `accessMutex`; in-use evicted
  entries are marked and closed exactly once by the last user; `close()` cancels the sweeper scope
  before taking the mutex (documented deadlock fix verified); no post-close repopulation.
- Dropped in-flight results on disconnect are deliberate, counted (`"dropped"` metric), and
  non-throwing; context `close()` is idempotent.
- Backlog accounting: increment-before-send, decrement-exactly-once across consumer/drain/failure
  paths (modulo finding 18's `closeAll()` gap).
- `grpcStub`/channel swap: `resetGrpcStubs()` runs only between connection generations after the
  prior `coroutineScope` fully joins; publication rides coroutine happens-before.
- Chunked-response ordering: a single `processScrapeResults` consumer serializes
  header→chunks→summary per scrape.
- Structured concurrency throughout: every connection's jobs joined by `coroutineScope` before the
  next reconnect iteration (finding 1 is about that join being *blocked*, not leaked).

**Positive patterns worth keeping**

- Read-only exposure of mutable state done right throughout (`chunkedContextMapView`,
  `scrapeRequestMapView`, copy-on-write `AgentContextInfo`, `buildList` interceptor assembly).
- `HttpClientCache.ClientKey`/`Credentials` is exemplary type-driven design (both-or-neither auth
  invariant made unrepresentable).
- `runCatchingCancellable` used consistently to avoid swallowing cancellation;
  `kotlin.time.Duration` for nearly all time config; kotlin-dsl proto builders;
  `typealias ScrapeRequestAction`.

---

## Suggested fix order

1. **Findings 1–5** (high): all small, targeted fixes — 3 and 4 are one-liners, 1 is a
   `triggerShutdown()` override (pattern already at `AgentContextCleanupService.kt:82`), 2 is a
   rethrow-policy change, 5 replaces `exitProcess` with a throw in embedded mode. Pair 1+2 with a
   shutdown-while-idle test and a heartbeat-failure-threshold test — the existing harness
   structurally cannot catch either (it tears down proxy and agent concurrently).
2. **Findings 6–7** (deterministic breakage / permanent-state race), then 8–13.
3. Batch the low-severity concurrency items (14–20) — several touch the same files as 1–2.
4. Batch quality/idiom items (21–42) opportunistically; 25+33 (HttpClientCache) and 5+37
   (BaseOptions) pair naturally, as do 11+35+40 (options validation).
