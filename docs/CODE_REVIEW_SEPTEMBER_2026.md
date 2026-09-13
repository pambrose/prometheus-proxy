# Prometheus-Proxy Code Review — September 2026 Findings

**Date:** 2026-09-12

**Scope:** `master` at `75579de` — ~10.7k lines of main source, ~28.9k lines of tests, plus the
build, CI workflows, Docker packaging, and documentation.

**Process:** five parallel reviews — proxy-side production code, agent-side and shared code,
security, build/CI/Docker/repo hygiene, and tests plus documentation accuracy. Each was told to read
the June and July 2026 reviews and the recent `git log` first, so fixed or already-tracked items are
not resurfaced. The key claim in every finding was then re-verified by reading the cited source
lines. Confidence is marked per finding: **confirmed** (traced in this repo's code) or **plausible**
(the repo-side gap is confirmed, but the failure depends on library behavior — Ktor, Guava, or gRPC
defaults — that was read from dependency sources rather than exercised).

**Headline:** the fixes from the earlier reviews hold, and the scrape path's core concurrency
machinery again checked out. The new findings cluster in four places: per-agent authorization does
not bind scrape results or heartbeats to the sending agent; a single rejected path or a
registration-rejecting proxy takes an agent fully offline with no failover; `master` does not
require the build or tests to pass before merging; and the CLI reference has drifted from the code.

---

## 📋 Findings index

✅ = fixed/addressed · ⬜ = open

| #  | Finding                                                                      | Area          | Severity | Status |
|----|------------------------------------------------------------------------------|---------------|----------|--------|
| 1  | Proxy accepts scrape results from any agent                                  | Security      | high     | ✅      |
| 2  | Agent identity unenforced with transport filter off; heartbeats never bound  | Security      | medium   | ✅      |
| 3  | No backpressure on the scrape queue; timed-out requests still dispatched     | Security      | medium   | ⬜      |
| 4  | Dashboard: open bind, no Origin check, unlimited sessions, per-frame snapshot | Security      | medium   | ⬜      |
| 5  | Credentials in target URLs leak to proxy, dashboard, logs                    | Security      | medium   | ✅      |
| 6  | Auth warning logic wrong; TLS examples use public test keys                  | Security      | low      | ✅      |
| 7  | One rejected static path takes the whole agent offline                       | Agent         | high     | ✅      |
| 8  | Failover never leaves a proxy that rejects registration                      | Agent         | medium   | ✅      |
| 9  | Failed discovery unregister leaves a stale path forever                      | Agent         | medium   | ✅      |
| 10 | Dead-connection detection (~90s) slower than proxy eviction (60s)            | Agent         | medium   | ✅      |
| 11 | Embedded agent startup failure leaks channel and cache                       | Agent         | medium   | ⬜      |
| 12 | Chunk size and gzip threshold unbounded vs gRPC 4 MiB limit                  | Agent         | low      | ⬜      |
| 13 | Host-only proxy address ignores `agent.proxy.port`                           | Agent         | low      | ⬜      |
| 14 | Prometheus-cancelled scrapes leave no metric or debug trace                  | Proxy         | low      | ⬜      |
| 15 | Per-path metric series never removed                                         | Proxy         | low      | ⬜      |
| 16 | Duplicate consolidated registration adds the agent twice                     | Proxy         | low      | ⬜      |
| 17 | Per-scrape debug/dashboard bookkeeping runs when both are off                | Proxy         | low      | ⬜      |
| 18 | `master` does not require build or tests to pass                             | CI/build      | high     | ✅      |
| 19 | Docs site built with different Zensical/Python in CI than locally            | CI/build      | medium   | ✅      |
| 20 | Workflow supply-chain and efficiency gaps                                    | CI/build      | medium   | ⬜      |
| 21 | Gradle 10 deprecation comes from the `taskinfo` plugin                       | CI/build      | medium   | ⬜      |
| 22 | `bin/docker-*.sh` produce an empty image tag                                 | CI/build      | medium   | ✅      |
| 23 | Oversized Docker build context; wrong `EXPOSE` ports                         | CI/build      | low      | ⬜      |
| 24 | Stale compose file and nginx run script                                      | CI/build      | low      | ⬜      |
| 25 | `docs/cli-args.md` documents a nonexistent env var and omits current flags   | Docs          | high     | ✅      |
| 26 | `security-agent-authentication.md` predates per-agent auth                   | Docs          | medium   | ✅      |
| 27 | Testing docs cite a nonexistent Gradle task and an incomplete spec list      | Docs          | medium   | ✅      |
| 28 | CHANGELOG/RELEASE_NOTES behind; release checklist misses version literals    | Docs          | medium   | ✅      |
| 29 | Metrics doc and CLAUDE.md drift                                              | Docs          | low      | ⬜      |
| 30 | Discovery and chunk-failure paths untested                                   | Tests         | medium   | ⬜      |
| 31 | Timing-sensitive and vacuously passing tests                                 | Tests         | medium   | ⬜      |
| 32 | Coverage gate checks totals only                                             | Tests         | low      | ⬜      |
| 33 | Tracked IDE state, point-in-time docs, dead local directory                  | Hygiene       | low      | ⬜      |
| 34 | Minor build tidy-ups                                                         | Hygiene       | low      | ⬜      |

**Suggested starting points:** #1, #7, and #8 (they undo per-agent authorization or take an agent
offline with no failover), #18 (a PR can merge with failing tests), and #25 (operators are told to
set an env var that is silently ignored).

