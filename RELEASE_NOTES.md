# Release Notes — Prometheus Proxy

---

## 3.2.0

_Released 2026-06-13_

### Highlights

- **Testcontainers smoke test** — A new `ContainersSmokeTest` builds the proxy and agent Docker images from `etc/docker/*.df`, runs them alongside an nginx metrics stub and a real Prometheus container, and verifies the full `Prometheus → proxy → agent → endpoint` scrape path end-to-end. Surfaces packaging/image regressions that the in-JVM harness can't catch.
- **Shadow JAR fix** — Re-register grpc-core's `DnsNameResolverProvider` and `PickFirstLoadBalancerProvider` in the agent and proxy fat JARs. Shadow 9.4.1's `mergeServiceFiles()` was silently dropping them when grpc-netty-shaded provided same-named service files, which made the gRPC client default to the `unix` scheme on any non-IP hostname (`Address types of NameResolver 'unix' for 'unix:///host:port' not supported by transport`). Anyone running the published agent/proxy JAR against a hostname-addressed proxy could have hit this.
- **Live `BUILD_TIME` / `APP_RELEASE_DATE`** — Switched these `BuildConfig` fields to `ValueSource`-backed providers so they refresh each build instead of being frozen by Gradle's configuration cache. The 3.1.1 `-PreleaseDate` / `-PbuildTime` overrides have been removed; release artifacts are no longer byte-for-byte reproducible.
- **Optional pre-shared agent token** — Authenticate agent gRPC connections with a shared secret (`--agent_token` / `AGENT_TOKEN`; config `proxy.agentToken` / `agent.agentToken`) without standing up mutual TLS. The agent attaches the token as an `agent-token` metadata header on every call and the proxy rejects a missing or mismatched token with `UNAUTHENTICATED` (constant-time comparison). Empty (the default) preserves today's open behavior and logs a startup warning unless mutual TLS is already configured. Closes item 1 of the [agent-authentication security note](docs/security-agent-authentication.md).
- **Per-CA HTTPS trust store for the agent** — The agent can now verify HTTPS scrape targets signed by a custom or private CA (e.g. an internal corporate CA) via `--https_truststore` / `--https_truststore_password`, without resorting to the all-or-nothing `--trust_all_x509`. This wires the previously-unused `SslSettings` into the scrape client. `--trust_all_x509` still takes precedence, and an empty path uses the JDK default trust store.
- **Code-review hardening pass** — A full code review drove a batch of security, reliability, and observability fixes: query-string secrets are now redacted in logs, embedded agents throw a catchable `ConfigLoadException` instead of killing the host JVM on a bad config, `ProxyOptions` validates ports and timeouts at startup, and the proxy scrape-latency histogram gained an `outcome` label. See the sections below.

### Security

- **Optional application-level agent authentication.** The proxy has never authenticated agents at the application layer — any process that could reach the agent gRPC port could register as an agent and hijack a path (`docs/security-agent-authentication.md`). A new optional pre-shared token (`--agent_token` / `AGENT_TOKEN`; config `proxy.agentToken` / `agent.agentToken`) closes that gap: the proxy's `AgentTokenServerInterceptor` rejects any RPC whose `agent-token` header is missing or wrong with `UNAUTHENTICATED`, using a constant-time comparison. When unset, behavior is unchanged and the proxy warns at startup that the agent port is unauthenticated (suppressed when mutual TLS is configured). The token complements — it does not replace — mutual TLS and network segmentation.
- **Query-string secrets no longer leak into logs.** `sanitizeUrl` now blanket-redacts query-parameter *values* (`?token=secret&job=x` → `?token=***&job=***`) in addition to the `user:pass@` userinfo, at every site where a scrape URL is logged or echoed back to Prometheus (including `srUrl` / `srFailureReason` when debug is enabled). Previously a token on an authed endpoint leaked at WARN whenever that endpoint was merely slow.
- **Basic-auth credentials are kept out of the HTTP-client cache key.** The agent's `HttpClientCache` no longer uses the plaintext `username:password` as the live `ConcurrentHashMap` key; it derives the key from a per-process salted HMAC-SHA256 digest, so the password no longer sits on the heap as the key for the cache's lifetime.
- **The agent's scrape response-body read is bounded, closing a memory-exhaustion vector.** `buildScrapeResults()` previously called `bodyAsText()`, which buffered the entire response into the heap *before* the size check — so a scrape target with no `Content-Length` (chunked transfer encoding) or an understated one could drive the agent toward OOM regardless of `maxContentLengthMBytes`. It now reads at most `maxContentLength + 1` bytes via `bodyAsChannel().readRemaining()`, so the size guard runs against a bounded buffer. The text path also decodes UTF-8 via `decodeToString()`, consistent with the gzip path.

### Reliability & Behavior

