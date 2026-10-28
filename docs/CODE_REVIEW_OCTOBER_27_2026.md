# Prometheus-Proxy Code Review — October 2026 Findings

**Status:** 47 issues — 17 fixed, 30 open (16 medium, 31 low, no high).

**Date:** 2026-10-27

**Scope:** `4.3.1-release` at `778f2df`, two commits ahead of `master` (the 4.3.1 version bump and a website lock
update). That covers ~12.8k lines of main source, the ~36k-line test suite, the build, CI workflows, Docker and Helm
packaging, and documentation. Source paths below are relative to `src/main/kotlin/io/prometheus/` and test paths to
`src/test/kotlin/io/prometheus/`; all other paths are from the repo root.

**Since the review:** on 2026-10-28 the branch was renamed `4.4.0-release` and retargeted to 4.4.0, because the
fixes below change defaults, startup validation, and metrics, which makes this a minor release. 4.3.1 won't be
published. Where a finding names the release being prepared, it says 4.4.0. The version literals stay at 4.3.0 until
the release PR bumps them, so every step's PR can target `master` and get CI.

**Process:** five parallel reviews:

- proxy-side production code;
- agent-side and shared code;
- security;
- build, CI, and packaging;
- tests plus documentation accuracy.

Each one read the June, July, September, and late-September 2026 reviews (`docs/archive/`) and the 58 commits since the
last one (`0385029..778f2df`), and diffed the #320/#321 refactors line by line. A fixed item comes back only when its
fix turned out to be incomplete. Overlapping findings were merged.

The key claim of every medium finding was re-checked against the cited lines. Claims that depend on a library were
checked against that library's source (Ktor 3.6.0, common-utils 5.1.2, Prometheus Java client 1.10.0, Kover 0.9.11)
and against the published 4.3.0 POM. Each finding carries a confidence level:

- **confirmed:** traced in this repo's code, or in the source of the library it calls.
- **plausible:** the gap in this repo is confirmed, but the impact depends on behavior read from another project's
  source rather than exercised.

**Headline:** there are no high-severity findings, and the #320/#321 refactors preserve behavior. The medium
findings cluster in five places:

