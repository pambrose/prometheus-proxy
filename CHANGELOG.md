# Changelog

All notable changes to this project are documented in this file.

---

## [Unreleased]

### Bug Fixes

- Fix the `hostName` service-discovery label, which reported the **proxy's** hostname instead of the agent's. The label is documented as "hostnames of agents serving this path" and is a reserved key the proxy computes itself (agents cannot override it), but the agent was sending the proxy endpoint it had dialed — so every discovered target carried the same value and the label identified nothing. It now reports the agent's own host. **This changes an existing target label value**: on upgrade Prometheus sees the new label and starts fresh series for affected targets, a one-time churn. The alternative was worse once proxy failover landed, since a value tracking the current endpoint would change on every failover and churn the series repeatedly

### New Features

- Add an optional read-only **operational web UI** on the proxy, answering "why isn't this target scraping?" without log-diving. Enable with `--ui` / `UI_ENABLED` / `proxy.ui.enabled`; it listens on its own port (`--ui_port`, default `8094`) and is **off by default**, matching the admin and metrics posture. The layout is master-detail: connected agents on the left, and for the selected agent its identity, registered paths, backlog, eviction countdown, and its own recent scrape results on the right
- The UI is server-rendered with the Ktor HTML DSL and updates live over a WebSocket, pushing HTML fragments rather than JSON so there is no client-side templating. Interaction is [htmx](https://htmx.org), which ships as a WebJar dependency served from the classpath — there is no CDN and no committed third-party JavaScript, so the UI works in the airgapped environments this product is frequently deployed into
- The UI is deliberately **not** on the admin port. That port is a Jetty servlet container whose only extension point is a path-to-servlet map, so Ktor routing and WebSockets cannot attach to it. A separate port is also the better posture: Kubernetes probes target `/ping` and `/healthcheck` on the admin port, so a shared port could not be firewalled without taking the probes down with it. The UI has **no authentication and no TLS** — like the admin and metrics ports — and must not be exposed publicly
- Add a `ProxyEvent` bus (`MutableSharedFlow`) so proxy topology changes are observable rather than only pollable. Agent connect/register/disconnect and path register/unregister emit events; emission is non-blocking (`tryEmit` with `DROP_OLDEST`) so it is safe on gRPC transport threads and inside the path-map monitor, and can never stall a registration. Values that drift rather than change — backlog depth, map sizes, eviction countdowns — are sampled on a ~2s timer instead, since they have no discrete moment to emit from

- Add agent-side proxy failover, so redundant proxies can be run without DNS tricks or a TCP load balancer. The agent takes an **ordered** list of proxy endpoints and connects to the first that answers: `agent.proxy.endpoints = [ "proxy-a:50051", "proxy-b:50051" ]`, or the same list comma-separated in `--proxy` / `PROXY_HOSTNAME`. A single value resolves to a one-element list and behaves exactly as before, so existing configurations are unaffected. `endpoints` is the base when non-empty and `agent.proxy.hostname` is used otherwise; env and CLI each replace the list wholesale rather than merging, and `agent.proxy.port` supplies the default port for config entries that omit one. An unparseable endpoint fails at agent startup rather than surfacing later as a connection error
- Failover semantics: a **failed connect** advances to the next endpoint, wrapping at the end, while a connection that came up and then **dropped** returns to the head of the list. The list is therefore a priority order rather than a ring — a recovered primary is picked up on the next reconnect with no health prober, timer, or manual step. Only one connection is active at a time, and the agent registers its paths on whichever proxy it is connected to, so a standby serves nothing until an agent fails over to it. Rotation reuses the existing `reconnectPauseSecs` pacing; there is no separate failover pause
- Scrape an HA pair with Prometheus `static_config`, **not** `http_sd_config`: a standby proxy returns an empty service-discovery list, which Prometheus treats as target *deletion* rather than as a failed scrape — the series would silently vanish with no `up=0` and no alert. With `static_config` the standby returns `404` for a path it does not know and records `up=0` as expected
- All failover endpoints share one TLS context and one authority override, so endpoints with different CAs or certificate SANs fail with an opaque handshake error rather than a clear configuration error. Simultaneous multi-homing (registering on several proxies at once) and proxy clustering remain out of scope
- Add optional per-path metric filtering at the agent, so unwanted metric families are dropped *before* the payload crosses the network boundary. Configured as a top-level `agent.filters` list keyed by path — each element takes `path`, `metricNameAllow`, and `metricNameDeny` (all three required; write `metricNameAllow: []` for a deny-only filter). The default is `filters: []`, so an existing config with no `filters` key loads unchanged and nothing filters unless asked. Regexes are **fully anchored** (`Regex.matches()`), matching Prometheus `relabel_config` / `metric_relabel_configs` semantics — `"go_"` matches nothing, `"go_.*"` is required to match `go_goroutines` — and `metricNameDeny` is applied after `metricNameAllow`, so deny wins on overlap. `filters` is a list, so it is config-file only with no CLI/env equivalent; an invalid regex fails agent startup rather than surfacing later on a scrape
- Filtering matches per **metric family**, not per series: a `# HELP`, `# TYPE`, or (OpenMetrics) `# UNIT` line opens a family, the allow/deny verdict is computed once against the family name, and every sample line belonging to it — the exact name, or the name plus a recognized suffix (`_bucket`, `_sum`, `_count`, `_created`, `_total`, `_gsum`, `_gcount`, `_info`) — inherits that verdict. A histogram's `_bucket` / `_sum` / `_count` series are therefore always kept or dropped together with their metadata lines, so a filter can never deliver a histogram missing some of its series. A sample line with no open family (a payload carrying no `TYPE` lines) is judged literally on its own full name. One consequence worth knowing: once a family is open, a series-level rule such as `metricNameDeny: [ "http_req_duration_seconds_bucket" ]` is a silent no-op, since the bucket lines inherit the family's verdict rather than being matched themselves
- Filtering runs between the scrape and the gzip decision, so gzip (`minGzipSizeBytes`) and chunking (`chunkContentSizeKbs`) both see the already-reduced payload and the bandwidth saving composes with them. `maxContentLengthMBytes` is the deliberate exception — it still guards the **raw** response, so filtering can never be used to slip a payload past that memory bound
- Filtering **fails open**: a non-text `Content-Type` (protobuf, an HTML error body) or a body that is not valid UTF-8 passes through unfiltered and byte-exact rather than risk corrupting it, each logging a warning once per path. The worst case is bandwidth not saved, never a corrupted payload
- Filters are matched by path regardless of how the path was registered, so they cover both static `agent.pathConfigs` entries and paths added at runtime by dynamic target discovery
- Add two agent counters, `agent_filter_lines_dropped` and `agent_filter_bytes_saved`, both labeled by `launch_id` and `path`, so operators can see what each filter is actually removing. Series are only created for paths that have a filter configured, so cardinality is bounded by the number of filters rather than the number of registered paths

### Behavior Changes

- **`proxy_scrape_requests{type}` and the latency histogram's `outcome` label gained `upstream_timed_out`.** An agent-reported scrape timeout (408/504 — the agent answered promptly to say its *own* fetch of the target exceeded `agent.scrapeTimeoutSecs`) was previously labeled `timed_out`, the same value the proxy emits when the agent never answers at all within `proxy.internal.scrapeRequestTimeoutSecs`. The two have opposite remediations, and because the agent's 15s default trips well before the proxy's 90s, the agent-side leg was the dominant contributor to the merged series. **Dashboards and alerts matching `type="timed_out"` will stop counting agent-side scrape timeouts** — use `type=~"timed_out|upstream_timed_out"` to match both. This completes the label taxonomy started in 3.2.0, which split `upstream_error` / `content_too_large` out of `path_not_found`

- **`agent.internal.heartbeatCheckPauseMillis` is now validated (`> 0`) at startup.** It is the poll interval of the agent's keepalive loop, and `delay()` returns immediately for a non-positive duration, so `0` spun that loop without ever suspending or reaching a cancellation check — pinning an IO thread for the connection's lifetime. A non-positive value now fails fast with a clear message instead of silently hot-spinning
- **`HttpClientCache` no longer swallows JVM `Error`s when closing an HTTP client.** `closeQuietly` caught `Throwable`, downgrading an `OutOfMemoryError` to a warning; it now re-throws `Error` so the agent terminates rather than running in a corrupted state, matching the policy already applied in `AgentHttpService`, `Agent.handleConnectionFailure`, and `AgentGrpcService.connectAgent`. The cache's sweeper scope gained a `CoroutineExceptionHandler` so that if an `Error` does kill the sweeper, it is logged loudly rather than dying silently

### Bug Fixes

- Fix a registration race that could strand a scrape path pointing at a dead agent. `Proxy.removeAgentContext` swept the path map *before* invalidating the agent context, so a `registerPath` blocked on the path-map monitor during the sweep was released into an unguarded window — the in-lock validity re-check read a flag the remover had not set yet, and the sweep had already run. The context is now invalidated first, so a registration racing teardown is either rejected by the re-check or undone by the sweep. Symptom was a permanently inflated `proxy_scrape_requests{type="agent_disconnected"}` plus a retained dead `AgentContext`; scrape results themselves stayed correct
- Guard the background `HttpClientCache` sweeper against a throwing `HttpClient.close()`. The 3.2.0-era fix was correct but untested at the call site, so nothing pinned it against regression

### Documentation

- Correct the `proxy_scrape_requests` type-label tables in `docs/metrics-and-grafana.md` and the Monitoring page, which still described `path_not_found` as "Agent returned a non-200 status for the target" and omitted `upstream_error` and `content_too_large` entirely. Both tables now document the full label set and the proxy-side/agent-side pairs operators have to tell apart (`timed_out` / `upstream_timed_out`, `payload_too_large` / `content_too_large`)
- Rework the Troubleshooting timeout section around which timeout actually fired, and widen the `ProxyPayloadTooLarge` example alert to match `content_too_large` as well — its summary claimed to cover the size limit but only ever matched the proxy-side half

---

## [3.2.0] - 2026-06-13

### New Features

- Add an optional pre-shared agent token for authenticating agent gRPC connections: `--agent_token` / `AGENT_TOKEN` (config `proxy.agentToken` on the proxy, `agent.agentToken` on the agent). The agent sends the token as an `agent-token` gRPC metadata header; the proxy's `AgentTokenServerInterceptor` rejects any RPC with a missing or mismatched token (`Status.UNAUTHENTICATED`, constant-time comparison of equal-length SHA-256 digests so the token's length can't leak via timing). Empty (the default) preserves today's open behavior and logs a startup warning unless mutual TLS is configured. Resolved CLI > env > config; the value is never logged
- Add a per-CA HTTPS trust store for the agent's scrape client: `--https_truststore` / `--https_truststore_password` (env `HTTPS_TRUST_STORE_PATH` / `HTTPS_TRUST_STORE_PASSWORD`; config `agent.http.trustStorePath` / `agent.http.trustStorePassword`) verify HTTPS targets against a custom/private CA without disabling validation. Resolved CLI > env > config; password never logged; `--trust_all_x509` takes precedence; an empty path uses the JDK default trust store
- Add `ContainersSmokeTest` (`io.prometheus.containers`) — Testcontainers-based end-to-end smoke test that builds the proxy/agent Docker images and verifies a Prometheus → proxy → agent → endpoint scrape, gated on `RUN_CONTAINER_TESTS=true`
- Expand the container-test suite (`io.prometheus.containers`) beyond the smoke test with seven specs over real Netty/Docker — `ContainersProxyHttpTest` (404s, upstream-status passthrough, admin endpoints, proxy/agent `/metrics`, service discovery), `ContainersAgentTokenAuthTest` (token match + mismatch), `ContainersConsolidatedTest` (two-agent merge), `ContainersLargePayloadTest` (chunk + gzip reassembly), `ContainersReconnectTest` (agent reconnect after proxy replacement), `ContainersTlsTest` (server-only + mutual gRPC TLS), and `ContainersHttpsTargetTest` (trust-all positive/negative) — all gated on `RUN_CONTAINER_TESTS=true` and backed by a shared `support/ContainerTestSupport.kt`
- Add `ContainersScalingTest` — a parameter-driven Testcontainers spec that scales the system along its real load axes (agents × endpoints per agent, series per endpoint, consolidated fan-out, and scrape concurrency), verifies every path is scrapable, and asserts the proxy's `proxy_agent_map_size` / `proxy_path_map_size` gauges match the expected counts; tunable via `SCALE_*` env vars without recompiling
- Add `make container-tests` target with Docker context auto-detection so Testcontainers finds Docker Desktop's non-default socket on macOS
- Add `.github/workflows/container-tests.yml` to run the smoke test on push to master, on `workflow_dispatch`, and on PRs touching packaging files
- Add `make help` target listing every Make target with an auto-extracted description

### Security

- Mitigate the unauthenticated agent-registration / path-hijacking finding (`docs/security-agent-authentication.md`, item 1) with the optional pre-shared agent token above. Mutual TLS and network segmentation remain the recommended production posture; the token is a lightweight app-level control that complements them
- Prevent agent-supplied service-discovery labels from overriding the proxy-computed reserved keys (`__metrics_path__`, `agentName`, `hostName`): a colliding label key is now skipped with a warning, so a malicious or misconfigured agent can't redirect Prometheus to a different scrape target or spoof another agent's identity through a label name
- Redact query-parameter *values* (not just the `user:pass@` userinfo) everywhere a scrape URL is logged or echoed back to Prometheus, so secrets in `?token=…` / `?api_key=…` no longer leak at WARN
- Derive the agent `HttpClientCache` key from a per-process salted HMAC-SHA256 digest instead of the plaintext `username:password`, keeping the password out of the long-lived cache key
- Bound the agent's scrape response-body read: `buildScrapeResults()` previously called `bodyAsText()`, buffering the whole body into the heap *before* the size check, so a target with no `Content-Length` (chunked transfer) or an understated one could push the agent toward OOM regardless of `maxContentLengthMBytes`. It now reads at most `maxContentLength + 1` bytes via `bodyAsChannel().readRemaining()`, so the guard runs against a bounded buffer

### Behavior Changes

- Embedded agents (`startAsyncAgent`, `exitOnMissingConfig=false`) now throw the new public `io.prometheus.common.ConfigLoadException` on a config-load failure instead of calling `exitProcess(1)`; standalone agents and the proxy still exit
- `connectAgent()` rethrows JVM `Error`s instead of swallowing them into retry-forever, and logs full stack traces on connection failures
- `ProxyOptions` now validates `proxyPort` / `proxyAgentPort` (`1..65535`), gRPC timeouts (`-1` or `> 0`), `internal.scrapeRequestTimeoutSecs` / `staleAgentCheckPauseSecs` / `maxAgentInactivitySecs` (`> 0`), and `internal.maxUnzippedContentSizeMBytes` (`>= 0`, so `0` stays a valid "reject all" limit) at startup
- `BaseOptions` now validates `adminPort` / `metricsPort` (`1..65535`) and `AgentOptions` validates `scrapeTimeoutSecs` (`> 0`) at startup, matching the existing port/timeout checks — an out-of-range admin/metrics port or a non-positive scrape timeout (which made `withTimeout()` cancel every scrape immediately) now fails fast with a clear message instead of an opaque bind error
- Per-request call logging emits at DEBUG instead of INFO when `requestLoggingEnabled = true` (config default unchanged)
- `HttpClientCache.close()` sets a terminal flag so a scrape racing shutdown can't create and cache a fresh client into the closed cache
- Removed the deprecated `all` log level. `ALL` is vestigial from log4j 1.x; in logback the `Level` class is final and all levels are enabled by setting a logger to `TRACE`, so `Level.ALL` is marked deprecated. `logLevel = "all"` now fails fast at startup — use `"trace"` for the most verbose output

### Observability

- Add an `outcome` label to `proxy_scrape_request_latency_seconds` and record latency for the timeout and agent-disconnected paths (previously unrecorded)
- Label `proxy_start_time_seconds` with a per-process `launch_id`
- Count a scrape result dropped on connection-close as `agent_scrape_result_count{type="dropped"}`
- Log a malformed scrape-target `Content-Type` at WARN (was DEBUG)

### Bug Fixes

- Fix `DnsNameResolverProvider` and `PickFirstLoadBalancerProvider` missing from the shaded `agentJar`/`proxyJar` (shadow 9.4.2 silently drops same-named `META-INF/services` entries when both grpc-core and grpc-netty-shaded contribute one). Without the DNS provider, gRPC defaulted to the `unix` scheme on any non-IP hostname. Static service files under `src/shadow/resources/` re-register both providers
- Fix flaky `ProxyHttpRoutesTest` test that occasionally hit `Connection reset` — the existing TCP-connect probe only confirmed kernel-level SYN/ACK; replaced with an HTTP-level readiness probe that retries on `IOException`/`ClosedByteChannelException`
- Fix `appendQueryParams` URL-decoding the encoded query blob before concatenation (an encoded `&` / `#` inside a value could expand into extra params or a fragment); it now appends the already-encoded string verbatim
- Fix `writeResponsesToProxy` letting a per-response processing error block the HTTP handler until `scrapeRequestTimeoutSecs` (slow, misleading 503); it now fails that scrape request immediately
- Fix embedded `Agent.stop()` / `EmbeddedAgentInfo.shutdown()` spawning a zombie reconnect thread: it called the Guava `shutDown()` hook directly, tearing down the gRPC channel, servlets, and metrics without flipping Guava's `isRunning` flag, so the `run()` loop kept waiting `reconnectPauseSecs` and rebuilding a fresh channel to re-register with the proxy forever (also violating `shutdown()`'s "blocks until terminated" contract). It now routes through `stopSync()` — the service transitions to STOPPING so the loop exits, the cleanup hook runs exactly once, and the call blocks until TERMINATED. Standalone and harness shutdown paths are unchanged
- Fix `ProxyPathManager` advertising multi-segment paths it could never scrape: a registered `path` containing an embedded `/` (e.g. `app/metrics`) appeared in service discovery but 404'd at scrape time because the scrape route (`get("/*")`) matches exactly one segment. Such a path is now rejected at registration — the agent receives `valid = false` with a clear reason rather than silently serving a dead target (and without triggering a reconnect loop)

