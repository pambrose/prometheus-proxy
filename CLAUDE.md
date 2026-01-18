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

### Essential Commands

```bash
# Build the project (with tests)
./gradlew build

# Build without tests
./gradlew build -xtest

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
# Run Kotlin linter
./gradlew lintKotlinMain lintKotlinTest

# Run detekt static analysis
./gradlew detekt

# Format code with kotlinter
./gradlew formatKotlin
```

### Makefile Shortcuts

The project includes a Makefile with convenient shortcuts:

```bash
# Build project
make build

# Run tests
make tests

# Create distribution JARs
make jars

# Generate coverage reports
make reports

# Code linting
make lint
```

## Project Architecture

This is a **Prometheus Proxy** system that enables Prometheus to scrape metrics from endpoints behind firewalls. The
system consists of two main components:

### Core Components

1. **Proxy (`io.prometheus.Proxy`)** - Runs outside the firewall alongside Prometheus
  - Listens for agent connections on port 50051 (gRPC)
  - Serves proxied metrics on port 8080 (HTTP)
  - Manages agent contexts and path routing
  - Handles service discovery for Prometheus

2. **Agent (`io.prometheus.Agent`)** - Runs inside the firewall with monitored services
  - Connects to proxy via gRPC
  - Scrapes actual metrics endpoints
  - Registers available paths with proxy
  - Handles concurrent scraping with configurable limits

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

## Configuration

The system uses **Typesafe Config** with support for:

- HOCON (.conf files)
- JSON (.json files)
- Java Properties (.properties/.prop files)
- Environment variables
- Command-line arguments

Configuration reference is in `etc/config/config.conf`.

## Testing

- **Test Framework**: Kotest (recently migrated from Kluent)
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

## Development Notes

- **Language**: Kotlin with Java 17+ requirement
- **Key Dependencies**: gRPC, Ktor (HTTP client/server), Dropwizard Metrics, Prometheus client
- **Build Tool**: Gradle 8.x with Kotlin DSL
- **Code Style**: Uses kotlinter for formatting and detekt for static analysis
- **Logging**: Uses kotlin-logging with Logback

## Important Files

- `build.gradle.kts` - Main build configuration
- `gradle/libs.versions.toml` - Dependency version catalog
- `etc/config/config.conf` - Configuration schema and defaults
- `src/main/proto/proxy_service.proto` - gRPC service definition
- `config/detekt/detekt.yml` - Static analysis configuration

## Docker Support

The project builds multi-arch Docker images for both proxy and agent:

- `pambrose/prometheus-proxy:VERSION`
- `pambrose/prometheus-agent:VERSION`

Docker files are in `etc/docker/` directory.
