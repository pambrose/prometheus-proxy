# GEMINI.md

## Project Overview

**Prometheus Proxy** is a Kotlin-based tool designed to enable Prometheus to scrape metrics from endpoints located
behind a firewall. It maintains the native pull-based model of Prometheus while solving connectivity issues caused by
network boundaries.

### Architecture

The system consists of two primary components:

- **Proxy**: Runs in the same network domain as the Prometheus server (outside the firewall). It accepts HTTP scrape
  requests from Prometheus and proxies them to the appropriate Agent.
- **Agent**: Runs inside the firewall alongside the monitored services. It initiates and maintains a persistent gRPC
  connection to the Proxy. When the Proxy receives a scrape request, it forwards it to the Agent, which performs the
  actual scrape and returns the metrics via gRPC.

### Main Technologies

- **Language**: Kotlin 1.9+ (targeted at JVM 17)
- **Communication**: gRPC and Protocol Buffers (Protobuf)
- **Build System**: Gradle (Kotlin DSL)
- **Configuration**: HOCON (Typesafe Config)
- **Web/HTTP**: Ktor, Jetty, and Netty
- **Testing**: JUnit 5, Kotest (StringSpec), and Mockk
- **Linting/Static Analysis**: Kotlinter (ktlint) and Detekt

---

## Building and Running

### Prerequisites

- Java 17 or newer

### Key Gradle Commands

- **Build Project**: `./gradlew build` (runs tests, linting, and creates JARs)
- **Run Tests**: `./gradlew test`
- **Linting**: `./gradlew lintKotlin` and `./gradlew detekt`
- **Clean**: `./gradlew clean`
- **Create Shadow JARs**: `./gradlew shadowJar` (generates fat JARs for Proxy and Agent)

### Running the Services

After building, the JAR files are located in `build/libs/`:

- **Proxy**: `java -jar build/libs/prometheus-proxy.jar`
- **Agent**: `java -jar build/libs/prometheus-agent.jar --config <config_path_or_url>`

---

## Development Conventions

### Code Style

- Follows standard Kotlin conventions, enforced by **ktlint** via the `kotlinter` plugin.
- **Detekt** is used for additional static analysis.
- Configuration for Detekt is in `config/detekt/detekt.yml`.

### Testing Practices

- Tests are located in `src/test/kotlin`.
- **Kotest** with `StringSpec` is the preferred testing style.
- **Mockk** is used for mocking dependencies.
- Use `inProcessServerName` in `Proxy` and `Agent` constructors for integration tests without real network overhead.

### Configuration

- Default configuration values are in `etc/config/config.conf`.
- Configuration supports HOCON, JSON, and Java properties.
- Environment variables (e.g., `PROXY_CONFIG`, `AGENT_CONFIG`) and CLI arguments can override file-based settings.

---

## Key Directories and Files

- `src/main/kotlin/io/prometheus/Proxy.kt`: Main entry point for the Proxy service.
- `src/main/kotlin/io/prometheus/Agent.kt`: Main entry point for the Agent service.
- `src/main/proto/proxy_service.proto`: gRPC service and message definitions.
- `etc/config/config.conf`: Comprehensive configuration reference.
- `docs/`: Additional documentation and architecture diagrams.
- `examples/`: Sample HOCON configuration files for various use cases.
- `testing/certs/`: Certificates for testing TLS/mTLS.

## Known Issues

A list of identified bugs and potential improvements can be found in [gemini-bugs.md](gemini-bugs-2-13-26.md).