- **Embedded agents no longer terminate the host JVM on a config-load failure.** When `exitOnMissingConfig` is false (the `startAsyncAgent` / embedded path), a config parse or fetch failure now throws the new public `io.prometheus.common.ConfigLoadException` for the host application to catch, instead of calling `exitProcess(1)`. Standalone agents and the proxy still exit on a missing or unreadable config.
- **Fatal JVM errors are no longer swallowed and retried forever.** `connectAgent()` now rethrows `Error`s (e.g. `OutOfMemoryError`, `StackOverflowError`) so the agent terminates instead of looping on corrupted state, and connection failures now log a full stack trace rather than a message-only line.
- **Fail-fast configuration validation in `ProxyOptions`.** `proxyPort` / `proxyAgentPort` must be in `1..65535`; each gRPC timeout must be `-1` (use the gRPC default) or `> 0`; `internal.scrapeRequestTimeoutSecs`, `staleAgentCheckPauseSecs`, and `maxAgentInactivitySecs` must be `> 0` (a `0` would busy-loop the cleanup service or evict agents immediately); and `internal.maxUnzippedContentSizeMBytes` must be `>= 0` (`0` stays a valid "reject all" limit). Invalid values now produce a clear startup error instead of an opaque Ktor/gRPC builder exception or every scrape instantly returning "timed_out".
- **Per-request call logging dropped from INFO to DEBUG.** With `requestLoggingEnabled = true` (still the default), routine per-scrape logging no longer floods INFO on a busy proxy; WARN/ERROR stay visible and operators can re-enable it by lowering the root log level.
- **No `HttpClient` leak when an embedded agent is stopped mid-scrape.** `HttpClientCache.close()` now sets a terminal flag so a scrape racing shutdown can't create and cache a fresh client into the already-closed cache.
- **Removed the deprecated `all` log level.** `ALL` is vestigial from log4j 1.x; in logback the `Level` class is final, so all levels are enabled simply by setting a logger to `TRACE`, and logback marks `Level.ALL` as deprecated. Accordingly, `logLevel = "all"` (config, `--log_level`, or env) now throws a clear startup error instead of mapping to the deprecated level — use `"trace"` for the most verbose output.

### Observability

- **`proxy_scrape_request_latency_seconds` gained an `outcome` label** and now records latency for *every* request outcome — including the timeout and agent-disconnected paths that previously recorded none. The `outcome` values share the taxonomy already used by the `proxy_scrape_requests` `type` label (`success`, `timed_out`, `agent_disconnected`, `path_not_found`, …).
- **`proxy_start_time_seconds` is now labeled with a per-process `launch_id`** (mirroring the agent metrics) so proxy restarts are distinguishable on a Prometheus target.
- **Dropped scrape results are now counted.** A fully-computed result discarded because the agent connection closed mid-scrape increments `agent_scrape_result_count{type="dropped"}` instead of silently relying on the proxy-side timeout.
- A malformed `Content-Type` from a scrape target is now logged at WARN (was DEBUG), so the silent fallback to `text/plain` is visible in production.

### Bug Fixes

- Fix gRPC `NameResolverProvider` and `LoadBalancerProvider` services dropped from the shaded `agentJar`/`proxyJar`; static service files under `src/shadow/resources/META-INF/services/` re-register `DnsNameResolverProvider` (so `forAddress(host, port)` resolves via DNS) and `PickFirstLoadBalancerProvider` (the default load balancer)
- Fix flaky `ProxyHttpRoutesTest > handleClientRequests should return ServiceUnavailable when proxy is not running` — the TCP-connect probe in `startServerAndGetPort` only confirmed the kernel's SYN/ACK handshake, not Ktor's user-space accept loop. Replaced with an HTTP-level readiness probe that retries on `IOException`/`ClosedByteChannelException` for up to 5 s
- Fix `appendQueryParams` mangling Prometheus query params: the already-encoded query string is now appended verbatim instead of being URL-decoded as a whole and re-concatenated, which could let an encoded `&` or `#` inside one value expand into extra parameters or a fragment
- Fix `writeResponsesToProxy` blocking the HTTP handler until `scrapeRequestTimeoutSecs` (a slow, misleading 503) when a single response failed to process — it now fails that scrape request immediately, matching the chunked-response path

### New Features

- New `--https_truststore` / `--https_truststore_password` agent options (env `HTTPS_TRUST_STORE_PATH` / `HTTPS_TRUST_STORE_PASSWORD`; config `agent.http.trustStorePath` / `agent.http.trustStorePassword`) — verify HTTPS scrape targets against a custom/private CA without disabling validation. Resolved CLI > env > config; the password is never logged. `--trust_all_x509` takes precedence, and an empty path uses the JDK default trust store
- New `ContainersSmokeTest` (`io.prometheus.containers`) — Testcontainers-based end-to-end test, gated on `RUN_CONTAINER_TESTS=true`. Default `./gradlew test` runs see a single SKIPPED placeholder
- Expanded container-test suite (`io.prometheus.containers`) — beyond the smoke test, seven Testcontainers specs now exercise the full stack over real Netty/Docker: `ContainersProxyHttpTest` (404s, upstream-status passthrough, admin `ping`/`version`/`healthcheck`/`threaddump`, proxy & agent `/metrics`, service discovery), `ContainersAgentTokenAuthTest` (token match + mismatch), `ContainersConsolidatedTest` (two-agent path merge), `ContainersLargePayloadTest` (chunk + gzip reassembly), `ContainersReconnectTest` (agent reconnect after proxy replacement), `ContainersTlsTest` (server-only and mutual gRPC TLS), and `ContainersHttpsTargetTest` (trust-all positive/negative). All share a new `support/ContainerTestSupport.kt` (image/network/container factories, HTTP and PromQL helpers) and stay gated on `RUN_CONTAINER_TESTS=true`
- New `ContainersScalingTest` — a single parameter-driven spec that scales the system along its real load axes (agents × endpoints per agent, series per endpoint, consolidated fan-out, and scrape concurrency), verifies every path is scrapable end-to-end, and asserts the proxy's own `proxy_agent_map_size` / `proxy_path_map_size` gauges match the expected counts. A CI-safe default table runs under `make container-tests`; setting any `SCALE_*` env var collapses the run to one tuned scenario so the load can be dialed up without recompiling
- New `make container-tests` target — auto-detects Docker Desktop's active context (`docker context inspect`) and exports `DOCKER_HOST` so Testcontainers finds non-default sockets on macOS
- New `.github/workflows/container-tests.yml` — runs the smoke test on push to master, on `workflow_dispatch`, and on PRs that touch packaging-relevant paths (`etc/docker/**`, `build.gradle.kts`, `gradle/libs.versions.toml`, `src/shadow/**`, the test sources/resources, and the workflow file)
- New `make help` target with auto-extracted descriptions from `## …` annotations on each target

