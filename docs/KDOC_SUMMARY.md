# KDoc Documentation Summary

This document summarizes the KDoc documentation in the Prometheus Proxy codebase and the current state of test coverage.

## KDoc Coverage

### Documented Classes

KDoc documentation exists on the two top-level public classes:

#### `Agent` (`src/main/kotlin/io/prometheus/Agent.kt`)

Comprehensive KDoc (~78 lines) covering:

- **Class Purpose**: Prometheus Agent for firewall-crossing metrics collection
- **Architecture**: Connection management, path registration, scrape processing, concurrent scraping, heartbeat, metrics
  collection
- **Configuration**: HOCON config details, proxy connection settings, operational parameters
- **Usage Examples**: Basic usage, embedded usage, custom initialization
- **Connection Lifecycle**: Startup, connection, registration, operation, reconnection, shutdown
- **Error Handling**: Network issues, scrape failures, configuration errors, resource exhaustion
- **@param**: `options`, `inProcessServerName`, `testMode`, `initBlock`
- **@see**: `AgentOptions`, `Proxy`, `EmbeddedAgentInfo`

#### `Proxy` (`src/main/kotlin/io/prometheus/Proxy.kt`)

Comprehensive KDoc (~85 lines) covering:

- **Class Purpose**: Prometheus Proxy for metrics collection across firewalls
- **Architecture**: HTTP service, gRPC service, request routing, service discovery, agent management, health monitoring
- **Configuration**: HTTP/gRPC ports, admin endpoints, TLS settings, service discovery, agent cleanup
- **Usage Examples**: Basic usage, custom initialization, in-process testing
- **Request Flow**: Prometheus request, path resolution, agent communication, metric collection, response aggregation,
  Prometheus response
- **Service Discovery**: Prometheus-compatible JSON generation
- **High Availability**: Multi-proxy deployment patterns
- **@param**: `options`, `proxyPort`, `inProcessServerName`, `testMode`, `initBlock`
- **@see**: `ProxyOptions`, `Agent`

### Undocumented Classes

Internal service classes intentionally suppress documentation requirements:

- `ProxyGrpcService`, `ProxyHttpService`, `ProxyHttpRoutes` — `@Suppress("UndocumentedPublicClass")`
- `AgentGrpcService`, `AgentHttpService` — `@Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")`
- All proxy and agent utility/manager classes — no KDoc

This is a reasonable design choice: the two main entry-point classes are thoroughly documented for users and
contributors, while internal implementation details rely on clear naming and code structure.

## Test Coverage

The test suite comprises **63 test-related files** across 5 directories. See [docs/TESTING.md](docs/TESTING.md) for the
full testing guide.

### Test Files by Component

| Component | Files | Description                                                         |
|:----------|------:|:--------------------------------------------------------------------|
| Agent     |    17 | Lifecycle, gRPC streaming, HTTP scraping, options, metrics, SSL     |
| Proxy     |    21 | Lifecycle, gRPC service, HTTP routing, path management, cleanup     |
| Common    |     6 | CLI parsing, config wrappers, env vars, scrape results, utilities   |
| Misc      |     6 | Admin endpoints, ConfigVals, data classes, options integration      |
| Harness   |    13 | Integration tests (in-process, Netty, TLS) + support infrastructure |

### Testing Frameworks

- **Kotest** (StringSpec style) with JUnit 5 runner
- **Kotest matchers** (`shouldBe`, `shouldNotBeNull`, `shouldContain`, etc.)
- **MockK** (`mockk`, `every`, `verify`)
- **Kotlin Coroutines** (`runBlocking`)
- **Ktor** (client & server) for HTTP testing
- **gRPC in-process transport** for integration tests
- **Kover** for coverage reporting

### Testing Patterns

**MockK:**

```kotlin
// Relaxed mocks for complex dependencies
val mockProxy = mockk<Proxy>(relaxed = true)

// Real objects for final fields (ConfigVals)
val config = ConfigFactory.parseString(
  """
  agent { pathConfigs = [] }
  proxy {}
"""
)
val configVals = ConfigVals(config)

// Lambda mocking for metrics
every { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers {
  val block = firstArg<ProxyMetrics.() -> Unit>()
  block(mockMetrics)
}
```

**Kotest Matchers:**

```kotlin
context.shouldBeNull()
context.shouldNotBeNull()
result shouldBe expected
status shouldBe HttpStatusCode.NotFound
list.size shouldBe 3
message shouldContain "Expected text"
```

**Test Structure:**

- Descriptive test names with backticks
- AAA pattern (Arrange, Act, Assert)
- `runBlocking` for coroutine tests
- Factory methods for mock creation
- `@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")` on all test files

### Coverage

Generate coverage report:

```bash
./gradlew koverMergedHtmlReport
# Report available at build/reports/kover/html/index.html
```

Coverage excludes generated gRPC classes (`io.prometheus.grpc.*`).

## Documentation Opportunities

### Potential Additions

1. **KDoc on service classes** — `ProxyGrpcService`, `AgentGrpcService`, `ProxyHttpService`, `AgentHttpService`, etc.
2. **KDoc on manager classes** — `ProxyPathManager`, `AgentPathManager`, `AgentContextManager`, `ScrapeRequestManager`
3. **Package-level documentation** — `package-info` or Dokka module docs
4. **Dokka integration** — Add Dokka plugin to generate HTML API docs from KDoc