---

## 🔒 Security

### 1. [x] Proxy accepts scrape results from any agent

**Severity:** high · **Confidence:** confirmed

**Where:** `proxy/ScrapeRequestManager.kt:57`, `proxy/ProxyServiceImpl.kt:274` (unary results),
`proxy/ProxyServiceImpl.kt:315` (chunked HEADER), `proxy/ScrapeRequestWrapper.kt:99` (ID counter).

**Problem:** `assignScrapeResults` finds the waiting request by `scrapeId` alone, and scrape IDs come
from one process-wide counter. Neither `writeResponsesToProxy` nor `writeChunkedResponsesToProxy`
compares the sender with `wrapper.agentContext.agentId`. PR #208 bound the connection's agent ID for
`registerAgent`, `registerPath`, `unregisterPath`, and `readRequestsFromProxy`, but not for these two
RPCs. An agent authorized only for `team-a-*` can observe its own IDs, answer nearby IDs with
fabricated metrics for another team's paths (first result wins), or send a HEADER with a victim's
`scrapeId` so `putChunkedContext` replaces the victim's in-progress transfer and the real scrape
fails its checksum.

**Fix:** read `CONNECTION_AGENT_ID_KEY` at RPC start and drop any response whose
`wrapper.agentContext.agentId` differs. Key chunked transfers by `(agentId, scrapeId)` and use
`putIfAbsent`. Add tests modeled on the existing attacker-agent specs.

### 2. [x] Agent identity unenforced with the transport filter off; heartbeats never bound

**Severity:** medium · **Confidence:** confirmed

**Where:** `proxy/ProxyServiceImpl.kt:113` (`connectionMismatchReason`), `:224` (`sendHeartBeat`),
`:237-270` (`readRequestsFromProxy`).

**Problem:** `connectionMismatchReason` returns "allow" whenever the connection agent ID is unset,
which is always the case with `transportFilterDisabled` (the nginx deployment). In that mode an
authenticated agent can open `readRequestsFromProxy` under another agent's ID and receive its scrape
requests, including Prometheus's `authHeader`; closing that stream then removes the victim's context.
`sendHeartBeat` has no binding check in any mode, so any client can keep a dead agent from being
evicted.

**Fix:** record the resolved identity on `AgentContext` at connect and require the same identity on
every later call, including heartbeats.

### 3. [ ] No backpressure on the scrape queue; timed-out requests still dispatched

**Severity:** medium · **Confidence:** confirmed

**Where:** `proxy/AgentContext.kt:56-57`, `proxy/ProxyHttpService.kt:65`,
`proxy/ProxyHttpRoutes.kt:330-335`, `proxy/ProxyServiceImpl.kt:252-263`.

**Problem:** the HTTP port binds `0.0.0.0` and each agent's queue is a `ConcurrentLinkedQueue` with
a `Channel(UNLIMITED)` notifier, so anyone who can reach the port can queue unbounded scrapes, each
of which becomes an internal fetch by the agent and up to several MB of proxy memory. When a request
times out or Prometheus hangs up, the `finally` removes it from `scrapeRequestMap` but leaves it in
the agent's queue, and `readRequestsFromProxy` emits whatever it dequeues without checking — so a
slow agent keeps scraping for nobody and its backlog grows.

**Fix:** cap each agent's backlog and return 503 when full; add a global in-flight limit; skip
dequeued requests for which `containsScrapeRequest(scrapeId)` is false; make the bind host
configurable. Optionally honor `X-Prometheus-Scrape-Timeout-Seconds` as a cap on the wait.

**Progress:** the proxy now skips a dequeued request it no longer tracks, so the agent no longer scrapes
for requests Prometheus has abandoned. Still open: the per-agent backlog cap, the global in-flight limit,
and a configurable bind host.

### 4. [ ] Dashboard: open bind, no Origin check, unlimited sessions, per-frame snapshot

**Severity:** medium (only when the dashboard is enabled) · **Confidence:** confirmed for the bind,
the missing Origin check, and the per-frame snapshot; plausible for the buffer growth

**Where:** `proxy/dashboard/ProxyDashboardService.kt:125` (bind), `:135` (`install(WebSockets)`),
`:206-212` (frame loop).