### Code Quality

- Full code-review cleanup pass with no behavior change: extracted the shared common-option assignment between `AgentOptions` and `ProxyOptions`, decomposed `Agent.run()`'s four connection tasks behind one `launchConnectionTask` helper, collapsed the six duplicated `ConfigWrappers` overloads onto shared builders, modeled basic-auth credentials as a `Credentials` value object, split the agent chunk-size field into a KB input plus a derived bytes value, replaced the stringly-typed `PathConfig` map with a typed data class, and unified the gRPC-default log formatting behind a single `grpcDefaultLabel` helper
- Added a negative-path mutual-TLS rejection test plus unit coverage for timeout-override resolution, the chunked unknown-`scrapeId` header drop, wrapped-timeout detection, the chunk-size boundary, `AgentHttpService` `PayloadTooLarge` branches, `SslSettings` success paths, and `BaseOptions` URL/HTTP config loading; encode the gzipped response body only once (was twice); deleted the dead `SslSettings` scaffolding (now wired into the new HTTPS trust store) and stale commented-out blocks
- Closed a test-coverage gap on `EnvVars`: the `getEnv(Int)` / `getEnv(Long)` invalid-value error paths were unreachable from tests because `System.getenv()` can't be set in-process. Extracted `parseIntStrict` / `parseLongStrict` companion helpers (matching the existing `parseBooleanStrict` seam) and tested them directly for boundaries, signs, `Int`-overflow rejection, and non-numeric/whitespace input; removed a redundant default-fallback test
- Hardened `ProxyPathManager` against shared-mutable-state bugs: `AgentContextInfo.agentContexts` is now an immutable `List`, with each mutation replacing the `pathMap` entry via `copy()` instead of mutating a list a data-class `copy()` might still share; the read accessors drop their now-redundant defensive `.toList()` copies

### Build & Tooling

