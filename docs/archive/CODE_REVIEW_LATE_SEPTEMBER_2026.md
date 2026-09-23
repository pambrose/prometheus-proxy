# Prometheus-Proxy Code Review — Late September 2026 Findings

**Status:** 31 issues — 31 fixed, 0 open — all findings addressed. Archived to `docs/archive/` once the last finding
merged (PR #293); line numbers and names describe the code as of the review.

**Date:** 2026-09-22

**Scope:** `master` at `0385029` — ~11.9k lines of main source, the test suite, the build, CI workflows, Docker
packaging, and documentation. Source paths below are relative to `src/main/kotlin/io/prometheus/` unless shown
otherwise; test paths are relative to `src/test/kotlin/io/prometheus/`.

**Process:** five parallel reviews — proxy-side production code, agent-side and shared code, security,
build/CI/packaging, and tests plus documentation accuracy. Each was told to read the June, July, and September 2026
reviews (`docs/archive/`) and the recent `git log` first, so fixed or already-tracked items are not resurfaced.
Overlapping findings were merged, and the key claim in each was re-checked against the cited source lines.
Confidence is marked per finding: **confirmed** (traced in this repo's code) or **plausible** (the repo-side gap
is confirmed, but the impact depends on library or Prometheus behavior read from sources rather than exercised).

**Headline:** the September fixes hold — identity binding, backlog caps, chunk ownership, and dashboard hardening
all re-checked sound. The new findings cluster in four places: the new retry/backoff logic for rejected static
paths is bypassed in the most common conflict case (#1); the scrape outcome labels mean something different from
what the docs and dashboards say (#5, #21); a handful of agent-side HTTP-client defaults (redirects, Basic auth,
`Accept` passthrough) are looser than the rest of the system (#2, #10); and #278 removed the only pre-merge build
gate (#17).

---

## 📋 Findings index

✅ = fixed/addressed · ⬜ = open

| #  | Finding                                                                         | Area     | Severity | Status |
|----|---------------------------------------------------------------------------------|----------|----------|--------|
| 1  | All static paths rejected as retryable → reconnect loop; backoff never runs     | Agent    | high     | ✅      |
| 2  | Protobuf-format scrapes are corrupted (Prometheus `Accept` passed through)      | Agent    | medium   | ✅      |
| 3  | Discovery still WARNs every reconcile for collisions, duplicates, bad file      | Agent    | low      | ✅      |
| 4  | Duplicate `agent.filters` entries for one path silently merged                  | Agent    | low      | ✅      |
| 5  | Proxy-made failures (disconnect, shutdown, drain) counted as `upstream_error`   | Proxy    | medium   | ✅      |
| 6  | Per-path series removal is O(all series) under the path lock, and leaks         | Proxy    | medium   | ✅      |
| 7  | Unvalidated backlog size / dashboard refresh: 0 rejects every scrape or spins   | Proxy    | low      | ✅      |
| 8  | Path registered with a leading slash is advertised but unscrapable              | Proxy    | low      | ✅      |
| 9  | Latency buckets stop at 10s; in-flight cap is not a memory bound                | Proxy    | low      | ✅      |
| 10 | Agent follows redirects and re-sends Basic credentials to any host              | Security | medium   | ✅      |
| 11 | Scrape port serves the target's Content-Type (HTML) with no `nosniff`           | Security | low      | ✅      |
| 12 | Agent labels can set `__*` meta labels and `job`/`instance`                     | Security | low      | ✅      |
| 13 | Raw target URL (credentials included) logged at DEBUG on every scrape           | Security | low      | ✅      |
| 14 | No cap on paths per agent/identity, path length, or labels size                 | Security | low      | ✅      |
| 15 | Unauthenticated connections create agent contexts and dashboard events          | Security | low      | ✅      |
| 16 | Unused, out-of-support Jetty 11 `jetty-servlet` ships in the fat JARs           | Security | low      | ✅      |
| 17 | PRs (incl. Dependabot) merge with no build or tests                             | CI/build | medium   | ✅      |
| 18 | SLF4J 2.0.20 bump not recorded in CHANGELOG / RELEASE_NOTES                     | CI/build | low      | ✅      |
| 19 | mkdocs-material unpinned locally; Dependabot misses uv and Docker images        | CI/build | low      | ✅      |
| 20 | Minor build tidy-ups                                                            | CI/build | low      | ✅      |
| 21 | Scrape outcome labels in the docs don't match the code                          | Docs     | medium   | ✅      |
| 22 | `KDOC_SUMMARY.md` Dokka section is stale                                        | Docs     | low      | ✅      |
| 23 | Smaller documentation drift                                                     | Docs     | low      | ✅      |
| 24 | `AgentBacklogDriftTest` tests its own copy of the logic, not the product        | Tests    | medium   | ✅      |
| 25 | Tests whose assertions don't match their names                                  | Tests    | low      | ✅      |
| 26 | Real-clock waits remain in `HttpClientCacheTest` and `AgentContextTest`         | Tests    | low      | ✅      |
| 27 | Harness binds hard-coded port 9900 outside `TestPorts`; `awaitPortReady` warns  | Tests    | low      | ✅      |
| 28 | Test resource leaks: `proxyCallTest` servers and unstopped `Agent`s             | Tests    | low      | ✅      |
| 29 | `prom/prometheus:latest` unpinned in the container suite                        | Tests    | low      | ✅      |
| 30 | Untested: retryable discovered rejection in backoff retried on URL/label change | Tests    | low      | ✅      |
| 31 | Harness binds test ports inside the Linux ephemeral range; CI bind flake        | Tests    | low      | ✅      |

---

## 🗺️ Plan: order of addressing

Each step is sized to be one PR. The order puts the pre-merge safety net first, then the one high-severity bug,
then the items that change what operators see, then hardening, and leaves tidy-ups for last.

| Step | Issues               | PR theme                                                   | Why here                                                                                                                         |
|------|----------------------|------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| 1 ✅  | #17, #18             | Restore a PR build gate; record the SLF4J bump             | Every later PR benefits from a pre-merge build. #18 is a two-line fix already pending.                                           |
| 2 ✅  | #1, #30              | Keep the agent connected when every rejection can clear    | The only high: the #264/#271 retry and backoff are bypassed in their main use case, and discovery goes down with it.             |
| 3 ✅  | #5, #21              | Accurate scrape outcome labels, and docs that match        | Label semantics and their docs must change together; alerts written from the docs never fire today.                              |
| 4 ✅  | #6, #9               | Cheap, leak-free per-path series removal; better buckets   | Same file (`ProxyMetrics.kt`); scraping stalls under the path lock at scale.                                                     |
| 5 ✅  | #10, #13, #11, #12   | Agent HTTP-client and scrape-port hardening                | Small, local changes that close a credential-leak path. #12 needs a decision on whether `job`/`instance` are reserved.           |
| 6 ✅  | #2                   | Strip protobuf from the forwarded `Accept` header          | Silent data corruption for native-histogram users; a small agent change plus a documented limitation.                            |
| 7 ✅  | #7, #8, #4           | Config and input validation                                | Startup `require`s and path normalization; low risk, each with a unit test.                                                      |
| 8 ✅  | #3                   | Log discovery warnings once                                | Follows the #267/#270 pattern already in the codebase.                                                                           |
| 9 ✅  | #24, #25, #28        | Tests that test the product and clean up after themselves  | Removes false confidence before the next refactor.                                                                               |
| 10 ✅ | #27, ~~#31~~, #26, #29 | Deterministic, collision-free test infrastructure          | Flake prevention; `TestPortsTest` then guards the harness port.                                                                  |
| 11 ✅ | #14, #15             | Per-identity path caps; defer context creation until auth  | Needs new config keys and a design choice, so it follows the quick hardening in step 5.                                          |
| 12 ✅ | #16, #19, #20        | Dependency and build hygiene                               | Remove Jetty 11 after the admin-servlet tests pass without it; extend Dependabot; tidy the Makefile and build.                   |
| 13 ✅ | #22, #23             | Documentation drift                                        | No behavior change; can ride along with any earlier PR that touches the same file.                                               |

---

## 🛰️ Agent

### 1. [x] All static paths rejected as retryable → reconnect loop; backoff never runs

**Severity:** high · **Confidence:** confirmed

**Where:** `agent/AgentPathManager.kt:147-151` (`registerPaths`), `Agent.kt:288-297` (`connectToProxy`),
`Agent.kt:320-330` (the retry task launch).

**Problem:** `registerPaths()` throws `RequestFailureException("Proxy rejected all N static paths")` whenever
every `pathConfigs` entry is rejected — including rejections whose cause is in `RETRYABLE_CAUSES`
(`HELD_BY_ANOTHER_IDENTITY`, `CONSOLIDATION_MISMATCH`), the very causes #264 and #271 made retryable. The common
case is an agent with one static path taking over from a still-connected agent under a different identity, or
joining a consolidated path another identity holds. Then:

- the throw happens before `coroutineScope`, so `retryRejectedStaticPaths` is never launched;
- the agent tears down and reconnects every `reconnectPauseSecs` (3s) — connect, register, registerPath each
  cycle, about 1,200 an hour where the backoff was meant to give about a dozen;
- `pathManager.clear()` at each connect wipes `rejectedStaticPaths`, so the backoff never grows and each cycle
  logs a fresh WARN;
- discovery runs inside the same scope, so an agent with one conflicting static path plus a discovery file
  registers none of its discovered paths either.

This is the "retry with every path down" behavior the CHANGELOG migration note says only ≤ 4.0.1 agents have.
The existing unit test (`agent/AgentPathManagerTest.kt:1260`) covers only a non-retryable rejection.

**Fix:** count only non-retryable rejections toward "all rejected"; stay connected and hand retryable ones to
the retry task. Optionally still fail over when there is another endpoint and discovery is off. Add tests for
all-retryable rejections and for a static-plus-discovery agent.

**Resolution:** `registerPaths()` now fails the attempt only when every static path is rejected and none of the
rejections can clear (`!hasRejectedStaticPaths`). One retryable rejection keeps the connection, so
`connectToProxy` reaches its `coroutineScope`, launches the retry task with its backoff intact, and starts
discovery. A proxy rejecting every path for causes that can't clear still triggers failover. The optional
"fail over anyway when another endpoint exists" was not taken: the conflicting agent is on this proxy, and the
conflict clears there. New tests in `agent/AgentPathManagerTest.kt` cover all-retryable (both causes), mixed
retryable and non-retryable, and all-non-retryable rejections. The failover entries in `CHANGELOG.md` and
`RELEASE_NOTES.md` were corrected to match.


### 2. [x] Protobuf-format scrapes are corrupted (Prometheus `Accept` passed through)

**Severity:** medium · **Confidence:** confirmed mechanism (needs native histograms or protobuf in
`scrape_protocols`)

**Where:** `agent/AgentHttpService.kt:194` (forwards `request.accept` verbatim), `agent/AgentHttpService.kt:271`
(lenient `decodeToString()` for small bodies), `proxy/ProxyUtils.kt:64` (`unzip` returns a `String`).

**Problem:** with native histograms enabled, Prometheus's `Accept` prefers `application/vnd.google.protobuf`, and
the target answers in binary protobuf. A body ≤ 512 bytes is decoded to text on the agent (invalid bytes become
U+FFFD); a larger body is gzipped intact but decoded as UTF-8 on the proxy. Either way Prometheus receives a
corrupted body labelled with the protobuf `Content-Type` and fails the scrape. `MetricFilter` fails open safely,
but the transport around it doesn't.

**Fix:** remove protobuf media types from the forwarded `Accept` (or send a text-only `Accept`), so the target
falls back to text. Document that the pipeline is text-only. Carrying bytes end to end is a larger follow-up.

**Resolution:** `AgentHttpService.textOnlyAccept` drops every `Accept` entry whose media type is
`application/vnd.google.protobuf` (case- and whitespace-insensitive), keeping the text, OpenMetrics, and `*/*` offers,
and `prepareRequestHeaders` sends the result, or no `Accept` at all when nothing is left. Tests use Prometheus's
native-histogram `Accept` string; an end-to-end test with an embedded target checks it receives no protobuf offer, and
fails when the call site is reverted to forward the header unchanged. The README and the agent configuration page
note that scrapes travel as text, so native histograms aren't available through the proxy. Carrying bytes end to end
was not attempted.

### 3. [x] Discovery still WARNs every reconcile for collisions, duplicates, and a bad file

**Severity:** low · **Confidence:** confirmed

**Where:** `agent/AgentPathManager.kt:266`, `:270`; `agent/discovery/PathDiscoveryService.kt:73`.

**Problem:** #267–#270 moved repeated failures to "WARN once, then DEBUG", but three cases were missed: a
discovered path colliding with a static one ("collides with a static path"), a duplicate path in the discovery
file, and a missing or malformed file (`warn(e)` with a full stack trace). At the default 30s interval each
logs about 2,880 times a day.

**Fix:** remember what was reported last time, the way `FileDiscoverySource.reportUnusable` does; WARN only when
the set (or the read error) changes, DEBUG otherwise.

**Resolution:** `reconcileDiscoveredPaths` now collects each reconcile's collisions and duplicates, and
`reportDiscoveryConflicts` logs each set at WARN only when it changes (naming every path), at DEBUG while it repeats,
and at INFO once it empties. The last-reported sets are guarded by `pathMutex` and reset by `clear()`, so a reconnect
reports them afresh. `PathDiscoveryService.reconcileOnce` remembers the last failure as type and message: a new one is
logged at WARN with its stack trace, a repeat at DEBUG, and the next success at INFO. Tests cover each warning appearing
once across repeated reconciles, a changed collision set or failure being reported again, recovery, and a reconnect.

### 4. [x] Duplicate `agent.filters` entries for one path silently merged

**Severity:** low · **Confidence:** confirmed

**Where:** `agent/AgentPathManager.kt:130-136`.

**Problem:** `.toMap()` keeps the last filter for a normalized path. An operator who splits allow and deny rules
for `/foo` across two entries silently loses the first.

**Fix:** reject duplicate normalized paths at startup with a clear message (or merge their lists).

**Resolution:** `AgentPathManager` rejects two `agent.filters` entries whose paths normalize to the same key, naming
the path, when it is built at startup. Merging the lists was not chosen: an allow list in one entry and a deny list
in another have no single obvious combination. Tested with `metrics` and `/metrics` entries.

---

## 📡 Proxy

### 5. [x] Proxy-made failures (disconnect, shutdown, drain) counted as `upstream_error`

**Severity:** medium · **Confidence:** confirmed

**Where:** `proxy/ProxyHttpRoutes.kt:344-352`, `:493-499` (`upstreamErrorLabel`); failures created in
`proxy/ScrapeRequestManager.kt:80-96` (`failScrapeRequest`), `proxy/AgentContext.kt:194-203` (`invalidate`),
`Proxy.kt:310` (shutdown).

**Problem:** every failure the proxy creates itself — agent disconnect or eviction, displacement or queue drain,
proxy shutdown, chunk/summary validation failure, `readRequestsFromProxy` cancellation — becomes a 502
`ScrapeResults` that `submitScrapeRequest` can't distinguish from a real target 502. All are labelled
`upstream_error` in `proxy_scrape_requests{type}`, in the latency `outcome`, and on the dashboard.
`agent_disconnected` is only emitted when the channel is already closed before queueing
(`proxy/ProxyHttpRoutes.kt:393`). So the common case — an agent dropping mid-scrape — sends operators to the
target instead of the agent connection. `proxy/ProxyHttpRoutesTest.kt:569` pins the current behavior.

**Fix:** carry an outcome label with proxy-made results (a parameter on `failScrapeRequest` / `invalidate`, or a
field next to the completion CAS) and use it in `submitScrapeRequest`: `agent_disconnected` for disconnect and
drain, `proxy_stopped` for shutdown, a new `invalid_response` for validation failures. Fall back to
`upstreamErrorLabel` only for results the agent sent. Update the tests and the docs together with #21.

**Resolution:** a new `ProxyFailure` enum (`proxy/ProxyFailure.kt`) names each proxy-made failure with its label
and status: `AGENT_DISCONNECTED` (`agent_disconnected`, 503), `PROXY_STOPPED` (`proxy_stopped`, 503), and
`INVALID_RESPONSE` (new `invalid_response`, 502). `failScrapeRequest`, `failAllScrapeRequests`, and
`failAllInFlightScrapeRequests` now require one, and `ScrapeRequestWrapper.complete` records it behind the same CAS
as the result, so a losing racer can't relabel the published result. `AgentContext.invalidate` fails drained
requests as `AGENT_DISCONNECTED`. `submitScrapeRequest` uses the recorded label and falls back to
`upstreamErrorLabel` only for the agent's own results. Disconnects and shutdowns now return 503, matching the
pre-queue `agent_disconnected` path and the troubleshooting guide. The tests that pinned `upstream_error` were
updated, including `ProxyHttpRoutesTest`'s shutdown test, which invalidated the agents before failing the in-flight
requests — the reverse of `Proxy.shutDown()`'s order. New tests cover the failure's status per kind,
`failAllScrapeRequests` scoping, the CAS keeping the first failure, and `invalid_response` end to end. The
`readRequestsFromProxy` cancellation call site has no dedicated test; it is a one-line argument change.

### 6. [x] Per-path series removal is O(all series) under the path lock, and leaks

**Severity:** medium · **Confidence:** confirmed (applies only with metrics enabled)

**Where:** `proxy/ProxyMetrics.kt:78-88` (`removePathSeries`), called inside `synchronized(pathMap)` at
`proxy/ProxyPathManager.kt:261`, `:319`; series re-created at `proxy/ProxyHttpRoutes.kt:239-242`, `:271-273`,
`:464-469`.

**Problem:**

- **Lock hold:** each call runs `collect()` on both histograms, materializing every sample of every path
  (~15 per latency child, ~11 per bytes child). An agent with 50 paths disconnecting from a proxy with 5,000
  paths does 50 full collects — millions of allocations — while every scrape and registration waits on the
  `pathMap` monitor.
- **Leak:** `removeAgentContext` removes a path's series before `failAllScrapeRequests` wakes the waiting
  handlers, which then `observe()` and re-create them. An in-flight scrape finishing after an `unregisterPath`
  does the same. If the path never comes back, the series stay forever, so the #252 fix is incomplete.

**Fix:** track the second-label values each path has used (a `ConcurrentHashMap<String, MutableSet<String>>`
filled where metrics are observed) and remove exactly those, with no `collect()`. Gather removed paths inside
the lock and remove series after releasing it. Skip observing (or re-run removal) when the path is no longer
registered at completion.

**Resolution:** `ProxyMetrics` keeps a map from each registered path to the (histogram, label) series it has
recorded. `addPath` calls the new `pathRegistered`, the scrape route records through `observeLatency` /
`observeResponseBytes`, and `removePathSeries` removes exactly the tracked series with no `collect()`. Observing uses
`computeIfPresent` and removal `compute` on the same key, so they are atomic per path: a scrape finishing after its
path's removal records nothing, closing the leak. Removal is now proportional to the path's own series, so it stays
inside the path lock. Moving it outside would open a race with a re-registration, which would lose the new
registration's tracking. New tests cover: removal of only the recorded series (fails under the old `collect()` scan),
a late scrape not re-creating series, re-registration recording again, and `addPath` registering (not on rejection).
Each was checked against a mutation of the code it guards.

### 7. [x] Unvalidated backlog size / dashboard refresh: 0 rejects every scrape or spins

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyHttpRoutes.kt:388` → `proxy/AgentContext.kt:148`; `proxy/dashboard/ProxyDashboardService.kt:142`,
`:351-358`; validation block in `proxy/ProxyOptions.kt` (~`:289-310`).

**Problem:** since #246, `proxy.internal.scrapeRequestBacklogUnhealthySize` also caps each agent's queue at twice
its value. At `0`, every scrape returns 503 `agent_backlog_full`. The agent validates its copy
(`agent/AgentOptions.kt:429-436`); the proxy doesn't. Likewise `proxy.dashboard.refreshIntervalSecs <= 0` makes
`withTimeoutOrNull(0)` return immediately, so `pushLoop` spins a `Dispatchers.IO` thread at 100%.

**Fix:** add `requirePositive(...)` for both next to the existing `internal.*` checks, with tests.

**Resolution:** `ProxyOptions` requires `internal.scrapeRequestBacklogUnhealthySize` > 0, and
`dashboard.refreshIntervalSecs` > 0 when the dashboard is enabled (next to `dashboard.maxSessions`, so a disabled
dashboard's unused value can't stop the proxy starting). Tests cover both rejections and a 0 refresh interval
accepted with the dashboard off.

### 8. [x] Path registered with a leading slash is advertised but unscrapable

**Severity:** low · **Confidence:** confirmed (the in-tree agent strips the slash; needs an older or custom agent)

**Where:** `proxy/ProxyPathManager.kt:101-106`, `:167`, `:187` (raw key stored); `proxy/ProxyHttpRoutes.kt:119`
(lookup uses `path().drop(1)`); `Proxy.kt:512` (SD).

**Problem:** `"/foo"` passes the multi-segment check, `isAuthorized`, and the SD builder, but is stored under the
key `"/foo"` while scrapes look up `"foo"` → 404 `invalid_path` for a path service discovery advertises.
`"/foo"` and `"foo"` are separate keys, so the takeover and consolidation checks don't see them as the same
path. `"/"` alone is accepted too.

**Fix:** normalize (`removePrefix("/")`) once at the start of `addPath` / `removePath`, reject a blank result as
`INVALID_PATH`, and return a `PathRejection` rather than letting a `require` surface as gRPC `UNKNOWN`.

**Resolution:** `ProxyPathManager.pathKey` strips the leading slash, and `addPath`, `removePath`, and
`getAgentContextInfo` all use it, so a path is stored and found under the key the scrape route looks up. `addPath`
rejects `/` with `INVALID_PATH` through the usual `PathRejection`. Since the metric-series tracking from #6 is keyed
by the stored path, a slash-registered path's series now also match what the scrape route observes. Tests cover the
stored key, `/metrics` and `metrics` conflicting as one path, removal given either form, and `/`; two existing tests
that pinned the slashed key were updated.

### 9. [x] Latency buckets stop at 10s; in-flight cap is not a memory bound

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyMetrics.kt:60`; `proxy/ScrapeRequestManager.kt:111-116`; `config/config.conf:97-105`.

**Problem:** the agent's default scrape timeout is 15s and the proxy's 90s, so every timeout lands in `+Inf` and
the slow tail has no resolution. The comment on `maxInFlightScrapeRequests` says it "bounds the proxy's memory",
but 1,000 in flight × (chunk buffer + copy + unzipped bytes + `String`) is tens of GB at the defaults.

**Fix:** add 15/30/60/90s buckets. Describe the cap as a concurrency limit, or add a separate byte budget.

**Resolution:** the latency histogram gains 15, 30, 60, and 90s buckets, with a test and the bucket lists in both
metrics pages updated. The `tryAddToScrapeRequestMap` KDoc now calls `maxInFlightScrapeRequests` a concurrency limit
and says what each in-flight scrape can buffer. A byte budget was not added.

---

## 🔒 Security

### 10. [x] Agent follows redirects and re-sends Basic credentials to any host

**Severity:** medium · **Confidence:** plausible (traced in Ktor 3.6.0 sources, not exercised)

**Where:** `agent/AgentHttpService.kt:288-333` (`newHttpClient`: redirects on by default;
`install(Auth) { basic { ... } }` with no realm or host limit, at `:325`).

**Problem:** `HttpRedirect` wraps the `Auth` interceptor, so every redirect hop goes back through `Auth`, and
`BasicAuthProvider.isApplicable` checks only the realm. A hostile or compromised target — or an open redirect
reachable through query parameters that any HTTP caller can forward via the proxy — answers
`302 Location: http://attacker/`, which returns `401 WWW-Authenticate: Basic`, and the agent re-sends the
credentials configured for the real target. The same redirect can point at an internal address (e.g.
`169.254.169.254`), and the body comes back through the proxy's unauthenticated scrape port. Ktor strips an
explicit `Authorization` header on cross-authority redirects, but that doesn't cover the Auth plugin re-adding it.

**Fix:** set `followRedirects = false` (or allow same-authority redirects only), and limit the Basic provider to
the configured host (`sendWithoutRequest { it.url.host == originalHost }`, refusing other hosts' challenges).
Add a test with a redirecting target.

**Resolution:** the agent's client sets `followRedirects = false` and installs an `HttpSend` interceptor,
`followSameOriginRedirects`, that follows up to five redirects only while each stays on the request's scheme, host,
and port. Any other redirect is returned as its 3xx status and logged at WARN with both URLs redacted. With no other
origin reachable, the Auth plugin only ever answers a challenge from the target itself, so its Basic provider needed
no change. A test that redirects to a second server issuing a Basic challenge failed before the fix — the agent
followed the redirect, sent the credentials, and scraped the second server's body with a 200 — and now sees the 302,
with the second server never contacted. A same-origin redirect is still followed.

### 11. [x] Scrape port serves the target's Content-Type (HTML) with no `nosniff`

**Severity:** low · **Confidence:** confirmed

**Where:** `agent/AgentHttpService.kt:223`; `proxy/ProxyHttpRoutes.kt:341`, `:414-425`;
`proxy/ProxyUtils.kt:123-130`; `proxy/ProxyHttpConfig.kt:59`.

**Problem:** a compromised target (or an agent limited to its own paths) can return `text/html` with a script;
the proxy serves it from its own origin with no `X-Content-Type-Options` or CSP. An operator opening the URL runs
the script on the proxy origin, which can read every other path and the SD JSON through the operator's browser.

**Fix:** allow only exposition content types (`text/plain`, `application/openmetrics-text`, protobuf) and fall
back to `text/plain`; add `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox`.

**Resolution:** `parseContentType` passes through only `text/plain`, `application/openmetrics-text`, and
`application/vnd.google.protobuf` (matched without parameters) and serves anything else as `text/plain`, logging at
DEBUG. `respondWith`, which writes every scrape-port response, adds `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: sandbox`. The dashboard, on its own port, is unchanged. Tests cover HTML, script, and SVG
types becoming `text/plain`, the exposition types being kept with their parameters, and both headers.

### 12. [x] Agent labels can set `__*` meta labels and `job`/`instance`

**Severity:** low · **Confidence:** plausible (proxy side confirmed; Prometheus precedence read from
`PopulateLabels`, not tested)

**Where:** `Proxy.kt:519-529`; `Proxy.kt:552` (`RESERVED_SD_LABEL_KEYS` = `__metrics_path__`, `agentName`,
`hostName`).

**Problem:** in HTTP SD, target labels override the scrape config's `job`, `__scheme__`, `__scrape_interval__`,
`__scrape_timeout__`, and `__param_*`. An identity authorized only for `team-a-*` can label its targets
`job="team-b"` to pass as another tenant, or change its own scrape's scheme, timeout, or parameters.

**Fix:** reject every key starting with `__`; optionally let operators reserve `job` and `instance`, or give
each identity a label allowlist.

**Resolution:** `Proxy.isReservedSdLabelKey` keeps the proxy's own keys and every `__`-prefixed key out of the
service-discovery response, and `job` and `instance` too when the new
`proxy.service.discovery.reserveJobAndInstanceLabels` is set. It defaults to `false`, since setting `job` from agent
labels is a plausible legitimate use; `ConfigVals` was regenerated with `make tsconfig`. The labels are dropped
rather than the registration rejected, as the three reserved keys were before. The warning moved from every
service-discovery poll to once at `addPath`, naming the dropped keys. A per-identity label allowlist was not added.
Documented on the service-discovery page, the agent `labels` row, and `security-agent-authentication.md`.

### 13. [x] Raw target URL (credentials included) logged at DEBUG on every scrape

**Severity:** low · **Confidence:** confirmed

**Where:** `agent/AgentHttpService.kt:120` (`"Fetching $pathContext ..."`); `agent/AgentPathManager.kt:454-461`
(`PathContext` is a `data class` whose `toString` includes `url`); `common/BaseOptions.kt:523` (config URL at
ERROR).

**Problem:** the same log line prints a redacted `logUrl`, but interpolating `$pathContext` prints the raw URL
with userinfo and query secrets — a leftover of September #5.

**Fix:** override `PathContext.toString()` to use `sanitizeUrl(url)` (or log only `path`); sanitize the config
URL in `BaseOptions`.

**Resolution:** `PathContext.toString()` redacts the URL. In `BaseOptions`, the invalid-config-URL error, the generic
load-failure error (which no longer logs a stack trace, whose first line was the raw exception message), and the
`ConfigLoadException` message are redacted. When the exception's cause carried a URL in its message, it is replaced by
a stand-in with the redacted message and the original stack trace, since an embedded host logging the exception prints
the cause too. Tests cover the `toString` and a config URL with credentials that gets a 404.

### 14. [x] No cap on paths per agent/identity, path length, or labels size

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyServiceImpl.kt:191-238`; `proxy/ProxyPathManager.kt:122-211`; `proxy/AgentContext.kt:74`,
`:179` (`loggedPathRejections`).

**Problem:** an identity limited to `team-a-*` can still register unbounded matching paths, each adding a
`pathMap` entry, an SD target, and per-path metric series. Path length and labels are bounded only by gRPC's
4 MiB message cap, and `loggedPathRejections` grows without bound.

**Fix:** add `maxPathsPerAgent` (or per identity), a maximum path length and labels size, and bound
`loggedPathRejections`.

**Resolution:** the three limits are new `proxy.internal` settings, and `0` turns each off:
- `maxPathsPerAgent` defaults to 10,000, about 40× the largest harness profile. It is per agent connection, not per
  identity, because every legacy-token agent shares one identity, so a per-identity cap would limit the whole fleet.
- `maxPathLength` defaults to 512 characters.
- `maxLabelsSizeBytes` defaults to 8 KiB.

How they're enforced:
- A path past the count limit is refused with the new `PathRejectionCause.PATH_LIMIT_REACHED` (proto value 7, an
  additive change). An over-long path or over-large labels are refused with `INVALID_PATH`. The agent treats both
  as non-retryable.
- `ProxyPathManager` keeps a per-agent path count under the `pathMap` lock, updated on add, re-registration (not
  counted), displacement, consolidated join and leave, unregister, and the disconnect sweep. Enforcement needs no
  scan, and the displacement orphan check now reads the count instead of scanning the map.
- The proxy WARNs once when an agent reaches 80% of the cap.
- A too-long path is truncated in the reason and in the connection's rejection record. `loggedPathRejections`, one
  entry per path, is now bounded by the cap.

Tests cover each limit, re-registration, room freed by each way a path leaves, other agents' paths, 0 meaning
unlimited, the warning, and option validation. Mutating each of the three count decrements fails a test.

### 15. [x] Unauthenticated connections create agent contexts and dashboard events

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyServerTransportFilter.kt:32-41`; `proxy/AgentContextManager.kt:62-65`.

**Problem:** `transportReady` creates an `AgentContext` for every HTTP/2 transport before any token is checked —
logging at INFO, emitting `AgentConnected` to the dashboard, and counting in agent metrics. With `proxy.auth` on,
anyone who can reach port 50051 can loop connections to flood logs and the dashboard. There is no connection cap
and `maxConnectionIdle` defaults off. `docs/security-agent-authentication.md` lists this only for the no-auth
default.

**Fix:** create the context (or at least emit the event and count it) on the first authenticated call; consider
a default `maxConnectionIdle` or a connection cap; document the remaining gap.

**Resolution:** the transport filter still creates the context in `transportReady`, since it must stamp the
connection's `agentId`, but adds it pending (`addAgentContext(context, announce = false)`). A pending context is
findable by `agentId` but logged only at DEBUG, and left out of `agentContextSize` (the `proxy_agent_map_size`
gauge) and `agentContextEntries` (the dashboard and health checks), with no `AgentConnected`.

`connectAgent` — the agent's first call, which the auth interceptor has already let through — announces it: INFO
log, `AgentConnected`, and counted from then on. `removeAgentContext` emits `AgentDisconnected` only for an announced
context. Stale-context eviction still reaches pending contexts, since it reads the full map.

Filter-disabled mode creates its context in `connectAgentWithTransportFilterDisabled`, already after auth, so it
announces at once. Neither a connection cap nor a default `maxConnectionIdle` was added.

Tests cover pending vs announced counting and listing, the event firing once at announcement, the transport filter
not announcing, `connectAgent` announcing, and no `AgentDisconnected` for a never-announced context. Reverting the
filter to announce immediately fails the filter test.

### 16. [x] Unused, out-of-support Jetty 11 `jetty-servlet` ships in the fat JARs

**Severity:** low · **Confidence:** confirmed that `src/` has no Jetty import; removal still needs a build and the
admin-servlet tests to confirm nothing transitive depends on it

**Where:** `gradle/libs.versions.toml:45`, `:105`; `build.gradle.kts:103`.

**Problem:** `org.eclipse.jetty:jetty-servlet:11.0.26` is declared directly, but the admin and metrics servers run
on Jetty 12.1.x via common-utils. The JAR still carries the Jetty 11 servlet classes, which no longer get
community security releases and will be flagged by scanners. Separately, `io.prometheus:simpleclient:0.16.0` is
the final release of the legacy client — worth tracking.

**Fix:** remove the dependency and version entry, then run the admin/metrics tests and a fat-JAR smoke run.

**Resolution:** removed from `build.gradle.kts` and the version catalog. The runtime classpath now holds only Jetty
12.1.13, the admin, metrics, and health-check specs (34 tests) pass, and the fat JARs no longer contain
`org/eclipse/jetty/servlet/`. `simpleclient` 0.16.0 is noted, not replaced.

---

## 🏗️ CI and build

### 17. [x] PRs (incl. Dependabot) merge with no build or tests

**Severity:** medium · **Confidence:** confirmed (a deliberate change in #278, noted here as a trade-off to
revisit)

**Where:** `.github/workflows/ci.yml:3-6`; `.github/workflows/container-tests.yml`; `master` branch protection
(only Codacy required); `build.gradle.kts:437-439` (comment still says Codecov gates PRs).

**Problem:** #278 dropped the `pull_request` trigger and `build` as a required check, reversing September #18.
Dependabot opens weekly grouped Gradle and Actions PRs that can now merge without compiling, running tests, or the
coverage gate, and Codecov no longer receives PR uploads, so `codecov.yml`'s PR gate is inert.

**Fix:** restore a light `pull_request` job (`build -x test` plus unit tests, container tests left to `master`)
and require it — at minimum for `dependabot[bot]` PRs. Update the `build.gradle.kts` comment to match.

**Resolution:** `ci.yml` runs on every pull request to `master` again, with the full build, tests, coverage floors,
and Codecov upload, and its concurrency group again cancels a superseded pull-request run while never cancelling a
run on `master`. The container tests stay on `master` pushes and `workflow_dispatch`. `build` is restored as a
required status check on `master` once this merges. With PR runs back, the `build.gradle.kts` comment about Codecov
gating a PR is accurate again and is unchanged. `CHANGELOG.md` and `RELEASE_NOTES.md` now describe this.

### 18. [x] SLF4J 2.0.20 bump not recorded in CHANGELOG / RELEASE_NOTES

**Severity:** low · **Confidence:** confirmed

**Where:** `gradle/libs.versions.toml:57`; `CHANGELOG.md` (Unreleased "Update dependencies"); `RELEASE_NOTES.md`
("Runtime:" paragraph).

**Problem:** both logs say "SLF4J 2.0.18 → 2.0.19"; the catalog has 2.0.20.

**Fix:** change both to "2.0.18 → 2.0.20".

**Resolution:** both logs now read SLF4J 2.0.18 → 2.0.20.

### 19. [x] mkdocs-material unpinned locally; Dependabot misses uv and Docker images

**Severity:** low · **Confidence:** confirmed

**Where:** `Makefile:271` (`--with mkdocs-material`) vs `.github/workflows/docs.yml:35`
(`--with mkdocs-material==9.7.7`); `.github/dependabot.yml`; `etc/docker/agent.df:6`, `etc/docker/proxy.df:6`,
`nginx/docker/Dockerfile:1`.

**Problem:** `make site` resolves the latest mkdocs-material while CI pins 9.7.7 — residual drift from September
#19 — and the pin lives only in `docs.yml`, which Dependabot never updates. Dependabot covers only
`github-actions` and `gradle`, so `website/uv.lock` and the digest-pinned base images are bumped by hand.

**Fix:** add `mkdocs-material` to `website/pyproject.toml` so `uv.lock` pins it and drop `--with` in both places;
add `docker` entries for `/etc/docker` and `/nginx/docker`, and a `uv` entry for `/website`.

**Resolution:** `mkdocs-material==9.7.7` is in `website/pyproject.toml`'s dev group and `uv.lock` (re-locked without
`--upgrade`, so only it and its dependencies were added). `make site` and `docs.yml` dropped `--with`, and the site
builds cleanly from the lock. `mkdocs-material` is needed for `zensical.toml`'s `material.extensions.emoji`. Dependabot
gains a `uv` entry for `/website` and a `docker` entry for `/nginx/docker`. `/etc/docker` was not added: Dependabot only
recognizes files named `Dockerfile`, `*.Dockerfile`, or `Dockerfile.*`, so `agent.df` and `proxy.df` would need
renaming, which touches the Makefile, container tests, scripts, and docs. Their digest-pinned bases stay manual, as
the comment in each file already says.

### 20. [x] Minor build tidy-ups

**Severity:** low · **Confidence:** confirmed

- `Makefile:55`, `:60`, `:74` — explicit `generateProto` / `stubs` prerequisites are redundant (the protobuf
  plugin runs it before compile).
- `build.gradle.kts:430-431` — Kover `log { onCheck = true }` makes `build -x test` print a coverage line with no
  tests run; CI already calls `koverLog` explicitly.
- `build.gradle.kts:400` — Detekt baseline points at a nonexistent `config/detekt/baseline.xml`.
- `testing/start-prometheus.sh:6-7` — unquoted `$(pwd)` and an unpinned `prom/prometheus`.
- `website/pyproject.toml` — placeholder `description` and a `readme` that doesn't exist.
- `config/jars/tscfg-1.2.5.jar` — 9 MB, the largest tracked file; could be fetched on demand by `make tsconfig`
  (optional).

**Resolution:**
- `make build`, `tibuild`, and `jars` no longer run `generateProto`; `make stubs` still does.
- `testing/start-prometheus.sh` quotes the mount and pins `prom/prometheus:v3.14.0`.
- `website/pyproject.toml` has a real description and no nonexistent readme.

Two items were left as they are, since neither was wrong:
- **Detekt baseline:** its path is where `make detekt-baseline` writes one, so the missing file is expected.
- **`koverLog` on `check`:** `CLAUDE.md` documents it as a console summary of every check run, and `build -x test`
  printing a stale line is the lesser cost.

The tscfg JAR stays tracked, so `make tsconfig` keeps working offline.

---

## 📚 Documentation

### 21. [x] Scrape outcome labels in the docs don't match the code

**Severity:** medium · **Confidence:** confirmed

**Where:** `docs/metrics-and-grafana.md:80-98`, `:114`; `website/prometheus-proxy/docs/monitoring.md:75-86`,
`:104`; `website/prometheus-proxy/docs/troubleshooting.md:88`, `:91`; vs `proxy/ProxyUtils.kt:104`, `:112` and
`proxy/ProxyMetrics.kt:59`.

**Problem:**

- The docs list `proxy_not_running`; the code emits `proxy_stopped`.
- An empty path emits `missing_path`, which isn't documented; the docs attribute it to `invalid_path`.
- `agent_disconnected` is described as a mid-scrape disconnect, which is actually reported as `upstream_error`
  (see #5).
- `no_agents` is described as "no agents registered for the path", which actually yields `invalid_path`.
- `proxy_scrape_request_latency_seconds` has an `outcome` label (since #162); the docs list only `path`.

Alerts and dashboards written from the docs never match.

**Fix:** correct all three pages in the same PR as #5, so the descriptions match the corrected labels.

**Resolution:** `docs/metrics-and-grafana.md` and the site's `monitoring.md` now list `proxy_stopped`,
`missing_path`, and `invalid_response`, describe `invalid_path`, `no_agents`, and `agent_disconnected` as the code
counts them, explain that `upstream_error` is always the target's own status, and list the latency histogram's
`outcome` label. `troubleshooting.md` names `proxy_stopped` and `agent_disconnected` correctly under 503 and gains a
502 section for `upstream_error` and `invalid_response`.

### 22. [x] `KDOC_SUMMARY.md` Dokka section is stale

**Severity:** low · **Confidence:** confirmed

**Where:** `docs/KDOC_SUMMARY.md:47`, `:165`, `:169`, `:178`.

**Problem:** it says `make dokka` (the target is `make kdocs`), cites Dokka 2.1.0 (the catalog has 2.2.0), says
`internal` is documented (`build.gradle.kts:320` sets `Public` only), and heads a 10-row table "9 classes".

**Fix:** correct each item.

**Resolution:** corrected: `make kdocs`, Dokka 2.2.0, `public` visibility only (with why the `internal` KDoc still
matters), and "10 classes".

### 23. [x] Smaller documentation drift

**Severity:** low · **Confidence:** confirmed

- `website/prometheus-proxy/docs/configuration/agent.md:102` — says a retryable rejected discovered path is
  "retried each poll"; since #271 it backs off, and `rejectedPathRetryMaxSecs` isn't mentioned for discovery.
- `docs/TESTING.md:129` — claims to "name every spec" but omits `ProxyDashboardServiceTest` and
  `ConfigLoadExceptionTest`; `:441-450` omits `koverVerify` / `koverVerifyPerClass`, the 80% per-class floor, and
  the separate-invocation rule.
- `.claude/skills/publishing-release/SKILL.md:14` — `make check-gpg-env`; the target is `_check-gpg-env`.
- `llms.txt:181` — cites shadow 9.4.2; the build uses 9.6.1.
- `README.md:5` — Kotlin badge says 2.4.0; the catalog has 2.4.20. Add the badge to the release checklist.

**Resolution:**
- The agent page's discovery table describes the backoff: the next poll, one interval, then doubling to the cap.
- `TESTING.md` lists `ConfigLoadExceptionTest` and `ProxyDashboardServiceTest`; a check of every `*Test.kt` against it
  now finds nothing missing. Its coverage section documents both Kover gates, the 80% per-class floor, and the
  separate-invocation rule.
- The release skill names `_check-gpg-env`.
- `llms.txt` qualifies the shadow version as the one the finding was made on.
- The README badge says 2.4.20.

The badge was not added to the release checklist. It tracks Kotlin upgrades, not releases, so a release checklist
would check it at the wrong time.

---

## 🧪 Tests

### 24. [x] `AgentBacklogDriftTest` tests its own copy of the logic, not the product

**Severity:** medium · **Confidence:** confirmed

**Where:** `agent/AgentBacklogDriftTest.kt:38-82` vs `agent/AgentGrpcService.kt:470-475`.

**Problem:** the test re-implements the increment / try / decrement-and-rethrow of
`AgentGrpcService.readRequestsFromProxy` in its own body (with `-= 1` where the product calls
`decrementBacklog(1)`) and asserts its own arithmetic. Its name ("should drift") contradicts what it asserts. The
agent-side `readRequestsFromProxy` has no other test, so deleting the production `catch` would still pass. The
`Agent` it creates is never stopped.

**Fix:** drive the real `readRequestsFromProxy` with a mocked stub flow and a closed `AgentConnectionContext`,
and assert the backlog returns to its prior value.

**Resolution:** `AgentBacklogDriftTest` was rewritten to drive the real `readRequestsFromProxy` on a real `Agent`,
with only `grpcService.grpcStub` replaced by a mock streaming the requests. One test checks each forwarded request is
counted; the other forwards onto a closed `AgentConnectionContext` and checks the `ClosedSendChannelException`
propagates and the backlog returns to 0. Deleting the production `decrementBacklog(1)` makes the second fail. The
agents it builds are released after each test.

### 25. [x] Tests whose assertions don't match their names

**Severity:** low · **Confidence:** confirmed

- `agent/AgentHttpServiceTest.kt:281` "close should close httpClientCache" — no assertion.
- `proxy/AgentContextTest.kt:474` "...should keep both timestamps consistent" — never reads
  `lastRequestDuration`; `:437` never checks request time; `:377` `durationAfter shouldNotBe durationBefore` is
  true by construction; `:392` "equals should be based on agentId" passes under identity equality.
- ~~`proxy/ProxyHttpRoutesTest.kt:569` "should report agent-disconnect" asserts `upstream_error` (fix with #5).~~
  Fixed with #5: it now asserts `agent_disconnected`.
- `agent/AgentPathManagerTest.kt:1029` "should keep retrying while...rejected" counts loop checks, not register
  calls — under the backoff only one real retry happens.

**Fix:** assert what each name claims, or rename.

**Resolution:** each now asserts what its name claims.
- The close test checks the cache refuses a new client with "closed".
- `AgentContext` gained an injectable `clock` (default `Monotonic`) and an `internal` `lastRequestDuration`, so its three
  activity tests assert exact durations on a `TestTimeSource`. The "isRequest false" test fails if a mark also resets
  the request time, which the old version didn't check.
- The `equals` test gives a second context the first's `agentId` by reflection (a spy fails the `javaClass` check), since
  no two real contexts share one.
- The retry-loop test turns the backoff off, advances a test clock by the loop's interval each pass, and counts register
  calls.

The `ProxyHttpRoutesTest` item was fixed with #5.

### 26. [x] Real-clock waits remain in `HttpClientCacheTest` and `AgentContextTest`

**Severity:** low · **Confidence:** confirmed (flakiness suspected, not observed)

**Where:** `agent/HttpClientCacheTest.kt:541`, `:828`, `:855`, `:891`, `:932`, `:963-966`;
`proxy/AgentContextTest.kt:478-486`.

**Problem:** September #31 moved only some expiry tests to `TestTimeSource`; at least six still sleep 150–600 ms
and assert a real 50–100 ms sweeper ran. `AgentContextTest.kt:486` asserts `inactivity < 50ms` after a real
`Thread.sleep(100)`, which a GC or CI stall fails.

**Fix:** inject `TestTimeSource` into the rest; replace sweep sleeps with `eventually` or a signal.

**Progress:** the `AgentContextTest` half is done with #25: `AgentContext` takes an injectable clock and its activity
tests no longer sleep. The `HttpClientCacheTest` sleeps remain.

**Resolution:** four `HttpClientCacheTest` expiry tests (expired-client close, replacement on access, background
removal, removal of all expired entries) now pass the spec's `TestTimeSource` to their caches, advance it past expiry,
and wait for the sweeper with `eventually` (5s ceiling, 10ms sweep interval) instead of sleeping and then asserting.
Replacement on access needs no wait at all. The two close-under-load tests keep a short sleep: it only gives the
sweeper work, and their assertions have 1–5s of headroom, so they aren't timing-sensitive in the same way.

### 27. [x] Harness binds hard-coded port 9900 outside `TestPorts`; `awaitPortReady` warns

**Severity:** low · **Confidence:** confirmed

**Where:** `harness/support/HarnessTests.kt:106` (`agentPort: Int = 9900`), `:84-100` (`awaitPortReady`);
`config/test-configs/web-ui-paths.conf:6-7`, `:16` (URL on 9558).

**Problem:** every `AbstractHarnessTests` subclass binds a real CIO server on 9900 in `timeoutTest`; it isn't in
`TestPorts`, so `TestPortsTest` can't catch a collision and a foreign listener on 9900 breaks all seven harness
specs. `awaitPortReady` only logs on timeout, while its sibling `awaitPortFree` fails fast. Separately,
`web-ui-paths.conf`'s "port nothing serves" is 9558, which is `DASHBOARD_UI_ROOT_DASHBOARD_PORT`.

**Fix:** move 9900 into `TestPorts` (or per spec), make `awaitPortReady` throw, and point the web-ui config at a
reserved constant or `http://unserved.invalid/metrics`.

**Resolution:**
- `timeoutTest`'s target moved from 9900 to `TestPorts.HARNESS_TIMEOUT_TARGET_PORT` (9528).
- The three literal ports (9525–9527) are now `IDLE_SHUTDOWN_HTTP_PORT`, `HEARTBEAT_DISABLED_HTTP_PORT`, and
  `METRIC_FILTER_HTTP_PORT`. The comments claiming `TestPorts` was only for default values were corrected, in
  `AgentProxyFailoverTest` too.
- `awaitPortReady` is `internal` and fails with the port number when nothing answers. Two new `HarnessHelpersTest`
  cases cover it.
- `web-ui-paths.conf` points at `http://ui-path-target.invalid/metrics`.

### 28. [x] Test resource leaks: `proxyCallTest` servers and unstopped `Agent`s

**Severity:** low · **Confidence:** confirmed (servers); likely (agent channels)

**Where:** `harness/support/HarnessTests.kt:147-282`; `agent/AgentTest.kt` (~40 `createTestAgent()` calls);
`agent/AgentBacklogDriftTest.kt`; channel built at `agent/AgentGrpcService.kt:207`.

**Problem:** `proxyCallTest` starts N CIO servers and stops them with no `try/finally`, so a failed assertion
leaves them bound for the rest of the JVM. Each unstopped `Agent` leaks a `ManagedChannel`.

**Fix:** wrap `proxyCallTest`'s body in `try/finally`; have `createTestAgent` register instances for a shared
`afterTest` cleanup.

**Resolution:** `proxyCallTest` now starts, exercises, and checks inside `try` and stops its servers in a `finally`
under `NonCancellable`, since a `withTimeout` expiry is one of the failures it must survive. This was checked by review
and by the harness specs passing, not by injecting a failure. `AgentTest.createTestAgent` records each agent, and an
`afterTest` releases its gRPC channel and HTTP client cache (in `runCatching`, since some tests stop their agent
themselves). `AgentBacklogDriftTest`'s agents are released the same way (#24).

### 29. [x] `prom/prometheus:latest` unpinned in the container suite

**Severity:** low · **Confidence:** confirmed

**Where:** `containers/support/ContainerTestSupport.kt:199`.

**Problem:** the image floats — the same reproducibility gap September #29 closed for nginx.

**Fix:** pin a tag in a shared constant (and add it to Dependabot's Docker coverage with #19 if practical).

**Resolution:** `ContainerTestSupport.PROMETHEUS_IMAGE` pins `prom/prometheus:v3.14.0`, the newest release on Docker
Hub today, next to `NGINX_IMAGE`; `CLAUDE.md` and `docs/TESTING.md` name the version. Dependabot can't see a tag in
Kotlin source, so bumping it stays manual.

### 30. [x] Untested: retryable discovered rejection in backoff retried on URL/label change

**Severity:** low · **Confidence:** confirmed

**Where:** `agent/AgentPathManager.kt:305-307`; `agent/AgentPathManagerTest.kt:602` (covers only the
non-retryable case).

**Problem:** the rule that a retryable discovered rejection still waiting out its backoff is retried immediately
when its URL or labels change has no test.

**Fix:** add the test (fits naturally in the #1 PR).

**Resolution:** added "reconcile should try a discovered path waiting out its backoff at once when its entry
changes" to `agent/AgentPathManagerTest.kt`. It builds up a 60s backoff, checks a poll inside it doesn't retry,
then checks a changed URL and then changed labels are each tried at once. Temporarily removing the URL/labels
comparison in `registerDiscoveredPath` makes it fail.



### 31. [x] Harness binds test ports inside the Linux ephemeral range; CI bind flake

**Severity:** low · **Confidence:** confirmed (observed on PR #280's first CI run; a rerun passed)

**Where:** `common/TestPorts.kt:64-86` — `TLS_NO_MUTUAL_AUTH_AGENT_PORT` (50440), `TLS_MUTUAL_AUTH_AGENT_PORT`
(50441), and 50460–50465 (`TLS_REJECTION_AGENT_PORT`, `TOKEN_AUTH_AGENT_PORT_OK` / `_BAD`, `PATH_AUTH_AGENT_PORT`,
`DISCOVERY_AGENT_PORT`, `REJECTED_PATH_RETRY_AGENT_PORT`).

**Problem:** Linux assigns outgoing connections a local port from 32768–60999, so any connection another test opens
can briefly hold one of these ports. On PR #280, `TlsNoMutualAuthTest`'s proxy failed to start with
`BindException: Address already in use` on `0.0.0.0:50440`; no test binds that port, and `TlsWithMutualAuthTest`
on 50441 passed in the same run. `TestPortsTest` guards against duplicates, not against this range. (`50051` also
sits in the range but, as a product default, is never bound on the host.)

**Fix:** move every harness port that is bound on the host below 32768, and have `TestPortsTest` fail on any bound
port in the ephemeral range.

**Resolution:** done ahead of step 10, after the same flake hit PR #283 on 50460. The eight ports moved to 9517–9524,
and `TestPortsTest` has a new case that fails on any `TestPorts` constant of 32768 or above, with `PROXY_AGENT_PORT`
(50051) the one exemption, since it is only asserted as a value and used inside containers. The case failed on
exactly the eight constants before the move. Three specs hard-code 9525–9527 outside `TestPorts`
(`InProcessIdleShutdownTest`, `InProcessHeartbeatDisabledTest`, `AgentMetricFilterTest`); those are below the range,
and are left to #27.

---

## ✅ Appendix: checked and sound

- **Identity binding and auth:** SHA-256 token digests compared with `MessageDigest.isEqual`; empty, duplicate,
  and shared tokens rejected at startup; the interceptor covers every RPC including reflection;
  `connectionMismatchReason`, `identityMismatchReason`, and `isScrapeOwnedByConnection` cover heartbeats, unary
  results, and every chunk stage; cross-identity takeover and consolidated joins are refused while the owner lives.
- **Glob matching:** anchored, every non-alphanumeric escaped; a leading slash doesn't bypass `isAuthorized`.
- **Scrape-path concurrency:** backlog accounting stays balanced in every interleaving, including send-after-close;
  `isStillAwaited` drops abandoned requests; the in-flight CAS claim and `finally` release are exact;
  `invalidate()` under the path lock only completes without blocking.
- **Payload limits:** unzip capped (zip-bomb guard); chunk size, count, and CRC checked; orphaned transfers swept;
  the agent body read is capped.
- **Retry backoff math:** zero first wait, floored at the loop interval, doubling to the cap; drift only makes a
  retry late; `retainAll` drops records of entries that leave the discovery file; deterministic tests via
  `TestTimeSource`.
- **Agent locking and lifetimes:** `pathMutex` never re-entered; the retry task idles in `awaitCancellation()`;
  `EndpointFailover` transitions, heartbeat deadline, `HttpClientCache` sweeper, strict-UTF-8 `MetricFilter`
  fail-open, and IPv6 endpoint parsing all check out.
- **Dashboard:** kotlinx.html escapes agent text; asset allowlist; WebSocket Origin check, opt-in Host allowlist,
  session cap, frame cap, bounded outgoing buffer; snapshot collection off the CIO threads.
- **Packaging and workflows:** digest-pinned, non-root images with correct `EXPOSE`; all actions SHA-pinned with
  least-privilege permissions; no `pull_request_target`; `docs.yml` uses `uv run --locked`; secrets and scratch
  files are gitignored and absent from history.
- **Docs accuracy:** every CLI flag in `cli-args.md` matches the `@Parameter` names; every documented env var
  exists in `EnvVars`; all 25 documented metric names exist; `4.0.1` is consistent across the release checklist
  files; `packages.md` matches the public types.
- **Dependencies:** grpc 1.84.0, netty 4.2.16, protobuf-java 3.25.9, Ktor 3.6.0, Jetty 12.1.13, and logback 1.6.3
  are current.