**Problem:** the dashboard binds `0.0.0.0`, performs no Origin/Host validation (so a page open in an
operator's browser can read the topology via cross-site WebSocket hijacking or DNS rebinding), and
accepts unlimited sessions. Every incoming text frame runs a full `snapshot()`, which takes the same
`pathMap` lock the scrape path uses, so a client looping messages couples scrape latency to the
dashboard. `install(WebSockets)` uses the defaults; in Ktor 3.5.2 that means an unbounded outgoing
buffer and no pings, so a client that stops reading accumulates a queued frame every refresh until
the proxy runs out of memory.

**Fix:** default the bind host to localhost (configurable); validate Origin; cap sessions; on an
incoming frame update the selection and serve the cached snapshot; set a ping period/timeout and a
latest-frame-only per-session buffer.

### 5. [x] Credentials in target URLs leak to the proxy, the dashboard, and logs

**Severity:** medium · **Confidence:** confirmed for the raw `targetUrl`; plausible for the Ktor
exception-message path

**Where:** `agent/AgentGrpcService.kt:399`, `agent/AgentHttpService.kt:143`,
`common/ScrapeResults.kt:110-118`, `proxy/dashboard/ProxyDashboardHtml.kt:209`.

**Problem:** `targetUrl = urlVal` sends the configured URL to the proxy unmodified, where it is
rendered on the dashboard and in `/debug`; `sanitizeUrl` is called only inside `AgentHttpService`.
Separately, Ktor's timeout exceptions embed the raw request URL (userinfo and query string) in their
message, and that message is logged at WARN and returned to the proxy as `srFailureReason` even with
debug off — bypassing the June URL-sanitizing fix. Relatedly, `hasTimeoutCause` does not recognize
the CIO engine's `ConnectTimeoutException`, so those timeouts report 503 rather than 408.

**Fix:** sanitize the URL before sending, logging, or rendering it; pass exception messages through
the sanitizer before logging or returning them; add the CIO connect-timeout type to
`hasTimeoutCause`. Also avoid dumping whole protos at DEBUG (`AgentGrpcService.kt:462` includes
`authHeader`).

**Resolution:** target URLs are redacted everywhere they are sent, logged, or rendered, including inside
exception messages; the proxy also redacts URLs from older agents; `hasTimeoutCause` recognizes Ktor's
`ConnectTimeoutException`; and the DEBUG trace logs only the scrape ID and path. One gap remains: the stack
trace of a failed scrape, now logged at DEBUG instead of WARN, still renders the exception's unredacted
message.

### 6. [x] Auth warning logic is wrong; TLS examples use public test keys

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyOptions.kt:357`, `examples/tls-with-mutual-auth.conf`,
`examples/tls-no-mutual-auth.conf`, `testing/certs/`.

**Problem:** `isAgentPortUnauthenticated` treats a non-empty `trustCertCollectionFilePath` as
authentication, but TLS is only active when both cert and key are set (`isTlsEnabled`), so a
trust-path-only config runs in plaintext without the warning. Tokens configured without TLS travel in
cleartext with no warning. The TLS examples point at the public gRPC test certificates, and the
no-mTLS example enables `enableTrustAllX509Certificates`.

**Fix:** warn when tokens are configured and `!isTlsEnabled`; count mTLS only when TLS is enabled and
a trust path is set; label the example certificates test-only and remove trust-all from the example.

---

## 🛰️ Agent reliability

### 7. [x] One rejected static path takes the whole agent offline

**Severity:** high · **Confidence:** confirmed

**Where:** `agent/AgentPathManager.kt:86`, `Agent.kt:291`, `agent/AgentGrpcService.kt:402-405`.

**Problem:** `registerPaths()` is a plain `forEach`. When the proxy returns `valid=false` for one path
(for example, the agent's identity is not authorized for it), the resulting exception ends the whole
connection. The agent reconnects every few seconds forever with every path down, and the only log
line is at INFO. Discovered paths already isolate failures per path in `reconcileDiscoveredPaths`.

**Fix:** catch failures per static path and log them at WARN or ERROR; keep only `registerAgent`
failure fatal to the connection, and raise that log to WARN.

### 8. [x] Failover never leaves a proxy that accepts the connection but rejects registration

**Severity:** medium · **Confidence:** confirmed

**Where:** `Agent.kt:259-278`, `agent/AgentClientInterceptor.kt:62`.

**Problem:** the reconnect logic treats a non-empty `agentId` as "connected, then dropped" and calls
`resetEndpoint()`. The client interceptor sets `agentId` from the response to `connectAgent`, before
`registerAgent` and `registerPaths` run, so a registration failure also looks like a successful
connection. With endpoints `[A rejects, B healthy]` the agent retries A forever; with
`[A down, B rejects, C healthy]` it cycles A → B → A and never reaches C.

**Fix:** mark the connection successful only after `registerAgent` and `registerPaths` complete, and
otherwise advance to the next endpoint. Fix together with #7.

### 9. [x] A failed discovery unregister leaves a stale path forever

**Severity:** medium · **Confidence:** confirmed

**Where:** `agent/AgentPathManager.kt:126-141`, `:175-180`.

**Problem:** `doUnregisterPath` calls the proxy before removing the local entry. If the proxy replies
`valid=false` because the path is gone or now owned by another agent, the exception skips the local
removal. Every reconcile tick then retries and logs a WARN, the agent keeps serving the old URL, and
for a changed URL the re-register step never runs, so the new URL is never applied.

**Fix:** treat "not found" and "not owner" as already unregistered and remove the local entry anyway.

### 10. [x] Dead-connection detection (~90s) is slower than proxy eviction (60s)

**Severity:** medium · **Confidence:** confirmed for the timing; plausible for the idle read stream

**Where:** `Agent.kt:484-501`, `Agent.kt:622` (`MAX_HEARTBEAT_FAILURES = 3`),
`agent/AgentGrpcService.kt:124-125` (30s unary deadline), `Agent.kt:390-406`.

**Problem:** heartbeats use the unary stub and inherit `unaryDeadlineSecs = 30`, and keepalive is off
by default. On a silently dropped TCP connection each heartbeat waits the full deadline, so three
failures take about 90 seconds — longer than the proxy's 60-second eviction and far from the intended
~5-second cadence. Separately, when a write stream fails, `closeAll()` closes the connection context
and the heartbeat loop exits, but the idle `readRequestsFromProxy` stream is not cancelled, so the
enclosing scope stays open and the agent does not reconnect until a scrape arrives or it is evicted.

**Fix:** give heartbeats a short dedicated deadline; when any connection task completes, cancel the
connection scope's children or call `grpcService.shutDownChannel()`.

**Resolution:** a heartbeat's deadline is now the heartbeat interval, capped at the unary deadline and
never below 1s, so a half-open connection is detected in about 16s instead of about 90s. With unary
deadlines disabled, heartbeats have no deadline either, as before. The second part -- an idle read stream
not being cancelled when a write stream dies -- is not addressed: it was never confirmed, and no failing
test could be written for it.

### 11. [ ] Embedded agent startup failure leaks the channel and cache

**Severity:** medium · **Confidence:** plausible (depends on Guava service lifecycle semantics)

**Where:** `Agent.kt:172,198,698-699`, `agent/AgentGrpcService.kt:204`,
`agent/HttpClientCache.kt:83,97`, `agent/EmbeddedAgentInfo.kt`.

**Problem:** the `Agent` constructor builds the gRPC `ManagedChannel` and starts the HTTP client
cache's cleanup coroutine, and `startAsyncAgent` returns right after `startAsync()`. If startup then
fails (for example, the admin or metrics port is taken), the service moves to FAILED without calling
`shutDown()`, so the channel and coroutine leak, and `EmbeddedAgentInfo.shutdown()` throws
`IllegalStateException` on the FAILED service. A constructor exception after the cache is built (bad
TLS path, bad filter regex) leaks the coroutine the same way and surfaces as
`IllegalArgumentException` rather than the documented `ConfigLoadException`.

**Fix:** create the channel and cache lazily, or close them from `failed()` / a `Service.Listener`;
have `startAsyncAgent` await running or report failure; make `shutdown()` tolerate FAILED.

### 12. [ ] Chunk size and gzip threshold are unbounded against gRPC's 4 MiB limit

**Severity:** low · **Confidence:** plausible (the proxy-side rejection follows from gRPC's default)

**Where:** `agent/AgentOptions.kt:332-345`, `agent/AgentGrpcService.kt:502-515`.

**Problem:** `chunkContentSizeKbs` is capped only at `Int.MAX_VALUE / 1024` and `minGzipSizeBytes`
has no upper bound, while `maxInboundMessageSize` is never set anywhere, so gRPC's 4 MiB default
applies. A large chunk size sends payloads as single messages the proxy would reject, ending the
write stream and dropping every in-flight result on that connection, on every scrape.

**Fix:** `require` both values to stay below a safe bound (4 MiB less protocol overhead).

### 13. [ ] Host-only proxy address ignores `agent.proxy.port`

**Severity:** low · **Confidence:** confirmed

**Where:** `agent/AgentOptions.kt:72-73,303`, `agent/AgentGrpcService.kt:133`.

**Problem:** only endpoints read from the config file receive `agent.proxy.port`. A host-only value
from `-p host` or `PROXY_HOSTNAME=host` goes through `parseEndpointList(..., DEFAULT_GRPC_PORT)`, so
with `port = 60000` in config and `PROXY_HOSTNAME=proxy` the agent dials `proxy:50051`, contrary to
the KDoc.

**Fix:** pass `configVals.agent.proxy.port` as the default port, or correct the KDoc.

---

## 📡 Proxy observability and performance

### 14. [ ] Prometheus-cancelled scrapes leave no metric or debug trace

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyHttpRoutes.kt:119`, `:203-225`.

**Problem:** the outcome record, the latency histogram observation, and the request count all run
after `awaitAll()` returns. When Prometheus times out and cancels the call, none of them run — so the
most common real failure (agent slower than Prometheus's timeout but faster than the proxy's) is
absent from `proxy_scrape_requests`, the latency histogram, `/debug`, and the dashboard.

**Fix:** catch the cancellation and record a `client_cancelled` outcome inside `NonCancellable`.

### 15. [ ] Per-path metric series are never removed

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyMetrics.kt:59,67`, `proxy/ProxyHttpRoutes.kt:222,394`.

**Problem:** both histograms carry a `path` label and nothing removes a label set. With discovery
creating and retiring paths, every path ever scraped keeps its full histogram series in memory and
on `/metrics` indefinitely.

**Fix:** remove the label sets when a path's last registration goes away, or drop the `path` label.

### 16. [ ] Duplicate consolidated registration adds the agent twice

**Severity:** low · **Confidence:** confirmed

**Where:** `proxy/ProxyPathManager.kt:118`, `:126-130`; `agent/AgentPathManager.kt:69-74`.

**Problem:** `agentInfo.agentContexts + agentContext` never checks whether the agent is already
listed, and agent config does not reject duplicate paths. A path listed twice sends two requests to
the same agent per scrape, and the merged duplicate samples are rejected by Prometheus. In
non-consolidated mode the agent "displaces" itself, incrementing `proxy_agent_displacement_total`.

**Fix:** replace an existing entry for the same agent instead of appending, and skip the displacement
count when the only existing owner is the registering agent.

### 17. [ ] Per-scrape debug and dashboard bookkeeping runs when both are off

**Severity:** low (performance) · **Confidence:** confirmed

**Where:** `proxy/ProxyHttpRoutes.kt:229-258`, `Proxy.kt:435-446`.

**Problem:** for every agent on every scrape, `recordScrapeOutcome` builds a status string, formats
the current time, takes the process-wide `recentReqs` and `recentScrapes` locks, and emits a
`ScrapeCompleted` event — even though `debugEnabled` and the dashboard both default to off.

**Fix:** skip this work unless `debugEnabled` or the dashboard is enabled.

---

## 🏗️ CI and build

### 18. [x] `master` does not require the build or tests to pass

**Severity:** high · **Confidence:** confirmed

**Where:** branch protection on `master`; `.github/workflows/ci.yml`.

**Problem:** the only required status check is `Codacy Static Code Analysis` (`strict: true`,
`enforce_admins: false`, no rulesets). `build` and `container-tests` are advisory, so a PR can merge
with failing tests or a coverage drop.

**Fix:** add `build` as a required check. Add `container-tests` too once its workflow always reports
a status (it currently has a `paths` filter on pull requests).

### 19. [x] Docs site built with different Zensical and Python in CI than locally

**Severity:** medium · **Confidence:** confirmed

**Where:** `.github/workflows/docs.yml:1-12,31-36`, `website/uv.lock`, `website/pyproject.toml:6`.

**Problem:** CI installs `zensical==0.0.45` via pip with `python-version: 3.x`, while `uv.lock`
resolves `zensical 0.0.59` and `pyproject.toml` requires Python `>=3.14`, so the deployed site is not
built with what `make site` previews. The workflow also triggers on a `main` branch that does not
exist and grants `pages: write` and `id-token: write` at workflow level rather than to the deploy job.

**Fix:** use `astral-sh/setup-uv` and `uv run --locked zensical build`; drop the `main` trigger; move
the write permissions onto the deploy job.

### 20. [ ] Workflow supply-chain and efficiency gaps

**Severity:** medium · **Confidence:** confirmed

**Where:** `.github/workflows/*.yml`.

**Problem:** every action is pinned by tag rather than commit SHA, including the third-party actions
that receive secrets or tokens (`anthropics/claude-code-action@v1` with `id-token: write`,
`codecov/codecov-action@v5` with `CODECOV_TOKEN`). There is no Dependabot or Renovate configuration.
`claude.yml` uses `actions/checkout@v4` while the rest use v5. `ci.yml` and `container-tests.yml`
have no concurrency groups, so repeated pushes stack runs, and `container-tests.yml` runs on every
`master` push. `ci.yml:30,33` runs `build -x test` and then lint and detekt again.

**Fix:** pin actions to SHAs; add Dependabot for `github-actions` and `gradle`; add
`concurrency: { group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress: true }`; drop
the duplicate lint step.

### 21. [ ] Gradle 10 deprecation comes from the `taskinfo` plugin

**Severity:** medium · **Confidence:** confirmed

**Where:** `build.gradle.kts:34,124-127`, `gradle/libs.versions.toml:19`.

**Problem:** the build's only recorded Gradle problem is `Project.getProperties` deprecated for
removal in Gradle 10, attributed to `org.barfuin.gradle.taskinfo` 3.0.2. The plugin is applied on
every non-IntelliJ build, but only `make tibuild` uses it.

**Fix:** upgrade the plugin, or apply it only when a `ti*` task is requested.

### 22. [x] `bin/docker-agent.sh` and `bin/docker-proxy.sh` produce an empty image tag

**Severity:** medium · **Confidence:** confirmed

**Where:** `bin/docker-agent.sh:4`, `bin/docker-proxy.sh:4`.

**Problem:** both scripts read the version with `grep '^version ' build.gradle.kts`, but the version
now lives in `gradle.properties`. The lookup returns nothing, so `docker run` receives
`pambrose/prometheus-agent:`, which is not a valid image reference.

**Fix:** `sed -n 's/^version=//p' gradle.properties`, matching the Makefile's `VERSION`.

### 23. [ ] Oversized Docker build context; wrong `EXPOSE` ports

**Severity:** low · **Confidence:** confirmed

**Where:** `.dockerignore`, `etc/docker/proxy.df:26-30`.

**Problem:** `.dockerignore` excludes neither `build/` (~140 MB) nor `.gradle`, the docs site output,
or `src`, and the context is sent once per image on `make docker-push`. The images only copy
`build/libs/*.jar`. `proxy.df` exposes `50440` (the nginx sidecar's port) and omits the dashboard's
default `8094`. There is no `HEALTHCHECK`, which is defensible because admin `/ping` defaults off.

**Fix:** convert `.dockerignore` to an allowlist (`*` then `!build/libs/prometheus-*.jar`); correct
the `EXPOSE` list; optionally merge the three `RUN` lines.

### 24. [ ] Stale compose file and nginx run script

**Severity:** low · **Confidence:** confirmed

**Where:** `etc/compose/proxy.yml`, `nginx/docker/run.sh`.

**Problem:** `proxy.yml` uses the defunct Docker Cloud/Tutum stack format (`autoredeploy`, no
`services:` key), so `docker compose` cannot run it, and it references `pambrose/prometheus-test`.
`run.sh` has no shebang and builds `pambrose/nginx2`.

**Fix:** rewrite `proxy.yml` as a current Compose file or delete it; add a shebang to `run.sh`.

---

## 📚 Documentation

### 25. [x] `docs/cli-args.md` documents a nonexistent env var and omits current flags

**Severity:** high · **Confidence:** confirmed

**Where:** `docs/cli-args.md:9,43,59`; `website/prometheus-proxy/docs/cli-reference.md:70`.

**Problem:** both tables list `AGENT_MAX_CONTENT_LENGTH_MBYTES`, which does not exist in `EnvVars`,
so setting it is silently ignored. `cli-args.md`, linked from README.md as the complete configuration
reference, omits `--agent_token` / `AGENT_TOKEN`, `--dashboard`, `--dashboard_port`,
`--dashboard_path`, and `--https_truststore` / `--https_truststore_password`. Line 43 does not
mention the comma-separated failover list, and line 9 describes the proxy's `--config` as "Agent
config file". The website reference also lacks the dashboard flags.

**Fix:** regenerate both tables from the `@Parameter` definitions and `EnvVars`, or remove the
phantom entry and add the missing flags by hand.

### 26. [x] `security-agent-authentication.md` predates per-agent auth

**Severity:** medium · **Confidence:** confirmed

**Where:** `docs/security-agent-authentication.md:3,35,117-139`.

**Problem:** the document still reports "Partially mitigated — items 3–4 remain open" and states that
no token, shared secret, or client identity is required. Per-agent `proxy.auth` identities with path
globs shipped in #204 and are documented on the website's security page, but not here.

**Fix:** add a section on `proxy.auth` identities and path-glob authorization and update the status,
or mark the document as a dated finding.

### 27. [x] Testing docs cite a nonexistent Gradle task and an incomplete spec list

**Severity:** medium · **Confidence:** confirmed

**Where:** `docs/TESTING.md:24,127-130,264-273,352,377,381`; `docs/KDOC_SUMMARY.md:72-84,143`.

**Problem:** `koverMergedHtmlReport` appears three times, but kover 0.9.9 has no merged tasks
(`koverHtmlReport` is correct). TESTING.md says its categories name every spec but omits ten
(`AgentTokenClientInterceptorTest`, `AgentAuthManagerTest`, `AgentAuthServerInterceptorTest`,
`ProxyEventBusTest`, `FileDiscoverySourceTest`, `PathDiscoveryServiceTest`, `MetricFilterTest`,
harness `AgentTokenAuthTest`, `ProxyDashboardHtmlTest`, `ProxySnapshotTest`), places
`HarnessConfig`/`HarnessConstants` under `harness/support/` instead of `harness/`, lists only the
gRPC stubs as coverage exclusions, and shows a JUnit `@Test fun` example that contradicts the Kotest
convention. KDOC_SUMMARY.md links `docs/TESTING.md` relative to `docs/` (a broken link) and carries
stale test-file counts.

**Fix:** correct the task name, inventory, locations, exclusions, and example; fix the link and drop
or refresh the counts.

### 28. [x] CHANGELOG and RELEASE_NOTES are behind; release checklist misses version literals

**Severity:** medium · **Confidence:** confirmed

**Where:** `CHANGELOG.md`, `RELEASE_NOTES.md`, `docs/RELEASE.md:10`,
`.claude/skills/publishing-release/SKILL.md:18`.

**Problem:** there are ten commits since tag `4.0.1` — including #236 and the three bug fixes in
`9fb1c88` — and neither file has changed, with no `[Unreleased]` section. The two files (~1,100 and
~850 lines) track the same releases in parallel. The version-bump checklist names only `README.md`
and `llms.txt`, but `4.0.1` is also hard-coded in the website's `getting-started.md`, `index.md`, and
`docker.md`, and in `etc/compose/proxy.yml`.

**Fix:** add an `[Unreleased]` section updated per PR; keep one detailed log and make the other a
short highlights file; list every version literal in the checklist or template them in the site.

**Resolution:** both logs now have an `Unreleased` section, and the release checklists list every
version literal. The maintainer chose to keep both logs as they are (2026-09-13): `CHANGELOG.md` as
the categorized record and `RELEASE_NOTES.md` as the narrative notes.

### 29. [ ] Metrics doc and CLAUDE.md drift

**Severity:** low · **Confidence:** confirmed

**Where:** `docs/metrics-and-grafana.md:135-166`; `CLAUDE.md` (Test Structure) vs
`src/test/kotlin/io/prometheus/containers/support/ContainerTestSupport.kt:115`.

**Problem:** the metrics table omits `agent_filter_lines_dropped` and `agent_filter_bytes_saved`,
which `AgentMetrics.kt` registers and the website's monitoring page documents. CLAUDE.md says the
container tests use an `nginx:1.29-alpine` stub, but the code uses the floating `nginx:alpine` tag,
which also makes container runs non-reproducible.

**Fix:** add the two metrics; pin the nginx tag in code (preferred) or correct CLAUDE.md.

---

## 🧪 Tests

### 30. [ ] Discovery and chunk-failure paths are untested

**Severity:** medium · **Confidence:** confirmed

**Where:** `PathDiscoveryServiceTest.kt`, `AgentPathManagerTest.kt`,
`ProxyServiceImplTest.kt:116,153,617,1368-1412`.

**Problem:** the discovery tests call only `reconcileOnce()`; `run()` and its early exit during the
sliced sleep are never exercised, and no test makes a register or unregister throw part-way through a
reconcile to confirm the remaining paths still reconcile. The chunk-failure tests verify only
`failScrapeRequest(...)` and `verify { proxy.metrics(any()) }`, which cannot distinguish
`chunkValidationFailures` (chunk vs summary stage) from `chunkedTransfersAbandoned`, though dashboards
and alerts depend on those counters.

**Fix:** add failure-isolation and `run()`-exit tests for discovery; assert the specific counter and
label in the chunk-failure tests.

### 31. [ ] Timing-sensitive and vacuously passing tests

**Severity:** medium · **Confidence:** confirmed (flakiness itself suspected, not observed)

**Where:** `HttpClientCacheTest.kt:68-74,318-340`; `AgentContextCleanupServiceTest.kt:151,205,227,263`;
`AdminEmptyPathTest.kt:46`; `AdminNonDefaultPathTest.kt:50`; `harness/support/HarnessSetup.kt`;
`harness/HarnessConstants.kt`.

**Problem:**

- `HttpClientCacheTest` waits 300 ms twice against a 500 ms idle timeout and a 1 s max age with a
  100 ms sweeper; a CI stall of ~200 ms turns the assertion into a new client.
- `AgentContextCleanupServiceTest` uses `Thread.sleep(1500)` followed by `verify(exactly = 0)`, which
  passes even if the sweep thread never ran.
- Several specs hard-code ports (8098, 8099, 9505, 9562-9575, 10700) instead of using `TestPorts`.
- `waitForPortAvailable` logs a warning after 10 s and continues rather than failing.
- `HarnessConstants.localOrGitHub` silently fetches config from GitHub `master` when the local file
  is missing, which hides a renamed config.

**Fix:** inject a clock into `HttpClientCache`; wait on a sweep-count signal instead of sleeping; move
the ports into `TestPorts`; fail fast in both harness helpers.

### 32. [ ] Coverage gate checks totals only

**Severity:** low · **Confidence:** confirmed

**Where:** `build.gradle.kts:62-63` (`configureCoverage()`), `codecov.yml:17`.

**Problem:** the 95% line / 87% branch floors are real and enforced by `koverVerify` in CI, but they
apply to totals only, so a new file with no coverage fits inside the ~2–3 points of headroom. The 80%
Codecov patch target is `informational: true`, so it never blocks a PR.

**Fix:** make the patch target blocking, or add a per-class minimum rule in kover.

---

## 🧹 Repository hygiene

### 33. [ ] Tracked IDE state, point-in-time docs, and a dead local directory

**Severity:** low · **Confidence:** confirmed

**Where:** `.idea/`, `docs/`, repo root.

**Problem:**

- `.idea/misc.xml`, `.idea/kotlinc.xml`, and `.idea/inspectionProfiles/Project_Default.xml` are
  gitignored but still tracked, so the ignore rules do nothing for them. Personal plugin state is also
  tracked (`copilot.data.migration.*.xml`, `SweepConfig.xml`, `material_theme_project_new.xml`,
  `php.xml`).
- The point-in-time review and planning documents (`CODE_REVIEW_*`, `FEATURE_PROPOSALS_JULY_2026.md`,
  `docs/superpowers/`) sit alongside the living docs, and TESTING.md cites "(finding 6)", "(finding 1)",
  and "(item 28)" without naming which review.
- `kotlinx-rpc-stubs/` is an untracked, unreferenced local leftover containing only empty source
  directories and stale build output.

**Fix:** `git rm --cached` the ignored and personal `.idea` files; move dated documents to
`docs/archive/` and qualify the finding references; delete `kotlinx-rpc-stubs/` locally.

### 34. [ ] Minor build tidy-ups

**Severity:** low · **Confidence:** confirmed unless noted

**Where:** `build.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/ci.yml`.

**Problem:**

- `testImplementation(kotlin("test"))` (`build.gradle.kts:109`) is unused; nothing imports
  `kotlin.test`.
- `protoc = "3.25.3"` is commented "keep in sync with grpc", but grpc-protobuf 1.84.0 requires
  `protobuf-java` 3.25.9; `protobuf-kotlin` shares the stale version.
- `System.getenv` is read at configuration time (`build.gradle.kts:190,199`);
  `providers.environmentVariable` is the configuration-cache-friendly form.
- Coverage exclusions list both `grpc.*` and `grpc.**` (`build.gradle.kts:398-399`).
- `compileKotlin dependsOn generateProto` (`build.gradle.kts:216-218`) is likely redundant with the
  protobuf plugin's own wiring (suspected).
- CI tests only on JDK 17 while the Docker images run JRE 25.

**Fix:** remove the unused dependency and duplicate exclusion; bump `protoc`/`protobuf-kotlin` to
match grpc; switch to providers; add a JDK 25 CI leg.

---

## ✅ Appendix: checked and sound

- **Token authentication:** SHA-256 digests compared with `MessageDigest.isEqual`; empty and
  duplicate tokens rejected at startup; token values never logged; a missing header yields
  `UNAUTHENTICATED`; the interceptor covers all ten `ProxyService` RPCs.
- **Path matching:** globs are anchored and escape every non-alphanumeric character; multi-segment
  paths are rejected and the HTTP route matches a single segment, so there is no traversal.
- **Agent outbound requests:** the target comes only from the agent's own path map; query parameters
  are appended after `?`, so the host cannot change; Ktor 3.5.2 strips `Authorization` on
  cross-authority redirects; response bodies are capped.
- **Proxy payload limits:** unzip is size-limited (zip-bomb guard); chunked transfers have a size cap
  and byte-count checks; orphaned transfers are swept; agent labels cannot overwrite reserved
  service-discovery keys.
- **Proxy concurrency:** `removeAgentContext` ordering, `invalidate()` failing queued requests, the
  completion CAS that stops a late result overwriting an earlier one, and `pathMap` locking all held
  up under tracing.
- **Dashboard rendering:** kotlinx.html escapes all agent-supplied text; the asset route is an
  allowlist; WebSocket JSON parsing fails safe.
- **Defaults and packaging:** trust-all is off by default with a warning; admin, metrics, debug,
  dashboard, and service discovery default off; both images run as a non-root user on a
  digest-pinned base.
- **Coverage gate:** floors of 95% line / 87% branch against measured 97.4% / 90.4%, enforced in CI.
- **Recent moves:** references to `docs/DESIGN.md` and `examples/prom-agent.conf` are correct, and
  `4.0.1` matches across README.md, llms.txt, and the website.
