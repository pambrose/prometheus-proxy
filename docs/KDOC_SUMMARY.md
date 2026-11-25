# KDoc Documentation Summary

This document summarizes the KDoc documentation in the Prometheus Proxy codebase and the current state of test coverage.

## KDoc Coverage

### Documented Classes

KDoc documentation exists on the two top-level public classes, `Agent` and `Proxy`, and on the embedded agent's
public API, `EmbeddedAgentConfig`, `MetricsSource`, and `EmbeddedAgentInfo` (below them). The other public types,
`AgentOptions`, `ProxyOptions`, `BaseOptions`, `EnvVars`, and `ConfigLoadException`, carry KDoc too, and
[`docs/packages.md`](packages.md) describes each:

#### `Agent` (`src/main/kotlin/io/prometheus/Agent.kt`)

Comprehensive KDoc (~87 lines) covering:

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

Comprehensive KDoc (~94 lines) covering:

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

#### `EmbeddedAgentConfig` (`src/main/kotlin/io/prometheus/agent/EmbeddedAgentConfig.kt`)

KDoc on the class and each of its members, its `Builder` and every builder method and property, and
`embeddedAgent`, covering:

- **Class Purpose**: an embedded agent's settings, built in code with a typed method for each and held in memory,
  started with `start()`, which behaves as `Agent.startAsyncAgent(config)` does: built-in defaults, single settings'
  environment variables first, and `ConfigLoadException` on a setting the agent can't use. `startIfConfigured()`
  starts it only where `namesProxy()` finds a proxy, and returns null, logging why, when there's none or it can't
  start; `toConfig()` gives the tree, and `toString()` the settings with their secrets masked
- **Usage Examples**: `embeddedAgent { }` from Kotlin, with `builder()` named for Java, and a worker that reads its
  proxy from the environment and serves its registry and the agent's own metrics in-process
- **Builder**: the `name`, `proxy`, `token`, `consolidated`, `agentMetricsPort`, and `dashboardPort` properties; TLS
  from PEM (`trustCertificatePem`, `clientCertificatePem`); the proxy, token, and certificate from the app's
  environment with `fromEnvironment()` (`PROMETHEUS_PROXY`, `PROMETHEUS_AGENT_TOKEN`, `PROMETHEUS_PROXY_CERT`, and
  `PROMETHEUS_PROXY_AUTHORITY`); paths scraped with `path` and `scrapeSelf` (credentials percent-encoded into the
  URL) or served in-process with `source` (a `MetricsSource` or a `PrometheusRegistry`); the agent's own metrics on a
  port with `agentMetrics(port)` or at a path in-process with `agentMetricsAt(path)`; its dashboard with `dashboard()`
  and `dashboardLogin()`; `setting(key, value)` and its precedence; and the values the builder refuses as they're
  given, with `ConfigLoadException`

#### `MetricsSource` (`src/main/kotlin/io/prometheus/agent/MetricsSource.kt`)

KDoc on the interface, `write`, and `of`, covering:

- **Purpose**: a host's metrics, read in-process for a path in place of a URL, given to the builder's `source()`
- **Contract**: called for each scrape, and each dashboard's Scrape now, on a thread it can block and from several at
  once, within the agent's size limit and the path's filter; interrupted at the scrape timeout; whatever it throws,
  short of a `VirtualMachineError`, fails only that scrape
- **write**: its `accept` and `names` (`name[]`) parameters, the content type it returns, and its `IOException` and
  `InterruptedException`
- **of**: a source of a `PrometheusRegistry`, which leaves out the agent's own metrics

#### `EmbeddedAgentInfo` (`src/main/kotlin/io/prometheus/agent/EmbeddedAgentInfo.kt`)

KDoc on the class and each member, covering:

- **Class Purpose**: the handle `Agent.startAsyncAgent`, `EmbeddedAgentConfig.start()`, and `startIfConfigured()`
  return, the supported surface for an agent the host didn't construct
- **Usage Examples**: starting, logging its identity, and `shutdown()`; and `use { }` on what `startIfConfigured()`
  returns, as it's `AutoCloseable`
- **Members**: `launchId`, `agentName`, `isConnected` and `proxyEndpoint` (its connection, read live for a health
  check), `shutdown()`, and `close()`, which calls it
- **@property**: `launchId`, `agentName`

### Documented Internal Classes

All 16 internal service/manager classes have concise KDoc (5–15 lines each) covering purpose,
responsibilities, constructor parameters, and cross-references:

#### Proxy-side (11 classes)