- Remove three dead config keys: `proxy.http.maxThreads` / `proxy.http.minThreads` (orphaned since the 1.4.0 Ktor-server migration dropped the `threadPool(...)` call) and `proxy.internal.scrapeRequestCheckMillis` (orphaned since 3.0.0 replaced the timeout polling loop with `awaitCompleted`). No production code read them — setting them had no effect — so the keys and their generated `ConfigVals` fields have been removed
- Replace the `-PreleaseDate` / `-PbuildTime` Gradle property overrides with `ValueSource`-backed providers so `BuildConfig.APP_RELEASE_DATE` and `BuildConfig.BUILD_TIME` are read fresh on each build rather than being frozen by Gradle's configuration cache. The override flags introduced in 3.1.1 are removed; release artifacts are no longer byte-for-byte reproducible
- Move detekt configuration from `etc/detekt/` to `config/detekt/` (the standard detekt convention); `build.gradle.kts` and `CLAUDE.md` updated accordingly
- Add `detekt` to the `lint` Makefile target so `make lint` now runs `lintKotlinMain`, `lintKotlinTest`, and `detekt` together
- Add `detekt-baseline` Makefile target (`./gradlew detektBaseline`) for grandfathering existing findings when tightening rules
- DRY the agent/proxy ShadowJar registrations behind a `ShadowJar.configureFatJar(archiveName, mainClass)` helper; switch `configurations = listOf(runtimeClasspath.get())` to `configurations.add(runtimeClasspath)` so the configuration stays a provider until shadow resolves it
- Refuse `make docker-push` when `VERSION` matches `*SNAPSHOT*` / `*-rc*` / `*-beta*` / `*-alpha*` so a pre-release can't clobber the public `:latest` tag
- Validate `VERSION` and `GRADLE_VERSION` are detected at the top of the Makefile (fail fast with a clear `$(error)` instead of silently issuing commands with empty version arguments)
- Add `TSCFG_VERSION` variable so the tscfg jar version isn't duplicated inline; centralize `PLATFORMS` and `IMAGE_PREFIX` next to the existing `VERSION` block
- Standardize on `$(VAR)` everywhere in the Makefile (was a mix of `$(VAR)` and `${VAR}`)
- Replace `distro: build $(MAKE) jars` with a plain `distro: build jars` prerequisite list (no recursive sub-make)
- Externalize the inline coverage-packages python script to `scripts/coverage_packages.py` (typed, error-on-missing-report)
- Annotate every Make target with a `## description` and add a `make help` target that awk-extracts them
- Add a `make all-tests` target that runs the full suite — the in-JVM tests plus the Docker-backed `container-tests`
- Add `make scaling-tests` (forwarding `SCALE_*` and `TEST_MAX_HEAP_SIZE`) and six curated scaling presets — `scaling-paths`, `scaling-agents`, `scaling-payload`, `scaling-consolidated`, `scaling-concurrency`, and `scaling-soak`, each hammering a different subsystem — wired together under `all-scaling`. These are dev/stress aids only and are not run by `all-tests` or CI
- Honor `-PtestMaxHeapSize` / `TEST_MAX_HEAP_SIZE` to size the forked test JVM heap, overriding the harness-load-based default so large `make scaling-tests` runs (which can hold thousands of scrape bodies at once) don't OOM
- Add `make regen-certs` to rebuild the `testing/certs` TLS fixtures (CA + server + client) from scratch at 2048-bit with 100-year validity, preserving the `*.test.google.fr` SAN the TLS harness relies on; the committed fixtures were regenerated and the container TLS/HTTPS specs mount them directly (no runtime openssl)
- Centralize the test suite's scattered port literals into a single `io.prometheus.common.TestPorts` object (test source set), so unit/harness/container specs reference named constants instead of duplicating magic numbers and unit tests no longer pull in the Testcontainers support harness just for a port value
- Document the double `./gradlew wrapper` invocation in `upgrade-wrapper` as Gradle's documented two-run upgrade procedure
- Fill in missing `.PHONY` entries for `mini-tests` and the `coverage-*` family
- Move the Testcontainers smoke test out of `io.prometheus.harness` into a dedicated `io.prometheus.containers` package so the make target is a clean wildcard rather than a `Containers*` prefix match
- Switch the proxy/agent Docker images to the prebuilt `bellsoft/liberica-openjre-alpine:17` base instead of `alpine` + `apk add openjdk17-jre`. Builds no longer download the JRE from the Alpine mirror at build time (faster and not subject to intermittent mirror stalls), and the base is genuinely multi-arch (amd64 + arm64), so the images run on Apple Silicon — the `eclipse-temurin:17-jre-alpine` alternative was amd64-only. Still Alpine-based, so the existing busybox `adduser` step is unchanged
- Pin the proxy/agent `bellsoft/liberica-openjre-alpine:17` base image by digest in `etc/docker/{agent,proxy}.df` for reproducible builds (the tag is kept inline for readability), and drop the no-op `-XX:+UnlockExperimentalVMOptions` / `-XX:+UseG1GC` flags from the container ENTRYPOINT — G1 is the JDK 17 default GC and `MaxGCPauseMillis` / `UseStringDeduplication` are non-experimental, so the unlock flag did nothing
- Pin the docs toolchain to `zensical==0.0.45` and `mkdocs-material==9.7.6` in `.github/workflows/docs.yml` so the published site builds reproducibly; bump deliberately when upgrading the toolchain
- Harden the nginx reverse-proxy example image (`nginx/docker/Dockerfile`): the base image moved from the Debian-based `nginx` to `nginx:1.29-alpine` (clearing a Snyk OS-package CVE while retaining the gRPC module), and a `RUN apk upgrade --no-cache` step pulls the patched Alpine package revisions Snyk flagged — libxml2 `2.13.9-r1`, xz-libs `5.8.3-r0`, and libssl3/libcrypto3 `3.5.7-r0` — from the same Alpine branch at build time
- Run the test suite in CI and upload kover coverage to Codecov on each push and pull request
- Scope `netty-tcnative` and `jul-to-slf4j` as `runtimeOnly` (neither is referenced at compile time; the native TLS provider and the JUL→SLF4J bridge are still bundled into the fat JARs via `runtimeClasspath`)
- Drop the unused `kotlinx-datetime` dependency (catalog entry, library, and a commented-out usage) — the code never referenced it

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

`protobuf` / `protoc` (4.34.1 → 3.25.3) and `netty-tcnative` (2.0.77.Final → 2.0.75.Final) are pinned
**down** to the versions the gRPC 1.82.0 artifacts ship — not the newer releases `make versions` flags —
so the generated protobuf stubs and the native TLS provider stay binary-compatible with grpc-netty-shaded.

---

## 3.1.1

_Released 2026-04-30_

### Highlights

- **Documented public API** — Every `@Parameter` field on `BaseOptions` / `AgentOptions` / `ProxyOptions`, every value of `EnvVars`, the `Agent` and `Proxy` companion entry points, and `EmbeddedAgentInfo` now ship with full KDoc covering resolution precedence (CLI → env → config → default), sentinel values, and validation rules.
- **Reproducible builds** — `BuildConfig.APP_RELEASE_DATE` and `BuildConfig.BUILD_TIME` accept `-PoverrideReleaseDate` / `-PoverrideBuildTime` Gradle properties so CI can produce bit-identical artifacts.
- **Flaky test fixes** — Replaced timing-based probes in `AgentTest` and `AgentHttpServiceTest` with deterministic
  readiness gates, eliminating two long-standing CI flakes.
- **Cleaner build script** — Centralized repositories in `settings.gradle.kts`, dropped the redundant fat-jar rewrap, removed the redundant `java` plugin alias, and aligned `dependsOn` calls on `tasks.named()`.

### New Features

- `-PoverrideReleaseDate` and `-PoverrideBuildTime` properties for reproducible builds

### Bug Fixes

- Fix flaky `AgentTest` "Bug #1" coroutine backpressure test — sample point could land between batches and observe 0
  active coroutines. Replaced the timing-based probe with a deterministic `CompletableDeferred` gate that pins the
  active count at exactly `maxConcurrency`, with Kotest `eventually()` to absorb scheduler jitter on busy CI hosts
