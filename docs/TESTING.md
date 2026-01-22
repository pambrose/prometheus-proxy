# Testing Guide

This document describes the test suite structure and how to run tests for the prometheus-proxy project.

## Running Tests

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "ProxyServiceImplTest"

# Run tests with coverage report
./gradlew koverMergedHtmlReport

# Run tests matching a pattern
./gradlew test --tests "*PathManager*"
```

## Test Structure

Tests are located in `src/test/kotlin/io/prometheus/` and organized by component:

```
src/test/kotlin/io/prometheus/
├── agent/                    # Agent component tests
│   ├── AgentContextManagerTest.kt
│   ├── AgentContextTest.kt
│   ├── AgentGrpcServiceTest.kt
│   ├── AgentPathManagerTest.kt
│   └── HttpClientCacheTest.kt
├── proxy/                    # Proxy component tests
│   ├── ProxyPathManagerTest.kt
│   ├── ProxyServiceImplTest.kt
│   ├── ProxyUtilsTest.kt
│   └── ScrapeRequestManagerTest.kt
├── harness/                  # Integration tests
│   ├── InProcessTestNoAdminMetricsTest.kt
│   ├── InProcessTestWithAdminMetricsTest.kt
│   ├── NettyTestNoAdminMetricsTest.kt
│   ├── NettyTestWithAdminMetricsTest.kt
│   ├── TlsNoMutualAuthTest.kt
│   └── TlsWithMutualAuthTest.kt
├── DataClassTest.kt          # Data class tests
└── OptionsTest.kt            # Configuration options tests
```

## Test Categories

### Unit Tests

Unit tests use MockK for mocking dependencies and test individual components in isolation.

**Proxy Component:**

- `ProxyServiceImplTest` - Tests gRPC service implementation (agent registration, path management, scrape requests)
- `ProxyPathManagerTest` - Tests path routing (add/remove paths, consolidated paths, validation)
- `ProxyUtilsTest` - Tests HTTP response utilities and error handling
- `ScrapeRequestManagerTest` - Tests scrape request tracking and result assignment

**Agent Component:**

- `AgentContextTest` - Tests agent context lifecycle (state transitions, scrape request queue, inactivity tracking)
- `AgentContextManagerTest` - Tests context management (add/remove, concurrent access, chunked contexts)
- `AgentGrpcServiceTest` - Tests hostname parsing for proxy connections
- `AgentPathManagerTest` - Tests path registration with proxy (register/unregister, validation)

### Integration Tests

Integration tests in the `harness/` directory test end-to-end flows with real proxy and agent instances:

- **InProcess tests** - Run proxy and agent in the same JVM
- **Netty tests** - Run with Netty transport
- **TLS tests** - Test secure communication with certificates

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

## Coverage

Current coverage (Phase 1 complete):

- Overall line coverage: ~54%
- Proxy package: ~90%
- Agent package: ~89%
- Common package: ~91%

Generate coverage report:

```bash
./gradlew koverMergedHtmlReport
# Report available at build/reports/kover/html/index.html
```

## Writing New Tests

1. Place tests in the appropriate package under `src/test/kotlin/io/prometheus/`
2. Use Kotest matchers (`shouldBe`, `shouldNotBeNull`, etc.)
3. Use MockK for mocking (`mockk`, `every`, `verify`)
4. Wrap async tests in `runBlocking`
5. Add `@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")` to avoid lint warnings
6. Add comments for complex test scenarios explaining what is being validated