### Code Quality

- Code-review cleanup with no behavior change: extracted shared common-option assignment between `AgentOptions` / `ProxyOptions`, decomposed `Agent.run()`'s connection tasks behind one helper, collapsed the duplicated `ConfigWrappers` overloads, modeled basic-auth credentials as a `Credentials` value object, split the chunk-size field into a KB input plus derived bytes, replaced the stringly-typed path config with a typed `PathConfig`, and unified gRPC-default log formatting behind one helper
- Added a mutual-TLS rejection test and coverage for timeout-override resolution, the unknown-`scrapeId` header drop, wrapped-timeout detection, the chunk-size boundary, `AgentHttpService` `PayloadTooLarge` branches, `SslSettings` success paths, and `BaseOptions` URL/HTTP config loading; encode the gzipped response body once (was twice); removed the dead `SslSettings` scaffolding (now wired into the HTTPS trust store) and stale commented-out blocks
- Covered the previously-untested `EnvVars.getEnv(Int)` / `getEnv(Long)` invalid-value error paths: extracted `parseIntStrict` / `parseLongStrict` companion helpers (mirroring the existing `parseBooleanStrict`) so the parse-and-throw branches are testable without setting process env vars, and dropped a redundant default-fallback test
- Made `ProxyPathManager`'s `AgentContextInfo.agentContexts` an immutable `List` (mutations now replace the `pathMap` entry with a `copy()` rather than mutating a shared list), dropping the now-redundant defensive `.toList()` copies in the read accessors
- Further agent-layer and test tidy with no behavior change: dropped `Agent.startTimer`'s redundant self-passed parameter, simplified `AgentHttpService.isTimeoutException`, and folded the three writer coroutines' identical failure handling in `writeResponsesToProxyUntilDisconnected` behind one helper; extracted `ProxyHttpRoutes.mergeResponseResults` as a unit-testable seam (consolidated status / content-type selection) and added an in-process agent disconnect → reconnect → re-register regression test; replaced the fixed `Thread.sleep` in the agent-eviction tests with `eventually` and made the `HttpClientCache` concurrency test genuinely contend (multi-threaded dispatcher + start latch)

