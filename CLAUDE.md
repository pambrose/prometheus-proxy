# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System and Common Commands

Gradle with Kotlin DSL. Java 17+ required. All commands from project root.

```bash
./gradlew build                          # Build with tests
./gradlew build -xtest                   # Build without tests
./gradlew test                           # Run all tests
./gradlew test --tests "TestClassName"   # Run specific test class
./gradlew test --tests "io.prometheus.SomeTestClass.someTestMethod"  # Single test method
./gradlew --rerun-tasks check            # Force rerun all checks (lint + tests)
./gradlew agentJar proxyJar              # Generate standalone JARs
./gradlew generateProto                  # Regenerate protobuf stubs
./gradlew koverHtmlReport                # HTML coverage report (build/reports/kover/html/)
./gradlew koverXmlReport                 # XML coverage report (CI / Codacy / Coveralls)
./gradlew koverLog                       # Print coverage % to console
```

### Code Quality

```bash
./gradlew lintKotlinMain lintKotlinTest  # Run kotlinter linter
./gradlew detekt                         # Run detekt static analysis
./gradlew formatKotlin                   # Auto-format code
```

Always run lint and build before completing tasks:
`./gradlew detekt && ./gradlew lintKotlinMain && ./gradlew build -xtest`

### Useful Make Targets

```bash
make help            # List all targets with descriptions (auto-extracted from `## …` annotations)
make tests           # Rerun all checks (lint + tests)
make nh-tests        # Unit tests only (agent, proxy, common, misc — no harness)
make ip-tests        # In-process integration tests only
make netty-tests     # Netty integration tests only
make tls-tests       # TLS integration tests only
make container-tests # Full Testcontainers end-to-end suite (proxy + agent + nginx + Prometheus); needs Docker
make all-tests       # Full suite: `make tests` + `make container-tests`
make scaling-tests   # Parameter-driven scaling container test (tune via SCALE_* vars); needs Docker
make regen-certs     # Regenerate the testing/certs TLS fixtures (CA + server + client; 2048-bit)
make coverage        # Run tests + generate HTML and XML coverage reports
make coverage-xml    # XML coverage report (for Codacy/Coveralls/etc.)
make coverage-log    # Print coverage % to console
make tsconfig        # Regenerate ConfigVals from config/config.conf via tscfg
```

## Architecture

A **Prometheus Proxy** system enabling Prometheus to scrape metrics from endpoints behind firewalls.

### Request Flow

```
Prometheus → Proxy HTTP (:8080) → AgentContext lookup → ScrapeRequest via gRPC stream
    → Agent scrapes actual endpoint → ScrapeResponse via gRPC stream → Proxy → Prometheus