- Fix flaky `AgentHttpServiceTest` — the fixed 100 ms post-`server.start` delay was insufficient on busy machines,
  causing "connection refused" failures with `expected:<true> but was:<false>`. Replaced with an active TCP-connect
  probe (20 ms poll, 5 s deadline)

### Build & Tooling

- Centralize repository declarations in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)`; `mavenLocal()` is opt-in with `-PuseMavenLocal=true`
- Replace the `agentJar`/`proxyJar` zipTree-rewrap with two `ShadowJar` tasks (configuration-cache safe; one fewer redundant fat jar on disk)
- Drop the redundant `java` plugin (applied transitively by `kotlin.jvm`)
- Switch `compileKotlin.dependsOn(":generateProto")` to `tasks.named("generateProto")` for type-safe task references
- Mark the internal `Utils` object as `internal`
- Hoist `formatter`, `releaseDate`, and `buildTime` out of the `buildConfig {}` block to top-level `val`s for reuse
  elsewhere in the script
- Centralize test server readiness in a shared `startServerAndGetPort` helper
- Add `check-gpg-env` Makefile target for GPG signing validation
- Fix the date format passed by the `build` and `local-build` Makefile targets to match the `MM/dd/yyyy` pattern parsed
  by `build.gradle.kts`
- Add Claude Code GitHub workflow

### Documentation

- Full KDoc on the public API surface — `BaseOptions`, `AgentOptions`, `ProxyOptions`, `EnvVars`, `Agent.Companion`, `Proxy.Companion`, `EmbeddedAgentInfo` — with resolution precedence, sentinel-value, and validation notes
- Trim `docs/packages.md` to the genuinely-public types so Dokka has no dangling cross-references; internal plumbing (HTTP routing, gRPC services, agent registries, etc.) is documented in source but intentionally omitted from the published site
- Refresh metrics-and-grafana reference and the Zensical website docs

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

## 3.1.0

### Highlights

- **Maven Central publishing** — Now published as `com.pambrose:prometheus-proxy` on Maven Central. JitPack is no longer used.
- **Documentation site** — Full Zensical-powered documentation site deployed to GitHub Pages with guides, code examples, and architecture diagrams.
- **Metrics overhaul** — New proxy and agent metrics, latency converted from summaries to histograms, rebuilt Grafana dashboards.

### Breaking Changes

- **Maven coordinates changed** from JitPack (`com.github.pambrose.prometheus-proxy`) to Maven Central (`com.pambrose:prometheus-proxy`). Update your `build.gradle.kts` or `pom.xml`.

### New Features

- Zensical documentation site with 13 pages covering architecture, getting started, configuration, security/TLS, Docker, embedded agent, service discovery, monitoring, CLI reference, and advanced topics
- Extract Java/Kotlin code examples into compilable source files so API changes are caught by the compiler
- Add mkdocs-material theme support with grid card layouts, material icons, admonitions, and collapsible details
- Add KDocs nav entry linking to Dokka API docs, plus API Reference section on index page
- Documentation automatically built and deployed to GitHub Pages via CI

### Metrics & Observability

- New proxy counters: `proxy_chunk_validation_failures_total`, `proxy_chunked_transfers_abandoned_total`, `proxy_agent_displacement_total`
- New proxy histogram: `proxy_scrape_response_bytes` (with `path` and `encoding` labels)
- New agent gauges: `agent_client_cache_size`, `agent_scrape_backlog_size`
- Converted proxy and agent latency metrics from summaries to histograms (enables `histogram_quantile` aggregation)
- Rebuilt Grafana dashboards for new metric schema
- Added complete metrics reference with PromQL examples (`docs/metrics-and-grafana.md`)

### Bug Fixes

- Fix flaky `HttpClientCacheTest` by ensuring deterministic LRU eviction order
- Fix scrape response bytes metric to observe correct unzipped size

### Build & Tooling

- Migrate publishing from JitPack to Maven Central using vanniktech maven-publish plugin
- Replace manual `maven-publish` + sources/javadoc JAR tasks with `mavenPublishing` DSL
- Remove JitPack plugin resolution strategy from `settings.gradle.kts`
- Remove `jitpack.yml`
- Add GPG signing for Maven Central (skipped when no key is provided)
- Add `overrideVersion` property support for snapshot publishing
- Use portable bash shebang (`#!/usr/bin/env bash`) in `bin/` scripts
- Extract Docker image version from `build.gradle.kts` in `bin/` scripts
- Remove `.superset` config files and legacy files

### Dependency Updates

| Dependency     | Old    | New    |
|----------------|--------|--------|
| utils          | 2.6.3  | 2.7.1  |
| Kotest         | 6.1.7  | 6.1.10 |
| Ktor           | 3.4.0  | 3.4.2  |
| Logback        | 1.5.31 | 1.5.32 |
| Protoc         | 4.34.0 | 4.34.1 |
| Dropwizard     | 4.2.38 | 4.2.38 |
| gradle-plugins | 1.0.10 | 1.0.12 |
| Dokka          | (new)  | 2.2.0  |
| maven-publish  | (new)  | 0.36.0 |
| Kover          | 0.9.7  | 0.9.8  |

---

## 3.0.3

### Dependency Updates