### Build & Tooling

- Remove the dead config keys `proxy.http.maxThreads` / `proxy.http.minThreads` (orphaned since the 1.4.0 Ktor-server migration removed the `threadPool(...)` call) and `proxy.internal.scrapeRequestCheckMillis` (orphaned since 3.0.0 replaced the timeout polling loop with `awaitCompleted`). No production code read them, so setting them had no effect; the keys and their regenerated `ConfigVals` fields are gone
- Annotate the unimplemented `config.conf` knobs `metrics.grpc.metricsEnabled`, `metrics.grpc.allMetricsReported`, and `internal.zipkin.grpcReportingEnabled` as `(not yet implemented)` so operators aren't misled — no production code reads them; they're left in place (with the note) to avoid regenerating `ConfigVals`
- Replace the `-PreleaseDate` / `-PbuildTime` Gradle property overrides with `ValueSource`-backed providers so `BuildConfig.APP_RELEASE_DATE` and `BuildConfig.BUILD_TIME` are read fresh on each build instead of being frozen by the configuration cache. The override flags are removed; release artifacts are no longer byte-for-byte reproducible
- Move detekt configuration from `etc/detekt/` to `config/detekt/` (standard detekt convention)
- Add `detekt` to the `lint` Makefile target so `make lint` runs `lintKotlinMain`, `lintKotlinTest`, and `detekt`
- Add `detekt-baseline` Makefile target wrapping `./gradlew detektBaseline`
- DRY the agent/proxy ShadowJar tasks behind a `configureFatJar(archiveName, mainClass)` helper; use `configurations.add(runtimeClasspath)` so the configuration stays a provider
- Refuse `make docker-push` when `VERSION` matches `*SNAPSHOT*` / `*-rc*` / `*-beta*` / `*-alpha*`
- Fail-fast `$(error)` guards on `VERSION` and `GRADLE_VERSION` if either can't be parsed
- Add `TSCFG_VERSION` variable so the tscfg jar version isn't duplicated inline
- Centralize `PLATFORMS`, `IMAGE_PREFIX`, `TSCFG_VERSION` at the top of the Makefile
- Standardize on `$(VAR)` form everywhere in the Makefile
- Replace `distro: build $(MAKE) jars` with `distro: build jars` (drop the recursive sub-make)
- Externalize the inline coverage-packages python to `scripts/coverage_packages.py`
- Annotate every Make target with `## descriptions` and add `make help`
- Add a `make all-tests` target that runs the full suite (`tests` + `container-tests`)
- Add `make scaling-tests` (forwards `SCALE_*` and `TEST_MAX_HEAP_SIZE`) plus six curated scaling presets — `scaling-paths`, `scaling-agents`, `scaling-payload`, `scaling-consolidated`, `scaling-concurrency`, `scaling-soak` — and an `all-scaling` aggregate; these are dev/stress aids, not run by `all-tests` or CI
- Honor `-PtestMaxHeapSize` / `TEST_MAX_HEAP_SIZE` to override the load-based forked-test JVM heap size for large scaling runs
- Add `make regen-certs` to rebuild the `testing/certs` TLS fixtures (CA + server + client) at 2048-bit with 100-year validity, preserving the `*.test.google.fr` SAN the harness relies on; the committed fixtures were regenerated and the container TLS/HTTPS specs mount them (no runtime openssl)
- Centralize scattered test port literals into a new `io.prometheus.common.TestPorts` object in the test source set, so unit/harness/container specs reference named constants and unit tests no longer pull in the Testcontainers support harness just for a port value
- Document the double `./gradlew wrapper` invocation in `upgrade-wrapper`
- Add missing `.PHONY` entries for `mini-tests` and the `coverage-*` family
- Move `ContainersSmokeTest` from `io.prometheus.harness` to a dedicated `io.prometheus.containers` package
- Switch the proxy/agent Docker images to the prebuilt `eclipse-temurin:25-jre` (Java 25 LTS) base instead of `alpine` + `apk add openjdk17-jre`, so builds are faster and not subject to Alpine-mirror stalls. The fat JAR remains Java 17 bytecode and runs unchanged on the newer JRE. The Ubuntu-based Temurin image publishes amd64, arm64, s390x, and ppc64le manifests, so the full multi-arch `make docker-push` platform set resolves and the images run on Apple Silicon (Alpine JRE images cover only amd64/arm64)
- Pin the `eclipse-temurin:25-jre` base image by its manifest-list digest in `etc/docker/{agent,proxy}.df` for reproducible builds (the tag is kept inline for readability); drop the no-op `-XX:+UnlockExperimentalVMOptions` / `-XX:+UseG1GC` ENTRYPOINT flags (G1 is the JDK 25 default GC and the remaining flags are non-experimental); and add `--enable-native-access=ALL-UNNAMED` and `--sun-misc-unsafe-memory-access=allow` to suppress the JDK 25 startup warnings from jansi's `System.load` and netty's deprecated `sun.misc.Unsafe` memory calls
- Pin the documentation toolchain (`zensical==0.0.45`, `mkdocs-material==9.7.6`) in `.github/workflows/docs.yml` for reproducible doc builds
- Harden the nginx reverse-proxy example image (`nginx/docker/Dockerfile`): switch the base from `nginx` (Debian) to `nginx:1.29-alpine` to clear a Snyk OS-package CVE while keeping the gRPC module, and add `RUN apk upgrade --no-cache` to pull patched Alpine package revisions (libxml2, xz-libs, libssl3/libcrypto3) at build time
- Run tests in CI and upload kover coverage to Codecov on each push and pull request
- Add `detekt` to the CI lint step (`./gradlew lintKotlinMain lintKotlinTest detekt`) so a detekt regression fails the build, mirroring `make lint` (detekt was a configured quality gate that no CI workflow actually invoked)
- Scope `netty-tcnative` and `jul-to-slf4j` as `runtimeOnly` (no compile-time references; still bundled in the fat JARs via `runtimeClasspath`)
- Drop the unused `kotlinx-datetime` dependency (catalog entry, library, and a commented-out usage), removing a transitive dependency the code never referenced

