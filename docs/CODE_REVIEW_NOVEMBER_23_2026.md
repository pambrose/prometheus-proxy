# Prometheus-Proxy Code Review — November 23, 2026

**Status:** 72 issues — 57 fixed, 15 open (8 medium, 64 low, no high).

**Progress:** Wave A (steps 2, 3, and 16, in #550–#552) and Wave B (steps 1, 4, 6, 9, 11, 13, and 15, in
#555–#561).

**Date:** 2026-11-23; rechecked twice on 2026-11-24

**Scope:** `master` at `5c9f0a24`, the unreleased 4.7.0 (the last release is 4.6.0). That covers ~26.7k lines of main
source, twice November's, the Spring Boot starter and the Ktor plugin (~1.3k lines), the ~62k-line test suite, the
build, CI workflows, the Docker images and Helm charts, the seven demos, the docs site, and the documentation. The
first pass reviewed `56b1c355`. A second pass at `4830930e` and a third at `5c9f0a24` reviewed the thirteen commits
since (#536–#549), none of which changes main source, and rechecked every finding they reach, so every line number
below points at `5c9f0a24`. Source paths below are relative to `src/main/kotlin/io/prometheus/` and test paths to
`src/test/kotlin/io/prometheus/`; all other paths are from the repo root.

---

## 📋 Summary

✅ = fixed · ⬜ = open

| #  | Finding                                                                                              | Area       | Severity | Status |
|----|------------------------------------------------------------------------------------------------------|------------|----------|--------|
| 1  | `ProxyRegistry.tla` lets an invalidated connection deliver path notices the code drops               | Proxy      | low      | ✅      |
| 2  | The forward span loses its queue-wait and "sent" tags when the send outlasts the scrape              | Proxy      | low      | ✅      |
| 3  | The 80% path and identity limit warnings repeat each time the count climbs back to the threshold     | Proxy      | low      | ✅      |
| 4  | The proto and AGENTS.md misdescribe the path-notices stream's keepalives                             | Proxy      | low      | ✅      |
| 5  | A dashboard bind failure leaves the agent's admin and metrics servers running, and `/ping` healthy   | Agent      | medium   | ✅      |
| 6  | Results queued when a writer stream fails are counted neither as sent nor as dropped                 | Agent      | low      | ⬜      |
| 7  | Connection tasks catch JVM `Error`s, so an `OutOfMemoryError` there only reconnects                  | Agent      | low      | ⬜      |
| 8  | The starter's agent dies with a `NoSuchMethodError` on Spring Boot 4.0.x and 4.1.0                   | Embedding  | medium   | ✅      |
| 9  | Logback at runtime scope breaks Spring Boot apps that log with Log4j2                                | Embedding  | medium   | ✅      |
| 10 | The Ktor plugin starts the agent before the port binds, so an app whose port is taken never exits    | Embedding  | medium   | ✅      |
| 11 | The starter and the plugin ignore a proxy set by `PROXY_HOSTNAME`, `setting()`, or a customizer      | Embedding  | low      | ✅      |
| 12 | A start that times out keeps starting, with no handle to stop it                                     | Embedding  | low      | ✅      |
| 13 | An anonymous `POST /logout` forces a collect and a push to every open page, once per request         | Dashboards | medium   | ✅      |
| 14 | Ending a session whose socket has stopped reading freezes every open page                            | Dashboards | medium   | ✅      |
| 15 | A session that stops reading stays open, holding a `maxSessions` slot                                | Dashboards | low      | ✅      |
| 16 | The tables' search doesn't match a path as its row shows it, or an agent by its label                | Dashboards | low      | ✅      |
| 17 | A collect that throws ends the push loop for good, logged only to stderr                             | Dashboards | low      | ✅      |
| 18 | A gone agent's detail pane names it by its raw `agentId`                                             | Dashboards | low      | ✅      |
| 19 | The eviction countdown shows when the stale-agent check is off                                       | Dashboards | low      | ✅      |
| 20 | One agent's labels can exhaust the proxy's heap, and `maxPathsPerAgent` doesn't bound an identity    | Security   | medium   | ✅      |
| 21 | Any gRPC caller can make the proxy sample its calls with a `b3` header                               | Security   | low      | ✅      |
| 22 | The dashboard logs `Origin` and `Host` whole and unthrottled, before any login                       | Security   | low      | ✅      |
| 23 | Pages behind the dashboard's login can be framed and cached                                          | Security   | low      | ✅      |
| 24 | The starter's `Target.toString` shows URL query values such as an `api_key`                          | Security   | low      | ✅      |
| 25 | Each release moves `NEEDS_RELEASE_AFTER` by hand, and `master`'s `make up` misstates the unpublished release | CI/build   | low      | ✅      |
| 26 | Both Helm charts' dashboards listen on every pod interface                                           | CI/build   | low      | ✅      |
| 27 | The quick-start compose file publishes the agent, scrape, and admin ports on every interface         | CI/build   | low      | ✅      |
| 28 | `--tests=<spec>` runs the whole suite unfiltered, and `unitTest --tests X` fails                     | CI/build   | low      | ⬜      |
| 29 | The files the `misc/` specs read aren't `Test` inputs, so an edit to them can take a cached pass     | CI/build   | low      | ⬜      |
| 30 | `make site-version` doesn't scan the cloud-proxy compose file                                        | CI/build   | low      | ✅      |
| 31 | The starter page's YAML sets no `authority`, so the docs' own certificate fails the handshake        | Docs       | medium   | ✅      |
| 32 | The upgrade notes miss that a 4.7.0 agent reads `DASHBOARD_*` and `METRICS_PROXY_PATH`               | Docs       | low      | ✅      |
| 33 | The Embedded Agent page gives `agent.logLevel` a default an embedded agent doesn't use               | Docs       | low      | ✅      |
| 34 | The docs say a refused `PROMETHEUS_*` value is never shown, but an endpoint is                       | Docs       | low      | ✅      |
| 35 | `agent.metrics.proxyPath` is missing from the README, `llms.txt`, and the Grafana docs               | Docs       | low      | ✅      |
| 36 | The security doc says a displaced agent is invalidated; it stays connected                           | Docs       | low      | ✅      |
| 37 | The Quick Start says a 404 means an unregistered path, but a target's own 404 looks the same         | Docs       | low      | ✅      |
| 38 | The Agent Dashboard page's Helm commands use a namespace the site's agent installs don't use         | Docs       | low      | ✅      |
| 39 | The README and changelogs say every demo turns on its agents' dashboards                             | Docs       | low      | ✅      |
| 40 | A check order, a `make up` claim, and an unnamed size limit on the deployment and security pages     | Docs       | low      | ✅      |
| 41 | The README and `llms.txt` miss `PROMETHEUS_PROXY_AUTHORITY` and misstate `-D` precedence             | Docs       | low      | ✅      |
| 42 | The metrics docs' counter count, alert list, and chunk threshold are wrong                           | Docs       | low      | ✅      |
| 43 | `docs/packages.md`, `ConfigLoadException`'s KDoc, and `KDOC_SUMMARY.md` describe 4.6.0               | Docs       | low      | ⬜      |
| 44 | AGENTS.md and `docs/TESTING.md` name a deploy check that's gone, and misdescribe `make all-tests`    | Docs       | low      | ⬜      |
| 45 | `docs/DESIGN.md` and `docs/PRODUCT.md` no longer match the shipped dashboard                         | Docs       | low      | ⬜      |
| 46 | Nothing tests `receiveCatchingWithin`, and a test helper uses the form it rules out                  | Tests      | low      | ⬜      |
| 47 | The starter's required-agent tests can't tell `start()` from `startIfConfigured()`                   | Tests      | low      | ✅      |
| 48 | Two of `MetricsSource`'s isolation promises have no test                                             | Tests      | low      | ✅      |
| 49 | `ProxyPathManagerLincheckTest` never runs takeover or reclaim                                        | Tests      | low      | ✅      |
| 50 | No test fails when a change stops waking the dashboards' push loop                                   | Tests      | low      | ✅      |
| 51 | The proxy's `scrape` and `forward` spans have no unit test                                           | Tests      | low      | ✅      |
| 52 | No test pins `AgentMetrics`' `PER_LAUNCH` rule                                                       | Tests      | low      | ✅      |
| 53 | Nothing tests that an embedded agent leaves the JVM metric groups to its host                        | Tests      | low      | ✅      |
| 54 | The Ktor plugin's wait for the agent's stop has no deterministic test                                | Tests      | low      | ✅      |
| 55 | `AgentDashboardHtmlTest` pins neither half of the column-guide rule, nor the agent page's live region | Tests     | low      | ✅      |
| 56 | Two more `dashboard-constraints.md` rules have no test                                               | Tests      | low      | ✅      |
| 57 | "fromEnvironment reads the process's environment" can't fail                                         | Tests      | low      | ✅      |
| 58 | The config-key guards don't reach `setting()` keys or the website's HOCON blocks                     | Tests      | low      | ⬜      |
| 59 | `TestingGuideTest` and `TestPortsTest` don't read the subprojects' tests                             | Tests      | low      | ⬜      |
| 60 | An agent with one proxy reconnects on gRPC's backoff, not every `reconnectPauseSecs`                 | Agent      | low      | ⬜      |
| 61 | `release-check`'s stale-version check misses the Quick Start's JAR download links                    | CI/build   | low      | ✅      |
| 62 | Both changelogs date 4.7.0 2026-11-23, and `release-check` accepts any date                          | CI/build   | low      | ✅      |
| 63 | The basics demo promises an alert that a late thaw never fires                                       | CI/build   | low      | ✅      |
| 64 | The agent page says `--proxy` and `PROXY_HOSTNAME` apply only without `endpoints`                    | Docs       | low      | ✅      |
| 65 | Two diagram descriptions split a hyphenated name across a line                                       | Docs       | low      | ✅      |
| 66 | Nothing checks the settings tables' options, variables, links, or gRPC defaults                      | Tests      | low      | ⬜      |
| 67 | On `master`, `make up` and `make tour` link to docs pages and anchors the live site doesn't have     | CI/build   | low      | ✅      |
| 68 | `make xxl-tests` gives both test partitions the XXLARGE profile's 16g heap                           | CI/build   | low      | ⬜      |
| 69 | The changelogs describe the home page #546 replaced, and miss four commits' changes                  | Docs       | low      | ✅      |
| 70 | The release skill still lists `index.md`, and a design-check exemption covers a deleted class        | Docs       | low      | ⬜      |
| 71 | The Kubernetes demo's docs say its agent dashboard port is set once, but nothing keeps it            | Docs       | low      | ✅      |
| 72 | `DemoTourTablesTest` misses a stale README heading and `demo.sh`'s page anchors                      | Tests      | low      | ⬜      |

---

## 🧭 Headline

There are no high-severity findings, and none of November's 43 fixes was found reopened. The new proxy code held up
under every interleaving the proxy pass traced: takeover and reclaim, the waiting lists, keepalives, and agent
teardown. The medium findings cluster in four places:

- **4.7.0 itself, before it's tagged.** The Spring Boot starter's agent dies on Spring Boot 4.0.x and 4.1.0, which its
  docs support (#8). Its Logback dependency breaks a Spring Boot app that logs with Log4j2 (#9). Its page's YAML fails
  the TLS handshake with the certificate the docs make (#31). All but #9's dependency are new in 4.7.0, and the starter
  is what carries #9 into Spring Boot apps. The docs site deploys only with a release, so the docs fixes must land
  before the tag to reach readers.
- **A start that fails halfway leaves threads running.** A taken agent-dashboard port leaves the admin and metrics
  servers up, with `/ping` answering 200 (#5). A taken Ktor port leaves the app running with its agent connected (#10).
  A start that times out keeps going with nothing to stop it (#12).
- **The dashboards' one shared push loop.** An unauthenticated logout flood drives a collect under the path-map lock
  per request (#13), November #18's concern again. One logged-in socket that stops reading can freeze every other
  viewer's page while it still shows "live" (#14).
- **One agent against the rest.** Labels within the default limits, times the per-connection path quota, exhaust the
  proxy's heap, and nothing bounds paths per identity (#20), following November #12.

The tests' findings are all low. Still, several invariants AGENTS.md states have no test that would fail if they broke:
`receiveCatchingWithin` (#46), reclaim under contention (#49), push wakeups (#50), and `PER_LAUNCH` (#52).

**Since the first pass** (`56b1c355..5c9f0a24`, #536–#549), no finding is fully fixed. #537's release check stops the
4.7.0 tag from shipping demos that build from source, which takes #25 from medium to low, and #540 fixed the stale
tours in #39. The thirteen commits bring thirteen low findings, #60–#72. The one most worth knowing: an agent with one
proxy reconnects on gRPC's backoff, up to two minutes after a long outage, not every 3 seconds as the config and the
demos say (#60). The latest six (#543–#549) reworked the demos' tours and the docs site's home page, and left both
changelogs describing a home page that's gone (#69).

---

## 🗺️ Plan: order of fixing

Each step is sized to be one PR, and the issues in a step are meant to be fixed together. The order is:

1. fix what the 4.7.0 tag and its deployed site would ship broken;
2. make a failed start release what it opened;
3. make the dashboards' push loop robust to any one client;
4. close the remaining one-agent-against-the-rest path;
5. clean up: the remaining proxy and agent bugs, the tests and their guards, the deployment defaults, and the
   maintainer docs last, so they describe the code the earlier steps settle.

The **Touches** column shows which steps edit the same files, and so need to land one after another.

✅ = done (with the PR that did it) · ⬜ = open

| Step | Status | Issues                       | PR theme                                                              | Touches                                                                                                                       | Why here                                                                                                                                                                                         |
|------|--------|------------------------------|-----------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | ✅ #561 | #25, #30, #61, #62, #67      | Release checks that miss nothing, and demos that pull a published release | `demos/*/demo.sh`, the demo READMEs, `bin/docker-*.sh`, `Makefile` (`release-check`, `_check-demos-pull-release`, `SITE_VERSION_SOURCES`, `release`), `docs/RELEASE.md`, the publishing-release skill, a new `misc/` spec | No longer blocks the 4.7.0 tag: #537's `_check-demos-pull-release` refuses a release until the demos are lowered. A gate on whether the release's images exist drops that release-day edit and keeps `master`'s `make up` right between the bump and the publish. For 4.7.0 itself, set both changelogs' date in the release's last change (#62). #67 is the demos' links to a site that, between releases, is the last release's. |
| 2    | ✅ #550 | #8, #24, #31                 | A Spring Boot starter that runs on the Spring Boot it names           | `agent/AgentMetrics.kt`, `Agent.kt` (`connectOutcome`), `spring-boot-starter/` (`PrometheusProxyAgentProperties`, the website YAML), `spring-boot-starter.md`, `embedded-agent.md` | The starter is new in 4.7.0; fixing it before the tag means no release ships it broken. A starter test at Spring Boot 4.0's or 4.1.0's Prometheus client pins #8.                                 |
| 3    | ✅ #551 | #9                           | Logback off embedders' classpaths                                     | `build.gradle.kts` (`compileOnly`, the shadow configuration), `common/Utils.kt` (`setLogLevel`), `etc/consumer-check`, `embedded-agent.md`'s Logging section | The starter carries it into every Spring Boot app from 4.7.0 on. Changes nothing for the fat JARs, and `consumer-check` can assert that no Logback is resolved.                                   |
| 4    | ✅ #557 | #5, #12                      | A failed or slow start releases what it opened                        | `Agent.kt` (`startUp`, `releaseSteps`, `stop`, `startEmbedded`), `agent/EmbeddedAgentConfig.kt`                               | The agent dashboard (#5) is new in 4.7.0, and the standalone agent's pod stays "healthy" with no connection. After step 2, which shares `Agent.kt`.                                               |
| 5    | ✅ #562 | #10, #11, #47, #54           | Integrations that start when configured and stop with their host      | `ktor-plugin/.../AgentLifecycle.kt`, `spring-boot-starter/.../PrometheusProxyAgent.java`, `agent/EmbeddedAgentConfig.kt` (`namesProxy`), possibly `Agent.kt` (a daemon executor), both integrations' tests and pages | Both integrations are new in 4.7.0. #47 and #54 are the tests that would catch a regression in what this step changes. After step 4: the daemon-thread option for #10 also covers #12.            |
| 6    | ✅ #558 | #13, #14, #15, #17, #50      | A push loop no client can flood or stall                              | `common/dashboard/DashboardServer.kt` (`pushLoop`, `push`, the logout route), `common/dashboard/DashboardSessions.kt`, the dashboard push specs | #13 needs no login, and #14 freezes every viewer. #50 makes the wakeups testable at all, so it lands with them. Read `.claude/rules/dashboard-constraints.md` first.                             |
| 7    | ✅ #563 | #22, #23                     | Dashboard response headers and bounded request logs                   | `common/dashboard/DashboardServer.kt` (`buildServer`, the origin guard), `web-dashboard.md`                                   | Small, but after step 6, which edits the same file.                                                                                                                                              |
| 8    | ✅ #564 | #16, #18, #19, #55, #56      | Dashboard views that show what's there                                | `common/dashboard/RowFilter.kt`, `proxy/dashboard/ProxyRowFilter.kt`, `proxy/dashboard/ProxyDashboardHtml.kt`, `proxy/dashboard/ProxySnapshot.kt`, `agent/dashboard/AgentActivityHtml.kt`, their tests, `dashboard-constraints.md` | Display-only. #55 and #56 are the constraint tests for the views this step edits. After step 9, which shares `ProxySnapshot.kt`.                                                                 |
| 9    | ✅ #560 | #20                          | Bound labels, and paths per identity                                  | `proxy/ProxyPathManager.kt`, `Proxy.kt` (`serviceDiscoveryLabels`), `proxy/ProxyOptions.kt`, `config/config.conf`, `proxy/dashboard/ProxySnapshot.kt`, `docs/security-agent-authentication.md`, the agent's `pathConfigs` check | New `proxy.internal` limits need config, validation, docs, and "Before you upgrade" notes. Check whether `ProxyRegistry.tla` should count paths per identity.                                     |
| 10   | ✅ #565 | #2, #3, #4, #21, #51         | Proxy tracing, limit warnings, and the notices stream's contract       | `proxy/ScrapeDispatch.kt`, `proxy/ProxyServiceImpl.kt`, `proxy/ProxyPathManager.kt`, `proxy/AgentContextManager.kt`, `src/main/proto/proxy_service.proto`, `common/ScrapeTracing.kt`, `proxy/ProxyGrpcService.kt`, AGENTS.md | #51 adds the span tests that #2 and #21 need. After step 9, which shares `ProxyPathManager.kt`.                                                                                                  |
| 11   | ✅ #559 | #1, #49                      | Takeover and reclaim, modeled and stress-tested                       | `specs/tla/ProxyRegistry.tla`, `proxy/ProxyPathManagerLincheckTest.kt`                                                        | Model and test only. Run `make tla-checks-full` and `make lincheck-tests`. Independent of the other steps.                                                                                       |
| 12   | ⬜      | #6, #7, #46, #60             | Agent connections that count every result, pass on JVM errors, and retry at their pace | `agent/AgentGrpcService.kt`, `agent/AgentConnectionContext.kt`, `Agent.kt` (`launchConnectionTask`), `agent/AgentPathManager.kt`, `agent/discovery/PathDiscoveryService.kt`, `agent/EndpointFailover.kt`, a `Channels` spec, `proxy/ProxyPathManagerTest.kt`, `config/config.conf` (`reconnectPauseSecs`), the demos' reconnect text | #46 pins `receiveCatchingWithin` before this step reworks the stream tasks around it. #60's backoff reset goes in `connectAgent`. After step 4 or rebased on it, since both touch `Agent.kt` in separate places. |
| 13   | ✅ #555 | #48, #52, #53, #57           | Tests for the agent's metrics and sources                             | `agent/MetricsSourceTest.kt`, `agent/AgentMetricsTest.kt`, `harness/AgentMetricsProxyPathTest.kt`, `agent/EmbeddedAgentConfigTest.kt` | Test-only, so it can go any time.                                                                                                                                                                |
| 14   | ⬜      | #28, #29, #58, #59, #66, #68, #72 | Test runs and guards that see everything                              | `build.gradle.kts` (`configureTestPartitions`, `Test` inputs), `misc/ConfigFileKeysTest.kt`, `misc/WebsiteConfigKeysTest.kt` or a new `CliReferenceTest`, `misc/TestingGuideTest.kt`, `common/TestPortsTest.kt`, `docs/TESTING.md`, `misc/DemoTourTablesTest.kt` | Build and test infrastructure. #66 extends #58's guard to the settings tables' other columns. After step 3, which edits `build.gradle.kts`. #68 gives the XXLARGE heap to the harness JVM alone, and #72 extends #549's tour-table guard. |
| 15   | ✅ #556 | #26, #27                     | Charts and compose files that keep dashboards and ports private       | `charts/prometheus-{proxy,agent}/`, `demos/kubernetes/values/proxy.yaml`, `etc/compose/proxy.yml`, `website/prometheus-proxy/docs/docker.md`, `src/test/kotlin/website/DockerExamples.txt` | Deployment defaults only. Independent of the other steps.                                                                                                                                        |
| 16   | ✅ #552 | #32–#42, #63–#65, #69, #71   | User docs that describe 4.7.0                                         | `README.md`, `llms.txt`, `website/prometheus-proxy/docs/` (Embedded Agent, Embedded Upgrading, Getting Started, Agent Dashboard, Agent Configuration, Grafana, security, deployment, and the basics and Kubernetes demo pages), `docs/metrics-and-grafana.md`, `docs/security-agent-authentication.md`, `monitoring.md`, the demo READMEs and tours, `demos/basics/demo.sh`, `agent/EmbeddedAgentConfig.kt` KDoc, `agent/EnvironmentSetting.kt`, both changelogs | No behavior change. Before the 4.7.0 tag, since the site deploys only with a release. Any item can instead ride along with an earlier step that edits the same page.                             |
| 17   | ⬜      | #43, #44, #45, #70           | Maintainer docs that describe what shipped                            | `docs/packages.md`, `docs/KDOC_SUMMARY.md`, `ConfigLoadException`'s KDoc, AGENTS.md, `docs/TESTING.md`, `docs/DESIGN.md`, `docs/PRODUCT.md`, the publishing-release skill, `.impeccable/config.json`, `extra.css` | No behavior change. Last, so they describe what the earlier steps settle.                                                                                                                        |

### What can run in parallel

The steps fall into six tracks that touch disjoint files:

- **Release:** 2, 3, and 16, all before the 4.7.0 tag; 1 at any time.
- **Agent and embedding:** 2 → 4 → 5, with 12 after 4, and 13 at any time.
- **Dashboards:** 6 → 7, and 8 after 9.
- **Proxy:** 9 → 10, and 11 at any time.
- **Build and deployment:** 3 → 14, and 15 at any time.
- **Docs:** 16 before the tag, 17 last.

Taken together, that gives these waves. Every step in a wave can be worked on at the same time:

| Wave | Status | Steps                       | Waits for                                    |
|------|--------|-----------------------------|----------------------------------------------|
| A    | ✅      | 2, 3, 16                    | nothing; all before the 4.7.0 tag            |
| B    | ⬜      | 1, 4, 6, 9, 11, 13, 15      | 4 on 2                                       |
| C    | ⬜      | 5, 7, 8, 10, 12, 14         | 5 and 12 on 4; 7 on 6; 8 and 10 on 9; 14 on 3 |
| D    | ⬜      | 17                          | the code steps (2–12)                        |

Seven files, or sets of files, are edited across tracks, in separate places, so they merge cleanly, though the later
PR rebases:

- `Agent.kt`: step 2 moves `connectOutcome`, step 4 edits `startUp` and `startEmbedded`, step 5 may add a daemon
  executor, and step 12 edits `launchConnectionTask`.
- `build.gradle.kts`: step 3 edits the Logback dependency and the shadow configuration, and step 14 the test
  partitions and inputs.
- `proxy/ProxyPathManager.kt`: step 9 edits the path limits and label storage, and step 10 the 80% warning.
- `proxy/dashboard/ProxySnapshot.kt`: step 9 edits `labelValues`, and step 8 the stale-agent countdown.
- `website/prometheus-proxy/docs/embedded-agent.md`: steps 2 and 3 edit the dependency and Logging sections, step 5
  the start rule, and step 16 the defaults and the refused-value text.
- AGENTS.md: step 10 scopes the keepalive sentence, step 16 narrows the `fromEnvironment()` bullet, and step 17 drops
  the deploy check.
- `demos/*/demo.sh` and the demo pages: step 1 edits the release gate, step 12 the reconnect messages (#60), and step
  16 the scenario and diagram text (#63, #65).

**Where the PRs go:** every step's PR targets `master`, since `ci.yml` and the other pull-request workflows run only
for `master`. Each PR adds its entries to both `CHANGELOG.md` and `RELEASE_NOTES.md`: to 4.7.0's sections for Wave A,
and to a new Unreleased section once 4.7.0 is out.

**Releases:** Wave A holds what 4.7.0 would otherwise ship broken: the new starter (#8, #24, #31), the dependency the
starter spreads (#9), and the docs the release deploys (#32–#42, #63–#65, #69, #71). #537's check already keeps the
tag's demos from building from source (#25): 4.7.0's last change lowers each demo's `NEEDS_RELEASE_AFTER`, as
`docs/RELEASE.md` says, and should also date both changelogs' 4.7.0 sections to the release day (#62). Steps 4 and 5
fix the other 4.7.0 features (the agent dashboard and both integrations), and could hold the release too. Step 9
changes what's accepted (new `proxy.internal` limits), so it needs "Before you upgrade" entries in whichever release
takes it. The rest can be a 4.7.1.

---

## 🔬 How this review was done

**Process:** eight parallel reviews, each by a subagent in its own git worktree of `56b1c355`:

- proxy-side production code;
- agent-side and shared code;
- the embedding surface: the public embedding API, the Spring Boot starter, the Ktor plugin, and what Maven Central
  gives an embedder;
- the proxy and agent dashboards;
- security;
- build, CI, packaging, and the demos' infrastructure;
- tests;
- documentation accuracy.

Each one read AGENTS.md, the November review and the earlier ones in `docs/archive/`, and the 157 commits since
November's scope (`e84d27c..56b1c355`). Main source doubled in that range, and none of the new code had been reviewed:
the agent dashboard, both integrations, the embedding API, `agent.metrics.proxyPath`, path takeover and reclaim, stream
keepalives, Zipkin scrape tracing, the shared metrics collectors, the demos, and the docs site. A November item comes
back only when its fix turned out to be incomplete. Overlapping findings were merged: #25 was found by both the build
and documentation passes, and #34 by both the documentation and tests passes.

The key claim of every medium finding was re-checked against the cited lines. Several were reproduced:

- **#5:** by running the shipped agent jar with its dashboard port held.
- **#10:** with a scratch Ktor test.
- **#13, #14, and #15:** with scratch `DashboardServer` specs.
- **#20:** with a standalone program that measures what the proxy keeps per path.
- **#46:** with a scratch program that loses a channel element in the form AGENTS.md rules out, 5 runs of 5.
- **#28:** with a Gradle dry run.

#8 was checked against Spring Boot 4.0.0's and 4.1.0's BOMs and the Prometheus client 1.5.1 jar from Maven Central,
which lack the members `AgentMetrics` uses. Claims that depend on a library were checked in that library's source
(Ktor 3.6.1, Brave 6.3.1, Spring Boot 4.2.0, grpc-kotlin, zipkin-reporter, common-utils 5.3.0). Each finding carries a
confidence level:

- **confirmed:** traced in this repo's code, or in the source of the library it calls, or reproduced.
- **plausible:** the gap in this repo is confirmed, but the impact depends on timing, or on behavior read rather than
  exercised.

**Second pass, 2026-11-24:** before anything was committed, `master` moved seven commits (#536–#542). They change no
main source, starter, or plugin code: the Makefile and the release docs, the seven demos, the docs site, the
changelogs, and the docs' examples and checks. Four more subagents, each in its own worktree of `4830930e`, rechecked
the 33 findings whose cited files or claims those commits reach, and reviewed the commits themselves:

- build, release, and the demos;
- documentation;
- the configuration reference, all 171 settings rows against the code;
- the rest of the docs site, built and link-checked, its scripts and contrast included.

The other 26 findings cite only unchanged files and carry over as they were. No finding is fully fixed: #25 and #39
are partly fixed, #8 is rewritten for #540's version paragraphs, and many line numbers moved. #60–#66 are new; #60
was reproduced by stopping a demo image's proxy for 110 seconds.

**Third pass, 2026-11-24:** six more commits (#543, #545–#549) landed before anything was committed, again with no
main source: the demos' tours and READMEs, the docs site (a rewritten home page and title-cased headings), the
release and testing docs, and the harness's test settings. Three more subagents, each in its own worktree of
`5c9f0a24`, rechecked the 39 findings those commits could reach and reviewed the commits:

- build, release, the demos, and tests;
- documentation;
- the docs site, built and link-checked after the heading changes, its restored home-page diagram rendered.

No finding is fixed: #27 and #38 are rewritten, #29 and #60 reach new places, and #67–#72 are new, three of them
merged from findings two or three reviews made apart. #72's gaps were found by planting errors in a scratch copy of
the tree, and #29's new reach by a run that passed `UP-TO-DATE` on four broken demo files.

**Earlier findings followed up here:** these earlier fixes turn out to be incomplete, or left a sibling unfixed.

| Earlier finding                              | Follow-up here |
|----------------------------------------------|----------------|
| June and July (the rethrow-`Error` policy)   | #7             |
| late-September #14 and November #12, #15     | #20            |
| November #16 and #40                         | #22            |
| November #18                                 | #13            |
| November #19                                 | #9             |
| November #38 and #41                         | #58            |

---

## 📡 Proxy

### 1. ✅ `ProxyRegistry.tla` invalidates an orphaned connection without clearing its unread notices, which the code's `invalidate()` clears, so the model lets a dead connection deliver notices the code drops

**Severity:** low · **Confidence:** confirmed by trace (model against code)

**Where:**

- `specs/tla/ProxyRegistry.tla:237-261`: `RegisterLocked` invalidates `orphans` (`valid' = FALSE`, `draining' = TRUE`)
  but leaves `notices[o]` as it was: the `notices'` update covers only `tellees` (another launch) and `c`.
- `specs/tla/ProxyRegistry.tla:274-280`: `NoticeEmit(c, p)` needs only `phase[c] = "live"`, which an orphan keeps
  (orphan invalidation, unlike `RemoveCtx`, doesn't change `phase`). `Fairness` puts `WF_vars(NoticeEmit(c, p))` on
  it, so the model *forces* the orphan to deliver.
- Code: `proxy/ProxyPathManager.kt:598-611` (`settleDisplaced`) calls `displacedContext.invalidate()`, and
  `proxy/AgentContext.kt:318-322` closes `pathNoticeSignal` and clears `pendingPathNotices`, so the orphan's
  `readPathNoticesFromProxy` ends (`proxy/ProxyServiceImpl.kt:461`) and its unread notices are gone.
- `RemoveCtx` (`ProxyRegistry.tla:150-156`) does clear `notices[c]`, with the comment "which invalidates the context,
  and its unread notices with it", so the model treats the two invalidations differently where the code doesn't.

**Problem:** AGENTS.md says to update the matching action when changing the lifecycle of these classes. The orphan
branch of `RegisterLocked` models `invalidate()`'s validity flip and drain but not its notice clear and stream close.
The drift is in the safe direction: the model allows behavior the code can't produce (an orphan's stale notice
adding `p` to `told[launch]`), so every property TLC proves still holds for the code. It's still a drift. It also
means `DisplacedReclaims` and `ReconnectReclaims` are checked partly over states the code never reaches, and it
leaves out one the code does reach: an agent that never hears about a takeover because its orphaned connection's
notice was cleared. That case is benign (one swap, after which the displaced agent is told and waits), and the model
already covers it through `RemoveCtx`.

**Fix:** in `RegisterLocked`'s accepting branch, clear the orphans' notices too:
`notices' = [x \in Conns |-> IF x \in tellees THEN notices[x] \cup {p} ELSE IF x = c THEN notices[x] \ {p} ELSE IF x
\in orphans THEN {} ELSE notices[x]]`. `NoticeEmit` could also require `valid[c]`, which matches the closed signal.
Then run `make tla-checks-full`.

**Tests:** `make tla-checks-full` still passes. A temporary invariant `\A o \in Conns : ~valid[o] /\ phase[o] =
"live" => notices[o] = {}` fails in `ProxyRegistryReconnect.cfg` today and holds after the change.

**Resolution:** Fixed in #559. `RegisterLocked` clears an orphan's unread notices as it invalidates it, and the new `InvalidatedHasNoNotices` is checked in all four ProxyRegistry configs; the optional `valid[c]` guard on `NoticeEmit` was left out, as the invariant would catch the slip it hid. One correction: no shipped config broke the invariant before the fix, since with one path an orphan has always just lost it to its own launch; with two paths and three connections the unchanged model breaks it at depth 12, a model too large to ship. TLC also showed the model needed the notice a refused reclaim sends (`tellHolder` in `reclaimRejection`), which the appendix had called irrelevant, so it sends it now.

### 2. ✅ `sendScrapeRequest` tags the forward span after a send that can outlast the scrape, so a timed-out scrape's span loses its queue wait and "sent to agent"

**Severity:** low · **Confidence:** plausible: the gap is confirmed in Brave 6.3.1, zipkin-reporter-brave 3.5.3, and
grpc-kotlin 1.5.0 source; it needs an agent that has stopped reading its scrape-request stream long enough to trip
gRPC flow control.

**Where:**

- `proxy/ProxyServiceImpl.kt:507-521`: `queueWait` is read and observed, then `send(wrapper.scrapeRequest)`, and only
  after the send returns does it `annotate("sent to agent")` and `tag(TAG_QUEUE_WAIT, …)` on `wrapper.traceSpan`.
- The send is the flow's `emit`, which in grpc-kotlin's `ServerCalls.kt:237` runs `readiness.suspendUntilReady()`
  before `call.sendMessage`. It suspends while the call isn't ready: more than 32 KiB queued for an agent whose own
  bounded `scrapeRequestActionsChannel` (`agent/AgentConnectionContext.kt:64`) has applied backpressure.
- `proxy/ScrapeDispatch.kt:155-164`: `traced` finishes the forward span as soon as `awaitScrapeResponse` returns,
  which on `awaitCompleted`'s timeout doesn't wait for the stream.
- Brave `RealSpan.tag` / `annotate` don't check whether the span has finished. `PendingSpans.finish` hands the
  `MutableSpan` to the handler, and `ConvertingSpanReporter.report` converts it to a `zipkin2.Span` right then, so
  later tags are silently dropped.

**Problem:** for a backpressured agent, the case where the queue wait matters most, the request's forward span is
finished (tagged `timed_out`) while `emit` waits. The `queue.wait_ms` tag and the "sent to agent" annotation then go to
a finished span and are lost, so Zipkin shows a timed-out forward span with no sign that the request waited in the
stream or was sent at all. AGENTS.md says each span's lifecycle goes through `ScrapeTracing.traced`, and this write
happens outside it. (The `proxy_scrape_queue_wait_seconds` observation, taken before the send, leaves out the same
wait. See "Considered".)

**Fix:** tag `TAG_QUEUE_WAIT` before calling `send`, since it's computed there anyway. Annotate after the send only
when the scrape is still tracked (`proxy.scrapeRequestManager.containsScrapeRequest(wrapper.scrapeId)`; the handler
removes it from the map before `traced` finishes the span), or drop the post-send annotation.

**Tests:** a `ProxyServiceImpl` unit test with a Brave `Tracing` on a `TestSpanHandler` and a wrapper carrying a
forward span. Pass a `send` that finishes the span before returning, which stands in for the scrape timing out during
a blocked emit, then check that the reported span has `queue.wait_ms`. It's missing today.

**Resolution:** Fixed in #565. `sendScrapeRequest` now tags the forward span with `queue.wait_ms` before the send, and adds `sent to agent` after it only while the scrape is still tracked (`containsScrapeRequest`), so a send held by flow control can no longer leave a timed-out span without its wait, and nothing is written to a span that has finished. A `ProxyServiceImplTest` case finishes the span during the send, as a timeout would, and failed before the fix; a second, with a mock span, checks that no annotation follows a scrape that has ended. The test collector, `TracingMocks.collectInto`, now copies each span as it finished: before, a late tag still reached the shared span, which hid the loss a real reporter suffers. The `proxy_scrape_queue_wait_seconds` observation stays where it was, as Considered says.

### 3. ✅ The 80% warnings for `maxPathsPerAgent` and `maxAgentsPerIdentity` fire every time the count climbs back to the threshold, not once, as the path one's comment says

**Severity:** low · **Confidence:** confirmed by trace

**Where:**

- `proxy/ProxyPathManager.kt:348-357`: "Warns once as an agent's path count reaches 80% of maxPathsPerAgent", but
  the check is `count == (max * 4 + 4) / 5` on every new registration (`recordRegistration`, :629-632), with no memory
  that it already warned.
- `proxy/AgentContextManager.kt:131-137`: the same equality test on every accepted connect. It's new in this range
  (#387), and copies the path one, which predates it (#293).

**Problem:** a count that hovers at the threshold crosses it again and again. An agent near 16,000 of 20,000 paths
whose discovery file churns logs the WARN on each reconcile that unregisters one path and registers another. With
the transport filter disabled and agent auth off (or only `proxy.agentToken`), every agent shares one identity, so a
fleet sitting near 8,000 of the default 10,000 logs "8000 agents are connected with no auth identity…" on each
reconnect that brings the count back up. The refusal WARN beside it is throttled with `KeyedThrottle`, but this one
isn't.

**Fix:** warn once per crossing with hysteresis. Remember that the agent (a flag on `AgentContext`) or the identity
(a set in `AgentContextManager`) was warned, and clear the flag only when the count drops below, say, 70%. The simpler
option is to throttle the identity WARN with the existing `refusalWarnings`-style `KeyedThrottle`, and the path one
per agent. Either way, the comment then holds.

**Tests:** a `ListAppender` test with `maxPathsPerAgent = 5` (threshold 4): register 4 paths (one WARN), unregister
one, register another, and expect still one WARN. Today it's two. The same for `addAgentContextWithinLimit` with
`maxPerIdentity = 5`: add 4, remove one, add one.

**Resolution:** Fixed in #565. A new `NearLimitWarnings` carries the hysteresis #560 gave `maxPathsPerIdentity`'s warning (warn as a count reaches 80%, and again only once it has fallen below 70%), and all three limit warnings use it. `ProxyPathManager` reports both the agent's and the identity's path counts from `countPaths`, under the `pathMap` lock, so every decrease, an agent's removal included, re-arms the warning and no memory outlives the agent; `AgentContextManager` reports an identity's agent count inside the compute that adds or removes the agent. With a limit of 10, going 8 → 7 → 8 → 6 → 8 now warns twice, not three times, for paths per agent and for agents per identity, and `NearLimitWarningsTest` covers the boundaries. Unlike the fix this finding suggested, the warned keys live in one shared class, not a flag on `AgentContext` or a set in the manager, and the threshold is "at least 80%" where it was "exactly 80%".

### 4. ✅ The proto doesn't say that a `PathNotice` with no path is a keepalive, and AGENTS.md says every keepalive waits for the other side's say-so, which the notices stream's don't

**Severity:** low · **Confidence:** confirmed by trace

**Where:**

- `src/main/proto/proxy_service.proto:126-134`: `PathNotice` is "that another agent holds it now, or, with both
  held_by fields empty, that no agent serves it any more". The rpc comment at :255-256 says nothing about keepalives
  either.
- `proxy/ProxyServiceImpl.kt:452-464`: the proxy sends `PathNotice.getDefaultInstance()` (every field empty) after
  each `keepaliveIdle`, to every agent that reads the stream, without checking `acceptsStreamKeepalives`.
- `AGENTS.md:175`: "A side sends keepalives only once the other said at registration that it takes them (the agent's
  `stream_keepalives`, a nonzero interval from the proxy)".

**Problem:** the proto is where the wire contract lives. A notice with both `held_by` fields empty is defined there
as "the path is free", and nothing says an empty path means "ignore me". The stock agent handles it
(`AgentPathManager.notePathNotice` returns on an empty path), but the rule is written only in the Kotlin. AGENTS.md's
statement is true of `readRequestsFromProxy` and the two response streams. It isn't true of the notices stream, whose
keepalives are unconditional, since every agent that calls it postdates them. A future change that follows AGENTS.md
literally, say a reader that predates keepalives, would misread them.

**Fix:** add to the `PathNotice` comment: "A notice with no path is a keepalive, sent after
`proxy.internal.streamKeepAliveSecs` of quiet, which the agent ignores; every agent that reads this stream takes them,
so no flag gates them." In AGENTS.md, scope the sentence to `readRequestsFromProxy` and the response streams, and say
the notices stream sends them to every reader.

**Tests:** none needed: comments only.

**Resolution:** Fixed in #565. The proto's `PathNotice` comment now says a notice with no path is a keepalive, which the agent ignores: the proxy sends one after `proxy.internal.streamKeepAliveSecs` of quiet, none when that is 0, and unlike the other streams' keepalives no registration flag gates them, since every agent that reads the stream ignores one. The `readPathNoticesFromProxy` rpc comment points to it, and AGENTS.md limits its gating rule to `readRequestsFromProxy` and the response streams. Only comments changed, so the code and the wire format are as they were. The website's keepalive text is left as is: only an agent that takes keepalives ever opens the notices stream, so it holds for users.

---

## 🛰️ Agent and shared code

### 5. ✅ When the agent's dashboard can't bind its port, the admin, metrics, and Zipkin services and the JVM shutdown hook keep running, so a standalone agent never exits and its `/ping` still answers 200

**Severity:** medium · **Confidence:** reproduced with the shipped `prometheus-agent.jar`. The config had the dashboard on
port 27420, which another process was holding, and the admin server on 27421 and the metrics server on 27422. `main()`
threw `IllegalStateException: Expected the service Agent{…} to be RUNNING, but the service has FAILED` (caused by
`BindException: Address already in use`), but the process was still running 15 s later. `lsof` showed it listening on
27421 and 27422, and `/ping` and `/metrics` both answered 200. When the process got SIGTERM, the leftover shutdown hook
threw `Expected the service Agent{…} to be TERMINATED, but the service has FAILED`.

**Where:**

- `Agent.kt:871-884`: `startUp()` calls `super.startUp()`, which starts Zipkin, the metrics server, and the admin
  server, then registers the JVM shutdown hook (common-utils 5.3.0, `AbstractGenericService.kt:208-243`, hook at
  `:241-242`). Only after that does it call `dashboardService?.startSync()`. When the dashboard throws, the
  `onFailure` runs only `releaseAfterFailedStartUp()`.
- `Agent.kt:892-907`: `releaseSteps` covers the dashboard, the gRPC channel, the HTTP client cache, tracing, and the
  agent metrics. It doesn't call `super.shutDown()`, so whatever `super.startUp()` started is never stopped, and the
  hook isn't removed. The comment at `:902-904` ("super.startUp() stops the admin, metrics and Zipkin services it
  started when it fails") holds only when `super.startUp()` itself is what fails.
- `Agent.kt:935-941`: `stop()` returns at once for a `FAILED` agent, on the grounds that "a failure in startUp()
  [went] through super.startUp()'s own rollback and releaseAfterFailedStartUp()". An embedder therefore has no way to
  release these ports.
- `Agent.kt:1054-1055` and `:1088-1089`: `startAsyncAgent`'s `@throws IllegalStateException` says "whatever the Agent
  had opened is released first."
- The proxy handles the same case correctly. `Proxy.kt:393-424` stops each sub-service that started and then calls
  `super.shutDown()`, and its comment names this symptom: "Left running, the admin server's threads kept a standalone
  proxy's JVM alive after main() threw." The agent's dashboard came in #481 without that handling.

**Problem:**

- **Standalone agent:** a dashboard port that's taken, or a bad `agent.dashboard.host`, leaves the process running
  with no gRPC connection, held up by the admin and metrics servers' non-daemon threads. In Kubernetes, `/ping` answers
  200, so the pod passes its liveness probe and is never restarted. It never connects either.
- **Embedded agent** (`startAsyncAgent`, or `EmbeddedAgentConfig.start()`, which the Spring Boot starter and the Ktor
  plugin call):
  - The host catches the `IllegalStateException`, but the admin and metrics ports stay bound for the life of its JVM,
    as do the Zipkin reporter and the Dropwizard export registration.
  - A retry fails on the admin port.
  - A host that gives up on startup, such as a Spring Boot app whose context fails, can't exit while those threads
    run.

**Fix:** do what `Proxy.startUp()` does. After `super.startUp()` returns, start the dashboard inside a `runCatching`.
On failure, run `dashboardService?.stopSync()`, then `super.shutDown()` (which stops the admin, metrics, and Zipkin
services, unregisters the Dropwizard exports, and removes the hook), then the rest of `releaseSteps`, and rethrow with
the others suppressed. Then correct the comments at `Agent.kt:902-904` and `:936-937`.

**Tests:** an `AgentTest` case that holds the dashboard port with a `ServerSocket`, enables admin and metrics on two
free ports, and calls `startSync()` expecting `IllegalStateException`. It then checks that both ports can be bound
again and that no shutdown hook is left. common-utils' `registeredShutDownHook` is `internal` to its own module, so the
test reads it through reflection. Today's code fails both checks. An `EmbeddedAgentApiTest` case
should check the same through `startAsyncAgent(Config)`.

**Resolution:** Fixed in #557. `Agent.startUp()` starts in two parts: a failure in `super.startUp()` runs the release steps, and a dashboard that fails after it returned runs them and `super.shutDown()` too, stopping the admin, metrics, and Zipkin services and removing the shutdown hook, as `Proxy.startUp()` does; what the release throws is attached as suppressed, and a FAILED dashboard isn't stopped again. The fix also closes a second leak: Ktor's `start()` leaves its shutdown hook and application behind when the bind fails, so `DashboardService.startUp()` stops the server. On the shipped jar, an agent with its dashboard port held now exits with status 1 in about 1.3 s; it ran on with `/ping` answering 200. `AgentStartupTest` and `DashboardServiceTest` pin both.

### 6. ⬜ Results still queued in the connection's results channel when a writer stream fails are never counted, as sent or as dropped

**Severity:** low · **Confidence:** confirmed by trace, including kotlinx-coroutines 1.11.0's channel source. Not
reproduced.

**Where:**

- `agent/AgentGrpcService.kt:834-850`: `streamOf` reads its channel inside `consume { }`. When gRPC cancels the request
  flow because the call failed, `consume` cancels the channel (`cancelConsumed`, `Channels.common.kt:266-270`). The
  messages queued in it are counted as dropped through `onUndeliveredElement`, which is correct.
- `agent/AgentGrpcService.kt:750-756`: `processScrapeResults` keeps draining `connectionContext.scrapeResults()`,
  because `closeAll()` closes that channel rather than cancelling it, and forwards each result. On a cancelled channel,
  `send` throws the channel's close cause, which is a `CancellationException` (`BufferedChannel.kt:1736`:
  `sendException = closeCause ?: ClosedSendChannelException`; `:1772-1775`).
- `agent/AgentGrpcService.kt:873-883`: `runStreamTask` rethrows a `CancellationException`. The producer's loop ends
  there, and its `finally` (`:892-897`) only closes the two outgoing channels.
- `agent/AgentConnectionContext.kt:65`: `scrapeResultsChannel` is `Channel(UNLIMITED)` with no
  `onUndeliveredElement`. Nothing receives the results still in it, and nothing counts them.
- The comment at `AgentGrpcService.kt:751-753` says the send "Throws ClosedSendChannelException … the channel counts it
  as dropped." That is true only of the one result whose send failed, and only when the channel was closed rather than
  cancelled. When the channel was merely closed (by `closeAll()`, while the other writer is the one that failed), the
  `ClosedSendChannelException` goes to `logStreamFailure`. Its status reads as `UNKNOWN`, so it logs an ERROR with a
  stack trace for an ordinary disconnect.

**Problem:** `ResultCount`'s KDoc (`:110-114`) promises that every result is counted once: as its type when gRPC takes
its last message, or as `dropped`. When a writer stream fails, as it does on a proxy restart or a reverse proxy cutting
the stream, the results that scrapes finished just before the failure are in neither count. The same happens when the
connection is cancelled because another task ended (heartbeat `EVICTED`): `launchConnectionTask`'s
`invokeOnCompletion` closes the context with results still buffered. The gap is small, at most about
`maxConcurrentHttpClients` results per disconnect. But it is exactly the case `agent_scrape_result_count_total{type="dropped"}`
exists to show, and its sum stops matching `agent_scrape_request_count_total`.

**Fix:**

- In the producer's `finally`, drain `connectionContext.scrapeResults()` with `tryReceive` and count each leftover with
  `agent.updateScrapeResultCounter(it, RESULT_DROPPED)`. Alternatively, give `scrapeResultsChannel` an
  `onUndeliveredElement` callback that does the same, and cancel it in `close()` once the producer has gone.
- In `forwardScrapeResult`, treat a `ClosedSendChannelException` as the end of the connection: don't log it as a stream
  failure.
- Fix the comment.

**Tests:** an `AgentGrpcServiceTest` case:

- Queue three non-gzipped results on a connection context.
- Make the non-chunked writer's stub fail on its first message.
- Run `writeResponsesToProxyUntilDisconnected`.
- Check that `agent_scrape_result_count_total` across `non-gzipped` and `dropped` comes to 3. Today it comes to fewer.

A second case, where the chunked writer is the one that fails, should check that no ERROR is logged for
`processScrapeResults`.

### 7. ⬜ Connection tasks, stream writers, heartbeats, and discovery catch JVM `Error`s, so an `OutOfMemoryError` there ends only the connection, and the agent reconnects

**Severity:** low · **Confidence:** confirmed by trace. `runCatchingCancellable` is `runCatching` plus a rethrow of
`CancellationException` (common-utils 5.3.0 `ExceptionUtils.kt:34-38`), so it catches `Error`.

**Where:**

- `Agent.kt:603-611`: `launchConnectionTask` runs each connection task in `runCatchingCancellable { block() }`, so an
  `Error` thrown in a task's own body is logged and noted, and the connection ends normally. The task bodies are the
  `readRequestsFromProxy` collect, `startHeartBeat`, `readPathNoticesFromProxy`, the reclaim task, discovery,
  `retryRejectedStaticPaths`, and `writeResponsesToProxyUntilDisconnected`. `registerAndServe` then returns, and
  `run()` reconnects.
- `agent/AgentGrpcService.kt:873-883`: `runStreamTask` does the same for `processScrapeResults` and both writers. This
  is where the large allocations happen: `ByteArray(chunkContentSize)`, `ByteString.copyFrom` of up to
  `maxContentLengthMBytes` in `toScrapeResponse`, and each chunk.
- `agent/AgentGrpcService.kt:670-685`: `sendHeartBeat` turns an `Error` into `TRANSIENT_FAILURE`.
- `agent/discovery/PathDiscoveryService.kt:114-137`: `reconcileOnce` turns one into a WARN "reconcile failed".
- `agent/AgentPathManager.kt:312,498,658,711`: the per-path retry, reclaim, unregister, and register isolation turns
  one into a refused or failed path.
- The policy these break is stated at `Agent.kt:644-649` ("Re-throw JVM Errors … so the agent terminates instead of
  running in a corrupted state"), `AgentHttpService.kt:360-364`, `AgentGrpcService.kt:451-455`, and
  `HttpClientCache.kt:387-399`. The June and July reviews established it and fixed the individual sites they found. A
  scrape follows it, because its `Error` fails a child coroutine and propagates through the connection's
  `coroutineScope`. Every other connection task's `Error` doesn't.

**Problem:** an agent under memory pressure that runs out of heap while building a chunked result logs one ERROR from
`processScrapeResults`, drops the connection, and reconnects every `reconnectPauseSecs`. It keeps running in the state
the policy exists to avoid, rather than failing so a supervisor restarts it. The same `Error` in a scrape terminates
the agent, so the outcome depends on which coroutine allocated.

**Fix:** in `launchConnectionTask` and `runStreamTask`, rethrow an `Error` (`.onFailure { if (it is Error) throw it }`
before the logging), so it fails the connection's scope and reaches `handleConnectionFailure`, as a scrape's does. Do
the same in `sendHeartBeat`, `reconcileOnce`, and the path manager's per-path `runCatchingCancellable` blocks, or add a
shared `runCatchingNonFatal` helper next to `failureKey` that rethrows `Error` and `CancellationException`.

**Tests:**

- An `AgentTest` case drives `launchConnectionTask` with a block that throws `OutOfMemoryError("test")`, inside a
  `coroutineScope`. It expects the scope to throw the `Error` rather than complete. Driving the agent through an
  in-process proxy stub whose `readRequestsFromProxy` flow throws it would instead check that the service ends
  `FAILED` rather than reconnecting.
- A `PathDiscoveryServiceTest` case whose source throws `StackOverflowError` expects it to propagate from
  `reconcileOnce`.

### 60. ⬜ The demo pages say a single-endpoint agent retries the proxy every 3 seconds and reconnects within a few seconds of its return, but gRPC paces the real attempts on an exponential backoff, so after a 110-second outage the agent took 34 seconds to reconnect

**Severity:** low · **Confidence:** reproduced: an agent and a proxy from the demos' `prometheus-proxy-demo/*:local`
images (version 4.7.0, built from this code), on a Docker network of their own, one `pathConfigs` entry and no other
setting. `docker stop` on the proxy, 110 seconds, `docker start`. The agent logged `Waited 3s to reconnect` every 3
seconds throughout, and `Failed to resolve name` (gRPC's resolver) at intervals of about 18, then 24 seconds, and
connected 34 seconds after the proxy was back. Traced in grpc-java 1.85.1's source.

**Where:**

- The new "How it works" rows (#541): `website/prometheus-proxy/docs/ktor-demo.md:307` ("the agent retries every 3
  seconds until it's back") and `spring-boot-demo.md:217` ("the agent retries every few seconds").
- The tours' prose: `ktor-demo.md:284` ("the agent reconnects within a few seconds"), `spring-boot-demo.md:187-188`
  ("the agent connects within a few seconds"), and, from before this range, `basics-demo.md:302` ("the agent tries
  again every 3 seconds"), `:306` ("within a few seconds"), and `:360` ("tries again every 3 seconds
  (`agent.internal.reconnectPauseSecs`) until the proxy is back").
- The scenarios' own messages say the same: `demos/ktor/demo.sh:949-950, 968-969`, `demos/spring-boot/demo.sh:739-740,
  757`, `demos/basics/demo.sh:941-943, 963`.
- So do the demos' READMEs: `demos/ktor/README.md:285` ("the agent reconnects within a few seconds") and `:342` ("the
  agent retries every 3 seconds until it's back"), `demos/spring-boot/README.md:161` ("the agent connects within a few
  seconds") and `:180` ("the agent retries every few seconds"), and `demos/basics/README.md:282-283` ("The agent tries
  again every 3 seconds"), `:295` ("Within a few seconds agent-east connects"), and `:370`, the page's row.
- Against them, #539's own onboarding text, `security/index.md:170-172`: "gRPC's backoff between attempts grows while
  it's down, so a quick restart is a quick reconnect", which is what the code does.
- The code:
  - `Agent.kt:381-399`: the run loop calls `connectToProxy` every `reconnectPauseSecs` (3).
  - `agent/EndpointFailover.kt:42-61`: after a connection that registered and then dropped, the agent builds a new
    channel once (`:44-47`). After that, an agent with one endpoint keeps the same channel for every attempt, "leaning
    on gRPC's own backoff", as the comment at `:49-52` says. Only an agent with more endpoints builds a new channel,
    with a fresh backoff, per attempt.
  - `agent/AgentGrpcService.kt:432-457`: `connectAgent` is a plain unary call, without `withWaitForReady` and without
    `channel.resetConnectBackoff()`.
  - grpc-java 1.85.1: `InternalSubchannel.scheduleBackoff` (`:296-329`) schedules the next dial after
    `ExponentialBackoffPolicy`'s delay (`:40-43`: 1 s, ×1.6, ±20%, at most 2 minutes); the name resolver retries on
    the same policy, which is what the reproduction hit, since Docker's DNS drops a stopped container's name.
    `PickFirstLoadBalancer` keeps a channel in TRANSIENT_FAILURE until a connection is READY ("sticky
    TRANSIENT_FAILURE", `:121-130`), so every call in between fails at once without dialing.

**Problem:** the agent's calls come every 3 seconds, but each one fails at once against the channel's cached failure,
and the channel resolves and dials only on its backoff schedule. A proxy back after a minute is found about 10 seconds
later; after 110 seconds, 34 in the reproduction; after a few minutes, up to 2 minutes later. The tours have the reader
stop the proxy, wait for `CoffeeShopDown` ("within about half a minute", `demos/ktor/demo.sh:956`), read `/debug` and
the agent's Connection view, and only then start the proxy, so the outage is a minute or more, and the reader waits
tens of seconds for the "few seconds" reconnect, with the Connection view still `failing`. The basics demo's row names
`agent.internal.reconnectPauseSecs` as the pace, which `config/config.conf:277` describes as the "Pause interval
between connect attempts", so a reader takes it as the reconnect time an outage costs. An agent with two or more
endpoints (the Network Demo's, `network-demo.md:310`, "retry every 3 seconds") does dial every 3 seconds, so the claim
holds there.

**Fix:** either:

- **Make the pause the pace:** call `channel.resetConnectBackoff()` in `connectAgent` when the previous attempt failed
  (or rebuild the channel per attempt for a single endpoint too, as `EndpointFailover` does for several). grpc-java's
  `ManagedChannelImpl.resetConnectBackoff` (`:1247-1264`) refreshes name resolution and has each subchannel in
  TRANSIENT_FAILURE dial at once, so the agent tries every `reconnectPauseSecs`, as the config comment, the demos, and
  `production.md:120` say. Or:
- **Say what happens:** in the places above, "the agent keeps retrying, gRPC spacing its tries further apart the
  longer the proxy is down, up to 2 minutes", and "the agent reconnects at its next try, within seconds of a short
  outage and up to 2 minutes after a long one"; and give `reconnectPauseSecs` the same caveat in `config/config.conf`.

**Tests:** for the first fix, an `AgentGrpcService` unit spec with an in-process server started after the channel has
failed three times: the next `connectAgent` succeeds without waiting out a backoff. For the second, none; it's prose.

---

## 🔌 Embedding: the API, the Spring Boot starter, and the Ktor plugin

### 8. ✅ Under Spring Boot 4.0.x and 4.1.0, which the starter's docs support, the agent dies at its first connection with a `NoSuchMethodError`

**Severity:** medium · **Confidence:** confirmed by trace and a bytecode check. Each `io/prometheus/metrics/*` member
the published 4.7.0 jar references (`4.7.0-consumer-check-SNAPSHOT` in `~/.m2`) was resolved against the Prometheus
client jars from Maven Central. 1.4.3 and 1.5.1 lack 4 of them: `MetricFamilyDescriptor`,
`MetricWithFixedMetadata.getMetricFamilyDescriptor()`, and `MetricMetadata.getExpositionBasePrometheusName()`.
1.7.0 resolves all 72. The Kotlin stdlib references all resolve against 2.2.21, so Spring Boot 4.0's Kotlin pin isn't
part of this.

**Where:**

- `agent/AgentMetrics.kt:357-362`: the companion's `FAMILY_NAMES` calls `collector.metricFamilyDescriptor` and
  `metadata.expositionBasePrometheusName`. It's a companion property, so it's set in `AgentMetrics`' static
  initializer.
- `agent/AgentMetrics.kt:112`: `connectOutcome()` is in that companion.
- `Agent.kt:452-455`: `registerAndServe` calls `AgentMetrics.connectOutcome(...)` on every connect, outside
  `metrics { }`, so it runs whether or not the agent collects its metrics. The bytecode is
  `getstatic AgentMetrics.Companion`, which runs the static initializer.
- `Agent.kt:874-876`: `startUp()` builds `AgentMetrics` when `collectsMetrics`.
- Spring Boot's `spring-boot-dependencies` sets `prometheus-client.version` as follows:

  | Spring Boot | prometheus-client |
  |---|---|
  | 4.0.0 to 4.0.6 | 1.4.3 |
  | 4.1.0 | 1.5.1 |
  | 4.1.1 and 4.1.2 | 1.7.0 |
  | 4.2.0 | 1.10.0 |

  It imports `prometheus-metrics-bom` at that version, so a Maven app with Spring Boot's parent, or Gradle's
  dependency-management plugin, gets the agent's `api` dependency `prometheus-metrics-model` and the rest at that
  version.
- Docs that claim support:
  - `website/prometheus-proxy/docs/spring-boot-starter.md:28`: "The starter needs Spring Boot 4".
  - `spring-boot-starter.md:116-124` and `embedded-agent.md:368-376` (rewritten in #540) list no properties any more:
    they say a release that breaks the agent is fixed by setting "that library to the agent's version with the
    property Spring Boot names it by, such as `grpc-java.version` or `netty.version`". That rule reaches
    `prometheus-client.version`, the one these releases need set forward, but nothing names the Prometheus client, and
    the Spring Boot demo's `./demo.sh versions` (`demos/spring-boot/demo.sh:407-432`), where the starter page sends
    readers to see which libraries run older, has no row for it.

**Problem:** take an app on Spring Boot 4.0.x or 4.1.0 that adds the starter, or embeds the agent with Maven and
Spring Boot's parent:

- **With `metrics-port` or `metrics-proxy-path` set:** `startUp()` throws `ExceptionInInitializerError`, the start
  fails, and the agent stays off (or a required agent fails the app).
- **Without them:** the agent starts and retries until a proxy answers its registration. Then `connectOutcome`
  throws:
  - `handleConnectionFailure` logs "Fatal JVM error ExceptionInInitializerError" and rethrows.
  - `run()` ends and the service goes to FAILED.
  - The app runs on with an agent that never serves.
  - The starter's health shows UNKNOWN "reconnecting" from then on, since the handle is still held.
- **Starter tests and the roastery:** they run only at the catalog's Spring Boot (4.2.0, prometheus-client 1.10.0),
  so nothing checks the lower end of the range the docs claim.

The same applies to any Maven embedder whose own dependency management sets the Prometheus client below 1.7.0.

**Fix:**

- Keep `AgentMetrics`' static initializer off the path of an agent that doesn't collect metrics. Move `connectOutcome`
  out of the companion, to a top-level function or one on `Agent`.
- Either build `FAMILY_NAMES` without the 1.7 API, or fail fast with a clear message:
  - Without it: `getPrometheusName()`, plus `_total` for a `Counter`, which 1.4.3 has.
  - Fail fast: have `Agent.startUp` check `Class.forName("io.prometheus.metrics.model.snapshots.MetricFamilyDescriptor")`
    and throw a `ConfigLoadException` that names `prometheus-client.version`.
- Make the docs say "Spring Boot 4.1.1 or later" (or 4.0 with `prometheus-client.version` set to 1.10.0), and add
  `prometheus-client.version` beside `grpc-java.version` and `netty.version` in the version paragraphs
  (`spring-boot-starter.md:116-124`, `embedded-agent.md:368-376`, and the Spring Boot demo's README part 3), and a
  Prometheus client row (`prometheus-metrics-model`) to the demo's `./demo.sh versions`.
- Have CI build the starter's tests, or the roastery, at the lowest supported Spring Boot. A Gradle test task with
  `enforcedPlatform("org.springframework.boot:spring-boot-dependencies:4.0.6")` is enough.

**Tests:**

- A starter test task run under the Spring Boot 4.0.6 BOM, which today fails `PrometheusProxyAgentIntegrationTest`'s
  scrape.
- A unit test that an agent with metrics off connects, registers, and serves with `prometheus-metrics-model` 1.4.3
  first on the classpath.

**Resolution:** Fixed in #550. The gap was wider than this finding says. Spring Boot 4.1.1 to 4.1.3, whose Prometheus
client is 1.7.0, failed too: Spring Boot 4.0 and 4.1 set kotlinx-coroutines to 1.10.2, and the agent's `run()` and
Ktor 3.6 call `runBlockingK`, new in 1.11. Nor can Prometheus client 1.4.3 be supported by building `FAMILY_NAMES`
without the 1.7 API: it refuses the gauge name `agent_build_info`. So:

- The starter's docs say Spring Boot 4.2 or later, or 4.0 or 4.1 with `kotlin-coroutines.version` set to 1.11.0 and
  `prometheus-client.version` to 1.10.0, as do the Embedded Agent page and the Spring Boot demo's docs, whose
  `./demo.sh versions` gains a Prometheus client row.
- An embedded agent checks both libraries before it starts (`RuntimeLibraries`, from `Agent.startEmbedded`), and
  refuses with an `IllegalStateException` that names each property to set.
- `connectOutcome` moved out of `AgentMetrics`' companion.
- `oldestSpringBootTest` runs the starter's tests under Spring Boot 4.0.10 with the two set; without them, the
  integration test failed with the `NoSuchMethodError`.

### 9. ✅ The starter puts Logback on every embedder's classpath, so a Spring Boot app that logs with Log4j2 breaks: it fails to start, or loses its Log4j2 configuration

**Severity:** medium · **Confidence:** confirmed by trace. The published POM was read, as was Spring Boot 4.2.0's
`LogbackLoggingSystem` and `Log4J2LoggingSystem`. Which of the two breakages an app sees depends on SLF4J's provider
order.

**Where:**

- `build.gradle.kts:189`: `implementation(libs.logback.classic)`, for `Utils.setLogLevel`
  (`common/Utils.kt:129-158`). The published POM therefore lists `ch.qos.logback:logback-classic:1.6.8` at runtime
  scope.
- The starter and the plugin pass it on. `spring-boot-starter/build.gradle.kts:28` has `api(project(":"))`, so the
  starter's POM puts the agent at compile scope.
- Spring Boot 4.2.0 (`spring-boot-4.2.0-sources.jar`):
  - `LogbackLoggingSystem.Factory` is `@Order(HIGHEST_PRECEDENCE + 1024)` and returns a `LogbackLoggingSystem`
    whenever `ch.qos.logback.classic.LoggerContext` is on the classpath. It comes ahead of `Log4J2LoggingSystem.Factory`
    (`@Order(0)`).
  - `LogbackLoggingSystem.getLoggerContext()` asserts that SLF4J's factory is a Logback `LoggerContext`. If it isn't,
    it throws "LoggerFactory is not a Logback LoggerContext but Logback is on the classpath. Either remove Logback or
    the competing implementation".

**Problem:** a Spring Boot app on Log4j2 swaps `spring-boot-starter-logging` for `spring-boot-starter-log4j2`, as
Spring Boot's docs describe. Adding the starter brings Logback back, and Spring Boot then picks its Logback logging
system:

- **SLF4J binds to Log4j2's provider:** the app fails at startup with that `IllegalStateException`.
- **SLF4J binds to Logback:** the app's `log4j2.xml` is ignored, and its logs go wherever Logback's defaults send
  them.

A Ktor or plain app on another backend gets SLF4J's "multiple providers" warning, and a binding that depends on
classpath order. The embedded-agent page's Logging section (`embedded-agent.md:560-563`) assumes the host uses
Logback. November #19 took `logback.xml` out of the jar but kept the dependency.

**Fix:**

- Make `logback-classic` `compileOnly` in the library, and keep it in the fat JARs through the shadow tasks'
  configuration.
- Have `setLogLevel` check `ch.qos.logback.classic.Logger` with `Class.forName` before it touches `Level`. It already
  handles a binding that isn't Logback.
- At the least, have the starter exclude Logback:
  `api(project(":")) { exclude(group = "ch.qos.logback", module = "logback-classic") }`. Apps on Logback have it from
  `spring-boot-starter-logging` already.
- Say in the Logging section what a non-Logback host does.

**Tests:**

- A starter test that runs `ApplicationContextRunner` with `spring-boot-starter-log4j2` in place of Logback, which
  fails today.
- A consumer-check assertion that `logback-classic` isn't on the classpath of a project that depends only on the agent
  (or only on the starter).

**Resolution:** Fixed in #551. `logback-classic` is `compileOnly` in the agent, so the published POM no longer names it,
and on the main runtime classpath through `standaloneRuntimeOnly`, a dependency scope `runtimeClasspath` extends and
`runtimeElements` doesn't, so the fat JARs and the images still log with it. `Utils.setLogLevel` sets a level through
`LogbackLevels`, the only class that links against Logback, once `Class.forName` finds it, and warns otherwise; a
`UtilsTest` spec scans the main classes for any other class naming Logback. `make consumer-check` fails when Logback
resolves for the agent alone, a Ktor app with the plugin, or a Spring Boot app with the starter (a new
`spring-boot-starter` profile), where the review's Log4j2 starter test would have needed a classpath of its own. The
Embedded Agent page's Logging section and the upgrade notes say what an app with no SLF4J provider adds.

### 10. ✅ The Ktor plugin starts the agent before the engine binds its port, so an app whose port is taken hangs instead of exiting, with its agent connected

**Severity:** medium · **Confidence:** reproduced with a scratch spec (since deleted):

- What it did: it held port 27419, then called `embeddedServer(CIO, port = 27419) { … }.start(wait = false)` on a
  plain thread. Its module subscribed to `ApplicationStarted` and `ApplicationStopping` exactly as `AgentLifecycle`
  does.
- What happened: `start()` threw (cause `BindException: Address already in use`), and `ApplicationStopping` was never
  raised.
- One second later, the thread `Agent scratch-e` was alive, not a daemon, and retrying its proxy.

**Where:**

- `ktor-plugin/.../AgentLifecycle.kt:62` starts the agent on `ApplicationStarted`. It stops it only on
  `ApplicationStopPreparing` and `ApplicationStopping` (`:63-64`).
- Ktor 3.6.1, `EmbeddedServerJvm.kt`:
  - `start()` (`:389-416`) raises `ApplicationStarted` inside `createApplication()`
    (`instantiateAndConfigureApplication`, `:475`), and only then calls `engine.start(wait)`.
  - Only `createApplication()` is wrapped in the `try` that calls `destroyApplication()`. An engine that fails to bind
    throws out of `start()` with the application never disposed.
- `Agent.kt:984-986`: the agent's service thread isn't a daemon. An embedded agent registers no shutdown hook
  (`Agent.kt:864-866`).

**Problem:**

- **What happens:** a Ktor app with the plugin and a proxy, whose server port is in use at startup (a second copy, a
  stale process), has `main` die with the `BindException`. The JVM doesn't exit, since the agent's thread keeps it up.
  Ktor's own shutdown hook would stop the agent, but runs only once the JVM starts to exit, so on SIGTERM.
- **What the operator sees:**
  - A process that should have crashed and been restarted or alerted on stays up.
  - Its agent stays connected and serves the app's registry, with the app's meters at zero.
  - The app answers no request.
- **What's affected:** the demo's coffee shop (`demos/ktor/app/src/main/kotlin/coffeeshop/Main.kt:40`,
  `embeddedServer(CIO, port).start(wait = true)`), and any `EngineMain` app.
- **What isn't:** the Spring Boot starter avoids this, since the web server starts in an earlier phase than the
  agent's.

**Fix:** pick one.

- **Start on `ServerReady`:** start a non-required agent on `ServerReady`, which an engine raises once its connectors
  are bound. A required agent keeps `ApplicationStarted`, since `ServerReady` handlers' exceptions are caught and
  logged.
- **Daemon thread:** run an embedded agent's service thread as a daemon. Override Guava's `executor()` in `Agent` to
  create a daemon thread when `isEmbedded`, which is set before `startSync()`. A host that dies without stopping its
  agent then exits, which also covers #12. The trade-off is that a host whose `main` only starts an embedded agent and
  returns no longer stays up. That's what `startSyncAgent` is for.

**Tests:** a `ktor-plugin` spec (with `ktor-server-cio` as a test dependency) that:

- holds a port, starts `embeddedServer(CIO, port)` with the plugin and a proxy, and expects `start()` to throw;
- then asserts that no `Agent` thread is alive, or that it's a daemon.

It fails today.

**Resolution:** Fixed in #562. An embedded agent's service thread is now a daemon: `Agent` overrides Guava's `executor()` when `isEmbedded`, which `startEmbedded` sets before `startAsync()`, so a host that fails after starting its agent, without stopping it, exits. `ServerBindFailureTest` runs a CIO app with the plugin in a child JVM against a port the test holds: before, it was still running after 30 seconds, its agent retrying the proxy; now it exits with status 1 in about a second, its app disposed by Ktor's shutdown hook, which stops the agent through the plugin. `AgentStartupTest` pins the thread kind, and covers a `startUp()` that never returns, the part of #12 that `startWithin` couldn't stop. The admin and metrics servers' Jetty threads, which common-utils builds, still aren't daemons, so an agent with either port on still holds the JVM; the docs say so. A program whose `main()` only starts an embedded agent and returns now exits, which both changelogs and the upgrade page note.

### 11. ✅ Both integrations decide whether to start from their own proxy setting alone: a proxy from `PROXY_HOSTNAME`, a `setting()` key, or (in the starter) a customizer leaves the agent off

**Severity:** low · **Confidence:** confirmed by trace

**Where:**

- `spring-boot-starter/.../PrometheusProxyAgent.java:66-69`: `start()` returns, or throws for a required agent, when
  `prometheus-proxy.agent.proxy` is blank. It does so before `config()` runs the customizers. The comment at `:100-101`
  says a customizer can *override* the property's proxy, but it can't supply one.
- `ktor-plugin/.../AgentLifecycle.kt:85-90`: checks `builder.proxy` after the blocks run. That reads only the builder's
  `proxy.endpoints` value, not `settings.proxy.hostname` / `setting("proxy.endpoints", …)`, nor `PROXY_HOSTNAME`.
- `agent/EmbeddedAgentConfig.kt:159-169`: `namesProxy` counts all three. `startIfConfigured`, which both integrations
  call for a non-required agent, honors them (its KDoc, `:114-117`).
- Docs:
  - `embedded-agent.md:290` maps the starter property and plugin key `proxy` to `PROXY_HOSTNAME`.
  - `spring-boot-starter.md:62-64` and `ktor-plugin.md:62-63` say the agent's own variables, `PROXY_HOSTNAME` among
    them, take precedence over its settings.
  - The starter's property table (`spring-boot-starter.md:72`) does say "Unset, the agent stays off". So the starter's
    behavior is documented, but conflicts with the rest of these docs.

**Problem:**

- **Starter, customizer:** a customizer that calls `builder.fromEnvironment()` (the Ktor plugin page's own pattern), or
  sets the proxy from a secret store, starts nothing when the property is unset. The log says
  "prometheus-proxy.agent.proxy isn't set".
- **Both, `PROXY_HOSTNAME`:** an operator who sets only `PROXY_HOSTNAME`, as for every other embedded agent, gets an
  agent that stays off. A required one fails the app, though the agent would have used that proxy.

The two integrations also disagree with each other: the plugin honors a proxy set in code, and the starter doesn't.

**Fix:**

- Decide after the customizers and blocks have run, with one rule.
- Give `EmbeddedAgentConfig` a public `namesProxy` (or `hasProxy`) with `namesProxy`'s logic.
- Call it from both integrations after `build()`: a non-required agent can just call `startIfConfigured()`, which
  already checks.

**Tests:**

- A starter test with no `proxy` property and a customizer that calls `proxy(...)`, which expects a running agent.
- An `AgentLifecycle` test with `settings.proxy.hostname` and no `proxy` key.
- The internal `env` lookup lets both cover `PROXY_HOSTNAME`.

**Resolution:** Fixed in #562. `EmbeddedAgentConfig.namesProxy()` is public: the rule `startIfConfigured()` already followed (the builder's proxy, a `setting()` of the endpoints or host name, or `PROXY_HOSTNAME`). The starter runs its customizers before deciding, and the plugin decides once the file and the install block have built the settings; both read `namesProxy()`. New tests in `PrometheusProxyAgentAutoConfigurationTest` (a customizer's proxy, a `settings.proxy.hostname` property, and a required agent whose customizer sets the proxy) and in `AgentLifecycleTest` (a file `settings` key on a required agent, and an agent block's `setting()`) failed before the change; `PROXY_HOSTNAME` itself is covered by `StartIfConfiguredTest`, through the internal `env` lookup. Both integrations now log `No proxy is set (prometheus-proxy.agent.proxy or PROXY_HOSTNAME)`.

### 12. ✅ A start that times out is reported as "stays off", but the agent keeps starting, with no handle to stop it

**Severity:** low · **Confidence:** plausible: the gap is confirmed in the code. It needs `startUp()` to take over
30 s, which is rare (binding the admin, metrics, and dashboard ports, and starting Zipkin's reporter).

**Where:**

- `Agent.kt:1112-1114`: `agent.startSync()` is common-utils 5.3.0's `startAsync(); awaitRunning(30 s)`.
  `awaitRunning` throws `TimeoutException` and leaves the service STARTING, and no `EmbeddedAgentInfo` is returned.
- The callers treat the timeout as a failure:
  - `agent/EmbeddedAgentConfig.kt:147-156`: `startIfConfigured` logs "could not start, so it stays off" and returns
    null.
  - `spring-boot-starter/.../PrometheusProxyAgent.java:76-78`, and `AgentLifecycle.start()`'s comment, name "the start
    timing out" as such a failure.

**Problem:** the agent reaches RUNNING moments later and connects. It serves its paths with no handle, so nothing can
stop it: no `shutdown()`, no Spring `stop()`, no Ktor `finishStop()`, and no shutdown hook, since it's embedded. Its
non-daemon thread then keeps the JVM up after the host stops. A required agent's timeout fails the Spring app's start
while the agent keeps the process alive.

**Fix:** in `startEmbedded`, catch the `TimeoutException`. Call `agent.stopAsync()`: Guava stops a STARTING service
once `startUp()` returns, and runs `shutDown()`. Then throw an `IllegalStateException` that names the timeout.

**Tests:** an `EmbeddedAgentApiTest` case with an agent whose `startUp()` blocks past a shortened start timeout. For
that, make the timeout a parameter of `startEmbedded`. The case expects the exception, and the agent TERMINATED
shortly after.

**Resolution:** Fixed in #557. `startEmbedded` starts the agent with `Agent.startWithin`, at `startSync()`'s 30 seconds: on a timeout it calls `stopAsync()`, so Guava skips `run()` once `startUp()` returns and calls `shutDown()`, and the agent releases what it opened and never connects; the start throws an `IllegalStateException` with the `TimeoutException` as its cause. `AgentStartupTest` blocks the dashboard's start on a latch and checks the agent reaches TERMINATED, its port free and its channel shut, without connecting. Step 5's daemon thread would cover a `startUp()` that never returns, which this doesn't.

---

## 🖥️ Dashboards

### 13. ✅ An anonymous `POST /logout` wakes the push loop, so anyone who can reach a login-protected dashboard can make it collect a snapshot and push every open page once per request

**Severity:** medium · **Confidence:** reproduced with a scratch spec (since deleted): a `DashboardServer` with a login,
a 2 s refresh, a site that counted its collects, and one logged-in WebSocket reader. Idle for 4 s it collected 2
snapshots and pushed 2 frames; during 4 s of `POST /dashboard/logout` with no cookie and no Origin, from one
`HttpClient` loop, it answered 16,862 requests (all 303) and collected 16,862 snapshots and pushed 16,861 frames.

**Where:** `common/dashboard/DashboardServer.kt:527-536`, the logout route, calls `wake.trySend(Unit)` on every POST,
whether or not the request named a session (`logins.remove` at `:530` returns nothing, and `DashboardSessions.remove`,
`common/dashboard/DashboardSessions.kt:94-97`, does nothing for an unknown or missing token). The auth check lets the
route through without a login (`needsNoLogin`, `:336-341`), and `originGuard` lets a request with no Origin through
(`isOriginAllowed`, `:860`). Each wake runs the push loop (`:625-641`), which calls `snapshots.fresh()` (`:630`), a
collect that bypasses `SnapshotCache`, then renders and sends a frame for every session. The site's own changes are
spaced to one wake per 250 ms (`start()`, `:606-615`); this wake isn't.

**Problem:**

- **Attacker:** anyone who can reach the dashboard port. No password, cookie, or Origin is needed.
- **Trigger:** a login is set (the logout route exists only then), at least one page is open (`:628` skips the work
  with no sessions), and the attacker loops `curl -X POST http://host:port/dashboard/logout`.
- **Impact:** one collect per request. The proxy's collect (`proxy/dashboard/ProxySnapshot.kt:334-394`) copies the
  path map under the `pathMap` monitor that every scrape and registration takes
  (`proxy/ProxyPathManager.kt:159`), then takes the path history's lock, which every completed scrape takes, and the
  activity log's. Each wake then renders the whole page region for every open session, which for the Paths view is
  every row. So the request rate the attacker picks becomes a lock-hold rate on the scrape path and a render rate per
  viewer: November #18 again, for the one unauthenticated route that reaches `fresh()`, on the dashboard whose login
  was meant to keep unauthenticated peers out. With frames of realistic size, the viewers' browsers also can't drain
  thousands of frames a second, so their outgoing buffers overflow and their pages drop and reconnect for as long as
  the flood lasts (plausible, not reproduced).

**Fix:** wake only for a logout that ended a session: have `DashboardSessions.remove` return whether it removed one,
and call `wake.trySend` only then. A logout flood without a valid token then costs a hash and a map lookup. Better
still, end that login's open sockets directly from the logout handler (the sessions whose `login` is the token), which
needs no collect at all, and leave the push loop to the site's changes and the tick.

**Tests:** in a `DashboardServer` spec with a counting site, a login, and one open session: 100 `POST /logout`
without a cookie add no collects beyond the tick's; one with the open session's cookie closes that session's socket.

**Resolution:** Fixed in #558, by the better-still option. `DashboardSessions.remove` returns whether it ended a session, and Log out, through `endLogin`, closes only that login's open pages and never wakes the loop, so one naming no session costs a lookup. `DashboardPushTest` sends 100 such logouts and sees no collect and no push; the old code collected 102 times.

### 14. ✅ Ending a session whose socket has stopped reading blocks the shared push loop until that TCP connection dies, so every open page freezes while still showing "live"

**Severity:** medium · **Confidence:** reproduced with a scratch spec (since deleted): a `DashboardServer` with a login
and a 200 ms refresh, one logged-in WebSocket client that read, and one raw socket that completed the handshake with
its own login cookie and a 1 KiB receive buffer and then never read. Two seconds after the second session's
`POST /logout`, the reading client got 1 frame in the next 4 s and 0 in the 4 s after, against about 5 a second
before, and got frames again as soon as the raw socket closed. In the control, where the second client read, the
first kept about 5 frames a second through the logout.

**Where:** `common/dashboard/DashboardServer.kt:635-636`: the push loop calls `it.end()` for a session whose login
ended, and `end` is `close(CloseReason(...))` (`:578`). Ktor's `WebSocketSession.close` (ktor-websockets 3.6.1,
`WebSocketSession.kt:147-153`) is `send(Frame.Close(reason))` then `flush()`, and `flush()` reaches
`WebSocketWriter.flush` (`WebSocketWriter.kt:178-188`), which waits for a `FlushRequest` that the write loop completes
only after writing every frame queued ahead of it, or, once the queue is closed, joins the write loop. A peer that
doesn't read leaves the write loop parked in `writeFully` on a full TCP window, so both wait as long as the connection
stays open. Nothing else in the loop waits on a client: `push`'s send goes into a `BoundedChannel` with
`DROP_OLDEST`, which never suspends.

**Problem:** once the loop is parked there, no session gets a push: no tick, no change, no logout's closing of other
pages. Each page keeps its socket, so its indicator stays on **live** while its data freezes, the silent staleness
the page's script calls "the one failure this dashboard exists to make impossible to miss". It lasts until the stalled
connection fails: about 15 minutes of retransmits for a peer that vanished, and indefinitely for one that keeps the
connection open with a zero window, which is what a client that has stopped reading does. Its own pinger can't end
it: the processor's close sequence stops the pinger (`runOrCancelPinger` with `closed` set closes its channel).

The trigger is a session whose socket isn't being read, in the seconds between its writer blocking and its being
closed (#15), and whose login ends then: Log out in that browser, or its 24 h idle or 7 day limit expiring then. A
logged-in user can do it on purpose (a socket with a small receive buffer that stops reading, then a logout with its
cookie) and freeze every other viewer's dashboard until the proxy or agent restarts. By accident it takes a rare
coincidence.

**Fix:** never wait on a client's close from the push loop. Take the session out of `sessions` first, so it's ended
once, then close it on its own scope with a bound, for example
`end = { launch { withTimeoutOrNull(CLOSE_TIMEOUT) { close(reason) } ?: cancel() } }` (the
`DefaultWebSocketServerSession` is a `CoroutineScope`), or launch `end()` from `scope` with the same timeout.

**Tests:** a `DashboardServer` spec with a login: a reading WebSocket client and a raw socket that completes the
handshake and never reads; `POST /logout` with the raw socket's cookie; the reader keeps receiving frames at the tick
rate.

**Resolution:** Fixed in #558. Every close goes through `end()`, which takes the session out of the pushes at once and closes its socket on a coroutine of its own: a close frame, waited for at most 5 seconds, then the socket's handler cancelled. `DashboardPushTest` ends the login of a raw socket that never reads a 16 MiB frame, by Log out and by expiry, and another page keeps getting frames; both froze on the old code.

### 15. ✅ A session that stops reading isn't reliably closed: once its outgoing buffer overflows, Ktor's pinger exits silently, and the session stays open, counted against `maxSessions` and rendered for on every push

**Severity:** low · **Confidence:** reproduced with a scratch spec (since deleted): with a 200 ms refresh and 256 KiB
frames, a raw socket that completed the handshake and then never read was still among `sessionsOpen` at 10, 20, 30,
40, and 50 s, though Ktor's 15 s ping and 15 s pong timeout would have closed it by 30 s; it left only when the socket
closed. Plausible with the default 2 s refresh on a busy proxy, whose change bursts push up to 4 frames a second, and
whose frames are large.

**Where:** `common/dashboard/DashboardServer.kt:470-479` bounds both the session's and the raw writer's queues at 32
with `ChannelOverflow.CLOSE` (Ktor's `BoundedChannel`, `WebSocketChannelsConfig.kt:77-105`: `DROP_OLDEST`, closing
the channel on the dropped element; `RawWebSocketJvm.kt:99` gives the writer the same config). On overflow the outgoing
processor ends and cancels the session's channel (`DefaultWebSocketSession.kt:322-333`), and its `raw.close()` sends
nothing, the queue being closed. The pinger's next `outgoing.send(Frame.Ping(...))` (`PingPong.kt:79`) then throws
`ClosedSendChannelException`, which it catches and exits on (`PingPong.kt:105`) without calling `onTimeout`. The
pinger closes the session only if a ping already queued times out, so whether the session is closed depends on where
in its 30 s cycle the overflow lands. `push` only logs a failed send at DEBUG (`:660-665`), and the handler waits on
`incoming` (`:585-590`), which a silent client never feeds.

**Problem:** the session stays in `sessions` and holds one of `maxSessions` slots (default 50) for as long as the TCP
connection lives, and every push renders a frame for it that it can't send. That contradicts the class KDoc ("a
session that stops reading is closed rather than buffered for", `:183-184`), the install comment ("a full buffer
closes that session", `:472-473`), and `website/prometheus-proxy/docs/web-dashboard.md:543-544` ("a client that stops
reading ... is disconnected").

**Fix:** when `session.send` fails for any reason other than the loop's own cancellation, end the session at once:
remove it from `sessions` and cancel its socket's scope, rather than relying on the pinger. Skip rendering for a
session whose last send failed.

**Tests:** a raw socket that never reads, with a short refresh and large frames: `sessionsOpen` drops back within a
few seconds of its first failed send.

**Resolution:** Fixed in #558. A failed send ends its session at once, freeing its `maxSessions` slot, and an ended session isn't rendered for again; `DashboardPushTest` sees a stalled page leave within about 2 seconds and a new one connect. One correction: once the buffer overflows, Ktor's pinger fails with an uncaught `ChannelOverflowException`, logged at ERROR, rather than stopping silently; the session stayed open all the same.

### 16. ✅ The tables' search doesn't match a path as its row shows it: typing `/payments` finds nothing

**Severity:** low · **Confidence:** confirmed by trace.

**Where:** `common/dashboard/RowFilter.kt:51-55` matches the query against each row's fields with `contains`, and its
KDoc (`:37`) says the query is "matched case-insensitively against what a row shows". The fields are the path key
without its slash (`proxy/dashboard/ProxyRowFilter.kt:36-40`, `agent/dashboard/AgentActivityHtml.kt:47-48`), while
every row shows it with one (`proxy/dashboard/PathTableHtml.kt:247`, `common/dashboard/ActivityTable.kt:82`). Agent
names are matched raw (`servingAgent`, `agentNames`, `entry.agentName`), while the rows show them through `agentLabel`
(`PathTableHtml.kt:261`, `ActivityHtml.kt:152`), which turns `Unassigned` into `(registering…)`.

**Problem:** a search for a path as the table, the copied proxy URL, or Prometheus writes it, `/payments_metrics`,
returns "No paths match the filter" on the proxy's Paths and Activity views and the agent's Activity view, unless the
target URL happens to contain the same text. `RowFilterTest` only searches without the slash.

**Fix:** match the fields as the row shows them: paths as `"/$path"` (or drop one leading `/` from the query when
comparing with a path), and agent names through `agentLabel`.

**Tests:** `RowFilterTest`: `/payments_metrics` matches the `payments_metrics` path; `ActivityHtmlTest` and
`AgentDashboardHtmlTest`: `/foo` matches an entry naming path `foo`; `registering` matches a path served by an
`Unassigned` agent.

**Resolution:** Fixed in #564. The search now matches a row as it shows it. `RowFilter.pathAsShown` gives a path its slash, and the Paths and Activity rows render the path with it too, so the two can't drift apart. The proxy's matchers name each agent through `agentLabel`, so `registering` finds an agent not yet registered and `Unassigned` no longer does. `/payments_metrics` now finds its path on the proxy's Paths and Activity views and on the agent's Activity view. `RowFilterTest` covers a path with its slash, an agent by its label, and an activity line's paths and agent; `ActivityHtmlTest` and `AgentDashboardHtmlTest` cover the rendered views. `dashboard-constraints.md` gains the rule that the search matches a row as the row shows it.

### 17. ✅ A collect that throws ends the push loop for good, with nothing logged through the logger

**Severity:** low · **Confidence:** confirmed by trace; no collect was found that throws today.

**Where:** `common/dashboard/DashboardServer.kt:603-605` launches `pushLoop()` on a `SupervisorJob` scope with no
`CoroutineExceptionHandler` (`:242`); `pushLoop` (`:625-641`) calls `snapshots.fresh()` with no catch.

**Problem:** an exception from `site.collect()`, however transient, ends the coroutine: the default handler prints it
to stderr, outside logback, and no page gets another tick or change push until the process restarts, while every page
keeps showing **live**. `push` already guards renders for this reason (`:648-656`: "a render that throws ... froze
every session on that page with nothing logged above DEBUG"); the collect it renders from has no such guard.

**Fix:** catch per iteration (rethrowing `CancellationException`), log the first failure at ERROR, and carry on at the
next wake or tick; give `scope` a `CoroutineExceptionHandler` that logs through the logger.

**Tests:** a site whose first collect throws: an open session still gets a frame on the next tick, and one ERROR is
logged.

**Resolution:** Fixed in #558. `collectForPush` logs a run of failed collects at ERROR once, then DEBUG, and the loop carries on at its next wake or tick; the server's scope logs its tasks' failures through the logger. `DashboardPushTest` sees one ERROR for two failures and a push on the next change.

### 18. ✅ The detail pane of an agent that has gone names it by its raw numeric `agentId`, which no other view shows

**Severity:** low · **Confidence:** confirmed by trace.

**Where:** `proxy/dashboard/ProxyDashboardHtml.kt:296-300`: `"Agent $selectedId is no longer connected"`. Agent ids
are a counter (`proxy/AgentContext.kt:75`), shown nowhere on the page but the `data-agent` attribute.

**Problem:** `.claude/rules/dashboard-constraints.md` requires every layout to render agent identity through
`agentLabel()`, "a divergent label (or a raw `agentId`) defeats the correlation they exist for". An operator whose
agent went away reads "Agent 17 is no longer connected" and can't tell which agent that was from the agent list, the
Paths view, or the Activity view, which all name it.

**Fix:** name it from what the snapshot still holds, through `agentLabel`: the newest `ScrapeRecord` with that
`agentId` in `snapshot.paths`' histories carries its `agentName`. Without one, say "The selected agent is no longer
connected", with no id.

**Tests:** `ProxyDashboardHtmlTest`: `detailFragment` for an id no longer connected doesn't contain the id, and names
the agent when a path's history holds a scrape of it.

**Resolution:** Fixed in #564. The gone pane names the agent through `agentLabel`, from the newest activity line or scrape the snapshot still holds of it (`ProxySnapshot.goneAgentName`), worked out once per snapshot and only once a session's selected agent has gone. When the snapshot holds neither, the pane says "The selected agent is no longer connected", and the id no longer appears. Unlike the fix this finding suggested, it reads the activity log as well as the paths' histories, so an agent that left before its first scrape is still named, usually from the line recording its departure. `ProxyDashboardHtmlTest` covers the name from a scrape, from an activity line, and from neither, never the id; `ProxySnapshotTest` covers the newest line or scrape winning, and no name for a connected agent or for a line that names no agent.

### 19. ✅ An agent's detail shows a stale-agent eviction countdown when the stale-agent check is off

**Severity:** low · **Confidence:** confirmed by trace.

**Where:** `proxy/dashboard/ProxyDashboardHtml.kt:324` always renders `"evicted in ${agent.evictionCountdownSecs(...)}s"`;
`ProxySnapshot` carries `maxAgentInactivitySecs` (`proxy/dashboard/ProxySnapshot.kt:383`) but not whether the check
runs (`Proxy.isStaleAgentCheckRunning`, `Proxy.kt:264`, false with `proxy.internal.staleAgentCheckEnabled = false`
and the transport filter on).

**Problem:** with the check off, no agent is ever evicted, but every agent's detail counts down to an eviction, and an
agent idle past `maxAgentInactivitySecs` (heartbeats off) reads "evicted in 0s" indefinitely.

**Fix:** carry the check's state in the snapshot and leave the countdown out, or say it's off, when it doesn't run.

**Tests:** `ProxyDashboardHtmlTest`: an agent's detail from a snapshot with the check off has no "evicted in".

**Resolution:** Fixed in #564. `ProxySnapshot.maxAgentInactivitySecs` became `evictAfterSecs`, which is null while `Proxy.isStaleAgentCheckRunning` is false, so a snapshot can't carry a delay that no check acts on, and the detail shows the countdown only when it's set. It leaves the countdown out rather than saying the check is off, which the Settings view already says. A disabled transport filter forces the check on, and the countdown then shows, correctly. `ProxyDashboardHtmlTest` covers 58s, 0s when overdue, and no countdown with the check off; `ProxySnapshotTest` covers `collect` from a proxy with the check on and with `staleAgentCheckEnabled = false`.

---

## 🔒 Security

### 20. ✅ One agent's labels can exhaust the proxy's heap within the default limits, and `maxPathsPerAgent` doesn't bound an identity

**Severity:** medium · **Confidence:** reproduced: a standalone JVM program that builds what `addPath` keeps per path
(the raw labels string plus `Proxy.serviceDiscoveryLabels`' filtered `JsonObject`, with kotlinx-serialization 1.13.0)
retains **130 KiB per path** for an 8,185-byte labels value of one-character labels (`{"a0":"x","a1":"x",…}`), and 18.5
KiB per path for a few 500-character values. The rest is confirmed by trace.

**Where:**

- `proxy/ProxyPathManager.kt:58-73` and `:166-197`: every path's entry keeps `labels`, the string as the agent sent it,
  and `sdLabels`, the parsed copy, side by side.
- `Proxy.kt:766-777`: `serviceDiscoveryLabels` keeps every entry with a valid name and a string value, with no limit on
  how many. A tiny label costs a `LinkedHashMap` node, a key `String`, a `JsonLiteral`, and a content `String`, about
  16× the bytes it took in the request.
- `config/config.conf:116-118`: the only bounds are 8,192 bytes of labels per path and 20,000 paths per agent
  connection. That is 160 MiB of raw labels per connection, before parsing.
- `proxy/ProxyPathManager.kt:330-345`: `pathLimitRejection` counts by `agentId`, one connection. Its comment says the
  limit stops "an identity limited to team-a-*" from registering unlimited paths, and
  `docs/security-agent-authentication.md:213-216` says the same. With the transport filter on, nothing limits how many
  connections one identity opens (`maxAgentsPerIdentity` applies only with the filter disabled), and each gets a fresh
  20,000.
- What the stored labels are then multiplied by:
  - `Proxy.kt:686-712` and `proxy/ProxyHttpRoutes.kt:86-96`: each service-discovery `GET`, on the unauthenticated scrape
    port, builds the whole document as a tree and then as one pretty-printed string;
  - `proxy/ProxyPathManager.kt:99-101` and `:780-792`: `/debug`'s `toPlainText` prints both `labels` and `sdLabels` of
    every path inside the `pathMap` lock;
  - `proxy/dashboard/ProxySnapshot.kt:101-107`: with a `{label.<name>}` dashboard link configured, every collect
    re-parses every path's raw labels, the pattern November #15 removed from service discovery.

**Problem:** November #12 bounded a path's target URL and source because about 100 registrations of 4 MiB values
exhausted the heap. Labels were already bounded per path, but the bound times the path quota is still far more than the
heap.

- **Attacker:** an authenticated agent whose identity has a wildcard glob (the chart's example `team_a_*`), or any peer
  when agent auth is off, the default.
- **Trigger:** about 3,000 `registerPath` calls on one connection, each with a distinct matching path and 8 KiB of
  one-character labels. That is 15% of one connection's quota, so no limit refuses them. Values of normal size need the
  whole quota (20,000 × 18.5 KiB ≈ 370 MiB), or a second connection.
- **Impact:** the proxy chart's heap is about 384 MiB (75% of its 512 Mi limit), so the proxy runs out of memory and
  every tenant's scrapes fail. The attacker repeats it after each restart. Below that threshold, each service-discovery
  request (from Prometheus or anyone who can reach the scrape port) allocates the whole document two or three times
  over, so a few concurrent requests finish the job. Even with no labels at all, connections × 20,000 paths × (a
  512-character path, a 2,048-character URL, per-path metrics, and entry overhead) has no per-identity bound.

**Fix:**

- Keep one representation: store only the filtered `sdLabels` (it is what service discovery and the links read), and
  render the dashboard's and `/debug`'s labels from it. Reuse it in `ProxySnapshot.labelValues` in place of re-parsing.
- Add `proxy.internal.maxLabelsPerPath` (for example 64; 0 for unlimited), refused with `INVALID_PATH` in
  `sizeRejection` (and checked in the agent's `pathConfigs` validation, as the size limit is). Consider lowering
  `maxLabelsSizeBytes` to 2 KiB.
- Bound paths per identity as well as per connection: a `proxy.internal.maxPathsPerIdentity`, counted under the
  `pathMap` lock next to `pathCounts`, with the empty and legacy identities either exempt or documented as sharing
  one budget, as `maxAgentsPerIdentity` does. Or a global `maxPaths`.
- Correct the claim in `docs/security-agent-authentication.md` and the `pathLimitRejection` comment, and add a "Before you
  upgrade" note for the new limits.

**Tests:**

- `ProxyPathManagerTest`: a path whose labels hold more than `maxLabelsPerPath` entries is refused with `INVALID_PATH`;
  an accepted path's entry holds one copy of its labels.
- `ProxyPathManagerTest`: two agent contexts of one identity together can't register more than `maxPathsPerIdentity`
  paths; the empty identity follows whichever rule the fix picks.
- `ProxyOptions` validation of the new limits.

**Follows up:** late-September #14 (per-connection quota, chosen so the legacy token's shared identity wouldn't cap the
fleet) and November #12 and #15 (the same attacker and amplifiers).

**Resolution:** Fixed in #560. A path's labels are parsed once, as it registers (`Proxy.pathLabels`), and its entry keeps only the parsed copy, which service discovery, the dashboard's links, and `/debug` read, and which the dashboard no longer re-parses on each collect: 8 KiB of one-character labels took 134.5 KiB, and with the new `proxy.internal.maxLabelsPerPath` (default 64) a path takes at most about 20 KiB. The new `proxy.internal.maxPathsPerIdentity` (default 100,000) caps what an identity's agents hold together; with auth off it counts the whole fleet, a takeover within an identity leaves its count alone, and it warns at 80%. The release notes gain a Before You Upgrade section for both. Differences from the fix above: the entry keeps every label with a value, since the dashboard's links read labels service discovery leaves out; the agent checks neither limit, both being the proxy's; `maxLabelsSizeBytes` stays at 8 KiB; and `ProxyRegistry.tla` doesn't count paths per identity, since the count changes under the same lock as `pathCounts` and drives no lifecycle decision. One connection at its 20,000-path quota with every path at its limits still holds about 400 MiB, which only a lower `maxPathsPerAgent` would close.

### 21. ✅ The proxy's gRPC tracing keeps every call whose metadata says "sampled", so any caller can override the sample rate and trace heartbeats

**Severity:** low (Zipkin is off by default) · **Confidence:** confirmed in Brave 6.3.1's source.

**Where:**

- `common/ScrapeTracing.kt:41-43` and `:68-77`: the server side's `rpcSampler` never samples `sendHeartBeat` or
  `readPathNoticesFromProxy` and samples the rest at `sampleRate`.
- `proxy/ProxyGrpcService.kt:117-120`: the proxy installs `grpcTracing.newServerInterceptor()` on every agent call.
- Brave `RpcServerHandler.nextSpan` (`brave/rpc/RpcServerHandler.java:82-91`): it extracts B3 from the call's metadata,
  and consults the sampler only when the extracted context has no decision (`sampled == null`). A `b3: 1`,
  `x-b3-sampled: 1`, or `x-b3-flags: 1` (debug) header is kept as is.
- The docs say otherwise: `website/prometheus-proxy/docs/advanced.md:318-327` says `sendHeartBeat` "is never traced" and
  "each side's rate applies to its gRPC calls too". `proxy/ProxyHttpRoutes.kt:220-222` and `advanced.md:313-314` refuse
  to join a scrape request's trace headers for exactly this reason ("a client that marked its trace sampled could make
  the proxy keep every one"), but the agent port joins them.

**Problem:**

- **Attacker:** an authenticated agent, or any peer that can reach the agent port when agent auth is off. The
  server-wide auth interceptor runs before the tracing one, so a refused token gets no span.
- **Trigger:** send every call, heartbeats included, with `b3: 1` (or debug flags) in its metadata, at any rate.
- **Impact:** the proxy reports a span for each call whatever `sampleRate` says, `0` included. That lifts the proxy's
  Zipkin traffic and the collector's storage to the attacker's call rate. A caller can also attach the proxy's spans to
  trace IDs of its choosing, mixing them into other services' traces in a shared Zipkin. Brave's async reporter drops
  spans past its queue, so the proxy's own heap is safe.

**Fix:** keep joining the honest agent's trace IDs, but make the decision on the proxy. Wrap the server's extractor (a
`Propagation.Factory` decorating B3 for the proxy's `Tracing`, or a `RpcTracing` built with a custom server request
parser around the extractor) so that an extracted context's `sampled` and `debug` are cleared, and `RpcServerHandler`
falls through to `rpcSampler`. A stock agent's heartbeat then stays unsampled because of the proxy's rule, not because
the agent asked.

**Tests:** in `ScrapeTracingTest` (with `TracingMocks`), a `sendHeartBeat` server call whose metadata carries
`b3: 1` produces no reported span, and with `sampleRate = 0.0` a `registerPath` call carrying `x-b3-sampled: 1` produces
none either.

**Resolution:** Fixed in #565. `ScrapeTracing`'s gRPC tracing uses a propagation, `ServerSampledPropagation`, whose extractor clears the sampled and debug flags of whatever it extracts (`ScrapeTracing.undecided`) and keeps the trace and span IDs, so Brave's server handler always asks the proxy's `rpcSampler`, and a call still joins its caller's trace. The client side's injection is unchanged. New `ScrapeTracingTest` cases make real calls through an in-process server behind the interceptor: a heartbeat sent with `b3: 1`, `b3: d`, `x-b3-sampled: 1`, or `x-b3-flags: 1` is served unsampled; at rate 0 a `registerPath` with `x-b3-sampled: 1` is unsampled and keeps the caller's trace ID; and at rate 1 one sent with `x-b3-sampled: 0` is sampled. The first two failed before the fix. The website's Sampling paragraph says whose rate applies to which side of a call.

### 22. ✅ The dashboard logs a client's `Origin` and `Host` headers whole, unescaped, and unthrottled, before any login

**Severity:** low · **Confidence:** confirmed by trace, with CIO's header parser read in `ktor-http-cio` 3.6.1.

**Where:**

- `common/dashboard/DashboardServer.kt:290-298`: `originRefusal` logs `Refused a dashboard request from origin $origin`
  at INFO for every refused request.
- `common/dashboard/DashboardServer.kt:442-450`: `checkHost` logs `Refused a dashboard request for host $host` at INFO,
  application-wide and before the login check, whenever `allowedHosts` is set.
- `:516-536`: the login and logout routes carry `originGuard` and need no session, so the Origin line can be reached with
  a password set as well as without one.
- `ktor-http-cio` `HttpParser.kt:277-302`: a header value refuses only CR and LF and keeps every other control character;
  `:155-162`, a `Host` value refuses only `/ ? # @`.

**Problem:**

- **Attacker:** anyone who can reach either dashboard's port. No login is needed.
- **Trigger:** `POST /dashboard/login` (or `GET`) with an `Origin` of up to about 8 KiB of terminal escape sequences
  and other control characters, in a loop. With `allowedHosts` set, any request with such a `Host`.
- **Impact:** one INFO line per request, each up to a request line's worth of client text with its escapes intact.
  So the client can write terminal escape sequences into the proxy's or agent's log, and fill it at its own request rate.
  November #40 closed the same gap on the scrape port, and `recordBadLogin` throttles its own line for the same reason
  ("so a client guessing in a loop can't flood the log"), but these two lines got neither treatment.

**Fix:** log `AgentValues.shownValue(origin)` and `shownValue(host)`, and throttle each line per remote address with a
`KeyedThrottle`, as `badLoginWarnings` does, at DEBUG past the first.

**Tests:** in `DashboardServerTest`, with a `ListAppender`, two refused login posts from one address with an `Origin`
holding `ESC[2J` and 10,000 more characters log one INFO message, with the escape written out and under 200
characters; the same for a refused `Host` with `allowedHosts` set.

**Follows up:** November #40 and #16 (the same rule for client- and agent-supplied text).

**Resolution:** Fixed in #563. `originRefusal` and `checkHost` now log the header through `shownValue`, cut to 270 characters rather than its default 64, so a real origin or host (a 253-character DNS name with its scheme and port) is still shown whole and still names what to allow. Each line has its own `KeyedThrottle` per remote address, as `recordBadLogin` does: INFO once a minute and DEBUG otherwise. The message wording is otherwise unchanged, so the docs' quotes of it still match. `DashboardResponsesTest` sends an `Origin`, then a `Host`, of `ESC[2J` and 5,000 more characters twice from one address over a raw socket (Ktor's client refuses a control character in a header), and sees one INFO and one DEBUG line, each with the escape written out and under 400 characters; the old code logged two INFO lines with the raw escape.

### 23. ✅ Pages behind the dashboard's login can be framed and are cacheable; only the login page refuses both

**Severity:** low · **Confidence:** confirmed by trace.

**Where:**

- `common/dashboard/DashboardServer.kt:371-381`: `respondLogin` sends `Content-Security-Policy: frame-ancestors 'none'`
  and `Cache-Control: no-store`, and its comment says the login page is "never cached or framed".
- `proxy/dashboard/ProxyDashboardService.kt:185-245` and `agent/dashboard/AgentDashboardService.kt:138-182`: the layout
  pages, the detail fragments, and the Scrape now result send neither header. `ProxyHttpConfig.configureKtorServer`
  (`proxy/ProxyHttpConfig.kt:59-62`) adds only `X-Engine`.
- `website/prometheus-proxy/docs/web-dashboard.md:517-529`: the Exposure section lists two ways a web page can use the
  dashboard through a viewer's browser (a foreign WebSocket, DNS rebinding); framing isn't among them.

**Problem:**

- **Attacker:** a web page that a viewer with access to the dashboard opens: any site when the dashboard has no
  password (the default), or a same-site origin (another port on the dashboard's host, or a sibling subdomain) when it
  has one, since the `SameSite=Lax` cookie still goes to a same-site frame.
- **Trigger:** frame the dashboard (its own WebSocket and actions pass the Origin check, since the framed page is the
  dashboard's own origin) and overlay it to steer the viewer's clicks.
- **Impact:** small. The viewer can be made to click Scrape now (within its limits) or Log out. The framing page can't
  read the frame. Separately, the pages behind the login may stay in the browser's back-forward cache after Log out.

**Fix:** send `Content-Security-Policy: frame-ancestors 'none'` (and `X-Frame-Options: DENY` for older browsers) on every
dashboard response, from an application plugin in `DashboardServer.buildServer`, and `Cache-Control: no-store` on every
HTML response once a login is set. Name framing in the Exposure section.

**Tests:** in `ProxyWebDashboardTest` and `AgentWebDashboardTest`, a page, a detail fragment, and a Scrape now response
each carry `frame-ancestors 'none'`; with a login set, a page carries `no-store`.

**Resolution:** Fixed in #563. A `DashboardServer` application plugin, an `onCallRespond` hook like `DefaultHeaders`', adds `Content-Security-Policy: frame-ancestors 'none'` and `X-Frame-Options: DENY` to every response, refusals, redirects, error pages, assets, and the WebSocket handshake included. It adds each header once, since StatusPages answers a bare 404 twice, and a brand mark keeps its `sandbox` policy alongside. With a login set, `CachingHeaders` gives every response but the versioned assets `Cache-Control: no-store`, not only HTML responses, so fragments, redirects, and 401s are covered too; `respondLogin` no longer sets either header itself. The Exposure section names framing as a third guard. `DashboardResponsesTest` checks ten kinds of response without a login and seven with one: before the fix all ten lacked the framing headers and six of the seven lacked `no-store`. The proxy and agent harness specs check a page, a fragment, Scrape now, and the login page.

### 24. ✅ The Spring Boot starter's `Target.toString` masks a URL's user info but not its query values, so a logged property set shows an `api_key`

**Severity:** low · **Confidence:** confirmed by trace.

**Where:** `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgentProperties.java:393-398`:
`url.replaceFirst("://[^/@]*@", "://***@")`, commented "A URL's own credentials masked, as the agent's logs mask them".
The class KDoc (`:25-28`) says `toString` exists "so the settings can be logged". The agent's own `Utils.sanitizeUrl`
(`common/Utils.kt:62-70`) masks query values too, and so does `EmbeddedAgentConfig.toString` for the same URL
(`agent/EmbeddedAgentConfig.kt:598-631`, `key == "url"`).

**Problem:**

- **Attacker:** anyone who can read the host application's logs, after an app logs its
  `PrometheusProxyAgentProperties`, as the KDoc invites.
- **Trigger:** a `prometheus-proxy.agent.paths[].url` such as `https://exporter/metrics?api_key=…`, a form the security
  page names as a credential (`website/prometheus-proxy/docs/security/index.md`, Credentials in URLs).
- **Impact:** the key reaches the log in full, though every log line the agent itself writes masks it. The regex also
  differs from the agent's first-`@` rule for user info.

**Fix:** mask the URL with the same rules as the agent: in Java, user info up to the first `@` of the authority, and each
query value replaced by `***`, keeping the keys. Or render `Target` through a small Kotlin helper the starter can call.

**Tests:** in `PrometheusProxyAgentAutoConfigurationTest` (or a new properties test), `Target.toString` of
`https://u:p@h/metrics?api_key=s&x=1` contains neither `p` nor `s`, and keeps `api_key=` and `x=`.

**Resolution:** Fixed in #550. `Target.toString` masks the URL with `shownUrl`, the agent's `sanitizeUrl` rules in
Java: the user info up to the first `@`, and each query value, keeping the keys. A new
`PrometheusProxyAgentAutoConfigurationTest` test binds `https://u:pw@grinder:9100/metrics?api_key=k3y&x=1#top` and
finds none of its secrets in the properties' `toString`.

---

## 🏗️ CI, build, packaging, and demos

### 25. ✅ Every release moves each demo's `NEEDS_RELEASE_AFTER` by hand, twice, and until the release is published `master`'s `make up` says a release that isn't out "predates this demo", then can't pull it

**Severity:** low (medium at `56b1c355`; found by both the build and the documentation passes) · **Confidence:**
confirmed by trace and by running `make _check-demos-pull-release` with several `VERSION`s.

**Where:**

- `demos/{basics,network,ktor,spring-boot,plain-kotlin}/demo.sh:62`, `demos/nginx/demo.sh:63`, and
  `demos/kubernetes/demo.sh:33`: `NEEDS_RELEASE_AFTER=4.7.0`, raised by #516 with the 4.7.0 bump. Each has the same
  comment above it (`:60-61`, `:31-32` in Kubernetes): "When the demo comes to use something unreleased, set this to the
  latest release." The READMEs' file tables define it the same way (e.g. `demos/basics/README.md:393`).
- `demos/basics/demo.sh:249-259` (`version_after`, strictly later), `:262` (`latest_release` is `make site-version`),
  `:307-318` (`cmd_up`, whose message is at `:314-315`), and `:291-304` (`pull_images`, "check the network"). The other
  five Docker demos have the same code. `demos/kubernetes/demo.sh:461-482` does the same with `! version_after`, and
  checks the release's manifests (`:471-476`).
- `Makefile:827-840`: `_check-demos-pull-release`, a prerequisite of `release-check` (`:626`), added by #537; `:835`
  skips a demo whose value it can't read. No test runs it.
- `docs/RELEASE.md:19-20` and `:68-74`, `.claude/skills/publishing-release/SKILL.md` (the `demos/*/demo.sh` bullet), and
  AGENTS.md (the release paragraph): raise the value to the new version with the bump, lower it to the outgoing release
  in the last change before `make release`, and expect `make up` on `master` to fail to pull until the release is out.
- `bin/docker-agent.sh:4-7` and `bin/docker-proxy.sh:4-7`: run `site-version`'s images, "which between releases is the
  last release".

**Problem:** the release tag is now safe, since `release-check` refuses it until every demo is lowered. The rest of
the cycle still runs on a version literal that has to be edited around each release, and `master` gets it wrong in
both windows the cycle has:

- **From the bump to the lowering** (since #516 for 4.7.0): `make up` builds from source and prints "The latest
  release, 4.7.0, predates this demo", wrong twice, since 4.7.0 isn't released and is the release that ships what the
  demo shows. `bin/docker-*.sh` run `pambrose/prometheus-{agent,proxy}:4.7.0`, which isn't on Docker Hub, so their
  `docker run` fails with a pull error.
- **From the lowering to the publish:** `make up` on `master` tries to pull 4.7.0 and dies with "can't pull …; check
  the network". RELEASE.md accepts this window, but if `make release` stops before `docker-push` (a failing
  `all-tests`, a Maven Central error), it lasts until the release is finished, and RELEASE.md doesn't say to raise the
  value again in the meantime.
- **The rule as the scripts state it** is the old one: "set this to the latest release" is right between a release and
  the next bump only. Set that way after a bump, `make up` pulls the unpublished version.
- **The guard** passes a demo whose line it can't read (an indented, `readonly`, or `export`ed assignment, or a renamed
  variable), and nothing tests it.

**Fix:** the gate the original Fix proposed still removes all of this:

- Rename the variable to `NEEDS_RELEASE` (the first release that has what the demo shows), compare with
  `version_at_least`, and use the release's images only when they exist: locally (`docker image inspect`, so an offline
  `make up` with the images cached still works), or on Docker Hub (`docker manifest inspect`, as
  `demos/kubernetes/demo.sh:471-476` does). Otherwise build from the checkout and say "release 4.7.0, which the docs
  give, isn't published yet". The tag then pulls, `master` builds until the publish, no release step touches the
  demos, and `_check-demos-pull-release` and `docs/RELEASE.md` step 2's two moves go.
- Give `bin/docker-*.sh` the same manifest check, failing with "4.7.0 isn't on Docker Hub yet" rather than a pull error.

Short of the gate:

- Have `_check-demos-pull-release` fail, rather than `continue`, when a `DEMOS` entry has no readable value, and name
  the value to set (`prev`, as the stale check computes it).
- In `cmd_up`, when the docs' version equals `NEEDS_RELEASE_AFTER`, say it isn't published yet instead of "predates";
  in `pull_images`, say the release may not be published yet.
- Reword the `demo.sh` comment and the README tables to `docs/RELEASE.md` step 2's rule, and add to that step: "if the release
  stops before the images are pushed, raise it again".

**Tests:** a `misc/` spec that runs `make -s _check-demos-pull-release VERSION=<v>` the way `SiteVersionTest` runs
`make site-version`: it fails at the current version, passes above it, and fails for a demo whose assignment it can't
read (run in a temp directory holding copies of `Makefile`, `gradle.properties`, and `demos/*/demo.sh`, one of them
edited). With the gate, a spec that sources each `demo.sh`'s choice with a stubbed `docker`, as originally proposed.

**Resolution:** Partly fixed in #537. `make release-check` now runs `_check-demos-pull-release`, which refuses a
release while any demo's `NEEDS_RELEASE_AFTER` isn't below the version, and `docs/RELEASE.md` step 2, the
publishing-release skill, and AGENTS.md have the release lower it in the last change before `make release`, so the
release tag pulls its images. Still open: the value is edited twice per release by hand, `master`'s `make up` and
`bin/docker-*.sh` mishandle the unpublished release between the bump and the publish, and the guard skips a demo it
can't parse and has no test.

**Resolution:** Fixed in #561. Each `demo.sh` checks whether the release the docs give is published, by an image Docker already has or Docker Hub's answer, only whose "no such manifest" means no, and `make up` builds from the checkout until it is, saying why; the Kubernetes demo asks Docker Hub alone, as its clusters pull the images. `NEEDS_RELEASE_AFTER` keeps its name and meaning, the newest release that lacks what the demo shows, rather than becoming the first that has it, and is 4.6.0 in all seven demos, so no bump or release moves it; `_check-demos-pull-release` stays as a guard, now failing on a value it can't read. `bin/docker-*.sh` say a release isn't on Docker Hub yet. `DemoReleaseGateTest` runs each `cmd_up` with a stubbed `docker`.

### 26. ✅ Both charts' dashboards listen on every pod interface, so keeping them off the Service doesn't keep them private

**Severity:** low · **Confidence:** confirmed by trace (the chart templates and `config/config.conf`'s default).

**Where:**

- `config/config.conf:51` and `:202`: `proxy.dashboard.host` and `agent.dashboard.host` default to `"0.0.0.0"`.
- `charts/prometheus-proxy/templates/deployment.yaml:78-90` and `charts/prometheus-agent/templates/deployment.yaml:82-94`
  set `DASHBOARD_ENABLED`, `DASHBOARD_PORT`, and the login variables, but never `DASHBOARD_HOST`. Neither chart has a
  NetworkPolicy.
- `charts/prometheus-agent/values.yaml:81-84` and the agent README, new in #482, and
  `charts/prometheus-proxy/values.yaml:60-62`: "The port is not added to the Service; reach it with kubectl
  port-forward". The READMEs say a login's password "is protected only by the port-forward's tunnel".

**Problem:** with `dashboard.enabled=true`, any pod in the cluster can open `http://<pod-ip>:8094/dashboard` or `:8095`.
Without a login, that's the whole operational view, the agent's Scrape now (which makes the agent scrape its targets on
demand), and the Settings view. With a login, the password crosses the pod network in plain HTTP, not only the tunnel
the README describes. The process does log its "listens on every interface without a login" warning, but the chart's
docs tell the reader that leaving the port off the Service is what keeps the dashboard private.

**Fix:**

- Add `dashboard.host` to both charts, defaulting to `127.0.0.1` and rendered as `DASHBOARD_HOST`. The kubelet's
  port-forward dials the pod's own loopback, so the documented access path keeps working.
- Have the Kubernetes demo's `values/proxy.yaml` set `dashboard.host: 0.0.0.0`. It's the one setup that publishes the
  proxy dashboard through a Service of its own (`demos/kubernetes/k8s/monitoring.yaml:97-114`).
- Reword the values comments to say the host setting is what makes the dashboard local.

**Tests:** in `make helm-lint`, assert that the default render sets `DASHBOARD_HOST` to `127.0.0.1` when the dashboard is
on, as the `secret-config-values.yaml` case is asserted now.

**Resolution:** Fixed in #556. Both charts have a `dashboard.host` value, default `127.0.0.1`, passed as `DASHBOARD_HOST`, which `kubectl port-forward` still reaches; a values file with no `host` key, as after `helm upgrade --reuse-values` from an older chart, gets `127.0.0.1` too, and an empty value leaves the address to the config. The install notes warn when the dashboard is on another address, the READMEs and pages say how to publish it deliberately, and the Kubernetes demo's proxy sets `0.0.0.0` for its NodePort. `make helm-lint` fails unless the all-features render sets `127.0.0.1`.

### 27. ✅ The Docker quick-start compose file publishes the agent, scrape, and admin ports on every interface with no token, and the docs tell readers to leave it running

**Severity:** low · **Confidence:** confirmed from the files.

**Where:**

- `etc/compose/proxy.yml:12-17` (proxy `8080`, `50051`, `8082`, `8092`) and `:24-26` (agent `8083`, `8093`) have no host
  address and no `AGENT_TOKEN`.
- `website/prometheus-proxy/docs/docker.md:89-105`: "a stack you can start as-is", then `up --detach` with `restart:
  unless-stopped`.
- The website's `docker-compose-full` snippet (`src/test/kotlin/website/DockerExamples.txt:73-104`) and
  `bin/docker-proxy.sh` publish the same ports the same way.

**Problem:** the agent in this stack reaches the proxy over the compose network, so publishing `50051` only exposes it.
Anyone who can reach the host can then register as an agent, with no token, and serve any path Prometheus scrapes. The
scrape port gives every registered path's metrics to anyone. The admin port serves `/threaddump`. Docker-published ports
also bypass a host's ufw or firewalld rules. Every demo binds `127.0.0.1` for this reason, and
`examples/cloud-proxy/docker-compose.yml` requires a token and TLS. The local quick start is the one copyable stack that
does neither.

**Fix:** bind every published port to `127.0.0.1` (`"127.0.0.1:8080:8080"`). Drop the `50051` publish, or comment that
it's for an agent on another host and needs `AGENT_TOKEN`. Do the same in the `docker-compose-full` snippet. Add a
line to `docker.md` pointing a reader who deploys it at the Cloud Platforms compose file.

**Tests:** a `misc/` check, alongside `CiHygieneTest`, that every `ports:` entry in `etc/compose/*.yml` and in the site's
compose snippets names `127.0.0.1`, as the demos' entries do.

**Resolution:** Fixed in #556. `etc/compose/proxy.yml` and the site's compose example bind every port to `127.0.0.1`, and no longer publish 50051, which the compose agent reaches over the compose network; `docker.md` says so, and sends readers with remote agents to the cloud-proxy compose file. `DocsExamplesTest` fails on a compose file or docs snippet that publishes a port on every interface, the cloud-proxy example excepted.

### 28. ⬜ `--tests=<spec>` (the equals form) runs the whole suite unfiltered, and with `RUN_CONTAINER_TESTS` runs the harness specs three at a time

**Severity:** low · **Confidence:** reproduced with `./gradlew -m test --tests=io.prometheus.common.UtilsTest`, which
schedules `:unitTest`, `:harnessTest`, and the disabled `:test`.

**Where:**

- `build.gradle.kts:406`: `configureTestPartitions` checks `"--tests" in it.args`, which matches only the
  space-separated form.
- `build.gradle.kts:395-402`: `testsOnlyContainerSpecs()`, a few lines up, also reads `--tests=`.
- `build.gradle.kts:354-356` sets `kotest.framework.config.fqn` on every `Test` task.

**Problem:**

- `./gradlew test --tests=io.prometheus.x.FooTest`, which Gradle accepts, registers the partitions. It disables `test`,
  which is the only task the filter reaches, and runs every spec in both partitions.
- With `RUN_CONTAINER_TESTS=true` and `--tests=io.prometheus.containers.X`, `testsOnlyContainerSpecs()` is true, so
  `unitTest` and `harnessTest` both get `ContainerTestsConfig`'s three-specs-at-a-time mode. The harness specs then run
  concurrently on their fixed `HARNESS_*` ports and fail with `BindException`.
- A smaller trap: `./gradlew unitTest --tests X`, the obvious way to run one non-harness spec, fails with "Task 'unitTest'
  not found", because naming specs skips the partitions. AGENTS.md says so only indirectly ("isn't split").

**Fix:**

- Compute "specs are named" once, from both forms, and use it in both functions.
- Register `unitTest` and `harnessTest` unconditionally, disabling `test` only when nothing is named, so
  `unitTest --tests X` works.

**Tests:** a Gradle TestKit check, or a `misc/` spec that runs `./gradlew -m`, asserting that `--tests=X` schedules
`:test` and neither partition.

### 29. ⬜ The `misc/` specs read docs, workflows, the Makefile, and demo files that aren't `Test` inputs, so `./gradlew check` and `make ci-build` take their result from the cache after an edit to those files

**Severity:** low · **Confidence:** confirmed by trace: the build script declares only property inputs on `Test`
(`build.gradle.kts:344-346`), and `org.gradle.caching=true`. Reproduced at `5c9f0a24`: with four demo files edited so
that `DemoTourTablesTest` fails, `./gradlew test --tests 'io.prometheus.misc.DemoTourTablesTest'` reported `:test
UP-TO-DATE` and passed; with `--rerun`, four of its five tests failed.

**Where:**

- `build.gradle.kts:339-349` makes the harness profile and the container switch inputs, for exactly this reason.
- Nothing declares the files the documentation and CI specs read: `docs/TESTING.md` (`TestingGuideTest`), `.github/` and
  the `Makefile` (`CiHygieneTest`, `SiteVersionTest`), website pages (`WebsiteConfigKeysTest`), `config/config.conf` and
  the shipped configs (`ConfigFileKeysTest`), `grafana/*.json` (`DashboardMetricNamesTest`), and
  `demos/*/docker/compose.yml` (`TestPortsTest`), and, since #549, every demo's `README.md` and `demo.sh` and its page
  on the docs site (`DemoTourTablesTest`).

**Problem:**

- Say a change edits only those files, for example a workflow job without `timeout-minutes`, a Makefile `*_IMAGE` without
  a digest, or a new spec missing from `docs/TESTING.md`. Locally, `unitTest` is then UP-TO-DATE, and after `clean`
  (`make ci-build`, so `make ci`) it comes FROM-CACHE: the specs that police those files don't run, and the build passes.
- CI is safe only by accident: each commit's time changes `BuildConfig`, so the classpath changes. That's why the gap
  shows up as "passes locally, fails in CI". `make tests` escapes it with `--rerun`.

**Fix:** declare the files on `unitTest` (or every `Test` task) with relative path sensitivity:
`inputs.files(fileTree("docs"), fileTree(".github"), "Makefile", fileTree("website/prometheus-proxy/docs"),
fileTree("demos") { include("*/docker/compose.yml", "*/demo.sh", "*/README.md", "*/kind/*.yaml") },
"config/config.conf", fileTree("grafana"),
...).withPropertyName("checkedFiles").withPathSensitivity(PathSensitivity.RELATIVE)`. Keep the list beside the specs'
own list in `docs/TESTING.md`.

**Tests:** none beyond the build: after the change, editing `docs/TESTING.md` makes the next `./gradlew unitTest` run.

### 30. ✅ `make site-version` doesn't scan `examples/cloud-proxy/docker-compose.yml`, which the site shows with an image tag, though its comment says nothing outside its sources names a version

**Severity:** low · **Confidence:** confirmed from the files.

**Where:**

- `Makefile:80-83`: `SITE_VERSION_SOURCES` is the site's pages and two snippet directories. The comment says the repo-root
  snippet base "gives the site examples/, ... none of which names a version".
- `website/prometheus-proxy/docs/cloud-platforms.md:190` includes `examples/cloud-proxy/docker-compose.yml`, whose line
  17 is `image: pambrose/prometheus-proxy:4.7.0`.
- `docs/RELEASE.md` step 2: "`make site-version` ... fails if they give more than one."

**Problem:** a bump that misses that file leaves the Cloud Platforms page showing the old tag, and `site-version` still
passes. `release-check` would catch it, since the file is in `RELEASE_VERSION_FILES`, so the impact is the broken
guarantee and the wrong comment.

**Fix:** add `examples` to `SITE_VERSION_SOURCES` (no other file there names a tag), and fix the comment.

**Tests:** a `SiteVersionTest` case that every snippet a page includes with `--8<--` is under `SITE_VERSION_SOURCES` or
names no version.

**Resolution:** Fixed in #561. `SITE_VERSION_SOURCES` includes `examples`, and `SiteVersionTest` checks every file a page includes.

### 61. ✅ `release-check`'s stale-version check, which #542 extended to the raw config links, misses the Quick Start's JAR download links, so a bump that skips them passes and the site sends JAR readers to the previous release

**Severity:** low · **Confidence:** confirmed by replaying the Makefile's regex on the lines left at the previous
version:
the raw link and the image tag are caught, the three JAR lines are not.

**Where:**

- `website/prometheus-proxy/docs/getting-started.md:16-20`: the JAR tab links the
  `releases/tag/4.7.0` page and downloads `releases/download/4.7.0/prometheus-{proxy,agent}.jar` (added by #533).
- `Makefile:642-647`: the stale check's regex has alternatives for an image tag or coordinate, `<version>`, a chart's
  `version`/`appVersion`, `--branch`, the backticked examples, and, since #542, `/prometheus-proxy/<prev>/` for the
  `raw.githubusercontent.com` links. `releases/tag/<prev>` and `releases/download/<prev>/` match none of them.
- The `missing` check (`Makefile:640-641`) passes `getting-started.md` on its image tags, so it can't catch them either.
- `docs/RELEASE.md:52-53` lists `getting-started.md` for "the Docker pull and run examples", not its JAR links, and
  the skill's matching line likewise. `release-check`'s comment (`Makefile:618-625`) and RELEASE.md's checklist
  (`:16-18`) list what the stale check covers without the raw links #542 added.
- `Makefile:698-720` (pre-existing): `make release` deploys the site with `gh-docs` and only then prints
  `docs/RELEASE.md`'s step 7, the GitHub release that holds those JARs.

**Problem:**

- At the next bump (4.7.1 or 4.8.0), a change that updates the image tags and raw links but misses the three JAR lines
  passes `release-check`. The deployed Quick Start's JAR tab then downloads the previous release's JARs while its
  Docker tab runs the new images, and its config links (raw, at the new tag) can name settings the old JARs don't
  know.
- Pre-existing, from #533: right after `make release`, the live JAR tab's `curl -fLO` fails with 404 until the
  maintainer creates the GitHub release with its assets, which `docs/RELEASE.md`'s step 7 leaves for after the deploy.

**Fix:**

- Add `/releases/(tag|download)/$$p([/)]|$$)` to the stale regex, name the JAR links in `docs/RELEASE.md` step 2's
  `getting-started.md` bullet and the skill's, and list the raw and release links in `release-check`'s comment and
  RELEASE.md's checklist.
- For the ordering: have `make release` deploy the site only once `gh release view $(VERSION) --json assets` shows both
  JARs, printing "run make gh-docs after step 7" otherwise; or attach the JARs in `make release` itself
  (`gh release create $(VERSION) --verify-tag` with the JARs and a placeholder note the maintainer edits in step 7).

**Tests:** move the stale grep into a target of its own (`_check-no-previous-version PREV=…`), and have a `misc/` spec
run it on the tree with `PREV` set to the current version, the way `SiteVersionTest` runs `make site-version`: it must
name `getting-started.md:16`, `:19`, and `:20`, which fails today.

**Resolution:** Fixed in #561. The stale check, now `_check-version-files` with a `PREVIOUS_RELEASE` override, catches the Quick Start's `releases/tag|download` links, and the Spring Boot demo's `<prometheus-proxy.version>`, which wasn't listed here; `ReleaseCheckTest` checks it catches every version mention but the historical ones. In place of placeholder notes, `make release` creates the GitHub release itself (`make github-release`) between the tag and the docs deploy, with `RELEASE_NOTES.md`'s section and the JARs, so the download links work from the start.

### 62. ✅ Both changelogs say 4.7.0 was released on 2026-11-23, a day that passed without the release, and `make release-check` accepts any date

**Severity:** low · **Confidence:** confirmed by trace.

**Where:**

- `CHANGELOG.md:7`: `## [4.7.0] - 2026-11-23`. `RELEASE_NOTES.md:7`: `_Released 2026-11-23_`.
- Both dates were written by #516 (19149a7d, 2026-11-23 01:32 -0800), when 4.7.0 replaced the unpublished 4.6.1. All
  seven commits from #536 to #542 (the last at 23:38 -0800 on the 23rd) added 4.7.0 entries under that date, and #547
  (3fc6d43d, 2026-11-24 14:55 -0800) added another a day later. There's no 4.7.0 tag at `5c9f0a24`, and neither Docker
  Hub nor Maven Central has 4.7.0.
- `Makefile:636-639` (`release-check`) checks only each date's form, `YYYY-MM-DD`, as `docs/RELEASE.md:14-15` says.
  Step 3 (`docs/RELEASE.md:87-88`) names "the new version and release date", but nothing at release time looks at it.

**Problem:** released on any later day, 4.7.0's changelog, its release notes, and the GitHub release made from them
(`docs/RELEASE.md` step 7) give a date that isn't the release's. The release's last change, which lowers each demo's
`NEEDS_RELEASE_AFTER` (`docs/RELEASE.md` step 2), is already a change the release can't skip, and it doesn't touch the dates.

**Fix:** set both dates in that last change before `make release`, and say so in `docs/RELEASE.md` steps 2 and 3. In
`release-check`, compare the two dates with the day's (`date +%F`) and refuse a mismatch, naming the date found, so a
release cut after the day its changelogs were dated is caught before it publishes.

**Tests:** none practical; `make release-check` is the check.

**Resolution:** Fixed in #561. `_check-changelogs` refuses a release unless both changelogs date its section the day it runs, naming the date they give, and `docs/RELEASE.md` step 3 dates them in the last change merged before `make release`. 4.7.0's sections keep 2026-11-23 until that change.

### 63. ✅ `agent-freeze agent-east` promises that DemoAgentEvicted fires once agent-east is back, but the rule looks back two minutes, so a thaw more than about a minute after the eviction never fires it

**Severity:** low · **Confidence:** plausible: the rule's window, the sample gap, and the eviction timing are traced
from the demo's configs, not run; whether it fires depends on when the reader thaws.

**Where:**

- `demos/basics/demo.sh:886-896` (#540): for agent-east, "The DemoAgentEvicted alert and Grafana's panels of the
  proxy's metrics, such as 'Agent disconnects at the proxy, by reason', can't update while agent-east is gone. Once
  it's back, they catch up: the alert fires, and the panel shows the eviction."
- `demos/basics/prometheus/demo-alerts.yml:18-19`: `DemoAgentEvicted` is `increase(proxy_eviction_count_total[2m]) > 0`.
  `demos/basics/grafana/dashboards/demo-overview.json:979`: the panel is
  `sum by (reason) (increase(proxy_agent_disconnects_total[5m])) > 0`.
- `demos/basics/config/agent-east.conf:40`: agent-east is the only agent that serves `/proxy_metrics`.
  `demos/basics/config/proxy.conf:56-57`: eviction after 30 seconds of silence, checked every 5.
  `demos/basics/prometheus/prometheus.yml:9,22`: 5-second scrapes and discovery refreshes.

**Problem:** from the freeze until agent-east re-registers, Prometheus gets no sample of the proxy's counters: the
scrapes through the frozen agent time out, and after the eviction the path leaves `/discovery`. The counter's last
sample before the gap is at the freeze, and its first after it comes about 10 seconds after the thaw (reconnect,
discovery refresh, scrape). `increase()` needs two samples in its window, so the alert sees the eviction only if those
two are within 2 minutes: a thaw within roughly 70 seconds of the eviction, about 105 seconds after the freeze. The
panel's 5-minute window allows about four minutes. A reader who follows the scenario's advice to watch the dashboards
first, then runs `agent-thaw agent-east`, can easily take longer, and then the alert the text promised never fires,
and the panel never shows the eviction. Both counters exist from the start (`ProxyMetrics.kt:158-161` and `:206-211`,
`startingAtZero`), so the pre-freeze sample is there; it's the window that drops it. The tour itself freezes
agent-west-2, whose branch is right, since the proxy's metrics keep flowing through agent-east.

**Fix:** state the condition: "Thaw it within a minute of the eviction and they catch up: the alert's rule looks back
two minutes and the panel five, so a longer freeze leaves both without the eviction." Or have the agent-east branch
suggest `agent-freeze` of agent-west-2 to see the alert.

**Tests:** none; scenario text.

**Resolution:** Fixed in #552. The agent-east branch says to thaw within a minute of the eviction, why, and to freeze agent-west-2
instead to see the alert fire.

### 67. ✅ On `master`, `make up` and `make tour` link to docs pages and anchors the live site doesn't have: three demos' pages answer 404, and no page has `#what-this-demo-shows`

**Severity:** low · **Confidence:** confirmed with `curl` against prometheus-proxy.com on 2026-11-24: `/ktor-demo/`,
`/nginx-demo/`, and `/plain-kotlin-demo/` answer 404; `/basics-demo/` and `/kubernetes-demo/` answer 200, with
`id="running-it"` but no `id="what-this-demo-shows"`.

**Where:**

- Each `demo.sh`'s `PAGE_URL` (#548, e.g. `demos/basics/demo.sh:73`). `print_urls` prints
  `${PAGE_URL}#what-this-demo-shows` after every `make up` (e.g. `:177`), and `tour_contents` before every tour
  (`:445`), in all seven demos.
- Part 1 of three tours now reads the layout off the page, "as the diagram at the top of its website page draws it:
  $PAGE_URL" (`demos/basics/demo.sh:618`, `demos/ktor/demo.sh:625`, `demos/plain-kotlin/demo.sh:859`), where it said
  "The diagram at the top of demos/<name>/README.md shows the layout". `demos/plain-kotlin/demo.sh:864` links
  `#the-workers-code` (the checkout's path follows it), and `demos/kubernetes/demo.sh:226-227` sends a port collision to
  `#running-it`.
- AGENTS.md and `docs/RELEASE.md:33-38`: the site deploys only from `make release`, so between releases it describes
  the last deploy.

**Problem:** until 4.7.0's release deploys the site, everyone who runs a demo runs `master` (the docs' `git clone
--branch 4.7.0` has no tag to check out). The Ktor, nginx, and plain Kotlin demos print a link that answers 404 after
every `make up` and before every tour, and the Ktor and plain Kotlin tours' first step sends the reader to that 404 for
the diagram. All seven demos' "What each part shows, and why" links land on a page without that table. At a release
the tag and the site agree, but on `master` between releases any demo added, page renamed, or section retitled drifts
the same way, where the README the tours named before is always the script's own.

**Fix:** print the checkout's copy beside the page, which `DemoTourTablesTest` keeps in step with the tour: "What each
part shows, and why: demos/<name>/README.md, or ${PAGE_URL}#what-this-demo-shows", and point part 1 at "the diagram at
the top of demos/<name>/README.md, or of the demo's page". Or print the page link only when `make site-version` equals
`VERSION`, which holds at a release's tag.

**Tests:** with the README path printed, `DemoTourTablesTest` can require each `${PAGE_URL}#anchor` a script prints to
be an anchor of the README too (see #72).

**Resolution:** Fixed in #561. `make up`, `make tour`, and the tours name the demo's README, which matches the checkout, beside the docs site's page for the latest release.

### 68. ⬜ `make xxl-tests` gives both test partitions the XXLARGE profile's 16g heap, though only the harness specs read the profile, so the run can grow to 32g of test heap beside Gradle's 4g

**Severity:** low · **Confidence:** plausible: the per-task heap is traced in the build script; how far each JVM grows
depends on the load and G1's sizing.

**Where:**

- `build.gradle.kts:366-372` (#547 raised XXLARGE from 8g to 16g) sets `maxHeapSize` inside `tasks.withType<Test>()`,
  so `unitTest`, `harnessTest`, and `test` all get it.
- `Makefile:240-241`: `xxl-tests` runs `check unitTest --rerun harnessTest --rerun -PharnessConfig=XXLARGE`, and the
  two partitions run at once, each in its own JVM (`configureTestPartitions`, with the configuration cache on).
  `gradle.properties:21` gives the daemon 4g.
- Only the harness specs read `HarnessConfig`. CI runs the MINI profile (`ci.yml:27`), so no runner ever takes the
  16g; `docs/TESTING.md:42` says "16g test heap", in the singular.

**Problem:** the unit specs gain nothing from the profile, but their JVM may now grow to 16g beside the harness JVM's
16g, where the cap used to stop both at 8g. On a machine with less than about 40 GB, a heavy run can push into swap
rather than end in a clear `OutOfMemoryError`.

**Fix:** set the profile's heap only on `harnessTest` (and on `test` when `--tests` makes it run every spec in one JVM),
leaving `unitTest` at its 1g; say "16g for the harness JVM" in `docs/TESTING.md`.

**Tests:** none beyond the build; `ps` during a `make xxl-tests` run shows each test JVM's `-Xmx`.

---

## 📚 Documentation

### 31. ✅ The Spring Boot Starter page's YAML reads `PROMETHEUS_PROXY_CERT` with no `authority`, so with the certificate the docs make for the proxy the agent's TLS handshake fails

**Severity:** medium · **Confidence:** confirmed by trace. It needs a certificate issued to a name other than the host
the agent dials, which is the certificate Cloud Platforms makes, on the page that sends Spring Boot readers here.

**Where:** `website/prometheus-proxy/docs/spring-boot-starter.md:35-40` ("Only the proxy is needed. Read it, and the
rest, from the environment"), which includes `spring-boot-starter/src/test/resources/website/application.yml:9-13`:
`proxy: ${PROMETHEUS_PROXY:}`, `token: ${PROMETHEUS_AGENT_TOKEN:}`, `trust-certificate: ${PROMETHEUS_PROXY_CERT:}`.

**Problem:** the YAML reads the same variables as `fromEnvironment()`, but not the way it does. `fromEnvironment()`
gives the certificate the authority `PROMETHEUS_PROXY_AUTHORITY`, or `prometheus-proxy` without it
(`agent/EmbeddedAgentConfig.kt:300-302`, `DEFAULT_AUTHORITY` at `:577`). The starter passes
`blankToNull(properties.getAuthority())` (`spring-boot-starter/.../PrometheusProxyAgent.java:163`), and with no
`authority` property no `agent.tls.overrideAuthority` is set, so gRPC checks the certificate against the host the
agent dials. Cloud Platforms' Securing the Link issues the proxy's certificate to `CN=prometheus-proxy`,
`subjectAltName=DNS:prometheus-proxy` (`cloud-platforms.md:155-163`, "checks it against the fixed name"), and sends
Spring Boot readers to the starter page (`cloud-platforms.md:77`). A reader who sets `PROMETHEUS_PROXY_CERT` to that
certificate and copies the page's YAML gets a hostname-verification failure on every connect. The Spring Boot demo's
own `application.yml:40` has the line the page lacks, with a comment (#538) that its default is the name Cloud
Platforms issues the certificate to: `authority: ${PROMETHEUS_PROXY_AUTHORITY:prometheus-proxy}`.

**Fix:** add `authority: ${PROMETHEUS_PROXY_AUTHORITY:prometheus-proxy}` to the snippet, with a comment that it's the
name the proxy's certificate is issued to, as `fromEnvironment()` defaults it.

**Tests:** `StarterExamplesTest` binds the snippet; have it assert that the bound properties give the same
`agent.tls.overrideAuthority` as `fromEnvironment()` given the same variables.

**Resolution:** Fixed in #550. The snippet sets `authority: ${PROMETHEUS_PROXY_AUTHORITY:prometheus-proxy}`, with a
comment saying why. `StarterExamplesTest` binds it with `PROMETHEUS_PROXY_CERT` set, and finds
`overrideAuthority=prometheus-proxy`, or the name `PROMETHEUS_PROXY_AUTHORITY` gives.

### 32. ✅ The upgrade notes don't say that a 4.7.0 agent reads `DASHBOARD_*` and `METRICS_PROXY_PATH`, which a 4.6.0 agent ignored

**Severity:** low · **Confidence:** confirmed by trace that 4.7.0 reads them and 4.6.0 didn't; whether a deployment is
affected depends on its environment.

**Where:** `website/prometheus-proxy/docs/embedded-upgrading.md:11-17` (Upgrading from 4.6.x, whose only bullet is the
shutdown hook), and the Behavior Changes of `RELEASE_NOTES.md:116-146` and `CHANGELOG.md:188-217`. `CHANGELOG.md:11-12`
names the variables only as a new feature.

**Problem:** `AgentOptions` now calls `BaseOptions.assignDashboardOptions` (`common/BaseOptions.kt:600-625`), which
resolves `DASHBOARD_ENABLED`, `DASHBOARD_PORT`, `DASHBOARD_PATH`, `DASHBOARD_HOST`, `DASHBOARD_USERNAME`, and
`DASHBOARD_PASSWORD`, and it resolves `METRICS_PROXY_PATH` (`agent/AgentOptions.kt:443-452`). At tag 4.6.0 the
`DASHBOARD_*` names were the proxy's alone. So after the upgrade:

- an agent sharing the proxy's environment (one env file for both on a host, a shared compose `env_file`) with
  `DASHBOARD_ENABLED=true` opens a dashboard of its own on `0.0.0.0:8095`, without a login unless
  `DASHBOARD_PASSWORD` is shared too;
- an app that embeds the agent and uses any of these generic names for its own settings gets the agent's dashboard, or,
  since the port and boolean are parsed strictly before the `if (!dashboardEnabled) return` (`BaseOptions.kt:605-616`),
  a value like an empty `DASHBOARD_PORT` fails `start()` with `ConfigLoadException`, and `startIfConfigured()` leaves
  the agent off.

**Fix:** add a bullet to Upgrading from 4.6.x and to both Behavior Changes: "The agent reads `DASHBOARD_ENABLED`,
`DASHBOARD_PORT`, `DASHBOARD_PATH`, `DASHBOARD_HOST`, `DASHBOARD_USERNAME`, `DASHBOARD_PASSWORD`, and
`METRICS_PROXY_PATH`, which through 4.6.0 only the proxy read, or nothing did. An agent whose environment sets them,
such as one sharing the proxy's, now opens its dashboard on port 8095 or serves its metrics at that path, and a value it
can't parse, such as an empty `DASHBOARD_PORT`, fails its start."

**Tests:** none practical; it's an upgrade note.

**Resolution:** Fixed in #552. Upgrading from 4.6.x, and both changelogs' Behavior Changes, name the seven variables, and say what
an agent whose environment sets them now does, and that a value it can't parse fails its start.

### 33. ✅ The Embedded Agent page gives `agent.logLevel` a default of `info`, but an embedded agent sets no level, and the startup log says `info` regardless

**Severity:** low · **Confidence:** confirmed by trace.

**Where:** `website/prometheus-proxy/docs/embedded-agent.md:335`: "`agent.logLevel` | info | The level of the agent's
`io.prometheus` loggers, and no others".

**Problem:** `config/config.conf:162` declares `agent.logLevel = ""`. `BaseOptions.assignLogLevel`
(`common/BaseOptions.kt:724-735`) sets a level only when the value isn't empty; otherwise it logs
`agent.logLevel: info` and sets nothing. The `info` comes from the standalone agent's `logback.xml`, which ships only in
the fat JARs (`src/shadow/resources/logback.xml`). So an embedded agent's `io.prometheus` loggers follow the app's own
logging configuration, and with none, Logback's DEBUG default, as the same page's Logging section says
(`embedded-agent.md:562-563`). `WebsiteConfigKeysTest` exempts the key (`NOT_CONFIG_DEFAULTS`) on the reasoning that
`logback.xml` sets info, which holds only for the standalone agent. The log line misleads the same way: an embedded
agent logging DEBUG says `agent.logLevel: info` at startup.

**Fix:** the table's default `""`, and "unset, they follow your application's logging configuration". In
`assignLogLevel`, log `"$role.logLevel: unset"` (or "the logging configuration's") when empty and embedded.

**Tests:** drop `agent.logLevel` from `WebsiteConfigKeysTest`'s exemptions for the Embedded Agent page, so the table's
default is checked against `config.conf`.

**Resolution:** Fixed in #552. The Embedded Agent page's row gives `""`, and says that unset, the loggers follow the application's
logging. An embedded agent with no level logs `agent.logLevel: unset, the application's logging configuration sets
it`, where it said `info` (`BaseOptions.unsetLogLevel`), which a new `AgentOptionsTest` test checks.
`WebsiteConfigKeysTest` exempts `logLevel` on the CLI Reference only, so the Embedded Agent page's default is checked
against `config.conf`.

### 34. ✅ The Embedded Agent page and `fromEnvironment()`'s KDoc say a refused `PROMETHEUS_*` value is never shown, but an endpoint is quoted, as the page's own example shows

**Severity:** low (found by both the documentation and the tests passes) · **Confidence:** confirmed by trace.

**Where:**

- `website/prometheus-proxy/docs/embedded-agent.md:193-196` ("with the variable, the setting, and the agent's
reason, never the value: `Invalid configuration: PROMETHEUS_PROXY, read by fromEnvironment() for
agent.proxy.endpoints: Invalid port in 'proxy:abc': 'abc' is not a valid integer`").
- `agent/EmbeddedAgentConfig.kt:280-283` (KDoc: "names the variable as well as the setting … and never the value").
- `agent/EnvironmentSetting.kt:37-40`: "The message never holds the value, which can be a token or a certificate."
- AGENTS.md's `fromEnvironment()` bullet: "and never the value".
- `agent/EnvironmentSettingTest.kt:46-52` requires the quoted endpoint, `Invalid port in 'proxy:abc': …`, from
  `Utils.parseEndpointList`.

**Problem:** `agent/EnvironmentSetting.kt:73-77` appends the parser's message, and the endpoint parser quotes its input
(`common/Utils.kt:237,238,272,277,293`). Only the certificate's message leaves the value out (`common/TlsMaterial.kt:110`).
`startIfConfigured()` logs the same text (`EmbeddedAgentConfig.kt:152-153`). The guarantee matters to a reader deciding
what may reach logs; an endpoint is rarely secret, but the docs promise more than the code does.

**Fix:** narrow all four texts to the guarantee the code keeps: "… and never a token or a certificate; an endpoint it
can't parse is quoted, as here" on the page, and the same in the KDoc, `EnvironmentSetting`'s comment, and AGENTS.md.
Or strip the value from the endpoint rejection and change `EnvironmentSettingTest.kt:51`.

**Tests:** `EmbeddedAgentConfigTest` (or wherever `fromEnvironment` is tested) already builds these failures; assert
the certificate's message omits the PEM, which is the guarantee worth keeping.

**Resolution:** Fixed in #552, by narrowing the texts: the page, `fromEnvironment()`'s KDoc, `EnvironmentSetting`'s comment,
AGENTS.md, and `docs/TESTING.md` say a refused value never shows a certificate, and that an endpoint is quoted. The
certificate's guarantee was already tested (`EnvironmentSettingTest`).

### 35. ✅ `agent.metrics.proxyPath` (new in 4.7.0) is missing from the README, `llms.txt`, Grafana's prerequisite, and `docs/metrics-and-grafana.md`, whose self-scrape snippet also points at a port that's off by default

**Severity:** low · **Confidence:** confirmed by trace.

**Where:**

- `README.md:105-107` (New Features presents serving the agent's own metrics through the proxy as `agentMetricsAt`,
  embedded only) and `README.md:872-873` (Built-in Metrics describes only a `pathConfigs` entry for port 8083).
- `llms.txt:63, 84, 236-250`: only `agentMetricsAt(path)`; the Common Agent Options table has no `--metrics_proxy_path`
  row.
- `website/prometheus-proxy/docs/grafana.md:13-15`: "Metrics must be enabled (`--metrics` / `METRICS_ENABLED=true`) and
  a Prometheus datasource must be scraping the proxy's and the agents' internal `/metrics` endpoints".
- `docs/metrics-and-grafana.md:30-38` (the agent's metrics only on port 8083) and `:381-394`, whose snippet registers
  `url: "http://localhost:8083/metrics"` with no `metrics.enabled = true`.

**Problem:** since #527 a standalone agent serves its own metrics, and the JVM groups `agent.metrics` turns on, at
`agent.metrics.proxyPath` (`--metrics_proxy_path`, `METRICS_PROXY_PATH`) in-process, with no port and without
`agent.metrics.enabled` (`Agent.kt:283-290`, `collectsMetrics`; `agent/AgentPathManager.kt:154-163`;
`agent/AgentOptions.kt:328,443-452`). `monitoring.md:34-61` recommends it, but these pages still tell a reader to open
the port, and Grafana's prerequisite says `--metrics` is required, which it isn't for the agents. The
`metrics-and-grafana.md` snippet alone registers a path to `localhost:8083` while `agent.metrics.enabled` defaults to
`false` (`config/config.conf:225`), so its scrape is the agent's 503 `upstream_error`; `monitoring.md:67-75` shows the
same snippet with `metrics.enabled = true`.

**Fix:** README New Features and Built-in Metrics, and `llms.txt`'s options table: name `agent.metrics.proxyPath` /
`--metrics_proxy_path` / `METRICS_PROXY_PATH` as the way to serve a standalone agent's metrics with no port. Grafana's
prerequisite: "The proxy's metrics must be enabled, and Prometheus must scrape them and each agent's metrics, each agent
in its own job and through the proxy, at its `agent.metrics.proxyPath` (or a path to its metrics port)". In
`metrics-and-grafana.md`, lead with `metrics.proxyPath` and add `metrics.enabled = true` to the port form.

**Tests:** `DashboardMetricNamesTest` reads `metrics-and-grafana.md`'s promql blocks; a `misc/` check that every hocon
block registering a `localhost:8083` (or `:8082`) URL also sets `metrics.enabled = true` would catch the snippet.

**Resolution:** Fixed in #552. `agent.metrics.proxyPath` (`--metrics_proxy_path`, `METRICS_PROXY_PATH`) is in the README's New
Features and Built-in Metrics, `llms.txt`'s embedded paragraph and options table, the Grafana prerequisite, and
`docs/metrics-and-grafana.md`, which leads with it and adds `metrics.enabled = true` to the port form. The new
`DocsCodeBlocksTest` fails on a docs HOCON block that scrapes `localhost:8083` without turning the port on, as the
metrics guide's did.

### 36. ✅ `docs/security-agent-authentication.md` still says a displaced agent is invalidated and must reconnect, but since 4.7.0 it stays connected and its reclaim is refused while the other agent holds the path

**Severity:** low · **Confidence:** confirmed by trace.

**Where:** `docs/security-agent-authentication.md:135-136` (Attack scenario, step 4: "The proxy overwrites the path's
owner with the attacker's context and invalidates the legitimate agent if it has no other paths") and `:150-151`
(Impact, Availability: "the legitimate agent's context is invalidated on displacement, so it must reconnect and
re-register; an attacker re-registering in a loop can keep a path effectively denied"). Its Affected code bullet
(`:100-106`) was updated and is right, so the document contradicts itself.

**Problem:** `proxy/ProxyPathManager.kt:598-618` (`settleDisplaced`) invalidates a displaced context only when it has
no paths left and shares the taker's `launchId`; an attacker's agent is always another launch, so the legitimate agent
stays connected, is told who holds the path (`tellHolder`), and `reclaimRejection` (`:487-508`) refuses its reclaim
with `HELD_BY_ANOTHER_AGENT` for as long as a live agent of another launch serves it, through its own reconnects. The
attacker needs one registration, not a loop; the path comes back when the attacker's agent leaves, or when the
legitimate agent restarts (a new launch registers without `reclaim`). The availability impact is worse than the
document says, which matters to a reader weighing whether to turn on per-agent auth.

**Fix:** step 4: "The proxy gives the path to the attacker's context. The legitimate agent, of another launch, stays
connected without the path, is told who holds it, and has its reclaims refused while the attacker's agent stays
connected." Availability: "one registration, held open, keeps the path from the real agent, through its reconnects
too, until the attacker's agent disconnects or is evicted, or the real agent restarts."

**Tests:** none; prose.

**Resolution:** Fixed in #552. Step 4 and Availability say the legitimate agent stays connected without the path, is told who holds
it, and has its reclaims refused until the attacker's agent leaves or the real agent restarts.

### 37. ✅ The Quick Start's Verify step says a `404` means no agent registered the path, but a target's own 404 comes back as 404 too

**Severity:** low · **Confidence:** confirmed by trace.

**Where:** `website/prometheus-proxy/docs/getting-started.md:353-354` (new in #533, its status a red pill since #539):
"404 Not Found: no agent has registered the path. Check the agent's log for the `Registered` line, and that the path
matches the agent's `path`."

**Problem:** `proxy/ScrapeDispatch.kt:202-215` passes a non-2xx agent status through (`statusCode =
scrapeResults.httpStatus`), labelled `path_not_found` for a 404 (`upstreamErrorLabel`), the same status as an
unregistered path (`ProxyUtils.invalidPathResponse`). A wrong `url` on a reachable target, the commonest first-run
mistake, sends the reader to check a registration that's fine. A target that doesn't answer in time is the agent's 408
(`common/ScrapeResults.kt:144-147`), which the list leaves out. `troubleshooting.md:107-122` already lists the target's
404.

**Fix:** after the 404 pill, "no agent has registered the path, or the target itself answered 404. Check the agent's
log for the `Registered` line; if it's there, check the `url` with `curl` from the agent's host." Add an item with a
`408 Request Timeout` pill, as the others have: "A `408` means the target didn't answer within the agent's scrape
timeout."

**Tests:** none; prose.

**Resolution:** Fixed in #552. The 404 item adds the target's own 404 and the `curl` check, and a `408 Request Timeout` item
follows it.

### 38. ✅ The Agent Dashboard page's Helm tab upgrades and port-forwards in `monitoring`, where the agent chart's README installs the agent, but the site's own agent installs are in the current namespace

**Severity:** low · **Confidence:** confirmed from the docs and Helm's per-namespace releases.

**Where:** `website/prometheus-proxy/docs/agent-dashboard.md:81-83`: `helm upgrade prometheus-agent
./charts/prometheus-agent --namespace monitoring --reuse-values --set dashboard.enabled=true` and `kubectl -n monitoring
port-forward deploy/prometheus-agent 8095`.

**Problem:** the site's agent installs, on `getting-started.md:303-305` and `kubernetes.md:73`, and `llms.txt:168`'s,
have no `--namespace` ("the agent in `default`", `kubernetes.md:57`); only the proxy goes in `monitoring`. The tab's
commands are the ones in the agent chart's README (`charts/prometheus-agent/README.md:61-65`), which installs the agent
in `monitoring` (`:18`), so they work for a reader who installed from the chart's README. For a reader who followed the
site, the upgrade fails with `"prometheus-agent" has no deployed releases` and the port-forward finds no deployment.
The chart's values are right (`charts/prometheus-agent/values.yaml:85-91`).

**Fix:** drop `--namespace monitoring` and `-n monitoring`, and add "with `--namespace`/`-n` if you installed the agent
in one, as the chart's README does in `monitoring`". Or install the agent the same way everywhere: the chart's README
in the current namespace, like the site's installs, or all of them in `monitoring`.

**Tests:** none; prose.

**Resolution:** Fixed in #552. The Helm tab drops `--namespace monitoring` and `-n monitoring`, and says to add them for an agent
installed in a namespace, as the chart's README does.

### 39. ✅ The README and the changelogs say every demo turns on its agents' dashboards, but the plain Kotlin demo's ovens have none

**Severity:** low · **Confidence:** confirmed by trace.

**Where:**

- `README.md:858-859` ("every demo turns it on for its agents, all but the nginx demo's 4.6.0 agent"),
  `CHANGELOG.md:80` ("Every demo turns on its agents' dashboards and shows the agent's side in its tour"), and
  `RELEASE_NOTES.md:23-24` ("every demo now shows its agents' dashboards"). The plain Kotlin demo's ovens don't:
  `demos/plain-kotlin/app/src/main/kotlin/bakery/Main.kt:29-36` calls no `dashboard(...)`, and
  `demos/plain-kotlin/docker/compose.yml` gives the ovens no `DASHBOARD_ENABLED` and publishes no oven port. The
  sentences predate the demo (#520).

**Fix:** "every demo turns it on for its agents except the nginx demo's 4.6.0 agent and the plain Kotlin demo's ovens,
which listen on no port", in the README and both changelogs.

**Tests:** none; prose.

**Resolution:** Partly fixed in #540. The plain Kotlin demo's README, page, and `demo.sh` now say a scale-in leaves
the stopped ovens' paths listed as `gone` for an hour while `/discovery` drops them, and the Kubernetes demo's
`change-config` counts the JVM and process series it adds instead of the stale 30-to-200 figure. The README and both
changelogs still say every demo turns on its agents' dashboards.

**Resolution:** Fixed in #552. The README and both changelogs leave out the plain Kotlin demo's ovens, which listen on no port, and
the changelog's entry, which ended mid-sentence, is finished.

### 40. ✅ Deployment and security pages: a check order, a `make up` claim, and a size limit no page names

**Severity:** low · **Confidence:** confirmed by trace.

**Where / Problem / Fix:**

- `website/prometheus-proxy/docs/security/index.md:112-114`: "A path is checked against the patterns only once it has
  passed the path limits (`proxy.internal.maxPathLength` and the rest …)". `ProxyServiceImpl.registerPath`
  (`proxy/ProxyServiceImpl.kt:316-330`) runs `formRejection` (length, labels size, form), then the globs, then
  `addPath`, where `maxPathsPerAgent` is checked (`ProxyPathManager.pathLimitRejection`); an agent at its path limit that
  registers a path outside its globs gets `NOT_AUTHORIZED`. `configuration/proxy.md:257-260` says it right. Fix: "once
  its form and size have passed (`maxPathLength`, `maxLabelsSizeBytes`)".
- `website/prometheus-proxy/docs/advanced.md:91-92`: the nginx demo's configs "with TLS, the dashboard, and
  `worker_shutdown_timeout`, which `make up` in `demos/nginx` runs". `make up` starts nginx on its stock configs
  (`demos/nginx/docker/compose.yml:61-67`); only `demo.sh nginx-shutdown-timeout` adds `worker_shutdown_timeout`, and
  `reset_demo` removes it. Fix: "`make up` runs nginx with TLS and the dashboard on its stock timeouts, and `./demo.sh
  nginx-shutdown-timeout` adds `worker_shutdown_timeout`".
- `website/prometheus-proxy/docs/troubleshooting.md:147-156`: "The scrape body exceeded a configured size limit … The
  two limits are independent and both surface as HTTP 413". A third, `proxy.internal.maxZippedContentSizeMBytes`
  (default 5), fails a chunked result as 502 `invalid_response` (`proxy/ChunkedContext.kt:69-71`,
  `ProxyServiceImpl.kt:822-831`), and neither the 502 section nor `production.md`'s sizing table names it. Fix: add it
  to the 502 section ("Zipped payload too large … raise `proxy.internal.maxZippedContentSizeMBytes`") to the sizing
  table (`production.md:195-196`), and to the proxy page's Content Size Limits section
  (`configuration/proxy.md:272-279`), which the production checklist links to since #539 and which shows the setting
  only in HOCON.

**Tests:** none; prose.

**Resolution:** Fixed in #552. The security page says the globs run once a path's form and size have passed; the Advanced page says
`make up` runs nginx on its stock timeouts and `nginx-shutdown-timeout` adds `worker_shutdown_timeout`; and
`maxZippedContentSizeMBytes` is in Troubleshooting's 502 section, Production's sizing table, and the proxy page's
Content Size Limits, which now says which limit fails a scrape with which status.

### 41. ✅ `README.md` and `llms.txt`: `fromEnvironment()`'s fourth variable, and `-D` precedence

**Severity:** low · **Confidence:** confirmed by trace.

**Where / Problem / Fix:**

- `README.md:556`: `fromEnvironment() // PROMETHEUS_PROXY, PROMETHEUS_AGENT_TOKEN, PROMETHEUS_PROXY_CERT`. With
  `PROMETHEUS_PROXY_CERT` set, the agent checks the certificate against `PROMETHEUS_PROXY_AUTHORITY`, or
  `prometheus-proxy` without it (`agent/EmbeddedAgentConfig.kt:300-302`), so a reader with a certificate issued to the
  proxy's real host name, who never learns of the fourth variable, fails the handshake. Fix: name
  `PROMETHEUS_PROXY_AUTHORITY` in the comment, and add a sentence on its default (as `llms.txt:63` has).
- `llms.txt:197`: "Precedence: CLI args > environment variables > `-D` system properties > config file values >
  built-in defaults." Only a `-D` given after the JAR (JCommander's `@DynamicParameter`, layered over the file in
  `common/BaseOptions.kt:753-760`) beats the file; a JVM system property (`java -D… -jar`) is in the
  `ConfigFactory.load()` fallback, which the file overrides. `README.md:920` says this right. Fix: "CLI args >
  environment variables > `-D` settings after the JAR > config file values > JVM system properties > built-in
  defaults."

**Tests:** none; prose.

**Resolution:** Fixed in #552. The README's example names `PROMETHEUS_PROXY_AUTHORITY`, with a sentence on its default, and
`llms.txt` gives the precedence with `-D` after the JAR above the file and JVM system properties below it.

### 42. ✅ `monitoring.md` and `docs/metrics-and-grafana.md`: a counter count, the alert list, and the chunk threshold

**Severity:** low · **Confidence:** confirmed by trace.

**Where / Problem / Fix:**

- `website/prometheus-proxy/docs/monitoring.md:320`: "The two `agent_filter_*` counters". There are three, with
  `agent_filter_fail_open_total`, all incremented only for a filtered path (`agent/AgentHttpService.kt:543-552`).
  Fix: "three".
- `docs/metrics-and-grafana.md:324-325`: the list of what `grafana/alerts.yml` covers omits `ProxyAgentAuthFailures`,
  `ProxyPathRejections`, `AgentDisconnected`, `AgentDiscoveryFailing`, and `AgentNotOnPrimary` (`grafana/alerts.yml:67,
  75, 102, 110, 120`). Fix: add refused tokens and paths, disconnects, failing discovery, and an agent off its primary.
- `docs/metrics-and-grafana.md:286`: "Large responses (> `chunkContentSizeKbs`) are split into chunks". The test is the
  gzipped size, `>=` (`common/ScrapeResults.kt:64`), as `architecture.md:75-76` says. Fix: "A response whose gzipped
  body reaches `chunkContentSizeKbs` (default 32 KB)".

**Tests:** `DashboardMetricNamesTest` could check that every `alert:` name in `grafana/alerts.yml` appears in
`metrics-and-grafana.md`.

**Resolution:** Fixed in #552: three `agent_filter_*` counters, the full alert list, and the chunk threshold as a gzipped body that
reaches `chunkContentSizeKbs`. The alert list stays descriptive, so no names test was added.

### 43. ⬜ The API summaries for maintainers describe 4.6.0: `docs/packages.md`, `ConfigLoadException`'s KDoc, and `docs/KDOC_SUMMARY.md`

**Severity:** low · **Confidence:** confirmed by trace.

**Where / Problem / Fix:**

- `docs/packages.md:39`: "[EnvVars] — typed enum of every recognized environment variable." The four `PROMETHEUS_*`
  variables `fromEnvironment()` reads (`agent/EnvironmentSetting.kt:31-34`) aren't in it. Fix: "the environment
  variables the proxy and agent read themselves (not the `PROMETHEUS_*` ones `fromEnvironment()` reads for the app)".
- `docs/packages.md:120-123` and `common/ConfigLoadException.kt:20-28`: thrown when a config can't be loaded or holds a
  bad setting "and the process was started with `exitOnMissingConfig = false`". The builder's own methods throw it as
  they're called (`agent/EmbeddedAgentConfig.kt:351, 412, 430, 438, 440, 497`, via `invalid()`), as does the Ktor
  plugin's config for a registry with no path (`ktor-plugin/.../PrometheusProxyAgentConfig.kt:91`), and the KDoc's list
  of rejected settings omits a malformed proxy endpoint, a path with a refused character, and a PEM that can't be read
  or is given with a file too, which `Agent.startAsyncAgent`'s KDoc lists (`Agent.kt:1047-1053`). Fix: name
  `startAsyncAgent(config)`, `EmbeddedAgentConfig.start()`, and the builder methods, and complete the list.
- `docs/KDOC_SUMMARY.md:9-10, 49-51, 57-61`: omits `MetricsSource`, the builder's `dashboardPort`, `fromEnvironment()`,
  `source()`, `agentMetricsAt()`, `dashboard()`, `dashboardLogin()`, `startIfConfigured()`, and `EmbeddedAgentInfo.close()`;
  `:181-186` describes `io.prometheus.proxy` and `io.prometheus.agent` as documenting their services, which are
  `internal`. Fix: bring the entries up to the 4.7.0 API, and describe the packages as `packages.md:73-75, 83-85` does.

**Tests:** none; prose.

### 44. ⬜ `AGENTS.md` and `docs/TESTING.md` name a docs.yml deploy check that's gone, and describe `make all-tests` without its TLA+ checks

**Severity:** low · **Confidence:** confirmed by trace.

**Where / Problem / Fix:**

- `AGENTS.md:302`: "`SiteVersionTest` pins `make site-version`, which `docs.yml`'s deploy check and `bin/docker-*.sh`
  read." `docs.yml` has no deploy check since #443 made deploys a `workflow_dispatch` from `make release`; nothing in
  `.github/workflows/` runs `site-version`. Its readers are `bin/docker-{proxy,agent}.sh` and every demo's
  `latest_release` (`demos/*/demo.sh`, e.g. `demos/basics/demo.sh:262`), which is what #25's gate depends on. Fix:
  "which the demos' `make up` and `bin/docker-*.sh` read".
- `docs/TESTING.md:52-53`: "`make all-tests` # Full suite: `make tests` + `make lincheck-tests` + `make
  container-tests`". `Makefile:357` adds `tla-checks-full` (#508). `TESTING.md:57-58` lists `ktor-demo-check` and
  `plain-kotlin-demo-check` as CI's consumer-check builds, but not `spring-boot-demo-check` (`Makefile:396-397`, in
  `CI_JOBS`). Fix: add both.

**Tests:** `TestingGuideTest` could check that each `make` target `docs/TESTING.md` lists exists and that every target in
`CI_JOBS` is named there.

### 45. ⬜ `docs/DESIGN.md` and `docs/PRODUCT.md`, the dashboard's design and product contracts, no longer match what shipped

**Severity:** low · **Confidence:** confirmed by trace.

**Where / Problem / Fix:**

- `docs/DESIGN.md:447, 983-984` ("There are no shadows in this system. Not one `box-shadow` declaration exists") and
  `:244-245, 481-482, 492-494, 985` (only `0`, fully round, and the tabs' top corners). `common/dashboard/DashboardCss.kt:252`
  has `.ptable td.links a.icon:hover { box-shadow:inset 0 0 0 1px var(--ink-3) }` (which `DESIGN.md:781` itself calls a
  ring), and `:106`, `:114` give Log out and the Theme picker `border-radius:3px`. `.claude/rules/dashboard-constraints.md`
  makes this file the visual contract, so the next change is reviewed against rules the code breaks. Fix: name both
  exceptions, or set those rules to the contract.
- `docs/PRODUCT.md:66-71` (three layouts; the proxy has four, with Settings, `proxy/dashboard/ProxyDashboardHtml.kt:82-85`),
  `:136-137` ("There is no logo"; #488 added one, `DashboardLogo.kt`), `:131-132` (the brand mark is the string
  `prometheus-proxy`; it's the logo and `Proxy: host:port` / `Agent: name`), `:150-152` (three demos; there are seven),
  and `:59-61` (every setting reachable from CLI, env, file, and defaults; `proxy.auth`, `agent.filters`,
  `agent.pathConfigs`, `proxy.dashboard.links`, and `agent.discovery` are config-file only, as `README.md:643-644, 698,
  1052` say). Fix: update each.

**Tests:** none; prose.

### 64. ✅ The Agent Configuration page lists `--proxy` and `PROXY_HOSTNAME` under `agent.proxy.hostname`, "used when `endpoints` is empty", but both replace `endpoints`

**Severity:** low · **Confidence:** confirmed by trace

**Where:**

- `website/prometheus-proxy/docs/configuration/agent.md:157`: the Proxy Connection row gives `agent.proxy.hostname`
  over `--proxy, -p` and `PROXY_HOSTNAME`, described as "The proxy's host, or `host:port`; used when `endpoints` is
  empty".
- `agent/AgentOptions.kt:631-640` (`proxyHostnameSpec`): `cliValue.ifEmpty { envValue ?: proxy.endpoints.ifEmpty {
  [proxy.hostname] } … }`.

**Problem:** The page's tables give a key over its option and variable as one setting in precedence order (`:9-11`), so
the row says all three are used only when `endpoints` is empty. That holds for the key alone. The option and the
variable are read before `endpoints` and, when given, replace it whatever it holds. Both also take a comma-separated
failover list, which the row doesn't say.

The same page says so 30 lines down (`:188-189`), and so do the CLI Reference's row and note
(`cli-reference.md:115,120-121`), so the row contradicts its own page. An operator who keeps `endpoints` in the agent's
config, reading the row as "the variable matters only without `endpoints`", loses the list without a warning. Under
Helm it never applies: the agent chart always sets `PROXY_HOSTNAME`, from its required `proxy.hostname` value
(`charts/prometheus-agent/templates/deployment.yaml:70-71`).

**Fix:** Describe the row as "The proxy's host, or `host:port`, used when `endpoints` is empty. `--proxy` and
`PROXY_HOSTNAME` also take a comma-separated list, and replace `endpoints` ([below](#proxy-failover))".

**Tests:** None; this is a description. #66's checks pin the row's option and variable, not its wording.

**Resolution:** Fixed in #552, with the description this finding gives.

### 65. ✅ Two diagrams' descriptions break a hyphenated name across lines, so a screen reader reads "agent- west-2" and "the prometheus- agent chart"

**Severity:** low · **Confidence:** confirmed by trace: Mermaid keeps the line break of a multi-line `accDescr` (it
drops only the indentation after it) in the SVG's `desc`, which `diagrams.js` copies into the hidden description, where
the break is whitespace. Any handling that kept these words joined would also join every other line break's two words
("primary" and "links" in the Network Demo's), which the other descriptions rely on.

**Where:**

- `website/prometheus-proxy/docs/basics-demo.md:18-19`: "three agents, agent-east, agent-west-1, and agent-" / "west-2,
  with dashboards on 8095 to 8097".
- `kubernetes-demo.md:22-23`: "behind the firewall with nothing exposed, the prometheus-" / "agent chart, dashboard
  8295".
- `javascripts/diagrams.js:31` (`svgText`) and `:49-55` (`describe`) put the `desc` text in the paragraph the
  diagram's button names with `aria-describedby`.

**Problem:** #539 gave each diagram a text equivalent. In these two, a wrapped source line splits a name at its hyphen,
so the description reads "agent- west-2" (announced as "agent dash west 2" or "agent west 2", not as the container
the rest of the page calls `agent-west-2`) and "the prometheus- agent chart" for the `prometheus-agent` chart.

**Fix:** wrap both lines before the name instead: "and" / "agent-west-2, with dashboards", and "the" /
"prometheus-agent chart". A `misc/` check that no line in an `accDescr { … }` block ends with a letter and `-` would
keep it so.

**Tests:** that check, which fails on today's two pages.

**Resolution:** Fixed in #552. Both lines wrap before the name. `DocsCodeBlocksTest` fails on a line of an `accDescr` that ends
with a word broken at its hyphen, as both pages' did.

### 69. ✅ 4.7.0's changelog and release notes describe the home page #546 replaced, and nothing records what #543, #546, #548, and #549 changed

**Severity:** low (found by all three third-pass reviews) · **Confidence:** confirmed from the files. In
`4830930e..5c9f0a24` only #547 touched `CHANGELOG.md` (`:249-250`), and no commit touched `RELEASE_NOTES.md`.

**Where:**

- `CHANGELOG.md:327-330` (4.7.0, Documentation, from #539): "The docs site's home page says what the project does in
  one line, draws it in another (Prometheus and the proxy on one side of the firewall, the agent on the other), gives
  the Docker commands to try it, a block for each of the three terminals they run in, and offers three ways on: a
  demo, the Quick Start, and onboarding a team. It dropped its list of eleven benefits, its use cases, and its six
  cards, and its JAR tab, which ran a jar it never downloaded".
- `RELEASE_NOTES.md:175` (4.7.0, The docs site): "Its home page is short: what the project does, the Docker commands
  to try it, and three ways on."
- `website/prometheus-proxy/docs/index.md` at `5c9f0a24` (#546): How It Works, with the firewall diagram and the two
  components (`:16-63`); Benefits, its eleven bullets (`:65-85`); Use Cases, seven rows (`:87-97`); and Next Steps,
  whose three ways on put Install it first (`:99-119`). It has no Docker command and no flow line, and it's the
  sidebar's first item, Home (`zensical.toml:35`).
- `CHANGELOG.md:364`, an older entry in the same section: "The basics demo's tour has a tenth part: `./demo.sh
  login`". #483 then added "A refused path, a lost proxy" before it, so login is the eleventh of `TOUR_PARTS`, as the
  README's demos table counts them ("11 parts").
- The commits with no entry in either log:
  - **#543:** each demo's URL table, in its README and on its page, has a Port variable column; the paragraphs on
    moving a taken port share one wording; the nginx demo's part 2 says who its legacy agent is for.
  - **#546:** the home page is its own nav item, Home, and every reader-facing heading is title case: the site's, the
    README's, the demo and chart READMEs', and `SECURITY.md`'s.
  - **#548:** every numbered tour step shows something (166 steps became 134); the Spring Boot demo's part 4 shows
    Actuator reporting the agent UNKNOWN and the roastery UP while the proxy is away; `make up`, `make status`, and
    the tours point at each demo's page on the docs site rather than at README files.
  - **#549:** each demo's README and page has a What This Demo Shows table, a row per tour part with what it shows and
    why, which `make up` and `make tour` link to; the pages' What You Need sections are gone, and the Demos page says
    once what every demo needs.

**Problem:** the 4.7.0 GitHub release is written from these sections (`docs/RELEASE.md` step 7). As they stand, it
tells readers the home page has Docker commands to try the proxy, which it doesn't, and that the benefits and use
cases are gone, which #546 brought back. And the section, which has an entry for every earlier docs and demo change
since 4.6.0, says nothing of the demos' tour tables, their links to the docs site, or the port variables. 4.7.0 isn't
tagged, so all of it can still be fixed before it's published.

**Fix:**

- **The CHANGELOG's home-page entry:** "The docs site's home page is the sidebar's first item, Home. It keeps its
  diagram, benefits, and use cases, and ends with three ways on: installing it, a demo, and onboarding a team. Its
  Quick Start tabs are gone, whose CLI tab ran a jar it never downloaded, and so are its six cards. Every heading on
  the site and in the READMEs is title case."
- **The release notes' home-page sentence:** "Its home page shows how the proxy works and ends with three ways on:
  install it, try a demo, or onboard a team."
- **The older entry:** "has a tenth part" becomes "ends with a part on the dashboard's login".
- **New CHANGELOG entries:**
  - "Each demo's README and page has a What This Demo Shows table, a row for each part of its tour, with what it shows
    and why, linked from `make up` and `make tour`. The demos point to their pages on the docs site, where they named
    README files, and the pages drop their What You Need sections, which the Demos page says once."
  - "Every numbered step of the demos' tours shows something. The Spring Boot demo's part 4 shows Actuator reporting
    the agent UNKNOWN and the roastery UP while the proxy is away."
  - "Each demo's table of URLs names the variable that moves each port."
- **A new release-notes bullet:** "**The demos' tours.** Each demo opens its tour with a table of what each part shows
  and why, every step shows something, and `make up` links to the demo's page on the docs site."

**Tests:** none; prose.

**Resolution:** Fixed in #552, with the wording this finding gives: the home-page entry and sentence, the login part, three new
CHANGELOG entries, and a release-notes bullet for the demos' tours.

### 70. ⬜ #546 removed the home page's version and flow line, but two configs still name them: the publishing-release skill lists `index.md` as a version file, and `.impeccable/config.json` exempts all of `extra.css` for `.pp-flow__wall`

**Severity:** low (the skill's part found by all three third-pass reviews) · **Confidence:** confirmed from the files.

**Where:**

- `.claude/skills/publishing-release/SKILL.md:31` lists
  `website/prometheus-proxy/docs/{getting-started,index,docker}.md` for "Docker pull/run examples", and `:33` lists
  `website/prometheus-proxy/docs/{index,grafana}.md` for the release tag in the `raw.githubusercontent.com` links.
  `:39` says `RELEASE_VERSION_FILES` in the Makefile "lists the same files … keep it in step".
- #546 took `index.md` out of `RELEASE_VERSION_FILES` (`Makefile:52-55`) and out of `docs/RELEASE.md` step 2
  (`:52-56`), since the page names no version now. Step 2's last bullet (`:75-78`) and the Makefile's comment
  (`:50-51`) ask for the skill to move with them. The skill is in `RELEASE_VERSION_FILES`, but `release-check` only
  checks that it names the version, which it does.
- `.impeccable/config.json:22-30` ignores the `side-tab` rule for every value (`"value": "*"`) in
  `website/prometheus-proxy/docs/stylesheets/extra.css`, because "The home page's `.pp-flow__wall` is an empty divider
  drawn as the firewall …". #546 deleted `.pp-flow__wall` and the rest of the `pp-flow` rules, and `extra.css` and
  `diagrams.css` now have no `border-left`, `border-right`, or `border-inline-start` at all.
- `extra.css:405-406` keeps the intro to the deleted lede and result rules ("The home page reads as a group per
  section, each set close within …"), and no rule follows it but the `.pp-ways` rules, which have a comment of their
  own.

**Problem:** the next version bump, by a maintainer or an agent following the skill, looks for a version in a page
that has none, and the skill's claim that the Makefile lists the same files is false. No check compares the three
lists, so the next time they part, a file can be left out instead. The design checker's exemption covers nothing
today, but it would hide a real side-tab accent added to `extra.css` later.

**Fix:** `{getting-started,docker}.md` on the skill's `:31`, and `grafana.md` alone on `:33`. Delete the
`.impeccable/config.json` entry, and the orphaned comment in `extra.css`.

**Tests:** a `misc/` spec that expands the website paths the skill and step 2 name and compares them with
`RELEASE_VERSION_FILES` as `make -s` prints it, the way `SiteVersionTest` runs `make site-version`. It fails today on
`index.md`.

### 71. ✅ The Kubernetes demo's page and README put `KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` under "set its variable for the `make up` that creates the clusters … You set it once", but nothing reads it there or keeps it

**Severity:** low · **Confidence:** confirmed by trace.

**Where:**

- `website/prometheus-proxy/docs/kubernetes-demo.md:82`, and `demos/kubernetes/README.md:63` (#543): the URL table's
  Agent dashboard row names `KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` in the new Port variable column.
- The paragraph under each table, `kubernetes-demo.md:87-92` and `README.md:70-75`: "if one is already taken, set its
  variable from the table for the `make up` that creates the clusters … You set it once: every command after reads the
  ports back from the running cluster". Before #543 the paragraph named only the three cluster variables.
- The paragraph about the agent's dashboard, `kubernetes-demo.md:97-99` and `README.md:80-85`:
  "`KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` moves it without recreating a cluster". Neither says how long the setting
  lasts.
- `demos/kubernetes/demo.sh:68` reads the variable from the environment on each run, with 8295 as the default.
  `check_up` (`:452-458`) only checks that it's a number. The cluster doesn't hold it, since `pp-target` publishes no
  port. The demo has no `docker/.env` and saves no variable. The other three ports come back through `cluster_port`
  (`:58-60`).
- The port is read again by `./demo.sh agent-dashboard` (`:597-613`), by `print_urls` (`:145`), and by the tour's
  part 2 and part 4 URLs (`:1016-1056`).

**Problem:** suppose 8295 is taken and a reader follows the table and the paragraph under it:
`KUBERNETES_DEMO_AGENT_DASHBOARD_PORT=9295 make up`. `make up` prints 9295, then forgets it.
`./demo.sh agent-dashboard` tries 8295 again and fails with "if port 8295 is taken, set
KUBERNETES_DEMO_AGENT_DASHBOARD_PORT to another". `make tour` sends the reader to `localhost:8295` in parts 2 and 4.
The other demos save every port variable in `docker/.env`, so there "you set it once" is true. Here it isn't, for
this variable alone.

**Fix:** either:

- **Docs:** make the paragraph say "set its variable from the table, the agent dashboard's aside, for the `make up`
  that creates the clusters", and add to the agent dashboard's paragraph: "Nothing keeps it, so give it to each
  `./demo.sh agent-dashboard` and `make tour`, or export it in your shell." Make the same change on the page and in the
  README. Or:
- **Code:** have `demo.sh` save the variable in a git-ignored file beside the demo, the way the other demos'
  `save_ports` and `load_saved_ports` use `docker/.env`, so "set it once" holds for it too.

**Tests:** none for the docs fix, which is prose.

**Resolution:** Fixed in #552, in the docs: the page and the README set aside the agent dashboard's variable from "set it once",
and say nothing keeps it.

---

## 🧪 Tests

### 46. ⬜ No test covers `receiveCatchingWithin`, the AGENTS.md rule against losing an element, and the loss it prevents can be reproduced deterministically

**Severity:** low · **Confidence:** confirmed by grep; the loss reproduced with a scratch program against
kotlinx-coroutines 1.11.0

**Where:** `common/Channels.kt:25-35`. Its callers are `proxy/AgentContext.kt:228` (`readScrapeRequest`), `:305`
(`awaitPathNotices`), and `agent/AgentGrpcService.kt:842` (`streamOf`, the response streams). Nothing under `src/test`
names `receiveCatchingWithin`. No `ChannelsTest` exists, and no test sets up an element arriving just as a wait times
out:

- `AgentContextTest.kt:317-321` checks only an idle timeout with nothing queued, and a request queued before the wait.
- `AgentContextLincheckTest.kt:110` calls `readScrapeRequest()` with the default infinite idle, so the `onTimeout`
  clause never runs.
- The keepalive tests in `ProxyServiceImplTest.kt:1041-1130` and `AgentGrpcServiceTest.kt:840-860` check that a
  keepalive is sent when nothing comes, not that a message arriving as the wait ends is kept.

**Problem:** AGENTS.md says every wait that ends in a keepalive goes through `receiveCatchingWithin`, "never a receive
inside `withTimeoutOrNull`, which can take a channel's element as it times out and lose it". If someone rewrote the
helper, or one of its three callers, in the forbidden form, the whole suite would still pass. Each kind of loss
shows up only in production:

- A scrape request stays queued with its signal gone, so the scrape times out.
- A path notice waits for the next one.
- A scrape result is dropped.

A scratch program (not kept) shows the loss is easy to pin deterministically. It works on
`runBlocking`'s single-threaded event loop, whose timeouts fire on `DefaultExecutor`:

1. Start a waiter with a 100 ms timeout, and `yield()`.
2. `trySend(1)` while holding the loop's thread, so the waiter's resumption is queued but doesn't run.
3. `Thread.sleep(300)`, so the timeout fires meanwhile.
4. `await()` the waiter.

Run five times each:

- `withTimeoutOrNull { receiveCatching() }` returned `(got=null, left=null)` every time: the element was lost.
- `receiveCatchingWithin` returned `(got=1, left=null)` every time.

`ProxyPathManagerTest.kt:138-139` (`waitingNotices`) wraps `awaitPathNotices()` in `withTimeoutOrNull(200.milliseconds)`.
That is the forbidden form, in a test helper. A lost signal there leaves the notices pending rather than lost, so the
cost is a possible flake, not a missed bug. But it also means the helper never runs the timeout branch that
`awaitPathNotices(timeout)` has.

**Fix:** Add `common/ChannelsTest.kt` with the recipe above. Assert the element is returned, and that the channel is
empty afterwards. Also add one test at each caller's level:

- `AgentContextTest`: `readScrapeRequest(100.milliseconds)` returns the wrapper that `addToScrapeRequestQueue` queued
  while the loop's thread was held.
- `AgentContextTest`: the same for `awaitPathNotices(100.milliseconds)` and `queuePathNotice`.

Change `waitingNotices` to call `context.awaitPathNotices(200.milliseconds)`.

**Tests:** The three tests above. Each fails once its subject is rewritten as `withTimeoutOrNull { receiveCatching() }`.

### 47. ✅ The starter's required-agent tests all fail before `launch()` picks `start()`, so a required agent started with `startIfConfigured()` passes them

**Severity:** low · **Confidence:** confirmed by trace

**Where:** `spring-boot-starter/src/main/java/io/prometheus/agent/spring/PrometheusProxyAgent.java:93-95` chooses
`config.start(false)` for a required agent and `startIfConfigured(false)` for any other. The four `required=true`
tests in `spring-boot-starter/src/test/java/io/prometheus/agent/spring/PrometheusProxyAgentAutoConfigurationTest.java`
never reach that choice:

- `:214` fails in `withoutAProxy()`.
- `:225` (`aRequiredAgentWithABadSettingFailsTheApp`) sets `paths[0].path=`. The builder rejects that inside
  `config()` (`agent/EmbeddedAgentConfig.kt:430`, `invalid("A path is required")`).
- `:293` and `:306` throw from a customizer, which also runs inside `config()`.

The settings that only the agent rejects as it starts, `settings.scrapeTimeoutSecs=soon` (`:255`) and an unreadable
certificate (`:321`), are tested only without `required`.

**Problem:** The class Javadoc, AGENTS.md, and the starter's docs say a required agent's start failure fails the app. If
`launch()` always called `startIfConfigured(false)`, a required agent the agent rejects would leave the app running
with no agent, and every starter test would pass. The Ktor plugin does pin its counterpart, in
`PrometheusProxyAgentPluginTest`'s "a required agent with a setting it rejects fails the app's start", whose bad PEM
the builder accepts.

**Fix:** Add `aRequiredAgentTheAgentRejectsFailsTheApp`, with `required=true`, a proxy, and a `trust-certificate` that
isn't PEM. Assert that the context fails with a `ConfigLoadException` root cause.

**Tests:** That test fails with `launch()` changed to always use `startIfConfigured`.

**Resolution:** Fixed in #562. `aRequiredAgentTheAgentRejectsFailsTheApp` starts a required agent with a proxy and a `trust-certificate` that isn't PEM, which the builder accepts and the agent rejects as it starts, and asserts that the context's startup failure has a `ConfigLoadException` in its cause chain, the root cause being the `IllegalArgumentException` it wraps. With `launch()` changed to always call `startIfConfigured()`, it's the only starter test that fails.

### 48. ✅ Two of a `MetricsSource`'s isolation promises have no test: a `VirtualMachineError` propagates, and a stuck call holds neither another path's scrape nor the agent's shutdown

**Severity:** low · **Confidence:** confirmed by trace

**Where:** In `agent/AgentHttpService.kt`:

- `sourceFailure` (`:753-777`), whose `thrown is VirtualMachineError -> thrown` branch is at `:766`;
- `sourceScope` (`:153`), a `SupervisorJob` on `Dispatchers.IO`;
- `close()` (`:650`).

The tests are in `agent/MetricsSourceTest.kt`:

- `:272-314`, the stuck-source test;
- `:318` and `:328`, which test only `NotImplementedError`.

**Problem:** AGENTS.md and `MetricsSource`'s KDoc say three things:

- anything a source throws fails only its scrape, except a `VirtualMachineError`;
- a call that ignores the timeout's interrupt "holds only its own thread";
- the test's own comment adds that it mustn't hold "the agent's other scrapes, or its shutdown".

Today:

- **`VirtualMachineError`:** Delete the `:766` branch, and the next one wraps an `OutOfMemoryError` in an `IOException`,
  which becomes a 503 rather than propagating. No test throws a `VirtualMachineError` from a source. The OOM tests in
  `AgentHttpServiceTest.kt:1631-1674` cover only the URL path.
- **Stuck source:** The test checks four things: the scrape times out on time, the second call is refused by
  `stuckSources` (before any dispatch), the source works once released, and the release happens before
  `service.close()`. So two regressions would still pass:
  - `sourceScope` on a bounded dispatcher (`Dispatchers.IO.limitedParallelism(1)`), which starves a second path's
    source behind the stuck one;
  - a `close()` that joins `sourceScope`'s children, which hangs shutdown.

**Fix:**

- Add `shouldThrow<OutOfMemoryError> { scraped { _, _, _ -> throw OutOfMemoryError("x") } }`, and the same for
  `StackOverflowError`.
- In the stuck-source test, register a second source path, and require a 200 from it, well within the timeout, while the
  first is stuck.
- Call `service.close()` before the release, and require it to return promptly.

**Tests:** The `OutOfMemoryError` test fails without the `:766` branch. The second-path check fails under the bounded
dispatcher. The `close()` check fails under a joining `close()`.

**Resolution:** Fixed in #555. `MetricsSourceTest` checks that a source throwing `OutOfMemoryError` or `StackOverflowError` lets it propagate; that while one source is stuck past its timeout, another path's source answers 200; and, in a test of its own, that `close()` returns within 5 seconds with the source still stuck. Each failed with the code broken: the `VirtualMachineError` branch dropped, sources on `limitedParallelism(1)`, and a `close()` that joins the source scope.

### 49. ✅ `ProxyPathManagerLincheckTest` never runs the takeover and reclaim code under contention: its agents share one launch, none reads path notices, and none reclaims

**Severity:** low · **Confidence:** confirmed by trace. The gap is confirmed; the impact is limited, since the code
runs under the `pathMap` lock.

**Where:** `proxy/ProxyPathManagerLincheckTest.kt`:

- `:93-107`: every agent is built with `registerAgentRequest { this.consolidated = … }`, so `assignProperties`
  (`proxy/AgentContext.kt:181`) gives all five the same `launchId` (`""`) and `acceptsPathNotices = false`.
- `:110-117`: `register` passes `PathMetadata(identityName = …)` and never sets `reclaim`.

The spec was last changed in #422, before #499's takeover notices and reclaim.

**Problem:** Every takeover in the spec is a same-launch takeover, so `settleDisplaced` (`proxy/ProxyPathManager.kt:607`)
always invalidates the displaced context. These parts of `ProxyPathManager` never run under contention:

- the other-launch branch, where the agent stays connected and is told;
- `tellHolder` and `queuePathNotice`;
- the `waiting` lists;
- `reclaimRejection` (`:490-515`).

The spec's header says it checks `ProxyRegistry.tla`'s invariants on the real code. It can't see a break of
`ReclaimNeverDisplaces`, or of the waiting-list bookkeeping. Only sequential unit tests cover those
(`ProxyPathManagerTest.kt:1873-2110`).

**Fix:**

- Give the two team-a exclusive agents different launch IDs, plus a same-launch stale copy of one of them, and set
  `pathNotices = true` on all of them.
- Add a `reclaim` operation that calls `addPath(…, PathMetadata(identityName, reclaim = true))`. Under a lock of the
  spec's own, record whether a valid agent of another launch held the path, and log a violation if the reclaim was
  accepted anyway, as `ScrapeResultOwnershipLincheckTest` does.
- In `@Validate`, check that:
  - there are no violations;
  - every `waiting` entry is valid, reads notices, and isn't the holder;
  - no `waiting` list holds more than `MAX_WAITERS_PER_PATH` agents.

**Tests:** The extended spec fails with `reclaimRejection`'s holder check removed (`make lincheck-tests`).

**Resolution:** Fixed in #559. The spec's seven agents include two team-a launches that read notices, a reconnected context of one, and one that predates notices. A `reclaim` operation records a reclaim that displaced a live agent of another launch, or one refused with no such holder. `@Validate` checks an invalidated agent's notices, notices to an agent that predates them, a notice about a path the agent serves, and each waiter's notices, launch, connection, and uniqueness. It failed for six breaks of main source, the holder check in `reclaimRejection` among them. "Every waiter is valid" was left out: an old context of a launch can wait on another path until its connection is removed, which is harmless.

### 50. ✅ A change no longer waking the dashboards' push loop fails no test: every push spec runs on a 1-second tick and waits 10 seconds or more

**Severity:** low · **Confidence:** confirmed by trace

**Where:** The code with no test:

- `common/dashboard/DashboardServer.kt:602-615`, where `site.changes` leads to `snapshots.invalidate()`, then
  `spaced(...)`, then `wake.trySend(Unit)`;
- `DashboardServer.kt:530-532`, where Log out wakes the loop so that session's pages close at once;
- `agent/dashboard/AgentDashboardService.kt:86`, `changes = agent.activityLog?.changes?.drop(1)`.

Every dashboard harness config sets `refreshIntervalSecs = 1`: `config/test-configs/web-ui.conf:16`,
`web-ui-paths.conf:33`, `web-ui-identity.conf:14`, and `agent-dashboard.conf:39`. The specs that describe a push
driven by a change wait 10 to 30 seconds for it, so the next tick delivers the frame anyway:

- `harness/ProxyWebDashboardTest.kt:195-263` and `:866-899`;
- `harness/AgentWebDashboardTest.kt:293-336`;
- `harness/ProxyWebDashboardLoginTest.kt:378-398` ("Log out wakes it at once").

The four cases that set `refreshIntervalSecs=60` (`ProxyWebDashboardTest.kt:604,638,791,827`) cover browser messages
and page GETs, not the wake.

**Problem:** AGENTS.md describes the agent's activity feed as the log "whose changes wake the push loop". None of these
changes fails a test:

- replacing `.collect { wake.trySend(Unit) }` with `.collect {}`;
- deleting Log out's `trySend`;
- replacing the agent's `changes` with `emptyFlow()`.

The last one also drops the agent's snapshot invalidation, so the agent's page GETs show a state up to an interval old.
The agent has no counterpart to the proxy's "a page GET after a topology change should show the change".

**Fix:** Run the push specs with `-D…dashboard.refreshIntervalSecs=60`, and wait well under that, as
`TOPOLOGY_PATIENCE` does:

- proxy: with a socket open, connect an agent and expect its frame within 10 s;
- agent: stop the proxy with the Activity page open, and expect the disconnected frame within 10 s;
- login: expect the socket to close within 5 s of Log out.

Add a unit check that the agent's `DashboardSite.changes` emits on `activityLog.record`.

**Tests:** Each of these fails under the matching change above.

**Resolution:** Fixed in #558, with a unit spec rather than a 60-second refresh in the harness specs. `DashboardPushTest` runs `DashboardServer` against a stand-in site with a one-hour tick, so a frame comes only from the wake under test, and a case fails within 5 seconds; removing the change wake fails five of its six cases, and removing Log out's close fails that case. A new `AgentDashboardServiceTest` case, through the now internal `site`, fails when the agent's `changes` is `emptyFlow()`.

### 51. ✅ The proxy's `scrape` and `forward` spans have no unit test: one successful single-agent harness scrape is all that covers them, and nothing pins that a client's trace headers are ignored

**Severity:** low · **Confidence:** confirmed by grep and trace

**Where:** The code:

- `proxy/ProxyHttpRoutes.kt:220-235` (`scrapeSpan`, which calls `newTrace`);
- `proxy/ScrapeDispatch.kt:155-178` (`forwardSpan`) and `:386-406` (`traceContextVal`).

The tests:

- No spec under `proxy/` uses `proxy.tracing` except `ProxyGrpcServiceTest.kt:377-400`, which covers the interceptor
  alone.
- `harness/InProcessZipkinTest.kt:96-135` makes one successful scrape through one agent, and asserts nothing about the
  `scrape` span's parent.
- No test anywhere sends `X-B3-*` or `traceparent`.

**Problem:** `ProxyHttpRoutes.kt:220-222` says the scrape span ignores a client's propagation headers, "so a client
can't make the proxy keep every trace" through the unauthenticated scrape port. A switch to extracting the headers
would pass every test. So would losing any of these:

- the `error` and `outcome` tags on a timed-out or disconnected scrape;
- one forward span per agent on a consolidated path, each carried in that agent's `ScrapeRequest.trace_context`;
- the escaping of the forward span's `agentId` and `agentName` tags;
- no forward span for a scrape without a trace (the dashboard's).

**Fix:** Add tests to `ScrapeDispatchTest`, with `every { proxy.tracing } returns` a collecting `ScrapeTracing`:

- a consolidated path with two agents gives two `forward` spans under one parent, and each agent's request names its
  own span;
- a 1 s timeout gives `error=timed_out`;
- `ScrapeSpec(trace = null)` records no span;
- an `agentName` containing `\n` comes out escaped.

Add one scrape sent with `X-B3-TraceId`, `X-B3-SpanId`, and `X-B3-Sampled: 1`, in `ProxyHttpRoutesTest` or the Zipkin
harness spec. Its `scrape` span must have no parent, and a new trace ID.

**Tests:** The cases above. Each fails when its behavior is removed.

**Resolution:** Fixed in #565. `ScrapeDispatchTest` stubs `proxy.tracing` with a collecting `ScrapeTracing` and checks one `forward` span per agent of a consolidated path under the scrape's span, each agent's request carrying its own span (checked by joining the request's context as the agent's fetch would); `outcome`/`error` = `timed_out`, with status 503, for a silent agent and `agent_disconnected` for one that's gone; no span and no context for a scrape without a trace; and an agent name with a line break cut and escaped. `ProxyHttpRoutesTest` sends a scrape with `X-B3-TraceId`, `X-B3-SpanId`, `X-B3-Sampled`, `X-B3-Flags`, `b3`, and `traceparent`: its `scrape` span has no parent, a new trace ID, and no debug flag, and at rate 0 no span is kept. Each failed against code changed to drop its behavior (no escaping, a span for an untraced scrape, a route that joins the request's headers). The timeout case uses a 200 ms `ScrapeSpec` timeout rather than 1 s, and a disconnected case was added.

### 52. ✅ No test pins AgentMetrics' `PER_LAUNCH` rule as a whole, so a new agent metric that breaks it keeps a stopped agent's series

**Severity:** low · **Confidence:** confirmed by trace

**Where:**

- `agent/AgentMetrics.kt:74-76`: `close()` removes series whose first label value is the launch ID, but only for
  metrics in `PER_LAUNCH`.
- `:322-350`: `COLLECTORS` is `PER_LAUNCH` plus six sampled gauges.
- `agent/AgentMetricsTest.kt:244-257`, `:293-307`, and `:311-336` check that series are removed for three metrics:
  `agent_dashboard_scrapes`, `agent_build_info`, and `agent_heartbeats`.

**Problem:** AGENTS.md says an agent metric takes `launch_id` as its first label and goes in `PER_LAUNCH`. Suppose a new
stateful metric goes in the sampled list by mistake, or puts another label first. It is still registered and exposed,
so the name checks and `DashboardMetricNamesTest` pass. But its series outlive a stopped agent for as long as another
agent in the JVM holds the shared collectors, as when an embedded host restarts its agent.

**Fix:** Expose `PER_LAUNCH` and `COLLECTORS` as `internal`, then assert two things:

- every collector's label names start with `launch_id`;
- every collector that isn't a `GaugeWithCallback` is in `PER_LAUNCH`.

**Tests:** The new `AgentMetricsTest` case. It fails if, say, `SCRAPE_QUEUE_WAIT` moves to the sampled list.

**Resolution:** Fixed in #555. A new `AgentMetricsTest` test reads `AgentMetrics.COLLECTORS`, and each metric's declared label order, by reflection, since this step changed no main source. It checks that every agent metric takes `launch_id` first, and that closing one agent's metrics removes its series from every family while keeping another's. It failed for a metric moved to the sampled gauges, one with another label first, and a `close()` that cleared every agent's series.

### 53. ✅ Nothing tests that an embedded agent leaves the JVM metric groups to its host

**Severity:** low · **Confidence:** confirmed by trace

**Where:** `Agent.kt:288-290` (`registersJvmMetrics = !isMetricsEnabled && !isEmbedded && …`) and `:874-877` (the
`startUp` call at `:876`). Every `*ExportsEnabled` defaults to false (`config/config.conf:233-241`). Only the
standalone `harness/AgentMetricsProxyPathTest.kt:62-63` turns any on.

**Problem:** AGENTS.md says an embedded agent's proxy path serves only its `agent_*` series, the JVM's being its
host's. Drop `!isEmbedded`, and an embedded agent with `agentMetricsAt` and, say,
`setting("metrics.standardExportsEnabled", true)` registers `SystemMetrics`' groups in the host's default registry.
There they can collide with the host's own JVM collectors. No test fails: `EmbeddedAgentApiTest` checks
`shouldNotContain "jvm_"` at the agent's path, but with every flag off nothing is registered anyway.

**Fix:** Add an embedded case with `agentMetricsAt` and `standardExportsEnabled = true`. First clear the default
registry and call `SystemMetrics.forget`, as `AgentMetricsProxyPathTest` does. After the start, require that the
registry gained no `process_` or `jvm_` family.

**Tests:** That case fails without `!isEmbedded`.

**Resolution:** Fixed in #555, in a unit spec rather than the harness spec named here. A new `EmbeddedAgentConfigTest` test starts an agent embedded, with `agentMetricsAt` and two JVM groups turned on, and finds no `jvm_` or `process_` family in the default registry. An agent built directly from the same settings adds them, so the check can fail; it did with `!isEmbedded` dropped from `registersJvmMetrics`.

### 54. ✅ The Ktor plugin's wait on `ApplicationStopping` for the agent's stop has no deterministic test

**Severity:** low · **Confidence:** plausible. Removing the join makes one test racy rather than failing.

**Where:** `ktor-plugin/src/main/kotlin/io/prometheus/agent/ktor/AgentLifecycle.kt:130-137` (`finishStop`, whose
`thread.join` is at `:134`). The tests are in `ktor-plugin/src/test/kotlin/io/prometheus/agent/ktor/AgentLifecycleTest.kt`:

- `:143` releases the stop's latch, raises `ApplicationStopping`, and then verifies only that `shutdown()` was called,
  which had already happened.
- `:187` checks that the wait gives up after its timeout. It also passes with no join at all.
- `:164` verifies `shutdown()` right after raising the event. Without the join, that races the stop thread.

**Problem:** AGENTS.md says to keep the split: the stop begins on `ApplicationStopPreparing` on a thread of its own, and
is waited for on `ApplicationStopping`. The first half is tested. If the join is removed, the engine can finish
disposing while the agent is still stopping, and the only sign is an occasional failure in `:164`.

**Fix:** Make the mocked `shutdown()` block on a latch that a helper thread counts down after about 200 ms, and set a
flag when it returns. Then assert the flag is set as soon as `events.raise(ApplicationStopping, app)` returns.

**Tests:** That test fails every time once the join is removed.

**Resolution:** Fixed in #562. `AgentLifecycleTest`'s "the app's disposal waits for the agent's stop the engine began" holds the mocked `shutdown()` on a latch that a helper thread opens only once the disposing thread is inside `Thread.join` (or after 5 seconds), and asserts that the stop finished as soon as `ApplicationStopping` returns. With the join removed, it failed in 3 of 3 runs, and every other case passed.

### 55. ✅ `dashboard-constraints.md` says `AgentDashboardHtmlTest` pins the column-guide rule, but it pins neither half, and the agent page's live region has no positive test

**Severity:** low · **Confidence:** confirmed by trace

**Where:** The claim is `.claude/rules/dashboard-constraints.md:17`: "`PathTableHtmlTest`, `ActivityHtmlTest`, and
`AgentDashboardHtmlTest` pin both". In `agent/dashboard/AgentDashboardHtmlTest.kt`:

- `:354-369` checks only that the page holds `<dt>Time|Event|Path|Detail</dt>`;
- no assertion looks at a `<th>`;
- the Activity push (`:386-395`) never checks that it leaves out `COLUMN_GUIDE_ID`;
- `ANNOUNCER_ID` appears once, as a `shouldNotContain` on the Settings push alone (`:420`).

The main code is `agent/dashboard/AgentActivityHtml.kt:64,70,81` and `AgentDashboardHtml.kt:151`.

**Problem:** None of these changes fails a test:

- moving `renderFilters` inside the pushed `activityTable`, so the guide closes on every frame;
- building the header from `COLUMNS.dropLast(1)`, so the guide and the header disagree;
- deleting `announcer()`;
- moving `announcer()` into a pushed region.

The proxy's pages pin all of these (`ActivityHtmlTest.kt:172,192` and `ProxyDashboardHtmlTest.kt:343,357`), and the
rule file says the agent's page does too.

**Fix:** Add the tests below, or correct the rule's claim.

**Tests:** In `AgentDashboardHtmlTest`:

- the Activity page's `<th>` labels equal its guide's `<dt>` labels, in order;
- the guide comes after the search and before `id="activity"`, and no `AgentLayout` push contains `COLUMN_GUIDE_ID`;
- the page has `id=ANNOUNCER_ID` with `aria-live="polite"`;
- no push contains `ANNOUNCER_ID`.

**Resolution:** Fixed in #564. `AgentDashboardHtmlTest` now pins both halves of the column-guide rule and the live region, as the rule file said it did: the Activity page's `<th>` labels equal its guide's `<dt>` labels, in order; the guide follows the search and precedes `#activity`, and no view's push carries it; every view's page has the announcer, with `role="status"` and `aria-live="polite"`; and no push, for any view or none, carries it. Each of those four regressions fails a test, and so does sending the announcer in every push. The rule file's live-region bullet now names `AgentDashboardHtmlTest` too.

### 56. ✅ Two more `dashboard-constraints.md` rules have no test: text tokens on `--surface-2`, and `agentLabel` in the proxy's Activity view

**Severity:** low · **Confidence:** confirmed by trace (contrast computed from `DashboardCss.TOKENS`)

**Where:**

- `common/dashboard/DashboardThemeTest.kt:69-77` is the only contrast assertion, and it checks `--ink-2` on
  `--surface-3`.
- `proxy/dashboard/ActivityHtml.kt:152` names an agent through `agentLabel`, but `proxy/dashboard/ActivityHtmlTest.kt`
  has no "Unassigned" case.

**Problem:**

- **Contrast.** The rule says to check colors against `--surface-2`, which is where all 29 contrast failures fixed in
  4.0.1 were. It gives 4.59:1 as the measured AA floor. Today's margins are thin:
  - light theme: `--ink-3` 4.59:1, `--ok` 4.57:1;
  - dark theme: `--crit` 4.77:1, `--ink-3` 4.88:1.

  A token change that takes one under 4.5:1 fails nothing.
- **Activity labels.** An announced agent that leaves before `registerAgent` records its departure as "Unassigned"
  (`Proxy.kt:562-565`). Rendering the raw name in `ActivityHtml` fails no test. The agent and path views pin this
  (`ProxyDashboardHtmlTest.kt:174,412` and `PathColumnsHtmlTest.kt:109`).

**Fix:**

- Reuse `DashboardThemeTest`'s `tokenValues` and `contrast` to require that `--ink`, `--ink-2`, `--ink-3`, `--ok`,
  `--warn`, and `--crit` each clear 4.5:1 on `--surface-2`, in all three token blocks (light, dark, and set to dark).
  This passes today.
- Assert that an `ActivityEntry` with `agentName = "Unassigned"` renders `agentLabel("Unassigned")`.

**Tests:** The two checks above.

**Resolution:** Fixed in #564. `DashboardThemeTest` requires `--ink`, `--ink-2`, `--ink-3`, `--ok`, `--warn`, and `--crit` to clear 4.5:1 on `--surface-2` in all three token blocks, reusing its `tokenValues` and `contrast`; it passes today, and lightening light `--ink-3` or darkening dark `--crit` fails it. `ActivityHtmlTest` requires an `Unassigned` agent to render as `agentLabel("Unassigned")`, with no placeholder in the push; rendering the raw name fails it. The rule file names both tests.

### 57. ✅ "fromEnvironment reads the process's environment" can't fail where the tests run

**Severity:** low · **Confidence:** confirmed by trace

**Where:** `agent/EmbeddedAgentConfigTest.kt:433-438`.

**Problem:** The test compares `fromEnvironment()` with `fromEnvironment { System.getenv(it) }`, in an environment that
sets none of the four variables, as its own comment says. Both give an empty builder, so a `fromEnvironment()` that
read nothing (`= this`) would pass too.

**Fix:** Set a harmless variable on the root `unitTest` task, `environment("PROMETHEUS_AGENT_TOKEN", "…")`. Nothing else
reads it, and with no `PROMETHEUS_PROXY` set, no agent starts. Then assert that the builder shows `agentToken=(set)`.
Otherwise, delete the test.

**Tests:** The changed test fails if `fromEnvironment()` doesn't read `System.getenv`.

**Resolution:** Fixed in #555. The test runs `fromEnvironment()` in a forked JVM, as `BaseOptionsTest` does, whose environment has all four `PROMETHEUS_*` variables, and checks that it builds what the lookup overload builds from them. It failed for a `fromEnvironment()` that read nothing. A variable set on the Gradle `Test` task would have reached every spec and child process, and IntelliJ's runner wouldn't set it.

### 58. ⬜ The config-key guards don't reach keys passed to `EmbeddedAgentConfig.setting()`, whose KDoc says they aren't checked, or the website's HOCON blocks

**Severity:** low · **Confidence:** confirmed by trace, and by a scratch checker showing that nothing has drifted yet

**Where:**

- `misc/ConfigFileKeysTest.kt:214` (`D_SETTING`) scans only `"-Dproxy.…=` / `"-Dagent.…=` literals, plus the `.conf`
  files in `CONFIG_DIRS` (`:196`).
- `misc/WebsiteConfigKeysTest.kt:90` checks the inline code in the website's prose, and `:100-109` the defaults its
  tables give. `misc/WebsitePages.kt:27-38` strips every fenced block first.
- `agent/EmbeddedAgentConfig.kt:508-521`: `setting(key, value)` "Keys aren't checked, so a misspelled one has no effect".
  It's documented on the website's Embedded Agent page (`embedded-agent.md:246-313`), and the starter's and the Ktor
  plugin's `settings.<key>` call it.

**Problem:** November #38 and #41 were both misnamed keys that did nothing silently. `ConfigFileKeysTest` was built to
stop that class of bug, but these places can still hold a misnamed key, with nothing failing:

- `setting("…", …)`: 14 distinct keys across the tests, `harness/support/EmbeddedAgentScrape.kt:91`, the website,
  README, and the Ktor demo's README;
- the website's 40 inline `hocon` blocks and the snippet-file sections they include (`src/test/kotlin/website/*.txt`),
  76 blocks in all.

A scratch checker extracted every one, ran the website blocks through the same walk as `ConfigFileKeysTest.undeclared`,
and checked the `setting()` keys against `config/config.conf`. All are valid today. The checker did flag a planted
`agent.foo` and `proxy.zipkin`.

**Fix:** In `ConfigFileKeysTest`:

- Collect the literal first argument of every `setting(` call (named `key =` or not) in `src/test`, the subprojects'
  tests, the website's pages and snippet files, `README.md`, and `demos/*/README.md`, and run each through
  `undeclaredIn("agent.$key = x")`. The deliberately bad keys in `EmbeddedAgentConfigTest.kt:371-373` need an exempt
  list.
- Parse each fenced `hocon` block on the website's pages, with its `--8<--` includes resolved, and run its
  `proxy`/`agent` trees through `undeclared`. Six blocks have another root, and each needs its own schema or an
  exemption: a discovery file's `paths` (the discovery schema `ConfigFileKeysTest` already builds), a lone
  `agent.filters` entry, `simple.conf`'s `proxyMetricsHost`, and the Ktor plugin's `prometheus-proxy` section.

**Tests:** The two new checks. Each fails on a planted misnamed key.

### 59. ⬜ The `misc/` guards stop at the root project: `TestingGuideTest` and `TestPortsTest` never read `ktor-plugin/` or `spring-boot-starter/` tests

**Severity:** low · **Confidence:** confirmed by trace

**Where:**

- `misc/TestingGuideTest.kt:44,58,70-71` walks `src/test/kotlin` for specs and `src/test` for support files.
- `common/TestPortsTest.kt:82` walks `src/test`.
- The starter's `PrometheusProxyAgentIntegrationTest.java:63-64` and the plugin's
  `PrometheusProxyAgentIntegrationTest.kt:102-103` bind the literals 11400-11403. A comment says `TestPorts` lists
  them (`common/TestPorts.kt:290-300`).

**Problem:** AGENTS.md says `TestingGuideTest` fails when a spec isn't in `docs/TESTING.md`, and that `TestPortsTest`
keeps every bound port in `TestPorts`. Neither holds for the two subprojects:

- **Specs:** `docs/TESTING.md` names every subproject spec today, but only by hand. A new `ktor-plugin` spec without an
  entry fails nothing.
- **Ports:** The subprojects' integration tests start a proxy on fixed ports while the root's `harnessTest` runs
  beside them. If a subproject port were edited to a harness port, or a new one added outside `TestPorts`, nothing
  would catch it before an intermittent `BindException`.

**Fix:**

- Have `TestingGuideTest` also walk `ktor-plugin/src/test` and `spring-boot-starter/src/test`, and require each spec in
  its section (`### Ktor Plugin Tests (ktor-plugin/)` and the starter's). The section regex's `[a-z]+` needs to admit
  `-`.
- Have `TestPortsTest` scan both trees for port constants (`*_PORT = <n>` in Kotlin, `int *_PORT = <n>;` in Java), and
  require each to equal a `TestPorts` value.

**Tests:** The two extended checks. Each fails on an unlisted spec, or on a port that isn't in `TestPorts`.

### 66. ⬜ `WebsiteConfigKeysTest` checks the settings tables' keys and defaults, but not the option and variable columns #542 added, the anchors they link to, or the gRPC defaults, so any of those can drift silently

**Severity:** low · **Confidence:** confirmed by trace. A script that extracted every row found all of them correct
today: 171 rows name a key, 125 of them also name an option or a variable.

**Where:**

- `misc/WebsiteConfigKeysTest.kt:39-43`: `keyOfCell` takes only the key from a cell. The option and variable after its
  `<br>`s are read by nothing.
- `misc/WebsiteConfigKeysTest.kt:47-71`: `documentedDefaults` keeps a row's key and its Default cell, and nothing else.
- `misc/WebsiteConfigKeysTest.kt:75-85`: `declares` accepts any documented default when `config/config.conf` gives a
  tscfg spec (`TSCFG_SPEC`, `:117`), a list, or an object (`:83`), and `:133` exempts both `logLevel`s.
- `website/prometheus-proxy/docs/cli-reference.md:22-204`: each row's `<span id="<key>">` is the anchor that every linked
  key on `configuration/agent.md` and `configuration/proxy.md` targets (`../cli-reference.md#agent.scrapeTimeoutSecs`).
- `docs/TESTING.md:875-877` says the spec checks "every default the pages' tables give".

**Problem:** #542's tables are what an operator copies a flag or a variable from. Nothing fails when any of these goes
wrong:

- **Options and variables.** An option or short form misspelled, removed from the code, or put on the wrong row passes.
  So does a variable: `--chunks` for `--chunk`, the proxy's `-p` on an agent row, `MAX_CACHE_SIZE` for
  `MAX_CLIENT_CACHE_SIZE`. That covers 125 rows on three pages. The reverse is unchecked too: an option or `EnvVars`
  entry the code adds and no table lists. Every one is right and complete today; I compared each role's alias sets
  with the `@Parameter` names of `BaseOptions` plus `AgentOptions` or `ProxyOptions`, and checked all 57 `EnvVars`
  entries.
- **Anchors.** Nothing ties a link's fragment, or a CLI Reference `<span id>`, to the row's key. A key renamed in one
  place leaves a dead link, and the site build doesn't catch it: `make build-site` (`Makefile:772-773`) runs
  `zensical build` with no strict flag, and `zensical.toml` sets no link validation.
- **gRPC defaults.** Every `proxy.grpc.*` timeout and `agent.grpc.keepAliveTime*` is `"long | -1"` in `config.conf`, so
  `declares` passes any value. That leaves 16 rows unchecked: 7 on `proxy.md` and 9 on the CLI Reference (120, 7200,
  20, 300, `INT_MAX`). The proxy's 7200 copied onto the agent's `keepAliveTimeSecs` row, whose gRPC default is off,
  would pass. Today all 16 match grpc-java 1.85.1: `ServerImplBuilder.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS`, `GrpcUtil`'s
  `DEFAULT_SERVER_KEEPALIVE_*` and `DEFAULT_KEEPALIVE_TIMEOUT_NANOS`, and `NettyChannelBuilder`'s disabled client
  keepalive.

**Fix:** In `WebsiteConfigKeysTest`, or a sibling `CliReferenceTest` named in `docs/TESTING.md`:

- **CLI Reference rows against the code.** Parse each role section into rows of key, option alias set, variable, and
  default, and require:
  - each alias set to equal the `names` of a JCommander `@Parameter` field on the role's options class or `BaseOptions`
    (reflection over `declaredFields`, as JCommander does);
  - each variable to be an `EnvVars` entry;
  - each `<span id>` to equal its row's Property cell;
  - every `@Parameter` and every `EnvVars` entry to appear in its role's section.
- **Configuration pages against the CLI Reference.** Each linked row must name the same option, variable, and default
  as the CLI Reference's row for that key, and its link fragment must equal the key. That leaves the CLI Reference as
  the one place a pairing is stated.
- **Pin the option-to-key pairing.** For each option that takes a value, build the options from `arrayOf(option, v)`
  and from `arrayOf("-D$key=$v")`, with no config file, and require equal public properties. A flag compares against
  `-D$key=true`. A variable's pairing can't be exercised in-process, since `EnvVars` reads `System.getenv`. Instead,
  check from the options' source that the row's `EnvVars` entry is passed in the same `resolve*Option` call as the
  option's field, as the other `misc/` specs read sources.
- **Compare the spec-valued gRPC defaults** with gRPC's own: the `GrpcUtil` constants above,
  `ProxyOptions.GRPC_HANDSHAKE_TIMEOUT_SECS`, and `INT_MAX` for the disabled limits.
- **Correct `docs/TESTING.md:875-877`** to say which defaults the spec checks.

**Tests:** The new checks. Each must fail on a planted error: `--chunk` renamed in `configuration/agent.md`,
`MAX_CLIENT_CACHE_SIZE` misspelled in `cli-reference.md`, a link's fragment or a `<span id>` changed, and the agent's
`keepAliveTimeSecs` given as 7200.

### 72. ⬜ `DemoTourTablesTest` misses a README's Guided Tour heading left at a part's old name and the anchors `demo.sh` adds to its page link, and `docs/TESTING.md` misdescribes what it checks

**Severity:** low · **Confidence:** reproduced by planting errors in a scratch edit of the tree, then reverting; the
guide's wording confirmed from the files.

**Where:**

- `misc/DemoTourTablesTest.kt` (new in #549):
  - `:71-81` requires each README's and page's What This Demo Shows rows to be numbered 1 to n and named exactly as
    `TOUR_PARTS`, in order;
  - `:83-93` finds the README's `### N.` heading by number alone and requires the row's anchor to be its slug;
  - `:95-105` requires a page row's anchor to be a heading below the table;
  - `:107-114` requires `PAGE_URL` to be `https://prometheus-proxy.com/<name>-demo/` and the page to exist;
  - `:118-121` looks for `\n## What This Demo Shows\n` anywhere in the file.
- `docs/TESTING.md:844-847`: "each demo's README and its page on the docs site open with a What This Demo Shows table
  that names the demo's tour parts … a page's rows link only to sections the page has". Every README has the table
  after Quick Start and What's Running (`demos/basics/README.md:107`), and every page after Running It and What's
  Running, three of them after a section on the app's code too (`spring-boot-demo.md:130`, `ktor-demo.md:178`,
  `plain-kotlin-demo.md:154`).

**Problem:**

- **Two errors pass the spec.** Each of its four checks catches what it names: a part renamed in `TOUR_PARTS` alone, a
  README anchor with no heading, a page anchor with no section, and a wrong `PAGE_URL` each failed the right test. But
  renaming the Spring Boot demo's part 5 in `TOUR_PARTS` and both tables, with the README's `### 5. A Graceful
  Shutdown Takes the Agent Down First` and its anchor left as they were, passes: the README's table names one part and
  links to a section titled with the old one. The spec's comment says a renamed part "fails here until both tables
  follow", which is true of the tables only. And `${PAGE_URL}#running-it` in the Kubernetes demo's `create_cluster`
  changed to `#running-it-nowhere` passes: the anchors `demo.sh` prints into its page (`#what-this-demo-shows`,
  `#the-workers-code`, `#running-it`) aren't checked, though the first is pinned indirectly by the table's heading.
- **The guide misdescribes it.** Read as written, `docs/TESTING.md` says moving the table under Quick Start breaks a
  rule, which it doesn't: none of them opens with the table, and the spec doesn't check where it is. It also
  undersells the rule that does exist: a page's row may link only to a section below its table, so a link to Running
  It, above the table, fails, though Running It is a section the page has.

**Fix:**

- In `:83-93`, also require the heading, lowercased, to equal the part's name lowercased; every README's headings are
  their parts title-cased, so this passes on today's tree. Add a check that each `${PAGE_URL}#anchor` in a `demo.sh`
  is a heading anchor of its page.
- In `docs/TESTING.md`: "each demo's README and its page on the docs site have a What This Demo Shows table that names
  the demo's tour parts, its `TOUR_PARTS` in `demo.sh`, in order; a README's rows link to their parts' sections of its
  Guided Tour, a page's rows, where they link, only to sections below the table, and each `demo.sh`'s `PAGE_URL`,
  which `make up` and `make tour` print, is its page".

**Tests:** the two extended checks, which fail on the planted errors above.

---

## ✅ Appendix: checked and sound

What the eight first-pass reviews, the four second-pass ones, and the three third-pass ones examined closely and
found correct, so the next review knows what was covered, and what each looked at and judged not worth a finding.

### Proxy

- **`receiveCatchingWithin` and every proxy wait that ends in a keepalive.** `AgentContext.readScrapeRequest` (one
  `UNLIMITED` signal per queued request) and `awaitPathNotices` (a `CONFLATED` wake) both select on
  `onReceiveCatching` with an optional `onTimeout`, so a signal that comes as the wait ends stays in the channel.
  `emitScrapeRequests` sends a keepalive only when the wait timed out and the context is still valid, never on the
  closed result that also returns null. `queuePathNotice` signals outside its monitor, and a wake that finds the
  notices already taken just loops, so no notice is lost. `ScrapeRequestWrapper.awaitCompleted` keeps
  `withTimeoutOrNull`, but its channel is only ever closed, never sent to, so the rule doesn't apply.
- **Keepalive negotiation and compatibility.** Each direction is gated: the proxy sends on `readRequestsFromProxy`
  only when `acceptsStreamKeepalives`, read once per stream after `registerAgent` (the agent registers before it opens
  any stream, `Agent.kt:452-512`). The agent sends only when `stream_keepalive_millis > 0`, and the proxy sends 0 when
  `streamKeepAliveSecs = 0`, which `ProxyOptions` keeps non-negative. Pairings checked: a 4.6.0 agent with 4.7.0 proxy
  gets no keepalives and never opens the notices stream; a 4.7.0 agent with 4.6.0 proxy sees interval 0 and gets
  UNIMPLEMENTED on notices, which it handles. Neither side counts a keepalive as activity (the proxy returns before
  `markActive`, the agent emits without `markMsgSent`), so heartbeats keep going and eviction is unchanged. Keepalive
  `ScrapeResponse` / `ChunkedScrapeResponse` messages are dropped before any field is read.
- **Path takeover and reclaim.** In `lockedRejection` the order is invalidated, then mismatch or another identity,
  then the reclaim holder, then the path limit. `reclaimRejection`'s holder is the first *valid* context of another
  launch, so a stale context of the agent's own launch, or a holder mid-teardown, never blocks a reclaim. An in-place
  discovery edit by the holder (exclusive, one context) finds no holder and replaces the entry, labels included. The
  waiting list is kept in step everywhere: re-told and replaced on takeover (`tellHolder` filters out the new
  holder's launch), added to on a refused reclaim (`waitersOf`, last 16), cleared for an unregistering waiter
  (`withoutWaiter`), scrubbed for a departing agent (`replaceAll` in `removeFromPathManager`), and dropped with the
  entry when `dropAgentFromPath` frees the path and sends "free". Every interleaving of a refused reclaim with the
  holder's removal ends with the reclaimer told "free" or taking the path: removal invalidates before the sweep, and
  both act under `pathMap`. A holder is never invalid while still listed, except during its own teardown, since only
  removal invalidates a holder (`settleDisplaced` invalidates only displaced contexts). The agent side backs this up:
  `AgentPathManager.clear` makes a kept takeover due at once on a reconnect, which puts the new connection on the
  waiting list.
- **Lock order.** `pathMap` is always taken first. Under it the code takes `AgentContext.pendingPathNotices` (in
  `queuePathNotice`, `forgetPathNotice`, `invalidate`), the `PathHistory` and `ActivityLog` monitors, and
  `ProxyMetrics.pathSeries` bins, all leaves. Nothing takes `pathMap` inside them: `PathHistory.record` reads
  `livePathCount` before taking its monitor, and the summed gauges copy `SHARED.instances()` before calling
  `pathMapSize`. `invalidate()` under `pathMap` only closes channels and completes wrappers, which resumes handlers by
  dispatch.
- **TLA+ model against code (apart from #1).** `RegisterLocked`'s rejection disjuncts match `invalidatedRejection`,
  `conflictRejection`, and `reclaimRejection` (the path limit and `formRejection` are left out, and only refuse
  more). `newCount`/`orphans` match `countPaths` and `settleDisplaced`, `tellees` matches `tellHolder` (all modeled
  agents read notices), and the forget/told updates match `recordRegistration`. `RemoveCtx`→`SweepPaths`→`FailScrapes`
  matches `removeFromContextManager` (remove, then invalidate) → `removeFromPathManager` →
  `removeChunkedContextsForAgent` → `failAllScrapeRequests`. `StreamOpen`/`StreamClose` match `readableAgentContext`
  and `removeAgentContextIfCurrent`. `Read`/`Emit`/`EmitCancelled` match `readScrapeRequest`, `isStillAwaited`, and
  `sendScrapeRequest`'s cancellation. A refused reclaim's waiting-list write and its notice to the reclaimer, both
  abstracted, change nothing the model tracks (its `told` already has the path).
- **`ScrapeDispatch`.** The order is track, then queue, then await. The `finally` removes the request before the
  chunked transfer, matching `readHeader`'s store-then-recheck, so one of the two always removes it. Every
  terminal path yields a response with `updateMsg` and `fetchDuration`. Both spans are finished on every path through
  `traced`, cancellation is tagged `cancelled`/`client_cancelled`, a dashboard scrape gets no span, and spans are
  passed explicitly, never put in scope.
- **Chunked transfers.** Each transfer has exactly one remover (removal sweep, stream sweep, the next message, or the
  handler's `finally`), and each counts it once. Owner checks use the context's recorded owner. `activeScrapeIds`
  stays under `maxTransfers` with its `retainAll`.
- **`ProxyMetrics` / `SharedCollectors`.** All 25 companion collectors are in `SHARED`. The sentinel check works on a
  labeled gauge with no series yet (`PrometheusRegistry.scrape` includes empty snapshots). Registration is
  all-or-nothing and `release` clears stateful metrics. `pathSeries` is changed only inside per-key compute, so a
  scrape that ends after its path's removal records nothing. `close()` runs after the HTTP and gRPC services stop.
- **Agent-supplied values.** The new fields (`path_notices`, `stream_keepalives`, `reclaim`, `keepalive`) are booleans.
  `held_by_agent_name` is the bounded agent name, and the reclaim reason, the notices-full WARN, and the span tags go
  through `shownValue`. Per-context notices are capped at 20,000 and per-path waiters at 16, and the waiters are
  scrubbed on removal.
- **`maxAgentsPerIdentity`.** The slot is claimed in `compute` before the context is added, and freed in `onRemoved`
  for both `removeFromContextManager` and `removeFromContextManagerIfCurrent`. A refused connect's context is never
  added, so nothing leaks. An orphan invalidated with the transport filter disabled is removed when its stream ends.
- **`removeAgentContext`.** It invalidates before the sweep (finding 7) and reclaims chunked transfers before failing
  scrapes (November #2). Events are emitted only for announced contexts, and the sweeps run even when no context was
  found.
- **Lifecycle.** `startUp` unwinds started services, the base service, and metrics on failure. `shutDown` fails
  in-flight scrapes before invalidating contexts, so the reason is `proxy_stopped`, and closes tracing after the
  services that trace. Streams of a context added during STOPPING end at once (`isStartingOrRunning`).
- **`PathHistory` / `ActivityLog`.** Bounded on every write. The departure is recorded and forgotten under
  `pathMap`, so it's ordered with re-registration, and `record`'s eviction never takes the path just recorded.
- **Considered, not reported:**
  - **Notices stream keepalive restarts on an empty wake.** A wake that finds no notices (one forgotten by
    `forgetPathNotice` between queueing and the stream's take) restarts `awaitPathNotices`'s timer without sending,
    so the stream can stay quiet past `streamKeepAliveSecs`. Each such wake needs a microsecond race, and a repeat
    within every window, so it was left out.
  - **`proxy_scrape_queue_wait_seconds` is observed before the send.** It leaves out the time `emit` waits on
    flow control (#2's case) and time in gRPC's buffers. It's documented as "until the agent's stream took it",
    which is arguable, and the gap only matters for an agent already hopelessly behind.
  - **Repeated "held by" notices.** A refused reclaim re-queues one to the reclaimer each time. The agent ignores a
    repeat (`takenOverBy` keeps the backoff), so the only cost is one small message per retry.
  - **Notices dropped past 20,000.** `MAX_PENDING_PATH_NOTICES` is below an unlimited or raised
    `maxPathsPerAgent`, so an agent losing more paths at once drops notices. The first drop is WARNed, and the
    agent's reclaim loop doesn't depend on the "free" notice.
  - **Misleading "keeps its connection" INFO.** It's logged for a displaced context that a racing removal has already
    invalidated, which is cosmetic.
  - **Same-launch rule weakened by small field limits.** The launch ID is cut to `maxAgentFieldLength`, so a tiny
    setting (≤ 8) could make two launches compare equal and bring back the orphan invalidation between them. That
    needs misconfiguration, and launch IDs are random.
  - **Chunked payload over `maxZippedContentSizeMBytes`.** It's reported as `invalid_response` and
    `proxy_chunk_validation_failures_total{stage="chunk"}`, outside `ProxyPayloadTooLarge`, and the docs call those
    failures corruption or agent bugs. This predates the range, and the agent's 10 MB raw default keeps the gzipped
    payload far under 5 MB.
  - **`application/vnd.google.protobuf` on the content-type allowlist.** Bodies are carried as text, but the agent
    strips protobuf from `Accept` (#285), so only a target that ignores `Accept` is affected, and it would fail either
    way.
  - **Long-lived gRPC streams traced at the sample rate.** `readRequestsFromProxy` and the response streams each get
    one span for the whole connection, as the notices stream would have. That's of little use, but bounded by
    connections.
  - **Late metric writes after shutdown.** A late RPC can increment a counter after `ProxyMetrics.close()` released the
    collectors, which matters only to a later proxy in the same JVM.
  - **`Proxy.shutDown()` isn't failure-isolated** (as in November). No step was found that throws in practice.

### Agent and shared code

- **`EndpointFailover` against `AgentFailover.tla`:** both are unchanged since the November review.
  - `BeforeAttempt` matches `beforeAttempt`: fail back to the primary when the previous attempt registered, advance
    when it didn't (except on the first attempt), and keep the index for a single endpoint.
  - `Register` matches `registerAgent` plus `registerPaths` before `registrationSucceeded()`. The retry task ending a
    registered connection is the model's `Drop` on a `rejects` proxy.
  - `connected` matches `agentId.isNotEmpty()`. The channel rebuilds aren't modeled, and need not be.
- **Stream keepalives on the agent's side:**
  - `responseKeepalive` is set on every successful `registerAgent`, so a failover to a proxy predating keepalives turns
    them off.
  - `streamOf` reads it on every wait, through `receiveCatchingWithin`. With `Duration.INFINITE` the wait is a plain
    receive.
  - A proxy keepalive on `readRequestsFromProxy` is skipped before the backlog is touched. An empty-path notice is
    ignored.
  - The agent always sends `stream_keepalives` and `path_notices`. An `UNIMPLEMENTED` notices stream waits out the
    connection.
- **Takeover and reclaim (`AgentPathManager`):**
  - `clear()` keeps only taken-over entries and makes them due at once, keeping their wait. `registerPaths` skips them,
    and the discovery reconcile only edits them in place, so nothing registers a taken-over path without `reclaim`. The
    reclaim covers a discovered edit of an exclusive path (`reclaim = current != null`). The consolidated edit
    unregisters first, and the proxy's `reclaimRejection` returns null for consolidated paths.
  - `registering` holds a notice that arrives during the RPC and applies it after the path is recorded or refused.
  - `reclaimFailed` keeps the takeover on `INVALID_AGENT`. It advances the backoff only when the takeover it tried is
    still the same object, and `takenOverBy` keeps that identity for a repeat of the same holder, so each refusal
    doubles the wait and doesn't loop.
  - `reclaimTakenOverPathsWhile` can't spin: every due path's try either advances its backoff or is replaced by a
    notice, which costs at most one extra immediate try.
  - Holder names from other agents are escaped (`shownValue`) in logs, the activity feed, and the dashboard reason.
- **`AgentPathManager`, other paths:**
  - `pathConfigs` checks duplicates and refused characters across the config, `source()` paths, and
    `agent.metrics.proxyPath`.
  - `servesNoStaticPath` counts a taken-over static path as served, as documented.
  - Rejection and registration failures are pruned on reconnect, and on leaving the desired set.
  - `discoveryConflicts` is a volatile snapshot, so the dashboard never waits on `pathMutex`.
- **`PathDiscoveryService`:** `lastGoodPaths` is reconciled on a failed read, `lastFailure` resets per connection,
  failures are keyed by `failureKey(withMessage = true)`, and the sliced sleep honors `keepRunning`.
  `FileDiscoverySource` refuses a missing file, resolves substitutions, and reports skipped entries only when the set
  changes.
- **`AgentHttpService`:**
  - **URL fetches:** the timeout is the smaller of the agent's and the proxy-forwarded one, and the fetch streams up
    to `maxContentLength + 1` bytes.
  - **Filtering:** the filter runs on raw bytes after the size cap and before the gzip decision. A dashboard scrape
    uses `previewBytes`, which counts nothing.
  - **Counting:** `fetchAndRecord` counts every scrape once by type, and records history only for a path the agent
    serves.
  - **The `fetch` span:** it is passed explicitly (`FETCH_TRACE_SPAN` attribute for retries), skipped when noop or for
    a dashboard scrape, and finished on every path by `traced`.
  - **`fetchFromSource`/`callSource`:**
    - It checks the cap before the result, so a source that swallows the cap's exception is still refused.
    - It interrupts an abandoned call and doesn't call a stuck source again until the call returns (`stuckSources` is
      added before `invokeOnCompletion`, so a call that completes in between is removed at once).
    - A `VirtualMachineError` from a source propagates.
    - `CappedBytes` writes are atomic.
- **`ScrapeTracing`:** no code anywhere puts a span in scope (`withSpanInScope`, `newScope`, and `maybeScope` don't
  appear). `CountingSampler` is used rather than trace-ID sampling, heartbeats and the notices stream are never
  sampled, and `DeferredClientInterceptor` builds the interceptor on first call.
- **`AgentMetrics`:** every counter, gauge, and histogram takes `launch_id` first and is in `PER_LAUNCH`, and the
  sampled gauges are in `COLLECTORS`. `close()` removes this launch's series before `release`. No metric is touched
  after `closeMetrics` in shutdown order: `run()` has returned and the dashboard has stopped first. `SharedCollectors`
  is unchanged in behavior since November.
- **`Agent.collectsMetrics`, `registersJvmMetrics`, `isEmbedded`:** `isEmbedded` is set before `startSync()`, and
  common-utils reads `registersShutDownHook` only in `startUp()`, so an embedded agent registers no hook. JVM groups
  are registered only for a standalone agent whose metrics port is off and that has a proxy path.
- **Connection lifecycle:**
  - The backlog stays balanced across a failed `send`, `close()`'s drain, and each scrape's `finally`.
  - A scrape's `Error` propagates to `handleConnectionFailure`.
  - `liveConnection` is set only after `registerPaths`.
  - The activity feed records each event where it is logged at WARN or INFO, never a DEBUG repeat.
- **`common/Channels.kt`:** `receiveCatchingWithin` is a `select` with `onReceiveCatching`/`onTimeout`, and it omits
  the timeout clause for an infinite duration.
- **`TlsMaterial`/`TlsSource`:**
  - Each source accepts a file or PEM, never both.
  - `pemText` restores literal `\n`, and refuses non-PEM base64 without echoing the value.
  - Missing files and bad PEM throw `IllegalArgumentException`, which `failOnInvalidConfig` turns into a redacted
    `ConfigLoadException`.
  - Only "(set)" or "(none)" is logged.
  - A certificate and key that don't match aren't refused at build time either: tried through `startAsyncAgent(Config)`
    with `client.pem` and `server1.key`, the agent started.
- **`EnvVars`, `BaseOptions`, `AgentOptions` resolution:**
  - Every new option resolves CLI, then env, then config inside its `assign*` function.
  - `resolveBoolean` honors an explicit flag.
  - `proxyHostnameSpec` parses config endpoints only when they're the value used.
  - The new `require`s (metrics proxy path, dashboard sizes) throw `IllegalArgumentException` inside the redacting
    handler.
  - Integer parse errors never involve a secret-bearing variable.
- **`AgentScrapeHistory` and `AgentActivityLog`:** both are bounded on every write, and their monitors are leaves (the
  path manager records while holding `pathMutex`, never the reverse). History is keyed by `requestedPathKey`, and is
  recorded only for served paths.

**Considered, not reported:**

- **`reclaimTakenOverPathsWhile` waits with `withTimeoutOrNull(wait) { reclaimWake.receive() }`.** That is the shape
  AGENTS.md forbids for keepalive waits. Here a lost element is a `Unit` wake from a `CONFLATED` channel, and the loop
  scans every path after either outcome, so nothing is lost.
- **In `doRegisterPath`'s `finally`, a newer notice can land between `registering.remove` and applying the held one,
  so the older one wins.** Every ordering heals within one reclaim: a stale "held by X" for a freed path keeps the due
  time, and a stale "free" gets refused, and the proxy then names the holder.
- **A reclaim refused for a cause that can't clear drops a static path without adding it to `rejectedStaticPaths`.**
  If that was the last static path, `servesNoStaticPath()` goes true with nothing ending the connection. The causes
  involved (`NOT_AUTHORIZED`, `INVALID_PATH`, `BINDING_MISMATCH`, `PATH_LIMIT_REACHED`) can't arise between a
  registration and its reclaim on one proxy except through a proxy config change.
- **Two concurrent scrapes of one in-process source that both time out share one `stuckSources` entry.** The first
  completion clears it while the second call is still stuck. This is bounded by `maxConcurrentHttpClients`.
- **Brief misordering of path notices:** `AgentClientInterceptor` synchronizes on the `Agent` instance, and a late
  stale notice can briefly mark a just-registered path as taken over. Both are pre-existing or self-healing.
- **`assignLogLevel` sets the `io.prometheus` logger for an embedded agent.** Behind a JUL-to-SLF4J bridge, that also
  covers the host's Prometheus client loggers. This is an edge case, and the logger is documented.
- **`heartbeatMaxInactivitySecs` isn't validated.** At 0 it sends a heartbeat every `heartbeatCheckPauseMillis`
  (500 ms), which is harmless.

### Embedding

- **Shutdown hook (`isEmbedded`, #519):** common-utils 5.3.0's `AbstractGenericService` reads `registersShutDownHook`
  in `startUp()`, never in its constructor. `startEmbedded` setting `isEmbedded` after building the Agent is therefore
  in time. The other `isEmbedded` readers are getters or per-scrape lambdas: `registersJvmMetrics`, and
  `AgentMetricsSource`'s `standalone`. The standalone agent and `startSyncAgent` keep the hook.
- **`EmbeddedAgentInfo`:** `shutdown()`/`close()` drive `stopSync()` and skip a FAILED agent. A second call is a no-op.
  `isConnected` and `proxyEndpoint` are read live from `liveConnection`.
- **Spring Boot starter lifecycle:**
  - Ordering: the bean takes `SmartLifecycle.DEFAULT_PHASE` (`Integer.MAX_VALUE` in Spring 7.1), so it starts after
    and stops before the web server's lifecycles.
  - Failed refresh: Spring 7.1's `DefaultLifecycleProcessor.onRefresh` stops started beans when a later start fails,
    and `AbstractApplicationContext.refresh` stops the lifecycle processor on a cancelled refresh, so a started agent
    is stopped once.
  - Start and stop: `stop()` clears the handle before `shutdown()`. A required agent's failure, a checked one
    included, fails the app. An optional one logs and stays off.
  - Health: the indicator is never DOWN.
  - Auto-configuration: the class check guards the health auto-configuration before `spring-boot-health` types load.
- **Ktor plugin lifecycle:**
  - Required agents: `Events.raise` (ktor-events 3.6.1) rethrows a handler's exception, so a required agent's failure
    fails `ApplicationStarted`.
  - Failed module load: a module or later handler that fails after the agent started gets `disposeApplication`, which
    raises `ApplicationStopping`, and `finishStop` stops the agent.
  - Shutdown: CIO raises `ApplicationStopPreparing` after cancelling accept, so the agent's stop runs during the
    engine's grace period. `finishStop` joins it.
  - Dev-mode reload: the `it === application` checks and the `ApplicationStopped` dispose keep a reload from touching
    the new app's agent.
- **`MetricsSource` containment (`AgentHttpService.callSource`, `:321-345`):**
  - Timeouts: the scrape timeout cancels the `async`, `runInterruptible` interrupts the call, and an abandoned call
    flags its source until its completion handler runs. That handler fires only once the blocking call returns.
  - What the source throws: `sourceFailure` maps a source's own `CancellationException`, `InterruptedException`, or
    non-VM `Error` to an `IOException`, and lets a `VirtualMachineError` through, as documented.
  - Size cap: `CappedBytes` is synchronized, so concurrent writes can't pass the cap. The cap check runs before the
    call's result, so a source that swallows the cap's exception is still refused.
  - Shutdown: `close()` cancels `sourceScope`.
- **Each series once:**
  - Both names checked: `RegistryMetricsSource` tests both a family's Prometheus name and its exposition name, as
    `PrometheusRegistry.scrape(Predicate)` and `Collector.collect(Predicate)` do in 1.10.0, so an agent counter is left
    out under both.
  - Per-launch filtering: `AgentMetricsSource` filters by `launch_id`, so with two agents in a JVM each path shows its
    own series.
  - Cases checked:
    - with and without `agentMetricsAt`;
    - with the metrics port;
    - with a second agent in the JVM;
    - with a Micrometer registry, which is never the default.
  - Collectors aren't registered until `SharedCollectors.acquire`, so an agent that doesn't collect leaves no agent
    families in the default registry.
  - `SystemMetrics.initialize` skips a set already registered by the host's own `JvmMetrics`.
- **Secrets in logs and exceptions:**
  - `EmbeddedAgentConfig.toString`: masks `agentToken`, `*Pem`, and `*password*` in any case, and URLs through
    `sanitizeUrl`. That holds for Spring's lowercased keys and nested `settings` maps.
  - `failureReason` runs `sanitizeUrlsInText` over the cause chain.
  - TLS errors: `TlsMaterial.pemText` and `checkTrustCollection` messages, and Netty's `trustManager(InputStream)`
    errors, carry no PEM content.
  - `ConfigLoadException`: the Agent constructor's checks go through `failOnInvalidConfig`, with redaction, on every
    embedded entry point (`newAgent`).
  - The starter's `Properties.toString` masks the token, certificates, key, and passwords.
- **Java usability:**
  - `@JvmStatic` `builder()` and `MetricsSource.of`.
  - `@JvmOverloads` on `start`, `startIfConfigured`, and the builder's optional-argument methods.
  - The internal `start`/`startIfConfigured`/`fromEnvironment` overloads are name-mangled out of Java's view.
  - `MetricsSource`'s `@Throws(IOException, InterruptedException)` lets a Java lambda throw both.
  - `source(path, PrometheusRegistry)` and `source(path, MetricsSource)` aren't ambiguous for a Java lambda.
  - Kotlin properties are `@JvmSynthetic`, so Java sees only the methods.
- **Public API surface:** the 11 non-`internal` top-level declarations match AGENTS.md's list, and `docs/packages.md`
  links each: `Agent`, `Proxy`, `AgentOptions`, `ProxyOptions`, `BaseOptions`, `EmbeddedAgentConfig`,
  `embeddedAgent`, `MetricsSource`, `EmbeddedAgentInfo`, `EnvVars`, and `ConfigLoadException`.
- **Generated POMs (consumer-check snapshot):**
  - Starter: the agent at compile scope, Micrometer's registry at runtime, and no Spring Boot artifact or BOM.
  - Plugin: the agent and `ktor-server-core` at compile scope.
  - Root: the API types `prometheus-metrics-model`, `config`, and `service-utils` at compile scope, and the rest at
    runtime. That matches AGENTS.md, apart from #9.
- **Spring Boot 4.2.0's pins:** Kotlin 2.4.21 and prometheus-client 1.10.0 match the catalog's. kotlinx-serialization
  1.11, gRPC 1.84.1, Logback 1.6.6, and Netty 4.2.20 are what the starter's enforced-platform tests run.
- **`fromEnvironment` / `EnvironmentSetting`:**
  - A later builder call or `setting()` forgets the variable for its key.
  - The agent's own variable skips the check, since the agent uses that value instead.
  - The default authority is applied only with a certificate.
- **Demos:**
  - The coffee shop installs the plugin with a Micrometer registry and `agentMetricsAt`.
  - The roastery uses the starter's properties only.
  - The oven's `use { }` plus its shutdown hook (a `@Volatile` stop flag) stops the worker and then the agent on
    SIGTERM, even when the signal comes before `runUntilStopped`.
- **Considered, not reported:**
  - **`stuckSources` is a set:** with two overlapping calls of one source both stuck (two Prometheus replicas
    scraping the path), the first to return clears the flag while the second is still stuck. Stuck calls can then
    pile up only if a source hangs on some calls and returns on others. That's too narrow to report; a per-source
    count would make the "not called again until it returns" wording exact.
  - **`EnvironmentSetting`'s `PROXY` rejection message includes the endpoint**
    (`parseEndpointList`: "No proxy endpoints found in '…'"), though its KDoc and AGENTS.md say the message "never
    the value". The proxy address isn't a secret, and the message is more useful with it.
  - **`startAsyncAgent(Config)` and the builder fall back to `ConfigFactory.load()`**, which reads the host's
    `application.conf` and system properties. A host with an unrelated top-level `agent` or `proxy` key could fail the
    agent's start. This is documented, and pre-existing for config files.
  - **Kotlin `internal` constructors compile to public JVM constructors** (`AgentOptions(Config)`,
    `EmbeddedAgentConfig.Builder()`), and `EmbeddedAgentInfo`'s data-class constructor and `copy` are public. Java can
    reach them, but nothing documents them, and misuse only builds a second handle.
  - **The starter's `Target.toString` masks with its own regex**, not `sanitizeUrl`. It differs only for a password
    with an unencoded `/`, and nothing logs it.
  - **Ktor `FileSettings` rejects an invalid `enabled` or `required` at install, failing the app** even when the agent
    isn't required. Spring's binder fails startup the same way for a bad boolean, so this is consistent.
  - **Source calls share `Dispatchers.IO` with the host's coroutines.** A stuck call holds one of its 64 threads, but
    only one per source at a time (see the first item).

### Dashboards

- **Escaping (XSS):** every agent-, target-, and config-supplied value reaches the page through kotlinx.html, which
  escapes text and attribute values; `unsafe` carries only the static script, the static stylesheets, and the icon
  stylesheet built from the config's base path and the `LinkIcon` enum. The result panel's `innerHTML` gets only
  `ScrapeNowHtml` (escaped, excerpt control characters escaped too) or the guards' fixed plain-text refusals, and
  StatusPages' 500 has no body. Path-derived ids can't collide with a region (every one has a prefix), and htmx 4.0.2
  builds id selectors with `CSS.escape` and restores focus with `getElementById`, so a path's characters can't break a
  swap. The frame stamp is matched on the status region, the frame's first tag. `hx-get` URLs carry only
  proxy-generated agent ids, and the agent's selection URL encodes its path.
- **Login and guards:** the auth check is application-wide and runs before routing, and only the exact login and
  logout paths skip it (a raw-path compare, so any encoding or trailing slash needs a login). Basic Auth is read only
  from non-browsers (`Sec-Fetch-Mode`), and the server never sends `WWW-Authenticate`, so no browser saves one. Tokens
  are 256 random bits, kept as SHA-256, LRU-capped at 1000, idle 24 h and max 7 d; the cookie is per role and port,
  `HttpOnly`, `SameSite=Lax`, `Secure` behind https. Credentials are compared as digests with `MessageDigest.isEqual`,
  both halves always. The login body is capped and the limiter bounded. `safeNext` refuses `//`, `\`, control
  characters, whitespace, and dot segments; an encoded dot segment resolves on the same origin only. Login and Log out
  are behind `originGuard`; both Scrape now routes and the WebSocket are behind `pageGuard`, whose non-GET branch needs
  `HX-Request`, which a cross-site page can't send without a preflight.
- **Versioned assets:** both asset routes are allowlists under `ASSET_DIR` and `ICON_DIR`; the year-long caching keys
  on the JavaScript and SVG content types, which only those routes return; the SVGs are served with `nosniff` and
  `CSP: sandbox`. `extractDashboardIcons` fails the build on a missing slug and keeps the WebJar off the runtime
  classpath.
- **Snapshot cache (November #18):** page GETs and WebSocket messages read `recent()`; the generation counter keeps a
  collect a change overtook from being reused; one collect serves concurrent reads.
- **Push loop otherwise:** the session cap is a CAS reservation released in `finally`; a render failure is isolated
  and logged once; the per-session mutex is never held across a suspension that depends on the client; the change
  flow is conflated and spaced to 250 ms; `stop()` cancels a parked flush, since the `FlushRequest` job is a child of
  the caller's. Losing a wake to `withTimeoutOrNull` at the timeout is harmless, since the timeout pushes anyway.
- **Invariants:** the live region is outside every `hx-swap-oob` region; the column guide is too, and both its rows
  and the header iterate one `TableColumn` list in all three views; every dark rule goes through `darkRule`, and the
  login page's head has `THEME_SCRIPT`; the route base is normalized once; the proxy's three views name agents through
  `agentLabel` (but #18). Text tokens on `--surface-2` measure 4.59:1 (`ink-3`, light) and up; the accent is never text.
- **Scrape now:** the proxy's submits through `ScrapeDispatch.submitScrapeRequests` with `ScrapeOrigin.DASHBOARD` and
  records only in the activity feed; the agent's goes through `fetchForDashboard`, counts nothing, isn't in
  `AgentScrapeHistory`, and scrapes only a URL the agent has for a known path. `ScrapeNowLimits` releases its permit in
  `finally`, so a cancelled call doesn't leak one; an empty response list can't happen, since `isNotValid()` is true
  for an empty agent list.
- **Agent snapshot:** it reads `ConcurrentHashMap`s and immutable copies; target and skipped-entry URLs go through
  `sanitizeUrl`, failure reasons through `sanitizeUrlsInText`. `AgentActivityLog` and `AgentScrapeHistory` are bounded
  and cut, and the change counts cover folds.
- **Settings views:** tokens and passwords show only as set or not set, TLS PEMs only as set, `-D` overrides by key,
  and a config URL through `sanitizeUrl`.
- **Messages and filters:** `DashboardMessage` and `RowFilter.fromMessage` never throw; the query is trimmed and cut
  to 200 characters.

**Considered, not reported:**

- A WebSocket message parses the JSON four times and renders a frame; a client can send them in a loop, but it's
  bounded by how fast it reads (the outgoing buffer closes a non-reader), takes no lock (`recent()`), and a page GET
  costs as much.
- `/session` is called by the page's script without `pageGuard`, against the rule's letter; it's a read-only GET with
  no body, and the guard's GET branch would add only the Origin check.
- The pages send no `frame-ancestors`; without a login, a framing page could click only Scrape now, which
  `ScrapeNowLimits` bounds, and with one, `SameSite=Lax` keeps the cookie out of a cross-site frame.
- `PathLink` encodes each placeholder value, so an agent's label can't change the host; a value of `..` can step up
  one path segment on the same host. Not worth the reader's time.
- A burst line in either activity log counts a path again in `pathCount` when it repeats outside the three samples
  within the window; rare, and only the "and N more" count is off.
- The login limiter's overall cap lets about ten addresses lock every login out for a minute; documented as the
  trade-off.
- A script that sends both a right Basic login and a stale cookie has its socket ended at the first tick (the session
  is keyed on the cookie); a harmless edge.
- The push loop can push an older snapshot just after a session's own push of a newer one; the next tick corrects it.

### Security

- **Dashboard login (`DashboardServer`, `DashboardLogin`, `DashboardSessions`, `LoginLimiter`):** the auth check is
  application-wide and installed after `hostCheck`, and Ktor 3.6.1's routing skips a handled call
  (`RoutingNode.kt:126`), so a 401 or redirect from `onCall` stops every route, the WebSocket included.
  `needsNoLogin` compares the raw `request.path()`; an encoded login path routes to the login handler but still needs a
  session, which is the safe direction. Basic Auth is read only from requests without `Sec-Fetch-Mode`, a header a page
  can't remove. Tokens are 256 random bits, stored as SHA-256, re-issued on every login, removed on Log out, and
  re-checked by the push loop each tick. Username and password digests are both compared with `MessageDigest.isEqual`.
  The cookie is `HttpOnly`, `SameSite=Lax`, scoped to the base path, and `Secure` behind https. `remoteAddress` is the
  socket peer (no `XForwardedHeaders` plugin). `safeNext` refuses absolute, protocol-relative, backslash, control, and
  `.`/`..` targets, so `next` can't leave the origin. The login body is capped at 4 KiB.
- **Dashboard guards:** every action (`/scrape` on both dashboards) and the WebSocket install `pageGuard`; a non-GET
  needs `HX-Request`, which no cross-site page can send without a preflight the server never answers. Logout and login
  check the Origin. `isOriginAllowed` refuses the opaque `null` origin and compares the full authority with `Host`.
  `allowedHosts` ignores `X-Forwarded-Host`.
- **Dashboard rendering:** kotlinx.html escapes every agent-, target-, and client-supplied value; the only raw blocks are
  the fixed CSS and scripts and `linkIconCss`, built from the `LinkIcon` enum. Fixed region IDs share no prefix with the
  IDs built from paths, so an agent's path can't take a region's ID. The script's one `innerHTML` takes the server's
  own Scrape now HTML or a guard's plain-text 403. `PathLink` refuses a placeholder before the end of the host and
  encodes every value as a URL component, so an agent can't choose a link's site. WebSocket messages are parsed
  field by field with defaults, frames capped at 64 KiB, filters cut to 200 characters. Rendering reads agent indexes
  with `getOrNull`, so an agent's values can't make a push throw.
- **Scrape now:** both dashboards scrape only a path the process already has, at its own URL, never one from the browser.
  `ScrapeNowLimits` holds four at a time and one per path per five seconds. The excerpt is cut to 2 KB and escaped twice
  (control characters, then markup). The proxy's version carries `ScrapeOrigin.DASHBOARD` and no `Authorization`.
- **Settings views:** the agent's token, the trust store's password, the dashboard's password, `proxy.auth` tokens, and
  PEM material show only as set or not; `-D` overrides show only their keys; a config URL is redacted (`configShown`).
- **Embedding:** `EmbeddedAgentConfig.toString`, which the starter and the plugin log at startup, masks `agentToken`,
  every `*Pem`, and every `*password*` key in any case, and redacts every `url`; credentials given as a username and
  password are percent-encoded into the URL, which every agent log, its dashboard, `/debug`, and the proxy redact.
  `EnvironmentSetting.check` names the variable and never the token or certificate. `failureReason` redacts URLs in
  every cause. Netty's PEM errors don't echo their input. `PrometheusProxyAgentProperties.toString` masks the token,
  PEMs, and passwords, and lists `settings` by key only.
- **Path takeover, reclaim, and notices:** `conflictRejection` runs before `reclaimRejection`, so an agent of another
  identity can neither reclaim nor join a path's waiting list while a live agent holds it. Waiting lists are capped at
  16 and swept on disconnect; unread notices at 20,000 per connection; the rejection memo at 1,000.
  `readPathNoticesFromProxy` binds on one lookup (`readableAgentContext`), as the scrape stream does. The agent escapes a
  notice's holder name in its log and its dashboard, and ignores a notice for a path it doesn't serve.
- **Keepalives:** a keepalive on either response stream returns before the activity mark and the ownership checks, so it
  neither keeps an agent alive nor touches another agent's scrape; the proxy sends keepalives only to an agent that said
  it takes them.
- **Tracing:** the proxy's `scrape` span never joins a scrape-port client's headers; `ScrapeRequest.trace_context` goes
  proxy to agent only, and nothing an agent sends back carries a context. Span tags escape the agent's name and redact
  `http.url`.
- **Agent fetch:** no `ContentEncoding` plugin, so the size cap reads raw bytes; same-origin redirects only; no cookie
  plugin, so the client cache shares nothing between targets but Basic credentials keyed by them.
- **gRPC auth and binding:** unchanged in substance since November apart from activity-feed and metrics hooks; the auth
  interceptor is still server-wide and first, and refusals are throttled per host.
- **Deployment artifacts:** the charts' dashboards stay off the Services and take their passwords only from a Secret;
  every demo publishes its ports on `127.0.0.1`, kind's mappings included.
- **Considered, not reported:**
  - The overall login limit (100 wrong logins a minute from all addresses) lets anyone hold off every new login, and
    every script's Basic Auth, at under two requests a second. Documented as the price of refusing a guesser; open
    sessions keep working.
  - Without a password and without `allowedHosts`, DNS rebinding reads either dashboard and can Scrape now. Documented,
    and the startup warning names `allowedHosts`.
  - An embedded agent reads the agent's generic environment variables (`DASHBOARD_ENABLED`, `ADMIN_ENABLED`,
    `AGENT_TOKEN`, …) from its host's environment, so a host variable of the same name turns a feature on. Documented
    in the embedding pages as the variables taking precedence, and the dashboard warns when it opens with no login.
  - A waiter of identity X can be told the agent name of identity Y's agent that took a path after X's holder died.
    A rejection already names the other identity, and agent names aren't secret.
  - `launchId` is agent-supplied, so an agent can claim another's launch and have its context invalidated when it takes
    that agent's last path. Only within one identity, which may take the path over anyway.
  - One agent can churn paths to push other identities' departed paths (1,000) and activity lines (500) out of the
    dashboard. Display only; the logs keep each event.
  - A `MetricsSource` exception's message goes to the proxy as the failure reason, as a target's error does.
  - `safeNext` lets `%2e%2e` segments through, which a browser resolves as `..`, but only within the dashboard's origin.
  - Bidi control characters (Unicode `Cf`) pass `shownValue` and the path alphabet; they reorder how a log line looks,
    but can't split it.

### Build, CI, packaging, and demos

- **Published artifacts**, generated with `generatePomFileForMavenPublication` and `generateMetadataFileForMavenPublication`
  for all three projects:
  - The agent's POM puts only `service-utils`, `config`, `prometheus-metrics-model`, and `kotlin-stdlib` at compile scope.
    It pins Netty 4.2.21.Final, tcnative 2.0.86.Final with all five natives, Jackson 2.23.0/2.23, and protobuf 4.36.2
    directly.
  - The agent's module has no `shadowRuntimeElements` and no empty variant.
  - The starter's POM is the agent (compile) and `micrometer-registry-prometheus` (runtime): no Spring Boot artifact and no
    BOM.
  - The Ktor plugin's POM is the agent and `ktor-server-core-jvm` at compile scope, and `kotlin-logging` at runtime.
  - The library jar has `reference.conf` and no `logback.xml`.
  - The javap'd public API names, outside internal-mangled and synthetic members, only `api` dependencies' types.
- **Fat JARs** (`agentJar`/`proxyJar` built locally): no duplicate entries, no signature files, one merged `NOTICE` and
  `LICENSE`, 95 distinct `.kotlin_module` files, `logback.xml` from `src/shadow/resources`, and a manifest with `Main-Class`
  and `Enable-Native-Access`.
- **Release gate:**
  - `RELEASE_VERSION_FILES` matches `docs/RELEASE.md` step 2 and the skill file by file. Every file holding a bumpable
    literal (image tag, coordinate, `<prometheus-proxy.version>`, chart version, `--branch`) is listed.
  - Replaying `release-check`'s local checks on this tree passes: every file names 4.7.0, the stale regex finds no 4.6.0
    where step 2 bumps, the nginx demo's deliberate 4.6.0 mentions don't match it, and both changelog checks pass.
  - The CI check and job checks read what `docs/RELEASE.md` says they do, and a job still running fails them.
  - `release` runs gate, tests, distro, confirm, publish, docker-push, docker-verify, release-tag, then gh-docs, as
    documented.
- **Workflows:** every `uses:` is SHA-pinned with a version comment. All 15 checkouts set `persist-credentials: false`.
  Every job has `timeout-minutes` and per-job least-privilege permissions. All 15 Dependabot entries have a 7-day
  cooldown. The zizmor version matches `ZIZMOR_IMAGE`, and the `setup-uv` pins match each other.
- **Images:** `etc/docker` bases, every Makefile `*_IMAGE`, every demo compose and app Dockerfile, and the Kubernetes
  demo's manifests and kind node are digest-pinned. The nginx demo's `agent-legacy` digest is Docker Hub's 4.6.0 index
  digest, checked against the registry.
- **Caching:**
  - `BuildConfig` from the commit time is configuration-cache safe and can't restore stale classes.
  - A scratch build confirmed Gradle reads the environment at execution, so `SCALE_*` and other test env vars aren't
    frozen by the configuration cache.
  - The starter's non-incremental, uncached `compileJava` is as documented.
  - `extractDashboardIcons` resolves the WebJar through its own non-published configuration.
- **Test partitions and Kover:** `make tests`/`ci-build` run both partitions and both floors. The `pambrose.testing`
  plugin (read from its sources jar) sets no fork parallelism. The Ktor plugin's and starter's fixed ports are in
  `TestPorts`. Kover aggregates `:ktor-plugin` with matching `perClass` variants.
- **Consumer check:** `check.sh` fails on each documented condition, and its Maven build compiles the site's Java,
  Kotlin, and Ktor examples. The setup-java Maven cache can hold the throwaway snapshot, but `publishToMavenLocal`
  always rewrites it before use.
- **Demos:**
  - Every published port binds `127.0.0.1`, including kind's `listenAddress`.
  - Compose project names are distinct, and default ports don't overlap across demos.
  - `.env` files holding generated tokens are git-ignored and written `umask 077`.
  - The grafana dashboards' `proxy_*`/`agent_*` series all exist in the code.
  - `demos-up`/`demos-down` keep going past a failed demo and report it. `demos-down` without kind skips the
    Kubernetes demo cleanly.
  - Each demo's `up-local` builds against the checkout (composite builds for the Ktor and plain Kotlin apps, a
    `-spring-boot-demo-SNAPSHOT` local publish for the roastery).
- **Charts:** every env var the templates set is in `EnvVars`, and the agent reads the shared `DASHBOARD_*` variables
  through `BaseOptions`.

**Considered, not reported:**

- `make gh-docs` dispatches `docs.yml` on `master`'s tip rather than the release tag, so a merge during a release's
  `all-tests` would deploy too. The only merger is the person running the release.
- The site's KDocs are the root module's only. The Ktor plugin's API isn't there, though its javadoc jar is on Maven
  Central and the plugin page documents it.
- `nginx/docker/Dockerfile`'s `FROM nginx:1.31-alpine` has no digest. It's a user-built example, and Dependabot bumps it.
- `etc/consumer-check/pom.xml`'s Maven plugin pins have no Dependabot entry. They're pinned on purpose and change by hand.
- `logback-classic` is runtime scope in the agent's POM (`Utils.setLogLevel` uses it), so a Log4j 2 host gets two SLF4J
  providers. This predates the review range.
- `release-check` would fail if CodeScene's advisory check posted a failure on a `master` commit. Plausible only: it
  posts on pull requests.
- `readme-diagram` is missing from `.PHONY`, and the Ktor demo's image is still tagged `embedded-app`. Both are cosmetic.

### Documentation

- **Site build and links:** `uv run --locked zensical build --clean` builds with no issues. A script over the built
  HTML found no broken relative link or anchor (only `kdocs/`, which `docs.yml` copies in from Dokka). Every
  `github.com/pambrose/prometheus-proxy/blob|tree/master/…` and `raw.githubusercontent.com` link in the pages, snippets,
  README, `llms.txt`, `docs/`, demo and chart READMEs, and changelogs names a file that exists. `zensical.toml`'s nav
  lists every page; the two pages outside it, `demo.md` and `embedded-demo.md`, are redirect sources.
- **Snippets and examples:** every Kotlin and Java snippet on the embedding pages (including `--8<--` includes from
  `src/test/kotlin/website`, `src/test/java/website`, `ktor-plugin/src/test`, `spring-boot-starter/src/test`) matches
  today's signatures, named arguments, `@JvmOverloads` arities, nullability, and return types; `.use` on a nullable
  `EmbeddedAgentInfo` and Java try-with-resources on a null handle compile. Every `docker run`, compose, Helm, and
  `kubectl` example in the config, deploy, and README pages uses images, ports, env vars, `WORKDIR /app`, and chart
  values that exist.
- **Public API surface:** the only non-`internal` top-level declarations in main are the ones AGENTS.md lists, and
  since 4.6.0 their changes are additions only (`EmbeddedAgentInfo : AutoCloseable`, the builder's new methods); no
  default of an existing agent key changed.
- **Embedded lifecycle claims:** `startIfConfigured()` returns null with no proxy, a rejected setting, or a failed start,
  logging why and calling `onFailure`; `fromEnvironment()`'s four variables, blank as unset, authority default, and
  precedence; `agentMetricsAt` serving only the launch's `agent_*` series embedded and the JVM groups too standalone
  (`AgentMetricsSource`, `registerJvmMetricGroups`, `isEmbedded` set only in `startEmbedded`); `source(path, registry)`
  leaving out the agent's families only for the default registry; `isConnected`/`proxyEndpoint` live; `close()` is
  `shutdown()`; no JVM shutdown hook when embedded (`registersShutDownHook = !isEmbedded`, common-utils 5.3.0).
- **Ktor plugin and Spring Boot starter pages:** every property and default against `FileSettings`,
  `PrometheusProxyAgentConfig`, and `PrometheusProxyAgentProperties`; the Ktor lifecycle split (`ApplicationStarted`,
  `ApplicationStopPreparing` on its own thread, `ApplicationStopping` waits); `SmartLifecycle` default phase; `required`
  semantics; the `prometheusAgent` health component (UP or UNKNOWN); the Spring Boot BOM property names.
- **Config, CLI, env vars:** every `@Parameter` flag, short form, and env var in `cli-reference.md`, its help text
  against what `resolve*Option` does, every `EnvVars` entry documented; `config/config.conf`'s comments against
  `ConfigVals` and their readers (keepalive `0 = off`, `sampleRate` 0 or 0.01–1 and never heartbeats or path notices,
  backlog ×2, map thresholds at 80%, `minGzipSizeBytes` after the filter, `cioTimeoutSecs` deprecation, dashboard login
  fields, `proxyPath`/`proxyPathLabels`); prose defaults on the config pages; `examples/*.conf`.
- **Behavior described on the site:** stream keepalives (both directions, only once the other side says it takes them,
  the nginx `grpc_read_timeout`/`client_body_timeout` guidance against `nginx/docker/nginx.conf` and the nginx demo's
  variants); takeover and reclaim (same-identity takeover, another launch keeps its connection, reclaim refused while
  held, the free notice); per-agent auth globs and `legacy-agent-token`; `maxAgentsPerIdentity`; agent-field limits;
  TLS PEM forms and "never both"; Zipkin span names, tags, and sampling; both dashboards' views, defaults, login rules
  (24 h idle, 7 days, 10 per address and 100 overall per minute, 429, Basic Auth for scripts only via `Sec-Fetch-Mode`),
  Settings groups, and Scrape now limits.
- **Metrics:** names, types, labels, label values, and buckets on `monitoring.md` and `metrics-and-grafana.md` against
  `ProxyMetrics`, `AgentMetrics`, `MetricBuilders`, and where each is incremented; every expression in
  `grafana/*.json`, `grafana/alerts.yml`, and the demos' dashboards and alerts uses labels that exist.
- **Demos:** every `./demo.sh` command and `make` target the tours name exists and does what's said; every published
  port and port variable matches its compose file or kind config; the root `DEMOS` list covers all seven; every PromQL
  query, quoted log line, and UI label in the tours is in the code; no leftover `demos/embedded` or `demos/worker` name
  after #530; the nginx demo's `agent-legacy` pinned to 4.6.0 by digest and ignored by Dependabot.
- **Changelogs:** each of the 67 commits since 4.6.0 maps to an entry in `CHANGELOG.md`'s 4.7.0 section, or is
  internal, a version or changelog edit, or a review fix to something new in 4.7.0 (#498, #500, #506); spot-checked
  against the code: the agent dashboard's defaults (8095, history 20, Scrape now on, activity 500), the error cut at
  200 characters (`ShownFailure.SHOWN_EXCEPTION_CHARS`), the brand-name cut at 48 (`BRAND_MAX_CHARS`), `Proxy:
  host:port` / `Agent: name`, the login limits, the 10-minute connect-failure reminder, the keepalive default and `0 =
  off`, the demo port offsets (+500 nginx, +600 plain Kotlin), the `demo.sh` commands each entry names, and the
  removed `grpcReportingEnabled` (nothing calls `checkValid`, and `ConfigVals` reads only the keys it declares, so a
  config that still sets it loads). `RELEASE_NOTES.md`'s 4.7.0 section agrees with the changelog.
- **AGENTS.md:** every backticked identifier it names exists in the repo; the Make targets, `RELEASE_VERSION_FILES`
  (matches `docs/RELEASE.md` step 2), `RELEASE_REQUIRED_JOBS`, `TLA_VERSION`/`TLA_SHA256`, the CI `tla` job's
  full-check condition, the Kover 0.9.11 pin, `jvm = 17`/`testJvm = 25`, the Docker images' Temurin-to-distroless
  layout and C.UTF-8 locale copy, the container suite's pinned `nginx:1.31-alpine` and `prom/prometheus:v3.14.0`, the
  `!Lincheck` default tag, `HarnessSetup`'s registry clear and `SystemMetrics.forget`, the harness file layout, the
  proto's `stream_keepalives`/`stream_keepalive_millis`, and the heartbeat and eviction defaults (5 s, 60 s).
- **Considered, not reported:**
  - `CHANGELOG.md` has no entry for #509 (the agent logs the keepalive interval the proxy gives it, at INFO): a log line,
    nothing a user acts on.
  - `RELEASE_NOTES.md`'s "Nginx's defaults are enough" depends on nginx's timer behavior for client-streaming RPCs,
    which wasn't traced in nginx's source; the nginx demo and `ContainersNginxTest` exercise it.
  - `start()`/`startAsyncAgent()` can throw `TimeoutException` after 30 s (`GenericExecutionThreadService.startSync`),
    which the API reference doesn't list; rare, and the list isn't claimed to be exhaustive.
  - The starter and the Ktor plugin decide "configured" from their own `proxy` setting before customizers run, so a
    customizer's `proxy()` or `PROXY_HOSTNAME` alone doesn't turn the agent on; both pages say "Unset, the agent stays
    off", which matches.
  - `basics-demo.md:248` quotes a log line as `[ProxyServiceImpl.kt:360]`, now line 361: cosmetic.
  - `demos/nginx/README.md:263` says the other demos' ports are "100 to 500 lower"; the plain Kotlin demo's are higher,
    but nothing collides.
  - The Ktor and plain Kotlin demos' internal image tags are still `embedded-app:local` and `worker-app:local`; no doc
    names them.
  - `nginx/docker/nginx.conf` passes to `grpc://alta.lan:50051`, a developer's host; it's an example to edit, and no page
    says it runs as-is.
  - `grafana.md`'s "Grafana 10.0 or later" with dashboards at `schemaVersion` 39: Grafana loads a newer schema without
    migrating; not shown to fail.
  - `PrometheusConfigs.txt` uses Prometheus's deprecated top-level `bearer_token`; not shown to be rejected by
    Prometheus 3.
  - `DOCKER_TESTING_SETUP.md:110` says the fat JARs come from "the `jars` target"; it's the test task's `dependsOn`,
    and they're built first either way.
  - `llms.txt:232-234`'s TLS rows don't render as a table, and it omits keepalives, Settings, and sampling: formatting
    and omissions in a condensed guide.

### Tests

- **AGENTS.md invariants that do have failing tests:**
  - **Reclaim never displaces:** `ProxyPathManagerTest.kt:1873-2110` fails without `reclaimRejection`.
    `AgentPathManagerTest.kt:2046-2480` deliver each notice inside the mocked RPC, which pins the order of the two
    streams. `AgentGrpcServiceTest` checks the `reclaim` flag on the wire.
  - **Keepalives only to peers that said they take them:**
    - Proxy: `ProxyServiceImplTest.kt:1057/1070/1117`.
    - The response streams ignore a keepalive (`:1084/1092`).
    - Agent: `AgentGrpcServiceTest.kt:791/820/840/849`.
  - **The embedded agent's missing shutdown hook:** `AgentTest.kt:383` spies on common-utils' `shutDownHookAction`,
    and its positive half (a directly started agent registers exactly one hook) proves the spy is on the real path.
  - **Each series served once:**
    - `RegistryMetricsSourceTest` fails without `withoutAgent`, or without the default-registry check.
    - `EmbeddedAgentApiTest`'s "each series at one path" fails if `standalone = { !agent.isEmbedded }` flips.
- **Kotest pitfalls:**
  - A brace-matching scan finds no `shouldThrow`, `shouldNotThrow`, or `shouldThrowAny` inside an `assertSoftly`, in
    the root suite or `ktor-plugin`.
  - Every `eventually` and `continually` has a duration.
- **Lincheck:** All nine specs carry `tags(Lincheck)`. None uses MockK, directly or through a helper. Each one reports
  its checks through `@Validate`.
- **Ports and lifecycle:**
  - Every container spec is gated on `RUN_CONTAINER_TESTS`.
  - No non-harness spec uses `HarnessSetup`, `TestUtils.start*`, or a `HARNESS_*` port.
  - `HarnessSetup` stops what it started when a start fails, and polls for a free port rather than sleeping.
  - The starter's and plugin's integration tests stop their proxies.
  - No dashboard spec binds a product-default port.
- **`misc/` specs:**
  - `TestingGuideTest`: every bold name in `docs/TESTING.md` is a real test file, so there are no stale entries.
  - `CiHygieneTest`: every image in the Makefile, `ContainerTestSupport`, the demos' compose files and kind configs, and
    every `FROM` in `etc/docker` and the demos' Dockerfiles, is pinned by digest. CiHygieneTest doesn't read the
    Dockerfiles, but none has drifted.
  - `DashboardMetricNamesTest`: every `proxy_*` and `agent_*` series named in README, `llms.txt`, `docs/`, the charts,
    the demos' READMEs, scripts, and configs, and the website, is declared. The rest are label values, paths, and
    shell variables.
  - `SiteVersionTest` runs `make site-version` with a parent make's flags removed.
  - `LocalTestConfigTest`, and `ConfigFileKeysTest`'s runtime-built `-D` keys (`ProxyOptionsTest.kt:577-613`,
    `ProxyDashboardOptionsTest.kt:147`), whose assertions check the named key in the message.
- **Every `config/test-configs/*.conf` file is loaded by a spec.** `path-takeover.conf`'s 60s retry makes the proxy's
  notice the only thing that can take the path back within the harness's few seconds.
- **Embedding and subprojects:**
  - `EnvironmentSettingTest` and `StartIfConfiguredTest` check secrets across whole cause chains, through the real
    start path.
  - `MetricsSourceTest`'s caps, interrupt, Error-to-503, filtering, and uncounted dashboard fetch.
  - The Ktor plugin's required-agent failure (a bad PEM the builder accepts), `FileSettings` over HOCON, YAML, and maps,
    and the integration test's path dropping after stop.
  - The starter's health states, metadata, and page examples.
- **Dashboards:**
  - Agent and proxy Scrape now count and record nothing.
  - `LoginLimiter` and `DashboardSessions` run on test clocks.
  - The Origin and Host rules, and the escaping of hostile agent values.
  - `SnapshotCacheTest`'s gated concurrency.
  - The activity feeds' caps and DEBUG-repeat folding.
- **Tracing:** `ScrapeTracingTest` covers the sampler rules (`sendHeartBeat` and the notices stream never sampled) and
  `traced()`'s endings. `InProcessZipkinTest`'s sample-rate-0 case pins `rpcSampler`'s wiring.
- **Shared collectors:** `SharedCollectorsTest` covers acquire, release, and registering again after a clear. Every
  `ProxyMetrics` and `AgentMetrics` a unit spec builds is closed.
- **Considered, not reported:**
  - `ScrapeDispatchTest.kt:382/399/466` invalidate after a fixed 200 ms. If the invalidation lands before the request
    is queued, the outcome is the same `agent_disconnected`, so the tests don't flake. They just don't guarantee
    "mid-scrape".
  - `ScrapeDispatchTest.kt:281` tests `AtomicBoolean` itself, and `AgentConnectionContextTest.kt:333-365` asserts a
    counter the test keeps. Both predate the range, and other cases cover the behavior.
  - `AgentTokenAuthTest`, `AgentPathAuthTest`, and `AgentDiscoveryTest` start the agent before the `try` whose
    `finally` stops the proxy, so an agent whose start throws would leave the proxy bound. An agent with a bad token
    still starts, so this happens only on a run that's already failing.
  - `AgentLifecycleTest.kt:143` and `:187` would hang until Kotest's timeout, rather than fail fast, on a regression to
    a synchronous stop. They still fail.
  - `DocsExamplesTest` loads `examples/*.conf`, but not `examples/cloud-proxy`. `ConfigFileKeysTest` loads both.
  - `DashboardMetricNamesTest.demoSources` is a list kept by hand. `demos/ktor/prometheus/ktor-alerts.yml` is left out,
    but it queries no proxy or agent series.
  - `ScrapeResultOwnershipLincheckTest` still keeps its own copy of the ownership predicate, which November #33's
    resolution accepted.
  - Outside this area, and judged harmless by the dashboards pass too: `common/dashboard/DashboardServer.kt:627` waits with
    `withTimeoutOrNull(settings.refreshInterval) { wake.receive() }`, the form AGENTS.md rules out for keepalive waits.
    A wake lost that way costs at most one tick, since the loop collects on the tick anyway.

### Second pass: build, release, and demos

- **`_check-demos-pull-release`** (#537): parses all seven `demo.sh` files, including the Kubernetes demo's at `:33`;
  `sort -V` agrees with `version_after` for three-field versions; it fails at 4.7.0 and 4.6.0 and passes at 4.7.1 and
  4.10.0; a quoted value fails closed; it runs before anything `make release` publishes or tags. Its only gaps are in
  #25.
- **The release gate after #542:** replayed `release-check`'s bumped and stale steps for 4.7.0 over 4.6.0:
  every `RELEASE_VERSION_FILES` file names 4.7.0, and nothing at 4.6.0 is flagged. Every `raw.githubusercontent.com`
  link at the tag is in one of the seven files `RELEASE_VERSION_FILES` gained or had (README, `llms.txt`, `index.md`,
  `grafana.md`, `DockerExamples.txt`, `GettingStarted.txt`, `ConfigExamples.txt`), and each links a file that exists
  (`examples/{simple,myapps}.conf`, `grafana/prometheus-{proxy,agents}.json`). `RELEASE_VERSION_FILES`, RELEASE.md
  step 2, and the skill list the same files. `site-version` still equals `VERSION` at release, since `SiteVersionTest`
  pins it to `DockerExamples.txt`'s tag, which the stale check covers. No CI job runs a demo's `make up`, so the
  lowering commit's required checks don't depend on the release being published.
- **RELEASE.md, the skill, and AGENTS.md** describe the guard, the two moves, and the window in which `master` can't
  pull, consistently with the Makefile.
- **Saved ports (#541):** `load_saved_ports` and `save_ports` are byte-identical in the six Docker demos, and run before
  any URL is built (`:31`); the four screenshot scripts load the file before their URLs too. `save_ports` keeps every
  other line (the Ktor and Spring Boot secrets) and writes through a `umask 077` temp file and `mv`, so the secrets
  file stays private; `write_env` now appends, and its `AGENT_TOKEN` test keeps an existing file's secrets. Compose
  reads `docker/.env` from the compose file's directory, as these demos already relied on. `.gitignore` covers
  `demos/*/docker/.env` and `.env.new`.
- **Kubernetes ports (#541):** `cluster_port` reads the node container's mapping only when the variable is unset;
  `check_ports` then compares equal ports; `published_port`'s `|| true` keeps a missing cluster from tripping
  `pipefail`. `TestPortsTest` still sees the demo's 8294/9290/8280 through the kind `hostPort`s, now that `demo.sh` no
  longer spells them `${…_PORT:-n}`.
- **Script health:** `bash -n` and `shellcheck` 0.11.0 are clean on all seven `demo.sh` and five `screenshots.sh`, as at
  `56b1c355`; every `demo.sh` prints its help under macOS's bash 3.2, and the empty-array expansions
  (`${on_standby[@]+…}`, `${#back[@]}`) work there. `wrap`'s awk and `tour_ending` are identical in all seven, and each
  tour calls `tour_ending` with its own name. `docker compose --progress quiet` is accepted since at least Compose
  2.20.0 (`cmd/compose/compose.go` maps `quiet` and `none`).
- **nginx (#536, #540, #541):** `client_max_body_size 0` is at server level in all three agent entrances and
  `nginx/docker/nginx.conf`, so each `location /` inherits it; the variants differ only in their timeouts. The reload
  wait lists workers with busybox `ps -o pid,args` and anchors on `worker process$`, so old workers retitled "is
  shutting down" drop out and it waits for new ones; its exit codes 1 and 2 map to the two messages.
  `keepalive_number`/`keepalive_vs` cover off, unknown, sooner, same, and later.
- **Network (#540, #541):** the Zipkin link's span name matches Brave's lowercased `ProxyService/connectAgent` (the proto
  has no package), and `ScrapeTracing.kt:70-71` never samples heartbeats or path notices, as the text says.
  `proxy-down proxy-standby` reads the standby's agents before stopping it; `kb` prints 7.5 for 7,500 bytes; a failing
  tour step now names the part to restart.
- **Ktor (#540, #541):** `shop` (`curl --fail-with-body`) separates a refusal from no answer; `order` splits the status
  code from the body; `baristas` validates digits before the `10#` arithmetic; `deploy` and `rollback` with nothing to
  do return early; the CoffeeShopErrors timing matches its 30-second rate window and `for: 10s`.
- **Spring Boot (#538, #540):** `versions` reports the roastery's version relative to the agent's in the right
  direction; the rotation has 4/3/2/1 of the origins as its comment says; the 2-second `@Scheduled` matches "about 30
  in all"; `ObjectProvider<BuildProperties>` lets the roastery start without build-info; `RoasteryAgentTest`'s
  `PER_CLASS` lifecycle allows the non-static `@AfterAll`, and the starter's `stop()` is idempotent
  (`PrometheusProxyAgent.java:115-122`), so the context's own stop at JVM exit is harmless; `application.yml`'s
  authority default matches `EmbeddedAgentConfig.kt:575-577`.
- **The bounded shutdown hook (#538)**, against the JVM's shutdown sequence: on SIGTERM the hooks run while `main`
  continues, and `main`'s return detaches its thread, so `join` returns once `use { }` has stopped the agent; on a
  normal return or an uncaught exception, `DestroyJavaVM` runs the hooks after `main` has ended, so `join` returns at
  once, and the launcher exits 1 after an exception; `System.exit`/`exitProcess` from `main` runs the hooks and waits
  for them, so the hook's `join` holds the JVM until its 25 seconds are up, exactly as the comments and the Embedded
  Agent page say. The Kotlin and Java examples, the demo's `Main.kt`, its README copy (identical), and the Plain Kotlin
  Demo page agree; the renamed `plain-kotlin-java` snippet section is the one the page includes.
- **Demo text checked against code:** agent-east's six static paths; the proxy Activity view's "released N paths"
  (`Proxy.kt:598`); the 413 explanation; the image renames (`ktor-app`, `plain-kotlin-app`) leave no reference to the
  old names; `examples/simple.conf` changed only in comment wrapping.
- **Considered, not reported:**
  - "To end with an error, throw from `main()` instead: the JVM then exits with status 1" assumes nothing but `main`
    keeps the JVM up: `DestroyJavaVM` waits for every non-daemon thread before it runs the hooks, so a worker whose
    `stop()` is what ends its own threads would hang. The page's examples do their work on `main`, so it holds for them.
  - The 25-second bound is moot in the demo: Docker's default stop timeout is 10 seconds, and an oven bakes for about
    two.
  - `roasts.getAndIncrement() % ROTATION.length` goes negative after 2^31 batches, about 136 years at one every two
    seconds.
  - After the image renames, a demo user's old `embedded-app:local` and `worker-app:local` images stay, since
    `start_demo` removes only images under the current names. A one-off few hundred MB.
  - `bin/docker-agent.sh:10` still fetches `simple.conf` from `master`, while #542 moved the docs' links to the release
    tag; `docker.md:29` links `blob/master`. A dev script, and a page for the docs reviewer.
  - The Kubernetes demo's scripts run `docker port` three times on every command, `help` included; quiet and harmless
    without Docker.

### Second pass: documentation

- **`README.md`'s diff:** every config and dashboard it now fetches at the `4.7.0` tag (`examples/myapps.conf`,
  `examples/simple.conf`, and, on the site, `grafana/prometheus-{proxy,agents}.json`) exists at that path, and
  `myapps.conf` is unchanged since 4.6.0, so a Homebrew agent still at 4.6.0 can read it. `release-check`'s stale
  pattern (`/prometheus-proxy/<previous>/`, `Makefile:645`) catches a URL the next bump misses. The nginx note matches
  `nginx/docker/nginx.conf`, which sets `client_max_body_size 0`. The demos table's times match each demo's
  `TOUR_PARTS` totals (28, 24, 21, 15, 23, 11, and 12 minutes).
- **`llms.txt`'s diff:** one URL, at the release's tag, the same as the README's.
- **Changelog coverage:** each of the seven commits maps to entries: #536 to the Documentation entry and the release
  notes' "Nginx's request body limit" and "default timeouts" wording; #537 to Build & Tooling; #538 to the roastery bug
  fix, the shutdown hook's 25 seconds, and the `application.yml` and `logBanner` entry; #540 to the two demo-accuracy
  entries; #539 and #542 to the docs-site entries; and #541 to the moved-ports entry. `RELEASE_NOTES.md`'s new bullets
  agree with them.
- **Changelog claims against the code:**
  - `RoasteryController` takes `ObjectProvider<BuildProperties>` and shows `unknown` without it.
  - `Roastery.status()` prints `Roasted stock:`, and `ROTATION` interleaves 4/3/2/1 batches in ten, 12/9/6/3 a minute,
    so the lines can't cross.
  - `RoasteryAgentTest` stops the agent before the proxy.
  - All four shutdown hooks (the oven's `Main.kt` and the three examples) wait with `join(25000)`.
  - `_check-demos-pull-release` refuses a `NEEDS_RELEASE_AFTER` not below `VERSION`.
  - `client_max_body_size 0` is in `nginx/docker/nginx.conf` and the three `agents-*.conf`, and `kubernetes.md:124`
    gives the Ingress annotation.
  - Every one of the site's nine Mermaid diagrams has `accTitle` and `accDescr`.
  - The headings are 2em, 1.5em, and 1.125em (32, 24, and 18px).
  - The Ktor and plain Kotlin images are `ktor-app` and `plain-kotlin-app` in both `compose.yml` and `demo.sh`.
  - Each of the seven demo pages has a How it works column.
  - The CLI Reference has an anchor per key (`#agent.scrapeTimeoutSecs`).
- **The demos' READMEs against `demo.sh`:**
  - **Moved ports:** `save_ports` writes only the `*_PORT` variables `compose.yml` reads and keeps the file's other lines,
    so the Ktor and Spring Boot demos' secrets survive. Their `write_env` now appends, and skips only when
    `AGENT_TOKEN` is there. `load_saved_ports` lets a variable on the command line win. `.gitignore` covers
    `demos/*/docker/.env`.
  - **Kubernetes ports:** `cluster_port` reads a running `pp-monitoring`'s ports back, and `check_ports` refuses a
    variable that asks for another, with the message the README quotes.
  - **`make open`:** each new row matches its demo's `cmd_open`.
  - **Network:** the tour's reset runs `heal`, and `unfilter` only when `branch_filtered`. NetworkTargetDown's
    `for: 15s` and NetworkAgentNotOnPrimary's `for: 15s`, at a 5-second evaluation, fit the stated 25 and about 20
    seconds.
  - **Ktor:** `CoffeeShopErrors`' 30-second ratio and `for: 10s` match the README.
  - **nginx:** `keepalive` and `reset` stop nginx, recreate the proxy, and start nginx, so each agent reconnects once.
    The agent opens `readPathNoticesFromProxy` before its write streams (`Agent.kt:481-512`), which fits part 3's "with
    the 504 as its reason". The other demos' published ports never equal nginx's, as its Troubleshooting says.
  - **Basics:** agent-east's `proxy_metrics` path carries `job: prometheus-proxy`, so freezing agent-east does stop the
    proxy's metrics and `up{job="prometheus-proxy"}`.
  - **Kubernetes:** the kind node image is `v1.37.0`, and `check_tools` checks kind's and Helm's versions, not
    kubectl's, as the README says. `config/agent-change.conf` turns on the standard, memory-pool, GC, and thread
    groups. The proxy's `Invalid path request` log line is `ProxyUtils.kt:91`.
  - **Plain Kotlin:** the README's `main` matches `Main.kt`, and the `logback.xml` row matches the file. The scale-in
    text matches `demo.sh:546-571`.
  - **Every README:** its list of the other demos follows `website/prometheus-proxy/docs/demos/index.md`'s order.
- **AGENTS.md's diff:** its two hunks are the Spring Boot demo paragraph and the release paragraph, both outside this
  pass's part of it.
- **Considered, not reported:**
  - The nginx README's part 1 says "Every entrance in `nginx/` turns the limit off", and the CHANGELOG says "the nginx
    demo's entrances". Only the three agents' entrances do; the `dashboard-*.conf` ones don't, and needn't, since the
    dashboard's requests and its WebSocket carry no long body.
  - `RELEASE_NOTES.md`'s "The demos remember a moved port … `make up` saves it in the demo's `docker/.env`" doesn't fit
    the Kubernetes demo. It has no such file and reads its cluster ports back from `pp-monitoring`, and its
    `KUBERNETES_DEMO_AGENT_DASHBOARD_PORT` is neither saved nor read back. The CHANGELOG and the Kubernetes README say
    it right.
  - The CHANGELOG's "The network demo's tour puts the demo back … before a part": it does so once, before the first
    part it runs, which is what `make tour PART=n` needs.
  - The README's and `llms.txt`'s config URLs at the `4.7.0` tag 404 until the tag is pushed. The same goes for the
    `4.7.0` image tags beside them, and the release pushes all of them.
  - The Spring Boot README's list of the other demos lacks the "in the order the docs site's Demos page gives them"
    line the other six have; its order is the same.

### Second pass: configuration reference

- **Every settings row on the three pages, against the code.** The pass extracted all 171 key rows on
  `configuration/agent.md`, `configuration/proxy.md`, and `cli-reference.md`:
  - **Keys** are all in `config/config.conf`.
  - **Options:** each role's alias sets in the CLI Reference equal the `@Parameter` names of `BaseOptions` plus
    `AgentOptions` or `ProxyOptions`. That includes the short forms (the proxy's `-p` is `--port`, the agent's
    `--proxy`), `--tf-disabled`/`--tf_disabled`, `--ref-disabled`/`--ref_disabled`, and `--override`/`--over`.
  - **Variables:** each one is paired with the key whose `resolve*Option` call reads it. The env-only settings are
    right: both dashboard logins and the PEMs. `--max_content_length_mbytes` correctly shows no variable
    (`AgentOptions.kt:495-497`). All 57 `EnvVars` entries appear.
  - **Agreement:** every configuration-page row that names an option or variable matches the CLI Reference's row for
    that key, in option, variable, and default.
  - **Links:** every link fragment equals its key and resolves to a `<span id>` that equals its Property cell.
- **Defaults:**
  - The numbers, booleans, and strings match `config/config.conf`, and the config-only keys match `ConfigVals`'
    readers.
  - The gRPC defaults match grpc-java 1.85.1's builders: 120, 7200, 20, 300, and disabled for the server; disabled and
    20 for the agent's channel.
  - `"info"` for both `logLevel`s matches `logback.xml`'s root `INFO`.
  - `proxy.http.idleTimeoutSecs`' `-1` means 45 (`ProxyHttpService.DEFAULT_IDLE_TIMEOUT_SECS`).
  - The chunk and gzip maximums, 4032 and 4128768, are `MAX_GRPC_PAYLOAD_BYTES`.
- **Descriptions:**
  - Gzip runs "above" the threshold (`size > minGzipSizeBytes`, `AgentHttpService.kt:497`), and a chunked result is "at"
    the size (`ScrapeResults.isChunked`).
  - The scrape timeout is the smaller of the agent's and Prometheus's (`AgentHttpService.kt:380-385`).
  - Each of these is read where the page says: `recentRequestsQueueSize` (`Proxy.recentReqs`), `requestLoggingEnabled`,
    `reserveJobAndInstanceLabels`, the admin paths and `metrics.path` (`ConfigWrappers`), and `Unnamed-<hostname>`
    (`Agent.kt:210`).
  - `--ref-disabled` only turns reflection off, and every other boolean flag only turns its setting on, from a `false`
    default.
- **Precedence prose:** the CLI Reference's intro and Dynamic Properties section, `configuration/index.md`,
  `ConfigExamples.txt`'s `precedence-example`, and the configuration pages' "Resolved from" lines.
  - All match `BaseOptions`: `resolve*Option` takes the command line, then the variable, then `configVals`.
  - `readConfig` merges `-D` into the config before options resolve, so a variable beats `-D`.
  - Booleans count as set from the command line only when the flag is present (`isFlagInArgs`).
  - The PEMs and dashboard logins take the variable, then the config.
- **Embedded agents:** these pages say only that a standalone agent serves its JVM metrics at both the port and the
  proxy path (`configuration/agent.md:496-500`). That matches `registersJvmMetrics` (`Agent.kt:289-290`, `!isEmbedded`),
  and nothing on them claims an embedded agent ignores more.
- **Glossary:** each entry matches the code:
  - paths: the single segment, and the refused whitespace, control characters, `?`, and `#`;
  - consolidated merging and the chunking threshold;
  - the dashboards' ports, defaults, and login;
  - stale-agent eviction at 60 seconds, linked to `configuration/proxy.md#internal-settings`;
  - the agent token's option and variable, and the transport filter.
- **`ConfigExamples.txt`:**
  - Every section's keys are declared.
  - #542's edits are comment removals, plus the `config-from-url` tag.
  - That `4.7.0` link resolves once `make release-tag` pushes the tag, which comes before `make gh-docs` deploys the
    site.
  - `ConfigExamples.txt` is in `RELEASE_VERSION_FILES`, so `release-check`'s new `/prometheus-proxy/$p/` pattern
    refuses a stale tag there.
- **Anchors:** every link from any page into the two configuration pages, the CLI Reference, the glossary, and
  `configuration/index.md` resolves. The CLI Reference's renamed headings keep their old ids through `{ #… }`
  (`proxy-grpc-options`, `agent-http-client-cache-options`, `agent-grpc-options`).
- **`tables.js`** breaks lines with `<wbr>` elements, which add no characters, so a key, option, or variable copied
  from a table is unchanged.
- **Considered, not reported:**
  - **Dashboard examples.** `configuration/agent.md:449` and `configuration/proxy.md:161` show `password = ""` beside
    the comment "Requires a login". The comment describes the key while the value leaves the dashboard open. The table
    row below says "empty leaves it open", and the block binds `127.0.0.1`.
  - **Lists through `-D`.** `-D` can set a list by index: `-Dagent.proxy.endpoints.0=a:1 -Dagent.proxy.endpoints.1=b:2`
    gives `[a:1, b:2]` through Typesafe's conversion of a numeric-keyed object, checked with Config 1.4.10.
    `configuration/proxy.md:89-90` and `:192-193` ("must be set in a config file rather than with `-D`") are therefore
    stricter than the code. This is older than the range, and harmless.
  - **Missing sections.** The agent page has no sections for `agent.admin`, `agent.tls`, `agent.grpc`, `agent.logLevel`,
    or `agent.internal`. The CLI Reference covers the ones that have options. This is an omission.
  - **`INT_MAX`.** Read in seconds, it stands for gRPC's disabled limits, which are `Long.MAX_VALUE` nanoseconds. Both
    mean no limit, as `configuration/proxy.md:54` says.
  - **Validation list.** The CLI Reference's Validation section leaves out `--dashboard_port`'s `1..65535` check. This
    is an omission.
  - **`--client_timeout_secs`.** Given as 90, it loses to a changed `agent.internal.cioTimeoutSecs`
    (`AgentHttpService.kt:692`), an exception to "an option overrides". It applies only to the deprecated key, and
    only at the default value.
  - **Dotted fragments.** Fragments such as `#agent.http.clientCache.maxSize` resolve by id in a browser. They weren't
    exercised with the theme's instant navigation.

### Second pass: the docs site

- **Build and links:** `make build-site` builds with no issues. Every relative link and anchor in the built site
  resolves (the 404 page's root-absolute links are right for `site_url = https://prometheus-proxy.com/`), including
  every link #539 and #542 added to settings sections (`configuration/proxy.md#internal-settings` lists
  `maxAgentInactivitySecs`), the pre-flight checklist's links, `security/index.md#onboarding-a-team`, and the
  `spring-boot-demo.md#spring-boots-versions-of-the-agents-libraries` links. The headings #539 renamed keep their old
  anchors with `{ #… }` (`embedded-agent.md`'s `#usage`, the basics demo's scenarios); nothing in the repo linked to
  `getting-started.md#start-the-agent`, which `### Start It` dropped, and every `prometheus-proxy.com/…#…` link in the
  README, `llms.txt`, `docs/`, the demos, the charts, and the sources resolves. No `---` rule is left on any page to
  stack with the new h2 hairline.
- **Try It (`index.md:34-62`):** in order, in the three terminals it names, it works as written: Terminal 1 creates the
  network before the proxy uses it; `METRICS_ENABLED=true` opens the proxy's 8082; the agent's `AGENT_CONFIG`, the
  4.7.0 tag's `examples/simple.conf` (only its comments changed in #542), takes `PROXY_HOSTNAME` for both the connect
  and the proxy's metrics URL and turns on the agent's 8083; both `curl`s get text-format metrics. The images' exec-form
  `ENTRYPOINT` makes Ctrl+C reach the JVM. The 4.7.0 tags, `make site-version`'s answer, match every image tag and
  `--branch` on the pages, and every `raw.githubusercontent.com/…/4.7.0/` link is in a file `RELEASE_VERSION_FILES`
  lists, which `release-check`'s new `/prometheus-proxy/$prev/` pattern now covers. "The Demos page lists six more"
  matches `demos/index.md`'s seven.
- **Quick Start:** the `proxy-host.example.com` note, `### Start It`, the agent log lines (`AgentGrpcService.kt:447`,
  `AgentPathManager.kt:815`), and the Verify step's 200 (the 404 text is #37's).
- **Security, Onboarding a team (#539):** `${TEAM_B_TOKEN}` resolves from the environment (`BaseOptions.kt:751`,
  `ConfigResolveOptions.defaults()`), and an unset one fails the load; identities are read only at startup; Settings'
  Agent authentication lists `Identity team-b` and `paths team_b_*` and never a token (`ProxySettings.kt:89-106`); the
  Agents view tags the identity and the Paths search matches it (`ProxyDashboardHtml.kt:83, 271`); a pattern miss is
  Activity's `rejected` (`ActivityLog.kt:49`) and the agent dashboard's `refused`, `NOT_AUTHORIZED` being outside
  `RETRYABLE_CAUSES` (`AgentPathManager.kt:959`, `AgentSnapshot.kt:318-327`); a removed identity's agents get
  `UNAUTHENTICATED` (`AgentAuthServerInterceptor.kt:72`); a registered connection that drops gets a new channel, so a
  quick restart is a quick reconnect (`EndpointFailover.kt:44-47`). `production.md`'s "adding or revoking one takes a
  proxy restart" agrees.
- **Embedded Agent and the shutdown examples:** `mainThread.join(25000)` in both languages and in
  `demos/plain-kotlin/app/.../Main.kt:25`; `System.exit()`/`exitProcess()` from `main` waits for the hooks, so the hook
  waits out its 25 s; a `main` that throws exits 1 once its non-daemon threads end, the hook's join returning at once on
  the terminated main thread. The renamed `plain-kotlin-java` section renders in both tabs.
- **Spring Boot pages:** `grpc-java.version` and `netty.version` are Spring Boot 4.2.0's property names; Netty's BOM
  (4.2.20.Final) sets netty-tcnative through `tcnative.version`, which Spring Boot doesn't name; `./demo.sh versions`
  exists; the starter's default `SmartLifecycle` phase stops the agent before `WebServerGracefulShutdownLifecycle`;
  Scrape now is the circular-arrow button after the path in the agent dashboard's path pane
  (`AgentDashboardHtml.kt:297-302`).
- **nginx body limit (#536):** `nginx/docker/nginx.conf:18` and all three of the nginx demo's agent entrances set
  `client_max_body_size 0`; NGINX Ingress's `proxy-body-size` default is 1m.
- **Demo pages:** each page's port variables against its compose file, and `save_ports`/`load_saved_ports` writing and
  reading `docker/.env` (the Ktor demo's secrets kept beside them); the Kubernetes demo reading ports back from the
  clusters, its node image `kindest/node:v1.37.0` and the kubectl 1.36-1.38 skew, and `Invalid path request`
  (`ProxyUtils.kt:91`); every new "How it works" cell in the Ktor, Spring Boot, plain Kotlin, and nginx tables against
  `demo.sh` and the compose profiles (`deploy`, `misconfigured`); the plain Kotlin oven's `logback.xml`; the eviction's
  Activity detail, "released N paths" (`Proxy.kt:588-599`); the network demo's alert `for:`s (15 s); every new
  `accDescr`'s ports and names against the compose files; "the other demos" lists in the Demos page's order.
- **`tables.js`:** the three header keys it matches are exactly the built tables' first two `th`s (16 CLI Reference
  tables, 6 and 7 settings tables), and no other page's table matches; it binds once to `document$` and marks each
  table and code element once (`data-kind`, `data-breaks`), so instant navigation neither misses a page nor doubles a
  `<wbr>`.
- **`code.js`:** `document$` once, idempotent, and nothing to throw on a page with no code.
- **`diagrams.js`:** the theme renders, `replaceWith`s, then calls `bindFunctions` with the shadow root (the bundle's
  `mermaid.render` call), so `host.after` has a parent; `svgText` and `descriptionOf` tolerate a missing `title`,
  `desc`, or sibling; the description ids stay unique across instant navigations; the dialog measures after
  `showModal`; the diagram is a focusable `role=button` with a visible ring, Enter and Space open it with the page's
  scroll prevented, the dialog is labelled with the diagram's title, its Close button is labelled, and a scrolling
  dialog takes the focus for the arrow keys. Every Mermaid block on the site has an `accTitle` and `accDescr`.
- **Contrast (computed, both schemes):** light links #b53f1c 5.69 on white, 5.20 on code, 4.84/5.01/4.75 on the
  note/warning/danger grounds; secondary text 4.74 (4.64 on code, 4.55 on a callout); the callout icons 4.59, warn
  5.07, crit 4.88; the pills 4.71 (ok) and 4.88 (crit); the highlighter's colors on the new #f2f5f8 code ground
  4.51-4.69; focus rings 6.69. Dark: links 7.13 (5.83 on a callout), secondary 5.28, pills 6.33 and 5.10, callout
  icons 4.88, warn 6.29, focus ring 9.87. Only callouts and the admonition types the site uses (note, tip, info,
  warning, danger) are recolored, and the tasklist override wins over the theme's `:root` rule.
- **Motion:** nothing #539-#542 added animates; the one transition is the code buttons' 125 ms opacity fade, from
  before.
- **Considered, not reported:**
  - The diagram's hidden description paragraph isn't `aria-hidden`, so in browse mode a screen reader can read it
    twice, as the button's description and as the next line. A trade for a description that `aria-describedby` can
    reach; verbosity, not a failure.
  - `diagrams.css:65-66` says the arrow keys scroll a tall diagram's dialog "while the focus is on its Close button",
    and `diagrams.js:143-144` says they didn't, which is why the dialog takes the focus. A comment, not behavior.
  - A tall diagram's copy on a phone, where the dialog's view is narrower than the page's column, is drawn a little
    smaller than on the page, not "larger" (`diagrams.css:65`); it still scrolls whole.
  - `content: "→" / ""` drops the home page's row arrow in a browser without the alternative-text syntax; the row
    stays a link.
  - The network demo's tour says `NetworkTargetDown` clears "about then", at the failover around 50 s; it can clear a
    few seconds earlier, once the eviction (30 s, checked every 10 s) drops the paths from `/discovery`.
  - Terminal 3's `curl`s can 404 if run before the agent has fetched its config and registered, a few seconds after
    Terminal 2 starts; the page doesn't say to wait, but a reader retries.
  - The basics page's quoted log line elides the logger location and thread `proxy_said` prints; an abbreviation.

### Third pass: build, release, demos, and tests

- **The release gate after #546:** the home page names no version, so dropping `index.md` from `RELEASE_VERSION_FILES`
  keeps the `missing` check from failing on a page with nothing to bump. Every file with a bumpable literal (an image
  tag, a coordinate, a `<version>`, a chart's version, a `--branch`, a raw link at the tag) is still listed, and the
  raw links at the tag are in six files, all listed. Step 2 and the Makefile list the same files; only the skill
  doesn't (#70). Neither file changed its line count, so #25's, #61's, and #62's line numbers hold.
- **`DemoTourTablesTest`:** passes at `5c9f0a24`; the four planted errors it targets each failed the right test; it's
  named in `docs/TESTING.md`'s misc section, and `TestingGuideTest` and `TestPortsTest` pass.
- **The URL tables (#543):** all 86 rows in the seven READMEs and pages name a port variable whose default in
  `docker/compose.yml` and `demo.sh` equals the table's port, and every Docker demo's variable is in both its compose
  file and its script. The Kubernetes demo's come from `cluster_port`'s defaults (`demo.sh:58-60`).
- **Script health:** `bash -n` under macOS's `/bin/bash` 3.2.57 and `shellcheck` 0.11.0 are clean on all seven
  `demo.sh`, and each prints its help under bash 3.2. `put_back` and `join_commands`, extracted from the basics
  demo and run under bash 3.2 against a stand-in script: they join the commands into the prompt and pass through only
  the bold headlines. A failing command prints all it said and ends the tour, since every script sets `pipefail`.
  Every `put_back` caller passes single-word commands; the network demo's failure message names `TOUR_PART`, which
  `part` sets, and its `cmd_tour` heals before any part, as the message says.
- **The tours against the code:**
  - the Ktor demo's "/healthcheck reports the agent itself, not its connection": common-utils 5.3.0's
    `thread_deadlock`, `metrics_service`, and `all_services_healthy`, and the agent's `dashboard_service` and
    `scrape_request_backlog_check`; none reads the connection;
  - the Spring Boot demo's UNKNOWN, not DOWN (`PrometheusProxyAgentHealthIndicator.java:45-56`); `health`'s new
    `sed` reads the component JSON its existing print does;
  - the network demo's "reconnects in milliseconds": `reconnectLimiter.acquire()` after a 15-second connection
    returns at once;
  - the nginx demo's "keepalives flow only when both ends are newer" matches the registration handshake;
  - every README's tour table against its tour, and the Guided Tour sections against the steps #548 merged and the
    put-backs it unnumbered;
  - the Spring Boot (11 minutes), Ktor (23), and plain Kotlin (12) totals against `TOUR_PARTS`.
- **Diagrams (#549):** the `linkStyle` index in the README diagrams (basics, Spring Boot) and the site's demo,
  Kubernetes, and home page diagrams picks the agent-to-proxy edges, leaving each arrowhead at the end that's dialed.
- **Links:** every `](#…)` in the seven demo READMEs and both chart READMEs resolves to a heading by GitHub's slugs, and
  every relative file link exists. The charts' title-cased headings keep their anchors (`#more-than-one-replica`).
- **#545:** 9758 and 9759 are unique in `TestPorts`, below 32768, published by no demo, and used nowhere as a literal.
  The refused port's name isn't `HARNESS_*`, so a unit spec may use it. `awaitPortFree`'s failure carries the last
  `BindException`, which `HarnessHelpersTest` pins, and `docs/TESTING.md` describes both.
- **#547:** the profile's values match the changelog entry, and `docs/TESTING.md:42` matches the build script.
- **Considered, not reported:**
  - `DemoTourTablesTest`'s slug doesn't collapse runs of separators as Python-Markdown's does, so a page heading
    with " — " would fail a correct link, a false failure rather than a false pass; no heading has one. And each check
    stops at the first demo that fails, so one run names one broken demo.
  - The Ktor plugin's `closedPort()` (`TestSupport.kt:22`) is the close-then-reuse pattern #545 removed from the root's
    specs. Its callers need only a proxy the agent can't reach, which a stray listener on the port also is, and it
    sits outside the root's guards (#59).
  - `put_back` leaves its `mktemp` file behind if the presenter interrupts a command.
  - The unnumbered put-backs no longer print what their command shows, such as `fix-machine`'s alert resolving. That's
    the stated intent.
  - The appendix's second-pass bullet on the release gate counts seven raw-link files, `index.md` among them; after
    #546 there are six.

### Third pass: documentation

- **Job 1's places:** every doc each of the 26 findings quotes or names was read at `5c9f0a24`. #546's heading changes
  are case-only on every page these findings cite, so no cited line moved there, and they keep every anchor a
  finding's Fix links to (`#proxy-failover`, `#nginx-reverse-proxy`, `#spring-boots-versions-of-the-agents-libraries`,
  `#onboarding-a-team`, `#exposure`). The only lines that moved are the four edits above (`demo.sh` twice,
  `kubernetes.md`, `docs/TESTING.md`).
- **`README.md`'s diff:** four headings. Three are case-only, so their anchors are unchanged. "Key Benefits" is now
  "Benefits", and nothing in the repo links to `#key-benefits`, or to the site's old `#key-benefits` and
  `#common-use-cases`. The table of contents lists only the emoji sections. The demos table's parts and minutes still
  match each `TOUR_PARTS` after #548's merges: 11/28, 7/24, 4/21, 5/15, 9/23, 5/11, and 5/12. "Each demo's README
  lists what it needs" still holds after #549 dropped What You Need, since each README's Quick Start says it in a
  sentence.
- **`SECURITY.md` and both charts' READMEs:** heading case only. `values.yaml`'s comment names the new "More Than One
  Replica", and the Values table's `#more-than-one-replica` link still resolves. The proxy chart README's commands and
  values are unchanged.
- **`CHANGELOG.md`'s one new entry (#547)** against `HarnessConfig` and `build.gradle.kts:366-372`: XXLARGE runs 30
  HTTP servers, 100 parallel queries, 1,000 concurrent clients, and a 16g heap; XLARGE runs 500 clients. It sits in
  Build & Tooling with the other test-infrastructure entries.
- **`docs/TESTING.md`'s diff:** `make xxl-tests`' 16g matches `maxHeapSize`. The HarnessHelpersTest entry matches #545's
  spec (`HARNESS_HELPERS_FREE_PORT`, and the failure's `BindException` cause, which the entry needn't name). The
  DemoTourTablesTest entry is in the `misc/` section that `TestingGuideTest` requires, and its `PAGE_URL` clause
  matches the spec and `demo.sh`, which prints `${PAGE_URL}#what-this-demo-shows` from `make up` and the tour.
- **`docs/RELEASE.md`'s diff:** step 2 and `RELEASE_VERSION_FILES` dropped `index.md` together, and `index.md` names no
  version and no tagged link. The files with `raw.githubusercontent.com/…/4.7.0/` links are exactly the six step 2
  lists (`README.md`, `llms.txt`, `grafana.md`, and the three snippet sources). `SITE_VERSION_SOURCES` still scans the
  whole docs directory, so a version put back on the home page would still reach `make site-version`.
- **Considered, not reported:**
  - `make xxl-tests` runs `unitTest` and `harnessTest` at once, each capped at 16g. A cap isn't an allocation, and only
    the harness JVM runs the XXLARGE load.
  - `.impeccable/config.json` exempts all of `extra.css` from its side-tab rule, with the reason that the home page's
    `.pp-flow__wall` is a divider. #546 removed `.pp-flow`, so the exemption now covers nothing it names. It's a design
    tool's config; #70 covers it.
  - `DemoTourTablesTest` reads `demos/` and the site's pages, which aren't `Test` inputs: a new place where #29 shows.
    #29 now names it.
  - `DemoTourTablesTest.slug` drops punctuation without collapsing runs of hyphens, GitHub's rule, which the READMEs
    need. On the site, a heading with `&` or a spaced dash would get a different anchor from Python-Markdown than from
    the spec. No demo page heading below a table has either today.
  - README's "nginx Reverse Proxy" heading over a body that says "Nginx", and the chart README's "More than one replica"
    link text beside the title-cased heading: cosmetic.
  - #545 needs no changelog entry: it fixes two flaky specs' ports and an internal helper's exception cause.

### Third pass: the docs site

- **Build and links:**
  - **Build:** `make build-site` builds with no issues.
  - **Built pages:** a script over the built HTML (39 pages) found no broken relative link or anchor, `kdocs/` aside,
    which `docs.yml` copies in.
  - **Site links elsewhere in the repo:** every `prometheus-proxy.com/…` link in the repo (100 distinct) names a built
    page, except the three to `kdocs/`. They are in the README, `SECURITY.md`, `llms.txt`, the charts' values,
    `NOTES.txt` and READMEs, and the demos' `PAGE_URL`s and the Ktor tour. All 10 with a fragment resolve, among them
    `kubernetes/#updating-targets-without-restarting-the-agent` in the agent chart's values.
  - **Anchors the demo scripts build:** every demo script's `${PAGE_URL}#what-this-demo-shows`, the Kubernetes demo's
    `#running-it`, and the plain Kotlin tour's `#the-workers-code` resolve.
  - **Anchors in the Markdown:** every `](…#…)` anchor in the 42 Markdown files outside the site (READMEs,
    `SECURITY.md`, the demo and chart READMEs, `docs/`) resolves under GitHub's slug rules. Nothing links to the
    dropped `#what-you-need` or `#try-it`, and nothing links to a GitHub page with an anchor.
  - **Title case:** the change keeps every generated id, since the slugs are lowercase. The basics demo's scenarios
    keep their `{ #… }` ids.
- **The home page, against the code and the other pages:**
  - Ports 8080 and 50051.
  - "Return to a recovered primary at their next reconnect" (`EndpointFailover`: a connection that registered and then
    dropped retries from the head).
  - Per-agent identities, filtering at the agent, and the dashboard's Scrape now.
  - The benefits and use cases match the README's.
  - "The Demos page lists six more" matches the seven demos.
  - The Quick Start is the reader's next step: Install it is Next Steps' first row, and the page footer's Next is
    Quick Start.
  - The 404 page's links don't depend on the removed parts.
- **The restored diagram:**
  - It has `accTitle` and `accDescr`, and the description matches the drawn edges, with no hyphen split across a line.
  - Rendered from a local server of the built site with headless Chrome at 1280 and 500 px: the gRPC edge has its one
    arrowhead at the proxy, Inside Firewall sits left of the line, and the 500 px drawing scales to about three
    quarters. `linkStyle … marker-end:none` works because an inline style beats Mermaid's `marker-end` attribute.
  - Every two-headed edge on the site has its index in its diagram's `linkStyle`: `index.md` 1, basics 2–4, network
    3–6, nginx 2–5, Kubernetes Demo 1, `kubernetes.md` 2, Ktor 2, Spring Boot 1, plain Kotlin 2. The arrowheads that
    remain point toward the proxy: nginx's at the proxy for its two proxy edges and at nginx for the agents'.
- **What This Demo Shows tables:**
  - **Page against README:** each page's rows match its README's row for row, name, What, and Why.
  - **Against `TOUR_PARTS`:** the parts and their order match, and the README's part counts and minutes (28, 24, 21,
    15, 23, 11, 12) are its sums.
  - **Each row against the tour's text in `demo.sh`**, among them:
    - basics' 30-second eviction;
    - the Ktor coffee shop's routes (no `/metrics`), the five admin endpoints `debug` lists, and `/healthcheck`
      staying healthy with the proxy gone (`scrape_request_backlog_check`, with no backlog while disconnected);
    - Kubernetes "at the cost of a scrape" (`delete-agent-pod`'s about a second of failed scrapes);
    - the network demo's 250 ms each way, 15-second flaky link, reconnect in milliseconds, and the single
      `NetworkNoAgentsConnected` once targets leave `/discovery`;
    - nginx's per-agent tokens (`proxy.auth`), "keepalives flow only when both ends are newer" (each side waits for
      the other's say-so at registration), and the two dashboard settings;
    - the plain Kotlin demo's three failing starts (`no-proxy`, `bad-cert`, `wrong-token`);
    - the Spring Boot demo's UNKNOWN agent component with the app UP (`PrometheusProxyAgentHealthIndicator`, Spring
      Boot's status order) and the agent stopping before the graceful shutdown.
  - Every row's link lands on a section that covers the part.
- **URL tables (#543):**
  - Every variable and default port matches its compose file's `${VAR:-port}`, and `save_ports` saves every
    `_PORT` variable compose reads, the rogue and copy dashboards' included.
  - The Kubernetes demo's three cluster variables go through `cluster_port` and kind's `listenAddress: 127.0.0.1`.
  - "The proxy dashboard's links keep the default ports" matches each demo's `config/proxy.conf`.
- **Removed CSS:** the 97 lines styled only `.pp-lede`, `.pp-result`, and `.pp-flow*`. No page, override, script, or
  the social card uses them (the card has its own `.flow`). `.pp-output`, `.pp-status`, and `.pp-ways` remain and are
  used.
- **`zensical.toml`:**
  - Home is a top-level item with its own icon (`lucide/house`), and Get Started is a plain section label.
  - The footer runs Home → Quick Start → Architecture.
  - No CSS rule targets the old place, and the other sections' index pages are unchanged.
- **Release lists:** the Makefile's `RELEASE_VERSION_FILES` and `docs/RELEASE.md` step 2 dropped `index.md` together,
  and the page has no version literal left (#70 covers the skill).
- **Considered, not reported:**
  - **Unlinked parts:** the tables' intro says "A part's name links to where this page covers it", but parts the page
    covers above the table (Ktor 1-2, Spring Boot 1-2) have no link, by `DemoTourTablesTest`'s rule that a row links
    only below the table. The wording is loose, but nothing is wrong.
  - **Links to the live site:** until 4.7.0 deploys the site, the demos' `make up` and `make tour` link to pages the
    live site lacks (the Ktor, nginx, and plain Kotlin pages are new in 4.7.0) and to an anchor no live page has. The
    release deploys them together, as the earlier pass found for the 4.7.0 config URLs. After a later release, a
    checkout of an older tag links to the newer tour's table, which "clone at the latest release" avoids.
  - **Requirements:** the Demos page's requirements say every demo needs "Docker with Compose v2". The Kubernetes Demo
    needs no Compose, and its own page says plain Docker.
  - **Spring Boot wording:** "the container's, which only `127.0.0.1` reaches" (`spring-boot-demo.md`, #543) means the
    host's published port. The compose network's other containers reach it too, which is harmless in the demo.
  - **Try It:** dropping it leaves the site without a three-command start on one machine outside the demos. The Basics
    Demo is that start, and Next Steps leads to it and to the Quick Start. This is a design choice.
  - **Phones:** the restored diagram, three apps wide, scales down on a phone as the demo pages' diagrams do, and opens
    full size in its dialog.