| Dependency     | Old    | New    |
|----------------|--------|--------|
| Kotlin         | 2.3.10 | 2.3.20 |
| Gradle wrapper | 9.2.0  | 9.4.0  |
| gRPC           | 1.79.0 | 1.80.0 |
| Kotest         | 6.1.3  | 6.1.7  |
| Protoc         | 4.33.5 | 4.34.0 |
| utils          | 2.5.3  | 2.6.3  |
| gradle-plugins | 1.0.8  | 1.0.10 |
| config plugin  | 6.0.7  | 6.0.9  |

### Build & Tooling

- Extract JitPack URLs into reusable Makefile variables (`JITPACK_BUILD_URL`, `JITPACK_API_URL`)
- Enable Gradle configuration caching and daemon
- Use `forEach` instead of `map` in coroutine launches for clarity in `AgentConnectionContextTest`

### Documentation & Cleanup

- Remove outdated GEMINI.md, AGENTS.md, and OpenSpec instructions
- Remove legacy documentation and workflows
- Clean up CLAUDE.md

---

## 3.0.1

### Build & Tooling

- Add homepage link to plugins configuration in build.gradle.kts
- Update dependency management and plugin versions in build.gradle.kts and settings.gradle.kts
- Update .gitignore to include test configuration files

### Documentation

- Add GitHub workflow commands and API documentation section to README

---

## 3.0.0

**Version bump: 2.4.0 → 3.0.0**

---

## Bug Fixes

### Data Integrity & Correctness

- Fix integer overflow in `ChunkedContext.totalByteCount` (Int → Long) that could silently bypass size limits on large
  payloads
- Fix chunk checksum calculation to use actual byte count instead of full buffer size
- Fix `toScrapeResponseHeader` to propagate the actual `srZipped` value (was hardcoded to `true`)
- Fix `applySummary` to propagate the `headerZipped` value from chunked response headers
- Fix `IOException` error code from `NotFound` (404) to `ServiceUnavailable` (503) — semantically correct for
  unreachable targets
- Fix catch-all HTTP exception handler from `NotFound` (404) to `InternalServerError` (500)
- Fix `errorCode()` to walk the exception cause chain for wrapped timeout exceptions
- Fix OpenMetrics `# EOF` marker handling in consolidated responses — intermediate `# EOF` markers are now stripped
- Fix `parseHostPort` to strip brackets from IPv6 addresses in `HostPort` — `[::1]:50051` now yields host `::1` instead
  of `[::1]`

### Concurrency & Resource Management

- Fix TOCTOU race in `AgentContextCleanupService` — agents are now re-checked for staleness before eviction
- Fix negative `scrapeRequestBacklogSize` with atomic CAS-loop decrement clamped at zero
- Fix `ConcurrentModificationException` in `ProxyPathManager.removePathsForAgentId` and `recentReqs` access
- Fix `HttpClientCache.close()` deadlock — coroutine scope cancelled before acquiring mutex
- Fix HTTP client close calls moved outside mutex to avoid blocking cache operations during slow I/O
- Fix idle HTTP clients now closed on eviction (previously only marked for close)
- Fix `AgentHttpService` now properly closed during agent shutdown (resource leak)
- Fix path registration concurrency by moving gRPC calls outside the mutex
- Fix `AgentClientInterceptor` to use the `next` channel parameter instead of bypassing the interceptor chain
- Fix synchronized `agentId` assignment in `AgentClientInterceptor` to prevent race condition
- Fix `ScrapeRequestWrapper.markComplete()` is now idempotent via `AtomicBoolean.compareAndSet`
- Fix `runCatching` replaced with `runCatchingCancellable` throughout to avoid swallowing `CancellationException`
- Fix agent context added after ID validation to prevent orphaned contexts

### Error Handling & Cleanup

- Fix orphaned `ChunkedContext` cleanup on stream failure — associated scrape requests are now explicitly failed
- Fix chunk validation errors now throw `ChunkValidationException` instead of crashing the gRPC stream
- Fix `readRequestsFromProxy` throws `StatusException(NOT_FOUND)` when agent context is missing (was silently no-op)
- Fix `connectAgent`/`connectAgentWithTransportFilterDisabled` throw `StatusException(FAILED_PRECONDITION)` instead of
  `RequestFailureException`
- Fix `sendHeartBeat` re-throws `NOT_FOUND` status to trigger agent reconnection (was zombie state)
- Fix agent invalidation now drains pending scrape requests and unblocks HTTP handlers immediately
- Fix `handleConnectionFailure` re-throws JVM `Error` subclasses instead of retrying in a corrupted state
- Fix stream cleanup for `transportFilterDisabled` mode in `readRequestsFromProxy` finally block

### Security

- Fix credential leak in `HttpClientCache` logs — `ClientKey.toString()` now masks credentials
- Fix password `CharArray` zeroed after use in `SslSettings.getKeyStore`
- Fix `FileInputStream` resource leak in `SslSettings` — now uses try-with-resources
- Fix URL sanitization in agent logs to strip credentials before logging

### Misc

- Fix gzip compression for small responses — enforced `minimumSize(1024)` in `ProxyHttpConfig`
- Fix redundant `response.status()` call in `ProxyUtils.respondWith`
- Fix service discovery and metrics paths now ensure leading `/`
- Fix dynamic parameter handling to correctly set system properties
- Fix `registerPath`/`registerAgent`/`sendHeartBeat` responses only set `reason` field when `valid` is false
- Fix typo: "Overide" → "Override" in config and ConfigVals

---

## New Features