```

### Core Components

1. **Proxy (`io.prometheus.Proxy`)** — Runs outside the firewall alongside Prometheus
   - `ProxyGrpcService` — accepts agent connections on port 50051
   - `ProxyHttpService` / `ProxyHttpRoutes` — serves proxied metrics on port 8080
   - `ProxyPathManager` — maps URL paths to agent contexts
   - `AgentContextManager` — tracks connected agents
   - `ScrapeRequestManager` — manages scrape request lifecycle with timeouts
   - `ProxyServiceImpl` — implements the gRPC `ProxyService` definition

2. **Agent (`io.prometheus.Agent`)** — Runs inside the firewall with monitored services
   - `AgentGrpcService` — connects to proxy, streams scrape requests/responses
   - `AgentHttpService` — scrapes actual metrics endpoints using Ktor HTTP client
   - `AgentPathManager` — manages path registrations
   - `HttpClientCache` — caches HTTP clients keyed by auth credentials (TTL/idle eviction)

3. **Common (`io.prometheus.common/`)** — shared between proxy and agent
   - `BaseOptions` — CLI argument parsing and config loading (parent of `AgentOptions` / `ProxyOptions`)
   - `ConfigVals` — type-safe config wrapper (auto-generated from HOCON via tscfg; see `make tsconfig`)
   - `ScrapeResults` — scrape response data model
   - `EnvVars` — environment variable mappings

### Public API Surface (Dokka)

Only these types are part of the supported, documented public API. Everything else is `internal`:

- `io.prometheus.Agent` (entry point + companion `main` / `startSyncAgent` / `startAsyncAgent`)
- `io.prometheus.Proxy` (entry point + companion `main`)
- `io.prometheus.agent.AgentOptions` / `io.prometheus.proxy.ProxyOptions` / `io.prometheus.common.BaseOptions`
- `io.prometheus.agent.EmbeddedAgentInfo` (handle returned by `Agent.startAsyncAgent`)
- `io.prometheus.common.EnvVars`
- `io.prometheus.common.ConfigLoadException` (thrown by `startAsyncAgent` on a config-load failure when `exitOnMissingConfig` is false, so embedded hosts can catch it instead of the JVM exiting)

When promoting a type from `internal` to `public`, also add a cross-reference to it in `docs/packages.md` (the Dokka `includes.from` file). When demoting, remove the link to avoid dangling references in the generated site.

### gRPC Service Definition

Defined in `src/main/proto/proxy_service.proto`. Key RPCs:

- `readRequestsFromProxy` — server-streaming: proxy sends scrape requests to agent
- `writeResponsesToProxy` — client-streaming: agent sends scrape responses back
- `writeChunkedResponsesToProxy` — client-streaming: chunked responses for large payloads (>32KB default)
- `registerAgent` / `registerPath` / `unregisterPath` — agent registration lifecycle
- `sendHeartBeat` — keepalive during inactivity (default 5s)

### Key Mechanisms

- **Chunking**: Large metric payloads are split into `ChunkedScrapeResponse` messages to stay within gRPC limits. Configurable via `chunkContentSizeKbs`.
- **Stale agent cleanup**: `AgentContextCleanupService` evicts inactive agents after `maxAgentInactivitySecs` (default 60s).
- **Consolidated mode**: Multiple agents can register the same path for redundancy.
- **Embedded agent**: Agents can run inside other JVM apps via `startAsyncAgent()`.

## Configuration

Uses Typesafe Config (HOCON). Precedence: CLI args → env vars → config file → built-in defaults. Reference schema: `config/config.conf`. Example configs in `examples/`.

The `ConfigVals` class is auto-generated from the HOCON schema using tscfg (`make tsconfig`).

## Build Version

`group` and `version` live in `gradle.properties` (single source of truth). The version can be overridden on the command line for CI snapshot publishing:

```bash
./gradlew build -PoverrideVersion=3.2.0-SNAPSHOT
```

`-PoverrideVersion` keeps its `override` prefix because it intentionally only applies when supplied (so the `gradle.properties` default is never accidentally cleared).

`BuildConfig.APP_RELEASE_DATE` and `BuildConfig.BUILD_TIME` are populated each build via `ValueSource`, so they reflect the actual build time and are not overridable. As a side effect, the configuration cache invalidates and `BuildConfig` regenerates on every build; release artifacts are therefore not byte-for-byte reproducible.

## Testing

- **Framework**: Kotest with JUnit 5 runner, MockK for mocking
- **Coverage**: kotlinx-kover. HTML report: `./gradlew koverHtmlReport`. XML report (CI): `./gradlew koverXmlReport`. Console summary: `./gradlew koverLog` (also runs after `koverXmlReport` / `koverVerify` via `onCheck = true`). Generated gRPC stubs, `BuildConfig`, and `ConfigVals` are excluded from report statistics (configured in `build.gradle.kts` `configureCoverage()`).

### Test Structure

Integration tests in `src/test/kotlin/io/prometheus/harness/`:
- `InProcessTest*` — uses gRPC in-process server (no network I/O, faster)
- `NettyTest*` — tests over actual network transport
- `TlsNoMutualAuthTest` / `TlsWithMutualAuthTest` — TLS communication tests
- `support/HarnessSetup.kt` — base class that sets up proxy+agent in test mode

Container tests in `src/test/kotlin/io/prometheus/containers/` — a full Testcontainers suite that builds the proxy and agent images from `etc/docker/*.df`, stands them up alongside an `nginx:1.29-alpine` metrics stub and a `prom/prometheus` container, and verifies the full Prometheus → proxy → agent → endpoint scrape path. Shared container/network/HTTP/PromQL factories live in `support/ContainerTestSupport.kt`. The specs are:
- `ContainersSmokeTest` — baseline single-metric scrape through proxy and agent
- `ContainersProxyHttpTest` — proxy/agent HTTP surfaces (registered-path scrapes, 404/503 passthrough, admin servlets, `/metrics`, service discovery)
- `ContainersConsolidatedTest` — two consolidated agents register the same path; proxy merges responses
- `ContainersLargePayloadTest` — forces the chunked + gzipped scrape path with a large synthetic payload
- `ContainersReconnectTest` — agent reconnects to a replacement proxy and scrapes resume
- `ContainersAgentTokenAuthTest` — pre-shared agent-token authentication on the gRPC channel (match + mismatch)
- `ContainersTlsTest` / `ContainersHttpsTargetTest` — TLS on the gRPC channel and HTTPS upstream targets
- `ContainersScalingTest` — parameter-driven N-agents × M-endpoints scaling (tune via `SCALE_*` env vars / `make scaling-tests`)

All container specs require Docker and are gated on `RUN_CONTAINER_TESTS=true` (set automatically by `make container-tests` / `make scaling-tests`). Default `./gradlew test` registers placeholders marked SKIPPED.

Unit tests in `src/test/kotlin/io/prometheus/{agent,proxy,common}/`. Shared test constants live in `src/test/kotlin/io/prometheus/common/TestPorts.kt` (`TestPorts` object) — canonical proxy/agent/Prometheus/nginx port numbers used across the unit, harness, and container suites; reference these instead of hard-coding port literals in new tests.

`EnvVars.getEnv()` reads `java.lang.System.getenv()`, which can't be set in-process, so its parse-and-throw branches aren't reachable by setting an env var in a test. The numeric/boolean parsing is therefore extracted into `internal` companion helpers (`parseBooleanStrict` / `parseIntStrict` / `parseLongStrict`) that the tests call directly. When adding a new typed `getEnv` overload, follow this pattern so the invalid-value path stays testable.

## Documentation Site

Documentation is built with [Zensical](https://zensical.org) (static site generator) and lives in `website/prometheus-proxy/`.

- **Config**: `website/prometheus-proxy/zensical.toml`
- **Pages**: `website/prometheus-proxy/docs/` (Markdown with pymdownx extensions)
- **Code snippets**: `src/test/kotlin/website/*.txt` (imported via `--8<--` snippet markers)
- **Local preview**: `make site` (runs `cd website/prometheus-proxy && uv run --with mkdocs-material zensical serve`)
- **CI deploy**: `.github/workflows/docs.yml` (builds and deploys to GitHub Pages)

Snippets use dual `base_path` in zensical.toml: first resolves `.txt` files from `src/test/kotlin/website/`, second resolves project-root files like `examples/*.conf`.

## Shadow JAR Service-File Merging

ShadowJar's default `DuplicatesStrategy` (EXCLUDE) drops duplicate-named entries *before* merging transformers run, so `mergeServiceFiles()` silently loses entries when grpc-core and grpc-netty-shaded both ship a same-named `META-INF/services` file — leaving the fat JAR without a DNS resolver, so the gRPC client defaults to the `unix` scheme on any non-IP hostname. The `agentJar`/`proxyJar` tasks fix this with a `filesMatching()` block that sets `DuplicatesStrategy.INCLUDE` on `META-INF/services/**` and `META-INF/*.kotlin_module` only (everything else keeps first-wins EXCLUDE semantics), letting `ServiceFileTransformer` and `KotlinModuleMetadataTransformer` merge all copies.

As a belt-and-braces guard against future Shadow regressions, the tasks also include `src/shadow/resources/META-INF/services/`, which pins `io.grpc.NameResolverProvider` (DNS + UDS) and `io.grpc.LoadBalancerProvider` (PickFirst + HealthCheckingRoundRobin). The static files don't affect the published Maven jar (they're under `src/shadow/`, not `src/main/`). If gRPC versions change provider class names, update those files to match.

## Publishing

Published to Maven Central as `com.pambrose:prometheus-proxy`. No JitPack.

Repository declarations are centralized in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)` and resolve solely from Maven Central.

Snapshot and Maven Central release Make targets (`publish-snapshot`, `publish-maven-central`) require GPG environment variables and a keychain password entry; `make check-gpg-env` validates them up-front.

When bumping the version, update `version` in `gradle.properties` and the `3.2.0` literals in `README.md` and `llms.txt` (Docker tag examples + Maven Central dependency block). The release flow itself is documented in `docs/RELEASE.md`.

## Code Style

- 2-space indentation for Kotlin, tabs for Makefiles
- Max line length: 120 characters
- Kotlinter + detekt enforce style (see `config/detekt/detekt.yml`)
- Mimic existing code patterns in nearby files