- **Limits that don't limit.** The agent's response-size cap runs after Ktor has already buffered the whole body
  (#8). Path authorization runs a backtracking regex before the length cap (#22). The names an agent reports at
  registration have no cap at all (#23).
- **Service discovery can't work as documented.** The targets it emits are URLs, which Prometheus refuses (#1), and
  one agent's labels can invalidate the whole discovery document (#24).
- **Embedding.** The Netty CVE pin never reaches the published POM (#25). With metrics on, a second agent in the
  same JVM never connects (#9). The README's embedded example exits the host JVM on a config error (#41).
- **Operability.** Readiness fails at 25 in-flight scrapes (#2). Discovered paths vanish when the agent reconnects
  while the discovery file is bad (#10). Failed scrapes aren't counted (#11).
- **Release gates.** `release-check` can pass a bad release (#29, #30), and the docs site is never built before
  merge (#31).

Six earlier fixes turn out to be incomplete:

| Earlier finding                         | Follow-up here |
|-----------------------------------------|----------------|
| September #1                            | #21            |
| late-September #4                       | #12            |
| late-September #9                       | #13            |
| late-September #15                      | #4             |
| the June agent body-buffering fix       | #8             |
| the `kotlin("test")` part of September #34 | #35         |

**First, on `4.4.0-release`:** four items should be fixed before the rest (steps 1–2):

- #43, #32, and #33 are in this branch's own changes.
- #25 means the CVE fix that the 4.4.0 changelog announces doesn't reach embedders.

---

## 📋 Findings index

✅ = fixed/addressed · ⬜ = open

| #  | Finding                                                                                 | Area     | Severity | Status |
|----|-----------------------------------------------------------------------------------------|----------|----------|--------|
| 1  | Service-discovery targets are URLs, which Prometheus refuses; SD as documented is empty | Proxy    | medium   | ⬜      |
| 2  | Health thresholds (25) far below the caps; `/healthcheck` readiness flaps under load    | Proxy    | medium   | ⬜      |
| 3  | Chunked-transfer sweep skipped on cancel; reclaim trusts the agent's `headerAgentId`    | Proxy    | low      | ✅      |
| 4  | Unauthenticated connections still log INFO on removal and count as evictions           | Proxy    | low      | ⬜      |
| 5  | Each `Proxy` leaks a JVM shutdown hook that keeps it reachable                          | Proxy    | low      | ✅      |
| 6  | A path equal to the SD path, blitz path, or `favicon.ico` registers but can't be scraped | Proxy   | low      | ⬜      |
| 7  | Dead or test-only proxy code, including an unreachable `no_agents` branch               | Proxy    | low      | ⬜      |
| 8  | `maxContentLengthMBytes` doesn't bound memory; Ktor buffers the whole body first        | Agent    | medium   | ✅      |
| 9  | A second agent in one JVM with metrics on never connects (duplicate registration)       | Agent    | medium   | ✅      |
| 10 | Discovered paths vanish on any reconnect while the discovery file is unreadable         | Agent    | medium   | ⬜      |
| 11 | `agent_scrape_request_count_total` misses failed fetches and `invalid_path`             | Agent    | medium   | ✅      |
| 12 | `pathConfigs`: `path = "/"` fails every connect; duplicates silently keep the last URL  | Agent    | low      | ⬜      |
| 13 | Agent latency histogram stops at 10s, below its own 15s scrape timeout                  | Agent    | low      | ✅      |
| 14 | `X-Prometheus-Scrape-Timeout-Seconds` isn't forwarded to the target                     | Agent    | low      | ✅      |
| 15 | Basic credentials from the target URL are sent only after a 401                         | Agent    | low      | ✅      |
| 16 | An embedded agent's `logLevel` changes the host JVM's root logger                       | Agent    | low      | ✅      |
| 17 | A routine proxy outage logs an ERROR stack trace on every reconnect                     | Agent    | low      | ⬜      |
| 18 | A bad `agent.http.trustStorePath` isn't caught at startup and fails every scrape        | Agent    | low      | ⬜      |
| 19 | `MetricFilter` misjudges quoted UTF-8 metric names                                      | Agent    | low      | ⬜      |
| 20 | Agent dead code, stale KDoc, and a `shutDown()` that isn't failure-isolated             | Agent    | low      | ⬜      |
| 21 | Scrape-result ownership checked on one lookup, applied on another (September #1 gap)    | Security | medium   | ✅      |
| 22 | Path authorization runs a backtracking regex before the length cap applies             | Security | medium   | ⬜      |
| 23 | Agent-reported names have no size limit and are repeated per path everywhere            | Security | medium   | ⬜      |
| 24 | SD labels aren't type- or name-checked; one agent can invalidate the whole SD document  | Security | medium   | ⬜      |
| 25 | The Netty CVE override isn't in the published POM; embedders get vulnerable Netty       | Security | medium   | ✅      |
| 26 | A connection that skips `connectAgent` works as an agent without being announced        | Security | low      | ⬜      |
| 27 | Helm proxy chart takes `proxy.auth` tokens only via a ConfigMap; example schema wrong   | Security | low      | ⬜      |
| 28 | Security docs don't say the scrape port is unauthenticated, or what it forwards         | Security | low      | ⬜      |
| 29 | `release-check` accepts any all-green check set without requiring the CI jobs           | CI/build | medium   | ✅      |
| 30 | Version-bump check passes partial bumps and skips two of step 2's files                 | CI/build | medium   | ✅      |
| 31 | The docs site is never built on a pull request                                          | CI/build | medium   | ⬜      |
| 32 | This branch would pass `release-check` with no release date in either changelog         | CI/build | low      | ✅      |
| 33 | The `--continue` comments are wrong: a failed test means no coverage report             | CI/build | low      | ✅      |
| 34 | The site and Helm charts point at a version before it's published                       | CI/build | low      | ⬜      |
| 35 | The testing plugin re-adds `kotlin-test` and a second copy of the test dependencies     | CI/build | low      | ⬜      |
| 36 | Hand lock upgrades skip the 7-day cooldown Dependabot follows                           | CI/build | low      | ⬜      |
| 37 | The TLA+ tools jar isn't versioned; local runs can use different tools than CI          | CI/build | low      | ⬜      |
| 38 | CI hygiene: `build` job has no timeout; tool images pinned by tag only                  | CI/build | low      | ⬜      |
| 39 | The image's UID is implicit, but the charts hard-code it                                | CI/build | low      | ⬜      |
| 40 | The fat JARs keep only one dependency's `NOTICE`                                        | CI/build | low      | ⬜      |
| 41 | Every embedded-agent example passes `exitOnMissingConfig = true` and exits the host     | Docs     | medium   | ✅      |
| 42 | Website tuning table names a nonexistent key; Troubleshooting a nonexistent metric      | Docs     | medium   | ⬜      |
| 43 | 4.4.0 changelogs miss the common-utils and gradle-plugins bumps                         | Docs     | low      | ✅      |
| 44 | `docs/TESTING.md` omits three specs and misstates `all-tests`                           | Docs     | low      | ⬜      |
| 45 | Tests whose assertions can't fail, or that test the JDK                                 | Tests    | low      | ⬜      |
| 46 | Failed-startup tests free ephemeral ports and then bind them again                      | Tests    | low      | ⬜      |
| 47 | The #314 JVM metric groups are never checked on a real `/metrics`                       | Tests    | low      | ⬜      |

---

## 🗺️ Plan: order of addressing

Each step is sized to be one PR, and the issues in a step are meant to be fixed together. The order is:

1. finish the release branch's own changes;
2. close the gates a release passes through;
3. fix the bugs that take down a scrape path or let one tenant affect another;
4. fix what operators see (discovery, readiness, metrics);
5. clean up, with deleting dead code last.

The **Touches** column shows which steps edit the same files and so need to land one after another.

✅ = done · ⬜ = open

| Step | Status | Issues          | PR theme                                                                    | Touches                                                                                           | Why here                                                                                                                                                                         |
|------|--------|-----------------|-----------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | ✅      | #43, #32, #33   | Finish the 4.4.0 branch: log the bumps, require dates, fix the comments     | `CHANGELOG.md`, `RELEASE_NOTES.md`, catalog and Dependabot comments, `Makefile`, `ci.yml`         | All three are in this unmerged branch's own changes. Commit on `4.4.0-release` before it merges.                                                                                 |
| 2    | ✅      | #25             | Publish the Netty pin in the POM                                            | `build.gradle.kts`                                                                                | The release's headline change is this CVE fix, and today it reaches only the images. Land it on this branch first.                                                               |
| 3    | ✅      | #29, #30        | `release-check` requires the named CI checks and complete bumps             | `Makefile`, `docs/RELEASE.md`, release skill                                                      | Protects every later release. Makefile-only, so it can run in parallel with any code step.                                                                                       |
| 4    | ✅      | #8, #14, #15    | Stream the agent's fetch with a real size bound; forward the timeout; preemptive Basic | `agent/AgentHttpService.kt`                                                            | One bad target can take the agent's heap, and every path with it. The other two change the same request setup.                                                                   |
| 5    | ✅      | #21, #3         | Check and complete scrape results on one wrapper; sweep transfers on cancel | `ProxyServiceImpl.kt`, `ScrapeRequestManager.kt`, `AgentContextManager.kt`, `ChunkedContext.kt`, `specs/tla/ProxyRegistry.tla` | Both concern the ownership and lifetime of in-flight results. The change touches `ScrapeRequestManager`, so update the TLA+ action and add a Lincheck spec in the same PR.        |
| 6    | ⬜      | #22, #23, #26   | Bound and authorize agent input before using it                             | `ProxyServiceImpl.kt` (`registerAgent`/`registerPath`), `AgentAuthManager.kt`, `ProxyOptions.kt`, `config.conf` | The three tenant-against-tenant availability issues. The new `proxy.internal` limits need config, validation, and docs. Do it after step 5, which edits the same file.            |
| 7    | ⬜      | #1, #24, #6     | Service discovery that Prometheus accepts                                   | `Proxy.kt` (`buildServiceDiscoveryJson`), `ProxyOptions.kt`, `ProxyPathManager.addPath`, SD docs, a new container spec | SD as documented yields no targets today. One container spec using `http_sd_configs` guards all three. Do it after step 6, since both edit `addPath`'s validation.               |
| 8    | ⬜      | #2              | Readiness that doesn't flap under load                                      | `config/config.conf`, `ProxyOptions.kt`, `Proxy.kt` health checks, `charts/prometheus-proxy`, k8s docs | A default and a chart change, so it needs a "Before you upgrade" note.                                                                                                       |
| 9    | ✅      | #9, #16, #5, #41 | Safe to embed: register metrics once, leave the root logger and shutdown hooks alone, fix the examples | `AgentMetrics.kt`, `ProxyMetrics.kt`, `Agent.kt`, `BaseOptions.kt`, `Utils.kt`, `ProxyGrpcService.kt`, README, website, KDoc | All four are about running inside another JVM, so one embedded-host spec (restart, two agents, log level) covers them. Do it after step 4 and before step 10, which shares `AgentMetrics.kt`. |
| 10   | ✅      | #11, #13        | Agent metrics that count every scrape                                       | `AgentHttpService.kt`, `AgentMetrics.kt`, metrics docs, Grafana JSON                              | Do it after steps 4 and 9, which share these files. The dashboards and docs change with the counter.                                                                             |
| 11   | ⬜      | #10, #12, #18   | Agent config and discovery robustness                                       | `PathDiscoveryService.kt`, `AgentPathManager.kt`, `PathDiscoverySource.kt`, `AgentOptions.kt`, trust-manager init | Startup validation, plus a last-known-good list that outlives the connection. Independent of the proxy steps.                                                                    |
| 12   | ⬜      | #4, #17         | Quiet logs for unauthenticated connections and routine outages              | `AgentContextManager.kt`, `AgentContextCleanupService.kt`, `AgentGrpcService.kt`, `Utils.kt`      | Changes only log levels and one counter, each covered by a `ListAppender` test. Do it after step 5, which shares `AgentContextManager.kt`.                                        |
| 13   | ⬜      | #19             | `MetricFilter` handles quoted UTF-8 names                                   | `agent/filter/MetricFilter.kt`                                                                    | Self-contained, so it can go any time.                                                                                                                                           |
| 14   | ⬜      | #31, #36, #34   | Docs site: build on PRs, cool down hand upgrades, deploy after publishing   | `docs.yml`, `ci.yml`, `Makefile`                                                                  | Keeps a broken site build off `master`, and keeps the site from advertising a version that isn't published yet. Workflow-only, so it can run in parallel with code steps.        |
| 15   | ⬜      | #27, #39, #40   | Helm and image packaging                                                    | `charts/*`, `etc/docker/*.Dockerfile`, `build.gradle.kts` shadow config                           | A Secret-backed auth config, an explicit UID, and NOTICE merging. Needs a `helm-smoke` run and the container tests.                                                              |
| 16   | ⬜      | #42, #44, #28   | Documentation drift                                                         | website pages, `llms.txt`, `docs/TESTING.md`, `SECURITY.md`                                       | No behavior change. Each item can ride along with any earlier PR that touches the same page.                                                                                     |
| 17   | ⬜      | #45, #46, #47   | Tests that can fail, and that don't race for ports                          | the cited specs, `TestPorts.kt`                                                                   | Removes false confidence. #45's `TESTING.md` line goes with step 16 if that lands first.                                                                                         |
| 18   | ⬜      | #35, #37, #38   | Build tidy-ups                                                              | `build.gradle.kts`, `Makefile`, workflows                                                         | Hygiene. After #35, compare the `testRuntimeClasspath` against today's.                                                                                                          |
| 19   | ⬜      | #7, #20         | Remove dead code                                                            | proxy and agent sources and their tests                                                           | Last, so the deletions don't collide with the behavior fixes, and the tests move onto the entry points those fixes settle.                                                       |

**Parallel tracks:** these sets of steps touch disjoint files, so they can be worked on at the same time.

- **Release and CI:** 1 → 2 → 3, then 14 and 18.
- **Proxy:** 5 → 6 → 7 → 8, then 12.
- **Agent:** 4 → 9 → 10, with 11 and 13 independent.
- **Packaging, docs, and tests:** 15, 16, and 17, at any time.

Step 19 waits for the code tracks.

**Where the PRs go:** every step's PR targets `master`, since `ci.yml` and the other pull-request workflows run only
for `master`. Each PR adds its entries under `## [4.4.0] - Unreleased` in `CHANGELOG.md` and `## 4.4.0` in
`RELEASE_NOTES.md`. The version literals stay at 4.3.0 until the last PR, the release PR, bumps them and dates both
changelogs just before `make release`. That keeps the site and the charts from pointing at an unpublished version for
longer than the release takes (#34).

**Release notes:** steps 4–13 change behavior and are why this release is 4.4.0 rather than a patch. Steps 2, 7 (the
SD target format and its default), and 8 (the health thresholds) each need a "Before you upgrade" entry.

---

## 📡 Proxy

### 1. [ ] Service-discovery targets are URLs, which Prometheus refuses, so SD as documented yields no targets

**Severity:** medium · **Confidence:** plausible. The repo side is confirmed; Prometheus's `CheckTargetAddress` was
read in its source, not exercised.

**Where:**

- `Proxy.kt:534-536` copies `options.sdTargetPrefix` verbatim into `targets`.
- `proxy/ProxyOptions.kt:285-288` only checks that the value is non-empty.
- `config/config.conf:24` defaults it to `http://localhost:8080/`.
- The docs give it as a URL in `website/prometheus-proxy/docs/service-discovery.md:76-80`, `docs/cli-args.md:20`,
  `README.md:438`, `src/test/kotlin/website/ServiceDiscoveryExamples.txt:6,14,56`, and
  `src/test/kotlin/website/DockerExamples.txt:73`.

**Problem:** an HTTP-SD target becomes `__address__`, and Prometheus's `PopulateLabels` rejects any address that
contains `/`, with the error `"… is not a valid hostname"`. With the default or any documented value, Prometheus
drops every discovered target. The only way around it is a relabel rule that rewrites `__address__`, and no doc
mentions one.

The tests don't catch it:

- The unit tests pass only because `proxy/ProxyTest.kt:88,108` use a bare `host:port`.
- The container suite uses static configs only, so nothing runs SD against a real Prometheus.

**Fix:**

- Parse `sdTargetPrefix` once at startup:
  - emit `host:port` as the target;
  - emit the scheme as `__scheme__` (agents are already barred from `__*` labels);
  - prepend any path prefix to `__metrics_path__`;
  - reject a value that doesn't parse.
- Change the default to `localhost:8080`, and fix the docs and examples.

**Tests:**

- `buildServiceDiscoveryJson` with `http://proxy.example.com:8080/` emits the target `proxy.example.com:8080`.
- A container spec scrapes through `http_sd_configs` and asserts `up == 1`.

### 2. [ ] Default health thresholds (25) sit far below the caps, so `/healthcheck` readiness flaps under load

**Severity:** medium · **Confidence:** confirmed. The threshold logic and the probe wiring are traced; whether it
trips depends on load.

**Where:**

- `Proxy.kt:359-392`: the `scrape_response_map_check`, `chunking_map_check`, and `agent_scrape_request_backlog`
  health checks.
- `config/config.conf:97-99,104`: each threshold is 25. The caps are `maxInFlightScrapeRequests = 1000` and a
  per-agent queue of 50.
- `charts/prometheus-proxy/templates/deployment.yaml:120-124` and `src/test/kotlin/website/KubernetesExamples.txt:34-35`
  point the readiness probe at `GET /healthcheck`.

**Problem:** in-flight scrapes are roughly the arrival rate times the latency. Two loads that reach 25:

- about 500 targets at a 30s interval with 1.5s latency;
- about 75 targets behind 10s exporters.

At that point `/healthcheck` reports unhealthy, although the proxy is well within its own caps. Then:

1. Kubernetes pulls the pod from the Service.
2. In-flight scrapes drop to zero.
3. The pod turns ready again, and the cycle repeats.

The backlog check is per agent, so one slow agent with 25 queued requests makes the whole proxy unready for every
target.

**Fix:** any of these would work.

- Derive the map thresholds from the caps (for example, 80% of `maxInFlightScrapeRequests`) and validate that each
  threshold is no higher than its cap.
- Leave these pressure signals out of the readiness aggregate; they are already exported as gauges.
- At minimum, point the chart's and the docs' readiness probe at `/ping`, and document `/healthcheck` as a pressure
  signal.

**Tests:**

- With the default config, 100 wrappers in the scrape map keep `scrape_response_map_check` healthy.
- An options test rejects a threshold above its cap.

### 3. [x] Chunked-transfer orphan sweep is skipped on cancellation, and reclaim matches the agent-supplied `headerAgentId`

**Severity:** low · **Confidence:** confirmed that the sweep is skipped. The leak itself needs a race or a mismatched
header.

**Where:**

- `proxy/ProxyServiceImpl.kt:433-439`: `reader.abandonUnfinished()` runs after `runCatchingCancellable`, which rethrows
  `CancellationException`.
- `proxy/ChunkedContext.kt:46`: `agentId = header.headerAgentId`.
- `proxy/AgentContextManager.kt:126-133`: `removeChunkedContextsForAgent`.

**Problem:** when an agent disconnects mid-transfer, grpc-kotlin cancels the RPC's job. The `CancellationException`
escapes before `abandonUnfinished()`, so the end-of-stream sweep never runs in the most common way a stream ends. The
code comments, June #6, and the deferred July #17 ("freed at … the end-of-stream orphan sweep") all assume it does
run.

The only fallback is `removeChunkedContextsForAgent(connectionAgentId)`, which matches on the `headerAgentId` the
agent sent. That leaves two ways to leak a buffer of up to `maxZippedContentSizeMBytes` (5 MB) per scrape:

- A buggy agent, or any peer when auth is off, sends a different `headerAgentId`. The buffer stays forever.
- An honest agent races the reclaim: the RPC can be inside `readHeader` while `transportTerminated` runs it, so the
  context is put after the scan and never swept.

Leaked contexts eventually make `chunking_map_check` report unhealthy (see #2).

**Fix:**

- Move `abandonUnfinished()` into a `finally`. It doesn't suspend, so it's safe to run while cancelled.
- Record the owner at header time from `scrapeRequestManager.ownerAgentId(scrapeId)` or from the connection, not from
  the header.

**Tests:**

- A flow that emits a HEADER and then throws `CancellationException` leaves `chunkedContextMapView` empty, and the
  scrape fails with `AGENT_DISCONNECTED`.
- A context with a mismatched `headerAgentId` is still reclaimed by `removeAgentContext(owner)`.

**Resolution:** `abandonUnfinished()` runs in a `finally` around `runCatchingCancellable`. `ChunkedContext.owner`,
the agent from the tracked scrape, replaces the header's `headerAgentId`, and `removeChunkedContextsForAgent` matches
on it. A header followed by a `CancellationException` now leaves `chunkedContextMapView` empty, and a context with a
mismatched `headerAgentId` is reclaimed by `removeAgentContext(owner)`. Both tests failed against the old code.

### 4. [ ] Late-September #15 is incomplete: unauthenticated connections still log at INFO when removed, and count as evictions

**Severity:** low · **Confidence:** confirmed

**Where:**

- `proxy/AgentContextManager.kt:150-161`: `removeFromContextManager` logs "Removed …" at INFO for every context,
  announced or not. `proxy/ProxyServerTransportFilter.kt:42-51` reaches it on every `transportTerminated`.
- `proxy/AgentContextCleanupService.kt:58-63`: logs at INFO and increments `proxy_eviction_count_total` for pending
  contexts too.

**Problem:** #15's resolution says a pending context is logged only at DEBUG. In practice, every HTTP/2 connection
that never authenticates writes an INFO line when it closes. Examples are a client with a bad token, a port scanner,
or a `grpc_health_probe` against the agent port. If such a connection idles past `maxAgentInactivitySecs`, it writes
a second INFO line and counts as an agent eviction.

**Fix:** log at INFO and count the eviction only when `agentContext.announced`; log at DEBUG otherwise.

**Tests:** with a logback `ListAppender`:

- removing an unannounced context emits no INFO line;
- evicting one leaves `agentEvictionCount` unchanged.

### 5. [x] Each `Proxy` adds a JVM shutdown hook for its gRPC server that is never removed

**Severity:** low · **Confidence:** confirmed. common-utils' `shutdownWithJvm` calls `Runtime.addShutdownHook`.

**Where:** `proxy/ProxyGrpcService.kt:77` calls `grpcServer.shutdownWithJvm(SHUTDOWN_GRACE)` from the constructor.

**Problem:** the hook captures the `Server`, which holds `ProxyServiceImpl` and, through it, the whole `Proxy`.
Neither `shutDown()` nor the #313 failed-start rollback removes the hook. So every `Proxy` built in a JVM stays
reachable until the JVM exits, which affects embedded hosts and the test suite.

The hook is also redundant. `GenericService`'s own hook already runs `Proxy.shutDown()`, which calls
`grpcService.stopSync()`. At exit, two hooks race to stop the same server.

**Fix:** drop `shutdownWithJvm`, or register the hook in `startUp()` and remove it in `shutDown()`.

**Test:** after a start and stop, GC clears a `WeakReference` to the `Proxy`.

**Resolution:** `shutdownWithJvm` is dropped from `ProxyGrpcService`, since the proxy's own hook, which
`GenericService` adds in `startUp()` and removes in `shutDown()`, already stops the server. A `ProxyTest` case checks
that a `WeakReference` to a started and stopped proxy clears after GC. It didn't before the fix.

### 6. [ ] A path equal to the SD path, the blitz path, or `favicon.ico` registers but can never be scraped

**Severity:** low · **Confidence:** plausible. Ktor's route precedence (a constant segment beats `/*`) comes from its
routing semantics, not from a test.

**Where:**

- `proxy/ProxyHttpRoutes.kt:110-117`: `get(sdPath)`.
- `proxy/ProxyHttpRoutes.kt:139-140`: favicon and blitz are checked before the path lookup.
- `proxy/ProxyPathManager.kt:118-143`: `addPath` has no reserved-name check.

**Problem:**

- With SD on, an agent can register the path `discovery` (the default `sdPath`). It is advertised in SD, and every
  scrape of it returns the SD JSON, which Prometheus fails to parse.
- `favicon.ico` always returns 404 `invalid_path`.
- The blitz path always returns `42`.

**Fix:** in `addPath`, reject these with `INVALID_PATH`:

- `favicon.ico`;
- the `sdPath`, when SD is on;
- the blitz path, when blitz is on.

**Test:** with SD enabled, `addPath("discovery")` is rejected with `INVALID_PATH`.

### 7. [ ] Dead or test-only proxy code, including an unreachable `no_agents` branch

**Severity:** low · **Confidence:** confirmed. Grep finds only the declarations in main source.

**Where and what:**

- `proxy/ProxyHttpService.kt:60,89-90`: the `tracing` lazy is never used. With Zipkin on, shutdown builds a Brave
  `Tracing` only to close it.
- `proxy/ScrapeRequestManager.kt:61-68`: `addToScrapeRequestMap` is an uncapped add that only tests call. Those tests
  exercise an admission path production never takes; production uses `tryAddToScrapeRequestMap`.
- These are used only by tests:
  - `AgentContextManager.putAgentContext` (`:100`)
  - `ProxyPathManager.allPaths` (`:107`)
  - `ProxyPathManager.pathCountFor` (`:97`)
  - `AgentContext.desc` (`:126`)
  - `DashboardLayout.of` (`proxy/dashboard/ProxyDashboardHtml.kt:64`)
- `proxy/ProxyHttpRoutes.kt:171-179`: the `no_agents` branch can't be reached.
  - `AgentContextInfo.isNotValid()` is `agentContexts.all { … }`, which is true for an empty list, so an empty
    agent list is reported as `invalid_agent_context` first.
  - `docs/metrics-and-grafana.md:99`, `website/prometheus-proxy/docs/monitoring.md:103`, and
    `website/prometheus-proxy/docs/troubleshooting.md:105` still describe `no_agents` as an outcome that happens.

**Fix:**

- Remove the dead code, and move its tests onto the production entry points (`tryAddToScrapeRequestMap`,
  `addAgentContext`, `allPathContextInfos`).
- For `no_agents`, either drop it from the outcome docs (the case is reported as `invalid_agent_context`), or check
  for an empty list first so the label means what the docs say.

---

## 🛰️ Agent

### 8. [x] `maxContentLengthMBytes` doesn't bound memory: Ktor buffers the whole body before either size check runs

**Severity:** medium · **Confidence:** confirmed. Traced through the common-utils 5.1.2 and Ktor 3.6.0 source, not
exercised.

**Where:**

- `agent/AgentHttpService.kt:182-187`: `entry.client.get(url, setUp) { response -> … }`.
- `agent/AgentHttpService.kt:216-230`: the Content-Length guard.
- `agent/AgentHttpService.kt:234-253`: `readBuffer(maxContentLength + 1)`.
- common-utils `KtorDsl.kt`: `HttpClient.get` is a plain `request(url) { … }`.
- In Ktor, `HttpStatement.fetchResponse()` calls `call.save()`, which reaches `SavedCall.kt:37`,
  `rawContent.readBuffer().readByteArray()`, with no limit. Only `fetchStreamingResponse()` skips the save.

**Problem:** the non-streaming `request()` reads the entire body into a `ByteArray` before the response block runs.
Both guards therefore measure a body that is already on the heap.

Scenario: a target streams a large or endless body. It might be a misrouted URL, a profiling or log-tail endpoint, or
a hostile target.

- The agent buffers it until the scrape timeout, 15s by default. On a LAN that can be gigabytes.
- The resulting `OutOfMemoryError` is deliberately rethrown (`agent/AgentHttpService.kt:145-146`,
  `Agent.kt:480-483`), so the agent stops serving every path.
- An embedded agent takes its host JVM down with it.
- Even a target that sends an honest `Content-Length` far over the limit is downloaded in full before the 413.

This finding reopens the June 2026 fix "Unbounded response-body buffering on the agent", which never bounded memory.
The late-September appendix line "the agent body read is capped" is wrong. The 413 tests
(`agent/AgentHttpServiceTest.kt:1373,1460`) check only the status code.

**Fix:** stream the response, so that the Content-Length guard runs before any body is read and
`readBuffer(max + 1)` becomes the real bound. Either:

- `entry.client.prepareGet(url, prepareRequestHeaders(req)).execute { buildScrapeResults(it, …) }`, or
- a streaming `get` in common-utils.

**Tests:**

- A target that streams 64 KB chunks forever, with `maxContentLengthMBytes = 1`, returns 413 within about a second.
  Today it returns 408 at the timeout.
- A response with `Content-Length: 2000000000` returns 413 without its body being read.

**Resolution:** `fetchContent` now uses `prepareGet(url, …).execute { buildScrapeResults(it, …) }`, a streaming
request, so the Content-Length guard runs from the headers and `readBuffer(max + 1)` is the real bound. A response
rejected by its Content-Length is also cancelled: a streaming request's cleanup completes the call and then waits for
the body, so without the cancel a target that stalled after its headers still held the scrape until the timeout.
Past-the-limit bodies need no cancel, since the cleanup stops the reader for chunked and close-delimited bodies
alike; both were tried. Both tests the finding asks for are in `agent/AgentHttpServiceTest.kt` (an endless chunked
body, and a 64 MB Content-Length that stalls after 1 KB). Before the fix, each returned 408 at the 5s timeout; after
it, 413 in about 0.16s and at once.

### 9. [x] A second agent in one JVM with metrics enabled never connects

**Severity:** medium · **Confidence:** confirmed. common-utils' `PrometheusDsl` registers in
`PrometheusRegistry.defaultRegistry`, and the 1.10.0 registry rejects a duplicate name.

**Where:**

- `agent/AgentMetrics.kt:30-95`: every collector registers in the default registry, and nothing unregisters them.
  `Agent.shutDown` (`Agent.kt:697-701`) doesn't either.
- `Agent.kt:208`: `metrics by lazy`.
- `agent/AgentGrpcService.kt:331,339`: the first uses of `metrics`, inside `connectAgent`.
- `Agent.kt:485-488`: the failure path logs and retries.
- The suite hides the problem by clearing the registry before each agent (`harness/support/HarnessSetup.kt:43`,
  `agent/AgentTest.kt:203,241`).

**Problem:** take an embedded host with `agent.metrics.enabled` that either restarts its agent (`shutdown()`, then
`startAsyncAgent` again) or runs two agents. Then:

1. The new agent's first connect builds `AgentMetrics`.
2. `register` throws `IllegalArgumentException` (duplicate metric name). This happens after the `connectAgent` RPC
   has already succeeded.
3. The failure counter touches the lazy again, its initializer re-runs and throws again, and this time the exception
   escapes `connectAgent`.
4. `handleConnectionFailure` logs a WARN and reconnects every `reconnectPauseSecs`, forever, without ever registering
   a path.

`Proxy.kt:217` builds `ProxyMetrics` the same way, so a second proxy in one JVM would hit the same problem.

**Fix:**

- Create the label-keyed collectors once per process; every series already carries `launch_id`.
- Register the per-launch gauges (start time, and the backlog and cache-size samplers) per instance, and unregister
  them in `shutDown()` and `releaseAfterFailedStartUp()`.
- Touch `metrics` in `startUp()`, so a registration failure fails startup visibly instead of looping.

**Test:** start and stop an agent with metrics on, then start a second one without clearing the registry.
`awaitInitialConnection` succeeds, and the second agent's `connect_count{type="success"}` is 1.

**Resolution:** a new `common/SharedCollectors` holds one set of collectors for every agent in the JVM. It
registers them when the first agent starts, registers them again if the registry was cleared (it checks a sentinel
metric), unregisters and clears them when the last one stops, and registers all or nothing. The fix departs from the
suggested one for the samplers: the registry rejects a second `SamplerGaugeCollector` with the same name and labels,
so registering them per instance would have fixed restarts but not two agents at once. `agent_scrape_backlog_size` and
`agent_client_cache_size` are now callback gauges that report one series per running agent. On close, an agent removes
every series with its `launch_id`. `ProxyMetrics` shares its collectors the same way. Its counters carry no
`launch_id`, so they add up across proxies, as do its sampled gauges. A closing proxy removes its start time and the
per-path series it recorded. Both build their metrics in `startUp()`, so a host metric with one of their names fails
startup. `EmbeddedAgentMetricsTest` covers a restart and two agents at once, both without a registry clear, plus the
startup clash. The metrics unit tests now close what they create, instead of clearing the registry.

### 10. [ ] Discovered paths vanish on any reconnect while the discovery file is unreadable

**Severity:** medium · **Confidence:** confirmed

**Where:**

- `agent/discovery/PathDiscoveryService.kt:61-90`: a failed read skips the reconcile, and no previous good list is
  kept.
- `Agent.kt:307` and `agent/AgentPathManager.kt:92-98`: `pathManager.clear()` runs on every connect.
- The docs promise otherwise in `website/prometheus-proxy/docs/kubernetes.md:162` ("a bad edit never tears down
  working targets"), `website/prometheus-proxy/docs/configuration/agent.md:97`, and
  `examples/discovery-targets.conf:51-54`.

**Problem:** the "last-known-good" set is just the live path map, and every connect wipes that map. Scenario:

1. A bad edit makes the file unparseable. The agent keeps its paths, as documented.
2. Later, the agent reconnects for any reason: a proxy restart, a network blip, a heartbeat-forced reconnect, or a
   failover.
3. The first reconcile on the new connection fails again, so no discovered paths register.
4. Every discovered target returns 404 until the file is fixed, while the WARN still says "keeping current paths".

**Fix:** keep the last successfully read list in `PathDiscoveryService`, which outlives connections, and reconcile
against it when a read fails (reconciling is idempotent). Reword the log message.

**Test:** use a source that succeeds once and then throws. After `pathManager.clear()` and `reconcileOnce()`, the
cached paths are registered again.

### 11. [x] `agent_scrape_request_count_total` never counts failed fetches or the documented `invalid_path` type

**Severity:** medium · **Confidence:** confirmed

**Where:**

- `agent/AgentHttpService.kt:111-117`: the invalid-path branch returns without calling `updateScrapeCounter`; the
  counter is incremented only at `:158`.
- `agent/AgentHttpService.kt:148-154`: exception results set no `scrapeCounterMsg`, so it defaults to `""`
  (`common/ScrapeResults.kt:51`).
- `Agent.kt:613-616`: an empty type is ignored.
- Documented in `docs/metrics-and-grafana.md:174-176` and `website/prometheus-proxy/docs/monitoring.md:170,176-178`.
- Charted by the "Unsuccessful Scrapes by Agent" panel (`type!="success"`) in `grafana/prometheus-agents.json:326`.

**Problem:**

- The documented `invalid_path` series is never emitted.
- Timeouts, refused connections, DNS failures, and closed-cache errors aren't counted at all.
- With a target down, the shipped "unsuccessful scrapes" panel stays empty, and the total scrape rate drops as though
  fewer scrapes had been requested.

**Fix:**

- Count in `fetchScrapeUrl`, so both branches are counted.
- Give exception results a type: `timed_out` when `hasTimeoutCause()` is true, otherwise `unsuccessful` or a new
  `failure`.
- Update both metrics pages.

**Test:** in `agent/AgentHttpServiceTest.kt`, with metrics on:

- an invalid path gives `invalid_path` = 1;
- a refused connection is counted;
- a timeout is counted.

**Resolution:** `fetchScrapeUrl` counts both branches, so `invalid_path` is emitted. A fetch with no answer is
counted as `timed_out` when `hasTimeoutCause()` is true and as `failure` otherwise (a refused connection, a DNS
failure). `unsuccessful` keeps its meaning: the target answered with a non-2xx status or an oversized body. Both
metrics pages list the types. The agents dashboard's Unsuccessful Scrapes by Agent panel (`type!="success"`) picks
them up unchanged. The three tests the finding asks for are in `agent/AgentHttpServiceTest.kt`; before the fix, the
invalid path wasn't counted at all, and the refused connection and the timeout were counted with an empty type.

### 12. [ ] `pathConfigs` validation gaps: `path = "/"` fails every connect, and duplicates silently keep the last URL

**Severity:** low · **Confidence:** confirmed

**Where:**

- `agent/AgentPathManager.kt:102-118,546-547`: `usable` checks `isNotBlank()` on the raw path.
- `agent/AgentPathManager.kt:412-417`: the leading slash is stripped only after that check.
- `agent/AgentGrpcService.kt:396`: `require(pathVal.isNotBlank())` throws `IllegalArgumentException`.
- `agent/AgentPathManager.kt:181-197`: only `RequestFailureException` is caught.
- Duplicates: `agent/AgentPathManager.kt:104-122`; compare the filters check at `:138-143`.
- `agent/discovery/PathDiscoverySource.kt:31`: `DiscoveredPath.usable` has the same check.

**Problem:**

- **`"/"`:** it passes `usable`, normalizes to `""`, and the `require` throws out of `registerPaths`. That aborts
  registration of the remaining paths and fails the attempt. The agent fails over and reconnects every 3s with a
  WARN stack trace, and serves nothing. This is exactly the "one typo fails every connect" case that the comment at
  `:102-103` says the filter prevents.
- **Duplicates:** two entries such as `metrics` and `/metrics` both register, and the second URL silently wins,
  locally and on the proxy. Late-September #4 fixed the same problem for `agent.filters` only.

**Fix:**

- Check `path.removePrefix("/").isNotBlank()` in both `PathConfig.usable` and `DiscoveredPath.usable`.
- Reject duplicate normalized `pathConfigs` paths at startup, the way filters already are.

**Tests:**

- `["/", "ok"]` connects and registers `ok`.
- `["metrics", "/metrics"]` fails construction, naming the path.

### 13. [x] The agent latency histogram stops at 10s, below the agent's own 15s scrape timeout

**Severity:** low · **Confidence:** confirmed

**Where:** `agent/AgentMetrics.kt:54-59` and `config/config.conf:138`; compare `proxy/ProxyMetrics.kt:65`.

**Problem:** late-September #9 added the 15, 30, 60, and 90s buckets to the proxy's histogram but not the agent's.
Every agent-side timeout and slow scrape lands in `+Inf`.

**Fix:** use the proxy's bucket list, and update the bucket list in the monitoring docs.

**Test:** an `AgentMetrics` assertion on the bucket bounds.

**Resolution:** both histograms take their buckets from `MetricBuilders.SCRAPE_LATENCY_BUCKETS`, the proxy's list,
which runs to 90s. An `AgentMetricsTest` case asserts the bounds; before the fix, they stopped at 10s. The test that
pinned the old layout now checks only that the histogram is classic-only.

### 14. [x] The agent doesn't forward `X-Prometheus-Scrape-Timeout-Seconds` to the target

**Severity:** low · **Confidence:** plausible. The exporter behavior is from the blackbox and snmp exporters' sources.

**Where:** `agent/AgentHttpService.kt:194-205` (`prepareRequestHeaders`). The proxy carries the header into
`ScrapeRequest.scrapeTimeoutMillis` (`proxy/ScrapeRequestWrapper.kt:58`), and the agent uses it only for its own
timeout.

**Problem:** exporters that size their work by this header, such as blackbox_exporter and snmp_exporter, fall back
to their own budget, which can be up to about 120s in blackbox. Scenario: a probe of a dead host.

- Without the proxy, the exporter returns `probe_success 0` in time.
- Through the proxy, the agent's timeout cuts the probe off first. The scrape returns 408 and records `up = 0`, so
  alerts written on `probe_success` stop firing.

**Fix:** set the header to `scrapeTimeout(request)`, in seconds.

**Test:** an embedded target receives `min(agent timeout, Prometheus timeout)`.

**Resolution:** `prepareRequestHeaders` sets the header to `scrapeTimeout(request)`, in Prometheus's format
(`10`, `2.5`). A test asserts `2.5`, `10`, and `10` for forwarded timeouts of 2.5s, none, and 30s, against the
agent's 10s; before the fix the target received no header.

### 15. [x] Basic credentials from the target URL are sent only after a 401 challenge

**Severity:** low · **Confidence:** plausible. Read in Ktor 3.6.0's `BasicAuthProvider`, not exercised.

**Where:** `agent/AgentHttpService.kt:335-343` (the `Auth { basic { … } }` setup). Ktor's `sendWithoutRequest`
defaults to `false`, and the `Auth` plugin acts only on a 401.

**Problem:**

- Every scrape of a protected target costs two round trips and leaves a 401 in the target's log.
- A target that answers unauthenticated requests with 403, or redirects to a login page, never receives the
  credentials, so the scrape fails.

**Fix:** `sendWithoutRequest { true }`, optionally scoped to the request's host. This is safe now that only
same-origin redirects are followed (late-September #10).

**Test:** a target that requires Basic auth and returns 403 without it answers 200 to a single request.

**Resolution:** the Basic provider uses `sendWithoutRequest { !it.headers.contains(HttpHeaders.Authorization) }`
rather than `{ true }`. Ktor's provider replaces any existing `Authorization` header, so `{ true }` would have
overridden a header Prometheus forwards from its scrape config. That header still takes precedence, and the URL's
credentials still answer a 401 to it. The finding's test (a target that returns 403 without credentials answers 200
to a single request) failed with 403 before the fix. A second test pins the forwarded header's precedence.

### 16. [x] An embedded agent's `logLevel` changes the host JVM's root logger

**Severity:** low · **Confidence:** confirmed

**Where:** `common/BaseOptions.kt:459-471` (`assignLogLevel`) and `common/Utils.kt:118-145`
(`rootLogger.level = level`).

**Problem:** setting `agent.logLevel = "warn"`, or `AGENT_LOG_LEVEL`, in an embedded agent's config silences the host
application's own INFO logs. No doc mentions this.

**Fix:** set the level on the `io.prometheus` logger instead (or skip it when `embedded`), and document the behavior.

**Test:** embedded options with `logLevel = warn` leave the root logger's level unchanged.

**Resolution:** `Utils.setLogLevel` takes a logger name, and `BaseOptions.assignLogLevel` passes `io.prometheus`
when `embedded`, so a standalone agent and the proxy still set the root logger. The embedded-agent page documents
it. `AgentOptionsTest` checks that embedded options with `--log_level warn` leave the root logger at INFO and set
`io.prometheus` to WARN; before the fix, the root logger became WARN.

### 17. [ ] A routine proxy outage logs an ERROR stack trace on every reconnect

**Severity:** low · **Confidence:** confirmed

**Where:**

- `agent/AgentGrpcService.kt:341-343`: `connectAgent` logs with `error(e)`.
- `common/Utils.kt:177` (`logStreamFailure`), called from `Agent.kt:449-452` and `agent/AgentGrpcService.kt:599-603`.

**Problem:**

- With the 3s reconnect pause, a proxy outage writes about 20 ERROR stack traces a minute.
- Each disconnect adds one to three more from the stream tasks.
- A permanently dead primary in a failover list adds one every cycle.

Elsewhere, the project logs a repeating failure once at WARN and then at DEBUG.

**Fix:** follow the `PathDiscoveryService.reconcileOnce` pattern:

- log at WARN, with the stack trace, on the first failure or when the status changes;
- log at DEBUG while the same failure repeats;
- log an `UNAVAILABLE` stream end at WARN.

**Test:** N consecutive connect failures produce one WARN.

### 18. [ ] A bad `agent.http.trustStorePath` or password isn't caught at startup and fails every scrape

**Severity:** low · **Confidence:** confirmed

**Where:**

- `agent/AgentHttpService.kt:103-109`: the trust manager is lazy.
- `agent/AgentHttpService.kt:314`: it is read in `engine {}` for every client, whatever the scheme.
- `agent/AgentOptions.kt:453-461`: only logs the path.

**Problem:**

- The agent connects and registers, then every scrape fails with 503, HTTP targets included.
- The failed lazy initializer re-reads the file on every scrape.
- The cause is logged as "Failed HTTP request: <target> [IOException: keystore password was incorrect]", which points
  at the target, not at the agent's config.

**Fix:** load the trust manager at startup and fail fast, as the other options do.

**Test:** constructing the agent with a missing trust store throws a clear error.

### 19. [ ] `MetricFilter` misjudges quoted UTF-8 metric names

**Severity:** low · **Confidence:** plausible. It needs a target that emits Prometheus 3 quoted names;
`textOnlyAccept` passes Prometheus's `escaping=allow-utf-8` offer through.

**Where:**

- `agent/filter/MetricFilter.kt:263-271`: `parseCommentFamily` keeps the quotes.
- `agent/filter/MetricFilter.kt:224-235`: for `{"a.b",…}`, `sampleNameEnd` returns an empty name.
- `agent/filter/MetricFilter.kt:181`: a line is treated as a comment only when `#` is in column 0.

**Problem:**

- With an allow list, an allowed quoted-name family is silently dropped: its HELP/TYPE family name is `"a.b"`,
  quotes included, and its samples are judged as `""`.
- A deny list can't drop such a family at all.

**Fix:** unquote the comment family name, and read the sample name from inside `{"…"`. Optionally, skip leading
whitespace.

**Test:** filter a payload with quoted names using an allow list, then a deny list.

### 20. [ ] Agent dead code, stale KDoc, and a `shutDown()` that isn't failure-isolated

**Severity:** low · **Confidence:** confirmed

- `agent/AgentGrpcService.kt:234-240`: `shutDownChannel()` has no production caller, only
  `agent/AgentGrpcServiceTest.kt:533`. Its comment says the heartbeat loop uses it, which is no longer true.
- `common/BaseOptions.kt:244-248,605-613`: neither the 3-argument `resolveBooleanOption` nor the legacy 4-argument
  `resolveBoolean` has a production caller.
- `Agent.kt:753-780`: the `startSyncAgent` KDoc says it "blocks until shutdown", but `startSync()` returns once the
  agent is running.
- `Agent.kt:142`: the class KDoc promises "exponential backoff", but reconnects use a fixed-rate limiter.
- `Agent.kt:697-701`: if `grpcService.shutDown()` throws, the client-cache close and `super.shutDown()` (the admin and
  metrics servers) are skipped. `releaseAfterFailedStartUp` already isolates each step.

**Fix:**

- Remove the dead code, or move it to test scope.
- Correct the KDoc.
- Run each `shutDown()` step under `runCatching`, and rethrow the first failure.

---

## 🔒 Security

### 21. [x] September #1 is incomplete: a scrape result's ownership is checked on one lookup and applied on another

**Severity:** medium · **Confidence:** the check-then-act gap is confirmed; how often it can be hit is plausible, since
it depends on timing.

**Threat model:** an authenticated agent with a limited `proxy.auth` identity that wants to put its own data into
another identity's scrape.

**Where:**

- `proxy/ProxyServiceImpl.kt:371-391`: `isScrapeOwnedByConnection` allows any scrapeId that isn't tracked.
- `proxy/ProxyServiceImpl.kt:396-402`: the check, then `toScrapeResults()`, then `assignScrapeResults`.
- `proxy/ScrapeRequestManager.kt:70-77`: `assignScrapeResults` looks the wrapper up again.
- `proxy/ScrapeRequestWrapper.kt:51,114`: scrape IDs come from one sequential, process-wide counter.
- `readHeader` (`proxy/ProxyServiceImpl.kt:471-483`) has the same split between `isOwned` and
  `containsScrapeRequest`/`putChunkedContext`.
- `proxy/AgentContextManager.kt:107-112` still keys chunked transfers by scrapeId alone and stores them with a plain
  `put`.

**Problem:** the ownership check and the completion use two separate map lookups.

1. When ownership is checked, the scrapeId isn't in the map yet, so the check passes.
2. Before the second lookup, the HTTP handler adds that ID for another agent's scrape (`proxy/ProxyHttpRoutes.kt:377`).
3. The foreign result then completes the other agent's scrape. The first result to complete wins.

The IDs are sequential, so an agent can learn the counter from its own scrapes and aim at the next few IDs.

September #1's fix asked for `(agentId, scrapeId)` keys and `putIfAbsent`. Neither landed, and the #321 refactor
kept the logic as it was.

**Fix:**

- Check and complete on the same wrapper. For example,
  `assignScrapeResults(results) { wrapper -> wrapper.agentContext.agentId == connectionAgentId }`, or the identity
  check when the transport filter is off.
- Treat an untracked ID as "drop", not "allow".
- In `readHeader`, fetch the wrapper once, check its owner, and use `putIfAbsent`.
- Update the `ScrapeRequestManager` action in `specs/tla/ProxyRegistry.tla`.

**Tests:**

- A non-owner's result for an ID added between the check and the assign doesn't complete that scrape. Inject the add
  through a seam.
- A `Lincheck`-tagged spec runs `tryAddToScrapeRequestMap` concurrently with non-owner `assignScrapeResults`, and the
  non-owner never completes.

**Resolution:** `assignScrapeResults` and `failScrapeRequest` take an `isOwner` check that runs on the wrapper they
complete, from the same lookup, and an untracked scrapeId is dropped. `failScrapeRequest` needed it too: once the
separate pre-check was gone, the error path in `writeResponsesToProxy` would have let a non-owner fail any scrape.
`readHeader` fetches the wrapper once with `getScrapeRequest`, checks it, records its agent as the transfer's owner,
and opens the transfer with `putIfAbsent`. Chunks and summaries are checked against that recorded owner, not a fresh
lookup, and removals take the context they checked. `ownerAgentId`, which invited the split check, is gone. The chunked
map stays keyed by scrapeId: only the owner can open a transfer, and IDs are never reused. In `ProxyRegistry.tla`, the
old model checked and completed in one atomic step, which is why `AnswerFromOwner` never caught this. A `SameLookup`
constant now models both forms: with it FALSE, TLC finds the violation, and both configs set it TRUE. The race tests
add the victim's scrape just before and just after the single lookup, and failed against the old code. A
`ScrapeResultOwnershipLincheckTest` that replayed the old logic found `Scrape 1257, sent to 2515, was completed by
2516`; it passes against the new API.

### 22. [ ] Path authorization runs a backtracking regex on the full path before the length cap applies

**Severity:** medium · **Confidence:** confirmed. The same regex construction was timed on JDK 25.

**Threat model:** an authenticated agent whose `proxy.auth` identity has a glob with two or more `*` followed by a
literal. It can stall the proxy for every identity.

**Where:**

- `proxy/ProxyServiceImpl.kt:211-219`: `identity.isAuthorized(request.path)` runs before `addPath`. On a denial, the
  full path is logged at WARN and echoed in the reason.
- `proxy/AgentAuthManager.kt:41-42,148-171`: `globToRegex` turns `*` into `.*`, then calls `Regex.matches`.
- `proxy/ProxyPathManager.kt:129,148-166`: `maxPathLength` is checked only inside `addPath`.
- The gRPC server keeps the 4 MiB default message size; no `maxInboundMessageSize` is set.

**Problem:** `registerPath` can carry a path of about 4M characters, and it is matched against the identity's regex
before any length check. Measured on paths that don't match:

| Glob              | Path length                            | Time  |
|-------------------|----------------------------------------|-------|
| `*_*_*_metrics`   | 2,000 characters                       | 4.2 s |
| `*_*_*_metrics`   | 4,000 characters                       | 34 s  |
| `*_*_*_*_metrics` | 512 characters (the default `maxPathLength`) | 9 s |

Each call holds a gRPC executor thread, so a few calls saturate every core. A denied path of any size is also logged
whole at WARN on every call.

**Fix:**

- In `registerPath`, check the length, plus the cheap `pathKey`, blank, and multi-segment checks, before
  `isAuthorized`. Log the truncated form that `sizeRejection` already uses.
- Replace the regex with a linear wildcard matcher (two pointers, backtracking to the last `*`). That is needed even
  at 512 characters once a glob has four stars.

**Tests:**

- An identity with `*_*_*_*_metrics` and a 4,096-character path gets a prompt `INVALID_PATH`, under a Kotest timeout.
- The matcher passes adversarial input under a timeout, alongside the existing glob tests.

### 23. [ ] Agent-reported registration fields have no size limit and are copied per path into logs, SD, the dashboard, and `/debug`

**Severity:** medium · **Confidence:** confirmed. The proxy side is traced.

**Threat model:** an authenticated agent with any identity, or any peer when auth is off. The effect is on
availability for every tenant of a shared proxy.

**Where:**

- `proxy/AgentContext.kt` (`assignProperties`): `agentName`, `hostName`, `launchId`, and `proxyEndpoints` are copied
  unchecked.
- `proxy/ProxyServiceImpl.kt:176-178`: INFO "Connected to $agentContext".
- `proxy/ProxyPathManager.kt:347`: INFO "Added path … for $agentContext" on every registration;
  `AgentContext.toString` includes the name and the host.
- `Proxy.kt:542-543`: service discovery copies `agentName` and `hostName` into every path's labels.
- The dashboard path table, and `ProxyPathManager.toPlainText` (`/debug`).

**Problem:** late-September #14 capped path count, path length, and labels size. These four fields are still bounded
only by gRPC's 4 MiB message limit. Each one is stored once but repeated for every path the agent registers, up to
`maxPathsPerAgent` (20,000). So log volume, and the size of the SD, dashboard, and `/debug` responses, grow with field
size times path count. Against the Helm chart's 512 Mi memory limit, that can exhaust the heap or the log volume.

**Fix:**

- In `registerAgent`, reject or truncate oversized fields, with configurable `proxy.internal` limits: for example,
  256 characters for name, host, and launch ID, and a cap on the length of the endpoint list.
- Consider an explicit, smaller `maxInboundMessageSize` on the agent port that still fits the chunk size.
- Log the agentId, not the whole context, on per-path INFO lines.

**Tests:**

- A `proxy/ProxyServiceImplTest.kt` case where `registerAgent` with an oversized name is rejected or truncated.
- Option-validation tests for the new limits.

### 24. [ ] SD labels aren't checked for type or name, so one agent's labels can invalidate the whole discovery document

**Severity:** medium when SD is on (it is off by default) · **Confidence:** plausible. The proxy side is confirmed;
Prometheus failing the whole response comes from reading its `http_sd` decoding source, not from running it.

**Threat model:** an authenticated agent with a limited identity, or an honest misconfiguration such as a numeric
label value. Every tenant's discovered targets are affected.

**Where:**

- `Proxy.kt:551-556`: `json.jsonObject.forEach { (k, v) -> put(k, v) }` passes objects, arrays, numbers, booleans,
  and any key through.
- `proxy/ProxyPathManager.kt:129`: only the labels' byte size is checked.
- The agent doesn't validate labels either (`agent/AgentPathManager.kt:416`, `agent/discovery/FileDiscoverySource.kt:69`).

**Problem:** Prometheus decodes the HTTP-SD response into target groups whose labels must be a string-to-string map
with valid label names. A single non-string value or invalid name, such as an empty key, fails the entire response.
Prometheus then keeps its last good target list and stops applying changes for every target behind the proxy. After a
Prometheus restart, it has no targets at all. The proxy logs nothing.

**Fix:**

- In `addPath`, require the labels to be a JSON object with string values and valid label-name keys. Reject anything
  else with `INVALID_PATH`, which is non-retryable, so the agent logs it once.
- In `buildServiceDiscoveryJson`, skip non-string values as a second guard.
- Validate on the agent too, so a misconfiguration fails early.

**Tests:**

- `addPath` rejects `{"a":1}`, `{"a":{"b":"c"}}`, and `{"":"x"}`.
- Every label value in `buildServiceDiscoveryJson`'s output is a string primitive.

### 25. [x] The Netty CVE override doesn't reach the published Maven artifact, so embedders get vulnerable Netty

**Severity:** medium · **Confidence:** confirmed against the published 4.3.0 `.pom` and `.module`.

**Threat model:** any host that embeds the agent (`Agent.startAsyncAgent`) and is exposed to the HTTP/2 issues in the
Netty version it resolves.

**Where:** `build.gradle.kts:128-139` (`resolutionStrategy.eachDependency { useVersion(nettyVersion) }`) and
`gradle/libs.versions.toml:36-39`.

**Problem:** `eachDependency` changes only this build's own resolution. The fat JARs and images get 4.2.18.Final, but
nothing about Netty is published: the only `io.netty` entry in the 4.3.0 POM is `netty-tcnative-boringssl-static`.

- A host with `implementation("com.pambrose:prometheus-proxy:4.4.0")`, as this branch builds it, resolves grpc-netty
  1.85.1's Netty 4.2.17.Final, which has CVE-2026-93492, the vulnerability whose fix the 4.4.0 changelog announces.
- 4.3.0 embedders got 4.2.16.Final, which has the critical CVE-2026-75595.
- Trivy scans only the images, so nothing catches this.

**Fix:**

- Publish the pin the way `netty-tcnative` already is: declare the Netty modules on the runtime classpath
  (`netty-codec-http2`, `netty-handler`, `netty-codec-http`, …) as direct `runtimeOnly` dependencies at
  `libs.versions.netty`. Direct dependencies win in Maven too, while Gradle `constraints` reach only Gradle consumers.
- Keep `eachDependency` for completeness.
- Check the result by inspecting the generated POM.
- If the fix can't land before 4.4.0, say on `website/prometheus-proxy/docs/embedded-agent.md` that embedders must
  apply the override themselves.

**Resolution:** the 12 Netty modules on the runtime classpath (all but netty-tcnative, which keeps its own pin) are
declared as `runtimeOnly` dependencies through a new `netty` bundle in `gradle/libs.versions.toml`, at
`libs.versions.netty`. The generated POM and Gradle module now list each one at 4.2.18.Final with runtime scope.
`eachDependency` stays, and now also fails `runtimeClasspath` resolution when a Netty module the bundle doesn't list
turns up, so a grpc update that adds one can't reopen this; with `netty-codec-socks` dropped from the bundle,
`agentJar` failed with that message. Both changelogs have a Security entry.

### 26. [ ] A connection that never calls `connectAgent` works as a full agent without being announced

**Severity:** low · **Confidence:** confirmed

**Threat model:** an authenticated agent, using a custom client, that wants to stay out of the operator's agent views.

**Where:**

- `proxy/ProxyServiceImpl.kt:88`: the only call to `announceAgentContext`.
- `registerAgent`, `registerPath`, `sendHeartBeat`, and `readRequestsFromProxy` (`proxy/ProxyServiceImpl.kt:167-333`)
  all accept a pending context.
- `proxy/AgentContextManager.kt:47,54-55,85`.

**Problem:** #293 and `docs/security-agent-authentication.md:204-206` say a context stays pending "until the
connection's first authenticated call", but only `connectAgent` announces it. A client can skip that call; it can
learn its agentId from the `agent-id` response header on any RPC. It can then register paths and serve scrapes while
missing from:

- the dashboard's agent list;
- `proxy_agent_map_size`;
- the backlog health check;
- the agent connected and disconnected events.

Its paths still appear in the path table and the logs.

**Fix:** either announce on the first authenticated call (in `ProxyServerInterceptor`, once the auth interceptor has
passed), or make `registerAgent`, `registerPath`, and `readRequestsFromProxy` reject an unannounced context. Align the
doc with whichever you choose.

**Test:** `registerPath` on a pending context is rejected (or announces it), and `agentContextSize` reflects that.

### 27. [ ] The Helm proxy chart can take `proxy.auth` tokens only through a ConfigMap, and its example shows the wrong schema

**Severity:** low · **Confidence:** confirmed

**Threat model:** anyone who can read ConfigMaps in the release namespace (often a broader group than those who can
read Secrets), or who can run `helm get values`.

**Where:** `charts/prometheus-proxy/values.yaml:17-27`, `charts/prometheus-proxy/templates/configmap.yaml`, and
`charts/prometheus-proxy/README.md:30`. The agent chart has the same pattern for target credentials in `pathConfigs`
URLs.

**Problem:**

- The legacy shared token comes from a Secret (`agentToken.existingSecret`). But `proxy.auth`, the recommended
  multi-tenant control, can be given only through `config` or `existingConfigMap`, so every identity's token sits in
  plaintext in a ConfigMap.
- The values comment shows `auth { identities: [ … ] }`, which doesn't match the schema `auth = [ { name, token, paths } ]`
  (`config/config.conf:11-17`). A config built from the comment fails at startup, which is safe but misleading.

**Fix:** support a Secret-backed config (an `existingSecret` with a `proxy.conf` key), or document HOCON substitution
(`token = ${TEAM_A_TOKEN}`, with `envFrom` a Secret). Correct the comment.

**Test:** a chart CI values file renders a Secret-backed config, and the `helm template` output has no token in any
ConfigMap.

### 28. [ ] The security docs don't say the scrape port is unauthenticated, or what it forwards

**Severity:** low (documentation) · **Confidence:** confirmed

**Threat model:** an unauthenticated network client of the scrape port, which is 8080 and binds to `0.0.0.0` by
default.

**Where:** `SECURITY.md` ("Before you report" lists only the agent, admin, metrics, and dashboard ports) and
`website/prometheus-proxy/docs/security/index.md`. The behavior is in `proxy/ProxyHttpRoutes.kt:124-147,531-549` and
`common/Utils.kt:99-111`.

**Problem:** the scrape port has no authentication.

- Anyone who can reach it can trigger scrapes of internal targets through the agents, with their own query string and
  `Authorization` header passed through.
- With a multi-target exporter that takes its target as a query parameter, that is a path into the agent's network.
- The SD endpoint lists every path, agent name, and host.

This is by design, but it is the main surface for an unauthenticated attacker, and the security policy doesn't
mention it.

**Fix:** add the scrape port to SECURITY.md's by-design list and to the security page. Recommend:

- binding `proxy.http.host` to a private address;
- firewalling the port so only Prometheus can reach it;
- care with exporters that take a target parameter.

Optionally, add a per-path query-parameter allowlist on the agent.

---

## 🏗️ CI, build, and release

### 29. [x] `release-check` accepts any all-green set of check runs, without requiring the CI jobs

**Severity:** medium · **Confidence:** confirmed (the mechanism)

**Where:** `Makefile:402-407`.

**Problem:** the gate requires only a non-empty list of check runs with nothing failed or in progress. It never
requires these checks to be present: `build`, `tla`, `rules`, `helm`, `container-tests`, `helm-smoke`,
`image-scan (proxy|agent)`, and CodeQL. Scenario:

1. The squash commit carries `[skip ci]` (say, pasted into a PR body), or a workflow is disabled. No push workflows
   run for HEAD.
2. A single third-party check run, plausibly Codacy, makes the list non-empty and all green, so the gate passes.
3. `make release` runs only `all-tests` and `distro`. The coverage floors, TLA+, helm lint, promtool, and the Trivy
   scan never ran for the commit being shipped.

**Fix:** add a `RELEASE_REQUIRED_CHECKS` list, and fail when any of those checks is missing as well as when one
has failed.

**Resolution:** a `RELEASE_REQUIRED_JOBS` list in the `Makefile` names the jobs as workflow-file/job-name, since
`ci.yml` and `docs.yml` both have a `build` job: CI's `build`, `tla`, `rules`, and `helm`, the container tests and
`helm-smoke`, both image scans, `codeql`, and zizmor's `code-scanning`. After the existing all-checks gate,
`release-check` reads HEAD's workflow runs and their jobs from the Actions API and fails, listing them, unless each
required job ran and succeeded. Against `master`'s HEAD (`c4a2303`) the check passes; against `778f2df`, which CI
never ran on, it lists all ten jobs; with a bad token it says it couldn't read the runs.

### 30. [x] The version-bump check passes partial bumps, and skips two of the files step 2 lists

**Severity:** medium · **Confidence:** confirmed

**Where:**

- `Makefile:395-396`: `grep -qF '$(VERSION)'`.
- `Makefile:28-34` (`RELEASE_VERSION_FILES`), compared with `docs/RELEASE.md:40`.
- `.claude/skills/publishing-release/SKILL.md:29` ("lists the same files").
- `build.gradle.kts:38`.

**Problem:**

- A file passes if the new version appears anywhere in it, however many old literals remain:
  - A `Chart.yaml` with `version: 4.4.0` but `appVersion: "4.3.0"` passes, and the chart deploys the old image by
    default. Helm lint doesn't notice, and `helm-smoke` overrides the tag, so it doesn't either.
  - The README passes with 11 of its 12 tags bumped.
- Step 2 lists `docs/RELEASE.md` and the release skill's `SKILL.md`, but `RELEASE_VERSION_FILES` leaves both out,
  even though the Makefile, the skill, and AGENTS.md all say the lists match.
- `build.gradle.kts:38` names only README and `llms.txt`.

**Fix:**

- Also fail when a file still has the previous version in a bumpable context. Take the previous version from
  `git describe --tags --abbrev=0`, or from the CHANGELOG section below `$(VERSION)`. Match contexts such as
  `prometheus-(proxy|agent):PREV`, `<version>PREV<`, `^version: PREV`, and `appVersion: "PREV"`, which leaves
  historical mentions like "since 4.3.0" alone.
- Add the two missing files, or say why they are left out.
- Point the gradle comment at step 2.

**Resolution:** `release-check` takes the previous release from the `CHANGELOG.md` section below `$(VERSION)`'s,
and fails, listing each line, when a bumped file still has that version in a bumpable context:
`prometheus-(proxy|agent):PREV`, `<version>PREV</version>`, `version: PREV` and `appVersion: "PREV"` lines,
``e.g., `PREV` ``, and `` `PREV` literal``. `RELEASE_VERSION_FILES` now includes `docs/RELEASE.md` and the release
skill. On a scratch copy, the unbumped files gave exactly the 49 lines the release bump changes and no historical
mention, a full bump passed, and a missed `appVersion` and README tag were both reported. The gradle comment points
at step 2.

### 31. [ ] The docs site is never built on a pull request

**Severity:** medium · **Confidence:** confirmed

**Where:** `.github/workflows/docs.yml:2-6` runs only on a push to `master` and on `workflow_dispatch`. No pull-request
workflow runs `zensical`.

**Problem:** `zensical.toml` sets snippet `check_paths = true`, so moving a snippet's source file breaks the build.
So can a Zensical 0.0.x change. Yet these merge with every required check green:

- Dependabot's weekly `docs-site` PRs;
- hand lock updates, such as 91f651a on this branch (Zensical 0.0.72, python-dateutil 3.0.0).

The first build happens on `master`. If it fails, the published site goes stale, and the failed check on the release
commit blocks `release-check` until a fix lands.

**Fix:**

- Build on pull requests: a job in `ci.yml`, or a `pull_request` trigger in `docs.yml` with deploy gated on
  `github.event_name != 'pull_request'`.
- Run `uv run --locked zensical build --clean`.
- Give it a per-ref concurrency group; the `pages` group would queue it behind deploys.
- Add a matching make target to `CI_JOBS`.

### 32. [x] This branch would pass `release-check` with no release date in either changelog

**Severity:** low · **Confidence:** confirmed

**Where:** `CHANGELOG.md:7` (`## [4.4.0] - Unreleased`), `RELEASE_NOTES.md:7` (`_Not yet released_`), and
`Makefile:391-394`.

**Problem:** step 3 of `docs/RELEASE.md` renames each section "to the new version and release date", as 4.3.0's
release PR did. But the checks match only `^## \[4.4.0\]` and `## 4.4.0`. If this branch merges as is, `make release`
publishes 4.4.0 with both logs still saying "Unreleased". The README's 4.4.0 paragraph also lacks the "(released …)"
that 4.3.0's has.

**Fix:**

- Set the dates in the release PR.
- Make the checks require `^## \[$(VERSION)\] - [0-9]{4}-[0-9]{2}-[0-9]{2}` in CHANGELOG, and `_Released [0-9]{4}-`
  under the RELEASE_NOTES heading.

**Resolution:** `release-check` now requires `## [$(VERSION)] - YYYY-MM-DD` in `CHANGELOG.md`, and
`_Released YYYY-MM-DD_` as the first non-blank line under `## $(VERSION)` in `RELEASE_NOTES.md`. Run through `make`
against the current logs, the checks pass 4.3.0 and 4.2.0 and reject the undated 4.4.0. The dates go in with the
release PR. `docs/RELEASE.md`, `AGENTS.md`, and the release skill describe the stricter check, and both changelogs log
it.

### 33. [x] The `--continue` comments are wrong: a failed test means no coverage report

**Severity:** low · **Confidence:** confirmed in the Kover 0.9.11 source.

**Where:** `.github/workflows/ci.yml:47-50`, and `Makefile:200-201`, which is new in 778f2df: "--continue still writes
the coverage report when a test fails".

**Problem:** Kover's report tasks call `dependsOn(tests)` (`AbstractVariantArtifacts.kt:108`), and under `--continue`
Gradle skips any task whose dependency failed. So when a test fails:

- `koverXmlReport`, `koverLog`, and both verify tasks don't run;
- the `if: always()` Codecov upload finds no report.

`--continue` does help when the tests pass and a coverage floor fails.

**Fix:** correct both comments. If a coverage report is wanted even when a test fails, add a property that sets the
test task's `ignoreFailures`, and fail the build afterwards.

**Resolution:** both comments now say that `--continue` keeps the coverage report when a coverage floor fails, but
not when a test fails, because the kover tasks depend on the test task. The optional `ignoreFailures` property wasn't
added.

### 34. [ ] The site and the Helm charts point at a version before it's published

**Severity:** low · **Confidence:** confirmed

**Where:** `.github/workflows/docs.yml` (deploys on every push to `master`), `docs/RELEASE.md` (steps 1–3 merge before
`make release`), and the `appVersion` in `charts/*/Chart.yaml`.

**Problem:** once a version bump merges, and until `make release` finishes:

- the site gives `docker pull pambrose/prometheus-proxy:4.4.0` and the Maven 4.4.0 coordinate, and both fail
  ("manifest unknown");
- a chart installed from a fresh clone defaults to the 4.4.0 image and hits `ImagePullBackOff`.

The window grows when a release stalls, as it has on Docker Hub push failures.

**Fix:**

- Have `make release` run `gh workflow run docs.yml` after `docker-verify`.
- On a push, deploy only when the version in `gradle.properties` is already on Maven Central, or always build the site
  from the latest release tag.

### 35. [ ] The `com.pambrose.testing` plugin re-adds `kotlin-test` and a second copy of the test dependencies

**Severity:** low · **Confidence:** confirmed in the plugin source, 1.1.5 through 1.1.8.

**Where:** `build.gradle.kts:32,121-125`, and the plugin's `TestingPlugin.kt`.

**Problem:** by default, the plugin adds `kotlin-test`, `kotest-runner-junit5`, and `logback-classic` at its own
versions.

- So the `kotlin("test")` removal in September #34 never took effect.
- The Kotest and Logback versions on the test classpath are the higher of the catalog's and the plugin's (both 6.2.6
  and 1.6.6 today). Each catalog bump therefore silently depends on a matching plugin release.

**Fix:** set `pambroseTesting { addKotest.set(false); addLogbackClassic.set(false); addKotlinTest.set(false) }`, so the
catalog is the only source of these versions.

### 36. [ ] Hand lock upgrades skip the 7-day cooldown Dependabot follows

**Severity:** low · **Confidence:** confirmed for the facts; plausible for the fix.

**Where:** `Makefile:466-467` (`upgrade-site`), `.github/dependabot.yml:5-6`, and commit 91f651a.

**Problem:** `uv lock --upgrade` has no age limit. Commit 91f651a locked:

- python-dateutil 3.0.0, a new major version, three days after it was uploaded;
- Zensical 0.0.72 and certifi, one day after.

None of them was built before merge (see #31).

**Fix:** apply the same cooldown with uv's `exclude-newer` (recent uv accepts a relative span), and check that
`uv run --locked` still accepts the lock. Otherwise, leave website updates to Dependabot.

### 37. [ ] The TLA+ tools jar isn't versioned, so a local run can use different tools than CI

**Severity:** low · **Confidence:** confirmed

**Where:** `Makefile:40-46,150-156`.

**Problem:** `$(TLA_JAR)` is a plain file target. After `TLA_VERSION` and `TLA_SHA256` are bumped, the existing
git-ignored `specs/tla/tla2tools.jar` is reused without being checked again. `make tla-checks` and `make ci` then run
the old TLC, while CI downloads the new one.

**Fix:** name the jar `tla2tools-$(TLA_VERSION).jar` and adjust `.gitignore`, or verify the SHA on every run.

### 38. [ ] CI hygiene: the `build` job has no timeout, and the tool images are pinned by tag only

**Severity:** low · **Confidence:** confirmed

**Where:**

- `.github/workflows/ci.yml:21`: the `build` job has no timeout. The other `ci.yml` jobs set 10–15 minutes; the
  `docs.yml` jobs and `claude.yml` set none.
- `Makefile:50-57` pins `prom/prometheus:v3.14.0`, `alpine/helm:3.22.0` (a third-party image), and
  `ghcr.io/yannh/kubeconform:v0.8.0` by tag. Only `ZIZMOR_IMAGE` (`:66`) is pinned by digest.
- `CRD_SCHEMAS` tracks datreeio's `main` branch.

**Problem:**

- A hung harness test holds the required `build` check for GitHub's six-hour default.
- A re-pushed tag or a moved branch changes what CI runs without any diff in the repo.

**Fix:**

- Add `timeout-minutes` (for example 45) to `build` and the docs jobs.
- Pin the tool images by digest, with the tag in a comment.
- Pin `CRD_SCHEMAS` to a commit.

### 39. [ ] The image's UID is implicit, but the charts hard-code it

**Severity:** low · **Confidence:** plausible

**Where:** `etc/docker/proxy.Dockerfile:16-21` and `etc/docker/agent.Dockerfile` (the same lines),
`charts/prometheus-proxy/values.yaml:109`, and `charts/prometheus-agent/values.yaml:112`.

**Problem:**

- `adduser prometheus` gets UID 1001 only because the noble base image already has `ubuntu` at 1000. A base-image
  change would move it silently, while the charts keep 1001.
- `USER prometheus` isn't numeric, so `runAsNonRoot` without a `runAsUser` fails the kubelet's non-root check.

**Fix:** use `adduser --uid 1001 …` and `USER 1001`, with a comment linking the charts to that UID.

### 40. [ ] The fat JARs keep only one dependency's `NOTICE`

**Severity:** low · **Confidence:** confirmed

**Where:** `build.gradle.kts:301-314`.

**Problem:** under the first-wins `EXCLUDE` strategy, the only `META-INF/NOTICE` left is the Prometheus simpleclient's.
Jackson's and the others are dropped from JARs and images that are redistributed under Apache-2.0, whose §4(d)
requires them.

**Fix:** add Shadow's `ApacheNoticeResourceTransformer` and `ApacheLicenseResourceTransformer`, or append the files.
Keep `.claude/rules/shadow-jar.md`'s service-file rules intact.

---

## 📚 Documentation

### 41. [x] Every embedded-agent example passes `exitOnMissingConfig = true`, which exits the host JVM on a config error

**Severity:** medium · **Confidence:** confirmed

**Where:**

- `README.md:414`: `startAsyncAgent("configFile.conf", true)`. Lines 423-425 then promise a `ConfigLoadException`
  "rather than terminating the host JVM".
- The website snippets: `src/test/java/website/EmbeddedAgentJavaExample.java:27` and
  `src/test/kotlin/website/EmbeddedAgentKotlinExample.kt:26`.
- The public KDoc at `Agent.kt:111,117`.
- `website/prometheus-proxy/docs/embedded-agent.md:61`.
- The `startSyncAgent` KDoc (`Agent.kt:764-766`).

**Problem:** `agent/AgentOptions.kt:68` sets `embedded = !exitOnMissingConfig`. With `true`, the agent isn't in
embedded mode, so `BaseOptions` (`common/BaseOptions.kt:308-316,539-543`) calls `exitProcess(1)` on any of these:

- an unparseable config file;
- an unreachable config URL;
- a bad option;
- `-v` or `-u`.

Scenario: an embedder copies the README example and points it at a config URL. A single transient fetch failure then
terminates their application, which is exactly the case `ConfigLoadException` exists for.

Every test passes `false` (`harness/EmbeddedAgentApiTest.kt:65`, `agent/AgentTest.kt:158,177,248`), so nothing
exercises the documented form.

Two smaller problems:

- The KDoc example `Agent(AgentOptions(args))` (`Agent.kt:111`) doesn't compile, because every public `AgentOptions`
  constructor takes `exitOnMissingConfig`.
- The website describes the flag only as "Exit the JVM if config file is not found".

**Fix:**

- Pass `false` in all four examples.
- Make the KDoc example `AgentOptions(args, exitOnMissingConfig = true)`.
- Reword the website parameter row and the `startSyncAgent` KDoc: `false` means every startup failure throws
  `ConfigLoadException`; `true` means standalone exit behavior.

**Resolution:** the README, both website snippets, and the `Agent` KDoc pass `exitOnMissingConfig = false`, and
the KDoc's standalone example is `AgentOptions(args, exitOnMissingConfig = true)`. The website's parameter row and the
`startSyncAgent` and `startAsyncAgent` KDoc now say what each value does: `false` throws `ConfigLoadException` on a
config or option error and uses the built-in config when none is given; `true` requires a config and exits the
process.

### 42. [ ] The website's tuning table names a config key that doesn't exist, and Troubleshooting a metric that doesn't

**Severity:** medium · **Confidence:** confirmed

**Where:**

| Location | Says | Should say |
|----------|------|------------|
| `website/prometheus-proxy/docs/production.md:150` | `agent.maxConcurrentClients` | `agent.http.maxConcurrentClients` (`config/config.conf:154`) |
| `website/prometheus-proxy/docs/production.md:156` | default of `proxy.internal.maxUnzippedContentSizeMBytes` is "—" | 10 (`config/config.conf:107`) |
| `website/prometheus-proxy/docs/troubleshooting.md:140` | `proxy_connected_agents` | `proxy_agent_map_size` |
| `llms.txt:237` | "Ktor (Netty engine)" | CIO, which the proxy and dashboard servers use (`proxy/ProxyHttpService.kt:26,72`) |

**Problem:**

- HOCON doesn't reject unknown keys. An operator who sets `agent { maxConcurrentClients = 10 }` from the Sizing &
  Tuning table gets no error, and scrapes stay serialized at 1.
- A query or alert on `proxy_connected_agents` silently returns nothing.
- `misc/DashboardMetricNamesTest.kt` checks the Grafana JSON, `alerts.yml`, `MonitoringExamples.txt`, and the PromQL
  blocks in `docs/metrics-and-grafana.md`, but not the website prose.

**Fix:** correct the key, the default, the metric name, and the engine. Optionally, extend `DashboardMetricNamesTest`
(or add a sibling test) to check the backticked `proxy_*`/`agent_*` names and the `proxy.`/`agent.` keys on the website
pages against the exposed series and `config/config.conf`.

### 43. [x] The 4.4.0 changelogs miss the common-utils and gradle-plugins bumps, and describe the state before them

**Severity:** low · **Confidence:** confirmed

**Where:**

- `gradle/libs.versions.toml:16,75`: 778f2df bumps `utils` (common-utils) from 5.1.1 to 5.1.2, and `gradle-plugins`
  from 1.1.7 to 1.1.8.
- `gradle/libs.versions.toml:53-55`, `CHANGELOG.md:28-29`, the 4.4.0 section of `RELEASE_NOTES.md`,
  `.github/dependabot.yml:30`, and `website/prometheus-proxy/docs/embedded-agent.md:102-105`.

**Problem:** common-utils is a runtime dependency that embedders get transitively, and 5.1.2 brings grpc 1.85.1 and
Prometheus client 1.10.0. Yet neither bump is in the commit message or in either log. Stale as a result:

- `CHANGELOG.md:28-29` explains the grpc and Prometheus BOM imports in terms of common-utils 5.1.1.
- The catalog comment ("Ahead of common-utils 5.1.1 … its next release moves to 1.10.0").
- `dependabot.yml:30` ("grpc 1.84 depends on protobuf-java 3.25.9").
- The website tells upgraders to add `prometheus-metrics-simpleclient-bridge` 1.9.0, which would pair with 4.4.0's
  1.10.0 core.

Also, the 4.4.0 section of RELEASE_NOTES lacks two items that CHANGELOG has: the Documentation changes (the Kotlin
badge and the Docker Hub links) and the `make versions` commit-hash fix.

**Fix:**

- Add both bumps to both logs.
- Credit 5.1.2 in the grpc and Prometheus lines.
- Update the catalog and Dependabot comments.
- Change the bridge advice to "the same version as `prometheus-metrics-core`".
- Add the missing items to RELEASE_NOTES.

**Resolution:** both changelogs log common-utils 5.1.1 → 5.1.2 and the `com.pambrose` Gradle plugins 1.1.7 → 1.1.8,
and the grpc and Prometheus lines credit 5.1.2. Its Gradle module metadata confirms that grpc-utils 5.1.2 brings grpc
1.85.1 and prometheus-utils 5.1.2 the Prometheus client 1.10.0. The catalog's Prometheus comment and `dependabot.yml`'s
protobuf comment are current, the website's bridge advice says to match the `prometheus-metrics-core` the agent
brings, and RELEASE_NOTES has the Documentation item and the `make versions` commit-hash fix.

### 44. [ ] `docs/TESTING.md` says it names every spec but omits three, and misstates `all-tests`

**Severity:** low · **Confidence:** confirmed

**Where:** `docs/TESTING.md:156-159` (the claim), `:279` ("Four support helpers"), `:36,39`, and `Makefile:187`.

**Problem:**

- These are missing:
  - `misc/DashboardMetricNamesTest` (#307), the spec that keeps the dashboards, the alert rules, and the docs' PromQL
    on real series;
  - `misc/LogbackConfigTest` (#305);
  - `common/MetricBuildersTest` (#307);
  - the `common/MetricsMocks.kt` helper.
- Lines 36 and 39 say `lincheck-tests` is "left out of every other target", and that `all-tests` is `tests` plus
  `container-tests`. In fact `all-tests` is `tests lincheck-tests container-tests`.

**Fix:** add the missing entries and correct the target description. Optionally, add a spec in the style of
`TestPortsTest` that fails when a `*Test.kt` isn't named in `TESTING.md`.

---

## 🧪 Tests

### 45. [ ] Tests whose assertions can't fail, or that test the JDK instead of the product

**Severity:** low · **Confidence:** confirmed

**Where:**

- `proxy/ProxyHttpServiceTest.kt:83-123`: the two idle-timeout tests assert only `service.shouldNotBeNull()`, so they
  pass even if `idleTimeoutSecs` is ignored (`proxy/ProxyHttpService.kt:55-58`, where `idleTimeout` is private).
- `proxy/ProxyGrpcServiceTest.kt:164-178,230-252`: "should create server with keepalive settings" and the other
  constructor tests assert only that the object isn't null. Nothing checks that the eight handshake, keepalive, and
  connection-age options (`proxy/ProxyGrpcService.kt:123-145`) reach the builder, yet `docs/TESTING.md:212` lists
  keepalive as covered.
- `agent/SslSettingsTest.kt:110-137`: four tests call only JDK APIs (`TrustManagerFactory.getDefaultAlgorithm`,
  `KeyStore.getDefaultType`, `SSLContext.getInstance("TLS")`). They are left over from the `getSslContext` removal in
  #313.

**Fix:**

- Make `idleTimeout` `internal`, and assert 45s and 90s.
- Verify that the gRPC options reach the builder. Otherwise, rename those tests "builds without throwing" and drop
  the keepalive claim from `TESTING.md`.
- Delete the JDK-only tests.

### 46. [ ] Failed-startup tests free ephemeral ports and then bind them again

**Severity:** low · **Confidence:** plausible (not observed)

**Where:** `proxy/ProxyTest.kt:537-538,572` and `agent/AgentTest.kt:204`, all using
`ServerSocket(0).use { it.localPort }`.

**Problem:** these ports come from the OS's ephemeral range. The test releases each one, and the proxy or agent under
test binds it again later. That is the window AGENTS.md and `TestPortsTest` keep the harness out of. If another
connection takes the port in between, the wrong service fails to start, and the test's "port free" assertion fails.
The ports deliberately held open as "taken" are fine.

**Fix:** give these specs `TestPorts` entries below 32768 for every port a service binds. Keep `ServerSocket(0)` only
for the port that is meant to stay occupied.

### 47. [ ] The three JVM metric groups added in #314 are never checked on a real `/metrics`

**Severity:** low · **Confidence:** confirmed

**Where:**

- `harness/NettyTestWithAdminMetricsTest.kt:120-136` asserts only `proxy_*` and `agent_*` families, although
  `config/test-configs/harness.conf:12-14,29-31` turns all three groups on.
- The #314 additions to `misc/DataClassTest.kt` check that each flag maps to `true` on its own, but never that the
  others stay `false`.

**Problem:** if `ConfigWrappers` passed one flag into every slot, or common-utils ignored the flags, every test would
still pass.

**Fix:**

- Assert `jvm_buffer_pool_used_bytes` and `jvm_compilation_time_seconds_total` in the metrics-endpoint test. Native
  memory exports nothing without `-XX:NativeMemoryTracking`, so assert that it's absent, or skip it.
- In `DataClassTest`, assert that the sibling flags stay `false`.

---

## ✅ Appendix: checked and sound

### The #320 and #321 refactors

- **`ProxyPathManager` (#320):** `addValidatedPath` and the helpers it now calls, plus `dropAgentFromPath`, behave
  the same as the parent commit, including:
  - check order;
  - path counts and the limit;
  - displaced-orphan invalidation;
  - consolidated join and self-replace;
  - series removal and the `PathUnregistered` events.
- **`AgentPathManager` (#320):** `mayRetryNow`, `discoveredRejection`, and `reportConflict` match the old inline
  logic.
- **`ProxyServiceImpl` (#321):**
  - `ChunkedResponseReader`, `emitScrapeRequests`, and `logResponseStreamFailure` are faithful to the old code.
  - `readRequestsDenial` is still thrown before the try/finally, so a spoofed id can't evict another agent.
- **`Agent` (#321):** `connectToProxy`, `launchConnectionTasks`, the heartbeat functions,
  `retryRejectedStaticPathsForConnection`, and `scrape` match the old inline code.
- **Options (#321):** the `resolve*Option` helpers keep the CLI → env → config order (-1 or an empty string means
  unset), with the same validation and log order. The cleartext-token warning uses the resolved TLS fields.

### Other recent changes

- **#313:** the proxy's failed-start rollback stops sub-services in reverse order and attaches stop errors as
  suppressed. The remote address comes from `TRANSPORT_ATTR_REMOTE_ADDR` with no reverse DNS, and is display-only.
- **#314:** `ConfigWrappers` passes the new buffer-pool, compilation, and native-memory flags by name, for both roles.
- **#330:** the 413 warnings use `sanitizeUrl`, and the failure reason is unchanged.

### Concurrency, locking, and failover

- **Scrape path:**
  - The in-flight CAS claim and its `finally` release are balanced.
  - Per-agent backlog admission stays balanced against `invalidate()`'s drain.
  - The completion CAS publishes `proxyFailure` together with the results.
  - `isStillAwaited` skips abandoned requests.
- **Lock ordering:** `pathMap` → `pathSeries` bin → histogram, with no cycle. `eventBus.emit` inside the lock is a
  non-blocking `tryEmit`.
- **Failover:** `EndpointFailover` matches `AgentFailover.tla`. `shutDownChannelPermanently` latches. Any connection
  task ending closes the context and cancels its siblings.
- **Agent backlog accounting** stays balanced across a failed send, the drain on close, and each scrape's `finally`.

### Auth and TLS

- **Token auth:**
  - Tokens are compared as SHA-256 digests with `MessageDigest.isEqual`.
  - Empty, duplicate, and shared tokens are rejected at startup.
  - Token values are never logged.
  - The server-wide interceptor covers every service, reflection included.
- **Path takeover and consolidated joins:**
  - Identity is compared under the `pathMap` lock.
  - The glob is anchored and escaped, with leading-slash normalization.
  - `unregisterPath` removes only paths the caller serves.
- **TLS:**
  - With a trust path, the server sets `ClientAuth.REQUIRE`.
  - The client verifies the hostname, with an `overrideAuthority` option.
  - `TrustAllX509TrustManager` is used only for the HTTPS scrape client, is off by default, and warns.

### Payloads and the dashboard

- **Agent outbound HTTP:**
  - Only same-origin redirects are followed.
  - Protobuf is stripped from `Accept`.
  - URLs are redacted in logs, failure reasons, and config-load errors.
  - The body-read cap is not a memory bound; see #8.
- **Proxy payload handling:**
  - The unzip limit holds.
  - Chunk byte counts, counts, and CRCs are checked.
  - Orphaned transfers are swept on a normal stream end; see #3 for a cancelled one.
  - The content-type allowlist sends `nosniff` and `CSP: sandbox`.
- **Dashboard:**
  - kotlinx.html escapes all agent-supplied text.
  - The asset route is an allowlist.
  - The WebSocket Origin check, opt-in Host allowlist, session cap, 64 KiB frame cap, and bounded outgoing buffer are
    in place.
  - The live region stays outside every `hx-swap-oob` region.
- **SD reserved labels:** `__*`, `agentName`, `hostName`, and optionally `job` and `instance` are dropped.

### Build, CI, and packaging

- **`make ci`:** `CI_JOBS` maps one-to-one onto `ci.yml`'s `tla`, `rules`, `helm`, and `build` jobs, and `ci-build`
  repeats both Gradle steps with `HARNESS_CONFIG=MINI`.
- **The version bump** to 4.3.1 in 778f2df: every remaining `4.3.0` was historical or illustrative, and all 11
  `RELEASE_VERSION_FILES` contained 4.3.1. With the retarget, the literals went back to 4.3.0 for the release PR.
- **Workflows:**
  - Every action is SHA-pinned with a version comment.
  - Every checkout sets `persist-credentials: false`.
  - Permissions are least-privilege per job.
  - There is no `pull_request_target`.
  - Concurrency never cancels a `master` run.
  - The zizmor pins match between the Makefile and the workflow.
- **Dependabot:** all four ecosystems have a cooldown.
- **Toolchains:** main code compiles on Java 17 and tests run on 25; the images run Temurin 25 `-noble`, pinned by
  digest.
- **Shadow JAR:** the service-file and `kotlin_module` merging, the `src/shadow` pins, and the project's own
  `reference.conf` and `logback.xml` winning are all correct. The JARs contain no signature files.
- **Docker and Helm:**
  - The images run as a non-root user and have an allowlist `.dockerignore`.
  - The charts set a read-only root filesystem, seccomp `RuntimeDefault`, and drop all capabilities.
  - Tokens and TLS material come from Secrets, apart from #27.
  - The admin and dashboard ports aren't on the Service.

### Tests and docs

- **Lincheck:** all 7 specs are tagged `Lincheck`, use no MockK, and report through `@Validate`.
- **`assertSoftly`:** both blocks capture outcomes as values first.
- **`TestPorts`:** every constant is used, distinct, and below 32768.
- **AGENTS.md:** every function and type it cites exists and behaves as described.
- **CLI and env docs:** `docs/cli-args.md` and the website's `cli-reference.md` cover every `@Parameter` flag and all
  51 `EnvVars`.
- **Metric docs:** metric names and labels match `ProxyMetrics` and `AgentMetrics`, apart from #7's `no_agents` and
  #42.
- **`docs/packages.md`:** matches the public types.
- **Earlier fixes:** spot-checked, with no regressions apart from those listed in the headline.