### Documentation

- Add a Kubernetes deployment guide (`website/prometheus-proxy/docs/kubernetes.md`) under a new top-level **Deployment** nav section that also hosts the existing Docker page. Covers the cluster topology (the agent runs inside each firewalled cluster and dials *out* to a centrally-reachable proxy that Prometheus scrapes), `Deployment` / `Service` / `ConfigMap` manifests for the proxy and agent, the standalone-Deployment and sidecar agent patterns, exposing the gRPC port to remote agents (`LoadBalancer` / HTTP/2 ingress), Prometheus integration via both a plain `scrape_config` and a Prometheus Operator `ServiceMonitor`, mounting TLS certs from a `Secret`, and wiring liveness/readiness probes to the `/ping` and `/healthcheck` admin endpoints. The manifests live in `src/test/kotlin/website/KubernetesExamples.txt` and are pulled into the page as `check_paths`-validated snippets
- Add a **Troubleshooting** page (new **Operations** nav section) — a symptom → cause → fix guide for the failures the system actually emits: the `unix`-scheme gRPC DNS error, `404` (path / `metrics_path` mismatch, multi-segment-path rejection), `503` (`no_agents` / `agent_disconnected` / `proxy_not_running`), `413 Payload Too Large`, scrape `timed_out`, `UNAUTHENTICATED: Missing or invalid agent token`, consolidated-mode registration rejections, and TLS handshake failures — each tied to the real log string and `proxy_scrape_requests` `type` label
- Add a **Running in Production** page (Operations) consolidating the security, high-availability (consolidated-mode redundancy vs. single-instance proxy), sizing/tuning, logging, and shutdown guidance scattered across the advanced/security/Kubernetes pages, ending in a pre-flight checklist
- Add a **Grafana & Alerting** page (Features) with import instructions and raw URLs for the shipped `grafana/prometheus-proxy.json` / `grafana/prometheus-agents.json` dashboards, plus ready-to-use Prometheus alerting rules (success rate, P99 latency, no agents connected, backlog growth, payload-too-large, eviction churn, agent connect failures) built on the documented metrics
- Add an **Example Configs** page (Configuration) walking through `examples/{simple,myapps,federate,tls-no-mutual-auth,tls-with-mutual-auth}.conf`, each imported as a whole-file snippet so the page can't drift from the committed configs
- Add a **Glossary** page (Overview) defining the core terms (proxy, agent, path / pathConfig, consolidated mode, chunking, heartbeat, service discovery, stale-agent cleanup, embedded agent, transport filter, `launch_id`), cross-linked to the relevant pages

