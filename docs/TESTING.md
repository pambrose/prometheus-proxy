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
make tests            # Rerun all checks (lint + tests); the container tests are SKIPPED
make nh-tests         # Unit tests only (agent, proxy, common, misc — no harness)
make ip-tests         # In-process integration tests only
make netty-tests      # Netty integration tests only
make tls-tests        # TLS integration tests only
make container-tests  # Full Testcontainers end-to-end suite (needs Docker)
make scaling-tests    # Parameter-driven scaling container test only (needs Docker)
make all-tests        # Full suite: `make tests` + `make container-tests`
                      #   (container-tests already includes ContainersScalingTest's default table)
```

#### Scaling Presets

For dev/stress work there are curated presets that each delegate to `scaling-tests` with a `SCALE_*` combo that
hammers a different part of the system. They are **not** run by `all-tests` or CI:

```bash
make scaling-paths         # 2000 paths across 4 agents (routing-table stress)
make scaling-agents        # 40 agents x 2 endpoints (gRPC connection stress)
make scaling-payload       # 16 paths x 50k series (chunking/gzip stress)
make scaling-consolidated  # 25-agent consolidated fan-out (response-merge stress)
make scaling-concurrency   # 1800 paths, 150 concurrent (scrape-correlation stress)
make scaling-soak          # every dimension at once (broad mixed-load soak)
make all-scaling           # run all of the presets above in sequence
```

Container count is roughly `2*agents + 2*consolidated + 1`, so many-agents presets stress Docker/the host
while many-endpoints presets stress the proxy cheaply.

The container tests (`io.prometheus.containers.*`) are gated on `RUN_CONTAINER_TESTS=true` and
need Docker, so a plain `make tests` / `./gradlew check` registers each spec as a SKIPPED placeholder. Use
`make container-tests` to run the whole suite on its own, `make scaling-tests` for just the scaling spec, or
`make all-tests` to run everything in one shot.

The `make scaling-tests` target forwards any `SCALE_*` environment variables to the test, so the scaling
inputs can be tuned without recompiling — for example:

```bash
make scaling-tests SCALE_AGENTS=10 SCALE_ENDPOINTS_PER_AGENT=20 SCALE_CONSOLIDATED_AGENTS=3 \
                   SCALE_SERIES_PER_ENDPOINT=2000 SCALE_CONCURRENT=true
```

Large runs hold many scrape bodies in memory at once. The forked test JVM defaults to a 1g heap; raise it with
`TEST_MAX_HEAP_SIZE` (or `-PtestMaxHeapSize`), and cap the number of in-flight verification coroutines with
`SCALE_CONCURRENCY_LIMIT` so memory stays bounded even at high path counts:

```bash
make scaling-tests SCALE_AGENTS=100 SCALE_ENDPOINTS_PER_AGENT=200 SCALE_SERIES_PER_ENDPOINT=5000 \
                   SCALE_CONCURRENT=true SCALE_CONCURRENCY_LIMIT=64 TEST_MAX_HEAP_SIZE=8g
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
├── common/                          # Shared utility tests + support (8 files)
│   ├── BaseOptionsTest.kt
│   ├── ConfigWrappersTest.kt
│   ├── ConstantsTest.kt
│   ├── EmbeddedTestServer.kt
│   ├── EnvVarsTest.kt
│   ├── ScrapeResultsTest.kt
│   ├── TestPorts.kt
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

Two support helpers also live here (not test classes themselves):

- **TestPorts** — canonical port constants shared across the unit, harness, and container suites (mirrors the
  proxy/agent config defaults and the fixed container ports), so no test hard-codes a port literal
- **EmbeddedTestServer** — `EmbeddedServer.startAndAwaitReady()`, which starts a Ktor server with `wait = false`
  and polls with real HTTP probes until it serves several consecutive clean replies before returning the port

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
- **HarnessConfig** — Enum defining test scale configs (MINI, SMALL, MEDIUM, LARGE, XLARGE, XXLARGE)
- **HarnessConstants** — Test constants (ports, delays, config paths)

### Container Tests (`containers/`)

End-to-end Testcontainers specs that build the proxy and agent images from `etc/docker/*.df`, stand them up
alongside `nginx:alpine` metrics stubs (and `prom/prometheus` where needed), and exercise the full
Prometheus → proxy → agent → endpoint scrape path over real network transport. Every spec is gated on
`RUN_CONTAINER_TESTS=true`; shared factories live in `containers/support/ContainerTestSupport.kt`.

- **ContainersSmokeTest** — baseline: Prometheus scrapes a single metric through the proxy and agent
- **ContainersProxyHttpTest** — HTTP surfaces: registered-path scrapes, 404/503 passthrough, admin servlets,
  self-metrics, service discovery
- **ContainersConsolidatedTest** — two consolidated agents register the same path; the proxy merges responses
- **ContainersLargePayloadTest** — forces the chunked + gzipped scrape path with a large synthetic payload
- **ContainersReconnectTest** — agent reconnects to a replacement proxy and scrapes resume
- **ContainersAgentTokenAuthTest** — pre-shared agent-token authentication on the gRPC channel
- **ContainersTlsTest** / **ContainersHttpsTargetTest** — server-only and mutual TLS on gRPC; HTTPS upstreams
- **ContainersScalingTest** — parameter-driven scaling (see below)

#### Scaling Test (`ContainersScalingTest`)

A single parameter-driven spec that stands up a fresh topology per scenario and verifies every path is
scrapable end-to-end, then asserts the proxy's own `proxy_agent_map_size` / `proxy_path_map_size` gauges match
the expected counts. Each scenario (a `ScalingScenario`) scales four dimensions:

| Dimension | `SCALE_*` env var | Default |
|-----------|-------------------|---------|
| Number of agents | `SCALE_AGENTS` | table |
| Endpoints (paths) per agent | `SCALE_ENDPOINTS_PER_AGENT` | table |
| Series per endpoint (payload size) | `SCALE_SERIES_PER_ENDPOINT` | table |
| Consolidated fan-out agents (share one path) | `SCALE_CONSOLIDATED_AGENTS` | table |
| Concurrent vs sequential scrape verification | `SCALE_CONCURRENT` | table |
| Max in-flight checks when concurrent (0 = unbounded) | `SCALE_CONCURRENCY_LIMIT` | `0` |

All agent configs and per-endpoint exposition payloads are generated at runtime (no fixture files). With no
overrides, a small CI-safe default table runs (a baseline row, an agents × endpoints row, a large-payload row,
and a consolidated-fan-out row). Setting **any** `SCALE_*` variable collapses the run to a single tuned
scenario built from those values (with modest fallbacks), letting the inputs be dialed up via
`make scaling-tests SCALE_...=...` without editing code.

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

Integration tests scale via `HarnessConfig` (MINI to XXLARGE), which controls the number of agents, paths, and
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
