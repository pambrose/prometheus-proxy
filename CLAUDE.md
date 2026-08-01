# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build System and Common Commands

Gradle with Kotlin DSL. Java 17+ required. All commands from project root.

```bash
./gradlew build                          # Build with tests
./gradlew build -x test                  # Build without tests
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
`./gradlew detekt && ./gradlew lintKotlinMain && ./gradlew build -x test`

### Useful Make Targets

Run `make help` for the current target list with descriptions (auto-extracted from `## …` annotations in the
`Makefile`). The container and scaling targets need Docker.

## Architecture

A **Prometheus Proxy** system enabling Prometheus to scrape metrics from endpoints behind firewalls.

### Request Flow

```
Prometheus → Proxy HTTP (:8080) → AgentContext lookup → ScrapeRequest via gRPC stream
    → Agent scrapes actual endpoint → ScrapeResponse via gRPC stream → Proxy → Prometheus
```

### Core Components

Source lives under `src/main/kotlin/io/prometheus/{proxy,agent,common}/`. The proxy runs outside the firewall
alongside Prometheus, the agent runs inside it next to the monitored services, and `common/` holds what both
share (notably `BaseOptions`, the parent of `AgentOptions` / `ProxyOptions`, and `ConfigVals`, auto-generated
from HOCON via tscfg — see `make tsconfig`).

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

Defined in `src/main/proto/proxy_service.proto` — read it for the current RPC set. Note the defaults that
aren't in the proto: chunked responses kick in above 32KB, and the heartbeat fires every 5s during inactivity.

### Key Mechanisms

- **Chunking**: Large metric payloads are split into `ChunkedScrapeResponse` messages to stay within gRPC limits. Configurable via `chunkContentSizeKbs`.
- **Stale agent cleanup**: `AgentContextCleanupService` evicts inactive agents after `maxAgentInactivitySecs` (default 60s).
- **Consolidated mode**: Multiple agents can register the same path for redundancy.
- **Embedded agent**: Agents can run inside other JVM apps via `startAsyncAgent()`.
- **Proxy failover**: The agent rotates through an ordered `agent.proxy.endpoints` list (`AgentGrpcService.advanceEndpoint` / `resetEndpoint`); failed connects advance, dropped connections retry from the head.
- **Dynamic target discovery**: `PathDiscoveryService` polls a watched file and calls `AgentPathManager.reconcileDiscoveredPaths`; paths are tagged `STATIC`/`DISCOVERED`, and static entries are never touched.
- **Per-agent auth**: `AgentAuthManager` + `AgentAuthServerInterceptor` resolve `proxy.auth` identity tokens and enforce path globs on `registerPath`; legacy `proxy.agentToken` maps to an allow-all identity.
- **Metric filtering**: `MetricFilter` (per-path `agent.filters`) drops whole metric families in `AgentHttpService` before gzip/chunking; fails open on non-text or non-UTF-8 payloads.
- **Dashboard**: Ktor + htmx (WebJar, no CDN) on its own port, fed by the `ProxyEvent` bus; see `proxy/dashboard/`.

### Dashboard Design Constraints

`DESIGN.md` is the visual contract (color tokens, typography roles, components) and `PRODUCT.md` the product one; both are tracked, while the tooling that generated them is gitignored. Three constraints in `ProxyDashboardHtml.kt` are load-bearing and easy to undo by accident:

- **The live region must stay outside every `hx-swap-oob` region.** A region the push loop rewrites re-announces itself on every frame. `ProxyDashboardHtmlTest` pins this.
- **Check a color against `--surface-2`, not `--surface`.** Every token also lands on the tighter ground under tables and section headers, which is where all 29 of the contrast failures fixed in 4.0.1 lived. The measured AA floor is 4.59:1 — `DESIGN.md` records the per-token values.
- **Both layouts must render agent identity through `agentLabel()`.** The agent and path views are read against each other, so a divergent label (or a raw `agentId`) defeats the correlation they exist for.

## Configuration

Uses Typesafe Config (HOCON). Precedence: CLI args → env vars → config file → built-in defaults. Reference schema: `config/config.conf`. Example configs in `examples/`.

The `ConfigVals` class is auto-generated from the HOCON schema using tscfg (`make tsconfig`).

## Build Version

`group` and `version` live in `gradle.properties` (single source of truth). The version can be overridden on the command line for CI snapshot publishing:

```bash
./gradlew build -PoverrideVersion=4.0.1-SNAPSHOT
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

Container tests in `src/test/kotlin/io/prometheus/containers/` — a full Testcontainers suite that builds the proxy and agent images from `etc/docker/*.df`, stands them up alongside an `nginx:1.29-alpine` metrics stub and a `prom/prometheus` container, and verifies the full Prometheus → proxy → agent → endpoint scrape path. Shared container/network/HTTP/PromQL factories live in `support/ContainerTestSupport.kt`; `ls` that directory
for the current spec list.

All container specs require Docker and are gated on `RUN_CONTAINER_TESTS=true` (set automatically by `make container-tests` / `make scaling-tests`). Default `./gradlew test` registers placeholders marked SKIPPED.

Unit tests in `src/test/kotlin/io/prometheus/{agent,proxy,common}/`. Shared test constants live in `src/test/kotlin/io/prometheus/common/TestPorts.kt` (`TestPorts` object) — canonical proxy/agent/Prometheus/nginx port numbers used across the unit, harness, and container suites; reference these instead of hard-coding port literals in new tests.

`EnvVars.getEnv()` reads `java.lang.System.getenv()`, which can't be set in-process, so its parse-and-throw branches aren't reachable by setting an env var in a test. The numeric/boolean parsing is therefore extracted into `internal` companion helpers (`parseBooleanStrict` / `parseIntStrict` / `parseLongStrict`) that the tests call directly. When adding a new typed `getEnv` overload, follow this pattern so the invalid-value path stays testable.

## Shadow JAR Service-File Merging

ShadowJar's default `DuplicatesStrategy` (EXCLUDE) drops duplicate-named entries *before* merging transformers run, so `mergeServiceFiles()` silently loses entries when grpc-core and grpc-netty-shaded both ship a same-named `META-INF/services` file — leaving the fat JAR without a DNS resolver, so the gRPC client defaults to the `unix` scheme on any non-IP hostname. The `agentJar`/`proxyJar` tasks fix this with a `filesMatching()` block that sets `DuplicatesStrategy.INCLUDE` on `META-INF/services/**` and `META-INF/*.kotlin_module` only (everything else keeps first-wins EXCLUDE semantics), letting `ServiceFileTransformer` and `KotlinModuleMetadataTransformer` merge all copies.

As a belt-and-braces guard against future Shadow regressions, the tasks also include `src/shadow/resources/META-INF/services/`, which pins `io.grpc.NameResolverProvider` (DNS + UDS) and `io.grpc.LoadBalancerProvider` (PickFirst + HealthCheckingRoundRobin). The static files don't affect the published Maven jar (they're under `src/shadow/`, not `src/main/`). If gRPC versions change provider class names, update those files to match.

## Code Style

Formatting is pinned in `.editorconfig` and enforced by kotlinter + detekt (`config/detekt/detekt.yml`) — don't
restate those rules here. Beyond them: mimic existing code patterns in nearby files.