### Dependency Updates

| Dependency             | Old           | New              |
|------------------------|---------------|------------------|
| Kotlin                 | 2.3.21        | 2.4.0            |
| Gradle wrapper         | 9.5.0         | 9.5.1            |
| Ktor                   | 3.4.3         | 3.5.0            |
| gRPC                   | 1.80.0        | 1.82.0           |
| Shadow plugin          | 8.3.7         | 9.4.2            |
| detekt                 | 1.23.8        | 2.0.0-alpha.3    |
| Typesafe Config        | 1.4.6         | 1.4.9            |
| BuildConfig plugin     | 6.0.9         | 6.0.10           |
| common-utils           | 2.8.1         | 2.9.1            |
| gradle-plugins         | 1.0.14        | 1.0.15           |
| Logback                | 1.5.32        | 1.5.34           |
| SLF4J                  | 2.0.17        | 2.0.18           |
| kotlin-logging         | 8.0.01        | 8.0.4            |
| Dropwizard metrics     | 4.2.38        | 4.2.39           |
| MockK                  | 1.14.9        | 1.14.11          |
| Testcontainers         | —             | 2.0.5            |

Pin `protobuf`/`protoc` (4.34.1 → 3.25.3) and `netty-tcnative` (2.0.77.Final → 2.0.75.Final) **down** to
the versions the gRPC 1.82.0 artifacts expect, keeping the generated stubs and native TLS binary-compatible
with grpc-netty-shaded.

---

## [3.1.1] - 2026-04-30

### New Features

- Add `-PoverrideReleaseDate` and `-PoverrideBuildTime` Gradle properties for reproducible builds (`BuildConfig.APP_RELEASE_DATE` and `BuildConfig.BUILD_TIME` now accept overrides instead of always reading the local clock)

### Bug Fixes

- Fix flaky `AgentTest` "Bug #1" coroutine backpressure test by replacing the timing-based probe with a deterministic
  `CompletableDeferred` gate plus Kotest `eventually()` for scheduler jitter
- Fix flaky `AgentHttpServiceTest` by replacing a fixed 100 ms post-`server.start` sleep with an active TCP-connect
  readiness probe (20 ms poll, 5 s deadline)

### Build & Tooling

