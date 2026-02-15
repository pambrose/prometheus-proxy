# Code Improvement Suggestions

This document outlines potential improvements for the Prometheus Proxy codebase to enhance code quality, performance,
security, and maintainability. Items that have been completed are marked accordingly.

## Code Quality & Maintainability

### 1. Add Documentation Comments ✅ Completed

KDoc comments have been added to the major public-facing classes:

- **Agent** — 78 lines of KDoc covering architecture, configuration, usage examples (basic, embedded, custom
  initialization), connection lifecycle, error handling, and full @param/@see documentation
- **Proxy** — 85 lines of KDoc covering architecture overview, request flow, service discovery, high availability, usage
  examples, and full @param documentation

Internal service classes (`ProxyGrpcService`, `AgentGrpcService`, `ProxyHttpService`, `AgentHttpService`) intentionally
suppress documentation requirements via `@Suppress("UndocumentedPublicClass")`, which is reasonable for internal
implementation details.

### 2. Reduce Cognitive Complexity

**Status**: Open — not yet addressed.

Methods like `Agent.run()` are complex with nested coroutines and exception handling.

**Improvements**:

- Extract connection logic into separate methods
- Break down large methods into smaller, focused functions
- Simplify nested coroutine structures
- Use higher-order functions to reduce repetition

### 3. Improve Error Handling ✅ Completed

The codebase now has specific exception types for different failure scenarios:

- **RequestFailureException** (`agent/RequestFailureException.kt`) — used for agent registration and path failures in
  `AgentGrpcService`
- **ChunkValidationException** (`proxy/ChunkedContext.kt`) — used for chunk validation errors during chunked response
  assembly
- **ZipBombException** (`proxy/ProxyUtils.kt`) — used for oversized unzipped content protection

## Performance & Scalability

### 4. Memory Management ✅ Completed

The `EvictingQueue<String>` for recent requests uses a configurable size limit (
`proxyConfigVals.admin.recentRequestsQueueSize`) and all access is properly synchronized:

```kotlin
// Synchronized read access (Proxy.kt)
synchronized(recentReqs) {
  if (recentReqs.isNotEmpty())
    "\n${recentReqs.size} most recent requests:\n" + recentReqs.reversed().joinToString("\n")
  else
    ""
}

// Synchronized write access (Proxy.kt)
synchronized(recentReqs) {
  recentReqs.add("${LocalDateTime.now().format(formatter)}: $desc")
}
```

This also resolves the `ConcurrentModificationException` bug, with a dedicated regression test in
`RecentReqsSynchronizationTest`.

### 5. Connection Pooling ✅ Completed

`HttpClientCache` (`agent/HttpClientCache.kt`) implements a full-featured connection pool:

- **TTL eviction**: `maxAge` (default 30 minutes) — entries expire after a fixed duration
- **Idle eviction**: `maxIdleTime` (default 10 minutes) — entries removed after inactivity
- **LRU eviction**: Least recently used entries removed when `maxCacheSize` (default 100) is reached
- **Background cleanup**: Coroutine-based periodic cleanup every `cleanupInterval` (default 5 minutes)
- **Credential masking**: Sensitive credentials masked in log output via `maskedKey()`
- **Keyed by auth credentials**: Separate clients for different auth configurations

All cache parameters are configurable via agent options.

## Security Improvements

### 6. Input Validation ✅ Completed

Extensive validation using `require()` throughout the codebase:

- **ChunkedContext**: Validates chunkByteCount bounds, totalByteCount limits, chunk counts, and CRC32 checksums
- **ProxyPathManager**: Validates non-empty path and agentId on all operations
- **AgentPathManager**: Validates non-empty path and URL
- **AgentOptions**: Validates chunkContentSizeBytes > 0, chunk size fits in Int.MAX_VALUE, and all HTTP client/cache
  configuration values > 0
- **BaseOptions**: Validates TLS configuration completeness (cert and key must both be present)
- **Proxy**: Validates non-empty agentId
- **AgentGrpcService**: Validates agentId and path values on multiple operations

### 7. Secrets Management ✅ Completed

Auth header forwarding over non-TLS connections generates a warning:

```kotlin
// ProxyHttpRoutes.kt
if (authHeader.isNotEmpty() && !proxy.options.isTlsEnabled && authHeaderWithoutTlsWarned.compareAndSet(false, true))
  logger.warn {
    "Authorization header is being forwarded to agent over a non-TLS gRPC connection. " +
      "Credentials may be exposed in transit. Configure TLS (--cert, --key) to secure the proxy-agent channel."
  }
```

Uses an `AtomicBoolean` to ensure the warning fires only once. Additionally, `HttpClientCache` masks credentials in log
output via `maskedKey()`.

