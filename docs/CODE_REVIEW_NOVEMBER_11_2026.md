# Prometheus-Proxy Code Review — November 11, 2026

**Status:** 42 issues — 40 fixed, 2 open (12 medium, 30 low, no high).

**Progress:** Wave A (steps 1, 2, 3, 7, 11, and 13, in #374–#379), Wave B (steps 4, 8, 9, and 12, in #381–#384, with #24
a settings change), Wave C (step 5, in #386, with #40 found and fixed there), Wave D (steps 6 and 10, in #387 and #388,
with #31 fixed in step 6), and Wave E (steps 14 and 15, in #389 and #390, with #11 moved to step 15) fixed the 40 issues
marked ✅. Wave E found #41 and #42, which Wave F covers.

**Date:** 2026-11-11

**Scope:** `master` at `e84d27c`, the 4.4.2 release. That covers ~13.6k lines of main source, the ~39k-line test
suite, the build, CI workflows, the Docker images and Helm charts, and the documentation. Source paths below are
relative to `src/main/kotlin/io/prometheus/` and test paths to `src/test/kotlin/io/prometheus/`; all other paths are
from the repo root.

---

## 📋 Summary

✅ = fixed · ⬜ = open

| #  | Finding                                                                                              | Area     | Severity | Status |
|----|------------------------------------------------------------------------------------------------------|----------|----------|--------|
| 1  | `proxy.http.idleTimeoutSecs = 0` makes the scrape port close every connection unanswered             | Proxy    | low      | ✅      |
| 2  | `proxy_chunked_transfers_abandoned_total` misses the transfers `removeAgentContext` reclaims          | Proxy    | low      | ✅      |
| 3  | A late agent result logs a WARN per routine timeout, and one per chunk when chunked                  | Proxy    | low      | ✅      |
| 4  | A pending connection evicted by the cleanup service still logs INFO when it closes                   | Proxy    | low      | ✅      |
| 5  | `SdTarget.parse` accepts a `host:port` with a query or fragment                                      | Proxy    | low      | ✅      |
| 6  | Proxy KDoc and comments that no longer match the code                                                | Proxy    | low      | ✅      |
| 7  | Turning on Zipkin crashes the agent, and the proxy, while they're constructed                         | Agent    | medium   | ✅      |
| 8  | A bad config value bypasses `ConfigLoadException`, and leaks a credentialed config URL               | Agent    | medium   | ✅      |
| 9  | The connect-failure WARN-once dedupe is defeated by gRPC's changing `DEADLINE_EXCEEDED` text         | Agent    | low      | ✅      |
| 10 | `PROXY_HOSTNAME` can't override a malformed `agent.proxy.endpoints` in the config                     | Agent    | low      | ✅      |
| 11 | `PathDiscoveryService` KDoc contradicts the code                                                     | Agent    | low      | ✅      |
| 12 | `targetUrl` and `pathSource` have no size limit, so one agent can exhaust the proxy's heap           | Security | medium   | ✅      |
| 13 | A chunked transfer outlives its scrape, so an agent can hold ~9 MiB per scrape                       | Security | medium   | ✅      |
| 14 | Filter disabled: `readRequestsFromProxy` checks one lookup and acts on another                       | Security | medium   | ✅      |
| 15 | Each service-discovery request logs every invalid labels value whole at WARN                         | Security | medium   | ✅      |
| 16 | Agent-supplied strings reach the proxy's logs with CR/LF intact                                      | Security | low      | ✅      |
| 17 | Filter disabled: each connect call creates and announces a new agent context, with no limit          | Security | low      | ✅      |
| 18 | The dashboard's page routes build a fresh snapshot on every request                                  | Security | low      | ✅      |
| 19 | The published library jar ships `logback.xml`, which replaces the host's logging config              | CI/build | medium   | ✅      |
| 20 | Java embedders can't compile against the published artifact                                         | CI/build | medium   | ✅      |
| 21 | Maven embedders still get Jackson 2.12.7, and tcnative 2.0.84 natives under 2.0.85 classes           | CI/build | medium   | ✅      |
| 22 | `docs.yml` probes the `-SNAPSHOT` version, so docs changes don't deploy between releases             | CI/build | low      | ✅      |
| 23 | The published Gradle module has an empty `shadowRuntimeElements` variant                             | CI/build | low      | ✅      |
| 24 | `docs-site` and `zizmor` still aren't required checks                                                | CI/build | low      | ✅      |
| 25 | CI's docs build uses whichever uv is newest                                                          | CI/build | low      | ✅      |
| 26 | Comments and help text left stale by the distroless move                                             | CI/build | low      | ✅      |
| 27 | The Docker quick starts can't connect, and the production commands combine `--rm` with `--restart`   | Docs     | medium   | ✅      |
| 28 | The shipped examples use non-string label values, which SD leaves out                                | Docs     | low      | ✅      |
| 29 | `metrics-and-grafana.md` gives the pre-4.4.0 agent buckets and names a removed class                 | Docs     | low      | ✅      |
| 30 | The website tells embedders to call `EmbeddedAgentInfo.close()`, which doesn't exist                 | Docs     | low      | ✅      |
| 31 | The nginx-mode warnings say a disconnected agent lingers until the inactivity timeout                | Docs     | low      | ✅      |
| 32 | Public KDoc lists the rejected `all` log level, and the old `sdTargetPrefix` form                    | Docs     | low      | ✅      |
| 33 | The ownership tests forge only the attacker's own agentId, so can't catch a trust-the-claim regression | Tests  | medium   | ✅      |
| 34 | `AgentPathAuthTest` still falls back to fetching its config from GitHub `master`                     | Tests    | low      | ✅      |
| 35 | The registry-clash test accepts any startup failure                                                  | Tests    | low      | ✅      |
| 36 | `DashboardMetricNamesTest` never closes the metrics it builds                                        | Tests    | low      | ✅      |
| 37 | The container suite's third-party images are pinned by tag only                                      | Tests    | low      | ✅      |
| 38 | `harness.conf` sets Zipkin keys that don't exist                                                     | Tests    | low      | ✅      |
| 39 | Path discovery dedupes its failures on the message, which a gRPC deadline changes every time         | Agent    | low      | ✅      |
| 40 | An unknown scrape path is logged whole, with any control characters it carries                       | Security | low      | ✅      |
| 41 | The harness's chunk-size override sets a key that doesn't exist, so no harness spec chunks           | Tests    | medium   | ⬜      |
| 42 | A transport failure in the agent's path retries logs a WARN with a stack trace every tick            | Agent    | low      | ⬜      |

---

## 🧭 Headline

There are no high-severity findings, and the October fixes (#338–#357) hold up: none of them regressed. The medium
findings cluster in five places:

- **What embedders get from Maven Central.** The library jar ships a `logback.xml` that takes over the host's logging
  (#19). The documented Java example doesn't compile against the published artifact (#20). Maven builds still get the
  Jackson and tcnative versions the build pins away (#21). A bad config value escapes `ConfigLoadException`, with a
  credentialed config URL in its message (#8). Nothing in CI builds a consumer project, so none of this was visible;
  each was reproduced against the published 4.4.2 artifacts.
- **One agent against the rest.** Two agent-supplied values have no size limit (#12), a chunked transfer can outlive
  its scrape indefinitely (#13), and service discovery re-logs each bad labels value on every poll (#15). In
  `transportFilterDisabled` mode, `readRequestsFromProxy` repeats October #21's check-then-act pattern (#14).
- **A documented feature that can't start.** Turning on Zipkin crashes both processes in their constructors (#7).
- **The first thing a new user runs.** The Docker quick start's agent dials its own container and never connects
  (#27).
- **A guard that can't fail.** The ownership tests behind September #1 and October #21 would still pass if the check
  trusted the agent's claimed ID (#33).

---

## 🗺️ Plan: order of fixing

Each step is sized to be one PR, and the issues in a step are meant to be fixed together. The order is:

1. make the security tests able to fail before the code they guard changes;
2. fix what every embedder and every new user hits;
3. close the paths by which one agent can affect the others;
4. fix the remaining agent and option bugs;
5. clean up, with the documentation and KDoc last, so it describes the code the earlier steps settle.

The **Touches** column shows which steps edit the same files, and so need to land one after another.

✅ = done (with the PR that did it) · ⬜ = open

| Step | Status | Issues                       | PR theme                                                              | Touches                                                                                                                       | Why here                                                                                                                                                                                         |
|------|--------|------------------------------|-----------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1    | ✅ #374 | #33, #34                     | Make the ownership and path-auth tests able to fail                  | `proxy/ProxyServiceImplTest.kt`, `proxy/ScrapeResultOwnershipLincheckTest.kt`, `harness/AgentPathAuthTest.kt`                 | Steps 4–6 rework the code these tests guard, so the tests should catch a regression before that work starts. Test-only.                                                                          |
| 2    | ✅ #375 | #19, #20, #21, #23           | Fix what embedders get from Maven Central                             | `build.gradle.kts`, `gradle/libs.versions.toml`, `src/main/resources` → `src/shadow/resources`, `Agent.kt` (`@JvmOverloads`), README, `embedded-agent.md`, a new Maven-consumer job in `ci.yml` | Every embedder of 4.4.2 is affected, and one consumer project tests all four. Changes no behavior for the standalone images, so it can ship on its own as a patch release.                       |
| 3    | ✅ #376 | #27, #28                     | Docker quick starts that connect, and examples with string labels     | README, `llms.txt`, `src/test/kotlin/website/DockerExamples.txt`, `examples/*.conf`, a new `misc/` spec                       | The first thing a new user runs. The examples are served from `master`, so the fix takes effect on merge. Docs and examples only.                                                                |
| 4    | ✅ #381 | #13, #3, #2                  | Chunked transfers end with their scrape                               | `proxy/ProxyServiceImpl.kt` (chunked stream), `proxy/ProxyHttpRoutes.kt` (`dispatchScrapeRequest`), `proxy/ScrapeRequestManager.kt`, `Proxy.kt` (`removeAgentContext`), metrics docs | #13 is the cheapest way for one agent to exhaust the heap. #3 has to land with it, or every late chunk logs a WARN, and #2 counts the reclaims it adds. Check whether `ProxyRegistry.tla` needs the new removal. |
| 5    | ✅ #386 | #12, #15, #16, #40           | Bound, parse once, and escape what agents send                        | `proxy/ProxyServiceImpl.kt` (`registerPath`), `proxy/ProxyPathManager.kt`, `Proxy.kt` (`buildServiceDiscoveryJson`), `proxy/ProxyHttpRoutes.kt`, `proxy/ProxyOptions.kt`, `config/config.conf`, both logback files, security docs | The rest of the tenant-against-tenant memory and log issues. A new `proxy.internal` limit and a narrower path alphabet need config, validation, docs, and "Before you upgrade" notes. After step 4, which edits the same files. |
| 6    | ✅ #387 | #14, #17, #31                | Make `transportFilterDisabled` mode bind like the default             | `proxy/ProxyServiceImpl.kt` (`readRequestsFromProxy`, `connectAgentWithTransportFilterDisabled`), `proxy/AgentContextManager.kt`, `specs/tla/ProxyRegistry.tla` | Changes how a context is removed, so update the TLA+ action and run `make tla-checks` in the same PR. After step 5, which edits the same file.                                                   |
| 7    | ✅ #377 | #7                           | Zipkin that starts                                                    | `Agent.kt`, `agent/AgentGrpcService.kt`, `Proxy.kt` (construction order), `proxy/ProxyGrpcService.kt`; possibly common-utils | A documented feature that can't be turned on. If the fix goes into common-utils instead, this step waits for that release.                                                                      |
| 8    | ✅ #382 | #8, #10                      | Config errors embedders can catch, and env overrides that win         | `common/BaseOptions.kt`, `agent/AgentOptions.kt`, `Agent.kt` KDoc, `embedded-agent.md`, `security/index.md`                   | Both are option resolution, and one embedded-API spec covers #8's inputs. Embedders that catch `IllegalArgumentException` today need a release note. After step 7, which shares `Agent.kt`.      |
| 9    | ✅ #383 | #4, #9                       | Quiet the remaining routine log noise                                 | `proxy/ProxyServerTransportFilter.kt`, `agent/AgentGrpcService.kt`                                                            | A log level and a dedupe key, each covered by a `ListAppender` test. After step 7, which shares `AgentGrpcService.kt`.                                                                           |
| 10   | ✅ #388 | #1, #5                       | Refuse proxy settings that can't work                                 | `proxy/ProxyOptions.kt`, `proxy/SdTarget.kt`                                                                                  | Two startup checks. A config that started before may now fail, so each needs a "Before you upgrade" line. After step 5, which shares `ProxyOptions.kt`.                                           |
| 11   | ✅ #378 | #18                          | Dashboard pages from the cached snapshot                              | `proxy/dashboard/ProxyDashboardService.kt`                                                                                    | Self-contained. Read `.claude/rules/dashboard-constraints.md` first.                                                                                                                              |
| 12   | ✅ #384 | #22, #24, #25, #26, #37      | CI and release hygiene                                                | `docs.yml`, `ci.yml`, `Makefile`, `security.yml`, `bin/docker-*.sh`, `containers/support/ContainerTestSupport.kt`, `misc/CiHygieneTest.kt`, `docs/RELEASE.md`, AGENTS.md, branch protection | Workflow and test-infrastructure only. #24 is a repository setting rather than a code change. After step 2, which adds a job to `ci.yml`.                                                         |
| 13   | ✅ #379 | #35, #36                     | Tests that check what they claim                                      | `harness/EmbeddedAgentMetricsTest.kt`, `misc/DashboardMetricNamesTest.kt`                                                     | Test-only, so it can go any time.                                                                                                                                                                |
| 14   | ✅ #389 | #29, #30, #32, #6, #38       | Documentation, KDoc, and config drift                             | `config/test-configs/harness.conf`, `docs/metrics-and-grafana.md`, website pages, README, `common/EnvVars.kt`, `common/BaseOptions.kt`, `proxy/ProxyOptions.kt`, the proxy KDoc in #6, `agent/discovery/PathDiscoveryService.kt` | No behavior change. Last, so the KDoc describes what steps 4–10 settle. Any item can instead ride along with an earlier PR that edits the same file.                                             |
| 15   | ✅ #390 | #39, #11                     | Dedupe path-discovery failures on their cause                         | `agent/discovery/PathDiscoveryService.kt`, `agent/AgentPathManager.kt`                                                       | Found while fixing #9; the same change as step 9's `connectFailureKey`. Independent of the other remaining steps. |
| 16   | ⬜      | #41                          | Make the harness chunk as its specs ask                               | `harness/support/TestUtils.kt`, `harness/HarnessConstants.kt`, the seven harness specs that pass `chunkContentSizeBytes`, `misc/ConfigFileKeysTest.kt` | Found in step 14. Test-only, but the first end-to-end chunking in the harness may surface failures it never saw, so it's its own PR. Independent of step 17. |
| 17   | ⬜      | #42                          | Dedupe transport failures in the agent's path retries                 | `agent/AgentPathManager.kt`                                                                                   | Found in step 15; the same `Utils.failureKey` dedupe. After step 15, which edits the same file. |

### What can run in parallel

The steps fall into four tracks that touch disjoint files:

- **Proxy:** 1 → 4 → 5 → 6, with 10 after 5, and 11 at any time.
- **Agent and shared code:** 7, then 8 and 9 in parallel.
- **Packaging and release:** 2 → 12.
- **Docs and tests:** 3 and 13 at any time; 14 last.

Taken together, that gives these waves. Every step in a wave can be worked on at the same time:

| Wave | Status | Steps                       | Waits for                                    |
|------|--------|-----------------------------|----------------------------------------------|
| A    | ✅      | 1, 2, 3, 7, 11, 13          | nothing                                      |
| B    | ✅      | 4, 8, 9, 12                 | 4 on 1; 8 and 9 on 7; 12 on 2                |
| C    | ✅      | 5                           | 4                                            |
| D    | ✅      | 6, 10                       | 5                                            |
| E    | ✅      | 14, 15                      | the code steps (4–10)                        |
| F    | ⬜      | 16, 17                      | 17 on 15                                     |

Four files are edited across tracks, in separate places, so they merge cleanly, though the later PR rebases:

- `Proxy.kt`: step 4 edits `removeAgentContext`, step 5 `buildServiceDiscoveryJson`, and step 7 the construction
  order.
- `README.md`: step 2 edits the embedded Java snippet, step 3 the Docker sections, and step 14 the nginx warning.
- `ci.yml`: step 2 adds the consumer job, and step 12 pins uv.
- `proxy/ProxyServerTransportFilter.kt`: step 5 escapes its log line, and step 9 lowers another one's level. Land 9
  after 5, or rebase it.

**Where the PRs go:** every step's PR targets `master`, since `ci.yml` and the other pull-request workflows run only
for `master`. Each PR adds its entries to the Unreleased sections of both `CHANGELOG.md` and `RELEASE_NOTES.md`.

**Releases:** steps 2 and 3 change nothing for a standalone deployment, so they could ship together as a 4.4.3 patch
for embedders and new users. Steps 5, 8, and 10 change what's accepted (a new `proxy.internal` limit, a narrower path
alphabet, stricter startup checks, and a different exception type for embedders), which makes the rest a 4.5.0, with
"Before you upgrade" entries for each.

---

## 🔬 How this review was done

**Process:** five parallel reviews:

- proxy-side production code;
- agent-side and shared code;
- security;
- build, CI, and packaging;
- tests plus documentation accuracy.

Each one read the June, July, September, late-September, and October 2026 reviews (all in `docs/archive/`) and the
34 commits since the last one (`778f2df..e84d27c`). Most of those commits are the October fixes, which hadn't been
reviewed before. A fixed item comes back only when its fix turned out
to be incomplete. Overlapping findings were merged: #13 and #12 were found by both the proxy and the security passes,
and #21 by both the security and the build passes.

The key claim of every medium finding was re-checked against the cited lines. Several were reproduced against the
published 4.4.2 artifacts: #7 and #8 by running the fat JARs, and #19, #20, and #21 with Logback, Spring Boot, javac,
and Maven consumer projects. Claims that depend on a library were checked in that library's source (Ktor 3.6.1,
grpc-java 1.85.1, common-utils 5.2.0). Each finding carries a confidence level:

- **confirmed:** traced in this repo's code, or in the source of the library it calls, or reproduced.
- **plausible:** the gap in this repo is confirmed, but the impact depends on timing, or on behavior read rather than
  exercised.

**Earlier findings followed up here:** these earlier fixes turn out to be incomplete, or left a sibling unfixed.

| Earlier finding                              | Follow-up here |
|----------------------------------------------|----------------|
| July #17 (deferred)                          | #13            |
| September #1 and October #21                 | #33            |
| September #2 and October #21                 | #14            |
| September #4                                 | #18            |
| September #31                                | #34            |
| late-September #13 and October #41           | #8             |
| late-September #14 and October #23           | #12            |
| October #1                                   | #32            |
| October #4                                   | #4, #16        |
| October #10 and #20                          | #11            |
| October #16                                  | #19            |
| October #17                                  | #9             |
| October #24                                  | #15            |
| October #25                                  | #21            |
| October #31                                  | #24            |
| October #38                                  | #37            |

---

## 📡 Proxy

### 1. ✅ `proxy.http.idleTimeoutSecs = 0`, or any negative but -1, makes the scrape port close every connection unanswered

**Severity:** low · **Confidence:** confirmed in Ktor 3.6.1's `ktor-server-cio` source (`ServerPipeline.kt`).

**Where:** `proxy/ProxyHttpService.kt:56-59,67` special-cases only -1, and nothing in `ProxyOptions` validates the
value.

**Problem:** CIO's response writer runs `withTimeoutOrNull(timeout) { receive }` as soon as a connection opens. With a
timeout of 0 or less, that returns null at once, so the writer closes the output and its actor channel. The request
loop's `actorChannel.send(response)` then throws, and the connection ends with nothing written. Every Prometheus
scrape fails with EOF while the proxy looks healthy. Operators often set 0 to mean "no timeout", and nothing documents
otherwise.

**Fix:** in `ProxyOptions`, require `idleTimeoutSecs == -1 || idleTimeoutSecs > 0`, as the other `requirePositive`
checks do.

**Tests:** `ProxyOptionsTest` rejects 0 and -2, and accepts -1 and 90.

**Resolution:** Fixed in #388. `ProxyOptions` checks `http.idleTimeoutSecs` with `requireDefaultOrPositive`, renamed
from `requireGrpcTimeout`, so 0 or a negative value other than -1 fails startup with `Invalid configuration:`. -1 gives
`ProxyHttpService`'s 45-second default, not "no timeout": CIO always applies a timeout, and the docs now say so. The new
`ProxyOptionsTest` tests, which refuse 0 and -2, failed on the old code.

### 2. ✅ `proxy_chunked_transfers_abandoned_total` misses transfers that `removeAgentContext` reclaims, which on a disconnect is usually all of them

**Severity:** low · **Confidence:** confirmed that the reclaim path never counts. That it usually wins the race is
plausible: grpc-java calls `transportTerminated` synchronously on the event loop, while the RPC's `finally` runs only
after the cancel passes through the call executor and the coroutine dispatcher.

**Where:**

- `Proxy.kt:451-457` and `proxy/AgentContextManager.kt:127-133`: the reclaim only logs.
- `proxy/ProxyServiceImpl.kt:649-662`: the counter is incremented only in `abandonUnfinished`.
- `docs/metrics-and-grafana.md:254-255` says it is "incremented when an agent disconnects mid-transfer".

**Problem:** since October #3, two paths race to reclaim an unfinished transfer: the stream's `abandonUnfinished`,
which counts, and `removeAgentContext` → `removeChunkedContextsForAgent`, which doesn't. On a disconnect the second
usually wins, and on a cleanup-service eviction it always does, so the documented metric stays near zero.

**Fix:** in `removeAgentContext`, increment `chunkedTransfersAbandoned` by `reclaimed.size`. Only one of the two
removers gets each context, so nothing is counted twice.

**Tests:** open a transfer, call `proxy.removeAgentContext(owner)`, then end the stream: the counter rises by exactly
1. Then the reverse order.

**Resolution:** Fixed in #381. `removeAgentContext` adds the transfers it reclaims to the counter, and now reclaims
before failing the agent's scrapes, so a handler that removes its own transfer as it returns can't take it uncounted.
Each transfer has one remover, so none is counted twice; `ProxyTest` checks the counter rises by exactly 1 in either
order (0 on the old code).

### 3. ✅ A late agent result logs a WARN on every routine Prometheus timeout, and a late chunked result logs one per chunk

**Severity:** low · **Confidence:** confirmed by trace.

**Where:**

- `proxy/ScrapeRequestManager.kt:77,103` and `proxy/ProxyServiceImpl.kt:566,585,615`.
- The cause is on the agent side, in `agent/AgentHttpService.kt:182-188`.

**Problem:** since October #14, the agent's fetch deadline equals Prometheus's scrape timeout, but the agent starts
counting after Prometheus did. So a scrape that hits Prometheus's timeout gets its answer just after the handler was
cancelled and removed its wrapper, and the proxy logs `Missing ScrapeRequestWrapper … (likely timed out)` at WARN.
`ProxyHttpRoutes.kt:244-247` calls this case "the most common real failure". A late chunked result logs one header
WARN, one "Missing chunked context" WARN per chunk (about 160 for 5 MB at 32 KB), and one summary WARN.

**Fix:**

- Log an unknown or untracked scrapeId at DEBUG; the `client_cancelled` and `timed_out` outcomes already record it.
  At minimum, log a missing chunk context once per scrapeId.
- Optionally, have the agent take a small margin off the forwarded timeout.

**Tests:** a `ListAppender` test in which results, and a 10-chunk transfer, for an untracked scrapeId log nothing
above DEBUG.

**Resolution:** Fixed in #381. A result, header, chunk, or summary for an untracked scrapeId, and `failScrapeRequest`
for one, log at DEBUG, and a late transfer's missing-transfer line is logged once. A `ListAppender` test that sends a
late result and a 10-chunk transfer logged 13 WARNs on the old code and now logs nothing above DEBUG.

### 4. ✅ A pending connection evicted by the cleanup service still logs INFO when it closes

**Severity:** low · **Confidence:** confirmed by trace.

**Where:**

- `proxy/ProxyServerTransportFilter.kt:51`.
- `proxy/AgentContextManager.kt:135-138`: `findStaleAgents` includes pending contexts.
- `proxy/AgentContextCleanupService.kt:59-72`.

**Problem:** a connection that never makes an authenticated ProxyService call keeps a pending context. Examples are a
client with a bad token on a long-lived channel, or an L7 load balancer that holds HTTP/2 connections for health
checks. After `maxAgentInactivitySecs`, the cleanup service evicts it and logs at DEBUG, as October #4 intended. When
the connection later closes, `removeAgentContext` returns null, and `transportTerminated` logs `Agent N already removed
before transport terminated` at INFO. That is the unauthenticated-connection noise October #4 set out to remove.

**Fix:** log the `context == null` branch at DEBUG. The eviction was already logged at the right level.

**Tests:** a `ListAppender` test: evict a pending context, then terminate its transport, and expect no INFO line.

**Follows up:** October #4.

**Resolution:** Fixed in #383. Only a cleanup-service eviction leaves `transportTerminated` without a context. The
filter now keeps the context it created in the transport's attributes and logs the line at DEBUG for a connection that
never authenticated, keeping INFO for an announced agent, whose eviction is logged at INFO too. A `ListAppender` test
that evicts a pending and an announced context and then ends their transports failed on the old code.

### 5. ✅ `SdTarget.parse` accepts a `host:port` with a query or fragment

**Severity:** low · **Confidence:** confirmed by reading `java.net.URI`'s parse.

**Where:** `proxy/SdTarget.kt:62-68`. The URL branch rejects both at `:55-57`.

**Problem:** `parseAddress` checks `host`, `rawPath`, and `rawUserInfo`, but not `rawQuery` or `rawFragment`. So
`localhost:8080?x` and `localhost:8080#x` pass startup and are emitted verbatim as the target. That contradicts the
KDoc's `@throws … for anything else`, and the scrape URL Prometheus builds from it is broken.

**Fix:** add `uri.rawQuery == null && uri.rawFragment == null` to `parseAddress`'s `require`.

**Tests:** `SdTarget.parse("localhost:8080?x")` and `SdTarget.parse("localhost:8080#f")` throw.

**Resolution:** Fixed in #388. `parseAddress` refuses a query or fragment, even an empty one, with its own message,
`sdTargetPrefix '…' can't have a query or a fragment`. The new `SdTargetTest` test and a `ProxyOptionsTest` startup test
failed on the old code.

### 6. ✅ Proxy KDoc and comments that no longer match the code

**Severity:** low · **Confidence:** confirmed.

**Where and what:**

- `proxy/ProxyEventBus.kt:33`: `AgentConnected` says it fires on "its first call, `connectAgent`". Since October #26,
  `ProxyServerInterceptor` announces on the connection's first authenticated ProxyService call of any kind.
- `proxy/ScrapeRecord.kt:40`: `durationMillis` is described as how long the agent took to fetch the target. It is the
  wrapper's age on the proxy side, which includes queueing and transport, or the time until the client hung up for
  `client_cancelled`.
- `proxy/ScrapeRequestWrapper.kt:101`: the comment names `markComplete()`, which doesn't exist; it is `complete()`.

**Fix:** reword each to match the code.

**Tests:** none needed.

**Resolution:** Fixed in #389. `ProxyEvent.AgentConnected`'s KDoc says it fires on a connection's first authenticated
call of any kind, or, with the transport filter disabled, on `connectAgentWithTransportFilterDisabled`.
`ScrapeRecord.durationMillis` is described as measured on the proxy's side, so it includes queueing and transport, or
runs until the client hung up. The `ScrapeRequestWrapper` comment names `complete()`, and the comment on
`Proxy.launchId` says per Proxy instance, not per process.

---

## 🛰️ Agent and shared code

### 7. ✅ Turning on Zipkin crashes the agent, and the proxy, while they're constructed

**Severity:** medium (a documented feature can't be used at all; the failure is loud, not silent) · **Confidence:**
confirmed by running the shipped jars. With `agent.internal.zipkin.enabled = true`, or
`-Dproxy.internal.zipkin.enabled=true` for the proxy, both die with `UninitializedPropertyAccessException: lateinit
property zipkinReporterService has not been initialized`.

**Where:**

- `Agent.kt:206` builds `grpcService` before `initServletService` at `:237`.
- `agent/AgentGrpcService.kt:118-119,213,285-286`: the constructor calls `resetGrpcStubs()`, which reads the lazy
  `grpcTracing`, which reads `agent.zipkinReporterService`.
- common-utils 5.2.0's `AbstractGenericService.initMetricsAndHealthChecks()` assigns `zipkinReporterService`, and it
  runs only from `initServletService`.
- The proxy has the same cause: `proxy/ProxyGrpcService.kt:59-60,78,110-111`, built at `Proxy.kt:204`, before
  `initServletService` at `:275`.
- `website/prometheus-proxy/docs/advanced.md:146-165` documents tracing as working for both.

**Problem:** setting either Zipkin key makes the constructor throw, so a standalone process exits at startup and
`startAsyncAgent` throws. No test builds a real Agent or Proxy with Zipkin on: `AgentGrpcServiceTest:608` mocks
`zipkinReporterService`, and the Zipkin flag in `junit-test.conf` is read only by `OptionsTest`.

**Fix:** one of these.

- Have the tracing interceptor look up its `Tracing` only when a call is made, after init, through a small delegating
  `ClientInterceptor` and `ServerInterceptor`.
- Have common-utils build `ZipkinReporterService` in the `AbstractGenericService` constructor, since it needs only
  `zipkinConfig`. That fixes both processes at once, but it needs a common-utils release.
- For the agent only, build `grpcService` after `initServletService`, splitting the `init` so that `initBlock` and
  `startSync` still run last.

**Tests:** an `AgentTest` and a `ProxyTest` case that start in-process with `internal.zipkin.enabled = true` (an
unreachable Zipkin endpoint is fine, since the reporter is asynchronous), check that `startSync()` succeeds and a
scrape round-trips, then stop cleanly.

**Resolution:** Fixed in #377. `DeferredClientInterceptor` and `DeferredServerInterceptor` build the tracing interceptor
on the first call, after `initServletService` has assigned the reporter, and every channel a reconnect builds shares one
`Tracing`; construction order is unchanged. Spans now carry the configured `serviceName`, which the code had overridden
with `grpc_client` and `grpc_server`. The new `InProcessZipkinTest` starts a real proxy and agent with tracing on, and
it failed on the old code with this exception.

### 8. ✅ A bad config value bypasses `ConfigLoadException` and its redaction, so a credentialed config URL leaks into the exception

**Severity:** medium · **Confidence:** confirmed by calling `Agent.startAsyncAgent(cfg, false, false)` on the fat
jar:

- `scrapeTimeoutSecs = 0` throws `IllegalArgumentException`.
- `scrapeTimeoutSecs = "abc"` throws `ConfigException$WrongType`.
- A missing trust store throws `IllegalArgumentException`.
- `name = ${missing.value}` throws `ConfigException$UnresolvedSubstitution`.

None is a `ConfigLoadException`. With the config served from `http://user:s3cret@127.0.0.1:…/type.conf`, both
Typesafe exception messages began with that URL, unredacted.

**Where:**

- `common/BaseOptions.kt:345-347`: `ConfigVals(config)` and `assignConfigVals()` run outside the `fail()` wrapper at
  `:537`.
- `BaseOptions.kt:483` and `:491`: the `resolve()` calls, including the one for dynamic parameters, run outside it
  too.
- The Agent constructor's own `IllegalArgumentException`s: `agent/AgentHttpService.kt:116`,
  `agent/AgentPathManager.kt:120,156`, and `agent/filter/MetricFilter.kt:237`.
- Docs that promise otherwise:
  - `Agent.kt:793-796`: `startSyncAgent` says "an option is invalid … throws ConfigLoadException";
  - `Agent.kt:824-826`: `startAsyncAgent` says "a config that can't be loaded, an invalid option … throws
    ConfigLoadException";
  - `website/prometheus-proxy/docs/embedded-agent.md:61,66`;
  - `website/prometheus-proxy/docs/security/index.md:186-189`, which says a config URL that fails to load is
    redacted.

**Problem:**

- An embedder that follows the docs and catches only `ConfigLoadException` gets an uncaught exception for any
  out-of-range or wrong-typed value, an unresolved `${…}`, a duplicate `pathConfigs` entry, a bad filter regex, or a
  bad trust store.
- In both embedded and standalone mode (standalone prints the uncaught exception from `main` to stderr), a type or
  substitution error in a config fetched from `http(s)://user:pass@…` prints the credentials. The late-September #13
  redaction covers only the parse and fetch branches.
- The proxy shares `BaseOptions`, so it leaks the same way.

**Fix:**

- In `parseOptions`, run `resolve()`, `ConfigVals(config)`, `assignConfigVals()`, and the dynamic-parameter merge
  under the same handler as `readConfig`. Catch `ConfigException` and `IllegalArgumentException`. When embedded,
  throw `ConfigLoadException(sanitizeUrlsInText(msg), redacted(e))`; when standalone, log the redacted message and
  exit 1.
- For the Agent constructor's own checks, either wrap them in `startAsyncAgent`, or narrow the KDoc and website
  wording to "a config that can't be loaded or parsed" and list the other exception types.

**Tests:** an `EmbeddedAgentApiTest` case per input above, loaded from `http://user:secret@…`. Each expects
`ConfigLoadException`, and neither the message nor anything in the cause chain contains `secret`.

**Follows up:** late-September #13 (config URL redaction) and October #41, whose doc rewrite now promises
`ConfigLoadException` for an option error.

**Resolution:** Fixed in #382. `BaseOptions.failOnInvalidConfig` runs the config's `resolve()`, the `-D` merge,
`ConfigVals`, and `assignConfigVals()` under one handler: embedded, it throws a `ConfigLoadException` with credentials
redacted from every message in its cause chain; standalone, `Proxy.main` and `Agent.startSyncAgent` log one redacted
line and exit 1. Both agent entry points build the Agent under it too, so the constructor's trust-store, TLS-file,
duplicate-entry, and filter-regex checks are reported the same way; building an `Agent` directly still throws
`IllegalArgumentException`. An `EmbeddedAgentApiTest` case for each input, served from `http://user:secret@…`, and
forked-JVM `BaseOptionsTest` cases for the standalone agent and proxy failed on the old code. Embedders catch
`ConfigLoadException` now, which the release notes' Before you upgrade says.

### 9. ✅ The connect-failure WARN-once dedupe is defeated by gRPC's changing `DEADLINE_EXCEEDED` text

**Severity:** low · **Confidence:** plausible. The message format is confirmed in grpc-core 1.85.1's
`ClientCallImpl.formatDeadlineExceededStatus()`; it wasn't exercised against a blackholed proxy.

**Where:** `agent/AgentGrpcService.kt:343-349`. The dedupe key is `"${e.simpleClassName}: ${e.message}"`, compared
with `connectFailures.put(proxyHost, failure) == failure`.

**Problem:** with a proxy that accepts TCP but never answers HTTP/2, such as a hung process, an L4 load balancer with
no backend, or a firewall that drops packets, every `connectAgent` fails with a description like `CallOptions
deadline exceeded after 29.999954000s … [buffered_nanos=…]`. It's different on every attempt, so every attempt counts
as a change and logs a WARN with a stack trace, about twice a minute, forever. October #17's resolution ("Five failed
connects now log one WARN") holds only for stable messages such as `UNAVAILABLE: io exception`.

**Fix:** key the dedupe on `Status.fromThrowable(e).code`, plus the exception class for non-gRPC failures, rather
than the full message. Keep the full message in the log line.

**Tests:** in `AgentGrpcServiceTest`, two `StatusException(DEADLINE_EXCEEDED)` failures in a row, whose descriptions
differ only in the elapsed time, log one WARN and one DEBUG.

**Follows up:** October #17.

**Resolution:** Fixed in #383. `connectAgent` dedupes on `Status.fromThrowable(e).code`, or on the exception class when
there's no gRPC status, and still logs the full message. Against a loopback port that accepts TCP but never answers,
grpc-java 1.85.1's descriptions differed on every attempt (elapsed time, name-resolution delay,
`connecting_and_lb_delay`), which confirmed the finding; that case and two mocked ones logged a WARN per attempt on the
old code and now log one WARN, then DEBUG. The same pattern elsewhere is #39.

### 10. ✅ `PROXY_HOSTNAME` can't override a malformed `agent.proxy.endpoints` or `hostname` in the config

**Severity:** low · **Confidence:** confirmed. With `agent { proxy.endpoints = ["proxy-a:5005x"] }` and
`PROXY_HOSTNAME=127.0.0.1:1`, startup throws `IllegalArgumentException: Invalid port in 'proxy-a:5005x'`.

**Where:** `agent/AgentOptions.kt:358-362`. `PROXY_HOSTNAME.getEnv(entries.joinToString(",") { parseHostPort(...) })`
parses the config value eagerly, before it checks whether the env var is set. The comment at `:356-357` promises
laziness only for `--proxy`.

**Problem:** this breaks the CLI → env → config precedence. An operator who overrides a broken or blank config entry
with the env var still fails at startup on the config value. The Helm chart always sets `PROXY_HOSTNAME`. A blank
`agent.proxy.hostname = ""` with no endpoints fails the same way ("Host/port string must not be blank").

**Fix:** `proxyHostname.ifEmpty { System.getenv(PROXY_HOSTNAME.name) ?: <build from config> }`, so the config
entries are parsed only when they're used.

**Tests:** an `AgentOptionsTest` case. Env vars can't be set in-process, so route the read through an injectable
helper, as `EnvVars`' parse helpers are, or use `--proxy`. Then check that a malformed config endpoint doesn't fail
when an override is present.

**Resolution:** Fixed in #382. `AgentOptions.proxyHostnameSpec` takes `PROXY_HOSTNAME`'s value as an argument and parses
the config's `endpoints` or `hostname` only when neither `--proxy` nor the env var is set. `AgentOptionsTest` shows a
malformed endpoint and a blank hostname are ignored when an override is set, which failed on the old code, and still
rejected without one.

### 11. ✅ `PathDiscoveryService` KDoc contradicts the code

**Severity:** low · **Confidence:** confirmed.

**Where:** `agent/discovery/PathDiscoveryService.kt:32-33,59-62`.

**Problem:**

- The class KDoc says a failed read "skips the tick, leaving the live (last-known-good) set untouched; only a
  successful read … reconciles". Since #346, a failed read reconciles `lastGoodPaths`, which re-registers the paths
  after a reconnect.
- `run()`'s KDoc says "On a graceful disconnect nothing cancels this coroutine". Since #263,
  `Agent.launchConnectionTask` cancels every sibling task when any one ends (`Agent.kt:456-460`). It also contradicts
  the class KDoc's own "is cancelled on disconnect".

**Fix:** reword both to describe the last-good reconcile and the sibling cancellation. The `keepRunning` polling is
now only a backstop.

**Tests:** none needed.

**Follows up:** October #10 and #20.

**Resolution:** Fixed in #390, with #39, which edits the same file. The class KDoc says a failed read reconciles the
last good read (#346), and `run()`'s says a sibling connection task ending cancels it (#263), with `keepRunning` only a
backstop. `Agent.kt`'s comment on the `reconcilePaths` task says the same, and `docs/TESTING.md`'s
`PathDiscoveryServiceTest` entry no longer says a failed read skips the reconcile.

### 39. ✅ Path discovery dedupes its failures on the message, which a gRPC deadline changes every time

**Severity:** low · **Confidence:** plausible. The pattern is confirmed; that a reconcile fails with a changing
`DEADLINE_EXCEEDED` in practice wasn't exercised. Found while fixing #9.

**Where:** `agent/discovery/PathDiscoveryService.kt:92` (`reconcileOnce`) and `agent/AgentPathManager.kt:415`
(`discoveredRejection`).

**Problem:** both key a failure on `"${e::class.simpleName}: ${e.message}"`, the shape #9 fixed in `connectAgent`.
`reconcileOnce` logs a WARN with a stack trace whenever that key changes, so a reconcile that keeps failing with
`DEADLINE_EXCEEDED`, whose description carries the elapsed time, logs one on every poll. `discoveredRejection` stores
the same string as the rejection's reason.

**Fix:** key both on the gRPC status code, or the exception class without one, as `connectFailureKey` does, and keep
the full message in the log line.

**Tests:** two reconciles failing with `DEADLINE_EXCEEDED` descriptions that differ only in the elapsed time log one
WARN and one DEBUG.

**Follows up:** #9.

**Resolution:** Fixed in #390. `connectAgent`'s key moved to `Utils.failureKey`: the gRPC status code, or, without one,
the class, and with `withMessage` the message too. `reconcileOnce` and `discoveredRejection` pass `withMessage`, since a
discovery file's parse error and the proxy's rejection reason carry stable messages that say what changed; only gRPC
descriptions carry timings. `discoveredRejection` stores only the key, which only the repeat comparison reads; the log
lines take the full message from the exception. No gRPC failure reaches `reconcileOnce` today, since
`FileDiscoverySource` makes no RPCs and `reconcileDiscoveredPaths` catches each path's failure; the reachable case was
`registerPathOnProxy`'s 30s deadline, which logged a WARN with a stack trace on every reconcile. The new
`DEADLINE_EXCEEDED` tests in `PathDiscoveryServiceTest` and `AgentPathManagerTest` failed on the old code.

### 42. ⬜ A transport failure while retrying a rejected static path, or unregistering a stale discovered path, logs a WARN with a stack trace every tick

**Severity:** low · **Confidence:** confirmed by reading the code. Found while fixing #39.

**Where:** `agent/AgentPathManager.kt`:

- `:230-242`, `retryRejectedStaticPaths`: `onFailure { e -> logger.warn(e) { "Failed to retry static path /$path" } }`.
  `registerStaticPath` (`:208`) catches only `RequestFailureException`, so a transport failure reaches it.
- `:354-363`, `unregisterStaleDiscoveredPaths`: `.onFailure { logger.warn(it) { "Failed to unregister discovered path
  /$path" } }`.

**Problem:**

- A transport failure, such as `DEADLINE_EXCEEDED` or `UNAVAILABLE`, logs a WARN with its stack trace for each path on
  each retry tick, and for each stale path on each reconcile, with no dedupe.
- It doesn't advance the static path's backoff, which only a rejection does, so the path is retried every
  `rejectedPathRetrySecs` while the failure lasts.
- Both last only until the connection's other tasks notice it's gone and end it, so it's a burst, not a flood. #39 fixed
  the same shape for discovered-path registration.

**Fix:** dedupe both on `Utils.failureKey`, as #39 does: WARN when a failure first appears, DEBUG while it repeats. On a
transport failure, advance the static path's backoff, or leave the path to the reconnect, which registers static paths
again.

**Tests:** two static-path retries that fail with `DEADLINE_EXCEEDED` log one WARN and one DEBUG; so do two reconciles
whose unregister of the same stale path fails.

**Follows up:** #39.

---

## 🔒 Security

### 12. ✅ A registered path's `targetUrl` and `pathSource` have no size limit, so one agent can run the proxy out of memory in about 100 calls

**Severity:** medium (the security pass rated it high; it is kept at medium to match October #22 and #23, which have
the same attacker) · **Confidence:** confirmed by trace. The proxy sets no `maxInboundMessageSize`, and common-utils'
`GrpcDsl.server` builds a plain `NettyServerBuilder`, so gRPC's 4 MiB default is the only bound.

**Where:**

- `src/main/proto/proxy_service.proto:43,46` and `proxy/ProxyServiceImpl.kt:273-288`: `request.targetUrl` and
  `request.pathSource` go into `PathMetadata` unchecked.
- `proxy/ProxyPathManager.kt:117-137`: they are stored per path, after only `sanitizeUrl`, which doesn't truncate.
- Where they are rendered:
  - `proxy/dashboard/ProxySnapshot.kt:196-197` and `ProxyDashboardHtml.kt:203-204`, in every push frame to every
    dashboard session;
  - `ProxyPathManager.kt:507-519`, `/debug`'s `toPlainText`, built inside the `pathMap` lock.
- Three per-scrape values have the same gap:
  - `proxy/ProxyHttpRoutes.kt:287-296`: the `/debug` activity line keeps the agent's `failureReason` and `url` whole,
    in a queue of 50 entries;
  - `ProxyHttpRoutes.kt:419-425`: an unparseable agent content type is logged whole at WARN on every scrape.

**Problem:** late-September #14 capped the path and its labels, and October #23 capped the agent's name, host, launch
ID, and endpoints. These per-path fields slipped through both.

- **Attacker:** any authenticated agent whose identity has a wildcard glob (such as the chart's example `team_a_*`),
  or any peer when agent auth is off.
- **Trigger:** `registerPath` calls with distinct path names, each with a `targetUrl` just under 4 MiB.
  `maxPathsPerAgent` allows 20,000 paths.
- **Impact:** the chart's heap is about 384 MiB (75% of 512 Mi), so about 96 registrations exhaust it, and every
  tenant's scrapes fail. The attacker can repeat it after each restart. With the dashboard on, all of it is
  re-rendered on every push.

AGENTS.md's rule is to "bound or cut any new agent-supplied value before storing or logging it".

**Fix:**

- In `registerPath`, cut `pathSource` to `maxAgentFieldLength`. Cut `targetUrl` to its own limit, such as a new
  `proxy.internal.maxTargetUrlLength` of 2048 (0 for unlimited), validated in `ProxyOptions`. Use
  `boundedRegistration`'s surrogate-safe `truncate`, and warn once.
- Run `sanitizeUrl` before cutting. Cutting first can split `user:password@`, and then `USERINFO_REGEX`, which needs
  the `@`, no longer redacts the start of the password.
- Cut `failureReason`, `url`, and the raw content type the same way in `recordScrapeOutcome` and `parseContentType`.
- Consider an explicit, smaller `maxInboundMessageSize` for the unary calls.

**Tests:**

- `registerPath` with a 1 MiB `targetUrl` and `pathSource` stores both cut (check
  `getAgentContextInfo(...).targetUrl.length`).
- A `targetUrl` with credentials past the cut point is still redacted.
- `ProxyPathManager.toPlainText` stays bounded.
- A `ListAppender` test: a scrape result with a 1 MiB content type logs a bounded line.
- `ProxyOptions` validation for the new limit.

**Follows up:** October #23 (incomplete) and late-September #14.

**Resolution:** Fixed in #386. `ProxyPathManager.addPath` redacts a path's target URL and then cuts it to the new
`proxy.internal.maxTargetUrlLength` (2048; `0` for unlimited, a negative value fails startup), and cuts its source to
`maxAgentFieldLength`, never inside a surrogate pair, with one WARN naming the fields and their sizes. A scrape's
failure reason, URL, and raw content type are cut to 4,096 characters, the chunked header's limit, before
`recordScrapeOutcome` and `parseContentType` keep or log them. The chunked header keeps its fixed 4,096, since a
setting an operator can turn off mustn't lift #13's bound. No `maxInboundMessageSize` was added. The new tests (a
1 MiB URL and source read back cut, a password past the cut still redacted, a bounded `toPlainText`, bounded `/debug`
and content-type lines, and the option's validation) failed on the old code.

### 13. ✅ A chunked transfer outlives its scrape, so an agent can hold up to about 9 MiB per scrape for as long as it stays connected

**Severity:** medium · **Confidence:** confirmed by trace (found by both the proxy and the security passes). Nothing
removes a `ChunkedContext` whose scrape is no longer tracked while the stream stays open.

**Where:**

- `proxy/ProxyServiceImpl.kt:562-577`: `readHeader` opens a transfer whenever the wrapper is tracked at that moment.
  `readChunk` (`:580-604`) appends to it without checking that the scrape is still tracked.
- `proxy/ProxyHttpRoutes.kt:359-377`: `dispatchScrapeRequest`'s `finally` removes only the wrapper.
- A context is removed in only four places:
  - at the summary (`ProxyServiceImpl.kt:607-617`);
  - on a chunk validation failure (`:592-602`);
  - in the stream-end sweep, `abandonUnfinished` (`:649-662`);
  - in `Proxy.removeAgentContext` (`Proxy.kt:451-457`).
- `proxy/ChunkedContext.kt:41-46,69-72`: the context keeps the whole `HeaderData`, and the size cap is per context.

**Problem:** `maxZippedContentSizeMBytes` limits one transfer and `maxInFlightScrapeRequests` limits tracked wrappers,
but nothing limits transfers. A transfer stays in `chunkedContextMap` after its wrapper is gone, and the agent's
`writeChunkedResponsesToProxy` stream lives as long as the connection.

- **Attacker:** any authenticated agent serving one path that Prometheus scrapes. The attacker can also drive the
  scrapes itself through the unauthenticated scrape port.
- **Trigger:** for each scrape request S, the agent:
  1. sends a chunked header for S, which opens a transfer, since S is tracked and owned by it;
  2. answers S at once on the unary `writeResponsesToProxy`, so the handler completes and removes S's wrapper, while
     the transfer stays;
  3. sends chunks for S totalling just under 5 MiB, which `readChunk` accepts after checking only the owner;
  4. never sends the summary, and keeps the stream open with heartbeats.
- **Impact:** each scrape keeps 5 MiB of chunks plus a header of up to 4 MiB (`headerUrl`, `headerFailureReason`,
  `headerContentType`). Because the agent answers on the unary stream, neither `maxInFlightScrapeRequests` nor the
  backlog cap bounds it. One path scraped every 15s exhausts the chart's heap in about 10 minutes, and 50 paths do it
  in seconds. `chunking_map_check` turns unhealthy at 800 transfers, but the chart's probes use `/ping`, so nothing
  reacts.

The stock agent sends header, chunks, and summary back to back (`agent/AgentGrpcService.kt:530-576`), so an honest
agent doesn't trigger this.

**Fix:**

- In `dispatchScrapeRequest`'s `finally`, after `removeFromScrapeRequestMap`, call
  `agentContextManager.removeChunkedContext(scrapeId)`, since nobody awaits that transfer anymore.
- In `readHeader`, after a successful `putChunkedContext`, check `containsScrapeRequest(scrapeId)` again, and if the
  scrape is gone, remove the context just added. The handler removes the wrapper before the context, so one of the
  two always sees the other.
- In `readChunk` and `readSummary`, drop the transfer when its scrape is no longer tracked, and drop the scrapeId from
  `activeScrapeIds` when no context is found.
- Cap one stream's `activeScrapeIds`, for example at the backlog cap, and cut the header's string fields when the
  transfer opens.
- Land it with #3, or every late chunk logs a WARN.

**Tests:**

- Track S, open a header for S, complete S through `assignScrapeResults` and `removeFromScrapeRequestMap`, then send
  a chunk: `chunkedContextMapView` doesn't contain S.
- With the stream left open, a header and chunk, then the handler's timeout or cancel, leave
  `chunkedContextMapView` empty.
- A header processed just after the wrapper's removal (inject the removal through a seam) leaves no context.

**Follows up:** July #17 (deferred). Its premise, that a transfer is "freed at SUMMARY or the end-of-stream orphan
sweep", holds only for an honest agent. The October #21 rework of `readHeader` kept the gap.

**Resolution:** Fixed in #381. `dispatchScrapeRequest`'s `finally` removes the scrape's transfer after untracking it,
and `readHeader` re-checks that the scrape is tracked once it has stored the transfer, so whichever runs second removes
it. `readChunk` and `readSummary` drop a transfer whose scrape is gone, one stream may have at most the agent backlog
cap of transfers under way, and a header's strings are cut to 4,096 characters. New `ProxyServiceImplTest` and
`ProxyHttpRoutesTest` tests (a unary answer, an injected race, a handler timeout and cancel, the cap, the cut) failed on
the old code, and the new `ChunkedTransferLifetimeLincheckTest` fails if either side's order is broken.
`ProxyRegistry.tla` abstracts chunking away, so it is unchanged.

### 14. ✅ With the transport filter disabled, `readRequestsFromProxy` checks identity on one lookup and acts on another, so an agent can read or remove another identity's new agent

**Severity:** medium · **Confidence:** plausible. The check-then-act and the unconditional removal are confirmed in
the code. Winning the race depends on timing, but the attacker can retry as often as it likes.

**Where:**

- `proxy/ProxyServiceImpl.kt:385-412`: `readRequestsDenial` looks the context up, the `try` looks it up again, and the
  `finally` removes `agentId` unconditionally when `transportFilterDisabled` is set.
- `proxy/ProxyGrpcService.kt:108-109`: `ProxyServerInterceptor` isn't installed in this mode, so there is no
  connection agentId and `connectionMismatchReason` passes.
- `proxy/AgentContextManager.kt:147-162`: removal is by ID alone.
- `proxy/AgentContext.kt:72`: agentIds are sequential.

**Problem:** with the filter disabled, the agent names its own agentId on every call, and the identity check is the
only binding (September #2).

- **Attacker:** an authenticated agent of identity A, in a `transportFilterDisabled` deployment (behind nginx) with
  per-agent identities. The security doc presents that setup as safely bound.
- **Trigger:** A learns the counter N from its own `connectAgentWithTransportFilterDisabled`, then calls
  `readRequestsFromProxy(N+1)` repeatedly on several streams.
  - When the first lookup finds nothing, the denial is null and the call proceeds.
  - If victim B's context is added after the second lookup also found nothing, the `finally` removes it, even though
    this call just threw `NOT_FOUND`. B's paths drop and its scrapes fail; repeated, this blocks other identities'
    reconnects.
  - If B's context is added between the two lookups, A streams B's scrape requests, including the `Authorization`
    header Prometheus forwards and the query strings. A can't answer them, so B's scrapes time out, and A removes B
    when it ends the stream.

This is October #21's check-then-act pattern, on the one RPC #21 and September #2 didn't revisit.

**Fix:**

- Look the context up once, before the `try`: `val ctx = getAgentContext(agentId) ?: throw NOT_FOUND`, then check
  `identityMismatchReason(ctx)`.
- Stream `ctx`, and in the `finally` remove only that object, with a conditional `agentContextMap.remove(agentId,
  ctx)`.
- Update the matching action in `specs/tla/ProxyRegistry.tla` and run `make tla-checks`.

**Tests:** with the filter disabled and caller identity A:

- `getAgentContext("7") returnsMany listOf(null, victimB)` streams nothing from B;
- with `returnsMany listOf(null, null)`, and B added before the `finally`, B is still registered afterward.

**Follows up:** October #21 (the same class) and September #2 (identity binding with the filter disabled).

**Resolution:** Fixed in #387. `readableAgentContext` looks the context up once, before the `try`, checks the connection
and the identity on that object, and throws `PERMISSION_DENIED` or `NOT_FOUND`. The stream reads that context, and the
`finally` calls the new `Proxy.removeAgentContextIfCurrent`, which removes it only through
`agentContextMap.remove(agentId, ctx)` and then tears it down as `removeAgentContext` does. `ProxyRegistry.tla` models a
stream that names another connection's agentId, with a new `StreamIdentity` invariant, which the old two lookups
(`StreamSameLookup = FALSE`) violate. Both tests failed on the old code; in the second, the victim appears in the first
lookup's answer, since the fixed code has no second lookup to inject into.

### 15. ✅ Each service-discovery request logs every invalid labels value whole at WARN, so one GET can write about 160 MiB of logs

**Severity:** medium when service discovery is on (it is off by default) · **Confidence:** confirmed by trace.

**Where:**

- `Proxy.kt:569-593`: `onFailure { logger.warn { "Invalid JSON in labels value: $labels …" } }`, in
  `buildServiceDiscoveryJson`.
- `proxy/ProxyPathManager.kt:383-390`: registration already warned once, and its comment says SD is not meant to
  repeat it.
- `proxy/ProxyHttpRoutes.kt:109-121`: the SD route is on the unauthenticated scrape port.

**Problem:**

- **Attacker:** an authenticated agent with a narrow glob registers many paths whose labels aren't a JSON object.
  They're accepted, since only their size is checked.
- **Trigger:** any `GET` of the discovery path, from Prometheus or from anyone who can reach the scrape port.
- **Impact:** one WARN line per such path, with the whole labels value: up to 8 KiB × 20,000 paths, about 160 MiB per
  request, with CR/LF passed through (#16). Every poll also re-parses every path's labels, which is CPU a client of
  the unauthenticated port can trigger at will.

**Fix:**

- Log the SD-time failure at DEBUG, cut; registration already warned.
- Better: parse and filter the labels once in `addPath`, keep the resulting `JsonObject` in `AgentContextInfo`, and
  have `buildServiceDiscoveryJson` only copy it.

**Tests:** register a path with labels `{"a":`, attach a `ListAppender`, and call `buildServiceDiscoveryJson()` twice:
neither call logs a WARN.

**Follows up:** October #24, and the log-cutting work in #353.

**Resolution:** Fixed in #386. `addPath` parses and filters a path's labels once, by October #24's rules, and keeps
the result in `AgentContextInfo.sdLabels`; `buildServiceDiscoveryJson` only copies it. Registration's warnings are the
only ones, logged after the path is stored rather than inside the `pathMap` lock. `ProxyTest` checks that two
discovery requests log no WARN for labels `{"a":`, which failed on the old code.

### 16. ✅ Agent-supplied strings reach the proxy's logs with CR/LF intact

**Severity:** low · **Confidence:** confirmed. Both logback configs use `%msg%n` with no escaping
(`src/main/resources/logback.xml`, `logback/docker-logback.xml`).

**Where:**

- `proxy/ProxyPathManager.kt:386,389,392`: "Added path /$path", the reserved label keys, and the label problem.
- `proxy/ProxyServiceImpl.kt:131,177`: `shownAgentId` (64 characters) and "Connected to $agentContext".
- `proxy/AgentContextManager.kt:159`, `proxy/AgentContextCleanupService.kt:68`, and
  `proxy/ProxyServerTransportFilter.kt:56`, which each log `AgentContext.toString`, including the name and host.

**Problem:**

- **Attacker:** an authenticated agent, or anyone when auth is off.
- **Trigger:** a path such as `team_a_x\n12:00:00.000 WARN … forged` passes `formRejection` and the glob (`*` matches
  line terminators by design) and is logged at INFO. The agent's name, host, and launch ID (cut to 256 characters, not
  escaped) and JSON label keys do the same.
- **Impact:** the agent can forge log lines, such as fake denials or connects for another identity, which matters
  wherever logs feed alerting or an audit trail. Such a path can never be scraped anyway, since the scrape route
  compares the raw, percent-encoded request path, so refusing it costs nothing.

**Fix:**

- In `formRejection`, accept only path characters that need no percent-encoding (RFC 3986 `pchar` without `/` and
  `%`). That also refuses paths that can't be scraped.
- Add one helper that cuts a value and escapes control characters, and use it for every agent-supplied value that's
  logged, building on `shownPath`.
- As defense in depth, use `%replace(%msg){'[\r\n]','\\n'}` in both logback files.

**Tests:**

- `registerPath` with a path containing `\n` returns `INVALID_PATH`.
- `registerAgent` with a name containing `\r\n`, under a `ListAppender`, leaves no message with a raw newline.

**Follows up:** #353 and October #4, which cut agent values in logs but didn't escape them.

**Resolution:** Fixed in #386. The new `AgentValues.shownValue` cuts a value and escapes control characters and line
and paragraph separators. `shownPath`, the label warnings, `labelsProblem`, the scrape-result fields, and
`AgentContext.toString` use it. `formRejection` refuses a path with whitespace, a control character, `?`, or `#`, and
the agent refuses such a `pathConfigs` entry at startup. The alphabet isn't narrowed to `pchar`: a percent-encoded or
non-ASCII path is scraped today by a client that sends it as registered, so `pchar` would break working setups, while
the four refused kinds can never be scraped. The logback `%replace` was left out: on the shipped appender it flattens
the startup banner and every multi-line message, and scoping it takes a second appender with `additivity="false"`,
which would keep the proxy's loggers out of any appender an operator adds to the root.

### 17. ✅ With the transport filter disabled, every `connectAgentWithTransportFilterDisabled` call creates and announces a new agent context, with no limit

**Severity:** low (a non-default mode) · **Confidence:** plausible. The missing limit is confirmed; the memory per
context wasn't measured.

**Where:** `proxy/ProxyServiceImpl.kt:91-108` and `proxy/AgentContextManager.kt:72-80`. Eviction comes only after
`maxAgentInactivitySecs` (`AgentContextCleanupService.kt:55-80`).

**Problem:**

- **Attacker:** any authenticated agent, or anyone when auth is off.
- **Trigger:** repeated calls of this cheap unary RPC. Each creates a context and announces it, which means an INFO
  line, an `AgentConnected` event, and a dashboard wake.
- **Impact:** contexts live about 70s, so the live count is the call rate times 70s, and each context gets its own
  20,000-path quota without opening a new connection.

**Fix:** cap contexts per identity, and in total, in this mode, or reuse the caller's existing context for the same
identity and stream.

**Tests:** 10,000 calls from one identity leave at most the cap in `agentContextSize`.

**Resolution:** Fixed in #387, with a per-identity cap only. The new `proxy.internal.maxAgentsPerIdentity` (10,000; 0
for no limit) is enforced by `AgentContextManager.addAgentContextWithinLimit`, which claims a slot inside a `compute` on
the identity before it adds the context, and frees it on removal. A connect past the cap gets `RESOURCE_EXHAUSTED`,
which the agent already retries after its reconnect pause; evicting instead would drop a fleet reconnecting after a
proxy restart. A refusal logs a WARN at most once a minute per identity. There is no separate total: the identities are
a fixed, configured set, so they bound it, and a global cap would let one identity lock out the others. With auth off or
the legacy token, every agent shares one identity, so the cap is the total, and `docs/security-agent-authentication.md`
records that a peer connecting in a loop can then hold it for about 70s.

### 18. ✅ The dashboard's page routes build a fresh snapshot on every request

**Severity:** low (the dashboard is opt-in and documented as unauthenticated) · **Confidence:** confirmed.

**Where:** `proxy/dashboard/ProxyDashboardService.kt:248-280`: each GET calls `snapshot()`. `recentSnapshot()`
(`:391-392`) is used only by the WebSocket.

**Problem:**

- **Attacker:** anyone who can reach the dashboard port, which listens on `0.0.0.0` when enabled.
- **Trigger:** repeated GETs of `/dashboard`, `/paths`, or `/agents/x`.
- **Impact:** each copies the whole path map while holding the `pathMap` monitor, which every scrape and registration
  also takes, and renders full HTML for every path. So a client can slow down scrapes. September #4 fixed this for the
  WebSocket only.

**Fix:** serve the GET routes from `recentSnapshot()`.

**Tests:** in `ProxyDashboardServiceTest`, N quick GETs collect the snapshot at most once per refresh interval.

**Follows up:** September #4.

**Resolution:** Fixed in #378. The page routes read a `SnapshotCache` shared with the WebSocket. A snapshot is reused
for less than the refresh interval but never across a topology change: a generation counter also discards a collect that
a change overtook, which the old clear-on-event cache could serve for a whole interval. One collect serves concurrent
reads. Twenty GETs within an interval now collect once, against twenty before.

### 40. ✅ An unknown scrape path is logged whole, with any control characters it carries

**Severity:** low · **Confidence:** confirmed by a test. Found while fixing #16.

**Where:** `proxy/ProxyUtils.kt:90` (`invalidPathResponse`), called from `proxy/ProxyHttpRoutes.kt:144,161` for any
request path no agent registered.

**Problem:**

- **Attacker:** any client of the scrape port, which is unauthenticated.
- **Trigger:** a `GET` of an unregistered path holding a control character, such as a terminal escape, or as long as
  the request line allows. CIO ends the request target at whitespace, so CR and LF can't get in, but it passes the
  other control characters.
- **Impact:** the path goes whole into a WARN and the `/debug` activity log, so a client can write terminal escape
  sequences, or a request line's worth of text per request, into both. That's the same gap #16 closes for agents, with
  no authentication in front of it.

**Fix:** show the path through `shownPath`, as an agent's is.

**Tests:** `invalidPathResponse` with a path holding `ESC[2J` and 10,000 more characters logs a message with the escape
written out, no control characters, and under 100 characters.

**Follows up:** #16.

**Resolution:** Fixed in #386. `invalidPathResponse` logs `shownPath(path)`. The new `ProxyUtilsTest` test failed on
the old code.

---

## 🏗️ CI, build, and packaging

### 19. ✅ The published library jar ships `logback.xml`, which replaces the host application's logging configuration

**Severity:** medium · **Confidence:** confirmed with a plain Logback host and with Spring Boot 3.5.0, against the
published 4.4.2 jar.

**Where:**

- `src/main/resources/logback.xml`. It is in `prometheus-proxy-4.4.2.jar` on Maven Central, next to `reference.conf`
  and `banners/`.
- `build.gradle.kts:319`: `src/shadow/resources` already holds the resources that only the fat JARs get.
- `README.md:456` says embedding "leaves your application's own logging alone".

**Problem:** Logback configures itself from the first `logback.xml` on the classpath, and Spring Boot treats any
classpath `logback.xml` as self-initialization config, which it uses ahead of `logback-spring.xml`.

- A plain Logback host with no config of its own switches from Logback's defaults to this library's file just by
  having the jar on the classpath. Its root level becomes INFO, `io.grpc.internal` becomes WARN, and its pattern
  changes.
- A Spring Boot app with `logback-spring.xml` silently drops its own config, including its file and JSON appenders,
  once `prometheus-proxy-4.4.2.jar` is added.

This undoes October #16's goal for every embedder.

**Fix:** move `logback.xml` from `src/main/resources` to `src/shadow/resources`, which `configureFatJar` already adds,
since only the fat JARs and the images need it. Keep `reference.conf` in the library, as Typesafe's convention
expects. Say on `embedded-agent.md` that the host configures logging, for example `io.grpc.internal` at WARN.

**Tests:** a unit test that opens the `jar` task's output and asserts it has no `logback.xml`, and that each fat JAR
still has one.

**Follows up:** October #16 (incomplete).

**Resolution:** Fixed in #375. `logback.xml` moved to `src/shadow/resources`, and `embedded-agent.md` says the host
configures logging. The new `make consumer-check`, also a CI job, fails if any jar on a Maven consumer's classpath
carries a Logback config.

### 20. ✅ Java embedders can't compile against the published artifact: `Agent`'s supertype is runtime-scoped, and the README's Java call has the wrong arity

**Severity:** medium · **Confidence:** confirmed with javac, kotlinc, and `mvn compile` against 4.4.2.

**Where:**

- `build.gradle.kts:106`: `implementation(libs.bundles.common.utils)`.
- `Agent.kt:166-178` and `Proxy.kt:174`: both public entry points extend `GenericService<ConfigVals>`, from
  common-utils' `service-utils`.
- `Agent.kt:835-839`: `startAsyncAgent(..., logBanner: Boolean = true)`, without `@JvmOverloads`.
- `README.md:438`: `startAsyncAgent("configFile.conf", false)`.
- The published POM puts every dependency except `kotlin-stdlib` at `runtime` scope, and the Gradle module's
  `apiElements` has only `kotlin-stdlib`.

**Problem:**

- `src/test/java/website/EmbeddedAgentJavaExample.java`, the snippet on the website's Embedded Agent page, fails in a
  Maven project that depends only on `com.pambrose:prometheus-proxy:4.4.2`, with `cannot access
  com.pambrose.common.service.GenericService`. A Gradle `implementation(...)` Java consumer fails the same way. javac
  needs `service-utils`, then `guava-utils`, then Guava.
- In Kotlin, `Agent.startAsyncAgent` compiles, but calling an inherited member on an `Agent` (for example
  `agent.startSync()`) fails with "cannot access 'GenericService' which is a supertype of 'io.prometheus.Agent'".
- The README's Java snippet fails even with the full classpath ("required: String,boolean,boolean"), because Java
  can't see Kotlin defaults.

The repo compiles its own Java example against the project's classpath, so CI never sees this.

**Fix:**

- Declare `api(libs.common.utils.service)`. Its POM already has `guava-utils` and Guava at compile scope, and that
  alone made javac succeed. Or stop exposing `GenericService` through the public types.
- Add `@JvmOverloads` to `startAsyncAgent`, or make the README pass three arguments, as the website does.

**Tests:** a CI consumer check: `publishToMavenLocal`, then `mvn -q compile` on a small project holding
`EmbeddedAgentJavaExample.java` and the Kotlin example. It fails today.

**Resolution:** Fixed in #375. `service-utils` is declared with `api`, so it has compile scope in the POM, and
`startAsyncAgent` has `@JvmOverloads`. The consumer check compiles the website's Java and Kotlin embedded-agent examples
as a Maven project; against the old POM it failed with `cannot access GenericService`.

### 21. ✅ Maven embedders still get Jackson 2.12.7 and tcnative 2.0.84 natives under 2.0.85 classes

**Severity:** medium · **Confidence:** confirmed with `mvn dependency:list` on a project that depends only on
`prometheus-proxy:4.4.2` (found by both the build and the security passes).

**Where:**

- `build.gradle.kts:115`: `runtimeOnly(libs.netty.tcnative)`, with no classifier.
- `build.gradle.kts:127-128`: `implementation(platform(libs.jackson.bom))`.
- `gradle/libs.versions.toml:31-37` (tcnative 2.0.85, "for the native fixes in 2.0.82-2.0.85, among them … an
  out-of-bounds read in ALPN protocol selection") and `:61-63` (Jackson raised past CVE-2025-52999 and
  CVE-2026-54512/54513).
- common-utils' `grpc-utils-5.2.0.pom` declares the five tcnative classifier jars at 2.0.84.Final.

**Problem:**

- **Jackson.** Maven 3 ignores a dependency's `<dependencyManagement>` BOM imports, so the jackson-bom in this POM
  does nothing for Maven embedders. They resolve jackson-core and jackson-annotations 2.12.7 and jackson-databind
  2.12.7.2 through common-utils → `metrics-jakarta-servlets` → `metrics-json`. Those are the versions the 4.3.x
  changelog's Security entry says were fixed. Gradle consumers get 2.23.0 through the module's platform dependency,
  and the fat JARs ship 2.23.0.
- **tcnative.** Maven's conflict key includes the classifier, so the classifier jars are resolved apart from the
  classifier-less 2.0.85 declaration. Both arrive at depth 2, and `grpc-utils` is declared first, so its 2.0.84 natives
  win on every platform, beside `netty-tcnative-classes` 2.0.85. That breaks the "classes and natives must match" rule
  at `build.gradle.kts:142-145`, and the 2.0.85 native fix doesn't reach Maven embedders.
- Netty itself is correct (4.2.20.Final), as are grpc and the Prometheus client, though those two line up only
  because common-utils 5.2.0 uses the same versions today.

This is October #25's problem, which was fixed then for Netty only.

**Fix:** apply #25's remedy to both.

- Add a `jackson` bundle (`jackson-core`, `jackson-databind`, `jackson-annotations`), declared `runtimeOnly` at
  `libs.versions.jackson`.
- Declare the five tcnative classifier artifacts directly, at `libs.versions.tcnative`.
- Extend the `eachDependency` guard so that a Jackson module missing from the bundle fails `runtimeClasspath`, as it
  does for Netty.

**Tests:** the Maven consumer check from #20 also runs `dependency:list` and asserts Jackson 2.23.0 and tcnative
2.0.85 for every classifier.

**Follows up:** October #25 (incomplete).

**Resolution:** Fixed in #375. A `jackson` bundle and the five tcnative classifier jars are declared at the catalog's
versions, and the `eachDependency` guard now covers Jackson. The consumer check asserts that Jackson, tcnative, and
Netty resolve at those versions for a Maven build; against the old POM it found Jackson 2.12.7 and tcnative 2.0.84
natives.

### 22. ✅ `docs.yml` checks the `gradle.properties` version, so with a `-SNAPSHOT` on `master`, no docs change deploys until the next release

**Severity:** low · **Confidence:** confirmed from the workflow and its run logs.

**Where:**

- `.github/workflows/docs.yml:42-64`.
- `docs/RELEASE.md:29-33`: "Outside a release, the version on `master` is already published, so a docs change
  deploys as it merges".
- `AGENTS.md:120-121`.
- `bin/docker-agent.sh:6` and `bin/docker-proxy.sh:6`.

**Problem:** #368 set `version=4.4.2-SNAPSHOT` on `master`, as the Makefile's `SNAPSHOT_VERSION` now expects.
`docs.yml` then probed Maven Central and Docker Hub for `4.4.2-SNAPSHOT`, got 404, and skipped the deploy for #368 and
#369 (the Context7 widget), and for #370 and #371, until `make release` dispatched one. But the site never shows the
`gradle.properties` version. It shows the version literals in step 2's snippet files, which still said 4.4.1, a
published version, the whole time. The `bin/docker-*.sh` scripts have the same cause: they would run the nonexistent
`:4.4.2-SNAPSHOT` tag.

**Fix:**

- Probe the version the site actually advertises (for example, the image tag in
  `src/test/kotlin/website/DockerExamples.txt`), or treat a `-SNAPSHOT` version as "the site still shows the last
  release" and deploy.
- Correct the wording in `RELEASE.md` and `AGENTS.md`.
- Have the `bin` scripts strip `-SNAPSHOT`, or refuse to run.

**Tests:** move the probe into a make target (for example `site-version`) that `docs.yml` calls, and pin its output
with a `misc/` spec.

**Resolution:** Fixed in #384. The new `make site-version` prints the version the site's image tags and Maven coordinate
give, failing unless they give exactly one, and `docs.yml` probes that version instead of the `gradle.properties` one: a
docs change deploys as it merges, while a merged version bump still waits for `make release`. `bin/docker-*.sh` run that
version, and `docs/RELEASE.md`, AGENTS.md, and the docs-site skill are reworded. The new `SiteVersionTest` pins it.

### 23. ✅ The published Gradle module has an empty `shadowRuntimeElements` variant

**Severity:** low · **Confidence:** confirmed in `prometheus-proxy-4.4.2.module` on Maven Central.

**Where:** `build.gradle.kts:303-307` disables `tasks.shadowJar`, but the Shadow plugin still adds its component
variant.

**Problem:** the published module has a `shadowRuntimeElements` variant (`bundling=shadowed`) with no files and no
dependencies. A Gradle consumer that asks for `Bundling.EMBEDDED` or `SHADOWED` selects it and gets nothing on its
classpath. Ordinary consumers still get `runtimeElements`.

**Fix:** `components.named<AdhocComponentWithVariants>("java") {
withVariantsFromConfiguration(configurations["shadowRuntimeElements"]) { skip() } }`.

**Tests:** a check on the `generateMetadataFileForMavenPublication` output that every variant has at least one file.

**Resolution:** Fixed in #375. The variant is skipped, and every publish now fails on a variant with no files.

### 24. ✅ `docs-site` and `zizmor` still aren't required checks

**Severity:** low · **Confidence:** confirmed with `gh api …/branches/master/protection`.

**Where:** the branch protection's required checks are Codacy, `build`, `codeql`, `container-tests`, `helm`,
`helm-smoke`, `image-scan` (agent and proxy), `rules`, `tla`, `CodeQL`, and GitGuardian. `ci.yml`'s `docs-site` and
`zizmor.yml`'s `zizmor` are missing. AGENTS.md says "The zizmor check fails a pull request on any finding".

**Problem:** a PR with a red `docs-site` or `zizmor` check can merge. After a broken-site merge, the push build fails
too, and `release-check`, whose `RELEASE_REQUIRED_JOBS` includes `docs-site`, blocks the next release until a fix
lands. That is the failure mode October #31 described and left as a maintainer setting. A zizmor finding that merges
fails nothing later, since the push run only uploads SARIF.

**Fix:** add `docs-site` and `zizmor` to the required checks. Both run on every PR to `master`, including Dependabot's.

**Tests:** nothing in the repo can read branch protection. `release-check` could warn when `RELEASE_REQUIRED_JOBS`
names a job that isn't required.

**Follows up:** October #31.

**Resolution:** Done on 2026-11-12 as a repository setting: `master`'s branch protection now also requires `docs-site`,
`zizmor`, and `consumer-check` (#375's job), 15 checks in all. The suggested `release-check` warning was skipped:
`RELEASE_REQUIRED_JOBS` names push-only jobs such as `zizmor.yml/code-scanning`, whose pull-request counterpart has
another name.

### 25. ✅ CI's docs build uses whichever uv is newest

**Severity:** low · **Confidence:** confirmed that it floats; the impact is plausible.

**Where:** `.github/workflows/ci.yml:129` and `docs.yml:68`: `astral-sh/setup-uv` with no `version` or
`version-file`. There is no `uv.toml`, and `website/pyproject.toml` has no `required-version`.

**Problem:** `master`'s last `docs-site` log says "Could not determine uv version … Falling back to latest" and
downloads uv 0.13.7, while the local uv is 0.12.19. What CI runs changes with no repo diff, which `CiHygieneTest`
exists to prevent. A uv release that changes how `--locked` or the lock revision is handled would fail `docs-site` and
the deploy with no commit to blame.

**Fix:** set `version` on both steps (or use `version-file: website/pyproject.toml` with `[tool.uv]
required-version`), and update it along with the local uv.

**Tests:** a `CiHygieneTest` case that requires `version` or `version-file` on every `setup-uv` step.

**Resolution:** Fixed in #384. Both `setup-uv` steps pin `version: "0.13.7"`, the release CI had been getting as the
newest; `website/uv.lock` passes `uv lock --check` under it and the site builds `--locked`. A `CiHygieneTest` case
requires the same `version` or `version-file` on every `setup-uv` step.

### 26. ✅ Comments and help text left stale by the distroless move

**Severity:** low · **Confidence:** confirmed.

**Where and what:**

- `Makefile:23-24` says "an image keeps the one its Ubuntu base sets (24.04)". The distroless base sets no labels, and
  the 4.4.2 image has only this repo's labels and `maintainer`.
- `.github/workflows/security.yml:3-4` says Trivy scans "the Temurin base layer". The base layer is now
  `gcr.io/distroless/base-nossl-debian13`, with the Temurin JRE copied in.
- `Makefile:151`: `tests: ## Run all tests` runs `check`, which leaves out the Lincheck specs and skips the container
  specs. `all-tests` is the full suite.

**Fix:** reword all three.

**Tests:** none needed.

**Resolution:** Fixed in #384. The `Makefile`'s OCI-label comment says the distroless base sets no labels,
`security.yml` says Trivy scans the distroless Debian 13 base's packages, and the `tests`, `mini-tests`, and `xxl-tests`
help says what they leave out.

---

## 📚 Documentation

### 27. ✅ The Docker quick starts can't connect: `simple.conf` points the agent at `localhost`, and the production commands combine `--rm` with `--restart`

**Severity:** medium · **Confidence:** confirmed from the files. Docker's rejection of `--rm` with `--restart` is
stated three times in the repo's own docs, but wasn't run here.

**Where:**

- `README.md:276-279` (Docker Quick Start) and `README.md:383-410` (Production Docker Setup).
- `llms.txt:130-133`.
- `src/test/kotlin/website/DockerExamples.txt:8-12`, which the website renders in `docker.md`'s "Agent with Remote
  Config".
- `examples/simple.conf:11,20,27`.

**Problem:** this is the first thing a new user runs, and it can't work:

- **The agent never connects.** Each of these runs the agent image with only `AGENT_CONFIG` pointing at
  `examples/simple.conf`. That file sets `agent.proxy.hostname = localhost`, and the image sets no `PROXY_HOSTNAME`, so
  the agent dials its own container's loopback and retries forever. Its two `pathConfigs` URLs,
  `http://localhost:8082/metrics` and `:8083/metrics`, are also the agent's own container. `bin/docker-agent.sh` and
  the README's TLS example (`README.md:871`) pass `PROXY_HOSTNAME`; the quick starts don't.
- **`--rm` with `--restart`.** `README.md:385-388` and `:396-398` combine `docker run --rm … --restart
  unless-stopped`. The same README says at `:283-284` that "Docker doesn't allow `--rm` with `--restart`", as do
  `docker.md:33` and `getting-started.md:136,273`.
- **Wrong URLs.** `README.md:406-410` says the metrics are then at `http://localhost:8082/proxy_metrics` and
  `http://localhost:8083/agent_metrics`. Registered paths are served on the scrape port, 8080. Ports 8082 and 8083 are
  the proxy's and agent's own `/metrics` endpoints, so both URLs return 404.

**Fix:**

- Add `--env PROXY_HOSTNAME=<proxy-host>` to every agent quick start (README, `llms.txt`, `docker-agent-basic`), and
  say which address to use (the host's address, or a shared Docker network). Or point them at the compose stack, which
  already works.
- Drop `--rm` from the two Production Docker Setup commands, or use the `--detach --name …` form the README gives a
  few lines earlier.
- Change the URLs to `http://localhost:8080/proxy_metrics` and `http://localhost:8080/agent_metrics`.

**Tests:** a `misc/` check that rejects any `docker run` snippet in the README, `llms.txt`, or the website that has
both `--rm` and `--restart`.

**Resolution:** Fixed in #376. The quick starts put both containers on a user-defined network, give the agent
`PROXY_HOSTNAME`, and enable the proxy's metrics, and `simple.conf` fetches the proxy's metrics from `PROXY_HOSTNAME`
when it's set. Run against the published 4.4.2 images, both paths answered 404 before and 200 after, in the quick start
and the production setup. The new `DocsExamplesTest` rejects a documented `docker run` with both `--rm` and `--restart`.

### 28. ✅ The shipped examples use non-string label values, which 4.4.0 warns about and leaves out of service discovery

**Severity:** low · **Confidence:** confirmed.

**Where:**

- `examples/simple.conf:19,26` (`"key2": 2`, `"key4": 4`) and `examples/myapps.conf:6,12,18`.
- The same snippet at `README.md:312,318,324`, and `website/prometheus-proxy/docs/examples.md:24,53`, which shows the
  files verbatim.

**Problem:** `service-discovery.md:107-108` says label values must be strings, "so the proxy leaves such entries out".
`Utils.labelsProblem` (`common/Utils.kt:143`) returns "label '…' must have a string value". The agent then warns at
startup for each of these `pathConfigs` entries (`AgentPathManager.kt:123-129`), the proxy warns at registration
(`ProxyPathManager.kt:388-390`), and SD drops `key2`, `key4`, and `key6` (`Proxy.kt:582`). So the example a new user
starts from logs warnings, and three of its six labels never appear. These files are served from `master` as the
quick start's `AGENT_CONFIG`, and no test loads them.

**Fix:** quote the values (`"key2": "2"`) in both example files and the README snippet.

**Tests:** a `misc/` spec that loads each `examples/*.conf` through `AgentOptions` and `ProxyOptions`, and asserts that
`labelsProblem` is null for every `pathConfigs` entry.

**Resolution:** Fixed in #376. The values are quoted, and `DocsExamplesTest` requires valid labels in every
`examples/*.conf`.

### 29. ✅ `docs/metrics-and-grafana.md` gives the pre-4.4.0 agent latency buckets and names a removed class

**Severity:** low · **Confidence:** confirmed.

**Where:** `docs/metrics-and-grafana.md:156,183,193`.

**Problem:**

- **Buckets.** `:193` gives `agent_scrape_request_latency_seconds` buckets as `5ms–10s`, and the next line says
  "Same bucket boundaries as the proxy latency histogram". The agent now uses `SCRAPE_LATENCY_BUCKETS`, up to 90s
  (`common/MetricBuilders.kt:44-45`, `AgentMetrics.kt:92`), as the 4.4.0 changelog and the website's `monitoring.md:191`
  say.
- **Removed class.** `:156` says "Gauges use lazy sampling (`SamplerGaugeCollector`)". That class went in #343; the
  gauges are `GaugeWithCallback`s built by `MetricBuilders.sampledGauge`.
- **`launch_id`.** `:183`, and `monitoring.md:175`, say it "identifies each agent process lifetime". Since 4.4.0,
  several agents in one JVM each have their own.

**Fix:** change the cell to `5ms–90s`, name `sampledGauge` and `GaugeWithCallback`, and say `launch_id` identifies
each agent instance.

**Tests:** `DashboardMetricNamesTest` reads only this file's `promql` blocks. Checking its tables' bucket ranges
against `SCRAPE_LATENCY_BUCKETS` would catch the next drift.

**Resolution:** Fixed in #389. The cell reads `5ms–90s`, and the gauges are named as `GaugeWithCallback`s built by
`MetricBuilders.sampledGauge`. `launch_id` identifies each agent instance there, on the website's Monitoring, Glossary,
and Grafana pages, and in `EmbeddedAgentInfo.launchId`'s KDoc, and both gauge tables show that
`proxy_start_time_seconds` carries one. `DashboardMetricNamesTest` now checks the tables' bucket ranges, and the bucket
lists on both pages, against `SCRAPE_LATENCY_BUCKETS`; the range check fails on the old `5ms–10s`.

### 30. ✅ The website tells embedders to call `EmbeddedAgentInfo.close()`, which doesn't exist

**Severity:** low · **Confidence:** confirmed.

**Where:** `website/prometheus-proxy/docs/production.md:189-190`.

**Problem:** the page says embedded agents "should be stopped via `EmbeddedAgentInfo.shutdown()` (or `close()`)".
`agent/EmbeddedAgentInfo.kt:51-89` is a plain `data class` with `launchId`, `agentName`, and `shutdown()`. It isn't
`AutoCloseable`, so `close()` doesn't compile against this public API type.

**Fix:** drop "(or `close()`)". (Making `EmbeddedAgentInfo` `AutoCloseable` would also work, but it's a public API
change, and `use { }` would make it easy to stop an agent sooner than intended.)

**Resolution:** Fixed in #389. The Production page names only `shutdown()`, and `EmbeddedAgentInfo` stays a plain data
class.

### 31. ✅ The `transportFilterDisabled` (nginx) warnings say a disconnected agent lingers until the inactivity timeout; the code removes it when its stream ends

**Severity:** low · **Confidence:** confirmed for a disconnect that ends the stream. Whether a half-open connection
through nginx ends it promptly is plausible, but wasn't checked.

**Where:**

- `website/prometheus-proxy/docs/advanced.md:16-20`.
- `website/prometheus-proxy/docs/configuration/proxy.md:201-205`.
- `website/prometheus-proxy/docs/troubleshooting.md:194-198`.
- `README.md:774-776`.

**Problem:** the docs say "agent disconnections are not immediately detected. Agent contexts on the proxy are removed
after the inactivity timeout", and Troubleshooting adds that stale contexts "are cleaned up only after
`maxAgentInactivitySecs`". But `ProxyServiceImpl.readRequestsFromProxy`'s `finally` (`:397-404`) calls
`proxy.removeAgentContext(agentId, "Stream terminated (transport filter disabled)")`, and
`harness/InProcessTransportFilterDisabledTest` asserts that the context and path are gone within 10s of
`agent.stopSync()`. Eviction is only the backstop for a connection whose stream never opened or never ends.

**Fix:** say that a disconnect that ends the agent's request stream is cleaned up at once, and that
`maxAgentInactivitySecs` covers an agent that dies before opening its stream, or whose connection stays half-open.

**Resolution:** Fixed in #387, alongside #14 and #17. The README and the website's Advanced Topics, Proxy Configuration,
and Troubleshooting pages say a disconnect that ends the agent's stream removes it at once, and that the inactivity
timeout covers only an agent that dies before opening its stream, or a half-open connection.

### 32. ✅ Public `EnvVars` and `ProxyOptions` KDoc lists a log level that's rejected, and describes `sdTargetPrefix` the old way

**Severity:** low · **Confidence:** confirmed.

**Where:**

- `common/EnvVars.kt:88,165` (`PROXY_LOG_LEVEL`, `AGENT_LOG_LEVEL`) and `common/BaseOptions.kt:195` (`logLevel`).
- `common/EnvVars.kt:51` (`SD_TARGET_PREFIX`).
- `proxy/ProxyOptions.kt:107-112`: the `sdTargetPrefix` KDoc and the `--sd_target_prefix` usage text.

**Problem:**

- **`all`.** All three log-level KDocs say "one of `all`, `trace`, …". `Utils.setLogLevel` (`common/Utils.kt:160-174`) has
  no `all` branch ("`all` is intentionally unsupported") and throws `Invalid … log level: all`. The website's
  `production.md:184` and `cli-reference.md:110` already say `all` fails at startup.
- **`sdTargetPrefix`.** `SD_TARGET_PREFIX` and `ProxyOptions.sdTargetPrefix` are documented as the "Base URL used to
  build per-target entries" (for example `http://proxy:8080/`), "validated to be non-empty". Since 4.4.0 the value is
  `host:port` (default `localhost:8080`), or an http or https URL that `SdTarget.parse` splits into the target, a
  `__scheme__` label, and a path prefix, and a bad value fails startup.

All three are Dokka-published API types.

**Fix:** drop `all` from the three KDocs. Describe the SD value as `docs/cli-args.md:20` does, and give the
`--sd_target_prefix` usage text the same wording.

**Follows up:** October #1 (the SD target wording).

**Resolution:** Fixed in #389. The three log-level KDocs drop `all`. `SD_TARGET_PREFIX`, `ProxyOptions.sdTargetPrefix`,
and the `--sd_target_prefix` usage text describe the target as `docs/cli-args.md` does, with the rules #388 completed:
`host:port` or an http(s) URL, with no user info, query, or fragment.

---

## 🧪 Tests

### 33. ✅ The ownership tests forge results only under the attacker's own agentId, so they'd pass if the check trusted the agent's claim

**Severity:** medium · **Confidence:** confirmed by reading the tests against the check.

**Where:**

- `proxy/ProxyServiceImplTest.kt`, with the transport filter on (the default):
  - `:2066-2084`, a forged response with `agentId = "attacker-agent"`;
  - `:2107-2127`;
  - `:2129-2158`, `headerAgentId = "attacker-agent"`;
  - `:2315-2337`, `"intruder-agent"`;
  - `:2356-2402`, `chunkedHeaderFrom("intruder-agent", …)`.
- `proxy/ScrapeResultOwnershipLincheckTest.kt`: `respond()` sets `srAgentId = agent.agentId`, and the ownership
  predicate is re-implemented inline.

**Problem:** the product compares the connection's transport-assigned agentId with the wrapper's owner
(`ProxyServiceImpl.kt:447-463`, `isScrapeOwnedByConnection`). `ChunkedContext.kt:36` says `headerAgentId` "is the
agent's own claim, so it is never used". But in every one of these tests, the payload's `agentId` or `headerAgentId`
equals the attacker's own connection ID. So a regression to the pre-4.1.0 shape would still pass them all: for
example, `isOwner = { it.agentContext.agentId == response.agentId }`, or a header check against `headerAgentId`. Only
the tests with the transport filter disabled (`:2653-2717`) forge the victim's ID, and the Lincheck spec doesn't call
the product's predicate at all. This is the test that guards September #1 and October #21, the two worst findings of
those reviews.

**Fix:** add tests that run `onConnection("attacker-agent")` and send:

- `scrapeResponse { agentId = victim.agentContext.agentId; scrapeId = victim.scrapeId }`;
- a chunked header with `headerAgentId = victim.agentContext.agentId`.

Assert that the victim's results stay null and that no chunked context opens. Optionally, have the Lincheck spec's
forged response carry the owner's agentId, and call the product's predicate rather than a copy.

**Follows up:** October #21 and September #1.

**Resolution:** Fixed in #374. Four new tests forge the victim's agentId and `headerAgentId`. With the check changed to
trust the payload, exactly those four fail. The Lincheck spec's forged answer now carries the owner's agentId, but the
spec still uses its own copy of the predicate: the product's is a private member that needs a `Proxy`, and a regression
in how `isOwner` is built from the response wouldn't show in a predicate anyway. `ProxyServiceImplTest` covers the
product's check.

### 34. ✅ `AgentPathAuthTest` still falls back to fetching its config from GitHub `master`

**Severity:** low · **Confidence:** confirmed.

**Where:** `harness/AgentPathAuthTest.kt:111-116`.

**Problem:** the spec defines `GH_PREFIX = "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/"` and
`localOrGitHub(path) = if (File(path).exists()) path else "$GH_PREFIX$path"`. September #31 removed exactly this
pattern from `HarnessConstants.localConfigFile`, whose comment says it "hid a renamed or deleted config and quietly
tested against whatever master held". This security-boundary spec kept its own copy: rename
`config/test-configs/path-auth.conf`, and it tests `master`'s auth config over the network instead of failing.

**Fix:** use `HarnessConstants.localConfigFile(AUTH_CONFIG_FILE)`, and delete `localOrGitHub` and `GH_PREFIX`.

**Tests:** a `misc/` check that no file under `src/test/kotlin/io` contains `raw.githubusercontent`.

**Follows up:** September #31.

**Resolution:** Fixed in #374. The spec loads its config through `localConfigFile`, and the new `LocalTestConfigTest`
fails on any `raw.githubusercontent` under `src/test/kotlin/io`.

### 35. ✅ The registry-clash test accepts any startup failure

**Severity:** low · **Confidence:** plausible. A false pass needs a different startup failure, such as
`EMBEDDED_METRICS_AGENT_1_PORT` held by another process.

**Where:** `harness/EmbeddedAgentMetricsTest.kt:105-113`.

**Problem:** the test is `shouldThrow<IllegalStateException> { startAgent("metrics-agent-clash", AGENT_1_METRICS_PORT) }`.
`startAsyncAgent` turns every startup failure into an `IllegalStateException` (`Agent.kt:830-831`, through Guava's
`startSync`). So a taken metrics port, or any other startup error, passes the test without the registry clash it
claims to check. If startup unexpectedly succeeds, the agent is also left running.

**Fix:** assert the cause, for example `.cause.shouldBeInstanceOf<IllegalArgumentException>()` with a message naming
`agent_connect_count`, and shut down any agent that does start.

**Resolution:** Fixed in #379. The test requires the registry's `IllegalArgumentException` naming `agent_connect_count`
as the cause, and shuts down an agent that starts anyway. With the metrics port held instead, the old assertion passed
and the new one fails.

### 36. ✅ `DashboardMetricNamesTest` never closes the metrics it builds, so their share of the JVM-wide collectors leaks

**Severity:** low · **Confidence:** plausible. It's latent; nothing fails today.

**Where:** `misc/DashboardMetricNamesTest.kt:51-56`.

**Problem:** the test builds `ProxyMetrics(mockProxyForMetrics())` and `AgentMetrics(mockAgentForMetrics())` and never
calls `close()`. `SharedCollectors` lives in the companion objects, and its instance map survives
`defaultRegistry.clear()`. So these instances stay acquired: their collectors are never unregistered, and they add
phantom instances to the summed and per-agent sampled gauges for the rest of the JVM. It's harmless today only because
every later mock has the same launch ID, so the next spec's `close()` releases the leaked entry. A spec that builds
metrics with distinct IDs and expects the last release to unregister them would be order-dependent.

**Fix:** build them in `beforeSpec` and close them in `afterSpec`, as `AgentMetricsTest` and `ProxyMetricsTest` do.

**Resolution:** Fixed in #379. The metrics are built in `beforeSpec` and closed in `afterSpec`, which then checks that
no `proxy_` or `agent_` metric is left registered.

### 37. ✅ The container suite's third-party images are pinned by tag only

**Severity:** low · **Confidence:** confirmed.

**Where:** `containers/support/ContainerTestSupport.kt:63-74` (`NGINX_IMAGE = "nginx:1.31-alpine"`,
`PROMETHEUS_IMAGE = "prom/prometheus:v3.14.0"`), and `misc/CiHygieneTest.kt:58-64`.

**Problem:** the KDoc says the images are pinned "so container runs are reproducible", but `nginx:1.31-alpine` moves
with every 1.31.x and Alpine rebuild, and `prom/prometheus:v3.14.0` can be re-pushed. `container-tests` is a required
check and in `RELEASE_REQUIRED_JOBS`. October #38 pinned the `Makefile`'s copy of the same Prometheus image by digest
for exactly this reason ("A re-pushed tag … changes what CI runs without any diff in the repo"), but `CiHygieneTest`
reads only the `Makefile`'s `*_IMAGE` variables.

**Fix:** pin both constants as `image:tag@sha256:…`, and extend `CiHygieneTest` to read `ContainerTestSupport`'s
`*_IMAGE` constants (skipping the two locally built test images).

**Follows up:** October #38.

**Resolution:** Fixed in #384. `NGINX_IMAGE` and `PROMETHEUS_IMAGE` are pinned as `image:tag@sha256:…` index digests,
the Prometheus one the same as the `Makefile`'s. `CiHygieneTest` requires a digest on every third-party `*_IMAGE`
constant, checks Testcontainers parses each to the digest it pulls, and checks the two Prometheus pins agree.

### 38. ✅ `harness.conf` sets Zipkin keys that don't exist

**Severity:** low · **Confidence:** confirmed. Found while fixing #7.

**Where:** `config/test-configs/harness.conf:3,20`.

**Problem:** the file sets `proxy.zipkin.enabled` and `agent.zipkin.enabled`, but the keys are
`proxy.internal.zipkin.enabled` and `agent.internal.zipkin.enabled` (`config/config.conf`). Typesafe Config ignores
unknown keys, so the two lines do nothing: tracing is off in the harness only because that's the default. A reader, or
a test that sets them to `true`, would expect otherwise.

**Fix:** move them under `internal`, or delete them.

**Tests:** a `misc/` check that every key in `config/test-configs/*.conf` exists in `config/config.conf`.

**Resolution:** Fixed in #389. The two keys moved under `internal`, still `false`. The new `ConfigFileKeysTest` checks
every HOCON config the repo ships or its tests load (`config/test-configs`, `examples`, `testing`, `etc/compose`, the
container suite's agent configs, and `reference.conf`) against `config/config.conf`, list entries included, and failed
only on the old `harness.conf`. It checks files, not the `-D` arguments tests pass, which is how #41 went unseen.

### 41. ⬜ The harness's chunk-size override sets a key that doesn't exist, so no harness spec chunks

**Severity:** medium (tests) · **Confidence:** confirmed. Found while fixing #38.

**Where:** `harness/support/TestUtils.kt:88,106-107` (`startAgent`), and the seven specs that pass
`chunkContentSizeBytes = DEFAULT_CHUNK_SIZE_BYTES` (5): `InProcessTestWithAdminMetricsTest`,
`InProcessTestNoAdminMetricsTest`, `InProcessReconnectTest`, `NettyTestWithAdminMetricsTest`,
`NettyTestNoAdminMetricsTest`, `TlsNoMutualAuthTest`, and `TlsWithMutualAuthTest`.

**Problem:**

- `startAgent` passes `-Dagent.chunkContentSizeBytes=N`, but the key is `agent.chunkContentSizeKbs`, from which
  `AgentOptions` derives `chunkContentSizeBytes`. Typesafe Config ignores the unknown key, so these specs run at the
  default 32 KB.
- No harness spec serves a response near that size, as far as a search shows, so chunked transfers, which #381 reworked
  and #386 bounded, are covered only by unit tests with mocks, never end to end over the in-process, Netty, or TLS
  transports the seven specs exist to cover.
- `ConfigFileKeysTest` (#38) checks config files, not the `-D` arguments tests pass, so it can't see this.

**Fix:**

- `chunkContentSizeKbs` has 1 KB granularity, so 5 bytes can't be set through config. Set
  `AgentOptions.chunkContentSizeBytes` after option resolution in `startAgent`, if the agent reads it only at runtime;
  or pass `chunkContentSizeKbs = 1` and have the shared harness tests serve a response over 1 KB.
- Expect the first end-to-end chunking in the harness to need fixes of its own, and check that the specs still pass in
  their time budget.

**Tests:**

- A harness test that a scrape was chunked, such as `agent_scrape_result_count_total{type="chunked"}` rising.
- A check that every `-D` key `TestUtils` passes exists in `config/config.conf`, in `ConfigFileKeysTest` or
  `HarnessHelpersTest`.

**Follows up:** #38.

---

## ✅ Appendix: checked and sound

What the five passes examined closely and found correct, so the next review knows what was covered.

### Proxy

- **October #21 (scrape-result ownership):** `assignScrapeResults` and `failScrapeRequest` check `isOwner` on the
  wrapper from the same lookup, and drop untracked IDs. `readHeader` does one lookup and uses `putIfAbsent`. A chunk
  or summary is checked against the owner its transfer recorded, and removals use `remove(key, ctx)`.
- **October #3:** `abandonUnfinished` runs in a `finally`, and `ChunkedContext.owner` comes from the wrapper, which is
  what `removeChunkedContextsForAgent` matches on.
- **October #26:** `ProxyServerInterceptor` is attached to `ProxyService` only, so reflection and health calls don't
  announce a connection. The server-wide auth interceptor runs first, and `announce` is idempotent and skips an
  invalidated context.
- **October #22 and #23:** `formRejection` runs before `isAuthorized`. `globMatches` handles empty globs, trailing
  stars, `?`, and backtracking to the last star, in time proportional to the path times the glob.
  `boundedRegistration` truncates without splitting surrogates and caps the endpoint list.
- **October #1, #2, #6, #24:** `SdTarget` handles IPv6, the scheme, and a path prefix (apart from #5). The health
  thresholds resolve to 80% of the in-flight cap. Reserved paths match the route lookup's slash-stripped form. SD drops
  label keys and values Prometheus can't decode.
- **Locking and lifecycle:** `removeAgentContext` invalidates before it sweeps paths, as `ProxyRegistry.tla` models;
  `pathCounts` stays in step with `pathMap`; a displaced agent's orphaned contexts are invalidated. `AgentContext`'s
  backlog admission and drain stay balanced across a failed send, `invalidate`'s drain, and `isStillAwaited` skips.
  The CAS claim in `tryAddToScrapeRequestMap` is balanced by the remove in `dispatchScrapeRequest`'s `finally`.
- **Shared metrics (#343):** per-proxy `pathSeries` with `computeIfPresent` doesn't recreate removed series. The lock
  order is `pathMap` → `pathSeries` bin → histogram, and `close()` releases only this proxy's series.
- **Ktor:** CIO's idle timeout applies only between requests, so the default 45s doesn't cut off a 90s scrape. The
  dashboard's 403s from `onCall` stop the route handlers (`RoutingNode`'s `isHandled` guard).
- **Considered, not reported:** `Proxy.shutDown()` isn't failure-isolated the way October #20 made `Agent.shutDown()`,
  but no step was found that throws in practice. `removeFromPathManager` scans the whole path map on every connection
  close, which is cheap at realistic sizes. Two proxies in one JVM share the per-path histograms; removing a path in
  one drops the other's series until its next scrape.

### Agent and shared code

- **The streamed fetch (#342),** checked in Ktor 3.6.1's source: `prepareGet(...).execute { }` streams the body, a
  connection is reused only after a full read, and `readBuffer(max + 1)` is the real bound. The Content-Length guard
  cancels only the call, not the caller. Redirect hops and `HttpRequestRetry` keep the skip-save marker. The
  `X-Prometheus-Scrape-Timeout-Seconds` value is formatted correctly, and the trust store loads eagerly with a clear
  error.
- **Scrape counting (#345):** `invalid_path`, `success`, `unsuccessful`, `timed_out`, and `failure` are all emitted
  and match `docs/metrics-and-grafana.md`. Cancellation and JVM `Error`s still propagate.
- **Shared metrics (#343):** `SharedCollectors`' acquire and release, the all-or-nothing registration, and series
  removal by `launch_id` are correct. `PrometheusRegistry` 1.10.0 takes no lock in `scrape` or `register`, so the
  callback gauges can't deadlock with `acquire`. `startUp()` rolls the metrics back on failure.
- **Shutdown (#357):** `shutDown()` and `releaseAfterFailedStartUp()` isolate failures (the first is rethrown, the
  rest suppressed). The permanent-channel latch holds, and the run loop exits well inside `stopSync`'s 30s.
- **Discovery (#346):** `lastGoodPaths` survives reconnects, an empty read still reconciles to empty, and the
  `pathConfigs` `/` and duplicate checks mirror `agent.filters`'.
- **`MetricFilter` (#348):** quoted names, unescaping, quoted histogram suffixes, leading blanks, and CRLF are
  handled. The strict UTF-8 decode keeps `bytesSaved` non-negative.
- **Connection lifecycle:** backlog accounting balances across `readRequestsFromProxy`, the drain in `close()`, and
  each scrape's `finally`, and any task ending cancels its siblings. `EndpointFailover` is unchanged since its TLA+
  check. `HttpClientCache`'s in-use accounting, sweeper, and LRU expiry are correct.
- **Two agents in one JVM:** only the shared collectors and the `io.prometheus` log level are global, and each agent's
  Dropwizard registry is empty, so `DropwizardExports` can't clash.

### Security

- **Token auth:** tokens are compared as SHA-256 digests with `MessageDigest.isEqual`. The server-wide auth
  interceptor runs before the per-service ones and covers reflection. Empty, duplicate, and shared tokens fail
  startup, and tokens are logged only as `***`.
- **Binding with the transport filter on:** `connectionMismatchReason` requires an exact match on `registerAgent`,
  `registerPath`, `unregisterPath`, `sendHeartBeat`, and `readRequestsFromProxy`. #14 is the filter-disabled case
  only.
- **Service discovery (#350):** `__*`, `agentName`, `hostName`, and optionally `job` and `instance` are dropped, as
  are non-string values and invalid names. A `__metrics_path__` with `?` or `#` can't reach another path, since
  Prometheus escapes it and the proxy compares raw paths.
- **Dashboard:** kotlinx.html escapes every agent string, attributes carry only proxy-assigned IDs, and the asset
  route is an allowlist. The WebSocket checks its Origin and caps sessions, frames (64 KiB), and its outgoing buffer.
  There are no state-changing endpoints, so CSRF doesn't apply.
- **Scrape-port responses:** a content-type allowlist plus `nosniff` and `CSP: sandbox`. The unzip limit holds, and
  `request.path()` is the raw URI, so the request path can't inject log lines.
- **The agent's HTTP client:** only same-origin redirects are followed. `sanitizeUrl`'s first-`@` rule matches Ktor's
  URL parser, failure reasons go through `sanitizeUrlsInText`, and trust-all applies only to the scrape client. A
  proxy can change only the query string and `Authorization` of a configured target, as documented.
- **Hardening note (not a finding):** the agent gRPC port can't be bound to one address. There is no
  `proxy.agent.host`, though common-utils 5.2.0's `server()` takes a `bindAddress`; the scrape port and the dashboard
  both have one.

### Build, CI, and packaging

- **Distroless images (#370),** checked on the published 4.4.2 images: `/etc/passwd` has `prometheus` at 1001:1001,
  `/app` is owned by it, `/tmp` is 1777, and `LANG=C.UTF-8` with its locale files present. The JRE's `cacerts` is a
  regular file, tcnative's Linux native needs only glibc, and `nsswitch.conf` is present. The OCI labels from #360 are
  there, and the index has amd64, arm64, s390x, and ppc64le. Nothing needs a shell: every probe in the charts,
  compose, and Kubernetes snippets is `httpGet` or `tcpSocket`.
- **Helm charts:** every env var the templates set exists in `EnvVars`. The probes use the named `admin` port, and
  `/ping` for the proxy's readiness. The `existingSecret` and `existingConfigMap` logic is right, and the UID 1001
  security context matches the image.
- **Release gates:** `RELEASE_VERSION_FILES` matches `docs/RELEASE.md`'s step 2, and the stale-version regex flags
  every bumpable line. `RELEASE_REQUIRED_JOBS` names the real job names, including the `image-scan` matrix. The
  `SNAPSHOT_VERSION` fix from #368 is right.
- **Workflows:** every `uses:` is SHA-pinned with a version comment, all 14 checkouts set `persist-credentials:
  false`, permissions are per job, every job has `timeout-minutes`, and every Dependabot entry has a cooldown.
- **Fat JARs:** no duplicate entries, the merged gRPC service files are intact, `NOTICE` and `LICENSE` are merged, and
  every class outside `META-INF/versions` is Java 17 bytecode.
- **Published POM:** all Netty modules resolve to 4.2.20.Final for a Maven consumer, so October #25 holds for Netty
  itself, and the POM has no test or compile-only dependencies.

### Tests and docs

- **Public API:** the only non-`internal` top-level types are the eight AGENTS.md lists, and `docs/packages.md` links
  each one.
- **CLI flags, env vars, and config keys:** every `@Parameter` flag is in `docs/cli-args.md` and the website's CLI
  reference, as are all 51 `EnvVars`. Every dotted `proxy.`/`agent.` key in the README, `llms.txt`, `SECURITY.md`,
  `docs/`, the chart READMEs, and `examples/` is in `config/config.conf`.
- **Metrics:** the names, labels, and label values in `metrics-and-grafana.md`, the website, the README, and
  `llms.txt` match the code, apart from #29.
- **Distroless:** no doc, chart, or script uses `docker exec` or a shell in the image, or names the Ubuntu base as
  current.
- **Changelogs:** the 4.4.0, 4.4.1, and 4.4.2 entries match the code and commits. The one omission is trivial: #370
  also raised the XXLARGE harness profile, which neither log mentions.
- **Test hygiene:** `assertSoftly` blocks capture outcomes as values first, no Lincheck spec uses MockK, every
  `TestPorts` constant is used, and all 15 container specs are gated on `RUN_CONTAINER_TESTS`. The October #22 check
  order, #26's announce-after-auth, the chunked sweep on cancel, the #8 streaming cap, and the connect-failure log
  levels all have tests that would fail on a regression.
