<!-- OPENSPEC:START -->
# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:
- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:
- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

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
./gradlew koverMergedHtmlReport          # Code coverage report
```

### Linting and Formatting

```bash
./gradlew lintKotlinMain lintKotlinTest  # Run kotlinter linter
./gradlew detekt                         # Run detekt static analysis
./gradlew formatKotlin                   # Auto-format code
```

Always run lint and build before completing tasks:
`./gradlew detekt && ./gradlew lintKotlinMain && ./gradlew build -xtest`

## Architecture

A **Prometheus Proxy** system enabling Prometheus to scrape metrics from endpoints behind firewalls.

### Request Flow

```
Prometheus → Proxy HTTP (:8080) → AgentContext lookup → ScrapeRequest via gRPC stream
    → Agent scrapes actual endpoint → ScrapeResponse via gRPC stream → Proxy → Prometheus
```

### Core Components

**Proxy (`io.prometheus.Proxy`)** — runs outside the firewall alongside Prometheus:

- `ProxyGrpcService` — accepts agent connections on port 50051
- `ProxyHttpService` / `ProxyHttpRoutes` — serves proxied metrics on port 8080
- `ProxyPathManager` — maps URL paths to agent contexts
- `AgentContextManager` — tracks connected agents
- `ScrapeRequestManager` — manages scrape request lifecycle with timeouts
- `ProxyServiceImpl` — implements the gRPC `ProxyService` definition

**Agent (`io.prometheus.Agent`)** — runs inside the firewall with monitored services:

- `AgentGrpcService` — connects to proxy, streams scrape requests/responses
- `AgentHttpService` — scrapes actual metrics endpoints using Ktor HTTP client
- `AgentPathManager` — manages path registrations
- `HttpClientCache` — caches HTTP clients keyed by auth credentials (TTL/idle eviction)

**Common (`io.prometheus.common/`)** — shared between proxy and agent:

- `BaseOptions` — CLI argument parsing and config loading
- `ConfigVals` — type-safe config wrapper (generated from HOCON via tscfg)
- `ScrapeResults` — scrape response data model
- `EnvVars` — environment variable mappings

### gRPC Service Definition

Defined in `src/main/proto/proxy_service.proto`. Key RPCs:

- `readRequestsFromProxy` — server-streaming: proxy sends scrape requests to agent
- `writeResponsesToProxy` — client-streaming: agent sends scrape responses back
- `writeChunkedResponsesToProxy` — client-streaming: chunked responses for large payloads (>32KB default)
- `registerAgent` / `registerPath` / `unregisterPath` — agent registration lifecycle
- `sendHeartBeat` — keepalive during inactivity (default 5s)
- `connectAgentWithTransportFilterDisabled` — for nginx reverse proxy scenarios

### Key Mechanisms

- **Chunking**: Large metric payloads are split into `ChunkedScrapeResponse` messages to stay within gRPC limits.
  Configurable via `chunkContentSizeKbs`.
- **Stale agent cleanup**: `AgentContextCleanupService` evicts inactive agents after `maxAgentInactivitySecs` (default
  60s).
- **Consolidated mode**: Multiple agents can register the same path for redundancy.
- **Embedded agent**: Agents can run inside other JVM apps via `startAsyncAgent()`.

### Configuration

Uses Typesafe Config (HOCON). Precedence: CLI args → env vars → config file. Reference schema: `etc/config/config.conf`.
Example configs in `examples/`.

The `ConfigVals` class is auto-generated from the HOCON schema using tscfg (`make tsconfig`).

## Testing

- **Framework**: Kotest with JUnit 5 runner, MockK for mocking
- **Coverage**: Kover

### Test Structure

Integration tests in `src/test/kotlin/io/prometheus/harness/`:

- `InProcessTest*` — uses gRPC in-process server (no network I/O, faster)
- `NettyTest*` — tests over actual network transport
- `TlsNoMutualAuthTest` / `TlsWithMutualAuthTest` — TLS communication tests
- `support/HarnessSetup.kt` — base class that sets up proxy+agent in test mode

Unit tests in `src/test/kotlin/io/prometheus/{agent,proxy,common}/`.

## Code Style

- 2-space indentation for Kotlin, tabs for Makefiles
- Max line length: 120 characters
- Kotlinter + detekt enforce style (see `config/detekt/detekt.yml`)
- Never add comments unless requested
- Mimic existing code patterns in nearby files
