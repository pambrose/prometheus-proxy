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
make tests        # Rerun all checks (lint + tests)
make nh-tests     # Unit tests only (agent, proxy, common, misc — no harness)
make ip-tests     # In-process integration tests only
make netty-tests  # Netty integration tests only
make tls-tests    # TLS integration tests only
make coverage     # Run tests + generate HTML coverage report
make coverage-xml # XML coverage report (for Codacy/Coveralls/etc.)
make coverage-log # Print coverage % to console
make tsconfig     # Regenerate ConfigVals from config/config.conf via tscfg
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

## Reproducible Builds

`group`, `version`, and `releaseDate` live in `gradle.properties` (single source of truth). `build.gradle.kts` reads them via `providers.gradleProperty(...)`; `BuildConfig.APP_RELEASE_DATE` and `BuildConfig.BUILD_TIME` default to those values (or the local clock for `buildTime`) and can be overridden on the command line:

```bash
./gradlew build -PreleaseDate=04/25/2026 -PbuildTime=1745558400000
./gradlew build -PoverrideVersion=3.1.2-SNAPSHOT
```

Use these for CI snapshot publishing and bit-identical artifact reproduction. `-PoverrideVersion` keeps its `override` prefix because it intentionally only applies when supplied (so the `gradle.properties` default is never accidentally cleared); `-PreleaseDate` / `-PbuildTime` map directly to the underlying property names.

## Testing

- **Framework**: Kotest with JUnit 5 runner, MockK for mocking
- **Coverage**: kotlinx-kover. HTML report: `./gradlew koverHtmlReport`. XML report (CI): `./gradlew koverXmlReport`. Console summary: `./gradlew koverLog` (also runs after `koverXmlReport` / `koverVerify` via `onCheck = true`). Generated gRPC stubs, `BuildConfig`, and `ConfigVals` are excluded from report statistics (configured in `build.gradle.kts` `configureCoverage()`).

### Test Structure

Integration tests in `src/test/kotlin/io/prometheus/harness/`:
- `InProcessTest*` — uses gRPC in-process server (no network I/O, faster)
- `NettyTest*` — tests over actual network transport
- `TlsNoMutualAuthTest` / `TlsWithMutualAuthTest` — TLS communication tests
- `support/HarnessSetup.kt` — base class that sets up proxy+agent in test mode

Unit tests in `src/test/kotlin/io/prometheus/{agent,proxy,common}/`.

## Documentation Site

Documentation is built with [Zensical](https://zensical.org) (static site generator) and lives in `website/prometheus-proxy/`.

- **Config**: `website/prometheus-proxy/zensical.toml`
- **Pages**: `website/prometheus-proxy/docs/` (Markdown with pymdownx extensions)
- **Code snippets**: `src/test/kotlin/website/*.txt` (imported via `--8<--` snippet markers)
- **Local preview**: `make site` (runs `cd website/prometheus-proxy && uv run zensical serve`)
- **CI deploy**: `.github/workflows/docs.yml` (builds and deploys to GitHub Pages)

Snippets use dual `base_path` in zensical.toml: first resolves `.txt` files from `src/test/kotlin/website/`, second resolves project-root files like `examples/*.conf`.

## Publishing

Published to Maven Central as `com.pambrose:prometheus-proxy`. No JitPack.

Repository declarations are centralized in `settings.gradle.kts` via `dependencyResolutionManagement(FAIL_ON_PROJECT_REPOS)` and resolve solely from Maven Central.

Snapshot and Maven Central release Make targets (`publish-snapshot`, `publish-maven-central`) require GPG environment variables and a keychain password entry; `make check-gpg-env` validates them up-front.

When bumping the version, update `version` in `gradle.properties` and the `3.1.2` literals in `README.md` and `llms.txt` (Docker tag examples + Maven Central dependency block). The release flow itself is documented in `docs/RELEASE.md`.

## Code Style

- 2-space indentation for Kotlin, tabs for Makefiles
- Max line length: 120 characters
- Kotlinter + detekt enforce style (see `config/detekt/detekt.yml`)
- Mimic existing code patterns in nearby files
