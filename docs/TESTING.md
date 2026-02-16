# Testing Guide

This document describes the test suite structure and how to run tests for the prometheus-proxy project.

## Running Tests

```bash
# Run all tests (lint + tests)
./gradlew --rerun-tasks check

# Run all tests only
./gradlew test

# Run specific test class
./gradlew test --tests "ProxyServiceImplTest"

# Run a single test method
./gradlew test --tests "io.prometheus.proxy.ProxyServiceImplTest.someTestMethod"

# Run tests matching a pattern
./gradlew test --tests "*PathManager*"

# Run tests with coverage report
./gradlew koverMergedHtmlReport
```

### Make Targets

```bash
make tests        # Rerun all checks (lint + tests)
make nh-tests     # Unit tests only (agent, proxy, common, misc — no harness)
make ip-tests     # In-process integration tests only
make netty-tests  # Netty integration tests only
make tls-tests    # TLS integration tests only
```

## Frameworks and Libraries

- **Kotest** (StringSpec style) — primary test framework with JUnit 5 runner
- **Kotest matchers** — assertions (`shouldBe`, `shouldNotBeNull`, `shouldContain`, etc.)
- **MockK** — mocking library (`mockk`, `every`, `verify`)
- **Kotlin Coroutines** — async and concurrency testing (`runBlocking`)
- **Ktor** (client & server) — HTTP testing utilities
- **gRPC in-process transport** — integration tests without network I/O
- **Kover** — code coverage

## Test Structure

Tests are located in `src/test/kotlin/io/prometheus/` and organized by component:

```
src/test/kotlin/io/prometheus/
├── agent/                           # Agent component tests (17 files)
│   ├── AgentBacklogDriftTest.kt
│   ├── AgentClientInterceptorTest.kt
│   ├── AgentConnectionContextTest.kt
│   ├── AgentContextManagerTest.kt
│   ├── AgentContextTest.kt
│   ├── AgentGrpcServiceTest.kt
│   ├── AgentHttpServiceHeaderTest.kt
│   ├── AgentHttpServiceTest.kt
│   ├── AgentMetricsTest.kt
│   ├── AgentOptionsTest.kt
│   ├── AgentPathManagerTest.kt
│   ├── AgentTest.kt
│   ├── EmbeddedAgentInfoTest.kt
│   ├── HttpClientCacheTest.kt
│   ├── RequestFailureExceptionTest.kt
│   ├── SslSettingsTest.kt
│   └── TrustAllX509TrustManagerTest.kt
├── common/                          # Shared utility tests (6 files)
│   ├── BaseOptionsTest.kt
│   ├── ConfigWrappersTest.kt
│   ├── ConstantsTest.kt
│   ├── EnvVarsTest.kt
│   ├── ScrapeResultsTest.kt
│   └── UtilsTest.kt
├── proxy/                           # Proxy component tests (21 files)
│   ├── AgentContextCleanupServiceTest.kt
│   ├── AgentContextManagerTest.kt
│   ├── AgentContextTest.kt
│   ├── ChunkedContextTest.kt
│   ├── ProxyDynamicConfigTest.kt
│   ├── ProxyGrpcServiceTest.kt
│   ├── ProxyHttpConfigTest.kt
│   ├── ProxyHttpRoutesTest.kt
│   ├── ProxyHttpServiceTest.kt
│   ├── ProxyMetricsTest.kt
│   ├── ProxyOptionsTest.kt
│   ├── ProxyPathManagerTest.kt
│   ├── ProxyServerInterceptorTest.kt
│   ├── ProxyServerTransportFilterTest.kt
│   ├── ProxyServiceImplTest.kt
│   ├── ProxyTest.kt
│   ├── ProxyUtilsTest.kt
│   ├── RecentReqsSynchronizationTest.kt
│   ├── ScrapeRequestManagerTest.kt
│   └── ScrapeRequestWrapperTest.kt
├── misc/                            # Cross-cutting tests (6 files)
│   ├── AdminDefaultPathTest.kt
│   ├── AdminEmptyPathTest.kt
│   ├── AdminNonDefaultPathTest.kt
│   ├── ConfigValsTest.kt
│   ├── DataClassTest.kt
│   └── OptionsTest.kt
├── harness/                         # Integration tests (8 files)
│   ├── HarnessConfig.kt
│   ├── HarnessConstants.kt
│   ├── InProcessTestNoAdminMetricsTest.kt
│   ├── InProcessTestWithAdminMetricsTest.kt
│   ├── NettyTestNoAdminMetricsTest.kt
│   ├── NettyTestWithAdminMetricsTest.kt
│   ├── TlsNoMutualAuthTest.kt
│   ├── TlsWithMutualAuthTest.kt
│   └── support/                     # Harness infrastructure (5 files)
│       ├── AbstractHarnessTests.kt
│       ├── BasicHarnessTests.kt
│       ├── HarnessSetup.kt
│       ├── HarnessSupport.kt
│       └── HarnessTests.kt
```