| Class                        | Summary                                                                  |
|:-----------------------------|:-------------------------------------------------------------------------|
| `ProxyGrpcService`           | gRPC server lifecycle, TLS, interceptors, keepalive                      |
| `ProxyHttpService`           | Ktor HTTP server for Prometheus scrape requests                          |
| `ProxyHttpRoutes`            | HTTP routing, service discovery, scrape recording                        |
| `ScrapeDispatch`             | Sends a scrape to a path's agents over gRPC and merges their answers     |
| `ProxyServiceImpl`           | gRPC `ProxyService` implementation (registration, streaming, heartbeats) |
| `ProxyPathManager`           | Path-to-agent mapping, consolidated/exclusive modes                      |
| `AgentContextManager`        | Agent context and chunked context registries                             |
| `ScrapeRequestManager`       | In-flight scrape request tracking and result assignment                  |
| `AgentContext`               | Connected agent state, scrape queue, activity tracking                   |
| `ChunkedContext`             | Chunked response reassembly with CRC32 validation                        |
| `AgentContextCleanupService` | Background stale-agent eviction                                          |

#### Agent-side (5 classes)

| Class                    | Summary                                                       |
|:-------------------------|:--------------------------------------------------------------|
| `AgentGrpcService`       | gRPC client, channel/stub management, streaming, reconnection |
| `AgentHttpService`       | HTTP scraping of metrics endpoints, builds `ScrapeResults`    |
| `AgentPathManager`       | Path registration lifecycle with the proxy                    |
| `AgentConnectionContext` | Bidirectional channels for a single connection                |
| `HttpClientCache`        | LRU cache with TTL/idle eviction, credential masking          |

## Test Coverage

The test suite is organized by component under `src/test/kotlin/io/prometheus/`. See [TESTING.md](TESTING.md) for
the full testing guide, including an inventory of every spec.

### Test Files by Component

| Component  | Description                                                                            |
|:-----------|:---------------------------------------------------------------------------------------|
| Agent      | Lifecycle, gRPC streaming, HTTP scraping, endpoint failover, discovery, filtering, SSL |
| Proxy      | Lifecycle, gRPC service, HTTP routing, path management, agent auth, cleanup, dashboard |
| Common     | CLI parsing, config wrappers, env vars, scrape results, utilities                      |
| Misc       | Admin endpoints, ConfigVals, data classes, options integration                         |
| Harness    | Integration tests (in-process, Netty, TLS), targeted specs + support infrastructure    |
| Containers | Testcontainers end-to-end suite, gated on `RUN_CONTAINER_TESTS`                        |

### Testing Frameworks

- **Kotest** (StringSpec style) with JUnit 5 runner
- **Kotest matchers** (`shouldBe`, `shouldNotBeNull`, `shouldContain`, etc.)
- **MockK** (`mockk`, `every`, `verify`)
- **Kotlin Coroutines** (suspending test bodies)
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

- `StringSpec` classes with descriptive string test names in an `init {}` block
- AAA pattern (Arrange, Act, Assert)
- Suspend functions called directly from the (already suspending) test body
- Factory methods for mock creation
- `@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")` on all test files

### Coverage

Generate coverage report:

```bash
./gradlew koverHtmlReport
# Report available at build/reports/kover/html/index.html
```

Coverage excludes generated code (see `configureCoverage()` in `build.gradle.kts`): the gRPC stubs
(`io.prometheus.grpc.*`), `io.prometheus.BuildConfig`, and `io.prometheus.common.ConfigVals` with its nested classes.

## Package-Level Documentation

Package-level documentation is provided in [`docs/packages.md`](packages.md) using Dokka's
`# Module` / `# Package` format. The following packages are documented:

| Package                | Description                                                                                                                        |
|:-----------------------|:-----------------------------------------------------------------------------------------------------------------------------------|
| `io.prometheus`        | Top-level entry points: `Agent` and `Proxy`                                                                                        |
| `io.prometheus.proxy`  | Proxy-side configuration: `ProxyOptions`                                                                                           |
| `io.prometheus.agent`  | Agent-side configuration, `AgentOptions`, and the embedded agent's `EmbeddedAgentConfig`, `MetricsSource`, and `EmbeddedAgentInfo` |
| `io.prometheus.common` | Shared configuration types: `BaseOptions`, `EnvVars`, and `ConfigLoadException`                                                    |

Each package's services, managers, and utilities are `internal`, as `packages.md` says of each: documented in source,
but not in the generated site.

## Dokka Integration

HTML API documentation is generated via the [Dokka](https://github.com/Kotlin/dokka) Gradle plugin (v2.3.0).

```bash
# Generate HTML docs
make kdocs
# Or directly:
./gradlew :dokkaGeneratePublicationHtml

# Output: build/dokka/html/index.html
```

Configuration (in `build.gradle.kts` via `configureDokka()`):

- Module name: "Prometheus Proxy"
- Includes package docs from `docs/packages.md`
- Documents `public` visibility only (`documentedVisibilities(VisibilityModifier.Public)`), so the `internal` classes
  above carry KDoc for readers of the source but don't appear in the generated site
- Suppresses generated gRPC classes (`io.prometheus.grpc.*`)
- Source links point to the GitHub repository