## Testing & Reliability

### 8. Test Coverage ✅ Completed

The test suite has grown from the initial Phase 1 (8 files, 102 tests) to **63 test-related files** across 5
directories:

| Component | Files | Description                                                         |
|:----------|------:|:--------------------------------------------------------------------|
| Agent     |    17 | Lifecycle, gRPC streaming, HTTP scraping, options, metrics, SSL     |
| Proxy     |    21 | Lifecycle, gRPC service, HTTP routing, path management, cleanup     |
| Common    |     6 | CLI parsing, config wrappers, env vars, scrape results, utilities   |
| Misc      |     6 | Admin endpoints, ConfigVals, data classes, options integration      |
| Harness   |    13 | Integration tests (in-process, Netty, TLS) + support infrastructure |

**Frameworks:** Kotest (StringSpec), MockK, JUnit 5, Kotlin Coroutines, Ktor, gRPC in-process transport, Kover

**Key coverage areas:**

- Bug regression tests for concurrency issues (`RecentReqsSynchronizationTest`), chunked context validation (
  `ChunkedContextTest`), and stale HTTP headers (`AgentHttpServiceHeaderTest`)
- Integration tests covering in-process gRPC, Netty transport, and TLS (with and without mutual auth)
- Concurrency testing for path managers, context managers, and scrape request tracking

See [docs/TESTING.md](docs/TESTING.md) for the full testing guide.

### 9. Observability

**Status**: Open — basic logging is in place but structured logging and request tracing have not been added.

**Improvements**:

- Implement structured logging with consistent format
- Add request tracing across proxy-agent boundaries
- Include correlation IDs for request tracking
- Add detailed metrics for performance monitoring

## Configuration & Deployment

### 10. Configuration Validation ✅ Partially Completed

Startup validation is implemented via `require()` calls in `AgentOptions`, `BaseOptions`, and other
configuration-consuming classes. However, there is no centralized `ConfigurationValidator` class or schema-level
validation.

**Remaining opportunities:**

- Centralized validation at startup with all errors reported at once
- Configuration schema validation
- Support for configuration hot-reloading

### 11. Graceful Shutdown ✅ Completed

Both `Agent` and `Proxy` implement comprehensive shutdown methods:

**Proxy.shutDown():**

1. Fails all in-flight scrape requests with informative error message
2. Invalidates all agent contexts
3. Stops gRPC service
4. Stops HTTP service
5. Stops agent cleanup service (if running)
6. Calls parent `shutDown()`

**Agent.shutDown():**

1. Shuts down gRPC service
2. Closes HTTP service (with `runBlocking` for coroutine cleanup)
3. Calls parent `shutDown()`

## Code Organization

### 12. Dependency Injection

**Status**: Open — not yet addressed.

**Improvements**:

- Consider using a lightweight DI framework (like Koin)
- Improve testability with better dependency isolation
- Reduce constructor complexity
- Make configuration injectable

### 13. Constants Management ✅ Completed

Dedicated constants objects are defined throughout the codebase:

- **Constants.kt** (`common/`) — `Messages` (EMPTY_AGENT_ID_MSG, EMPTY_PATH_MSG), `GrpcConstants` (AGENT_ID,
  META_AGENT_ID_KEY), `DefaultObjects` (EMPTY_INSTANCE)
- **ProxyConstants.kt** (`proxy/`) — MISSING_PATH_MSG, CACHE_CONTROL_VALUE, FAVICON_FILENAME
- **BaseOptions** companion — DEBUG, HTTP_PREFIX, HTTPS_PREFIX
- **AgentHttpService** companion — INVALID_PATH_MSG, SUCCESS_MSG, UNSUCCESSFUL_MSG

## Implementation Priority

### Completed ✅

- **Test Coverage** — 63 test-related files across agent, proxy, common, misc, and harness
- **KDoc Documentation** — Comprehensive docs on Agent and Proxy classes
- **Error Handling** — Custom exception types (RequestFailureException, ChunkValidationException, ZipBombException)
- **Input Validation** — Extensive `require()` throughout the codebase
- **Secrets Management** — Auth header TLS warning, credential masking in logs
- **Connection Pooling** — HttpClientCache with TTL/idle/LRU eviction
- **Memory Management** — Synchronized EvictingQueue access
- **Graceful Shutdown** — Proper cleanup in both Agent and Proxy
- **Constants Management** — Dedicated object declarations

### Open

1. **Reduce cognitive complexity** — Break down large methods (e.g., Agent.run())
2. **Observability** — Structured logging and request tracing
3. **Centralized configuration validation** — Report all config errors at startup
4. **Dependency injection** — Consider lightweight DI framework