- **Content size limits** — New configurable limits to prevent zip bombs and unbounded memory:
  - `proxy.internal.maxZippedContentSizeMBytes` (default 5 MB)
  - `proxy.internal.maxUnzippedContentSizeMBytes` (default 10 MB)
  - `agent.http.maxContentLengthMBytes` / `AGENT_MAX_CONTENT_LENGTH_MBYTES` (default 10 MB)
- **Unary RPC deadline** — `agent.grpc.unaryDeadlineSecs` / `UNARY_DEADLINE_SECS` (default 30s) prevents unary gRPC
  calls from hanging indefinitely
- **Graceful scrape request failure** — Orphaned scrape requests are failed with proper status on agent disconnect,
  stream termination, chunk validation failure, and proxy shutdown
- **Consolidated/non-consolidated mismatch rejection** — `addPath` now rejects mismatched agent types on the same path
  with a descriptive error
- **Authorization header TLS warning** — One-time warning logged when auth headers are sent over non-TLS connections
- **HTTP request lifecycle** — `cancelCallOnClose = true` cancels HTTP requests when clients disconnect
- **Bounded scrape request channel** — Agent-side channel now has configurable backpressure instead of unlimited
  capacity
- **Outer scrape timeout** — `withTimeout` wrapper in `fetchContent()` as safety net beyond Ktor client timeout
- **Strict env var parsing** — Boolean env vars only accept `"true"`/`"false"`; integer/long env vars throw descriptive
  errors on invalid values
- **"all" log level** — `setLogLevel` now accepts "all" as a valid level
- **Input validation** — `parseHostPort` validates blank strings; `parsePort` validates port ranges
- **TLS config validation** — Requires both certificate and key for TLS; warns on disabled X.509 verification

---

## Refactoring

- `ScrapeResults` fields changed from `var` to `val` (fully immutable construction)
- `ResponseResults` and `ScrapeRequestResponse` converted to immutable data classes
- `updateMsg: String` → `updateMsgs: List<String>` in `ResponseResults`
- `ProxyUtils` response functions now return values instead of mutating a passed-in object
- `AgentContextManager` maps made private with accessor methods and read-only views
- `ScrapeRequestManager.scrapeRequestMap` made private with read-only view
- `ProxyPathManager` changed from `ConcurrentMap` to `HashMap` with explicit `synchronized` blocks
- `AgentPathManager` uses `ConcurrentHashMap` and `Mutex` for thread-safe registration
- `AgentGrpcService` uses `ReentrantLock` for thread-safe shutdown and stub creation
- gRPC metadata constants consolidated into `GrpcConstants`
- Config file moved: `etc/config/config.conf` → `config/config.conf`
- Detekt config moved: `config/detekt/` → `etc/detekt/`
- `SslSettings` return types changed from nullable to non-nullable
- Scrape request queue changed from `Channel` to `ConcurrentLinkedQueue` with notifier
- Scrape request polling loop replaced with event-driven `awaitCompleted()` suspension
- Proto: reserved field 5 in `RegisterAgentRequest`; added `header_zipped` field 8 to `HeaderData`

---

## Dependency Updates

| Dependency     | Old    | New    |
|----------------|--------|--------|
| Kotlin         | 2.2.20 | 2.3.10 |
| Gradle wrapper | 8.x    | 9.2.0  |
| Ktor           | 3.2.3  | 3.4.0  |
| gRPC           | 1.75.0 | 1.79.0 |
| Protoc         | 4.32.0 | 4.33.5 |
| JCommander     | 2.0    | 3.0    |
| Kotest         | 6.0.3  | 6.1.3  |
| Logback        | 1.5.18 | 1.5.31 |
| MockK          | (new)  | 1.14.9 |
| tcnative       | 2.0.73 | 2.0.74 |
| utils          | 2.4.5  | 2.5.3  |
| config plugin  | 5.6.8  | 6.0.7  |
| kotlinter      | 5.2.0  | 5.4.2  |
| kover          | 0.9.1  | 0.9.7  |
| dropwizard     | 4.2.36 | 4.2.38 |
| gengrpc        | 1.4.3  | 1.5.0  |
| serialization  | 1.9.0  | 1.10.0 |
| slf4j          | 2.0.13 | 2.0.17 |
| typesafe       | 1.4.4  | 1.4.5  |

---

## CI/CD

- Added GitHub Actions CI workflow for building the project on push/PR to `master`
- Added GitHub Actions workflow for deploying Dokka API documentation to GitHub Pages
- Removed Travis CI configuration (`.travis.yml`)

---

## Documentation

- Integrated Dokka for HTML API documentation generation (`./gradlew dokkaHtml`)
- Added KDoc documentation across agent, proxy, and common packages
- Added module and package documentation (`docs/packages.md`)
- Added improvements roadmap document (`docs/improvements.md`)

---

## Testing

- ~26,000+ lines of new unit tests added
- Tests reorganized into `io.prometheus.agent/`, `io.prometheus.proxy/`, `io.prometheus.common/`, `io.prometheus.misc/`
- Added MockK for mocking support
- Compiler option `-Xreturn-value-checker=check` enabled

---

## Breaking Changes

### High Impact — Will affect most users monitoring scrape responses