## Test Categories

### Unit Tests — Agent (`agent/`)

- **AgentTest** — Agent main class lifecycle, heartbeats, reconnection, scrape request processing
- **AgentGrpcServiceTest** — gRPC service: scrape request/response streaming, heartbeats, registration, error handling,
  channel shutdown
- **AgentHttpServiceTest** — HTTP service: scraping endpoints, compression support, response handling, timeout behavior,
  error cases
- **AgentHttpServiceHeaderTest** — HTTP Accept header handling per-request (prevents stale headers in cached clients)
- **AgentContextTest** — Agent context unique ID generation, validity, invalidation, ScrapeRequestWrapper management
- **AgentContextManagerTest** — Context management: add/remove, concurrent access, chunked contexts
- **AgentConnectionContextTest** — Connection state, close/drain behavior, concurrent scrape request handling
- **AgentPathManagerTest** — Path registration/unregistration, path lookup, concurrent access
- **AgentOptionsTest** — CLI parsing, defaults, validation, SSL settings, gRPC options
- **AgentClientInterceptorTest** — gRPC client interceptor that adds agent-id metadata to outbound calls
- **AgentMetricsTest** — Prometheus metrics registration and gauge/counter updates
- **AgentBacklogDriftTest** — scrapeRequestBacklogSize drift when sendScrapeRequestAction fails
- **HttpClientCacheTest** — HTTP client caching with TTL/idle eviction, keyed by auth credentials
- **EmbeddedAgentInfoTest** — EmbeddedAgentInfo data class (launchId, agentName storage)
- **RequestFailureExceptionTest** — RequestFailureException custom exception class
- **SslSettingsTest** — SSL keystore/truststore loading for TLS configuration
- **TrustAllX509TrustManagerTest** — TrustAllX509TrustManager (dev/test only, bypasses SSL validation)

### Unit Tests — Proxy (`proxy/`)

- **ProxyTest** — Proxy main class lifecycle, agent info retrieval, JSON serialization, stale agent cleanup
- **ProxyServiceImplTest** — gRPC implementation: registerAgent, registerPath, heartbeat, scrape responses, chunked
  responses
- **ProxyGrpcServiceTest** — gRPC server configuration: TLS, keepalive, transport filter, reflection settings
- **ProxyHttpServiceTest** — ProxyHttpService string representation
- **ProxyHttpConfigTest** — HTTP server config: Ktor compression, status pages, CORS
- **ProxyHttpRoutesTest** — HTTP routing: path resolution, query params, error responses, compression,
  ensureLeadingSlash
- **ProxyPathManagerTest** — Path registration/unregistration, consolidated mode, agent selection, concurrent access
- **AgentContextTest** — Proxy's AgentContext: unique ID generation, validity, path registration, ScrapeRequestWrapper
  queue
- **AgentContextManagerTest** — Proxy's AgentContextManager: add/remove contexts, chunked context management, concurrent
  access
- **AgentContextCleanupServiceTest** — Stale agent eviction (removes agents inactive beyond maxAgentInactivitySecs)
- **ChunkedContextTest** — Chunk accumulation, CRC32 checksum validation, header/chunk/trailer sequencing
- **ProxyOptionsTest** — CLI parsing, defaults, validation, TLS settings, gRPC options
- **ProxyDynamicConfigTest** — Dynamic config overrides via -D flags (maxZippedContentSizeMBytes,
  scrapeRequestTimeoutSecs, etc.)
- **ProxyServerInterceptorTest** — gRPC server interceptor: agent-id metadata extraction from incoming calls
- **ProxyServerTransportFilterTest** — gRPC transport filter: agent context creation on connection, cleanup on
  disconnect
- **ProxyUtilsTest** — Helper functions: invalidAgentContextResponse, respondWith
- **ProxyMetricsTest** — Prometheus metrics registration and gauge/counter updates
- **RecentReqsSynchronizationTest** — Synchronized access to recentReqs EvictingQueue (prevents
  ConcurrentModificationException)
- **ScrapeRequestManagerTest** — Add/remove scrape requests, timeout handling, concurrent access
- **ScrapeRequestWrapperTest** — Scrape request lifecycle, timeout behavior, response delivery

### Unit Tests — Common (`common/`)

