# Prometheus-Proxy Code Review — November 26, 2026

**Status:** 70 issues — 4 fixed, 66 open (4 high, 35 medium, 31 low).

**Progress:** the four high-severity findings, #1, #11, #12, and #44, are fixed in #586, from steps 1, 4, and 10, whose
other findings (#7, #38; #13, #31; #43) are still open.

**Date:** 2026-11-26

**Scope:** `master` at `74e56674`, the unreleased `4.7.1` (the last release is `4.7.0`, tagged `2026-11-24`). That covers the full repository: ~30.9k lines of main source (root proxy/agent/common/proto, `:ktor-plugin`, `:spring-boot-starter`), the test suite (unit, harness, Lincheck, container, hygiene guards, and subproject tests), TLA+ specs, Gradle build and `Makefile`, CI workflows and Dependabot, Dockerfiles, Helm charts, Grafana dashboards and alerts, all seven demos, the docs site, and maintainer/user documentation. All 72 issues from the November 23 review (`docs/archive/CODE_REVIEW_NOVEMBER_23_2026.md`) were fixed in PRs `#550–#568`; this review audits the entire codebase fresh, including those fixes and the 4.7.0/4.7.1 commits since (`#569–#583`). All file paths below are relative to the repository root.

---

## 📋 Summary

✅ = fixed · ⬜ = open

| #  | Finding | Area | Severity | Status |
|----|---------|------|----------|--------|
| 1  | `ChunkedContext` is unsynchronized and reachable across concurrent `writeChunkedResponsesToProxy` streams | Proxy | high | ✅ |
| 2  | Consolidated path takeover during mid-teardown inherits the dead previous identity's labels and retains invalidated contexts | Proxy | medium | ⬜ |
| 3  | Orphaned same-launch `AgentContext` in `settleDisplaced` is only invalidated, not removed, stranding scrapes/chunks and drifting from `ProxyRegistry.tla` | Proxy | medium | ⬜ |
| 4  | `removeFromPathManager` runs two full `O(total_paths)` scans under the global `pathMap` lock on every disconnect, including unauthenticated ones | Proxy | medium | ⬜ |
| 5  | `Proxy.shutDown()` stops sub-services without fault isolation, and `Proxy.startUp()` rollback omits `tracing?.close()` and `invalidateAllAgentContexts()` | Proxy | medium | ⬜ |
| 6  | Race between `AgentContextManager.announceAgentContext()` and `removeFromContextManager()` can announce a removed agent and diverge eviction/disconnect metrics | Proxy | low | ⬜ |
| 7  | `ScrapeDispatch.mergeContentTexts` emits invalid blank lines in OpenMetrics output when a consolidated agent returns only `# EOF` | Proxy | low | ⬜ |
| 8  | `AgentAuthManager` strips a leading `/` from incoming paths in `isAuthorized` but not from configured `proxy.auth[].paths` globs | Proxy | low | ⬜ |
| 9  | `ProxyOptions.sdPath` and `ProxyPathManager.reservedPathRejection` disagree on multiple leading slashes and whitespace-only paths | Proxy | low | ⬜ |
| 10 | `ProxyServiceImpl.unregisterPath` throws unhandled `IllegalArgumentException` on blank `path`, and proxy `ActivityLog` duplicates repeated paths in a burst | Proxy | low | ⬜ |
| 11 | Double-slash path stripping (`//foo`, `//`) causes permanent 404s for static paths, infinite discovery flapping + static-path clobbering, and a phantom `metricsProxyPath` | Agent | high | ✅ |
| 12 | Reclaim loop tight-spins with zero delay (unbounded gRPC flood) when `rejectedPathRetryMaxSecs == 1`, and fires every reclaim 1s early otherwise | Agent | high | ✅ |
| 13 | Connection stays stuck forever serving zero paths (no reconnect or failover) when a taken-over static path is permanently rejected on reclaim | Agent | medium | ⬜ |
| 14 | Concurrent overlapping scrapes of the same `MetricsSource` defeat `AgentHttpService.stuckSources` protection | Agent | medium | ⬜ |
| 15 | `writeResponsesToProxyUntilDisconnected` can drop `RESULT_DROPPED` metrics on early writer exit, loses `endedBy` on stream failure, and doesn't tear down on early normal completion | Agent | medium | ⬜ |
| 16 | Fatal JVM `Error` in `Agent.run()` blocks for `reconnectPauseSecs` and logs `"Waited ... to reconnect"` before terminating | Agent | medium | ⬜ |
| 17 | TLS context build errors (`SSLException`) and unreadable TLS files (`FileNotFoundException`) bypass `failOnInvalidConfig` | Agent | medium | ⬜ |
| 18 | `MetricFilter.previewBytes` mutates fail-open latches, logs warnings, and records `FILTER_FAILED_OPEN` during dashboard "Scrape now" previews | Agent | low | ⬜ |
| 19 | `Utils.parseHostPort` accepts empty hosts (`":50051"`, `"[]:50051"`, `"[]"`) and silently discards trailing garbage after bracketed IPv6 (`"[::1]50051"`) | Agent | low | ⬜ |
| 20 | `MetricFilter.quotedSampleName` misses Prometheus 3 UTF-8 samples with whitespace after `{` | Agent | low | ⬜ |
| 21 | `EmbeddedAgentInfo.shutdown()` / `close()` and `Agent.stop()` sneaky-throw undeclared checked `TimeoutException` (Java interop hazard) | Embedding | medium | ⬜ |
| 22 | `EmbeddedAgentConfig.Builder.path()` and `scrapeSelf()` accept `"/"`-only paths and blank URLs, silently dropping targets instead of failing validation | Embedding | medium | ⬜ |
| 23 | `EnvironmentSetting.check()` validates and fails on `fromEnvironment()` values overridden by an earlier `setting()` call | Embedding | medium | ⬜ |
| 24 | Ktor plugin `PrometheusProxyAgentConfig` eagerly parses `path` and `labels` in its constructor, crashing `install()` even when `enabled = false` or `required = false` | Embedding | medium | ⬜ |
| 25 | Ktor plugin `AgentLifecycle` has `beginStop()`/`finishStop()` and `start()`/`beginStop()` races plus unguarded `Thread.join()` throwing checked `InterruptedException` | Embedding | medium | ⬜ |
| 26 | Spring Boot starter's default `metricsPath()` uses `spring.application.name` verbatim, failing startup when the app name contains spaces or disallowed path characters | Embedding | low | ⬜ |
| 27 | `EmbeddedAgentInfo` leaks internal `STOP_THREAD_NAME` constant as a `public static final` JVM field | Embedding | low | ⬜ |
| 28 | `/screens` Split layout ignores `first`/`second` pane ordering at `<= 720px`, breaking pane swaps and `#later+earlier` links | Dashboards | medium | ⬜ |
| 29 | `DashboardScript.scrapeNow` lacks an in-flight guard, leaves `#scrape-result` uncloseable on network/non-panel errors, and writes arbitrary error bodies via `innerHTML` | Dashboards | medium | ⬜ |
| 30 | Theme toggle on `/screens` and its same-origin Proxy Dashboard iframe go out of sync | Dashboards | medium | ⬜ |
| 31 | `AgentScrapeNow` strips all leading slashes (`trimStart('/')`) while `AgentSnapshot`/`AgentPathManager` strip only one, breaking Scrape now for `//...`/`///...` paths | Dashboards | low | ⬜ |
| 32 | `ScreensScript.splitPanes` misparses single-pane search links as Split layouts when a query term after `+` matches a screen ID | Dashboards | low | ⬜ |
| 33 | `GET <base>/login` ignores `?next=...` when the viewer already has a valid session | Dashboards | low | ⬜ |
| 34 | `DashboardSessions.isValid(..., counts = false)` mutates LRU access order on every push tick | Dashboards | low | ⬜ |
| 35 | `DashboardServer.render` never resets `session.renderFailed` on recovery and logs nothing on repeated render errors | Dashboards | low | ⬜ |
| 36 | Toggling "Problems only" during the search input debounce window causes a duplicate WebSocket announce and screen-reader announcement | Dashboards | low | ⬜ |
| 37 | Incomplete URL userinfo redaction leaks passwords containing or following `@` in `Utils.sanitizeUrl` and Spring Boot's `PrometheusProxyAgentProperties.shownUrl` | Security | medium | ⬜ |
| 38 | Unary `ScrapeResponse` lacks header-field caps, `parseContentType` passes multi-MB and control-character parameters, `httpStatus` emits arbitrary 32-bit codes, and plain bodies bypass `maxUnzippedContentSizeMBytes` | Security | medium | ⬜ |
| 39 | Dashboard `isOriginAllowed()` ignores URL scheme and bypasses explicit `allowedOrigins` restrictions | Security | medium | ⬜ |
| 40 | Unthrottled WebSocket per-frame full-HTML re-render amplification on dashboard `/events` | Security | medium | ⬜ |
| 41 | Dashboard browser Basic-Auth restriction (`isBrowser`) is bypassed over plain HTTP on non-localhost hosts | Security | low | ⬜ |
| 42 | Unescaped/unsanitized inputs in log output (`ProxyHttpConfig`, `MetricFilter`, and local config parse errors in `BaseOptions`) | Security | low | ⬜ |
| 43 | Helm charts mount the default Kubernetes ServiceAccount token unnecessarily (`automountServiceAccountToken: false` omitted) | Security | low | ⬜ |
| 44 | Helm charts crash with nil pointer on `helm upgrade --reuse-values` from 4.6.0 (`dashboard` / `dashboard.login`) | CI/build | high | ✅ |
| 45 | Inverted severity thresholds in `grafana/prometheus-agents.json` (Panels 27 "Not on Primary" and 38 "Since Last Discovery") | CI/build | medium | ⬜ |
| 46 | `ProxyScrapeSuccessRateLow` alert and Proxy Grafana Panel 4 silently miss 0% success rate when no scrape has succeeded since startup | CI/build | medium | ⬜ |
| 47 | `etc/consumer-check/check.sh` silently skips the last JAR on the Maven classpath | CI/build | medium | ⬜ |
| 48 | `docs.yml/build` is omitted from `RELEASE_REQUIRED_JOBS` in `Makefile` despite comments and docs stating `make release-check` requires it | CI/build | medium | ⬜ |
| 49 | `build.gradle.kts` `checkedFiles()` omits `charts/` and `.claude/skills/publishing-release/SKILL.md` (and doesn't exclude `demos/**/__pycache__/**`) | CI/build | medium | ⬜ |
| 50 | `demos/nginx/bin/screenshots.sh` `shows_reconnecting` uses stale 4.7.0 top-bar pixel coordinates after 4.7.1's 1-button theme switcher | CI/build | low | ⬜ |
| 51 | Dead color overrides in `demos/network/grafana/dashboards/network-overview.json` (Panels 14 and 18) | CI/build | low | ⬜ |
| 52 | Minor build/script drift (`Makefile` `.PHONY`, unused `NEXT_AGENT_DASHBOARD_URL` and stale comment in `demos/ktor/demo.sh`, stale port/binding/URL in `bin/docker-*.sh`) | CI/build | low | ⬜ |
| 53 | `ProxyPathManagerLincheckTest` externally serializes all `ProxyPathManager` mutations behind `pathLock` and omits `maxPathsPerIdentity` | Tests | medium | ⬜ |
| 54 | Tautological `Bug #1` test in `AgentTest` tests a local copy-pasted `Semaphore` instead of `Agent.processScrapeRequests` | Tests | medium | ⬜ |
| 55 | Leaked Logback logger level mutation in `BaseOptionsTest` contaminates subsequent specs in `unitTest` | Tests | medium | ⬜ |
| 56 | Five container specs leak Docker containers, networks, and HTTP clients when `beforeSpec` fails partway through | Tests | medium | ⬜ |
| 57 | Hygiene guard blind spots in `DemoScreensLinksTest` (hardcoded `DEMOS`) and `ConfigFileKeysTest` (`listEntrySchemas` omitting `links`/`screens`) | Tests | low | ⬜ |
| 58 | Weak timeout assertion and untested `stopTimeout == null` branch in Ktor `AgentLifecycleTest` (plus missing `finally` in `EmbeddedAgentInfoTest`) | Tests | low | ⬜ |
| 59 | Vacuous JSON assertion in `UtilsTest` for `Utils.getVersionDesc(true)` | Tests | low | ⬜ |
| 60 | `README.md` omits 4.7.1's `/screens` page and default-open framing behavior change from "New Features", and omits `/screens` (and `SAME_ORIGIN`) from "Operational Dashboard" | Docs | medium | ⬜ |
| 61 | `website/prometheus-proxy/docs/embedded-upgrading.md` is missing an "Upgrading from 4.7.0" section for 4.7.1's behavior changes affecting embedders | Docs | medium | ⬜ |
| 62 | `docs/PRODUCT.md` has a factual error on proxy Scrape-now metrics and multiple pre-4.7.1 stale statements | Docs | medium | ⬜ |
| 63 | `docs/DESIGN.md` contradicts itself and the actual CSS on the Status Bar layout above 720px and on border-radius rules | Docs | medium | ⬜ |
| 64 | `demos/network/README.md` has a stale `make open` description (and omits `screens` on `proxy-standby.conf`), and `demos/basics/README.md` omits `make open` from Scenario Reference | Docs | medium | ⬜ |
| 65 | `demos/spring-boot/README.md` duplicates and contradicts its `make up` explanation in Quick Start | Docs | low | ⬜ |
| 66 | `website/prometheus-proxy/docs/embedded-agent.md` omits `stopTimeout` from the "Every Setting, Every Way" cross-reference table | Docs | low | ⬜ |
| 67 | `website/prometheus-proxy/docs/configuration/agent.md` omits `agent.grpc.*`, `agent.admin.*`, and `agent.internal.*` (leaving `UNARY_DEADLINE_SECS` off both configuration pages) | Docs | low | ⬜ |
| 68 | Public API KDoc / Dokka documentation inaccuracies (`docs/KDOC_SUMMARY.md`, `docs/packages.md`, `EmbeddedAgentInfo.kt`, `EmbeddedAgentConfig.kt`, `BaseOptions.kt`) | Docs | low | ⬜ |
| 69 | `website/prometheus-proxy/docs/javascripts/screenshots.js` can throw on `Tab` if no enabled lightbox button exists, and `zensical.toml` has a stale GLightbox CDN comment | Docs | low | ⬜ |
| 70 | `website/prometheus-proxy/docs/troubleshooting.md` startup-rejection list omits 4.7.1's `proxy.dashboard.screens` / `proxy.dashboard.path = "/screens"` rejections | Docs | low | ⬜ |

---

## 🧭 Headline

The codebase is remarkably disciplined — all 72 fixes from November 23 held up cleanly, and the core concurrency invariants, TLA+ models, constant-time auth, linear-time globs, and dashboard constraints are well-engineered. Even so, an exhaustive full-repo pass found **4 high-severity** and **35 medium-severity** issues clustering in five areas:

- **Four high-severity runtime/upgrade defects:**
  1. **`ChunkedContext` stream-level race (#1):** `ChunkedResponseReader.owns()` checks connection/identity ownership rather than stream ownership, so two concurrent `writeChunkedResponsesToProxy` streams on one connection (or one auth identity under `transportFilterDisabled`) mutate the same unsynchronized `ChunkedContext` (`CRC32`, byte counts, `ByteArrayOutputStream`), risking payload corruption and `maxZippedContentSizeMBytes` bypass.
  2. **Double-slash path stripping across boundaries (#11):** `AgentPathManager` and `ProxyPathManager` each strip a single leading `/`, so `path = "//foo"` registers on the proxy as `"foo"` while the agent keys it under `"/foo"` (permanent 404 on every scrape), and in dynamic discovery causes infinite unregister/re-register flapping and static-path clobbering.
  3. **Zero-delay reclaim tight-spin (#12):** `reclaimTakenOverPathsWhile` subtracts `RECLAIM_WINDOW` (`1s`) before sleeping. With the valid configuration `rejectedPathRetryMaxSecs = 1`, `wait <= 0` so the loop never suspends and floods the proxy with back-to-back `registerPath` RPCs at full speed.
  4. **Helm `helm upgrade --reuse-values` crash from 4.6.0 (#44):** Confirmed with `helm template`: upgrading either chart from 4.6.0 with `--reuse-values` fails immediately with nil-pointer template errors on `.Values.dashboard.login.existingSecret` (both charts) and `.Values.dashboard.enabled` / `.Values.dashboard.port` (agent chart), breaking the exact upgrade command documented in `charts/prometheus-agent/README.md`.
- **Proxy/agent edge-case isolation & security boundaries:** Unary `ScrapeResponse` lacks header-field bounds, `parseContentType` passes multi-MB or `\r\n` parameters through, `httpStatus` emits arbitrary 32-bit status codes, and uncompressed bodies bypass `maxUnzippedContentSizeMBytes` (#38). `removeFromPathManager` runs two full `O(total_paths)` scans under `synchronized(pathMap)` on every disconnect including unauthenticated port probes (#4). Consolidated mid-teardown takeover can permanently poison `labels`/`sdLabels` (#2), and orphaned same-launch contexts strand in-flight scrapes/chunks (#3). `Utils.sanitizeUrl` and Spring Boot's `shownUrl` both leak passwords containing or following `@` (#37).
- **New 4.7.1 embedding & integration surfaces:** `EmbeddedAgentInfo.shutdown()`/`close()` sneaky-throw checked `TimeoutException` to Java callers (#21); `setting()` called before `fromEnvironment()` still fails on the shadowed env var (#23); Ktor's `PrometheusProxyAgentConfig` eagerly parses `path`/`labels` in its constructor (#24) and `AgentLifecycle` has stop/start races and unguarded `Thread.join()` (#25); overlapping scrapes defeat `MetricsSource` stuck-call protection (#14).
- **New 4.7.1 `/screens` page & dashboard hardening:** Split layout loses pane order at `<= 720px` (#28), `/screens` and its same-origin proxy iframe theme toggles go out of sync (#30), `scrapeNow` lacks request-sequence guards and close buttons on errors (#29), `isOriginAllowed` ignores scheme and bypasses explicit `allowedOrigins` (#39), and WebSocket `/events` re-renders full HTML synchronously on every incoming text frame (#40).
- **Release gates, monitoring & test blind spots:** `ProxyPathManagerLincheckTest` externally serializes every `ProxyPathManager` mutation behind `pathLock` (#53); `AgentTest`'s Bug #1 test exercises a local copy-pasted loop instead of `Agent.processScrapeRequests` (#54); `BaseOptionsTest` leaks `io.prometheus=DEBUG` across `unitTest` (#55); `docs.yml/build` is missing from `RELEASE_REQUIRED_JOBS` (#48); `consumer-check/check.sh` silently skips the last classpath JAR (#47); Grafana's Agents dashboard has two inverted red/green panels (#45) and `ProxyScrapeSuccessRateLow` misses 0%-success outages from startup (#46); and `README.md`, `embedded-upgrading.md`, `PRODUCT.md`, and `DESIGN.md` have 4.7.1 drift (#60–#64).

---

## 🗺️ Plan: Order of Fixing

Each step is sized for one focused PR. Steps in the same wave touch disjoint files and can land in parallel.

| Step | Issues | PR Theme | Touches | Why Here |
|------|--------|----------|---------|----------|
| 1 | #1, #7, #38 | Stream-scoped `ChunkedContext` and hardened unary/consolidated scrape responses | `ChunkedContext.kt`, `ProxyServiceImpl.kt`, `ScrapeDispatch.kt`, `ProxyUtils.kt` | Fixes High #1 and Security #38 together on the scrape response boundary. |
| 2 | #2, #3, #4, #6, #8, #9, #10 | Proxy path manager takeover, teardown, scaling & validation fixes | `ProxyPathManager.kt`, `Proxy.kt` (orphan removal), `AgentContext.kt`, `AgentContextManager.kt`, `AgentContextCleanupService.kt`, `AgentAuthManager.kt`, `ProxyOptions.kt`, `ActivityLog.kt`, `specs/tla/ProxyRegistry.tla` | Cohesive proxy state-machine pass; run `make tla-checks-full`. |
| 3 | #5 | Fault-isolated `Proxy.shutDown()` and complete `Proxy.startUp()` rollback | `Proxy.kt` (`startUp`, `shutDown`) | Separate from Step 2 (or rebased after it) so lifecycle teardown is clean and isolated. |
| 4 | #11, #12, #13, #31 | Single-segment path normalization and reclaim-loop correctness | `AgentPathManager.kt`, `AgentOptions.kt`, `Agent.kt` (reclaim task), `AgentScrapeNow.kt` | Fixes High #11 and High #12 plus the matching zero-path reclaim stall and `AgentScrapeNow` mismatch. |
| 5 | #14, #15, #16, #17, #18, #19, #20 | Agent stream teardown, `stuckSources` refcount, fatal-error exit, TLS & filter fixes | `AgentHttpService.kt`, `AgentGrpcService.kt`, `Agent.kt` (`run`), `TlsMaterial.kt`, `MetricFilter.kt`, `Utils.kt` (`parseHostPort`) | Core agent runtime hygiene; after Step 4 (shared `Agent.kt`). |
| 6 | #37, #42 | Complete URL userinfo redaction and log-input sanitization | `Utils.kt` (`USERINFO_REGEX`), `PrometheusProxyAgentProperties.java`, `ProxyHttpConfig.kt`, `MetricFilter.kt` (`shownValue`), `BaseOptions.kt` | Cross-cutting credential/log hardening; after Step 5 (`Utils.kt`, `MetricFilter.kt`). |
| 7 | #21, #22, #23, #24, #25, #26, #27 | Embedding API, Ktor plugin, and Spring Boot starter correctness & Java interop | `EmbeddedAgentInfo.kt`, `EmbeddedAgentConfig.kt`, `Agent.kt` (`stop`), `ktor-plugin/src/main/**`, `spring-boot-starter/.../PrometheusProxyAgent.java` | Fixes all 4.7.1 embedding/integration issues before tagging 4.7.1. |
| 8 | #33, #34, #35, #39, #40, #41 | Dashboard server security, session LRU, and WebSocket throttling | `DashboardServer.kt`, `DashboardSessions.kt` | Closes scheme-blind origin bypass (#39) and WS render amplification (#40). |
| 9 | #28, #29, #30, #32, #36 | `/screens` narrow Split ordering, theme sync, and `DashboardScript` hardening | `ScreensCss.kt`, `ScreensScript.kt`, `DashboardScript.kt`, `ThemeScript.kt` | Client-side JS/CSS fixes for 4.7.1's `/screens` and theme button; disjoint from Step 8. |
| 10 | #43, #44 | Helm chart 4.6.0 `--reuse-values` compatibility and SA token hardening | `charts/prometheus-{proxy,agent}/templates/**`, `charts/*/values.yaml`, `Makefile` (`helm-lint`) | Fixes High #44 before 4.7.1 release; independent of Kotlin/Java code. |
| 11 | #45, #46, #51 | Grafana dashboard thresholds, dead overrides, and 0%-success alert fix | `grafana/prometheus-agents.json`, `grafana/alerts.yml`, `grafana/prometheus-proxy.json`, `demos/network/grafana/dashboards/network-overview.json` | Monitoring assets only; completely independent. |
| 12 | #47, #48, #49, #50, #52 | CI/release checks, Gradle test inputs, `consumer-check`, and scripts | `etc/consumer-check/check.sh`, `Makefile`, `build.gradle.kts`, `demos/nginx/bin/screenshots.sh`, `demos/ktor/demo.sh`, `bin/docker-*.sh`, `docs/TESTING.md` | Release-gate and build-cache reliability before 4.7.1 release. |
| 13 | #53, #54, #55, #56, #57, #58, #59 | Test suite concurrency fidelity, isolation, container cleanup & guards | `ProxyPathManagerLincheckTest.kt`, `AgentTest.kt`, `BaseOptionsTest.kt`, `UtilsTest.kt`, 5 `containers/*Test.kt`, `DemoScreensLinksTest.kt`, `ConfigFileKeysTest.kt`, `AgentLifecycleTest.kt`, `EmbeddedAgentInfoTest.kt` | Strengthens tests and hygiene guards; after Steps 2 and 7 for `ProxyPathManager` / `AgentLifecycle` hooks. |
| 14 | #60, #61, #64, #65, #66, #67, #68, #69, #70 | 4.7.1 user docs, website, demo READMEs, and public API KDocs | `README.md`, `website/prometheus-proxy/**`, `demos/{network,basics,spring-boot}/README.md`, `docs/{packages,KDOC_SUMMARY}.md`, public KDocs | Before 4.7.1 tag (since the docs site deploys on release); after Step 7 for `shutdown()` KDoc. |
| 15 | #62, #63 | Maintainer product and design docs | `docs/PRODUCT.md`, `docs/DESIGN.md` | Last, after Step 9 settles any dashboard CSS/behavior changes. |

### Parallel Waves

- **Wave A (no dependencies):** Steps `1`, `2`, `4`, `8`, `9`, `10`, `11`, `12`
- **Wave B:** Steps `3` (after 2), `5` (after 4), `7` (after 5 if sharing `Agent.kt`)
- **Wave C:** Steps `6` (after 5), `13` (after 2 & 7), `14` (after 7), `15` (after 9)

---

## 🔬 How This Review Was Done

**Process:** Eight read-only deep-dive audit tracks covered the entire repository at `74e56674`, followed by direct source verification of every high, medium, and cross-cutting finding and live reproductions where applicable:

1. **Proxy core & TLA+ specs:** `Proxy.kt`, `proxy_service.proto`, all 30 non-dashboard files under `src/main/kotlin/io/prometheus/proxy/`, and `specs/tla/ProxyRegistry*`.
2. **Agent core & common shared code:** `Agent.kt`, all 21 non-dashboard/non-embedding files under `src/main/kotlin/io/prometheus/agent/` (including `discovery/` and `filter/`), all 18 non-dashboard files under `src/main/kotlin/io/prometheus/common/`, and `specs/tla/AgentFailover*`.
3. **Embedding API, Spring Boot starter, Ktor plugin & consumer surface:** `EmbeddedAgentConfig`, `EmbeddedAgentInfo`, `EnvironmentSetting`, `MetricsSource`, `RegistryMetricsSource`, `RuntimeLibraries`, all files in `ktor-plugin/` and `spring-boot-starter/`, `etc/consumer-check/`, `src/shadow/`, and published POM/Gradle module metadata.
4. **Dashboards & `/screens`:** all 45 Kotlin files across `common/dashboard/`, `proxy/dashboard/`, and `agent/dashboard/`, checked against `.claude/rules/dashboard-constraints.md`, `docs/DESIGN.md`, and `docs/PRODUCT.md`.
5. **Cross-cutting security & threat model:** gRPC/HTTP boundaries, auth/globs, `transportFilterDisabled`, input validation/log escaping, SSRF/redirects, TLS/secret redaction, dashboard/screens XSS/CSRF/CSWSH/clickjacking/open-redirect/session security, DoS amplification, Dockerfiles, Helm charts, and CI workflows.
6. **Build, CI/CD, packaging, Docker, Helm charts, demos & scripts:** Gradle builds, `Makefile`, all 8 GitHub Actions workflows, Dependabot, `config/config.conf` vs `tscfg` output (`ConfigVals.java`), `etc/`, `charts/`, `grafana/`, `examples/`, `bin/`, `scripts/`, and all 7 `demos/`.
7. **Test suite quality & hygiene guards:** all root unit, harness, Lincheck, container, and `misc/` specs, plus `:ktor-plugin` and `:spring-boot-starter` tests and `docs/TESTING.md`.
8. **Documentation, website, config reference, KDocs & changelogs:** `README.md`, `AGENTS.md`, `llms.txt`, `SECURITY.md`, `CHANGELOG.md`, `RELEASE_NOTES.md`, `docs/*.md`, all 35 pages and JS/CSS assets under `website/prometheus-proxy/`, all demo/chart READMEs, and public KDocs.

**Direct verifications performed:**

- **#44 (Helm crash):** Reproduced live with `helm template test charts/prometheus-proxy --set dashboard.login=null` and `helm template test charts/prometheus-agent --set proxy.hostname=proxy:50051 --set dashboard.enabled=true --set dashboard.port=null --set dashboard.login=null --set dashboard.host=null`, verified against `git show 4.6.0:charts/*/values.yaml`.
- **#21, #27 (`EmbeddedAgentInfo` bytecode):** Verified with `javap` on compiled classes (no `throws TimeoutException` clause; `STOP_THREAD_NAME` emitted as `public static final`).
- **#47 (`consumer-check/check.sh`):** Verified `maven-dependency-plugin-3.11.0.jar` (`BuildClasspathMojo.storeClasspathFile`) writes classpath with no trailing newline.
- **#24, #25, #37, #38, #56 (library behavior):** Verified directly against bytecode/sources of Ktor 3.6.1 (`HoconApplicationConfig`, `Events.raise`, `URLParser`, `HttpHeaderValueParser`, `ContentType.match`, `HttpStatusCode.fromValue`, `CIOApplicationResponse`), Kotest 6.2.7 (`BeforeAfterSpecCallbacksInterceptor`), Guava 33.8.0 (`Uninterruptibles`), Prometheus client 1.10.0, and Spring Boot 4.2.0.
- **Config schema & shell scripts:** Verified `tscfg-1.2.5.jar` regeneration of `ConfigVals.java`, ran `shellcheck -S warning` across all shell scripts, and verified internal links/anchors and Mermaid `linkStyle` indices across the built docs site (`website/prometheus-proxy/site/`).

**Confidence levels:**
- **confirmed:** directly traced in this repo's source (and dependency bytecode where relevant) or reproduced live.
- **plausible:** structural defect confirmed in source, while live trigger depends on timing or next capture run (only #50).

---

## 📡 Proxy

### 1. ✅ `ChunkedContext` is unsynchronized and reachable across concurrent `writeChunkedResponsesToProxy` streams

**Severity:** high · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ChunkedContext.kt:40-113`
- `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:681-700, 723-726, 803-880`

**Problem:**
`ProxyServiceImpl.ChunkedResponseReader` documents (`ProxyServiceImpl.kt:684-688`) that `activeScrapeIds` is confined to a single client-streaming RPC coroutine, while `chunkedContextMapView` is a shared `ConcurrentHashMap<Long, ChunkedContext>`. However, `ChunkedContext` itself has no synchronization (`CRC32` is not thread-safe; `totalChunkCount` and `totalByteCount` are plain non-volatile fields updated via non-atomic `++` / `+=`; the `maxZippedContentSize` check + `checksum.update` + `baos.write` sequence is an unguarded critical section), and `owns(context, scrapeId)` (`ProxyServiceImpl.kt:723-726`) checks only connection/identity ownership (`isScrapeOwnedByConnection(connectionAgentId, context.owner, scrapeId, RPC_NAME)`), **not** whether the current stream created `context`.

Because HTTP/2 gRPC permits multiple concurrent `writeChunkedResponsesToProxy` streams on one connection (or across two agents sharing an auth identity when `transportFilterDisabled = true`):
1. Stream 1 calls `readHeader(scrapeId)` and inserts `context` into `chunkedContextMapView[scrapeId]`.
2. Stream 1 and Stream 2 concurrently call `readChunk(scrapeId)` and/or `readSummary(scrapeId)`; both pass `owns(context, scrapeId)`.
3. Both coroutines invoke `context.applyChunk(...)` / `context.applySummary(...)` simultaneously on the same `ChunkedContext`. Even after `readSummary` removes `context` from `chunkedContextMapView` (`ProxyServiceImpl.kt:849`), a racing `readChunk` that already looked up `context` on line 806 can still mutate `checksum` and `baos` concurrently with `baos.toByteArray()`.

**Impact:** Data races on `CRC32`, `totalChunkCount`, `totalByteCount`, and `ByteArrayOutputStream` — causing lost updates to `totalByteCount` (potentially bypassing `maxZippedContentSizeMBytes`), corrupted checksums, torn gzipped payloads delivered to Prometheus, or spurious `ChunkValidationException`s. Stream 2 can also finish or fail Stream 1's transfer while leaving `scrapeId` stale in Stream 1's `activeScrapeIds`.

**Fix:** Pass an opaque stream-owner token (`val stream: Any = this`) from `ChunkedResponseReader` into `ChunkedContext`, and require `context.stream === this` inside `owns(...)` so only the stream that opened a transfer can extend or finish it. Optionally annotate `applyChunk` and `applySummary` with `@Synchronized` as defense-in-depth.

**Resolution:** Fixed in #586. `ChunkedResponseReader` keeps the transfers its stream opened, by scrapeId, where it kept their IDs, and `owns` requires the transfer to be the one the stream opened; the connection's check, which that implies, now only decides how data the stream may not touch is logged: another agent's is logged and counted as before. The end-of-stream sweep removes only the stream's own transfers. Another stream's chunk or summary is ignored, with a WARN once per transfer, and isn't counted in `proxy_binding_mismatches_total`, which is for calls naming another agent or identity. That map serves as the suggested stream token without changing `ChunkedContext`'s constructor; `ChunkedContext` isn't `@Synchronized`, as only its stream touches it, which its KDoc now says. A `ProxyServiceImplTest` case opens a transfer on one stream and sends a valid chunk and summary for it on a second stream of the same connection. It failed before the fix, with the second stream's data completing the scrape. Seven existing cases had a mocked manager hand a stream a transfer it never opened, or checked the sweep's removal by scrapeId alone, so they now run on a spy of the real manager.

---

### 2. ⬜ Consolidated path takeover during mid-teardown inherits the dead previous identity's `labels`/`sdLabels` and retains invalidated `AgentContext`s

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:488-491, 575-581, 627-645, 872-875`
- `src/main/kotlin/io/prometheus/Proxy.kt:531-532`

**Problem:**
In `Proxy.removeAgentContext`, `removeFromContextManager` invalidates the departing `AgentContext` outside `synchronized(pathMap)` before `removeFromPathManager` acquires `pathMap`. `ProxyPathManager.conflictRejection` (`ProxyPathManager.kt:575-577`) explicitly allows a new agent of a **different** auth identity to take a path whose existing agents are all invalidated (`!otherAgentLive`).

For a consolidated path `/p`:
1. `A1` (consolidated, identity `team-a`, labels `{"team":"a"}`) registers `/p`.
2. `A1` disconnects: `removeFromContextManager` flips `A1.valid = false`, before `removeFromPathManager` acquires `synchronized(pathMap)`.
3. `B1` (consolidated, identity `team-b` — or a redeploy of `team-a` with updated labels `{"team":"b"}`) calls `addPath("p", ..., B1)`:
   - `conflictRejection` sees `otherAgentLive == false` and allows it.
   - `addValidatedPath` (`ProxyPathManager.kt:490`) runs `pathMap[path] = agentInfo?.joinedBy(agentContext, entry) ?: entry`.
   - `joinedBy` (`ProxyPathManager.kt:631-645`) appends `B1` to `[A1]` (`agentContexts = [A1, B1]`), overwrites `identityName = "team-b"`, merges `agentTargets`, and **keeps dead `A1`'s `labels` and `sdLabels`, discarding `B1`'s**.
4. Until `A1`'s `removeFromPathManager` runs, `A1` remains first in `agentContexts`: `showingFirstAgent()` retains `A1`'s `targetUrl`/`pathSource`, service discovery advertises both agents, and scrapes fan out to dead `A1` (generating spurious `agent_disconnected` metrics/records).
5. When `A1`'s `removeFromPathManager` runs `dropAgentFromPath` (`ProxyPathManager.kt:873-874`), `remaining` is `[B1]`, so `A1` is dropped from `agentContexts`/`agentTargets`, **leaving `team-a`'s `labels` and `sdLabels` permanently attached to `team-b`'s path `/p`**.

(`ProxyRegistry.tla` does not model `labels`/`sdLabels` and guards `IdentityIsolation` with `valid[c] =>`, so TLC does not flag this.)

**Impact:** Permanent cross-identity label poisoning in HTTP service discovery, dashboard, and `/debug` (or silent loss of updated labels on a consolidated redeploy), plus transient failed scrapes dispatched to an already-invalidated agent.

**Fix:** In `addValidatedPath` (`ProxyPathManager.kt:489-491`), before joining an existing consolidated `agentInfo`, filter out already-invalidated `AgentContext`s from `agentInfo` and decrement their path counts via `countPaths(dead.agentId, -1)` (keeping `PathCountsAgree` intact). If no live contexts remain, store `entry` directly so `B1` starts clean with its own `labels`, `sdLabels`, `targetUrl`, `pathSource`, and `[B1]`.

---

### 3. ⬜ Orphaned same-launch `AgentContext` in `settleDisplaced` is only invalidated, not removed — stranding in-flight scrapes/chunks, while `ProxyRegistry.tla` masks the liveness gap

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:702-715`
- `src/main/kotlin/io/prometheus/Proxy.kt:557-591`
- `specs/tla/ProxyRegistry.tla:260-261, 372-396, 540`

**Problem:**
When an agent's connection goes half-open (the primary reason an agent reconnects with the same `launchId` before `transportTerminated` fires), the new connection `A2` takes over `/p` from stale `A1` (`A1.launchId == A2.launchId`). When `A1`'s `pathCount` hits `0`, `ProxyPathManager.settleDisplaced` (`ProxyPathManager.kt:711-714`) calls only `displacedContext.invalidate()`.

`AgentContext.invalidate()` closes channels and drains **queued** requests in `scrapeRequestQueue`, but unlike `Proxy.removeAgentContext`:
1. It does **not** call `scrapeRequestManager.failAllScrapeRequests(A1.agentId, ...)` — any scrape already polled out of `A1`'s queue and emitted onto the half-open stream (`agentHas[A1]`) hangs until `AgentContextCleanupService` evicts `A1` (up to `maxAgentInactivitySecs + staleAgentCheckPauseSecs` = 70s) or until `scrapeRequestTimeoutSecs` (90s) when `staleAgentCheckEnabled = false`.
2. It does **not** call `agentContextManager.removeChunkedContextsForAgent(A1.agentId)` — any in-flight `ChunkedContext` buffer (up to `maxZippedContentSizeMBytes`) stays pinned until timeout or eviction.
3. With the default transport filter enabled, `A1` stays in `AgentContextManager.agentContextMap` (inflating `proxy_agent_map_size` and appearing in the dashboard as invalid) until TCP timeout or stale-agent eviction — at which point eviction increments `proxy_eviction_count_total` and records an `AGENT_EVICTED` problem in the Activity feed for a connection the proxy already knew was an orphaned reconnect.

**TLA+ model drift (`specs/tla/ProxyRegistry.tla`):** In `RegisterLocked` (`ProxyRegistry.tla:260-261`), `orphans` sets `valid'[x] = FALSE` while keeping `phase[x] = "live"` and `x \in inMgr`. However, `Emit(c, s)` (`:374`) and `Respond(c, s)` (`:391`) check only `phase[c] = "live"`, **not** `valid[c]`, and `Fairness` (`:540`) asserts `WF_vars(Respond(c, s))`. TLC therefore assumes a dead orphaned connection (`~valid[c]`) will still fairly answer in-flight scrapes in `agentHas[c]`. If `Respond` accurately requires `valid[c]`, `EveryScrapeFinishes` fails because `Disconnect(c)` is an unfair `Environment` action.

**Impact:** In-flight scrapes on half-open reconnects hang for up to 70–90s (failing as `timed_out` or `client_cancelled` instead of prompt `agent_disconnected`), chunked buffers remain allocated, and ghost invalid contexts stay in `agentContextMap` until falsely counted as evictions.

**Fix:** Return orphaned `AgentContext`s from `takeOverPath` / `addValidatedPath` and, immediately after releasing `synchronized(pathMap)`, tear them down via `proxy.removeAgentContextIfCurrent(orphan, ...)` (using `RemovalReason.TERMINATION` or a dedicated reason) — or at minimum reclaim their chunked contexts and fail their in-flight scrapes immediately. Update `ProxyRegistry.tla` so `Emit`/`Respond` require `valid[c]` and orphan teardown removes/fails in-flight scrapes, then run `make tla-checks-full`.

---

### 4. ⬜ `removeFromPathManager` performs two full `O(total_paths)` scans under the global `pathMap` lock on every disconnect, including unauthenticated connections

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:831-840`
- `src/main/kotlin/io/prometheus/proxy/ProxyServerTransportFilter.kt:35-69`
- `src/main/kotlin/io/prometheus/Proxy.kt:557-591`

**Problem:**
With the default transport filter enabled, `ProxyServerTransportFilter.transportReady` creates a pending (unannounced) `AgentContext` for every incoming TCP/HTTP2 connection to the agent port **before** `AgentAuthServerInterceptor` runs. When any connection closes (port scanner, `grpc_health_probe`, bad-token agent retrying every 3s, or any 0-path agent), `transportTerminated` calls `proxy.removeAgentContext(agentId, TERMINATION)` -> `pathManager.removeFromPathManager(agentId, TERMINATION)`.

Inside `synchronized(pathMap)` — the global monitor blocking every Prometheus scrape lookup (`getAgentContextInfo`), service-discovery build, and path registration — `removeFromPathManager` unconditionally executes:
1. `pathMap.filterValues { info -> info.agentContexts.any { it.agentId == agentId } }` (`ProxyPathManager.kt:833`), even when `pathCountFor(agentId) == 0` (which `PathCountsAgree` guarantees means `served` is empty).
2. `pathMap.replaceAll { _, info -> info.withoutWaiter(agentId) }` (`ProxyPathManager.kt:836`), scanning and rewriting every entry in `pathMap` even when `agentId` is waiting on zero paths (an unannounced connection never passed auth or called `registerAgent`).

**Impact:** On a proxy holding tens of thousands of paths (default `maxPathsPerAgent = 20,000`, `maxPathsPerIdentity = 100,000`), unauthenticated connection churn on port 50051 repeatedly holds `synchronized(pathMap)` across two full `O(N)` map scans per close, starving Prometheus scrapes and legitimate registrations.

**Fix:** Under `synchronized(pathMap)`:
1. Skip the `filterValues` scan whenever `pathCountFor(agentId) == 0`.
2. Maintain a secondary index `waitedPathsByAgent: HashMap<String, MutableSet<String>>` (guarded by `pathMap`, updated whenever `waiting` changes in `takeOverPath`, `reclaimRejection`, `removePath`, `dropAgentFromPath`, and `removeFromPathManager`) so removing a departing agent from `waiting` lists visits only the paths it waited on (`O(waited)` — `O(1)` for normal and unauthenticated disconnects).

---

### 5. ⬜ `Proxy.shutDown()` stops sub-services without fault isolation, and `Proxy.startUp()` rollback omits `tracing?.close()` and `invalidateAllAgentContexts()`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/Proxy.kt:401-447`

**Problem:**
1. Unlike `Agent.shutDown()` (`Agent.kt:955-961`, hardened in `#557` with `runEach()`), `Proxy.shutDown()` (`Proxy.kt:438-446`) invokes `grpcService.stopSync()`, `dashboardService?.stopSync()`, `httpService.stopSync()`, `agentCleanupService?.stopSync()`, `tracing?.close()`, `closeMetrics()`, and `super.shutDown()` sequentially with no per-step fault isolation. If any earlier sub-service is already in `Service.State.FAILED` (`stopSync()` throws `IllegalStateException`) or times out stopping (`TimeoutException`), all subsequent shutdown steps are skipped — leaving remaining servers (`httpService`, `dashboardService`, admin/metrics Jetty servers in `super.shutDown()`), `SharedCollectors` registrations (`closeMetrics()`), Brave `Tracing`, and the JVM shutdown hook leaked.
2. In `Proxy.startUp()` (`Proxy.kt:401-429`), `grpcService.startSync()` starts first while `Proxy.isStartingOrRunning` is `true`. If an agent connects before `httpService.startSync()` or `dashboardService.startSync()` fails (e.g., port conflict), the rollback calls `grpcService.stopSync()` without first calling `agentContextManager.invalidateAllAgentContexts()` (stalling active streams for the full 2s `SHUTDOWN_GRACE`), and if Zipkin tracing is enabled and `DeferredServerInterceptor` initialized `tracingHolder` on that call, the rollback calls `super.shutDown()` without calling `if (tracingHolder.isInitialized()) tracing?.close()`.

**Impact:** A single failed sub-service during shutdown can leave Jetty/Ktor threads alive (preventing JVM exit) and leak shared metrics; a partial startup failure after an early agent connect leaks Brave's `Tracing` instance and slows rollback.

**Fix:** Apply the same fault-isolated `runEach()` teardown pattern used in `Agent.shutDown()` to `Proxy.shutDown()` (skipping `stopSync()` for sub-services already `FAILED` and attaching secondary exceptions as suppressed). In `Proxy.startUp()`'s failure handler, invalidate all agent contexts before stopping `grpcService` and close `tracing` if initialized before `super.shutDown()`.

---

### 6. ⬜ Race between `AgentContextManager.announceAgentContext()` and `removeFromContextManager()` can announce a removed agent and diverge eviction/disconnect metrics

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/AgentContextManager.kt:173-184, 234-281`
- `src/main/kotlin/io/prometheus/Proxy.kt:567-571`
- `src/main/kotlin/io/prometheus/proxy/AgentContextCleanupService.kt:66-74`

**Problem:**
`AgentContextManager.announce()` (`AgentContextManager.kt:179-184`) checks `agentContext.isValid()` and calls `agentContext.markAnnounced()` non-atomically with respect to `onRemoved()` / `invalidate()`:
- **Unmatched `AgentConnected`:** Thread 1 (`ProxyServerInterceptor`) reads `isValid() == true`. Thread 2 (`transportTerminated` or cleanup) removes the context, reads `announced == false` (emitting no `AgentDisconnected` and no disconnect metric), and calls `invalidate()`. Thread 1 then succeeds in `markAnnounced()` (`false -> true`), logging `"Registering agentId: ..."` and emitting `ProxyEvent.AgentConnected(agentId)` for a context that is already gone and will never emit `AgentDisconnected`.
- **Eviction metric divergence:** In `AgentContextCleanupService.run()` (`AgentContextCleanupService.kt:66-74`), Thread 2 snapshots `val announced = agentContext.announced` (`false`), enters `removeAgentContext`, and invalidates the context. Meanwhile Thread 1 flips `announced = true`. Thread 2 then reaches `Proxy.releaseRemovedAgent` (`Proxy.kt:567`), observes `agentContext.announced == true`, increments `proxy_agent_disconnects_total{reason="eviction"}`, and logs `AGENT_EVICTED` — while `AgentContextCleanupService` uses its stale local `announced == false` and skips `proxy_eviction_count_total.inc()`.

**Impact:** Stray `AgentConnected` events for terminated connections and permanent drift between `proxy_eviction_count_total` and `proxy_agent_disconnects_total{reason="eviction"}`.

**Fix:** Collapse `valid` and `announced` into a single atomic state machine (`AtomicReference<State>`: `PENDING`, `ANNOUNCED`, `INVALIDATED_PENDING`, `INVALIDATED_ANNOUNCED`) so `markAnnounced()` CASes `PENDING -> ANNOUNCED` and fails atomically once invalidated; have `AgentContextCleanupService` check `removed.announced` on the returned `AgentContext` after `removeAgentContext(...)`.

---

### 7. ⬜ `ScrapeDispatch.mergeContentTexts` emits invalid blank lines in OpenMetrics output when a consolidated agent returns only `# EOF`

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ScrapeDispatch.kt:121-133`

**Problem:**
```kotlin
val trimmed = nonEmpty.map { it.contentText.trimEnd() }
val hasEof = trimmed.any { it.endsWith(Exposition.EOF_MARKER) }
val stripped = trimmed.map { it.removeSuffix(Exposition.EOF_MARKER).trimEnd() }
val joined = stripped.joinToString("\n")
return if (hasEof) "$joined\n${Exposition.EOF_MARKER}" else joined
```
When a consolidated path scrapes multiple agents in OpenMetrics format and one agent returns `"# EOF\n"` (e.g., an empty registry or all metric families dropped by `MetricFilter`), that result is non-empty and passes line 124. After stripping `# EOF`, `stripped` contains `""`:
- `["metric_a 1.0\n# EOF\n", "# EOF\n"]` -> `"metric_a 1.0\n\n# EOF"` (blank line before `# EOF`).
- `["# EOF\n", "metric_b 2.0\n# EOF\n"]` -> `"\nmetric_b 2.0\n# EOF"` (leading blank line).
- `["# EOF\n", "# EOF\n"]` -> `"\n\n# EOF"` (two blank lines).

Whitespace-only non-empty bodies trigger the same defect.

**Impact:** OpenMetrics 1.0 forbids blank lines in exposition text; Prometheus rejects the entire consolidated scrape as malformed OpenMetrics.

**Fix:** Filter out empty strings after stripping `# EOF` and trailing whitespace (`val stripped = trimmed.map { it.removeSuffix(Exposition.EOF_MARKER).trimEnd() }.filter { it.isNotEmpty() }`), and when `stripped.isEmpty()` return `if (hasEof) Exposition.EOF_MARKER else ""`.

---

### 8. ⬜ `AgentAuthManager` strips a leading `/` from incoming paths in `isAuthorized` but not from configured `proxy.auth[].paths` globs

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/AgentAuthManager.kt:44-47, 125`

**Problem:**
`AgentIdentity.isAuthorized(path)` (`AgentAuthManager.kt:45`) strips a leading `/` via `val key = path.removePrefix("/")` before running `globMatches(it, key)`. However, `AgentAuthManager.create` (`AgentAuthManager.kt:125`) keeps `entry.paths.toList()` verbatim without stripping a leading `/` or rejecting patterns starting with `/`. Because `ProxyPathManager` always strips a leading `/` and rejects multi-segment paths, any `proxy.auth` glob written with a leading slash — e.g., `paths = ["/team_a_*"]`, matching the format accepted in `agent.pathConfigs[].path`, `agent.filters[].path`, and printed in denial logs (`"denied registration of path /team_a_metrics"`) — loads silently at startup and can never match any valid registration.

**Impact:** Silent misconfiguration trap where leading-slash `proxy.auth` globs deny every registration from that identity with no startup error or warning.

**Fix:** Normalize `entry.paths.map { it.removePrefix("/") }` in `AgentAuthManager.create` (matching `AgentPathManager`'s handling of `pathConfigs` and `filters`), or reject patterns containing `/` at startup with a clear `require(...)` error.

---

### 9. ⬜ `ProxyOptions.sdPath` and `ProxyPathManager.reservedPathRejection` disagree on multiple leading slashes and whitespace-only paths

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ProxyOptions.kt:297-300`
- `src/main/kotlin/io/prometheus/proxy/ProxyHttpRoutes.kt:88-90`
- `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:330`

**Problem:**
`ProxyOptions.assignServiceDiscoveryOptions` validates only `require(sdPath.isNotEmpty())`, accepting whitespace-only `"   "` and multi-slash `"//discovery"`. `ProxyHttpRoutes` registers `get(proxy.options.sdPath.ensureLeadingSlash())`, where Ktor ignores empty segments so `get("//discovery")` serves `/discovery`. Meanwhile, `ProxyPathManager.reservedPathRejection` (`ProxyPathManager.kt:330`) compares `key == pathKey(proxy.options.sdPath)`, where `pathKey` strips only a single `/` (`"/discovery"`), which never matches any registered single-segment `key` (`"discovery"`). (`ProxyOptions.assignBlitzPath` at lines 432–439 already avoids this via `trimStart('/')` and `require(isNotBlank())`.)

**Impact:** With `sdPath = "//discovery"`, an agent can register `/discovery` and advertise it in service discovery while every scrape of `/discovery` returns the SD JSON document instead of metrics. Whitespace-only `sdPath` also slips past startup validation.

**Fix:** Validate `require(sdPath.trimStart('/').isNotBlank())` when `sdEnabled` (mirroring `blitzPath`) and compare against `proxy.options.sdPath.trimStart('/')` in `reservedPathRejection` (or normalize `sdPath` once in `ProxyOptions`).

---

### 10. ⬜ `ProxyServiceImpl.unregisterPath` throws unhandled `IllegalArgumentException` on blank `path`, and proxy `ActivityLog` duplicates repeated paths in a burst

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:368-396`
- `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:775`
- `src/main/kotlin/io/prometheus/proxy/ActivityLog.kt:87-91`
- `src/main/kotlin/io/prometheus/proxy/dashboard/ActivityHtml.kt:154`

**Problem:**
1. Unlike `registerPath` (which checks blank paths in `formRejection` and returns `valid = false, INVALID_PATH`), `unregisterPath` forwards `request.path` straight to `ProxyPathManager.removePath`, where `require(path.isNotBlank())` (`ProxyPathManager.kt:775`) throws an uncaught `IllegalArgumentException` if a non-stock client sends an empty/whitespace `path`, turning into gRPC `Status.UNKNOWN` with a server exception instead of `UnregisterPathResponse(valid = false, reason = ...)`.
2. Commit `551feba8` (`#579`) fixed `AgentActivityLog` so repeated occurrences of the same path within a 5-second burst are deduplicated, but proxy `ActivityLog`'s `ActivityEntry.joinedBy` (`ActivityLog.kt:87-91`) still unconditionally appends `paths + next.paths` while `paths.size < MAX_SAMPLE_PATHS`. Clicking "Scrape now" repeatedly on `/orders` within 5 seconds (`ScrapeNow.kt:94-101`, same status and client group) or flapping a path within 5s produces `paths = ["orders", "orders", "orders"]` and renders `/orders, /orders, /orders + N more` via `ActivityHtml.kt:154`.

**Impact:** Non-stock gRPC clients can trigger `Status.UNKNOWN` server exceptions on blank `unregisterPath`; the proxy Activity feed renders duplicate path names and inflated "+N more" counts for repeated events on the same path.

**Fix:** Return `unregisterPathResponse { valid = false; reason = BLANK_PATH_MSG }` in `ProxyServiceImpl.unregisterPath` when `request.path.isBlank()`; deduplicate burst paths in proxy `ActivityLog` using the same per-line `counted` set + `pathCount` pattern as `AgentActivityLog`.

---

## 🤖 Agent & Common

### 11. ✅ Double-slash path stripping (`//foo`, `//`) causes permanent 404s for static paths, infinite discovery flapping + static-path clobbering, and a phantom `metricsProxyPath`

**Severity:** high · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:125, 170-199, 627, 643-644, 654, 765, 820, 980-983`
- `src/main/kotlin/io/prometheus/agent/AgentOptions.kt:445-449`
- `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:207, 293, 926`

**Problem:**
`AgentValues.pathCharacterProblem` does not reject `'/'`, and `AgentPathManager`, `AgentOptions`, and `ProxyPathManager` each strip only a single leading `'/'` (`removePrefix("/")`). Across boundaries, a path with two leading slashes is stripped twice in inconsistent places:
1. **Static `pathConfigs` entry `path = "//foo"`:** `AgentPathManager` checks `pathCharacterProblem("/foo")` -> `null` (passes startup). `doRegisterPath("//foo")` (`AgentPathManager.kt:765`) strips one `/` -> `path = "/foo"`, sends `RegisterPathRequest(path = "/foo")` to the proxy, and stores `paths["/foo"]` (`AgentPathManager.kt:820`). On the proxy, `ProxyPathManager.pathKey("/foo")` (`ProxyPathManager.kt:926`) strips the **second** `/` -> `"foo"`, bypasses `multiSegmentPathError`, accepts the registration, and stores `"foo"`. When Prometheus scrapes `/foo`, the proxy sends `ScrapeRequest(path = "foo")`. `AgentPathManager.get("foo")` (`AgentPathManager.kt:125`) looks up `paths["foo"]` -> `null` (it was stored under `"/foo"`), returning **404 (`Invalid path: foo`)** on every scrape forever. `PathNotice(path = "foo")` is also silently ignored. (This directly contradicts the invariant comment at `AgentPathManager.kt:980`.)
2. **Discovered entry `path = "//foo"`:** `desiredDiscoveredPaths` (`AgentPathManager.kt:627`) strips one `/` and keys `desiredByPath` by `"/foo"`. It then passes `"/foo"` to `doRegisterPath` (`:765`), which strips a **second** `/` -> `"foo"` and stores `paths["foo"]`. On the next reconcile tick, `unregisterStaleDiscoveredPaths` (`:654`) sees `"foo" !in {"/foo"}`, unregisters `"foo"`, and `reconcileDiscoveredEntry` immediately re-registers `"foo"` — **flapping (unregister + register) on every reconcile tick forever**. Worse, if static `pathConfigs` already owns `"foo"`, `isStaticPath("/foo")` (`:643-644`) returns `false`, so discovery bypasses static-collision protection, overwrites static `paths["foo"]` with `PathSource.DISCOVERED`, and unregisters the static path on the next tick.
3. **`agent.metrics.proxyPath = "//"`:** `AgentOptions.assignMetricsProxyPath` (`AgentOptions.kt:445-449`) strips one `/` leaving `metricsProxyPath = "/"` (passes `require`). `AgentPathManager.metricsProxyPathConfig()` builds `PathConfig(path = "/")`, whose `usable` strips the second `/` -> `""` (`false`) and silently drops it, leaving `agent.options.metricsProxyPath == "/"` (`collectsMetrics == true`, `registersJvmMetrics == true`) with no proxy path ever registered.

**Impact:** Silent permanent 404s on static paths, continuous proxy registration churn and static-path takeover/deletion on discovered paths, and phantom enabled-metrics state for `metricsProxyPath`. (See also #31 for the matching breakage in `AgentScrapeNow`.)

**Fix:** Reject any `'/'` remaining after stripping a single leading `'/'` (matching `ProxyPathManager.multiSegmentPathError`) in `AgentPathManager`'s `pathConfigs` validation, `AgentOptions.assignMetricsProxyPath`, and `DiscoveredPath.usable` / `desiredDiscoveredPaths`. Normalize once at the entry boundary and assert `'/' !in path` inside `doRegisterPath` so double-stripping is impossible.

**Resolution:** Fixed in #586. `AgentValues.segmentProblem` names a path with a slash left once one leading slash is dropped, and the agent refuses such a path where it comes in. A `pathConfigs` entry fails startup, naming it, as one with a character no scrape can carry does, and `agent.metrics.proxyPath` fails in `AgentOptions`. The embedded builder's `path()`, `scrapeSelf()`, `source()`, and `agentMetricsAt()` throw `ConfigLoadException` for one, as each drops a slash of its own before the agent drops another, so `source("//foo")` had served `foo`. The proxy's `multiSegmentPathError` calls `segmentProblem` too, so the two sides share one rule. `DiscoveredPath.usable` is false, so `FileDiscoverySource` skips and reports the entry (its message now says "single-segment path"), and the reconcile drops an unusable entry from any source. That covers `app/metrics` as well as `//foo`: the proxy already refused it, but a static one is now refused at startup, not at each connect, which the release notes list under Before you upgrade. The Path Rules note, the discovery table, the agent dashboard and troubleshooting pages, and `examples/discovery-targets.conf` say so. #31 stays open: `AgentScrapeNow` can no longer meet a configured or discovered `//` path, but its lookup still strips differently from `AgentSnapshot`'s.

---

### 12. ✅ Reclaim loop tight-spins with zero delay (unbounded gRPC flood) when `rejectedPathRetryMaxSecs == 1`, and fires every reclaim 1s early otherwise

**Severity:** high · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:408-414, 468, 489-504, 550-561, 963`
- `src/main/kotlin/io/prometheus/agent/AgentOptions.kt:581-584`

**Problem:**
`AgentOptions.validateInternalConfigVals` allows `rejectedPathRetrySecs = 1` and `rejectedPathRetryMaxSecs = 1` (`requirePositive` accepts `1`). In `AgentPathManager`:
- `RECLAIM_WINDOW = 1.seconds` (`AgentPathManager.kt:963`).
- `reclaimTakenOverPathsWhile` (`AgentPathManager.kt:552-559`) computes:
  ```kotlin
  val wait = paths.values.minOfOrNull { it.takenOver?.backoff?.remaining ?: Duration.INFINITE }
    ?.takeIf { it.isFinite() }
    ?.minus(RECLAIM_WINDOW)
  when {
    wait == null -> reclaimWake.receive()
    wait > Duration.ZERO -> withTimeoutOrNull(wait) { reclaimWake.receive() }
  }
  reclaimTakenOverPaths()
  ```
- When `rejectedPathRetryMaxSecs = 1` and a path is taken over by another agent, `nextBackoff` (`:412`) clamps `wait` to `1.seconds` (`dueAt = now + 1.seconds`), so `remaining <= 1.seconds == RECLAIM_WINDOW`.
- `wait = remaining - RECLAIM_WINDOW` is `<= Duration.ZERO`, so the `when` block **does not suspend at all**.
- `reclaimTakenOverPaths()` (`:491`) checks `remaining <= RECLAIM_WINDOW` (immediately `true`), calls `reclaimPathOnProxy`, gets `HELD_BY_ANOTHER_AGENT`, sets `dueAt = now + 1.seconds`, returns, and loops again with **zero sleep**.
- Even for larger settings (e.g., `rejectedPathRetrySecs = 2` or default `10`), subtracting `RECLAIM_WINDOW` from the sleep fires every reclaim 1s early (halving `2s` to `1s` and firing an initial `1s` `rejectedPathRetrySecs` immediately at `0s`).

**Impact:** With `rejectedPathRetryMaxSecs = 1`, any taken-over path turns the agent into a zero-delay tight loop hammering the proxy with `registerPath` RPCs at full CPU/network speed for as long as another agent holds the path.

**Fix:** Sleep for the true minimum `remaining` without subtracting `RECLAIM_WINDOW` in `reclaimTakenOverPathsWhile`:
```kotlin
val wait = paths.values.minOfOrNull { it.takenOver?.backoff?.remaining ?: Duration.INFINITE }
  ?.takeIf { it.isFinite() }
when {
  wait == null -> reclaimWake.receive()
  wait > Duration.ZERO -> withTimeoutOrNull(wait) { reclaimWake.receive() }
}
```
Keep `remaining <= RECLAIM_WINDOW` inside `reclaimTakenOverPaths()` so once the earliest path is actually due (`remaining <= 0`), other paths due within the next 1s still batch into the same pass without ever firing before the earliest path's backoff expires.

**Resolution:** Fixed in #586, with more than the suggested fix, which alone left the spin. `reclaimRejection` sends the refused agent a notice naming the holder (`tellHolder`), which wakes the reclaim loop at once. The pass then tried every path due within `RECLAIM_WINDOW`, so a path just refused with a backoff no longer than the window was tried again immediately. `reclaimTakenOverPathsWhile` now sleeps the full remaining wait, and `reclaimTakenOverPaths` tries nothing until some path is due, then gathers the paths due within the window as before. Three `AgentPathManagerTest` cases cover it: a pass tries no path before one is due, a due path still brings those due within a second into its pass, and the real loop, with `rejectedPathRetrySecs = rejectedPathRetryMaxSecs = 1` and a notice on each refusal, waits out the backoff. That last case made 27,319 loop passes in half a second before the fix. Neither TLA+ model covers the reclaim loop's timing, so neither changed.

---

### 13. ⬜ Connection stays stuck forever serving zero paths (no reconnect or failover) when a taken-over static path is permanently rejected on reclaim

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/Agent.kt:522-524, 534-537, 553-566`
- `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:135, 259-267, 274-276, 522-527, 550-561`

**Problem:**
Suppose an agent has static path(s) (e.g., `/foo`), `pathDiscoveryService == null`, and failover endpoints `[proxy1, proxy2]` (or a single proxy):
1. `/foo` is taken over by another agent on `proxy1` (`paths["foo"].takenOver != null`).
2. The connection drops; the agent reconnects (e.g., fails over to `proxy2`, or reconnects to `proxy1` whose auth/path rules now reject `/foo` with `NOT_AUTHORIZED` / `INVALID_PATH` / `PATH_LIMIT_REACHED`).
3. `pathManager.clear()` (`AgentPathManager.kt:135`) retains `paths["foo"]` because `takenOver != null`. `registerPaths()` (`:262`) skips `/foo`, so `rejectedCount == 0` and `hasRejectedStaticPaths == false` (`retryRejectedStaticPathsForConnection` is not launched).
4. `reclaimTakenOverPaths()` runs immediately and receives a non-retryable rejection (`dropsTakeover == true`). `reclaimFailed` (`:522-527`) removes `/foo` (`paths -= path`) and returns.
5. Now `servesNoStaticPath() == true`, `hasRejectedStaticPaths == false`, and `pathDiscoveryService == null`. Unlike `registerPaths()` and `retryRejectedStaticPathsForConnection` (`Agent.kt:562`), `reclaimTakenOverPathsWhile` never checks `servesNoStaticPath()`: on the next iteration `wait == null`, so it parks forever on `reclaimWake.receive()`.

The same stall occurs if `/a` was retryably rejected at connect while `/b` was taken over, `/a` becomes permanently rejected first (so `retryRejectedStaticPathsForConnection` sees `/b` in `paths` and calls `awaitCancellation()`), and `/b` is subsequently dropped by `reclaimFailed`.

**Impact:** The agent remains permanently connected (`isConnectedToProxy == true`) to a proxy that serves none of its paths, never reconnecting or failing over to a healthy backup proxy.

**Fix:** After `reclaimTakenOverPaths()` in the reclaim task (`Agent.kt:522-524`), check `if (pathManager.servesNoStaticPath() && !pathManager.hasRejectedStaticPaths && pathDiscoveryService == null)` (mirroring `retryRejectedStaticPathsForConnection` at `Agent.kt:562`) and return to tear down the connection. Also consider clearing `EndpointFailover.previousAttemptRegistered` on a no-paths teardown so failover advances past the rejecting proxy immediately instead of retrying primary once first.

---

### 14. ⬜ Concurrent overlapping scrapes of the same `MetricsSource` defeat `AgentHttpService.stuckSources` protection

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:155-156, 321-346`
- `src/main/kotlin/io/prometheus/agent/MetricsSource.kt:30-35`

**Problem:**
`stuckSources` is a `MutableSet<MetricsSource>` (`ConcurrentHashMap.newKeySet()`) with no per-source reference count, even though `MetricsSource` explicitly allows concurrent calls (e.g., `maxConcurrentHttpClients >= 2`, or a Prometheus scrape overlapping a dashboard "Scrape now" call):
1. Scrapes `S1` and `S2` both pass `if (source in stuckSources)` (`AgentHttpService.kt:326`) and launch `call1` and `call2`.
2. `S1` times out while `call1` is genuinely hung ignoring interrupts: `stuckSources += source`; `call1.invokeOnCompletion { stuckSources -= source }`; `call1.cancel()`.
3. `S2` times out or is cancelled (e.g., shorter dashboard timeout or disconnect): `stuckSources += source` (no-op in a set); `call2.invokeOnCompletion { stuckSources -= source }`; `call2.cancel()`.
4. `call2` responds to the interrupt and completes promptly; its `invokeOnCompletion` executes `stuckSources -= source`, removing `source` while `call1` is still hung.

**Impact:** The stuck-source circuit breaker clears prematurely while an abandoned call is still pinning a `Dispatchers.IO` thread, allowing subsequent scrapes to keep invoking the hung `MetricsSource` and pile up blocked IO threads.

**Fix:** Replace `MutableSet<MetricsSource>` with `ConcurrentHashMap<MetricsSource, Int>` tracking the count of abandoned in-flight calls per source: increment via `stuckSources.merge(source, 1, Int::plus)` before registering `invokeOnCompletion`, decrement/remove via `stuckSources.compute(source) { _, c -> (c ?: 0).dec().takeIf { it > 0 } }` on completion, and gate entry on `source in stuckSources`.

---

### 15. ⬜ `writeResponsesToProxyUntilDisconnected` can drop `RESULT_DROPPED` metrics on early writer exit, loses `endedBy` on response-stream failure, and doesn't tear down on early normal stream completion

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt:881-897, 903-974`
- `src/main/kotlin/io/prometheus/Agent.kt:486, 498-502, 539-542, 636-643`

**Problem:**
Three related bugs in `writeResponsesToProxyUntilDisconnected`:
1. **Unaccounted buffered outgoing messages:** `endConnection()` (`AgentGrpcService.kt:922-925`) only calls `nonChunkedChannel.close()` and `chunkedChannel.close()`, never `cancel()`. If `processScrapeResults` enqueues an `Outgoing` message into `nonChunkedChannel` or `chunkedChannel` (e.g., after a fast in-process `MetricsSource` scrape) and the corresponding writer coroutine fails before gRPC-Kotlin collects `streamOf`'s `flow { channel.consume { ... } }` or is cancelled prior to entering `channel.consume`, `channel.consume` never runs and the outer `finally { endConnection() }` leaves those buffered items uncancelled. `onUndeliveredElement` never fires, so `agent_scrape_result_count_total{type="dropped"}` silently undercounts.
2. **Missing `endedBy` in activity feed:** `runStreamTask` (`AgentGrpcService.kt:931-941`) catches non-fatal failures, logs them, and calls `closeAll()`, without calling `connectionContext.noteFailure(e)`. `writeResponsesToProxyUntilDisconnected` then returns normally, sibling tasks fail only with `CancellationException`, `connectionContext.endedBy` stays `null`, and the `DISCONNECTED` activity-feed entry (`Agent.kt:498-502`) omits the failure reason whenever a response stream failed first.
3. **Early normal completion of a writer:** `ProxyServiceImpl.writeResponsesToProxy` and `writeChunkedResponsesToProxy` catch non-cancellation stream errors and return `EMPTY_INSTANCE` (`ProxyServiceImpl.kt:645-646, 668-676`). If either client-streaming RPC completes normally on the agent side, `runStreamTask` skips `.onFailure` and never calls `closeAll()`, leaving the sibling stream and `processScrapeResults` alive in a half-dead state until a later send hits `ClosedSendChannelException`.

**Impact:** Undercounted `dropped` result metrics on early teardown, missing disconnect reasons in the agent dashboard Activity feed when a write stream fails, and delayed disconnect detection on clean server-side stream close.

**Fix:** In the outer `finally` of `writeResponsesToProxyUntilDisconnected` (after `coroutineScope` exits and all three child coroutines have finished), cancel `nonChunkedChannel` and `chunkedChannel` as well as calling `endConnection()`. In `runStreamTask`, call `connectionContext.noteFailure(e)` while `agent.isRunning`, and invoke `closeAll()` in a `finally` block so any writer exit immediately tears down the connection.

---

### 16. ⬜ Fatal JVM `Error` in `Agent.run()` blocks for `reconnectPauseSecs` and logs `"Waited ... to reconnect"` before terminating

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/Agent.kt:392-407, 608-611, 678-681`

**Problem:**
```kotlin
while (isRunning) {
  try {
    runCatchingCancellable {
      runBlocking {
        connectToProxy(endpointFailover)
        ...
      }
    }.onFailure { e -> handleConnectionFailure(e) }
  } finally {
    if (isRunning)
      reconnectPause()
  }
}
```
When `handleConnectionFailure(e)` (`Agent.kt:680`) rethrows a JVM `Error` (e.g., `OutOfMemoryError`) to terminate the agent, Guava's `AbstractExecutionThreadService` does not transition `isRunning` to `false` until the `Error` actually escapes `run()`. The `finally` block therefore observes `isRunning == true`, blocks uninterruptibly in `reconnectLimiter.acquire()` for up to `reconnectPauseSecs`, and allocates/logs `"Waited Xs to reconnect"` right after an `OutOfMemoryError`, even though `run()` is aborting and will never reconnect.

**Impact:** Delayed fatal-error shutdown (up to `reconnectPauseSecs`), extra allocations under OOM, and misleading reconnect log output during crash termination.

**Fix:** Remove the `try / finally` wrapper and place `if (isRunning) reconnectPause()` sequentially after `.onFailure { e -> handleConnectionFailure(e) }` inside the `while (isRunning)` loop, so rethrown `Error`s (and `CancellationException`s) exit `run()` immediately without pausing.

---

### 17. ⬜ TLS context build errors (`SSLException`) and unreadable TLS files (`FileNotFoundException`) bypass `failOnInvalidConfig`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/TlsMaterial.kt:63-66, 134-169`
- `src/main/kotlin/io/prometheus/common/BaseOptions.kt:885-895`
- `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt:311-321`
- `src/main/kotlin/io/prometheus/Agent.kt:1075-1078, 1105-1111`

**Problem:**
`BaseOptions.failOnInvalidConfig` catches only `ConfigException` and `IllegalArgumentException`. While Netty's `SslContextBuilder.keyManager(InputStream, InputStream)` wraps malformed PEM in `IllegalArgumentException`, two realistic TLS misconfigurations throw `IOException` subclasses instead:
1. **Mismatched certificate chain and private key** (both individually valid PEM): `SslContextBuilder.build()` (`TlsMaterial.kt:145, 168`) throws `javax.net.ssl.SSLException`.
2. **Unreadable TLS file** (file exists and `isFile == true`, e.g., `chmod 000` or container UID mismatch): `TlsSource.withStream` (`TlsMaterial.kt:66`) passes `require(file.exists() && file.isFile)`, then `file.inputStream()` throws `java.io.FileNotFoundException` (`Permission denied`).

Unlike `AgentHttpService.kt:137-148`, which wraps `resolveHttpsTrustManager` failures in `IllegalArgumentException`, `TlsMaterial` lets these `IOException`s escape raw.

**Impact:** Embedded callers of `Agent.startAsyncAgent` or `EmbeddedAgentConfig.start()` catching the documented `ConfigLoadException` receive an uncaught `SSLException` or `FileNotFoundException` instead. Standalone `Agent.startSyncAgent` and `Proxy.main` crash with an unhandled exception instead of logging `"Invalid configuration: ..."` and exiting `1`.

**Fix:** Wrap `IOException` (including `SSLException`) in `TlsSource.withStream` and `TlsMaterial.clientContext` / `serverContext` into `IllegalArgumentException` with a message naming the TLS setting (never the PEM contents), matching `AgentHttpService.kt:137-148`.

---

### 18. ⬜ `MetricFilter.previewBytes` mutates fail-open latches, logs warnings, and records `FILTER_FAILED_OPEN` during dashboard "Scrape now" previews

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/filter/MetricFilter.kt:134-180`
- `src/main/kotlin/io/prometheus/agent/AgentHttpService.kt:228-235, 530-541`
- `src/main/kotlin/io/prometheus/agent/dashboard/AgentScrapeNow.kt:48-51`

**Problem:**
`fetchForDashboard` and `AgentScrapeNow` guarantee that dashboard scrapes leave no trace in what the agent reports, and `AgentHttpService.filtered` calls `filter.previewBytes(bytes, contentType)` specifically when `dashboardScrape == true`. However, `filterBytes` delegates directly to `previewBytes`, so `previewBytes` still trips `contentTypeWarned` / `invalidUtf8Warned` (`MetricFilter.kt:155, 175`), logs the once-per-path WARN, and invokes `onFailedOpen(...)` — recording `AgentActivityKind.FILTER_FAILED_OPEN` in `AgentActivityLog` and permanently flipping `filter.failedOpen` on the dashboard.

**Impact:** A transient non-text or non-UTF-8 response seen only during a manual dashboard "Scrape now" permanently flags the filter as failed-open on the dashboard, emits an activity-feed problem entry, and suppresses the first-time WARN when a real Prometheus scrape hits fail-open later.

**Fix:** Extract a private helper taking `recordFailOpen: Boolean` (`true` for `filterBytes`, `false` for `previewBytes`) so `previewBytes` neither mutates the latches, logs warnings, nor invokes `onFailedOpen`.

---

### 19. ⬜ `Utils.parseHostPort` accepts empty hosts (`":50051"`, `"[]:50051"`, `"[]"`) and silently discards trailing garbage after bracketed IPv6 (`"[::1]50051"`)

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/Utils.kt:275-319`

**Problem:**
- `parseHostPort(":50051", 50051)` splits on `:` to `host = ""`, returning `HostPort("", 50051)`.
- `parseHostPort("[]:50051", 50051)` and `parseHostPort("[]", 50051)` extract `substring(1, 1)` = `""`, returning `HostPort("", 50051)`.
- `parseHostPort("[::1]50051", 50051)` (missing colon after `]`) falls into the `else` branch at `Utils.kt:296`, silently discarding `"50051"` and applying `defaultPort`.

**Impact:** Malformed proxy endpoints pass startup validation in `AgentOptions.assignProxyHostname` (`AgentOptions.kt:434`), surfacing later as cryptic connect failures or silently dialing the wrong port on IPv6 typos.

**Fix:** Require the extracted `host` to be non-blank (`require(host.isNotBlank())`), and in the bracketed-IPv6 branch require either `closeBracket == hostPort.lastIndex` or `hostPort[closeBracket + 1] == ':'`, throwing `IllegalArgumentException` on unexpected trailing characters.

---

### 20. ⬜ `MetricFilter.quotedSampleName` misses Prometheus 3 UTF-8 samples with whitespace after `{`

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/filter/MetricFilter.kt:248-256, 318-344`

**Problem:**
`quotedSampleName` checks `text[start] == '{' && start + 1 < end && text[start + 1] == '"'`. For a valid Prometheus 3 sample with whitespace after `{` (e.g., `{ "a.b", job="x" } 1`), `quotedSampleName` returns `null`; `sampleNameEnd` then stops immediately at `text[first] == '{'` (`nameEnd == first`), passing `""` to `keepSample`. That closes any open family and evaluates allow/deny regexes against `""` instead of `"a.b"`.

**Impact:** An allow list drops the sample and a deny list fails to drop it whenever an exporter formats quoted metric names with a space after `{`.

**Fix:** In `quotedSampleName`, skip leading blanks after `'{'` via `val q = Exposition.firstNonBlank(text, start + 1, end)` before testing `q < end && text[q] == '"'`, then pass `q` to `Exposition.unquote(text, q, end)`.

---

## 🔌 Embedding, Ktor Plugin & Spring Boot Starter

### 21. ⬜ `EmbeddedAgentInfo.shutdown()` / `close()` and `Agent.stop()` sneaky-throw undeclared checked `TimeoutException` (Java interop hazard)

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/EmbeddedAgentInfo.kt:114-123, 190`
- `src/main/kotlin/io/prometheus/Agent.kt:975-982` (compare `Agent.kt:990-997`)
- `src/main/kotlin/io/prometheus/agent/EmbeddedAgentConfig.kt:231-233`

**Problem:**
`Agent.stop()` (`Agent.kt:981`) calls `GenericExecutionThreadService.stopSync()`, whose JVM signature declares `throws java.util.concurrent.TimeoutException`. When `stopTimeout == null` (the default for `EmbeddedAgentConfig` and `Agent.startAsyncAgent`), `EmbeddedAgentInfo.shutdown()` (`EmbeddedAgentInfo.kt:120-123`) calls `agent.stop()`, and `EmbeddedAgentInfo.close()` (`:190`) delegates to `shutdown()`.

Neither `Agent.stop()`, `EmbeddedAgentInfo.shutdown()`, nor `EmbeddedAgentInfo.close()` declares `@Throws(TimeoutException::class)` or wraps the checked exception (verified via `javap`: bytecode emits no `throws` clause, narrowing `AutoCloseable.close()`). Yet the KDoc on `EmbeddedAgentInfo.shutdown()` (`:115`), `EmbeddedAgentConfig.Builder.stopTimeout` (`:232`), and `website/prometheus-proxy/docs/embedded-agent.md` explicitly document that `shutdown()` without `stopTimeout` throws `java.util.concurrent.TimeoutException`.

In Java, writing `try { agent.shutdown(); } catch (TimeoutException e) { ... }` or `try (var agent = cfg.start()) { ... } catch (TimeoutException e) { ... }` fails to compile (`exception java.util.concurrent.TimeoutException is never thrown in body of corresponding try statement`). At runtime, if stopping exceeds 30s, the checked `TimeoutException` escapes undeclared (Kotlin sneaky-throw), bypassing `catch (RuntimeException e)` handlers.

**Impact:** Java callers cannot legally catch the documented `TimeoutException`, and unchecked exception handlers miss it at runtime. `Agent.startWithin()` (`Agent.kt:990-997`) already wraps `TimeoutException` into unchecked `IllegalStateException` for the exact same reason.

**Fix:** Catch `TimeoutException` in `Agent.stop()` and wrap it in an unchecked `IllegalStateException` (matching `Agent.startWithin()`), then update the KDoc and docs on `EmbeddedAgentInfo.shutdown()` and `EmbeddedAgentConfig.Builder.stopTimeout`. (Adding `@Throws(TimeoutException::class)` instead would be a source-breaking change for existing Java callers.)

---

### 22. ⬜ `EmbeddedAgentConfig.Builder.path()` and `scrapeSelf()` accept `"/"`-only paths and blank URLs, silently dropping targets instead of failing validation

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/EmbeddedAgentConfig.kt:469-479, 487-515, 561-562` (compare `415-416`)
- `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:170-183, 1164-1167`
- `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgent.java:244-249`
- `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/FileSettings.kt:55-63`

**Problem:**
1. `Builder.path()` (`EmbeddedAgentConfig.kt:495`) checks `if (path.isBlank()) invalid("A path is required")` and then strips a leading `/` on lines 510–511 (`path.removePrefix("/")`). Passing `"/"` (or `" / "`) to `path("/", "http://...")` or `scrapeSelf("/", 8080)` passes validation and writes `path = ""` into `agent.pathConfigs`. (`Builder.source(" / ", ...)` at `:561` also strips before trimming, unlike `agentMetricsAt()` at `:415`.)
2. When `username` and `password` are absent/empty, `Builder.path()` (`:498-499`) skips all URL checks (`val target = url`), accepting `url = ""` or `"   "`. Both the Spring Boot starter (`PrometheusProxyAgent.java:247`: `orEmpty(target.getUrl())`) and the Ktor plugin (`FileSettings.kt:58`: `target.string("url").orEmpty()`) pass `""` when a `paths` entry omits `url` or resolves it from an unset env var.
3. At startup, `AgentPathManager.pathConfigs` (`AgentPathManager.kt:175-183, 1166`) filters by `config.usable` (`path.removePrefix("/").isNotBlank() && url.isNotBlank()`), **silently dropping** the target with only a `WARN` log instead of throwing `ConfigLoadException`.

**Impact:** A misconfigured target with a missing/blank URL or `"/"` path in Spring Boot, Ktor, or `EmbeddedAgentConfig` does not throw `ConfigLoadException`, does not fail `required = true` apps, and does not invoke `onFailure` handlers — silently dropping the scrape target, inconsistent with omitting `path` or passing `"/"` to `agentMetricsAt()` / `source()`.

**Fix:** In `EmbeddedAgentConfig.Builder.path()`:
1. Normalize `val normalized = path.trim().removePrefix("/")` and require `normalized.isNotEmpty()` (and use `path.trim().removePrefix("/")` in `Builder.source()`).
2. Require `url.isNotBlank()` unconditionally before the `if (credentials.isEmpty())` branch (and consider validating `url.indexOf("://") > 0` unconditionally so credential-less URLs fail fast as credentialed ones do).

---

### 23. ⬜ `EnvironmentSetting.check()` validates and fails on `fromEnvironment()` values overridden by an earlier `setting()` call

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/EmbeddedAgentConfig.kt:119, 353-371, 579-619`
- `src/main/kotlin/io/prometheus/agent/EnvironmentSetting.kt:69-83`
- `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/FileSettings.kt:64` & `PrometheusProxyAgentConfig.kt:97-113`
- `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgent.java:157-175`

**Problem:**
`Builder.build()` (`EmbeddedAgentConfig.kt:604`) merges `values + paths + settings`, so `setting(key, value)` takes precedence over builder methods regardless of call order (as documented in `EmbeddedAgentConfig.kt:575`, `PrometheusProxyAgentConfig.kt:80-82`, and `PrometheusProxyAgentProperties.java:120-121`). Both the Ktor plugin (`FileSettings.applyTo` line 64) and the Spring Boot starter (`PrometheusProxyAgent.config` line 162) apply `settings.*` **before** running `agent { }` blocks / `PrometheusProxyAgentCustomizer` beans — the exact place apps are guided to call `fromEnvironment()`.

However, `setFromCode(key)` (`EmbeddedAgentConfig.kt:598-600`) removes a key from `readFromEnvironment` only when `setting(key, ...)` is called **after** `fromEnvironment()`. When `setting("proxy.endpoints", ...)` or `setting("tls.trustCertCollectionPem", ...)` was called **before** `fromEnvironment()`, `fromEnvironment()` (`:357-370`) still records the env var in `readFromEnvironment`, and `build()` (`:616`) passes it unmodified to `EmbeddedAgentConfig`. When `start()` runs (`:119`), `EnvironmentSetting.check(readFromEnvironment, env)` validates the stale, shadowed environment value and throws `ConfigLoadException` even though `toConfig()` holds the valid `setting()` value.

**Impact:** Any Ktor app, Spring Boot app, or direct builder caller that overrides `proxy.endpoints` or `tls.trustCertCollectionPem` via `settings.*` / `setting()` before `fromEnvironment()` runs fails to start if the shadowed `PROMETHEUS_PROXY` or `PROMETHEUS_PROXY_CERT` variable is malformed, even though the effective configuration is valid.

**Fix:** In `EmbeddedAgentConfig.Builder.build()` (`EmbeddedAgentConfig.kt:616`), drop any environment entry whose key is overridden by `settings`:
```kotlin
readFromEnvironment = readFromEnvironment.filterKeys { it.key !in settings },
```

---

### 24. ⬜ Ktor plugin `PrometheusProxyAgentConfig` eagerly parses `path` and `labels` in its constructor, crashing `install()` even when `enabled = false` or `required = false`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/PrometheusProxyAgentConfig.kt:44-74, 97-114`
- `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/FileSettings.kt:32, 48, 104-108`
- `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/PrometheusProxyAgent.kt:50-54, 65-72`

**Problem:**
`PrometheusProxyAgentConfig`'s constructor runs during `install(PrometheusProxyAgent) { ... }` (`createConfiguration = ::PrometheusProxyAgentConfig` at `PrometheusProxyAgent.kt:69`), **before** the install block executes, before any `agent { onFailure { ... } }` handler is added, and even when `enabled = false` or `required = false`. Lines 70 and 73 eagerly read `path` and `labels` from `fileSettings`:
```kotlin
var path: String? = fileSettings.string("path")
var labels: Map<String, String> = fileSettings.stringMap("labels")
```
Verified against Ktor's `HoconApplicationConfig` bytecode: if `prometheus-proxy.agent.labels` is a scalar instead of a map (e.g., `labels = "team=web"`), `config.config("labels")` throws `ApplicationConfigurationException` (wrapping `ConfigException.WrongType`); if `prometheus-proxy.agent.path` is an object/list instead of a scalar, `propertyOrNull("path")?.getString()` throws `ApplicationConfigurationException`. Unlike `enabled` and `required` (which must be read eagerly to govern plugin behavior), `path` and `labels` are regular agent settings like `metrics-proxy-path` and `metrics-proxy-path-labels`, which are deferred to `applyTo()`.

**Impact:** A malformed `labels` or `path` entry in `application.conf` crashes Ktor startup inside `install(PrometheusProxyAgent)` even when `enabled = false` or `required = false` (default), bypassing `onFailure` handlers and violating the plugin's documented guarantee (`PrometheusProxyAgent.kt:50-54`).

**Fix:** Defer/catch exceptions reading `path` and `labels` from `fileSettings` (recording the first deferred failure unless overwritten in the install block), and surface any unresolved failure inside `applyTo()`'s `attempt { ... }` block so `AgentLifecycle.start()` honors `enabled`, `required`, and `onFailure`.

---

### 25. ⬜ Ktor plugin `AgentLifecycle` has `beginStop()`/`finishStop()` and `start()`/`beginStop()` races plus unguarded `Thread.join()` throwing checked `InterruptedException`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/AgentLifecycle.kt:81-118, 138-164`

**Problem:**
1. **Unguarded `Thread.join()` (`:156, 161`):** `finishStop()` calls raw `thread.join()` and `thread.join(...)`. Verified in Ktor's `io.ktor.events.Events.raise` bytecode: `Events.raise` catches any `Throwable` from a handler and rethrows it after handlers finish. If the thread disposing the Ktor `Application` is interrupted, `thread.join()` clears the interrupt flag, aborts waiting for shutdown, and propagates an undeclared checked `InterruptedException` out of `ApplicationStopping` — contrasting with `EmbeddedAgentInfo.stopWithin()` (`EmbeddedAgentInfo.kt:156`), which uses `Uninterruptibles.joinUninterruptibly(...)`.
2. **`beginStop()` / `finishStop()` publish-order race (`:138-153`):** `beginStop()` clears `agent` on line 139 (`val started = agent.getAndSet(null) ?: return`), removes the attribute and logs (`:140-141`), and only afterward spawns the stop thread and publishes it to `stopping.set(...)` (`:142`). If `finishStop()` runs concurrently between line 139 and line 142, its call to `beginStop()` returns early (`agent == null`), `stopping.getAndSet(null)` returns `null`, and `finishStop()` returns without waiting for `started.shutdown()`.
3. **`start()` / `beginStop()` concurrent-shutdown leak (`:111-114, 139`):** If shutdown fires while `launch(settings)` (`:111`, up to 30s) is in flight, `beginStop()`/`finishStop()` see `agent == null` and return; when `launch(settings)` finishes, lines 113–114 publish `started` into `agent` and `application.attributes` on an already-disposed `Application`, leaking the running agent without ever calling `shutdown()`. (`start()` also lacks a re-entry guard if `ApplicationStarted` fires twice.)

**Impact:** Interrupted shutdowns throw checked `InterruptedException` out of `ApplicationStopping` and skip waiting for agent teardown; concurrent stop/disposal or shutdown during startup can skip waiting for teardown or leak a live agent past app disposal.

**Fix:** Wait uninterruptibly in `finishStop()` (using `Uninterruptibles.joinUninterruptibly` or an interrupt-preserving join loop that restores `Thread.currentThread().interrupt()` in `finally`). Guard `AgentLifecycle` state transitions (`NEW -> STARTING -> RUNNING -> STOPPING -> STOPPED`) with a small lock so `beginStop()` publishes `stopping` atomically with clearing `agent`, `start()` immediately shuts down a late-finishing `started` handle if stop already began, and duplicate `start()` calls are no-ops.

---

### 26. ⬜ Spring Boot starter's default `metricsPath()` uses `spring.application.name` verbatim, failing startup when the app name contains spaces or disallowed path characters

**Severity:** low · **Confidence:** confirmed

**Where:**
- `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgent.java:261, 277-287`
- `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:194-199`

**Problem:**
When `prometheus-proxy.agent.path` is unset and `source` is `REGISTRY` (the default), `PrometheusProxyAgent.metricsPath()` (`:277-287`) returns `(app == null ? "application" : app) + "_metrics"` using the raw, untrimmed `spring.application.name` property. Spring Boot apps commonly have spaces or slashes in `spring.application.name` (e.g., `"Order Service"` or `"team/orders"`). Passing `"Order Service_metrics"` to `builder.source(...)` (`:261`) causes `AgentPathManager` (`AgentPathManager.kt:194-199`) to reject it at startup with `ConfigLoadException: Path 'Order Service_metrics', given by source(), is one the proxy refuses: ...` — mentioning neither `spring.application.name` nor `prometheus-proxy.agent.path`.

**Impact:** Zero-config Spring Boot apps whose `spring.application.name` contains spaces, leading/trailing whitespace, `?`, `#`, or control characters fail to start the agent with a misleading error blaming `source()`.

**Fix:** Trim `appName()` and either sanitize disallowed path characters when deriving the default path from `spring.application.name` (e.g., replacing whitespace/slashes with `_`, documented on the starter page) or detect disallowed characters in `metricsPath()` and throw a clear `ConfigLoadException` instructing the operator to set `prometheus-proxy.agent.path`.

---

### 27. ⬜ `EmbeddedAgentInfo` leaks internal `STOP_THREAD_NAME` constant as a `public static final` JVM field

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/EmbeddedAgentInfo.kt:192-197`

**Problem:**
Inside `private companion object`, line 196 declares `const val STOP_THREAD_NAME = "prometheus-agent-shutdown"` without `private`. In Kotlin, a non-`private` `const val` inside a `private companion object` compiles as a `public static final java.lang.String STOP_THREAD_NAME` field directly on the enclosing `EmbeddedAgentInfo` class (verified via `javap`).

**Impact:** Exposes an internal thread-name detail as part of `EmbeddedAgentInfo`'s public JVM binary API.

**Fix:** Mark it `private const val STOP_THREAD_NAME = "prometheus-agent-shutdown"`.

---

## 📊 Dashboards & `/screens`

### 28. ⬜ `/screens` Split layout ignores `first`/`second` pane ordering at `<= 720px`, breaking pane swaps and `#later+earlier` links

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensCss.kt:81, 111-114`
- `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensScript.kt:143-154`

**Problem:**
`ScreensScript.kt:149` positions Split panes strictly via inline `gridColumn`:
```js
panes[id].style.gridColumn = layout === 'all' || at < 0 ? '' : String(at + 1);
```
At `<= 720px`, `ScreensCss.kt:111-114` overrides both axes to `auto`:
```css
@media (max-width:720px) {
  .stage.split { grid-template-columns:1fr; grid-template-rows:1fr 1fr; }
  .stage.split .pane.shown { grid-row:auto; grid-column:auto !important; }
```
With neither `grid-row` nor `order` carrying the slot index, CSS Grid auto-placement stacks the two panes in static DOM order (`proxy` first, then config order):
1. Opening `/screens#grafana+proxy` on a `<= 720px` viewport renders `proxy` on top and `grafana` on the bottom, opposite the link and tab underlines.
2. Picking the other pane's screen in `<select>` or clicking the `second` tab flips `first`/`second` in JS (updating underlines and hash) while the two panes on screen do not swap.
3. Replacing `first` when `first` follows `second` in DOM order can cause the untouched `second` pane to jump between top and bottom.

**Impact:** Split pane ordering, swapping, and ordered split links are visually broken on narrow viewports (`<= 720px`).

**Fix:** Preserve the no-DOM-move invariant by setting a CSS custom property `--slot` (`panes[id].style.setProperty('--slot', String(at + 1))` when `layout === 'split' && at >= 0`, else `removeProperty('--slot')`) instead of `gridColumn`. In `ScreensCss.kt`, use `.stage.split .pane.shown { grid-row:1; grid-column:var(--slot); }` above 720px and `.stage.split .pane.shown { grid-column:1; grid-row:var(--slot); }` inside `@media (max-width:720px)` (removing `!important`).

---

### 29. ⬜ `DashboardScript.scrapeNow` lacks an in-flight guard, leaves `#scrape-result` uncloseable on network/non-panel errors, and writes arbitrary error bodies via `innerHTML`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardScript.kt:299-331`

**Problem:**
1. **Stale / out-of-order overwrites:** `scrapeNow(btn)` tracks no request sequence number or `AbortController`. Clicking Scrape now on `/slow` (up to 15s proxy / 10s agent timeout) and then on `/fast` (50ms) lets `/slow` overwrite `#scrape-result` and announce via `say(...)` seconds later. Closing `#scrape-result` while a request is in flight similarly lets the late response write into the hidden panel and announce via `say(...)` while `panel.hidden` remains `true`.
2. **Uncloseable error state:** On network error (`DashboardScript.kt:321-324`), `panel.textContent = 'No answer from the ...'` renders plain text with no `.close` button. Plain-text server refusals (`403` from `pageGuard`/`hostCheck`, `404`, `500`) do the same. Because `Escape` closes only `#column-guide` (`:384-387`), `#scrape-result` stays stuck open at the top of the page until reload or another scrape.
3. **Unchecked `innerHTML`:** `panel.innerHTML = html` (`:317`) parses any non-401 response body as HTML. Behind a reverse proxy returning an HTML 502/504 error page (e.g., nginx), that entire HTML error document is injected directly into `#scrape-result`.

**Impact:** Stale scrape results during concurrent clicks, permanently stuck error banner after failed fetches, and broken DOM if a reverse proxy returns an HTML error page.

**Fix:** Add a monotonic `var scrapeSeq = 0;` incremented in both `scrapeNow` and `closeScrapeResult` (optionally with `AbortController`), ignoring stale callbacks (`reqId !== scrapeSeq`). Assign `panel.innerHTML` only when the response is `text/html` from the scrape-now handler (e.g., checking `Content-Type` and a response header or expected `sr-head` marker); otherwise render the text safely via `textContent` inside a DOM-constructed `.sr-head` wrapper that includes a working `.close` button (using the same wrapper for network errors and `"Scraping /…"`).

---

### 30. ⬜ Theme toggle on `/screens` and its same-origin Proxy Dashboard iframe go out of sync

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/ThemeScript.kt:43-69`
- `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensHtml.kt:85, 94, 190`

**Problem:**
`/screens` and the Proxy Dashboard inside its first `<iframe>` (`proxy` pane) are same-origin, share `localStorage['prometheus-dashboard-theme']`, and both display the Theme button stacked one right above the other. `ThemeScript.kt` listens to `systemDark.addEventListener('change', showTheme)` but does not listen to `window.addEventListener('storage', ...)`.

Clicking the Theme button in `/screens`'s bar switches `/screens` to dark while the embedded Proxy Dashboard stays light (with its old button icon/tooltip) until reloaded; clicking the iframe's Theme button afterward cycles from its stale `<html data-theme>` rather than the stored value. (The same desync happens across any two open dashboard tabs.)

**Impact:** Immediate visual theme mismatch between `/screens` chrome and its own embedded proxy dashboard right underneath it, plus confusing cycling across the two adjacent Theme buttons.

**Fix:** Add a `storage` listener in `ThemeScript.kt`:
```js
window.addEventListener('storage', function (evt) {
  if (evt.key !== '$THEME_KEY' && evt.key !== null) return;
  var t = evt.newValue;
  if (t === 'light' || t === 'dark') document.documentElement.setAttribute('data-theme', t);
  else document.documentElement.removeAttribute('data-theme');
  showTheme();
});
```
Because `storage` events fire only in *other* same-origin browsing contexts, `/screens`, its proxy iframe, and sibling dashboard tabs stay synchronized immediately without looping.

---

### 31. ⬜ `AgentScrapeNow` strips all leading slashes (`trimStart('/')`) while `AgentSnapshot`/`AgentPathManager` strip only one, breaking Scrape now for `//...` / `///...` paths

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/agent/dashboard/AgentScrapeNow.kt:73-89`
- `src/main/kotlin/io/prometheus/agent/dashboard/AgentSnapshot.kt:279, 284`
- `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt:186, 195, 296, 983`

**Problem:**
Because `AgentPathManager` and `AgentSnapshot` (`AgentSnapshot.kt:279, 284`) normalize configured/discovered paths with single-slash `removePrefix("/")`, a configured or discovered path like `"//metrics"` (or `"///metrics"`, which the proxy rejects as multi-segment) appears in `AgentSnapshot` as `"/metrics"` (or `"//metrics"`), rendering a Scrape now button that posts `path=%2Fmetrics` (or `%2F%2Fmetrics`). `AgentScrapeNow.scrape` (`AgentScrapeNow.kt:73`) calls `AgentPathManager.requestedPathKey(path)`, which runs `trimStart('/')` -> `"metrics"`, and `targetOf("metrics")` (`:86-87`) compares `it.path.removePrefix("/") == "metrics"` -> `false`, returning `404 Not Found: /metrics isn't configured, discovered, or registered`.

**Impact:** Clicking Scrape now on any `//...` or `///...` path in the Agent Dashboard always fails with a misleading 404. (Shares root cause with #11; once #11 rejects multi-slash paths at agent startup/discovery, this remains relevant for direct/runtime paths unless normalized consistently.)

**Fix:** Resolve #11 so invalid leading-slash paths are rejected upfront, and in `AgentScrapeNow.scrape` look up `path`, `path.removePrefix("/")`, and `AgentPathManager.requestedPathKey(path)` consistently so `AgentScrapeNow` and `AgentSnapshot` never disagree.

---

### 32. ⬜ `ScreensScript.splitPanes` misparses single-pane search links as Split layouts when a query term after `+` matches a screen ID

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensScript.kt:167-186`
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardScript.kt:220-224`

**Problem:**
`DashboardScript.applyFilter` builds query strings via `URLSearchParams.toString()`, encoding spaces as `+` (e.g., `?q=refused+agent`). `ScreensScript.splitPanes` splits on the first `+` whose following segment up to `/` matches any key in `panes`:
```js
if (panes[rest.split('/')[0]]) return [text.slice(0, at), rest];
```
In configs with common screen IDs (e.g., `demos/ktor/config/proxy.conf` defines `agent`, `debug`, `prometheus`, `grafana`; `demos/basics/config/proxy.conf` defines `agent-east`, `agent-rogue`), a single-pane filtered link like `#proxy/activity?q=refused+agent` splits into `["proxy/activity?q=refused", "agent"]`, truncating the search query and opening Split view instead.

**Impact:** Sharing or bookmarking a `/screens` link to a multi-word dashboard search whose trailing word (or word before `/`) matches a screen ID opens the wrong layout and drops part of the query.

**Fix:** Encode spaces as `%20` in `DashboardScript.applyFilter` (`params.toString().replace(/\+/g, '%20')`), which Ktor decodes identically while avoiding collisions inside `/screens` hashes.

---

### 33. ⬜ `GET <base>/login` ignores `?next=...` when the viewer already has a valid session

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt:645-653`

**Problem:**
```kotlin
get {
  if (logins.isValid(call.request.cookies[sessionCookie]))
    call.seeOther(basePath)
  else
    call.respondLogin(safeNext(call.request.queryParameters[LoginHtml.NEXT_FIELD], routeBase, site.outsidePages))
}
```
When a logged-out user opens two tabs (e.g., `/screens` and `/dashboard/paths`), both redirect to `/dashboard/login?next=...`. After signing in on Tab 1, reloading Tab 2 redirects to `basePath` (`/dashboard`) instead of `safeNext(...)`.

**Impact:** Already-authenticated viewers following a stale login link lose their intended destination (`/screens` or a filtered view).

**Fix:** Compute `val next = safeNext(call.request.queryParameters[LoginHtml.NEXT_FIELD], routeBase, site.outsidePages)` once and redirect with `call.seeOther(next)` (which falls back to `basePath` when `next` is absent or invalid).

---

### 34. ⬜ `DashboardSessions.isValid(..., counts = false)` mutates LRU access order on every push tick

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardSessions.kt:51-55, 73-91`
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt:807`

**Problem:**
`DashboardSessions.sessions` is an access-ordered `LinkedHashMap(..., accessOrder = true)` capped at 1,000 entries. On every refresh tick (default 2s), `DashboardServer.pushToEvery` calls `logins.isValid(it.login, counts = false)` so open tabs don't count as user activity. Inside `isValid`, `sessions[key]` calls `LinkedHashMap.get(key)`, which unconditionally moves the entry to the most-recently-used tail even when `counts == false`.

**Impact:** While `idleTimeout` still expires properly because `lastRequest` isn't updated, under session-cap eviction (`> 1,000` sessions) idle WebSocket tabs are promoted to MRU every 2s and can evict more recently active non-WebSocket sessions (such as `/screens` sessions).

**Fix:** Use insertion order (`accessOrder = false`) and explicitly re-insert (`sessions.remove(key); sessions[key] = session`) only when `counts == true` inside the same `synchronized(sessions)` block.

---

### 35. ⬜ `DashboardServer.render` never resets `session.renderFailed` on recovery and logs nothing on repeated render errors

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt:858-866` (compare `collectForPush` at `817-829`)

**Problem:**
Unlike `collectForPush` (which resets `collectFailing = false` on success and logs repeat failures at `DEBUG`), `render()` sets `session.renderFailed = true` on the first failure, never resets it after a successful render, and logs nothing at any level on subsequent failures.

**Impact:** After one transient render error on a long-lived session, any later unrelated render exception on that session is silently swallowed with zero log output.

**Fix:** Add `.onSuccess { session.renderFailed = false }` and an `else logger.debug { "Dashboard push failed to render again: ${e.simpleClassName}" }` branch matching `collectForPush`.

---

### 36. ⬜ Toggling "Problems only" during the search input debounce window causes a duplicate WebSocket announce and screen-reader announcement

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardScript.kt:228-241`

**Problem:**
Typing in `#filter-q` schedules `filterTimer = setTimeout(applyFilter, 250)`. Unlike the `submit` handler (`:239`), the `change` handler for `#filter-problems` (`:233-235`) calls `applyFilter()` without first calling `clearTimeout(filterTimer)`. Typing a query and toggling "Problems only" within 250ms fires `applyFilter()` immediately and again when the timer expires.

**Impact:** Duplicate WebSocket frame round-trip and duplicate match-count announcement in `#conn-announce`.

**Fix:** Call `clearTimeout(filterTimer);` before `applyFilter();` in the `#filter-problems` `change` handler.

---

## 🔒 Security

### 37. ⬜ Incomplete URL userinfo redaction leaks passwords containing or following `@` in `Utils.sanitizeUrl` and Spring Boot's `PrometheusProxyAgentProperties.shownUrl`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/Utils.kt:36, 62-70, 87-92`
- `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgentProperties.java:307-311`

**Problem:**
Both redactors use `[^@/?#]+@` (`USERINFO_REGEX = Regex("(://)[^@/?#]+@")` in `Utils.kt:36`, `url.replaceAll("(://)[^@/?#]+@", "$1***@")` in `PrometheusProxyAgentProperties.java:311`), which stops at the **first** `@` in the authority rather than the last `@` before `/`, `?`, `#`, or `\`.

In real deployments, HTTP Basic Auth userinfo commonly contains an unescaped `@` — either an email username (`https://alice@corp.com:s3cr3t@metrics.internal/metrics`) or a password containing `@` (`https://admin:p@ssword@metrics.internal/metrics`):
- Ktor's `URLParser` (`ktor-http` 3.6.1 `URLParser.kt:83-101`) scans past every `@` in the authority and parses `host = "metrics.internal"` without throwing, so such target URLs register and scrape normally.
- Meanwhile, both redactors transform `https://alice@corp.com:s3cr3t@metrics.internal/metrics` into `https://***@corp.com:s3cr3t@metrics.internal/metrics`, and `https://admin:p@ssword@metrics.internal/metrics` into `https://***@ssword@metrics.internal/metrics`.
- The same leak occurs on remote config URL load failures (`BaseOptions.kt:835-837`, `ConfigLoadException`) when an exception message embeds the raw URL and passes through `sanitizeUrlsInText`.

**Impact:** Plaintext passwords (or password suffixes) from target URLs and remote config URLs leak into agent/proxy logs, `/debug`, Zipkin `http.url` tags, `ConfigLoadException` messages, Spring Boot `toString()` diagnostics, and proxy/agent dashboards (`ProxyPathManager.kt:273` -> `PathTableHtml`).

**Fix:** Match greedily up to the **last** `@` in the authority component (before `/`, `?`, `#`, or `\`) in both files:
- `Utils.kt:36`: `private val USERINFO_REGEX = Regex("(://)[^/?#\\\\]+@")`
- `PrometheusProxyAgentProperties.java:311`: `url.replaceAll("(://)[^/?#\\\\]+@", "$1***@")`

Add unit tests covering email usernames (`https://alice@corp.com:s3cr3t@host/metrics`), passwords with `@` (`https://user:p@ss@host/metrics`), and paths containing `@` (`https://host/@alice`, which stays unredacted because `/` precedes `@`), and update the existing comment/test in `PrometheusProxyAgentAutoConfigurationTest.java:157`.

---

### 38. ⬜ Unary `ScrapeResponse` lacks header-field caps, `parseContentType` passes multi-MB and control-character parameters, `httpStatus` emits arbitrary 32-bit codes, and plain bodies bypass `maxUnzippedContentSizeMBytes`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ProxyServiceImpl.kt:628` (compare chunked `boundedHeader` at `:757, 924-948`)
- `src/main/kotlin/io/prometheus/proxy/ScrapeDispatch.kt:203-218, 289-313, 323-330`
- `src/main/kotlin/io/prometheus/proxy/ProxyUtils.kt:124-135`
- `src/main/kotlin/io/prometheus/proxy/ProxyOptions.kt:413-417`

**Problem:**
A malicious or compromised authenticated agent (or any peer when agent auth is disabled) answering a scrape can exploit four missing boundary checks:
1. **Multi-megabyte HTTP `Content-Type` response header:** Unlike `ChunkedResponseReader.readHeader` (`ProxyServiceImpl.kt:757`), unary `writeResponsesToProxy` (`:628`) never caps `srContentType`, `srUrl`, or `srFailureReason`. In `ScrapeDispatch.parseContentType()` (`:289-309`), `EXPOSITION_CONTENT_TYPES` have no parameters, so Ktor's `ContentType.match()` compares only `type/subtype` and passes **all** parameters through verbatim. Sending `contentType = "text/plain; a=" + "x".repeat(3_500_000)` (~3.5 MiB, within gRPC's 4 MiB limit) emits a 3.5 MiB `Content-Type` HTTP response header, exceeding Prometheus/Go `net/http`'s 1 MiB `DefaultMaxHeaderBytes` and reverse-proxy header buffers.
2. **Control-character parameter name crashes response handling with `500`:** Ktor's `HttpHeaderValueParser.parseHeaderValueParameter()` accepts interior `\r`/`\n` in parameter names, and `HeaderValueWithParameters.toString()` appends parameter names unescaped. Sending `contentType = "text/plain; a\r\nb=1"` (even under the 4096-byte chunked cap) passes `parseContentType()`, then throws `IllegalHeaderValueException` inside `ResponseHeaders.append()`, bypassing the `text/plain` fallback, hitting `StatusPages`'s catch-all `500 Internal Server Error` handler (`ProxyHttpConfig.kt:124-126`), and logging the raw header value in the exception message.
3. **Arbitrary 32-bit HTTP status codes emitted on the wire:** `ScrapeResults.httpStatus` (`ScrapeDispatch.kt:312-313`) calls `HttpStatusCode.fromValue(srStatusCode)`, which constructs `HttpStatusCode(value, "Unknown Status Code")` for **any** `Int` (`-1`, `0`, `100`, `101`, `103`, `204`, `99999`), written verbatim by `CIOApplicationResponse`. Sending `100`/`101`, out-of-range integers, or `204 No Content` with a body (`statusCode.isSuccess()` is `true` for `200..299`) produces invalid or keep-alive-desynchronizing HTTP/1.1 responses.
4. **Plain-text `srContentAsText` bypasses `maxUnzippedContentSizeMBytes`:** `buildSuccessResponse()` (`ScrapeDispatch.kt:326-329`) computes `srContentAsText.toByteArray().size.toLong()` without comparing it to `maxSize`. Both `ProxyOptions.kt:413` (`0` = reject all content) and `configuration/proxy.md:311-312` promise `maxUnzippedContentSizeMBytes` bounds all unzipped results, yet any agent sending `zipped = false` (up to 4 MiB) bypasses limits `0..3` MiB.

**Impact:** A compromised agent can break Prometheus scrapes and reverse proxies via oversized `Content-Type` headers, induce 500s via control-char parameter names, emit protocol-violating HTTP status lines, and bypass sub-4 MiB `maxUnzippedContentSizeMBytes` caps.

**Fix:**
1. Cap unary `ScrapeResponse` header fields (`contentType`, `url`, `failureReason`) to `MAX_CHUNKED_HEADER_FIELD_LENGTH` in `writeResponsesToProxy`.
2. In `parseContentType`, rebuild the returned `ContentType` from only the matched `type/subtype` plus an explicit allowlist of safe exposition parameters (`version`, `charset`, `escaping`), validated to be short printable RFC 9110 tokens with no control characters (falling back to `Text.Plain.withCharset(Charsets.UTF_8)` otherwise).
3. Validate `srStatusCode`: allow `200..299` (excluding `204`/`205`) and `400..599`, mapping `1xx`, `3xx`, `204`/`205`, or out-of-range values to `502 Bad Gateway` (or suppressing body on `204`/`205`/`304`).
4. Enforce `byteCount <= maxSize` on the plain-text branch of `buildSuccessResponse()`, throwing `ProxyUtils.ZipBombException` (returning `413` `payload_too_large`) when exceeded.

---

### 39. ⬜ Dashboard `isOriginAllowed()` ignores URL scheme and bypasses explicit `allowedOrigins` restrictions

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt:336-345, 1086-1095`

**Problem:**
```kotlin
internal fun isOriginAllowed(origin: String?, host: String?, allowedOrigins: Collection<String>): Boolean {
  if (origin == null) return true
  val authority = runCatching { URI(origin).rawAuthority }.getOrNull()
  return allowedOrigins.any { normalizeOrigin(it) == normalizeOrigin(origin) } ||
    (authority != null && host != null && authority.equals(host, ignoreCase = true))
}
```
The same-authority fallback inspects only `URI(origin).rawAuthority` against `Host`, ignoring the URL scheme (`http://`, `ws://`, `ftp://`, or even URIs carrying paths/queries/fragments). Because the two clauses are joined with `||`, even when an operator explicitly configures `allowedOrigins = ["https://proxy.example.com"]`, `Origin: http://proxy.example.com` still passes whenever the TLS-terminating reverse proxy forwards `Host: proxy.example.com` (default ports 443 and 80 both omit the port).

**Impact:** When dashboard login is disabled (or auth is handled at the reverse proxy via IP/mTLS rather than cookies, making `pageGuard` the primary CSWSH boundary), a plain-HTTP page on `http://proxy.example.com` can open `wss://proxy.example.com/dashboard/events` and stream live proxy/agent inventory, target URLs, auth identities, and activity logs; with login enabled, `POST /login` (login CSRF) and non-schemeful clients remain exposed, and an explicit HTTPS-only `allowedOrigins` setting is silently bypassed.

**Fix:** Parse `origin` strictly (requiring `scheme?.lowercase() in setOf("http", "https")`, no `rawUserInfo`, empty `rawPath`, null `rawQuery`/`rawFragment`, and non-null `rawAuthority`). When `allowedOrigins` contains any entry for the same authority as `origin`, require an exact normalized origin match (including scheme) instead of falling back to scheme-blind `authority == host`.

---

### 40. ⬜ Unthrottled WebSocket per-frame full-HTML re-render amplification on dashboard `/events`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt:720-725`
- `src/main/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardService.kt:150-168, 286-296`
- `src/main/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardHtml.kt:442-474`

**Problem:**
`SnapshotCache` protects `pathMap`'s lock and `OUTGOING_FRAME_BUFFER = 32` drops slow readers, but the WebSocket receive loop invokes `push(session, snapshots.recent())` synchronously on **every** incoming `Frame.Text` with no view-equality check, debounce, or per-session rate limit:
```kotlin
for (frame in incoming) {
  if (frame is Frame.Text) {
    session.view = site.parseView(frame.readText())
    push(session, snapshots.recent())
  }
}
```
For `DashboardLayout.AGENT` and `DashboardLayout.PATH`, `shownRegion()` returns `null` and `ProxyDashboardHtml.pushFragment()` renders the full HTML DOM (all agents or all registered path rows, badges, links, history strips, and buttons). Any client able to open WebSockets to `/events` (up to `maxSessions`) can stream 2-byte `{}` or `{"layout":"PATH"}` frames while draining responses, triggering full `kotlinx.html` DOM rendering and multi-hundred-KB/multi-MB UTF-8 serialization per 2-byte frame inside the same JVM serving Prometheus scrapes.

**Impact:** CPU/GC and network egress amplification inside the proxy or agent JVM, degrading scrape latency or starving resources under a flood of tiny WebSocket frames.

**Fix:** Skip `push()` when the newly parsed `view` equals the previous `session.view` (no-op on unchanged state), and coalesce rapid view changes per session through a conflated channel spaced by ~50–100 ms (reusing `DashboardServer.spaced(...)`). Optionally cap incoming frames per second per session and close abusive sockets with `TRY_AGAIN_LATER`/`POLICY_VIOLATION`.

---

### 41. ⬜ Dashboard browser Basic-Auth restriction (`isBrowser`) is bypassed over plain HTTP on non-localhost hosts

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardServer.kt:392-395, 710, 807, 1041-1046`

**Problem:**
`DashboardServer` rejects HTTP Basic Auth from browsers so cached browser credentials cannot bypass the login form or survive Log out, identifying browsers solely via `request.headers["Sec-Fetch-Mode"] != null` (`:1046`). Per the W3C Fetch Metadata specification, browsers send `Sec-Fetch-*` headers **only to potentially trustworthy origins** (`https://`, `http://localhost`, `http://127.0.0.1`, `http://[::1]`). Because the embedded dashboard server serves plain HTTP, any internal/LAN deployment reached at `http://<lan-host-or-ip>:8094/dashboard` receives no `Sec-Fetch-Mode` header from Chrome, Firefox, or Safari.

Consequently, `isBrowser()` returns `false` on those deployments: a browser holding cached Basic Auth silently re-authenticates immediately after clicking **Log out**, and if it opens `/events` with Basic Auth (no session cookie), `Session.login` is `null` (`:710`), so neither `endLogin()` nor session expiration (`:807`) ever closes the WebSocket.

**Impact:** On plain-HTTP non-localhost deployments with dashboard authentication enabled, Log out and session expiration do not take effect for browsers holding cached Basic Auth credentials.

**Fix:** Broaden `isBrowser(request)` to also match `wantsPage(request)` (`Accept` containing `text/html`) or browser-only headers (`Origin`, `Referer`, `Upgrade-Insecure-Requests`, `Sec-Fetch-*`), or restrict Basic Auth to non-HTML endpoints / require session cookies for WebSocket `/events` when a browser header is present.

---

### 42. ⬜ Unescaped/unsanitized inputs in log output (`ProxyHttpConfig`, `MetricFilter`, and local config parse errors in `BaseOptions`)

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/main/kotlin/io/prometheus/proxy/ProxyHttpConfig.kt:95, 100, 147`
- `src/main/kotlin/io/prometheus/agent/filter/MetricFilter.kt:156`
- `src/main/kotlin/io/prometheus/common/BaseOptions.kt:849`

**Problem:**
1. **`ProxyHttpConfig.kt:95, 100, 147`:** `ProxyHttpRoutes.kt:114` notes that Ktor CIO allows non-whitespace control characters (ANSI escapes, NUL, C1 controls, U+2028/U+2029) in request targets and uses `ProxyPathManager.shownPath(path)` to prevent log forging. However, `getFormattedLog` (`:95, 100`, calling `request.toLogString()`) and `logClientGone` (`:147`, also installed on `DashboardServer` via `StatusPages`) log raw, untruncated, unescaped `call.request.path()` (at `DEBUG`; `requestLoggingEnabled` is `true` by default).
2. **`MetricFilter.kt:156`:** Logs target-controlled HTTP `Content-Type` verbatim at **`WARN`** level (`"Skipping metric filter for /$path: content type \"$contentType\" is not text"`) without `shownValue(...)` (contrast `ScrapeDispatch.kt:296, 305` and `AgentActivityLog.kt:212`). A compromised target can inject terminal escape sequences or oversized strings into the agent's WARN log (once per path lifetime).
3. **`BaseOptions.kt:849`:** On a local config file parse error, `logger.error(e) { "Exception: ${e.simpleClassName} - ${e.message}" }` logs the raw `ConfigException` and message without `sanitizeUrlsInText(...)`. A HOCON syntax error on or near an unquoted URL with credentials (`url = http://user:pass@host/metrics`) quotes the offending text in `ConfigException.Parse`, leaking credentials into startup error logs (unlike remote URL errors at `:837` and `ConfigLoadException`).

**Impact:** Log forging / terminal escape injection on HTTP scrape/dashboard ports (when `DEBUG` is enabled) and at `WARN` from scraped targets; potential credential leakage into startup error logs on local config syntax errors.

**Fix:** Pass `request.path()` through `ProxyPathManager.shownPath(...)` / `AgentValues.shownValue(...)` in `ProxyHttpConfig.kt:95, 100, 147`; wrap `contentType` in `AgentValues.shownValue(contentType)` in `MetricFilter.kt:156`; and log `sanitizeUrlsInText(e.message.orEmpty())` without passing the raw exception at `BaseOptions.kt:849` (matching `:837`).

---

### 43. ⬜ Helm charts mount the default Kubernetes ServiceAccount token unnecessarily (`automountServiceAccountToken: false` omitted)

**Severity:** low · **Confidence:** confirmed

**Where:**
- `charts/prometheus-proxy/templates/deployment.yaml:31-38`
- `charts/prometheus-agent/templates/deployment.yaml:30-37`

**Problem:**
Both Helm charts apply strong pod/container hardening (`runAsNonRoot: true`, `readOnlyRootFilesystem: true`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `seccompProfile: RuntimeDefault`, loopback dashboard binding), but neither sets `automountServiceAccountToken: false` on the Pod spec even though neither `prometheus-proxy` nor `prometheus-agent` talks to the Kubernetes API server.

**Impact:** Kubernetes automatically mounts the namespace's default ServiceAccount JWT at `/var/run/secrets/kubernetes.io/serviceaccount/token` inside every proxy and agent pod (CIS Kubernetes Benchmark 5.1.6), increasing post-compromise blast radius unnecessarily.

**Fix:** Set `automountServiceAccountToken: false` (or expose `automountServiceAccountToken: false` as a configurable default in `values.yaml` rendered on `spec:`) in both `charts/prometheus-proxy/templates/deployment.yaml` and `charts/prometheus-agent/templates/deployment.yaml`.

---

## 🏗️ Build, CI, Packaging, Helm Charts & Demos

### 44. ✅ Helm charts crash with nil pointer on `helm upgrade --reuse-values` from 4.6.0 (`dashboard` / `dashboard.login`)

**Severity:** high · **Confidence:** confirmed (reproduced live with `helm template`)

**Where:**
- `charts/prometheus-proxy/templates/deployment.yaml:86-94`
- `charts/prometheus-proxy/templates/NOTES.txt:19-24`
- `charts/prometheus-agent/templates/deployment.yaml:54, 64-67, 83-98`
- `charts/prometheus-agent/templates/_helpers.tpl:48-50`
- `charts/prometheus-agent/templates/NOTES.txt:13, 22-28`
- `charts/prometheus-agent/README.md:62` (documents `helm upgrade prometheus-agent ... --reuse-values --set dashboard.enabled=true`)

**Problem:**
Released chart `4.6.0` had `dashboard: { enabled: false, port: 8094 }` (no `host`, no `login`) in `charts/prometheus-proxy/values.yaml` and **no `dashboard` key at all** in `charts/prometheus-agent/values.yaml` (confirmed via `git show 4.6.0:charts/*/values.yaml`). Under Helm 3, `helm upgrade --reuse-values` carries forward the previous release's values without merging newly added keys from the upgraded chart's `values.yaml`. Commit `ef58ecb6` guarded `dashboard.host` with `hasKey .Values.dashboard "host"` specifically to survive `--reuse-values` from older releases, but left `.Values.dashboard.login` (both charts) and `.Values.dashboard` / `.Values.dashboard.port` (agent chart) unguarded.

Reproduced directly with `helm template`:
- `helm template test charts/prometheus-proxy --set dashboard.login=null`:
  ```
  Error: prometheus-proxy/templates/deployment.yaml:86:28
    executing "prometheus-proxy/templates/deployment.yaml" at <.Values.dashboard.login.existingSecret>:
      nil pointer evaluating interface {}.existingSecret
  ```
  (`NOTES.txt:19` fails the same way if `dashboard.enabled=true`.)
- `helm template test charts/prometheus-agent --set dashboard=null`:
  ```
  Error: prometheus-agent/templates/deployment.yaml:64:26
    executing "prometheus-agent/templates/deployment.yaml" at <.Values.dashboard.enabled>:
      nil pointer evaluating interface {}.enabled
  ```
  (`_helpers.tpl:49` also fails running `hasKey` on `nil`.)
- `helm template test charts/prometheus-agent --set proxy.hostname=proxy:50051 --set dashboard.enabled=true --set dashboard.port=null --set dashboard.login=null --set dashboard.host=null` (simulating the exact command in `charts/prometheus-agent/README.md:62` upgrading from 4.6.0):
  ```
  Error: prometheus-agent/templates/deployment.yaml:90:28
    executing "prometheus-agent/templates/deployment.yaml" at <.Values.dashboard.login.existingSecret>:
      nil pointer evaluating interface {}.existingSecret
  ```
  And even past line 90, missing `dashboard.port` renders an empty `containerPort` and `DASHBOARD_PORT`.

**Impact:** Every user upgrading either Helm chart from 4.6.0 with `--reuse-values` — including following the exact upgrade command documented in `charts/prometheus-agent/README.md:62` — hits a hard template nil-pointer failure.

**Fix:** Guard nested maps with `default dict` before dereferencing:
- Define `$dashboard := .Values.dashboard | default dict` and `$login := $dashboard.login | default dict` in `_helpers.tpl`, `deployment.yaml`, and `NOTES.txt` of both charts.
- Check `hasKey $dashboard "host"` instead of `hasKey .Values.dashboard "host"`.
- In `prometheus-agent`, default `$dashboard.enabled` to `false` and `$dashboard.port` to `8095`.
- Guard login env rendering on `$login.existingSecret`, defaulting `$login.username` to `"admin"` and `$login.key` to `"password"`.
- Add a `helm template` regression check in `Makefile` (`helm-lint`) and CI rendering both charts against 4.6.0-shaped values (`--set dashboard.login=null` and `--set dashboard=null`).

**Resolution:** Fixed in #586. It was broader than reported: a plain `--reuse-values` upgrade of the proxy chart failed too, with no `--set`, as `deployment.yaml` read `dashboard.login` whether or not the dashboard was on. Each chart's `<chart>.dashboard` helper renders the dashboard values, each one missing at its default (the host keeps its `hasKey` rule, so an empty host stays empty). `deployment.yaml`, `NOTES.txt`, and `dashboardIsLocal` read it with `fromYaml`, in place of the `dashboardHost` helper, and a comment says its defaults repeat `values.yaml`'s. For every `ci/` values file, with and without dashboard, host, and login overrides, the render, `NOTES.txt` included, is byte-identical to before. New `ci/reuse-4.6.0*-values.yaml` files, which `make helm-lint` lints, renders, and validates, null out what 4.6.0 lacked, for each chart and, for the agent's, with the README's `--set dashboard.enabled=true`. They fail on the old charts with the reported nil pointers and pass now. #43 stays open.

---

### 45. ⬜ Inverted severity thresholds in `grafana/prometheus-agents.json` (Panels 27 "Not on Primary" and 38 "Since Last Discovery")

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `grafana/prometheus-agents.json:670-694` (Panel 27: `"Not on Primary"`)
- `grafana/prometheus-agents.json:1152-1190` (Panel 38: `"Since Last Discovery"`)

**Problem:**
Both panels carry thresholds copy-pasted from Panel 26 (`"Connected"`):
```json
"steps": [
  { "color": "red", "value": null },
  { "color": "green", "value": 1 }
]
```
- **Panel 27 (`"Not on Primary"`):** queries `count(agent_proxy_endpoint_index{job=~"$agent"} > 0) or vector(0)`. When `0` agents have failed over (healthy), the stat renders **red**; when `>= 1` agents are off primary (degraded), it renders **green**.
- **Panel 38 (`"Since Last Discovery"`):** queries `time() - max by (job) (agent_discovery_last_success_timestamp_seconds{job=~"$agent"})`. Immediately after a successful discovery (`< 1s`), it renders **red**; when discovery has been failing for hours or days (`>= 1s`), it renders **green**.

**Impact:** The shipped Agents Grafana dashboard shows false-alarm red panels during healthy operation and turns green precisely when failover or stale discovery occurs.

**Fix:** Invert Panel 27 to `{ "color": "green", "value": null }` and `{ "color": "yellow", "value": 1 }` (or `"red"`). Invert Panel 38 to `{ "color": "green", "value": null }` with warning/critical thresholds appropriate for stale discovery (e.g., `"yellow"` at `120`, `"red"` at `300`, or a single neutral/green step).

---

### 46. ⬜ `ProxyScrapeSuccessRateLow` alert and Proxy Grafana Panel 4 silently miss 0% success rate when no scrape has succeeded since startup

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `grafana/alerts.yml:17-20`
- `grafana/prometheus-proxy.json:188`
- `src/main/kotlin/io/prometheus/proxy/ProxyMetrics.kt:145-150`

**Problem:**
`proxy_scrape_requests_total` is registered without `startingAtZero`, so the `{type="success"}` time series does not exist until at least one scrape succeeds (`ProxyUtils.kt:121`). Both `ProxyScrapeSuccessRateLow` (`grafana/alerts.yml:19-20`) and Panel 4 (`"Scrape Success Rate"`, `grafana/prometheus-proxy.json:188`) evaluate:
```promql
sum(rate(proxy_scrape_requests_total{type="success"}[5m]))
  / sum(rate(proxy_scrape_requests_total[5m])) < 0.99
```
If a newly started or restarted proxy fails 100% of scrapes (e.g., after a bad deploy or when no agents can register), the numerator is an empty vector; in PromQL, empty divided by non-empty yields an **empty vector**, not `0`.

**Impact:** During a total scrape outage from proxy startup, `ProxyScrapeSuccessRateLow` never fires and Panel 4 shows `"No data"` instead of `0%` (red).

**Fix:** Rewrite the ratio in `grafana/alerts.yml:19-20` and `grafana/prometheus-proxy.json:188` as:
```promql
(sum(rate(proxy_scrape_requests_total{type="success"}[5m])) or vector(0))
  / sum(rate(proxy_scrape_requests_total[5m])) < 0.99
```
(When idle, `sum(rate(proxy_scrape_requests_total[5m]))` is `0`, yielding `NaN`, which does not fire `< 0.99`, or absent, yielding empty vector.) Alternatively, pre-initialize `startingAtZero = listOf("success")` on `SCRAPE_REQUEST_COUNT` in `ProxyMetrics.kt:146`.

---

### 47. ⬜ `etc/consumer-check/check.sh` silently skips the last JAR on the Maven classpath

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `etc/consumer-check/check.sh:88, 154-159`

**Problem:**
Verified directly in `maven-dependency-plugin-3.11.0.jar` (`BuildClasspathMojo.storeClasspathFile`): Maven writes the `:`-separated classpath string via `Writer.write(cpString)` with **no trailing newline**. `check.sh:159` translates `:` to `\n` via `tr ':' '\n' < "$classpath"`, leaving the final JAR line with no trailing newline. Line 154 loops with `while IFS= read -r jar; do`; in Bash, `read` returns exit status `1` on EOF when a line has no trailing newline, so the loop body **never executes for the final JAR** on the classpath.

**Impact:** Any bundled `logback.xml` / `logback-test.xml` in the last JAR on the resolved classpath goes unchecked, and if `prometheus-proxy-$version.jar` ever lands last in Maven's classpath ordering, the check spuriously fails with `prometheus-proxy-$version.jar isn't on the classpath`.

**Fix:** Change `etc/consumer-check/check.sh:154` to:
```bash
while IFS= read -r jar || [ -n "$jar" ]; do
```

---

### 48. ⬜ `docs.yml/build` is omitted from `RELEASE_REQUIRED_JOBS` in `Makefile` despite comments and docs stating `make release-check` requires it

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `Makefile:72-77`
- `.github/workflows/docs.yml:4-6`
- `docs/RELEASE.md:38-39`
- `src/test/kotlin/io/prometheus/misc/CiHygieneTest.kt:196-197`

**Problem:**
Four places — `.github/workflows/docs.yml:4-6`, `docs/RELEASE.md:38-39`, `CiHygieneTest.kt:196-197`, and `Makefile:72-73` itself (*"The workflow file tells apart jobs with the same name, such as ci.yml's and docs.yml's build"*) — state that `make release-check` requires `docs.yml`'s `build` job (which builds both the Zensical site and Dokka KDocs on every push to `master`). However, `RELEASE_REQUIRED_JOBS` (`Makefile:75-77`) includes `ci.yml/docs-site` (Zensical only, no Dokka KDocs) and **omits `docs.yml/build`**.

**Impact:** If `docs.yml/build` fails (e.g., broken Dokka KDoc cross-reference), is still queued, or didn't run on `master`, `make release-check` passes anyway and `make release` publishes before `make gh-docs` fails deploying the docs site.

**Fix:** Add `docs.yml/build` to `RELEASE_REQUIRED_JOBS` in `Makefile:75-77`.

---

### 49. ⬜ `build.gradle.kts` `checkedFiles()` omits `charts/` and `.claude/skills/publishing-release/SKILL.md` (and doesn't exclude `demos/**/__pycache__/**`)

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `build.gradle.kts:430-459`
- `src/test/kotlin/io/prometheus/misc/ReleaseCheckTest.kt:55, 81-87, 175-202, 313`
- `Makefile:52-65` (`RELEASE_VERSION_FILES`)
- `docs/TESTING.md:50-56`

**Problem:**
`ReleaseCheckTest` (in `unitTest`) reads `charts/prometheus-proxy/Chart.yaml`, `charts/prometheus-agent/Chart.yaml` (via `RELEASE_VERSION_FILES`), and `.claude/skills/publishing-release/SKILL.md`. Neither `charts/` nor `.claude/skills/publishing-release/SKILL.md` is declared in `checkedFiles()` (`build.gradle.kts:430-459`). Editing either `Chart.yaml` or `SKILL.md` without touching another tracked input leaves `unitTest` falsely `UP-TO-DATE` or `FROM-CACHE`, bypassing `ReleaseCheckTest`.

Conversely, `fileTree("demos")` at `build.gradle.kts:445` excludes `**/build/**`, `**/.gradle/**`, `**/.kotlin/**`, `**/target/**`, and `*/docker/.env*`, but does not exclude `**/__pycache__/**`. Running the Python demos locally creates `demos/basics/app/__pycache__` and `demos/network/app/__pycache__`, unnecessarily busting the `unitTest` build cache.

**Fix:** In `build.gradle.kts:430-459`, add `fileTree("charts")` and `".claude/skills/publishing-release/SKILL.md"` to `checkedFiles()`, add `"**/__pycache__/**"` to `fileTree("demos")`'s exclusions, and update the input list in `docs/TESTING.md:50-56`.

---

### 50. ⬜ `demos/nginx/bin/screenshots.sh` `shows_reconnecting` uses stale 4.7.0 top-bar pixel coordinates after 4.7.1's 1-button theme switcher

**Severity:** low · **Confidence:** plausible (layout shift confirmed; live trigger on next screenshot capture)

**Where:**
- `demos/nginx/bin/screenshots.sh:171-178`
- `src/main/kotlin/io/prometheus/common/dashboard/DashboardCss.kt`

**Problem:**
`shows_reconnecting()` crops `65x15+722+17` (CSS `x=722..787, y=17..32`) to detect red text in `reconnecting...` (`mean.r - mean.g > 0.02`). In the 4.7.0-era `dashboard-reconnecting.webp` (last captured in `ea8f9a0b`), `reconnecting...` sits at `x=722..838` because the old 3-button theme picker occupied `x=1180..1265` (~85px). In 4.7.1 (`#575`), the theme picker shrank to a single `30px` button (`x=1235..1265`), shifting `.bar-right` ~55px right and pushing `reconnecting...` to `~x=777..893` — leaving the `x=722..787` crop box almost entirely on empty background.

**Impact:** The next `make screenshots` run for the nginx demo will likely exhaust all 5 retries in `shoot_reconnecting` and abort.

**Fix:** Shift/widen the crop region in `demos/nginx/bin/screenshots.sh:176` to cover the updated indicator position (e.g., `140x15+760+17`) and verify on the next screenshot capture.

---

### 51. ⬜ Dead color overrides in `demos/network/grafana/dashboards/network-overview.json` (Panels 14 and 18)

**Severity:** low · **Confidence:** confirmed

**Where:**
- `demos/network/grafana/dashboards/network-overview.json:1025-1088` (Panel 14: `"Disconnects, by proxy and reason"`)
- `demos/network/grafana/dashboards/network-overview.json:1277-1341` (Panel 18: `"Agents off their primary"`)

**Problem:**
Both panels carry copy-pasted `byName` overrides for `"prometheus-proxy"` (orange) and `"prometheus-proxy-standby"` (blue) that never match any rendered series:
- Panel 14 formats series as `"{{job}}: {{reason}}"` (e.g., `"prometheus-proxy: eviction"`), so exact `byName` matches on `"prometheus-proxy"` / `"prometheus-proxy-standby"` never fire.
- Panel 18 queries `max by (job) (agent_proxy_endpoint_index)` with `legendFormat: "{{job}}"`, whose `job` labels are the **agents** (`agent-hq`, `agent-branch`), not the proxies.

**Impact:** Intended consistent proxy/agent color coding silently falls back to Grafana's default palette on both panels.

**Fix:** In Panel 14, switch matchers from `byName` to `byRegexp` (`/^prometheus-proxy:/` and `/^prometheus-proxy-standby:/`) or drop the dead overrides; in Panel 18, match `agent-hq` / `agent-branch` or clear `"overrides": []`.

---

### 52. ⬜ Minor build/script drift (`Makefile` `.PHONY`, `demos/ktor/demo.sh`, `bin/docker-*.sh`)

**Severity:** low · **Confidence:** confirmed

**Where:**
- `Makefile:1-16, 825`
- `demos/ktor/demo.sh:60, 82`
- `bin/docker-agent.sh:20-23` and `bin/docker-proxy.sh:20-22`

**Problem:**
1. `Makefile:825`: `readme-diagram` is the only Make target omitted from `.PHONY` (`Makefile:1-16`).
2. `demos/ktor/demo.sh:82`: `NEXT_AGENT_DASHBOARD_URL` is declared and unused (the sole `shellcheck -S warning` finding across the repository: `SC2034`), leftover after `#583` moved links to `SCREENS_URL`. Line 60's comment also says `NEEDS_RELEASE_AFTER=4.7.0` is for *"the Ktor plugin, and the agent's own dashboard"*, both of which shipped in 4.7.0 (it actually needs 4.7.1's screens page and frameable agent dashboards, as `demos/basics/demo.sh:63-64` states).
3. `bin/docker-agent.sh:20-22`: Still publishes `-p 8083:8083` even though `examples/simple.conf` switched to `agent.metrics.proxyPath = agent_metrics` in 4.7.1 and leaves port `8083` off; both `bin/docker-agent.sh:20` and `bin/docker-proxy.sh:20` bind `0.0.0.0` (while `etc/compose/proxy.yml` and the docs' `docker run` commands were hardened to `127.0.0.1`), fetch `simple.conf` from `master` instead of `$VERSION`, and `bin/docker-agent.sh:22` still hardcodes `PROXY_HOSTNAME=mymachine.lan`.

**Impact:** Minor build/script hygiene issues and stale helper script defaults.

**Fix:** Add `readme-diagram` to `.PHONY` in `Makefile`; drop `NEXT_AGENT_DASHBOARD_URL` and update the line 60 comment in `demos/ktor/demo.sh`; remove `-p 8083:8083`, bind `127.0.0.1:`, and reference `$VERSION` in `bin/docker-{agent,proxy}.sh`.

---

## 🧪 Tests

### 53. ⬜ `ProxyPathManagerLincheckTest` externally serializes all `ProxyPathManager` mutations behind `pathLock` and omits `maxPathsPerIdentity`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/test/kotlin/io/prometheus/proxy/ProxyPathManagerLincheckTest.kt:114-116, 152-154, 166-182, 190-192, 202-204, 230-240, 301-307`
- `src/main/kotlin/io/prometheus/proxy/ProxyPathManager.kt:136-175, 415-428, 482-498, 522-535, 783-805, 831-840`

**Problem:**
1. **Internal locking is masked by external `pathLock`:** Every `ProxyPathManager` mutation in `ProxyPathManagerOps` (`addPath` in `register` and `reclaim`, `removePath` in `unregister`, and `removeFromPathManager` in `disconnect`) is wrapped in `synchronized(pathLock)` on the same monitor. Only `AgentContextManager.getAgentContext` and `removeFromContextManager` run outside `pathLock`. Consequently, **no two `ProxyPathManager` mutations can ever race each other** in this Lincheck test. If a refactor drops `synchronized(pathMap)`, splits `addValidatedPath` across two lock acquisitions, or reads/writes `pathCounts`/`countedIdentities`/`identityPathCounts` outside `pathMap`, `ProxyPathManagerLincheckTest` still passes 100% of the time.
2. **Per-identity path quota (`#560`) is unexercised:** `PROXY` only passes `-Dproxy.internal.maxPathsPerAgent=2` (`:303`), leaving `maxPathsPerIdentity = 0`, and `validate()` (`:230-240`) checks only `pathCountFor(agentId)`. Even though `ProxyPathManagerOps` already defines multi-agent identities (`TEAM_A` has 5 agents, `TEAM_B` has 2), concurrent updates to `countedIdentities`, `identityPathCounts`, and the `replacesOwn` quota exemption under concurrent takeovers/reclaims/disconnects are never stressed.

**Impact:** The primary concurrency stress test for `ProxyPathManager` cannot detect broken internal synchronization in `ProxyPathManager` itself and does not verify per-identity path accounting invariants under contention.

**Fix:** Remove `pathLock` (or add a dedicated unguarded `ProxyPathManagerConcurrencyOps` alongside the atomic-observer reclaim check) so `addPath`, `removePath`, and `removeFromPathManager` genuinely race on `ProxyPathManager`'s own `synchronized(pathMap)` monitor. Configure `-Dproxy.internal.maxPathsPerIdentity=3` in `PROXY` and assert in `validate()` that `pathManager.identityPathCountFor(identity)` equals the actual served path count per identity and never exceeds the configured limit.

---

### 54. ⬜ Tautological `Bug #1` test in `AgentTest` tests a local copy-pasted `Semaphore` instead of `Agent.processScrapeRequests`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/test/kotlin/io/prometheus/agent/AgentTest.kt:965-1018`
- `src/main/kotlin/io/prometheus/Agent.kt:570-589`

**Problem:**
`"Bug #1: semaphore.acquire() before launch should limit waiting coroutines"` instantiates a local `kotlinx.coroutines.sync.Semaphore(2)` and local `Channel`, copies the loop inline (`// Mirrors the loop in Agent.processScrapeRequests`), and calls **zero production code**. No other spec in the entire test suite exercises `Agent.processScrapeRequests`'s real `Semaphore(options.maxConcurrentHttpClients)` or `maxConcurrentHttpClients` backpressure.

**Impact:** Moving `semaphore.acquire()` back inside `launch { }` in `Agent.kt:579-580` (reintroducing Bug #1) or breaking `semaphore.release()`/`decrementBacklog(1)` in `Agent.kt:583-586` would leave `AgentTest` completely green.

**Fix:** Make `processScrapeRequests` `internal` (or drive it through a test `Agent` with `maxConcurrentHttpClients = 2` and a real `AgentConnectionContext`), feed gated `ScrapeRequestAction` lambdas into `connectionContext.scrapeRequestActions()`, and assert on the real production method that concurrency never exceeds `maxConcurrentHttpClients`, backpressure bounds launched coroutines, and `semaphore.release()`/`decrementBacklog(1)` execute even when an action throws.

---

### 55. ⬜ Leaked Logback logger level mutation in `BaseOptionsTest` contaminates subsequent specs in `unitTest`

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/test/kotlin/io/prometheus/common/BaseOptionsTest.kt:209-215`
- `src/main/kotlin/io/prometheus/common/BaseOptions.kt:737-747`
- `src/test/kotlin/io/prometheus/common/LogCapture.kt:35-53`
- `src/test/kotlin/io/prometheus/common/UtilsTest.kt:68, 85-87`

**Problem:**
`"agent logLevel should be settable via --log_level"` calls `agentOptions(["--name", "test", "--proxy", "host", "--log_level", "DEBUG"], false)`. Because `exitOnMissingConfig = false` (`embedded = true`), `BaseOptions.assignLogLevel` sets Logback's JVM-wide `"io.prometheus"` logger to `Level.DEBUG`, and `BaseOptionsTest` never restores it (unlike `AgentOptionsTest.kt:51-67`, which saves and restores both `ROOT` and `"io.prometheus"` in `finally`). Every spec running later in the shared `unitTest` JVM inherits `Level.DEBUG` across all `io.prometheus.*` loggers. Over a dozen tests call `captureLogs<...>()` without an explicit `level` parameter (including `ProxyServiceImplTest.kt:1281, 1300` asserting `events.size shouldBe 1` / `events.single()`, `UtilsTest.kt:453, 457`, `FileDiscoverySourceTest.kt:134, 144, 157, 171`, `AgentPathManagerTest.kt:943, 976`, `AgentHttpServiceTest.kt:2069, 2116`, and `ScrapeRequestManagerTest.kt:408`). Similarly, `UtilsTest.kt:68, 85-87` resets `ROOT` to a hardcoded `Level.INFO` instead of restoring its prior level.

**Impact:** Log-capture assertions in `unitTest` silently depend on spec execution order and can flake whenever a `logger.debug` line is added.

**Fix:** Save and restore `(LoggerFactory.getLogger("io.prometheus") as Logger).level` in `try/finally` around `BaseOptionsTest.kt:209-215`, and restore the captured prior `ROOT` level instead of hardcoded `Level.INFO` in `UtilsTest.kt:68, 85-87`.

---

### 56. ⬜ Five container specs leak Docker containers, networks, and HTTP clients when `beforeSpec` fails partway through

**Severity:** medium · **Confidence:** confirmed

**Where:**
- `src/test/kotlin/io/prometheus/containers/ContainersSmokeTest.kt:53-74`
- `src/test/kotlin/io/prometheus/containers/ContainersServiceDiscoveryTest.kt:61-82`
- `src/test/kotlin/io/prometheus/containers/ContainersProxyHttpTest.kt:90-110`
- `src/test/kotlin/io/prometheus/containers/ContainersConsolidatedTest.kt:72-84`
- `src/test/kotlin/io/prometheus/containers/ContainersLargePayloadTest.kt:80-90`

**Problem:**
Each of these five specs starts 3–5 Docker containers sequentially inside `beforeSpec` without a `try/catch` rollback, relying exclusively on `afterSpec` to stop containers and close `network` and `httpClient`. Verified directly in Kotest 6.2.7 (`BeforeAfterSpecCallbacksInterceptor.kt`), any exception thrown from `beforeSpec` aborts the spec immediately and **never invokes `afterSpec`**. Contrast with the remaining container specs (`ContainersTlsTest`, `ContainersNginxTest`, `ContainersScalingTest`, etc.), which guard `beforeSpec` with `try/finally` or `stopQuietly`/`closeQuietly`.

**Impact:** Because `ContainerTestsConfig` runs three container specs concurrently in one JVM, a mid-`beforeSpec` failure in any of these five specs leaks already-started containers, Docker networks, and HTTP clients for the remainder of the suite run.

**Fix:** Extract a `teardown()` method (using `stopQuietly`/`closeQuietly`) in each of the five specs and call it both in `afterSpec` and in a `catch (t: Throwable) { teardown(); throw t }` wrapper in `beforeSpec`.

---

### 57. ⬜ Hygiene guard blind spots in `DemoScreensLinksTest` and `ConfigFileKeysTest` for recent `/screens` and `links` features

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/test/kotlin/io/prometheus/misc/DemoScreensLinksTest.kt:361`
- `src/test/kotlin/io/prometheus/misc/ConfigFileKeysTest.kt:198-202`
- `config/config.conf:63-76`

**Problem:**
1. `DemoScreensLinksTest.DEMOS` (`:361`) hardcodes `listOf(BASICS, NETWORK, KTOR)` without asserting that every demo whose config defines `proxy.dashboard.screens` is included. Unlike `DemoTourTablesTest`, `DemoReleaseGateTest`, and `ConfigFileKeysTest` (which auto-discover `demos/*`), migrating another demo onto `/screens` without adding it to `DEMOS` silently skips all screens-link and `ProxyDown` checks.
2. `ConfigFileKeysTest.listEntrySchemas` (`:198-202`) lists only `proxy.auth`, `agent.pathConfigs`, and `agent.filters`, omitting the two newer lists-of-objects in `config/config.conf:63-76`: `proxy.dashboard.links` and `proxy.dashboard.screens`. A doc HOCON snippet illustrating a standalone `links` or `screens` entry object without its enclosing `proxy.dashboard` block will mis-validate against `undeclaredInBlock`.

**Impact:** Newly migrated `/screens` demos can bypass link checks silently, and doc snippets of `links`/`screens` list elements lack schema coverage.

**Fix:** Add a test in `DemoScreensLinksTest` scanning `demos/*/config/*.conf` for `proxy.dashboard.screens` and asserting every matching demo is in `DEMOS`; add `"proxy.dashboard.links"` and `"proxy.dashboard.screens"` to `ConfigFileKeysTest.listEntrySchemas` (or discover all object-list schemas dynamically from `config/config.conf`).

---

### 58. ⬜ Weak timeout assertion and untested `stopTimeout == null` branch in Ktor `AgentLifecycleTest` (plus missing `finally` in `EmbeddedAgentInfoTest`)

**Severity:** low · **Confidence:** confirmed

**Where:**
- `ktor-plugin/src/test/kotlin/io/prometheus/agent/ktor/AgentLifecycleTest.kt:285-297, 366-380`
- `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/AgentLifecycle.kt:150-164`
- `src/test/kotlin/io/prometheus/agent/EmbeddedAgentInfoTest.kt:82-92`

**Problem:**
1. `AgentLifecycleTest.kt:285-297` (`"an agent still stopping when the wait runs out doesn't hold up the app's disposal"`) asserts only `release.count shouldBe 1` after raising `ApplicationStopping`. Because nothing counts down `release` before line 295, the assertion passes even if `shutdown()` was never called, `finishStop()` never waited, or the timeout warning (`AgentLifecycle.kt:162-163`) was never logged. It also lacks `try/finally` around `release.countDown()`, leaving the daemon thread parked if an assertion fails (same missing `finally` in `EmbeddedAgentInfoTest.kt:82-92`).
2. `AgentLifecycle.kt:155-158` (`if (timeout == null) { thread.join(); return }`, reachable via `agent { stopTimeout = null }`) has zero test coverage because `TestApp.lifecycle` always configures a non-null `stopTimeout` (`:371`).

**Impact:** Regressions in `AgentLifecycle.finishStop()`'s timeout warning and unbounded-wait path would go undetected.

**Fix:** In `AgentLifecycleTest.kt:285-297`, signal a `shutdownStarted` latch inside the mocked `shutdown()`, wrap `release.countDown()` in `finally`, and assert `shutdownStarted` triggered and the `"still stopping after 50ms"` WARN was logged. Add a test setting `agent { stopTimeout = null }` exercising the unbounded `thread.join()` branch. Wrap `release.countDown()` in `finally` in `EmbeddedAgentInfoTest.kt:82-92`.

---

### 59. ⬜ Vacuous JSON assertion in `UtilsTest` for `Utils.getVersionDesc(true)`

**Severity:** low · **Confidence:** confirmed

**Where:**
- `src/test/kotlin/io/prometheus/common/UtilsTest.kt:280-285`
- `src/main/kotlin/io/prometheus/common/Utils.kt:44`

**Problem:**
`"getVersionDesc should return JSON when requested"` calls `Utils.getVersionDesc(true)` and asserts only `versionDesc.shouldNotBeEmpty()` above the comment `// JSON output should contain braces or JSON structure`. No other test in the repository calls `getVersionDesc`.

**Impact:** If `asJson = true` were ignored and plain text returned, the test would still pass.

**Fix:** Parse `Utils.getVersionDesc(true)` with `Json.parseToJsonElement(versionDesc).jsonObject`, assert `"version"` is present, and assert it differs from `Utils.getVersionDesc(false)`.

---

## 📚 Documentation

### 60. ⬜ `README.md` omits 4.7.1's `/screens` page and default-open framing behavior change from "New Features", and omits `/screens` (and `SAME_ORIGIN`) from "Operational Dashboard"

**Severity:** medium · **Confidence:** confirmed

**Where:**
- Doc: `README.md:93-107, 802-838`
- Code / Changelogs: `config/config.conf:94-95, 220`; `src/main/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardService.kt:130-134, 203-204, 273`; `src/main/kotlin/io/prometheus/common/dashboard/Framing.kt:31-41, 49-57`; `CHANGELOG.md:34-45, 66-69`; `RELEASE_NOTES.md:9-21, 38-47`

**Problem:**
1. `README.md:95-103` summarizes 4.7.1 strictly as *"Version 4.7.1 adds, for apps that embed the agent:"*, listing only `onFailure` and `stopTimeout`. It omits both the `/screens` page (`proxy.dashboard.screens`) and the `framingEnabled` default change (`true` by default, reversing 4.7.0's refusal — called out under "Before you upgrade" in `RELEASE_NOTES.md:15-21`).
2. `README.md:802-838` ("Operational Dashboard") never mentions `/screens` or `proxy.dashboard.screens`, and line 831 states unconditionally *"set `proxy.dashboard.framingEnabled = false` and every response forbids framing"*, omitting that when `proxy.dashboard.screens` is listed, `framingEnabled = false` resolves to `Framing.SAME_ORIGIN` (`frame-ancestors 'self'` / `X-Frame-Options: SAMEORIGIN`, `Framing.kt:38, 55`) so `/screens` can frame the proxy's own dashboard.

**Impact:** Readers of the repository README miss two major 4.7.1 changes (including a security-relevant default change) and receive an inaccurate description of `framingEnabled = false` when screens are configured.

**Fix:** Expand `README.md:95-103` to cover `/screens` and `framingEnabled`, and in `README.md:802-838` document `proxy.dashboard.screens` and clarify line 831's same-origin exception when screens are configured.

---

### 61. ⬜ `website/prometheus-proxy/docs/embedded-upgrading.md` is missing an "Upgrading from 4.7.0" section for 4.7.1's behavior changes affecting embedders

**Severity:** medium · **Confidence:** confirmed

**Where:**
- Doc: `website/prometheus-proxy/docs/embedded-upgrading.md:1-12`
- Code / Changelogs: `config/config.conf:220`; `src/main/kotlin/io/prometheus/agent/dashboard/AgentDashboardService.kt:139`; `src/main/kotlin/io/prometheus/agent/EmbeddedAgentConfig.kt:134, 167, 669-676`; `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgentProperties.java:116-117`; `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgent.java:129, 234-241`; `RELEASE_NOTES.md:15-21, 35-37, 66-67`; `CHANGELOG.md:30-33, 66-71`

**Problem:**
`embedded-upgrading.md` starts at `## Upgrading from 4.6.x` and misses three 4.7.1 behavioral changes that directly affect embedding applications:
1. `agent.dashboard.framingEnabled` now defaults to `true` (whereas 4.7.0 refused framing on every response) — `RELEASE_NOTES.md:17-21` specifically calls out how embedders restore refusal (`setting("dashboard.framingEnabled", false)` or `settings.dashboard.framingEnabled`).
2. `EmbeddedAgentConfig.startIfConfigured(onFailure)` now catches and logs exceptions thrown by `onFailure` and returns `null`, whereas in 4.7.0 an exception from `onFailure` escaped.
3. The Spring Boot starter's `stop()` now defaults to a 30-second `stop-timeout` and returns with a warning on expiry instead of throwing `TimeoutException`.

**Impact:** Embedders upgrading from 4.7.0 to 4.7.1 consulting the dedicated upgrade guide miss all three behavior changes.

**Fix:** Add an `## Upgrading from 4.7.0` section at the top of `website/prometheus-proxy/docs/embedded-upgrading.md` documenting those three changes.

---

### 62. ⬜ `docs/PRODUCT.md` has a factual error on proxy Scrape-now metrics and multiple pre-4.7.1 stale statements

**Severity:** medium · **Confidence:** confirmed

**Where:**
- Doc: `docs/PRODUCT.md:65-66, 71, 113-114, 159, 198-199`
- Code / Docs: `src/main/kotlin/io/prometheus/Agent.kt:818-834`; `src/main/kotlin/io/prometheus/proxy/dashboard/ScrapeNow.kt:45-47`; `src/main/kotlin/io/prometheus/agent/dashboard/AgentScrapeNow.kt:48-50`; `config/config.conf:95`; `src/main/kotlin/io/prometheus/common/dashboard/DashboardCss.kt:186-202`; `DashboardPage.kt:77-111, 293-307`; `ThemeScript.kt:30-69`; `CHANGELOG.md:123`

**Problem:**
1. **Direct factual error (`:113-114`):** Claims the agent dashboard has *"a Scrape now that fetches a target from the agent and, **like the proxy's, is counted nowhere**"*. In fact, the proxy's Scrape now **is** counted on the agent in `agent_dashboard_scrapes_total` (`Agent.kt:834`, `ScrapeNow.kt:46`, `docs/metrics-and-grafana.md:210-213`, `web-dashboard.md:293`); only the agent dashboard's own Scrape now is counted in no metric (`AgentScrapeNow.kt:48-50`).
2. **Stale config-file-only list (`:65-66`):** Omits `proxy.dashboard.screens`.
3. **Stale UI chrome (`:71`):** Still says *"Four bookmarkable layouts, switched by **folder tabs**"* — `#575` replaced folder tabs with borderless underlined icon tabs, and `/screens` is now an additional page on the dashboard port.
4. **Stale release signal (`:159`):** Cites `v4.6.0, released 2026-11-20` instead of `4.7.0` (`2026-11-24`).
5. **Incomplete theme note (`:198-199`):** Mentions only `prefers-color-scheme`, omitting the manual theme button in the bar.

**Impact:** Maintainers and automated design passes relying on `docs/PRODUCT.md` as the product contract get contradicted facts about scrape-now metrics and current dashboard UI chrome.

**Fix:** Correct line 114, add `proxy.dashboard.screens` at lines 65–66, update line 71 to underlined icon tabs and `/screens`, update line 159 to the latest release, and note the manual theme toggle at lines 198–199.

---

### 63. ⬜ `docs/DESIGN.md` contradicts itself and the actual CSS on the Status Bar layout above 720px and on border-radius rules

**Severity:** medium · **Confidence:** confirmed

**Where:**
- Doc: `docs/DESIGN.md:247-248, 431-433, 482-496, 504-506, 834-836, 963, 1014-1015`
- Code: `src/main/kotlin/io/prometheus/common/dashboard/DashboardCss.kt:90-97, 194-195`; `src/main/kotlin/io/prometheus/proxy/dashboard/ProxyDashboardCss.kt:31-36`; `src/main/kotlin/io/prometheus/proxy/dashboard/ScreensCss.kt:57-58, 69, 79, 87`

**Problem:**
1. **Status Bar layout contradiction (`:834-835`):** Still states *"One row above 720px, where the counters wrap within themselves rather than push the tabs off the rule."* This contradicts both `DashboardCss.kt:90-97` (`.bar:has(> .bar-tail) { flex-wrap:wrap-reverse; }` and `.bar-tail { flex:1 1 380px; min-width:min-content; }`) and `docs/DESIGN.md:431-433` (updated in `#581`): above 720px, when `.bar-tail` doesn't fit beside the tabs, it moves whole onto a row **above** the brand and tabs. The Status Bar section (`:826-848`) also omits `.screens-link` (`ProxyDashboardCss.kt:31-36`).
2. **Border-radius contradictions:**
   - `:247-248` still says *"the **top corners** of the tabs' hover ground"* (remnant of old folder tabs), whereas `DashboardCss.kt:195` rounds all four corners (`border-radius:6px`).
   - `:482-488`, `:504-506`, and `:1014-1015` assert *"No rounded corners on any container"* and *"The tabs' `6px` hover ground and the bar's end's `3px` are the only ones"*, contradicting `:963` (*"a pane per screen on a `surface` card with a 3px radius"*) and the actual CSS, which sets `border-radius:3px` on `.screens-link` (`ProxyDashboardCss.kt:33`) and `.layouts button`, `.handoff button`, `.pane`, and `.pane-bar select` (`ScreensCss.kt:57-58, 69, 79, 87`).

**Impact:** Contradictory visual contract in `docs/DESIGN.md` can mislead future UI changes or design audits.

**Fix:** Update `docs/DESIGN.md:834-836` to match `:431-433` and `DashboardCss.kt:90-97` (and mention `.screens-link`); remove *"top corners of"* at `:247`; and reconcile the radius rules (`:247-248, 482-496, 504-506, 1014-1015`) with the `3px` radius on `.screens-link` and `/screens` controls/panes (`:963`).

---

### 64. ⬜ `demos/network/README.md` has a stale `make open` description (and omits `screens` on `proxy-standby.conf`), and `demos/basics/README.md` omits `make open` from Scenario Reference

**Severity:** medium · **Confidence:** confirmed

**Where:**
- Doc: `demos/network/README.md:58, 371, 382`; `demos/basics/README.md:383-397` (compare `website/prometheus-proxy/docs/network-demo.md:362`, `website/prometheus-proxy/docs/basics-demo.md:384`)
- Code: `demos/network/demo.sh:510-518`; `demos/network/Makefile:45`; `demos/network/config/proxy-standby.conf:18-30`; `demos/basics/demo.sh:402-410`; `demos/basics/Makefile:45`

**Problem:**
1. `demos/network/README.md:371` says `make open` *"Opens both proxy dashboards, agent-branch's dashboard, Grafana, and Prometheus's alerts in the browser."* Since `#582`, `demos/network/demo.sh:510-518` opens only `$SCREENS_HOME_URL` (the standby proxy's screens page), matching line 58, `Makefile:45`, and `network-demo.md:362`.
2. `demos/network/README.md:382` describes `config/proxy-standby.conf` as containing only its service-discovery scrape address and Zipkin service name, omitting the `dashboard.screens` list added in `demos/network/config/proxy-standby.conf:18-30`.
3. `demos/basics/README.md:383-397` omits the `make open` row from its Scenario Reference table, whereas `website/prometheus-proxy/docs/basics-demo.md:384` and every other demo README include it.

**Impact:** Demo READMEs disagree with the website pages and actual `make open` behavior after `#577`/`#582`.

**Fix:** Update `demos/network/README.md:371` and `:382` to match `network-demo.md:362` and `proxy-standby.conf`; add the `make open` row to `demos/basics/README.md` matching `basics-demo.md:384`.

---

### 65. ⬜ `demos/spring-boot/README.md` duplicates and contradicts its `make up` explanation in Quick Start

**Severity:** low · **Confidence:** confirmed

**Where:**
- Doc: `demos/spring-boot/README.md:51-55` vs `67-69` (compare `website/prometheus-proxy/docs/spring-boot-demo.md:63-81`)
- Code: `demos/spring-boot/demo.sh:65`

**Problem:**
Lines 51–55 explain `make up` and `make up-local`, including *"Until a release with everything the demo shows is on Docker Hub, `make up` builds from the checkout too, as `make up-local` does."* Immediately after the command block, lines 67–69 repeat the `make up` / `make up-local` paragraph without that caveat (*"`make up` runs the latest release's proxy image... so it needs only Docker."*), contradicting the paragraph directly above it.

**Impact:** Confusing duplicate/contradictory Quick Start instructions.

**Fix:** Trim `demos/spring-boot/README.md:67-69` down to just the first-build timing note (*"The first build downloads Maven's dependencies, which takes a few minutes."*) as in `spring-boot-demo.md:80-81`.

---

### 66. ⬜ `website/prometheus-proxy/docs/embedded-agent.md` omits `stopTimeout` from the "Every Setting, Every Way" cross-reference table

**Severity:** low · **Confidence:** confirmed

**Where:**
- Doc: `website/prometheus-proxy/docs/embedded-agent.md:300-316`
- Code: `src/main/kotlin/io/prometheus/agent/EmbeddedAgentConfig.kt:275-289`; `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgentProperties.java:116-117`; `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/FileSettings.kt:79`

**Problem:**
The "Every Setting, Every Way" table maps settings across Builder, Config key, Starter property / plugin key, and Environment variable — including non-config-tree rows (`source(path, registry)`) and 4.7.1's `framingEnabled` — but omits `stopTimeout` (`stopTimeout(timeout)` / `stop-timeout`).

**Impact:** Readers looking up the property/plugin key for `stopTimeout` in the master mapping table won't find it.

**Fix:** Add a row for `Stop timeout | stopTimeout(timeout) | none: it's the handle's | stop-timeout |` to `website/prometheus-proxy/docs/embedded-agent.md:300-316`.

---

### 67. ⬜ `website/prometheus-proxy/docs/configuration/agent.md` omits `agent.grpc.*`, `agent.admin.*`, and `agent.internal.*` (leaving `UNARY_DEADLINE_SECS` off both configuration pages)

**Severity:** low · **Confidence:** confirmed

**Where:**
- Doc: `website/prometheus-proxy/docs/configuration/agent.md:1-515` (compare `website/prometheus-proxy/docs/configuration/proxy.md:33-54, 112-150, 243-328, 377-380`)
- Code: `config/config.conf:159, 172, 198-206, 278-308`; `src/main/kotlin/io/prometheus/agent/AgentOptions.kt:314-317, 556`

**Problem:**
`configuration/proxy.md` documents `proxy.grpc.*`, `proxy.admin.*`, `proxy.internal.*`, `proxy.transportFilterDisabled`, and `proxy.logLevel`, plus a "Full Reference" link to `config/config.conf`. By contrast, `configuration/agent.md` omits gRPC, Admin, and Internal settings sections completely — leaving `agent.grpc.unaryDeadlineSecs` (`--unary_deadline_secs` / `UNARY_DEADLINE_SECS`), `agent.grpc.keepAlive*`, `agent.admin.*`, and `agent.internal.*` (`reconnectPauseSecs`, `rejectedPathRetrySecs`, `rejectedPathRetryMaxSecs`, `heartbeat*`, `scrapeRequestBacklogUnhealthySize`, `zipkin.*`) off the agent configuration page even though `network-demo.md:269`, `production.md:129`, and `basics-demo.md` refer to them.

**Impact:** Operators looking up `agent.grpc.*`, `agent.admin.*`, or `agent.internal.*` settings on the Agent Configuration page cannot find their keys, defaults, or env/CLI equivalents.

**Fix:** Add **gRPC Settings**, **Admin Endpoints**, and **Internal Settings** tables plus a **Full Reference** link to `config/config.conf` in `website/prometheus-proxy/docs/configuration/agent.md`, mirroring `configuration/proxy.md`.

---

### 68. ⬜ Public API KDoc / Dokka documentation inaccuracies (`docs/KDOC_SUMMARY.md`, `docs/packages.md`, `EmbeddedAgentInfo.kt`, `EmbeddedAgentConfig.kt`, `BaseOptions.kt`)

**Severity:** low · **Confidence:** confirmed

**Where:**
- `docs/KDOC_SUMMARY.md:10-12, 61-64` vs `AgentOptions.kt:17, 60`, `ProxyOptions.kt:17, 59`, `BaseOptions.kt:17, 107`
- `src/main/kotlin/io/prometheus/agent/EmbeddedAgentInfo.kt:107` vs `Agent.kt:945, 955-961`
- `src/main/kotlin/io/prometheus/agent/EmbeddedAgentConfig.kt:128-130, 195` vs `158, 173-191, 203`
- `src/main/kotlin/io/prometheus/common/BaseOptions.kt:279-283` vs `Utils.kt:131-151` and `BaseOptions.kt:723-729`
- `docs/packages.md:95-116` vs `EmbeddedAgentConfig.kt:248-289`

**Problem:**
1. `docs/KDOC_SUMMARY.md:10-12` states `AgentOptions`, `ProxyOptions`, and `BaseOptions` carry KDoc, but all three suppress `UndocumentedPublicClass` and have **no class-level KDoc** (only member KDocs).
2. `EmbeddedAgentInfo.shutdown()` KDoc (`:107`) says it *"releases the admin/metrics servlets"*, omitting the agent dashboard server (`Agent.kt:945`).
3. `EmbeddedAgentConfig.startIfConfigured()` KDoc (`:128-130`) mentions only `Builder.proxy` and `PROXY_HOSTNAME`, omitting `setting("proxy.endpoints", ...)` and `setting("proxy.hostname", ...)` that `namesProxy()` honors (`:173-191`). `toString()` KDoc (`:195`) also omits `stopTimeout` (`:203`).
4. `BaseOptions.logLevel` KDoc (`:279-283`) still describes it as *"Logback log level ... if still empty, the level configured in `logback.xml` is used"*, predating 4.7.0's removal of Logback from embedded agents (`BaseOptions.kt:723-729` was updated, this property KDoc was not).
5. `docs/packages.md:95-116` (the Dokka package overview for `io.prometheus.agent`) was not updated for 4.7.1's `onFailure`, `reportFailure`, and `stopTimeout`.

**Impact:** Generated KDocs have blank class descriptions for options classes and omit or slightly misstate 4.7.0/4.7.1 API details.

**Fix:** Add concise class-level KDoc to `AgentOptions`, `ProxyOptions`, and `BaseOptions` (or clarify `KDOC_SUMMARY.md`); update the three member KDocs above; and add `onFailure`, `reportFailure`, and `stopTimeout` links to `docs/packages.md:95-116`.

---

### 69. ⬜ `website/prometheus-proxy/docs/javascripts/screenshots.js` can throw on `Tab` if no enabled lightbox button exists, and `zensical.toml` has a stale GLightbox CDN comment

**Severity:** low · **Confidence:** confirmed

**Where:**
- `website/prometheus-proxy/docs/javascripts/screenshots.js:77-82`
- `website/prometheus-proxy/zensical.toml:168-169` vs `website/prometheus-proxy/overrides/main.html:34-37, 47-50`

**Problem:**
1. In `screenshots.js:77-82`, `stepFocus` calls `buttons.at(next).focus()` without checking `buttons.length > 0`. If `Tab` is pressed right after `.glightbox-container` is inserted or when no enabled `.gbtn` exists, `buttons.at(0)` is `undefined` and throws `TypeError` inside a capture-phase `keydown` listener.
2. `zensical.toml:168` comments *"GLightbox, which the theme loads from unpkg on a page that has one"*, contradicting `overrides/main.html:34-37, 47-50`, which self-hosts pinned GLightbox 3.3.1 from `vendor/glightbox/` specifically to avoid `unpkg`.

**Impact:** Potential uncaught JS `TypeError` on rapid `Tab` during lightbox open, and misleading maintainer comment in `zensical.toml`.

**Fix:** Guard `stepFocus` with `if (!buttons.length) return;` (or `buttons.at(next)?.focus();`) in `screenshots.js:78-81`, and update the comment in `zensical.toml:168-169`.

---

### 70. ⬜ `website/prometheus-proxy/docs/troubleshooting.md` startup-rejection list omits 4.7.1's `proxy.dashboard.screens` / `proxy.dashboard.path = "/screens"` rejections

**Severity:** low · **Confidence:** confirmed

**Where:**
- Doc: `website/prometheus-proxy/docs/troubleshooting.md:60-71`
- Code: `src/main/kotlin/io/prometheus/proxy/dashboard/Screen.kt:89-135`; `src/main/kotlin/io/prometheus/proxy/ProxyOptions.kt:341-348`

**Problem:**
The `Invalid configuration: …` troubleshooting checklist (`troubleshooting.md:60-71`) tracks startup rejections across releases (including `proxy.dashboard.links` and 4.7.0's `maxPathsPerIdentity` / `maxLabelsPerPath`), but omits 4.7.1's `proxy.dashboard.screens` validation failures and `proxy.dashboard.path = "/screens"` conflict.

**Impact:** Operators hitting a startup rejection from an invalid `proxy.dashboard.screens` entry or a `/screens` base-path clash won't find it in the troubleshooting checklist.

**Fix:** Add a bullet under `troubleshooting.md:68-71` noting invalid `proxy.dashboard.screens` entries and `proxy.dashboard.path = "/screens"` conflicts, linking to `web-dashboard.md#screens`.

---

## ✅ Checked and Clean / Considered, Not Reported

To avoid repeating work in future reviews, the following areas were audited in full and verified clean (along with borderline items investigated and explicitly ruled out):

- **Proxy core (`Proxy.kt`, `proxy_service.proto`, `AgentContext`, `AgentContextManager`, `NearLimitWarnings`, `ScrapeRequestManager`, `ScrapeRequestWrapper`, `PathHistory`, `ProxyEventBus`, `ProxyMetrics`, `ProxyHttpRoutes`, `SdTarget`):**
  - `AgentContext` queue/invalidate/notice interleavings (`receiveCatchingWithin` `select` with `onTimeout`, `InvalidatedHasNoNotices`, `MAX_PENDING_PATH_NOTICES = 20_000`, `MAX_REMEMBERED_REJECTIONS = 1_000`) are sound.
  - `AgentContextManager.addAgentContextWithinLimit` / `onRemoved` and `NearLimitWarnings` (`Long` arithmetic, no `Int` overflow) are atomic and race-free.
  - `ProxyServiceImpl` keepalive gating (agent `stream_keepalives` + nonzero proxy interval on request/response streams; ungated empty `PathNotice` keepalive), `readableAgentContext` single-lookup + `removeAgentContextIfCurrent`, and `ScrapeRequestWrapper` CAS completion + ownership checks are clean.
  - `ProxyPathManager.formRejection` runs before `AgentAuthManager.globMatches` (iterative $O(m \cdot n)$, no ReDoS); `AgentAuthManager` uses SHA-256 + `MessageDigest.isEqual` across all identities; `Proxy.isReservedSdLabelKey` strips `__*` meta-labels and validates Prometheus label names; `PathHistory` keeps strict lock ordering (`pathMap` -> `rings`).
  - `ProxyMetrics` per-path histogram series lifecycle (`ConcurrentHashMap.compute`/`computeIfPresent`) prevents post-removal leaks; `ProxyUtils.respondWith` sets `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox`.
  - *Considered, not reported:* `StreamSender.markActive` skips keepalive messages — safe because the agent sends independent heartbeats on `heartbeatMaxInactivitySecs` (5s). `ProxyPathManager.countPaths` counts an agent's paths against `countedIdentities[agentId]` (its first registered identity) — documented as intentional at `ProxyPathManager.kt:144-145`. When `maxPathsPerIdentity <= 9`, replacing an exclusive path at 80% briefly dips below 70% inside `takeOverPath` and re-logs the near-limit WARN — harmless noise at tiny limits.
- **Agent & common (`Agent.kt`, `AgentGrpcService`, `AgentHttpService`, `HttpClientCache`, `AgentActivityLog`, `PathDiscoveryService`, `AgentMetrics`, `SharedCollectors`, `Channels`, `ScrapeTracing`, `KeyedThrottle`, `EnvVars`, `NonFatal`, `AgentFailover.tla`):**
  - `Agent.startUp` / `shutDown` / `releaseSteps` / `startWithin` / daemon executor rollback on partial startup failure (`#557`, `#562`) is solid.
  - `AgentHttpService` streaming body cap (`readBuffer(maxContentLength + 1)`), `followSameOriginRedirects` outermost before `Auth`, and Ktor CIO 3.6.1 SNI + hostname verification with custom `X509TrustManager` (verified in Ktor bytecode) are clean.
  - `HttpClientCache` (`NonCancellable` release, `LAZY` sweeper, per-process `SecureRandom`-salted HMAC-SHA256 cache keys, `closeQuietly` rethrowing JVM `Error`s) is clean.
  - `AgentActivityLog` (`#579`: `holdAttempt`/`Hold.release`/`Hold.discard`, burst path deduplication, atomic `snapshot()`) and `PathDiscoveryService` (`failedLast` surviving reconnect for `DISCOVERY_RECOVERED`, `lastGoodPaths` retention, `runCatchingNonFatal`) are clean.
  - `AgentFailover.tla` matches `EndpointFailover.beforeAttempt` for connect/register/drop transitions; `resetConnectBackoff()` on single-endpoint retries is properly gated.
  - `AgentMetrics` (`SHARED`, `COLLECTORS`, `PER_LAUNCH` with `launch_id` first, `registersJvmMetrics` across all four `(isMetricsEnabled, isEmbedded)` combinations) and `ScrapeTracing` (explicit `Span` passing, `ServerSampledPropagation.undecided` stripping caller sampling flags) are clean.
- **Embedding, Ktor plugin, Spring Boot starter & Maven packaging:**
  - `EmbeddedAgentInfo.stopWithin()` lock coordination (`finished`/`abandoned`/`failure`), `Duration.INFINITE` handling with Guava 33.8.0's `Uninterruptibles.joinUninterruptibly`, and `EmbeddedAgentConfig`'s `onFailure`/`reportFailure`/`failureReason` (cycle-safe cause walk, URL redaction) are solid.
  - `RegistryMetricsSource` / `AgentMetricsSource` default-registry exclusion of `AgentMetrics.FAMILY_NAMES` verified against Prometheus client 1.10.0 bytecode.
  - `RuntimeLibraries` version checks (`kotlinx-coroutines >= 1.11.0`, `prometheus-metrics-model >= 1.7.0`) handle missing metadata (shaded jars) and prerelease suffixes cleanly.
  - Spring Boot starter `SmartLifecycle` phase ordering verified against `spring-boot-web-server:4.2.0` bytecode (starts last, stops first before graceful web server shutdown); auto-config conditions, health indicator (`UP`/`UNKNOWN`, never `DOWN`), `spring-configuration-metadata.json`, and non-incremental `compileJava` are clean.
  - Published POMs and Gradle module metadata for all 3 artifacts emit Java 17 bytecode, exclude Logback and `logback.xml` from library JARs, keep `reference.conf` in the root JAR, and keep `spring-boot-starter` POM free of Spring Boot dependencies/BOM.
- **Dashboards & `/screens` (`.claude/rules/dashboard-constraints.md`):**
  - Every dark rule routes through `DashboardCss.darkRule` (`ScreensCss` uses only tokens); `.bar-tail`, live region (`#conn-announce`), `#column-guide`, `TableColumn` headers/guide pairing, AA contrast against `--surface-2`, `agentLabel()`, `RowFilter` matching rendered cells, single `routeBase` normalization, `ScrapeNow`/`AgentScrapeNow` metric/history isolation, `Framing.of` (`ANY`/`SAME_ORIGIN`/`NONE`), `safeNext`, `Screen.parseAll`, `PathLink.parseAll`, `LoginLimiter` (per-IP + global caps, ignores `X-Forwarded-For`), `DashboardLogin` (constant-time SHA-256), `DashboardSessions` (256-bit `SecureRandom`), and versioned allowlisted assets (`ASSET_DIR`, `ICON_DIR`) all hold.
  - *Considered, not reported:* `DashboardServer.kt:659` (`logoutPath`) expires cookie without `secure = true` — RFC 6265 matches by `(name, domain, path)` and `endLogin` already revokes server-side. `ScreensHtml.kt:185` uses `rel="noopener"` without `noreferrer` — pinned by `ScreensHtmlTest.kt:131`.
- **Build, CI, Docker, Config codegen, Demos & Scripts:**
  - `tscfg-1.2.5.jar` regeneration against `config/config.conf` matches checked-in `ConfigVals.java` verbatim.
  - Shadow JAR service-file/NOTICE/LICENSE merging (`shadow-jar.md`) and `extractDashboardIcons` vs `LinkIcon` are clean.
  - All 8 GitHub Actions workflows pin actions to full SHAs, set `persist-credentials: false`, use least-privilege `permissions`, avoid untrusted `${{ ... }}` shell interpolation, and pass `zizmor` conventions; `dependabot.yml` sets 7-day cooldowns everywhere.
  - Production Dockerfiles and all 3 app demo Dockerfiles use digest-pinned bases, non-root users, and exec-form `ENTRYPOINT`.
  - All 7 demos have non-overlapping host port blocks, `127.0.0.1` compose bindings, accurate `NEEDS_RELEASE_AFTER` gates (`4.7.0` for `basics`/`network`/`ktor`; `4.6.0` for the rest), and clean `shellcheck -S warning` results (aside from Finding #52's single `SC2034`). Root `test.conf` is gitignored local scratch.
- **Tests & Docs:**
  - All recent `/screens`, `Framing`, `EmbeddedAgentConfig` (`#573`), `AgentActivityLog` (`#579`), subproject, and hygiene guard specs (outside Findings #53–#59) are thorough and properly clean up mocks/system properties.
  - All 9 `*LincheckTest` specs carry `tags(Lincheck)` and zero MockK usage; `ContainerTestSupport` image building is thread-safe (`SYNCHRONIZED` lazy).
  - Zero broken internal Markdown links, `#anchors`, `prometheus-proxy.com/...` URLs, or Mermaid `linkStyle` indices across all 35 website pages, `README.md`, `AGENTS.md`, `SECURITY.md`, `llms.txt`, `docs/*.md`, demo READMEs, and chart READMEs. All 51 exposed metrics and all 57 `EnvVars` entries are accurately documented.