| # | Change                                       | Detail                                                                                                                                                                                                                           |
|---|----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | **Default scrape failure status: 404 → 503** | `ScrapeResults.srStatusCode` default changed from `NotFound` (404) to `ServiceUnavailable` (503). Any monitoring/alerting keyed on status codes from failed scrapes will see different codes.                                    |
| 2 | **IOException scrape error: 404 → 503**      | When the agent can't reach the scrape target (connection refused, DNS failure, etc.), the status returned to Prometheus changed from 404 to 503.                                                                                 |
| 3 | **Catch-all exception: 404 → 500**           | Unexpected server errors in the proxy now return `InternalServerError` (500) instead of `NotFound` (404).                                                                                                                        |
| 4 | **New HTTP status codes**                    | New responses that didn't exist before: `413 Payload Too Large` (zip bomb), `502 Bad Gateway` (invalid gzip / chunk validation), `503 ServiceUnavailable` (agent disconnected mid-scrape, no agents available, missing results). |

### Medium Impact — Affects users with specific configurations

| #  | Change                                              | Detail                                                                                                                                                                                                                                              |
|----|-----------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 5  | **Content size limits enforced by default**         | Scrape responses >10 MB (agent-side), zipped chunks >5 MB, or unzipped content >10 MB will now be rejected. Users with large metric endpoints must raise `maxContentLengthMBytes`, `maxZippedContentSizeMBytes`, or `maxUnzippedContentSizeMBytes`. |
| 6  | **Unary RPC deadline enforced (30s)**               | gRPC unary calls (register, heartbeat, etc.) now fail after 30 seconds. May affect users with very slow/distant proxy connections. Configurable via `agent.grpc.unaryDeadlineSecs`.                                                                 |
| 7  | **Retry policy: only 5xx**                          | Agent retries now only on server errors (500-599). Previously retried on any non-success status except 404. Users relying on retries for specific 4xx errors will no longer get retries.                                                            |
| 8  | **Strict boolean env vars**                         | `EnvVars.getEnv(Boolean)` now only accepts `"true"` or `"false"` (case-insensitive). Values like `"1"`, `"yes"`, `"TRUE "` (with whitespace) will throw `IllegalArgumentException`.                                                                 |
| 9  | **Consolidated/non-consolidated mismatch rejected** | `addPath` now returns an error when agent types conflict on the same path. Previously silently accepted.                                                                                                                                            |
| 10 | **Compression preference: Deflate → Gzip**          | Gzip priority raised from 1.0 to 10.0. Clients that previously received Deflate-compressed responses will now receive Gzip.                                                                                                                         |
| 11 | **JCommander 2.0 → 3.0**                            | May have CLI parsing behavior changes.                                                                                                                                                                                                              |

### Low Impact — Affects developers / embedded API users

| #  | Change                                                                      | Detail                                                                                                                                                                                                            |
|----|-----------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 12 | **Config file path moved**                                                  | `etc/config/config.conf` → `config/config.conf`. Docker volumes or scripts referencing the old path need updating.                                                                                                |
| 13 | **`GrpcConstants` consolidation**                                           | `META_AGENT_ID_KEY` and `AGENT_ID` moved from `ProxyServerInterceptor`/`ProxyServerTransportFilter` companions to `io.prometheus.common.GrpcConstants`. Compile-time break for code importing from old locations. |
| 14 | **`SslSettings` non-nullable returns**                                      | `getTrustManagerFactory()` and `getSslContext()` now return non-nullable types. Callers that null-checked will get compile errors.                                                                                |
| 15 | **`HttpClientCache.getCacheStats()` is now `suspend`**                      | Requires coroutine context to call.                                                                                                                                                                               |
| 16 | **`AgentConnectionContext.close()` returns `Int`**                          | No longer implements `Closeable`. Returns drained request count.                                                                                                                                                  |
| 17 | **`ScrapeRequestWrapper.scrapeResults` is now nullable**                    | Changed from `nonNullableReference()` delegate to `@Volatile var scrapeResults: ScrapeResults? = null`.                                                                                                           |
| 18 | **`ProxyOptions.proxyHttpPort` renamed to `proxyPort`**                     | CLI flags unchanged, but programmatic access needs updating.                                                                                                                                                      |
| 19 | **`AgentOptions.chunkContentSizeKbs` renamed to `chunkContentSizeBytes`**   | CLI flag (`--chunk`) and config key (`agent.chunkContentSizeKbs`) unchanged, but the internal Kotlin field name changed. Affects embedded agent API users.                                                        |
| 20 | **`ScrapeResults` fields are now immutable (`val`)**                        | All fields changed from `var` to `val`. The `setDebugInfo()` method was removed. `scrapeCounterMsg` changed from `AtomicReference<String>` to plain `val String`.                                                 |
| 21 | **`PathManager.addPath()` return type changed**                             | Returns `String?` (null on success, failure reason on failure) instead of `Unit`.                                                                                                                                 |
| 22 | **`AgentContextManager` and `ScrapeRequestManager` maps made private**      | Access through getter methods and read-only views only.                                                                                                                                                           |
| 23 | **`ScrapeRequestWrapper.suspendUntilComplete` renamed to `awaitCompleted`** | Polling loop replaced with single event-driven suspension.                                                                                                                                                        |
| 24 | **Kotlin 2.3.10 / Gradle 9.2.0**                                            | Users building from source need compatible toolchains.                                                                                                                                                            |
| 25 | **Strict integer/long env var parsing**                                     | `EnvVars.getEnv(Int)` and `getEnv(Long)` now throw `IllegalArgumentException` with descriptive messages for invalid values (previously threw raw `NumberFormatException`).                                        |