- Centralize repository declarations in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)`; opt into `mavenLocal()` with `-PuseMavenLocal=true`
- Replace the `agentJar` / `proxyJar` zipTree-rewrap pattern with two `ShadowJar` tasks for configuration-cache safety on Gradle 9.x and one fewer redundant fat jar
- Drop the redundant `java` plugin (already applied transitively by `kotlin.jvm`)
- Use `tasks.named("generateProto")` instead of the `:generateProto` path string
- Mark internal `Utils` object as `internal` so it no longer leaks into the public API surface
- Hoist `formatter`, `releaseDate`, and `buildTime` out of the `buildConfig {}` block to top-level `val`s for reuse
  elsewhere in the script
- Centralize test server readiness in a shared `startServerAndGetPort` helper
- Add `check-gpg-env` Makefile target for GPG signing validation
- Fix the date format passed by the `build` and `local-build` Makefile targets to match the `MM/dd/yyyy` pattern parsed
  by `build.gradle.kts`
- Add Claude Code GitHub workflow

### Documentation

- Add KDoc to every `@Parameter` field on `BaseOptions`, `AgentOptions`, and `ProxyOptions`, every value of the `EnvVars` enum, the `Agent` and `Proxy` companion entry points, and `EmbeddedAgentInfo` — the supported public API now has full Dokka coverage with resolution-precedence, sentinel-value, and validation notes
- Trim `docs/packages.md` to advertise only the genuinely-public surface (`Agent`, `Proxy`, `AgentOptions`, `ProxyOptions`, `BaseOptions`, `EnvVars`, `EmbeddedAgentInfo`); remove dangling cross-references to internal types
- Refresh website docs and metrics-and-grafana reference

### Dependency Updates

| Dependency     | Old          | New          |
|----------------|--------------|--------------|
| Kotlin         | 2.3.20       | 2.3.21       |
| Gradle wrapper | 9.4.1        | 9.5.0        |
| Ktor           | 3.4.2        | 3.4.3        |
| serialization  | 1.10.0       | 1.11.0       |
| tcnative       | 2.0.74.Final | 2.0.77.Final |
| utils          | 2.7.1        | 2.8.1        |
| gradle-plugins | 1.0.12       | 1.0.14       |
| protobuf       | 0.9.6        | 0.10.0       |
| taskinfo       | 3.0.1        | 3.0.2        |

---

## [3.1.0] - 2026-04-04

### Breaking Changes

- **Maven coordinates changed**: Published to Maven Central as `com.pambrose:prometheus-proxy`
- JitPack is no longer used; all dependencies resolve from Maven Central

### New Features

- Add Zensical documentation site with comprehensive guides, code examples, and architecture diagrams
- Publish documentation to GitHub Pages via CI

### Build & Tooling

- Migrate publishing from JitPack to Maven Central using vanniktech maven-publish plugin
- Replace manual `maven-publish` + sources/javadoc JAR tasks with `mavenPublishing` DSL
- Remove JitPack plugin resolution strategy from `settings.gradle.kts`
- Remove `jitpack.yml`
- Add GPG signing for Maven Central (skipped when no key is provided)
- Add `google()` repository to build script
- Add `overrideVersion` property support for snapshot publishing
- Import `VisibilityModifier` directly instead of using fully qualified name in Dokka config

### Documentation

- Add full Zensical documentation site in `website/prometheus-proxy/` with 13 pages covering architecture, getting started, configuration, security/TLS, Docker, embedded agent, service discovery, monitoring, CLI reference, and advanced topics
- Add code example snippets in `src/test/kotlin/website/*.txt` imported via pymdownx.snippets
- Extract Java/Kotlin code examples into compilable source files (`EmbeddedAgentJavaExample.java`, `EmbeddedAgentKotlinExample.kt`) so API changes are caught by the compiler
- Add mkdocs-material dependency to CI workflow and Makefile for material theme support
- Add KDocs nav entry with `material/book-open-page-variant` icon linking to Dokka API docs
- Add API Reference section and grid card layouts for Next Steps on index, getting-started, and configuration pages
- Add markdown extensions: `admonition`, `pymdownx.details`, `attr_list`, `md_in_html`, `pymdownx.emoji` with material icon support
- Update `zensical.toml`: dark mode default, navigation tabs, snippet base paths, Mermaid support, GitHub social link
- Fix KDocs nav redirect 404 by renaming `kdocs.md` to `api.md` to avoid path conflict with Dokka output
- Fix GitHub Actions docs workflow to use correct `working-directory` and artifact path
- Update `README.md` with Maven Central badge, documentation site link, and dependency coordinates
- Update `CLAUDE.md` with documentation site and publishing sections
- Update `llms.txt` with Maven Central link and documentation site reference

### Dependencies

- Bump utils to 2.7.1
- Bump Kotest to 6.1.10, Ktor to 3.4.2, Logback to 1.5.32
- Bump gradle-plugins to 1.0.12, Protoc to 4.34.1, Dropwizard to 4.2.38
- Bump Dokka to 2.2.0, maven-publish plugin to 0.36.0, Kover to 0.9.8

### Metrics & Observability

- Add new proxy metrics: `proxy_chunk_validation_failures_total`, `proxy_chunked_transfers_abandoned_total`, `proxy_agent_displacement_total`, `proxy_scrape_response_bytes`
- Convert proxy and agent latency metrics from summaries to histograms
- Add new agent metrics: `agent_client_cache_size`, `agent_scrape_backlog_size`
- Add `path` and `encoding` labels to proxy response metrics
- Rebuild Grafana dashboards for new metric schema
- Add `docs/metrics-and-grafana.md` with complete metrics reference and PromQL examples

### Bug Fixes

- Fix flaky `HttpClientCacheTest` by ensuring deterministic LRU eviction order
- Fix scrape response bytes metric to observe correct unzipped size

### Misc

- Use portable bash shebang (`#!/usr/bin/env bash`) in `bin/` scripts
- Extract Docker image version from `build.gradle.kts` in `bin/` scripts
- Remove `.superset` config files
- Remove legacy files and clean up `.gitignore`

---

## [3.0.3] - 2026-03-18

### Dependencies

- Upgrade Kotlin to 2.3.20, Gradle wrapper to 9.4.0, gRPC to 1.80.0, Kotest to 6.1.7, Protoc to 4.34.0

### Build & Tooling

- Extract JitPack URLs into reusable Makefile variables
- Enable Gradle configuration caching and daemon
- Use `forEach` instead of `map` in coroutine launches for clarity in tests

### Documentation

- Remove outdated GEMINI.md, AGENTS.md, and OpenSpec instructions
- Clean up CLAUDE.md

---

## [3.0.1] - 2026-02-28

### Build & Tooling

- Add homepage link to plugins configuration in build.gradle.kts
- Update dependency management and plugin versions
- Update .gitignore to include test configuration files

### Documentation

- Add GitHub workflow commands and API documentation section to README

---

## [3.0.0] - 2026-02-15

### Bug Fixes

- Fix integer overflow in `ChunkedContext.totalByteCount` (Int -> Long)
- Fix chunk checksum calculation to use actual byte count instead of full buffer size
- Fix `toScrapeResponseHeader` to propagate actual `srZipped` value (was hardcoded to `true`)
- Fix `IOException` error code from `NotFound` (404) to `ServiceUnavailable` (503)
- Fix catch-all HTTP exception handler from `NotFound` (404) to `InternalServerError` (500)
- Fix OpenMetrics `# EOF` marker handling in consolidated responses
- Fix `parseHostPort` to strip brackets from IPv6 addresses
- Fix TOCTOU race in `AgentContextCleanupService`
- Fix negative `scrapeRequestBacklogSize` with atomic CAS-loop decrement
- Fix `ConcurrentModificationException` in `ProxyPathManager.removePathsForAgentId`
- Fix `HttpClientCache.close()` deadlock
- Fix idle HTTP clients now closed on eviction (previously only marked for close)
- Fix `AgentHttpService` now properly closed during agent shutdown (resource leak)
- Fix path registration concurrency by moving gRPC calls outside the mutex
- Fix `AgentClientInterceptor` to use the `next` channel parameter
- Fix synchronized `agentId` assignment in `AgentClientInterceptor`
- Fix `ScrapeRequestWrapper.markComplete()` is now idempotent via `AtomicBoolean.compareAndSet`
- Fix `runCatching` replaced with `runCatchingCancellable` to avoid swallowing `CancellationException`
- Fix orphaned `ChunkedContext` cleanup on stream failure
- Fix credential leak in `HttpClientCache` logs
- Fix password `CharArray` zeroed after use in `SslSettings.getKeyStore`
- Fix `FileInputStream` resource leak in `SslSettings`
- Fix URL sanitization in agent logs to strip credentials before logging

### New Features

- Content size limits to prevent zip bombs and unbounded memory
- Unary RPC deadline (`agent.grpc.unaryDeadlineSecs`, default 30s)
- Graceful scrape request failure on agent disconnect
- Consolidated/non-consolidated mismatch rejection
- Authorization header TLS warning
- Bounded scrape request channel with configurable backpressure
- Outer scrape timeout as safety net beyond Ktor client timeout
- Strict env var parsing for booleans and integers
- Input validation for `parseHostPort` and `parsePort`
- TLS config validation

### Refactoring

- `ScrapeResults` fields changed from `var` to `val` (fully immutable)
- `ResponseResults` and `ScrapeRequestResponse` converted to immutable data classes
- `ProxyUtils` response functions now return values instead of mutating
- `AgentContextManager` and `ScrapeRequestManager` maps made private
- gRPC metadata constants consolidated into `GrpcConstants`
- Config file moved: `etc/config/config.conf` -> `config/config.conf`
- Scrape request queue changed from `Channel` to `ConcurrentLinkedQueue` with notifier
- Scrape request polling loop replaced with event-driven `awaitCompleted()` suspension

### Dependencies

- Kotlin 2.2.20 -> 2.3.10, Gradle 8.x -> 9.2.0
- Ktor 3.2.3 -> 3.4.0, gRPC 1.75.0 -> 1.79.0, JCommander 2.0 -> 3.0
- Kotest 6.0.3 -> 6.1.3, Logback 1.5.18 -> 1.5.31
- Add MockK 1.14.9 for mocking support

### CI/CD

- Add GitHub Actions CI workflow
- Add GitHub Actions workflow for Dokka API documentation
- Remove Travis CI configuration

### Testing

- ~26,000+ lines of new unit tests added
- Tests reorganized into agent, proxy, common, and misc packages
- Add MockK for mocking support

### Breaking Changes

- Default scrape failure status changed from 404 to 503
- IOException scrape error changed from 404 to 503
- New HTTP status codes: 413 (zip bomb), 502 (invalid gzip/chunk), 503 (agent disconnected)
- Content size limits enforced by default (10 MB)
- Unary RPC deadline enforced (30s)
- Retry policy: only 5xx (previously retried on any non-success except 404)
- Strict boolean env vars (`"true"`/`"false"` only)
- JCommander 2.0 -> 3.0
- Kotlin 2.3.10 / Gradle 9.2.0 required

---

## [2.4.0] - 2025-09-10

### Dependencies

- Refactor dependency management in build.gradle.kts and libs.versions.toml
- Update gRPC, Jetty, Kotest, Dropwizard, and tcnative versions
- Upgrade Kotlin to 2.2.20

---

## [2.3.0] - 2025-08-13

### Misc

- Fix formatting and capitalization in README.md
- Dependency updates

---

## [2.2.0] - 2025-06-25

### Misc

- Dependency updates
- Project cleanup

---

## [2.1.0] - 2025-03-22

### Improvements

- Refactor coroutine dispatchers to use IO for better performance
- Refactor coroutine exception handling for improved safety
- Refactor atomic operations for improved clarity

### Dependencies

- Upgrade Kotlin to 2.1.20

---

## [2.0.0] - 2025-02-14

### New Features

- Add gRPC keepalive support

---

## [1.23.2] - 2025-02-10

### Dependencies

- Update jars

---

## [1.23.1] - 2024-12-09

### Bug Fixes

- Catch and report exceptions on labels JSON deserialization
- Fix platform type issue
- Default Plain content types to charset UTF-8
- Add charset to JSON values

### Improvements

- Add logging to `--debug` option
- Replace explicit size check with `.isNotEmpty()` call

### Dependencies

- Update Ktor and logging jars

---

## [1.23.0] - 2024-11-29

### New Features

- Add support for gRPC reflection
- Add support for service discovery labels in pathConfigs
- Remove krotodc library

### Build

- Convert build.gradle to build.gradle.kts
- Change minimum JDK from 17 to 11

### Dependencies

- Upgrade to Kotlin 2.1.0, Ktor 3.0.1, gRPC 1.68.2

---

## [1.22.0] - 2024-06-11

### Misc

- Misc refactoring
- Fix detekt complaints
- Update Copyright date

### Dependencies

- Update logging and Kotlin jars

---

## [1.21.0] - 2024-01-06

### Dependencies

- Upgrade to Kotlin 1.9.22

---

## [1.20.0] - 2023-12-11

### Improvements

- Refactor configuration handling in Agent and Proxy classes

### Dependencies

- Update Kotlin and gRPC versions
- Update Kotlinter, ktor-client, ktor-server, and other dependencies
- Update Gradle wrapper

---

## [1.19.0] - 2023-11-02

### Dependencies

- Upgrade to Kotlin 1.9.20
- Upgrade to Kotlinter 4.0.0
- Update jars

---

## [1.18.0] - 2023-07-19

### Improvements

- Convert try/catch statements to `runCatching`
- Suppress warnings on functions intended for embedded client usage

### Dependencies

- Kotlin 1.9.0, Ktor 2.3.1, gRPC 1.56.1, Dropwizard 4.2.19
- Gradle 8.2.1

---

## [1.17.0] - 2023-05-16

### New Features

- Add krotodc data classes

### Dependencies

- Update jars

---

## [1.16.0] - 2023-04-11

### Dependencies

- Update jars
- Upgrade to Kotlin 1.8.20

---

## [1.15.0] - 2022-12-14

### New Features

- Add support for using nginx as a reverse proxy for prometheus_proxy

### Dependencies

- Update gRPC, Jetty, and Ktor jars

---

## [1.14.2] - 2022-11-26

### Bug Fixes

- Fix README.md typo
- Fix service discovery format problem (#83)

---

## [1.14.1] - 2022-10-14

### Build

- Update Dockerfiles from openjdk11-jre to openjdk17-jre

---

## [1.14.0] - 2022-08-30

### New Features

- Add support for agent `scrapeMaxRetries`
- Add example of how to scrape Prometheus from the agent

### Dependencies

- Upgrade to Ktor 2.1.0, Kotlin 1.7.10, gRPC 1.49.0, Dropwizard 4.2.11

---

## [1.13.0] - 2022-03-03

### Dependencies

- Update jars

---

## [1.12.0] - 2022-01-13

### New Features

- Add Authorization header to proxied requests (#70)

### Improvements

- Convert to Ktor 2.0

### Dependencies

- Upgrade jars

---

## [1.11.0] - 2021-12-14

### Dependencies

- Update jars

---

## [1.10.0] - 2021-06-25

### New Features

- Multiplatform Docker images (#64)

### Dependencies

- Upgrade jars
- Revert Gradle to 6.8.3

---

## [1.10.1] - 2021-06-26

### Bug Fixes

- Fix missing sources (#66)

### Misc

- Remove wercker.yml config

---

## [1.9.1] - 2021-05-23

### Dependencies

- Update jars

---

## [1.9.0] - 2021-05-01

### Improvements

- Remove no-cache HTTP directive
- Code cleanup

### Dependencies

- Upgrade to Kotlin 1.5.0
- Update jars

---

## [1.8.8] - 2021-02-03

### Dependencies

- Update jars

---

## [1.8.7] - 2020-12-04

### Dependencies

- Update jars

---

## [1.8.6] - 2020-10-08

### Dependencies

- Upgrade to Kotlin 1.4.10

---

## [1.8.5] - 2020-09-03

### Dependencies

- Update jars

---

## [1.8.4] - 2020-09-03

### Dependencies

- Update jars

---

## [1.8.3] - 2020-09-01

### Dependencies

- Update jars

---

## [1.8.1] - 2020-08-31

### Documentation

- Update README.md

---

## [1.8.0] - 2020-08-28

### Dependencies

- Update jars

---

## [1.7.1] - 2020-08-12

### Dependencies

- Update jars

---

## [1.7.0] - 2020-07-08

### Dependencies

- Update jars

---

## [1.6.4] - 2020-04-30

### Dependencies

- Update jars

---

## [1.6.3] - 2019-12-21

### Dependencies

- Update jars

---

## [1.6.2] - 2019-12-18

### Dependencies

- Update jars

---

## [1.6.1] - 2019-12-15

### Bug Fixes

- Minor fixes

---

## [1.6.0] - 2019-12-15

### Improvements

- Update Docker scripts

---

## [1.5.0] - 2019-12-04

### Dependencies

- Update jars

---

## [1.4.5] - 2019-11-22

### Dependencies

- Update jars

---

## [1.4.4] - 2019-11-22

### Dependencies

- Update jars

---

## [1.4.3] - 2019-11-18

### Dependencies

- Update jars

---

## [1.4.2] - 2019-11-15

### Bug Fixes

- Fix utils jar issue

---

## [1.4.1] - 2019-11-14

### Bug Fixes

- Minor fixes

---

## [1.4.0] - 2019-11-14

### Improvements

- Convert from Maven to Gradle build system
- Convert from Java to Kotlin coroutines for gRPC streaming
- Replace Spark HTTP framework with Ktor
- Replace OkHttp client with Ktor HTTP client
- Modernize project structure

### Documentation

- Minor version and proxy/agent port corrections (#21)
- Added network diagram and other clarifications (#19)

---

## [1.3.10] - 2019-01-18

### Documentation

- Added network diagram and other clarifications (#19)
- Make app names consistent with diagram in README.md

---

## [1.3.9] - 2019-01-03

### Dependencies

- Update jars

---

## [1.3.8] - 2018-12-08

### Dependencies

- Update jars

---

## [1.3.7] - 2018-09-18

### Dependencies

- Update jars

---

## [1.3.6] - 2018-04-08

### Misc

- Cleanup compiler warnings

---

## [1.3.5] - 2018-04-08

### Dependencies

- Update jars

---

## [1.3.4] - 2018-03-02

### Dependencies

- Update jars

---

## [1.3.3] - 2018-01-25

### Dependencies

- Update jars

---

## [1.3.2] - 2018-01-03

### Misc

- Remove ConfigVals.java from Code Climate review

---

## [1.3.1] - 2017-12-29

### Improvements

- Add Kotlin idioms

---

## [1.3.0] - 2017-12-24

### New Features

- Convert codebase from Java to Kotlin

---

## [1.2.5] - 2017-12-07

### Dependencies

- Update gRPC, Spark, OkHttp, Metrics, and Brave jars

---

## [1.2.4] - 2017-11-06

### Dependencies

- Update jars

---

## [1.2.3] - 2017-05-24

### Dependencies

- Update jars

---

## [1.2.2] - 2017-04-30

### Dependencies

- Update jars

---

## [1.2.1] - 2017-04-30

### Bug Fixes

- Minor fixes

---

## [1.2.0] - 2017-04-29

### Improvements

- Minor improvements

---

## [1.1.0] - 2017-04-28

### New Features

- Add agent and proxy Grafana dashboards

---

## [1.0.0] - 2017-04-26

### Initial Release

- Prometheus Proxy enabling Prometheus to scrape metrics from endpoints behind firewalls
- **Proxy** component runs alongside Prometheus outside the firewall
- **Agent** component runs inside the firewall with monitored services
- gRPC-based communication between proxy and agent
- Streaming scrape request/response via gRPC server/client streams
- Docker support with Alpine/OpenJDK base containers
- Docker Compose configuration
- Typesafe Config (HOCON) configuration support
- CLI argument parsing with JCommander
- Prometheus metrics for agent and proxy
- Zipkin distributed tracing support
- Agent heartbeat and inactive agent cleanup
- InProcess gRPC transport for testing
- Logback logging
- Maven build with wrapper
- Travis CI and Wercker CI support
- Code Climate integration
