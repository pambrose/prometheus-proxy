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

## Build System and Common Commands

This project uses **Gradle** with Kotlin DSL for build management. All commands should be run from the project root.

Gradle with Kotlin DSL. Java 17+ required. All commands from project root.

### Essential Commands

```bash
# Build the project (with tests)
./gradlew build

# Build without tests
./gradlew build -x test

# Run tests
./gradlew test

# Run specific test
./gradlew test --tests "TestClassName"

# Generate JAR files for proxy and agent
./gradlew agentJar proxyJar

# Clean build artifacts
./gradlew clean

# Check for dependency updates
./gradlew dependencyUpdates

# Generate code coverage report
./gradlew koverMergedHtmlReport
```

### Code Quality and Linting

```bash
./gradlew lintKotlinMain lintKotlinTest  # Run kotlinter linter
./gradlew detekt                         # Run detekt static analysis
./gradlew formatKotlin                   # Auto-format code
```

Always run lint and build before completing tasks:
`./gradlew detekt && ./gradlew lintKotlinMain && ./gradlew build -xtest`

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

## Architecture

A **Prometheus Proxy** system enabling Prometheus to scrape metrics from endpoints behind firewalls.

## Project Architecture

This is a **Prometheus Proxy** system that enables Prometheus to scrape metrics from endpoints behind firewalls. The
system consists of two main components:

### Request Flow

```
Prometheus → Proxy HTTP (:8080) → AgentContext lookup → ScrapeRequest via gRPC stream
    → Agent scrapes actual endpoint → ScrapeResponse via gRPC stream → Proxy → Prometheus
```

### Core Components

1. **Proxy (`io.prometheus.Proxy`)** - Runs outside the firewall alongside Prometheus
  - Listens for agent connections on port 50051 (gRPC)
  - Serves proxied metrics on port 8080 (HTTP)
  - Manages agent contexts and path routing
  - Handles service discovery for Prometheus

- `ProxyGrpcService` — accepts agent connections on port 50051
- `ProxyHttpService` / `ProxyHttpRoutes` — serves proxied metrics on port 8080
- `ProxyPathManager` — maps URL paths to agent contexts
- `AgentContextManager` — tracks connected agents
- `ScrapeRequestManager` — manages scrape request lifecycle with timeouts
- `ProxyServiceImpl` — implements the gRPC `ProxyService` definition

2. **Agent (`io.prometheus.Agent`)** - Runs inside the firewall with monitored services
  - Connects to proxy via gRPC
  - Scrapes actual metrics endpoints
  - Registers available paths with proxy
  - Handles concurrent scraping with configurable limits

- `AgentGrpcService` — connects to proxy, streams scrape requests/responses
- `AgentHttpService` — scrapes actual metrics endpoints using Ktor HTTP client
- `AgentPathManager` — manages path registrations
- `HttpClientCache` — caches HTTP clients keyed by auth credentials (TTL/idle eviction)

**Common (`io.prometheus.common/`)** — shared between proxy and agent:

- `BaseOptions` — CLI argument parsing and config loading
- `ConfigVals` — type-safe config wrapper (generated from HOCON via tscfg)
- `ScrapeResults` — scrape response data model
- `EnvVars` — environment variable mappings

### Key Architectural Patterns

- **gRPC Communication**: Agents connect to proxy using gRPC with optional TLS
- **Coroutine-based Concurrency**: Heavy use of Kotlin coroutines for async operations
- **Service Discovery**: Built-in Prometheus service discovery endpoint support
- **Health Checks**: Comprehensive health checking via Dropwizard metrics
- **Configuration**: Uses Typesafe Config (HOCON) for flexible configuration

### Package Structure

- `io.prometheus.common/` - Shared utilities, configuration, and constants
- `io.prometheus.proxy/` - Proxy-specific components (HTTP service, gRPC service, path management)
- `io.prometheus.agent/` - Agent-specific components (HTTP client, gRPC client, path management)

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

## Configuration

The system uses **Typesafe Config** with support for:

- HOCON (.conf files)
- JSON (.json files)
- Java Properties (.properties/.prop files)
- Environment variables
- Command-line arguments

Configuration reference is in `config/config.conf`.

Uses Typesafe Config (HOCON). Precedence: CLI args → env vars → config file. Reference schema: `etc/config/config.conf`.
Example configs in `examples/`.

The `ConfigVals` class is auto-generated from the HOCON schema using tscfg (`make tsconfig`).

## Testing

- **Framework**: Kotest with JUnit 5 runner, MockK for mocking
- **Coverage**: Kover
- **Mocking Framework**: MockK for Kotlin-friendly mocking
- **Test Location**: `src/test/kotlin/`
- **Coverage**: Uses Kover for coverage reporting
- **Test Types**: Unit tests, integration tests, and TLS tests

### Testing with DI and MockK

The codebase now supports Dependency Injection with MockK for better testing:

```bash
# Run tests with coverage
./gradlew test

# Run tests with coverage report
./gradlew koverMergedHtmlReport
```

Example test with MockK:

```kotlin
val mockService = mockk<AgentGrpcService>(relaxed = true)
every { mockService.connectAgent(any()) } returns true

val factory = TestAgentFactory()
val agent = factory.createTestAgent(
  options = testOptions,
  grpcService = mockService
)

verify { mockService.connectAgent(any()) }
```

### Test Structure

Integration tests in `src/test/kotlin/io/prometheus/harness/`:

- `InProcessTest*` — uses gRPC in-process server (no network I/O, faster)
- `NettyTest*` — tests over actual network transport
- `TlsNoMutualAuthTest` / `TlsWithMutualAuthTest` — TLS communication tests
- `support/HarnessSetup.kt` — base class that sets up proxy+agent in test mode

Unit tests in `src/test/kotlin/io/prometheus/{agent,proxy,common}/`.

## Development Notes

- **Language**: Kotlin with Java 17+ requirement
- **Key Dependencies**: gRPC, Ktor (HTTP client/server), Dropwizard Metrics, Prometheus client
- **Build Tool**: Gradle 8.x with Kotlin DSL
- **Code Style**: Uses kotlinter for formatting and detekt for static analysis
- **Logging**: Uses kotlin-logging with Logback

## Important Files

- `build.gradle.kts` - Main build configuration
- `gradle/libs.versions.toml` - Dependency version catalog
- `config/config.conf` - Configuration schema and defaults
- `src/main/proto/proxy_service.proto` - gRPC service definition
- `etc/detekt/detekt.yml` - Static analysis configuration

## Docker Support

The project builds multi-arch Docker images for both proxy and agent:

- `pambrose/prometheus-proxy:VERSION`
- `pambrose/prometheus-agent:VERSION`

Docker files are in `etc/docker/` directory.

## Code Style

- 2-space indentation for Kotlin, tabs for Makefiles
- Max line length: 120 characters
- Kotlinter + detekt enforce style (see `etc/detekt/detekt.yml`)
- Always add comments where appropriate
- Mimic existing code patterns in nearby files
