# Project Context

## Purpose

Prometheus Proxy is a distributed metrics collection system that enables Prometheus to scrape metrics from endpoints
behind firewalls. The system solves the challenge of monitoring services in private networks without exposing them
directly to external Prometheus instances.

**Key Goals:**

- Enable Prometheus to monitor services behind firewalls without VPN or direct network access
- Provide a secure, efficient proxy mechanism using gRPC for agent-to-proxy communication
- Support service discovery for dynamic infrastructure
- Maintain high performance with concurrent scraping and connection management
- Offer flexible configuration and deployment options (standalone, Docker, Kubernetes)

## Tech Stack

- **Language**: Kotlin 2.3.x with Java 17+ requirement
- **Build System**: Gradle 9.x with Kotlin DSL
- **RPC Framework**: gRPC (protobuf) for proxy-agent communication
- **HTTP Framework**: Ktor for both client (agent scraping) and server (proxy serving)
- **Metrics**: Prometheus Java Client, Dropwizard Metrics for health checks
- **Configuration**: Typesafe Config (HOCON, JSON, properties support)
- **Concurrency**: Kotlin Coroutines with structured concurrency
- **Logging**: kotlin-logging with Logback backend
- **Testing**: Kotest (test framework), MockK (mocking), Kover (coverage)
- **Code Quality**: kotlinter (formatting), detekt (static analysis)
- **Deployment**: Multi-arch Docker images (proxy and agent)

## Project Conventions

### Code Style

- **Formatter**: Uses kotlinter plugin with standard Kotlin formatting conventions
- **Linter**: detekt for static analysis (config: `config/detekt/detekt.yml`)
- **Commands**:
  - `./gradlew formatKotlin` to format code
  - `./gradlew lintKotlinMain lintKotlinTest` to lint
  - `./gradlew detekt` for static analysis
- **Naming**: Standard Kotlin conventions (camelCase for functions/properties, PascalCase for classes)
- **Package Structure**: Organized by component (`io.prometheus.proxy`, `io.prometheus.agent`, `io.prometheus.common`)
- **Avoid**: Premature abstractions, over-engineering, unnecessary comments or docstrings for self-evident code

### Architecture Patterns

- **Proxy-Agent Architecture**: Two-component system with gRPC-based communication
  - **Proxy** (`io.prometheus.Proxy`): Runs outside firewall, receives agent connections, serves metrics to Prometheus
  - **Agent** (`io.prometheus.Agent`): Runs inside firewall, connects to proxy, scrapes local endpoints
- **Coroutine-based Concurrency**: Heavy use of structured concurrency with `CoroutineScope`, `launch`, `async`
- **Service Discovery**: Built-in Prometheus service discovery endpoint support for dynamic targets
- **Health Checks**: Comprehensive health monitoring via Dropwizard metrics
- **Dependency Injection**: Supports constructor-based DI with factory patterns for testing
- **Configuration Management**: Centralized config handling with Typesafe Config (HOCON)
- **Path-based Routing**: Agent paths registered with proxy for request routing
- **TLS Support**: Optional TLS for gRPC connections between agents and proxy

### Testing Strategy

- **Framework**: Kotest (recently migrated from Kluent)
- **Mocking**: MockK for Kotlin-friendly mocking with relaxed mocks
- **Coverage**: Kover for code coverage reporting
- **Test Types**:
  - Unit tests for individual components
  - Integration tests for end-to-end flows
  - TLS tests for secure communication
- **Commands**:
  - `./gradlew test` - run all tests
  - `./gradlew test --tests "TestClassName"` - run specific test
  - `./gradlew koverMergedHtmlReport` - generate coverage report
- **Test Location**: `src/test/kotlin/` with test resources in `src/test/resources/`
- **DI in Tests**: Use factory patterns to inject mocked dependencies for better testability

### Git Workflow

- **Main Branch**: `master` (use this for PRs)
- **Feature Branches**: Named after version or feature (e.g., `2.4.1`)
- **Commit Style**: Descriptive commit messages focusing on "why" not "what"
- **Build Requirements**: Code must build and pass tests before committing
- **Version Management**: Semantic versioning, versions tracked in build files

## Domain Context

**Prometheus Metrics Collection**:

- Prometheus uses a pull model to scrape metrics from HTTP endpoints
- Metrics format: plain text exposition format (metric name, labels, value)
- Common scrape endpoints: `/metrics`
- Challenge: Prometheus can't reach endpoints behind firewalls/NAT

**How Prometheus Proxy Works**:

1. Agent runs inside firewall, connects to proxy via gRPC (outbound connection)
2. Agent registers available scrape paths with proxy
3. Proxy exposes HTTP endpoints that Prometheus can scrape
4. When Prometheus scrapes proxy, proxy requests metrics from agent via gRPC
5. Agent fetches metrics from actual endpoint and returns to proxy
6. Proxy serves metrics to Prometheus

**Key Metrics Concepts**:

- **Scrape interval**: How often Prometheus collects metrics (typically 15-60 seconds)
- **Scrape timeout**: Maximum time allowed for a scrape operation
- **Service discovery**: Dynamic registration of targets (file-based, Kubernetes, etc.)
- **Path routing**: Mapping proxy endpoints to agent scrape targets

## Important Constraints

- **Java Version**: Requires Java 17 or higher
- **Kotlin Version**: Must stay on latest stable Kotlin version (currently 2.3.x)
- **Backward Compatibility**: Avoid breaking changes in configuration format or gRPC protocol
- **Performance**: Must handle concurrent scrapes efficiently (agent has configurable concurrency limits)
- **Network**: Agents must be able to establish outbound gRPC connections to proxy
- **Security**: TLS support required for production deployments
- **No Breaking Changes**: Don't rename unused variables, add compatibility shims, or make breaking config changes
  without version bumps

## External Dependencies

- **gRPC Services**: Agent-to-proxy communication via gRPC protocol (defined in `src/main/proto/proxy_service.proto`)
- **Prometheus Server**: External Prometheus instance scrapes proxy HTTP endpoints
- **Target Endpoints**: Internal services being monitored (agents scrape these)
- **Service Discovery**: Optional integration with Prometheus service discovery mechanisms
- **Metrics Libraries**:
  - Prometheus Java Client for metrics exposition
  - Dropwizard Metrics for internal health monitoring
- **Docker Registry**: Images published to `pambrose/prometheus-proxy` and `pambrose/prometheus-agent`
- **Configuration Sources**: Supports loading from files, environment variables, and command-line arguments