- **BaseOptionsTest** — Shared CLI argument parsing, config loading, boolean resolution
- **ConfigWrappersTest** — Factory methods for AdminConfig, MetricsConfig, ZipkinConfig
- **ConstantsTest** — Constant values (EMPTY_AGENT_ID_MSG, EMPTY_PATH_MSG, EMPTY_INSTANCE)
- **EnvVarsTest** — Environment variable mappings and fallback defaults
- **ScrapeResultsTest** — ScrapeResults data class, error code mapping, timeout handling, protobuf conversion
- **UtilsTest** — Utility functions: parseHostPort, sanitizeUrl, appendQueryParams, decodeParams, toJsonElement,
  setLogLevel, exceptionDetails

### Unit Tests — Misc (`misc/`)

- **AdminDefaultPathTest** — Admin endpoints with default paths (ping, version, health, thread dump)
- **AdminEmptyPathTest** — Admin endpoints with empty paths (disables endpoints)
- **AdminNonDefaultPathTest** — Admin endpoints with custom paths
- **ConfigValsTest** — ConfigVals auto-generated from HOCON schema (validates default values)
- **DataClassTest** — Config data classes (AdminConfig, MetricsConfig, ZipkinConfig parsing)
- **OptionsTest** — ProxyOptions and AgentOptions config file loading

### Integration Tests (`harness/`)

Integration tests exercise full proxy+agent workflows with real instances:

- **InProcessTestNoAdminMetricsTest** — In-process gRPC tests without admin/metrics
- **InProcessTestWithAdminMetricsTest** — In-process gRPC tests with admin/metrics enabled
- **NettyTestNoAdminMetricsTest** — Netty transport tests without admin/metrics
- **NettyTestWithAdminMetricsTest** — Netty transport tests with admin/metrics enabled
- **TlsNoMutualAuthTest** — TLS tests without mutual authentication
- **TlsWithMutualAuthTest** — TLS tests with mutual authentication (client certs)

Each integration test class runs a standard suite defined in `AbstractHarnessTests`:

1. Scrape metrics through proxy
2. Return not found for missing path
3. Return not found for invalid path
4. Add/remove paths correctly
5. Add/remove paths under concurrent access
6. Handle invalid agent URL gracefully
7. Timeout when scrape exceeds deadline

#### Harness Infrastructure (`harness/support/`)

- **HarnessSetup** — Base class providing `setupProxyAndAgent()` / `takeDownProxyAndAgent()` lifecycle
- **HarnessSupport** — Utility functions: `startProxy()`, `startAgent()`, `exceptionHandler()`
- **AbstractHarnessTests** — Abstract base defining the standard 7-test suite
- **BasicHarnessTests** — Reusable test implementations (missing path, invalid path, add/remove paths, etc.)
- **HarnessTests** — Core integration logic: `proxyCallTest()` (sequential, parallel, concurrent queries) and
  `timeoutTest()`
- **HarnessConfig** — Enum defining test scale configs (MINI, SMALL, MEDIUM, LARGE, XLARGE1, XLARGE2)
- **HarnessConstants** — Test constants (ports, delays, config paths)

## Testing Patterns

### Mocking with MockK

Tests use relaxed mocks for dependencies that aren't central to the test:

```kotlin
val mockProxy = mockk<Proxy>(relaxed = true)
every { mockProxy.options } returns mockOptions
```

### Test Mode

Some components support a `isTestMode` flag that disables certain behaviors (like health check registration):

```kotlin
val manager = ProxyPathManager(proxy, isTestMode = true)
```

### Coroutine Testing

Async operations are tested using `runBlocking`:

```kotlin
@Test
fun `test async operation`(): Unit = runBlocking {
    val result = suspendFunction()
    result shouldBe expectedValue
  }
```

### Harness Test Scaling

Integration tests scale via `HarnessConfig` (MINI to XLARGE2), which controls the number of agents, paths, and
iterations used in integration tests.

### Bug Regression Tests

Several tests serve as regression guards for specific bugs, documented inline:

- **RecentReqsSynchronizationTest** — Prevents ConcurrentModificationException on recentReqs
- **ChunkedContextTest** — Validates chunk sequencing and input validation
- **AgentHttpServiceHeaderTest** — Prevents stale Accept headers in cached HTTP clients

## Coverage

Generate coverage report:

```bash
./gradlew koverMergedHtmlReport
# Report available at build/reports/kover/html/index.html
```

Coverage excludes generated gRPC classes (`io.prometheus.grpc.*`).

## Writing New Tests

1. Place tests in the appropriate package under `src/test/kotlin/io/prometheus/`
2. Use Kotest matchers (`shouldBe`, `shouldNotBeNull`, etc.)
3. Use MockK for mocking (`mockk`, `every`, `verify`)
4. Wrap async tests in `runBlocking`
5. Add `@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")` to avoid lint warnings
6. Add comments for complex test scenarios explaining what is being validated
7. For integration tests, extend `AbstractHarnessTests` and use the harness support infrastructure
