# Code Audit Findings - Proxy Components

## Summary

Reviewed all proxy component files for potential bugs, race conditions, and edge cases.

**Files Reviewed:**

- ProxyHttpService.kt
- ProxyHttpRoutes.kt
- ProxyGrpcService.kt
- ProxyPathManager.kt
- AgentContext.kt
- AgentContextManager.kt
- ProxyServiceImpl.kt
- ScrapeRequestManager.kt
- ScrapeRequestWrapper.kt
- AgentContextCleanupService.kt
- ChunkedContext.kt
- Proxy.kt

---

## Critical Severity

*None found*

---

## High Severity

### H1: Potential IndexOutOfBoundsException in ProxyHttpRoutes.kt

**Location:** `ProxyHttpRoutes.kt:151-152`

**Description:**

```kotlin
statusCode = if (statusCodes.contains(HttpStatusCode.OK)) HttpStatusCode.OK else statusCodes[0]
contentType = okContentType ?: contentTypes[0]
```

If `results` is empty (no agents responded), `statusCodes` and `contentTypes` will be empty lists, causing
`IndexOutOfBoundsException` when accessing `[0]`.

**Scenario:** This could happen if:

- All agent contexts become invalid between lookup and request execution
- The agentContextInfo.agentContexts list is somehow empty

**Fix:** Add empty check before accessing list elements, return appropriate error response.

---

## Medium Severity

### M1: check() in writeChunkedResponsesToProxy throws IllegalStateException

**Location:** `ProxyServiceImpl.kt:200-201`

**Description:**

```kotlin
val context = chunkedContextMap[chunkScrapeId]
check(context.isNotNull()) { "Missing chunked context with scrapeId: $chunkScrapeId" }
```

If chunks arrive out of order or after header processing somehow fails, this throws `IllegalStateException`. While the
exception is caught in the outer `runCatching`, it aborts the entire response collection flow for that agent.

**Fix:** Use a softer error handling approach - log error and skip the chunk instead of throwing.

### M2: Debug log typo in ProxyServiceImpl.kt

**Location:** `ProxyServiceImpl.kt:192`

**Description:**

```kotlin
logger.debug { "Reading header for scrapeId: $scrapeId}" }  // Extra closing brace
```

Extra `}` character in the log message string.

**Fix:** Remove the extra `}`.

---

## Low Severity

### L1: Reason field set even when valid in RegisterAgentResponse

**Location:** `ProxyServiceImpl.kt:101-105`

**Description:**

```kotlin
return registerAgentResponse {
  valid = isValid
  agentId = request.agentId
  reason = "Invalid agentId: ${request.agentId} (registerAgent)"  // Always set
}
```

The `reason` field is populated with an error message even when `valid = true`. Clients should ignore this, but it's
misleading.

**Fix:** Only set reason when valid is false.

### L2: Unnecessary sleep in ProxyHttpService shutdown

**Location:** `ProxyHttpService.kt:74-76`

**Description:**

```kotlin
httpServer.stop(5.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
sleep(2.seconds)  // Unnecessary additional delay
```

After calling `stop()` with timeout parameters, there's an additional 2-second sleep that slows down shutdown.

**Fix:** Remove the unnecessary sleep or reduce it if there's a specific reason for it.

### L3: Race condition in buildServiceDiscoveryJson

**Location:** `Proxy.kt:399-426`

**Description:**
Between getting `allPaths` and calling `getAgentContextInfo(path)`, an agent could disconnect, removing the path. This
causes warning logs and potentially incomplete entries.

**Current behavior:** Logs warning, continues with other paths.

**Fix:** This is acceptable behavior - the warning helps with debugging. Could wrap in synchronized block for
consistency, but performance impact may not be worth it.

### L4: Mismatch warning not logged when non-consolidated overwrites consolidated

**Location:** `ProxyPathManager.kt:78-81`

**Description:**
When a non-consolidated agent registers a path that was previously registered as consolidated, no warning is logged
about the mismatch. The warning is only logged in the opposite direction.

**Fix:** Add warning when overwriting consolidated path with non-consolidated agent.

### L5: ChunkedContext ByteArrayOutputStream not closed

**Location:** `ChunkedContext.kt:30, 75-76`

**Description:**

```kotlin
private val baos = ByteArrayOutputStream()
// ...
baos.flush()
scrapeResults.srContentAsZipped = baos.toByteArray()
// baos is never closed
```

While `ByteArrayOutputStream.close()` is a no-op, it's good practice to close streams.

**Fix:** Consider using `use` block or explicitly closing, though this is very low priority for BAOS.

---

## Observations (No Fix Required)

### O1: Complex completion signaling in ScrapeRequestWrapper

**Location:** `ScrapeRequestWrapper.kt:73-82`

The `suspendUntilComplete` method uses channel closure to signal completion. This is a valid pattern but could be
simplified with `CompletableDeferred`.

### O2: Synchronized blocks in ProxyPathManager

The class uses synchronized blocks on `pathMap` even though it's a ConcurrentHashMap. This is intentional for compound
operations and is correct.

### O3: AtomicInt operations in AgentContext

The `channelBacklogSize` operations using `+=` and `-=` are atomic but not atomic with the channel operations. This is
acceptable since backlog size is for monitoring only.

---

## Fixes to Implement

| ID | Severity | File                | Description                                     |
|----|----------|---------------------|-------------------------------------------------|
| H1 | High     | ProxyHttpRoutes.kt  | Add empty results check                         |
| M1 | Medium   | ProxyServiceImpl.kt | Softer error handling for missing chunk context |
| M2 | Medium   | ProxyServiceImpl.kt | Fix debug log typo                              |
| L1 | Low      | ProxyServiceImpl.kt | Only set reason when invalid                    |
| L2 | Low      | ProxyHttpService.kt | Remove unnecessary sleep                        |
| L4 | Low      | ProxyPathManager.kt | Add warning for consolidated mismatch           |

---

# Code Audit Findings - Agent Components

## Summary

Reviewed all agent component files for potential bugs, race conditions, and edge cases.

**Files Reviewed:**

- AgentHttpService.kt
- AgentGrpcService.kt
- AgentPathManager.kt
- HttpClientCache.kt
- AgentConnectionContext.kt
- Agent.kt

---

## Critical Severity

*None found*

---

## High Severity

*None found*

---

## Medium Severity

### A1: Health check uses stale backlog size

**Location:** `Agent.kt:336-342`

**Description:**

```kotlin
healthCheckRegistry.register(
  "scrape_request_backlog_check",
  newBacklogHealthCheck(
    backlogSize = scrapeRequestBacklogSize.load(),  // Captured at registration time
    size = agentConfigVals.internal.scrapeRequestBacklogUnhealthySize,
  ),
)
```

The `backlogSize` parameter is evaluated once at health check registration time, not dynamically when the health check
runs. This means the health check always compares against the initial backlog size (0), making it ineffective.

**Fix:** Use a supplier/lambda pattern or check if `newBacklogHealthCheck` supports dynamic values. If not, create a
custom health check that reads the current value.

---

## Low Severity

### A2: Backlog size increment not atomic with channel send

**Location:** `AgentGrpcService.kt:296-297`

**Description:**

```kotlin
connectionContext.sendScrapeRequestAction { agentHttpService.fetchScrapeUrl(grpcRequest) }
agent.scrapeRequestBacklogSize += 1
```

The backlog size increment happens after sending to the channel, not atomically. This could cause brief inconsistencies
in monitoring. However, since this is only for monitoring and the values are eventually consistent, this is low
severity.

**Fix:** None required - acceptable for monitoring purposes.

---

## Observations (No Fix Required)

### O4: HttpClientCache cleanup loop

**Location:** `HttpClientCache.kt:54-61`

The cleanup loop runs until the scope is cancelled in `close()`. This is correct behavior - the Agent's shutdown flow
properly cancels this scope.

### O5: Channel cancellation in AgentConnectionContext

**Location:** `AgentConnectionContext.kt:46-50`

Cancelling channels immediately on disconnect could lose pending scrape results. This is acceptable behavior since
disconnection means results can't be delivered anyway.

### O6: Proper in-use tracking in HttpClientCache

**Location:** `HttpClientCache.kt:82-93`

The cache properly tracks entries that are in use and only closes them when both marked for close AND not in use.

---

## Fixes to Implement

| ID | Severity | File     | Description                                    |
|----|----------|----------|------------------------------------------------|
| A1 | Medium   | Agent.kt | Fix health check to use dynamic backlog values |

---

# Code Audit Findings - Common Utilities

## Summary

Reviewed all common utility files for configuration loading, metrics, health checks, and SSL/TLS handling.

**Files Reviewed:**

- BaseOptions.kt
- ConfigWrappers.kt
- Utils.kt
- EnvVars.kt
- Constants.kt
- TypeAliases.kt
- ScrapeResults.kt
- AgentMetrics.kt
- ProxyMetrics.kt
- SslSettings.kt
- TrustAllX509TrustManager.kt

---

## Critical Severity

*None found*

---

## High Severity

*None found*

---

## Medium Severity

*None found*

---

## Low Severity

*None found*

---

## Observations (No Fix Required)

### O7: EnvVars integer/long parsing could throw NumberFormatException

**Location:** `EnvVars.kt:83-85`

```kotlin
fun getEnv(defaultVal: Int) = getenv(name)?.toInt() ?: defaultVal
fun getEnv(defaultVal: Long) = getenv(name)?.toLong() ?: defaultVal
```

If an environment variable is set with an invalid numeric value, these will throw NumberFormatException. This is
acceptable behavior - configuration errors should fail fast and loudly at startup.

### O8: SslSettings not actively used in production

**Location:** `SslSettings.kt`

The file is marked with `@Suppress("unused")` and is only referenced in tests. The `getTrustManager` function could
throw `NoSuchElementException` if no X509TrustManager is found, but since this code isn't used in production, no fix is
required.

### O9: TrustAllX509TrustManager is intentionally insecure

**Location:** `TrustAllX509TrustManager.kt`

This trust manager accepts all certificates without validation. This is intentional for testing/development scenarios
where certificate verification needs to be bypassed. The empty `checkClientTrusted` and `checkServerTrusted`
implementations are by design.

### O10: Metrics use proper dynamic value collection

**Location:** `AgentMetrics.kt:66-80`, `ProxyMetrics.kt:68-96`

Both metrics classes use `SamplerGaugeCollector` with lambda functions to collect dynamic values. This ensures metrics
reflect current state rather than captured values.

### O11: Health checks in Proxy use proper dynamic evaluation

**Location:** `Proxy.kt:256-296`

The Proxy's health checks use `newMapHealthCheck` and `healthCheck{}` blocks that evaluate conditions dynamically when
health checks run, not when registered.

---

## Fixes to Implement

*None required for Section 3*

---

# Code Audit Findings - Concurrency Analysis

## Summary

Reviewed all concurrency patterns including coroutine scopes, race conditions, synchronization mechanisms, and deadlock
scenarios.

**Areas Reviewed:**

- Coroutine scope usage and cancellation (Agent.kt, HttpClientCache.kt, AgentGrpcService.kt, ProxyHttpRoutes.kt)
- Shared state and race conditions (ProxyPathManager.kt, AgentContextManager.kt, ScrapeRequestManager.kt)
- Synchronization mechanisms (synchronized blocks, Mutex, atomics, ConcurrentHashMap)
- Channel usage and cancellation (AgentConnectionContext.kt, AgentContext.kt, ScrapeRequestWrapper.kt)

---

## Critical Severity

*None found*

---

## High Severity

*None found*

---

## Medium Severity

*None found*

---

## Low Severity

### L6: Incomplete L1 fix - reason field always set in registerPathResponse

**Location:** `ProxyServiceImpl.kt:120-126`

**Description:**

```kotlin
return registerPathResponse {
  pathId = if (isValid) PATH_ID_GENERATOR.fetchAndIncrement() else -1
  valid = isValid
  reason = "Invalid agentId: ${request.agentId} (registerPath)"  // Always set
  pathCount = proxy.pathManager.pathMapSize
}
```

The L1 fix was applied to `registerAgentResponse` but not to `registerPathResponse`. The reason field is still always
set even when valid is true.

**Fix:** Wrap reason assignment in `if (!isValid)` block, matching the fix in `registerAgentResponse`.

---

## Observations (No Fix Required)

### O12: No GlobalScope usage

The codebase properly avoids GlobalScope, using structured concurrency with `coroutineScope {}` blocks that wait for
child coroutines to complete.

### O13: Proper coroutine scope lifecycle in HttpClientCache

**Location:** `HttpClientCache.kt:51-61`

```kotlin
private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

init {
  scope.launch {
    while (true) {
      delay(cleanupInterval)
      // cleanup logic
    }
  }
}
```

The scope uses SupervisorJob (failures don't cancel siblings) and is properly cancelled in `close()` method.

### O14: Proper Semaphore usage for concurrency limiting

**Location:** `Agent.kt:277-285`

```kotlin
val semaphore = Semaphore(max)
connectionContext.scrapeRequestActionsFlow().collect { scrapeRequestAction ->
  semaphore.withPermit {
    val scrapeResponse = scrapeRequestAction.invoke()
    connectionContext.sendScrapeResults(scrapeResponse)
  }
}
```

The Semaphore properly limits concurrent HTTP scrapes to `maxConcurrentHttpClients`.

### O15: Proper Mutex usage in HttpClientCache

**Location:** `HttpClientCache.kt:50, 126, 153, 225`

All cache operations are protected by a single Mutex with `withLock`, ensuring thread-safe access to both the
ConcurrentHashMap and the LinkedHashMap tracking access order.

### O16: Synchronized blocks properly used for compound operations

**Location:** `ProxyPathManager.kt:54-56, 65-89, 99-131, 146-164`

The pathMap uses synchronized blocks even though it's a ConcurrentMap. This is intentional and correct for compound
operations (read-modify-write, iteration with modification) where ConcurrentMap's individual operation atomicity is
insufficient.

### O17: No deadlock potential identified

The codebase uses single-lock acquisition patterns:

- ProxyPathManager: synchronized(pathMap) only
- HttpClientCache: accessMutex only
- No nested lock acquisition across different lock objects

### O18: Channel backlog tracking is eventually consistent

**Location:** `AgentContext.kt:79-88`

```kotlin
suspend fun writeScrapeRequest(scrapeRequest: ScrapeRequestWrapper) {
  scrapeRequestChannel.send(scrapeRequest)
  channelBacklogSize += 1  // Increment after send
}
```

The backlog size is updated after channel operations, not atomically. This is acceptable since the value is only used
for monitoring and will be eventually consistent.

---

## Fixes to Implement

| ID | Severity | File                | Description                                          |
|----|----------|---------------------|------------------------------------------------------|
| L6 | Low      | ProxyServiceImpl.kt | Only set reason when invalid in registerPathResponse |

---

# Code Audit Findings - Resource Management

## Summary

Reviewed resource management including connection lifecycles, resource leaks, and shutdown sequences.

**Areas Reviewed:**

- Connection lifecycle management (gRPC channels, HTTP clients, servers)
- Resource cleanup (streams, channels, sockets)
- Shutdown sequences (Agent, Proxy, services)

---

## Critical Severity

*None found*

---

## High Severity

*None found*

---

## Medium Severity

### R1: AgentHttpService not closed during Agent shutdown

**Location:** `Agent.kt:432-435`

**Description:**

```kotlin
override fun shutDown() {
  grpcService.shutDown()
  super.shutDown()
}
```

The `agentHttpService.close()` method is never called during Agent shutdown. The HttpClientCache inside AgentHttpService
has:

1. A background coroutine that periodically cleans up expired entries - this would continue running
2. HTTP clients in the cache that need to be closed

**Fix:** Add `runBlocking { agentHttpService.close() }` to the shutDown method.

---

## Low Severity

*None found*

---

## Observations (No Fix Required)

### O19: Proper gRPC server shutdown in ProxyGrpcService

**Location:** `ProxyGrpcService.kt:132-136`

```kotlin
override fun shutDown() {
  if (proxy.isZipkinEnabled)
    tracing.close()
  grpcServer.shutdownGracefully(2.seconds)
}
```

The gRPC server uses graceful shutdown with a timeout, allowing in-flight requests to complete.

### O20: Proper gRPC channel shutdown in AgentGrpcService

**Location:** `AgentGrpcService.kt:127-132`

```kotlin
fun shutDown() {
  if (agent.isZipkinEnabled)
    tracing.close()
  if (grpcStarted)
    channel.shutdownNow()
}
```

The gRPC channel is properly shut down, and tracing is closed if enabled.

### O21: Proper HTTP server shutdown in ProxyHttpService

**Location:** `ProxyHttpService.kt:70-75`

```kotlin
override fun shutDown() {
  if (proxy.isZipkinEnabled)
    tracing.close()
  httpServer.stop(5.seconds.inWholeMilliseconds, 5.seconds.inWholeMilliseconds)
}
```

The HTTP server is stopped with proper grace and timeout periods.

### O22: HttpClientCache properly closes resources

**Location:** `HttpClientCache.kt:224-231`

```kotlin
override fun close() {
  runBlocking {
    accessMutex.withLock {
      scope.cancel()
      cache.values.forEach { it.client.close() }
      cache.clear()
      accessOrder.clear()
    }
  }
}
```

The close method properly cancels the background coroutine scope and closes all cached HTTP clients.

### O23: AgentConnectionContext properly cancels channels

**Location:** `AgentConnectionContext.kt:46-50`

```kotlin
override fun close() {
  disconnected = true
  scrapeRequestActionsChannel.cancel()
  scrapeResultsChannel.cancel()
}
```

Both channels are cancelled when the connection context is closed.

### O24: SslSettings uses try-with-resources for FileInputStream

**Location:** `SslSettings.kt:35-38`

```kotlin
FileInputStream(fileName).use { keyStoreFile ->
  val keyStorePassword = password.toCharArray()
  load(keyStoreFile, keyStorePassword)
}
```

The FileInputStream is properly closed via the `use` block.

---

## Fixes to Implement

| ID | Severity | File     | Description                                  |
|----|----------|----------|----------------------------------------------|
| R1 | Medium   | Agent.kt | Close agentHttpService during Agent shutdown |

---

# Code Audit Findings - Error Handling

## Summary

Reviewed error handling patterns including exception handling, error propagation across component boundaries, and retry
logic for idempotency issues.

**Areas Reviewed:**

- Exception handling patterns (swallowed exceptions, improper logging)
- Error propagation across gRPC and HTTP boundaries
- Retry logic and idempotency concerns
- Timeout and cancellation handling

---

## Critical Severity

*None found*

---

## High Severity

### E1: IOException maps to 404 instead of 503

**Location:** `ScrapeResults.kt:124-127`

**Description:**

```kotlin
is IOException -> {
  logger.warn { "Failed HTTP request: $url [${e.simpleClassName}: ${e.message}]" }
  NotFound.value  // WRONG: Should be ServiceUnavailable
}
```

An `IOException` typically indicates network issues or connection failures, not that a resource doesn't exist. Returning
404 (Not Found) is semantically incorrect and misleading to Prometheus. This should return 503 (Service Unavailable).

**Fix:** Changed `NotFound.value` to `ServiceUnavailable.value` and added exception parameter to logger.

---

## Medium Severity

*None found requiring fixes beyond observations*

---

## Low Severity

### E2: Exception handler returns 404 instead of 500

**Location:** `ProxyHttpConfig.kt:102-107`

**Description:**

```kotlin
exception<Throwable> { call, cause ->
  logger.warn(cause) { " Throwable caught: ${cause.simpleClassName}" }
  call.respond(NotFound)  // WRONG: Should be InternalServerError
}
```

All unexpected exceptions result in a 404 response, which masks actual server errors. A 500 (Internal Server Error)
would be more appropriate for unexpected exceptions. This could hide real server issues from monitoring systems.

**Fix:** Changed `call.respond(NotFound)` to `call.respond(HttpStatusCode.InternalServerError)`.

### E3: Missing exception parameter in logger calls

**Location:** `Agent.kt:313, 318`

**Description:**

```kotlin
is StatusException -> {
  logger.warn { "Cannot connect to proxy at $proxyHost ${e.simpleClassName} ${e.message}" }
}
// ...
else -> {
  logger.warn { "Throwable caught ${e.simpleClassName} ${e.message}" }
}
```

These exceptions are logged without passing the exception as the first parameter, causing full stack traces to be lost.
This limits debugging capability for connection and unexpected errors.

**Fix:** Changed to `logger.warn(e) { ... }` to include full stack traces.

---

## Observations (No Fix Required)

### O25: Conditional exception logging during shutdown

**Location:** `Agent.kt:246-290`, `AgentGrpcService.kt:390-419`

Multiple places use the pattern:

```kotlin
.onFailure { e ->
  if (agent.isRunning)
    logger.error(e) { "..." }
}
```

Exceptions are only logged if the agent/proxy is still running. During shutdown, exceptions are silently ignored. This
is intentional to avoid noisy logs during expected shutdown scenarios, but could make debugging shutdown-related issues
harder.

### O26: Error propagation in gRPC Flow collection

**Location:** `ProxyServiceImpl.kt:171-186, 188-239`

Errors in `writeResponsesToProxy()` and `writeChunkedResponsesToProxy()` are caught and logged but always return
`EMPTY_INSTANCE`. Callers cannot distinguish between successful and failed operations. This is by design for gRPC
streaming where the response is already being sent.

### O27: Channel close race condition potential

**Location:** `AgentConnectionContext.kt:46-50`

When connection closes, channels are cancelled immediately. Any pending scrape request actions are dropped without
execution. This is acceptable since disconnection means results can't be delivered anyway.

### O28: Scrape response after timeout is dropped

**Location:** `ProxyHttpRoutes.kt:207-229`

If proxy times out waiting for agent response, the request is removed from the map. If agent later sends a response, it
is dropped with an error log. This is expected behavior but could cause confusion when debugging timing issues.

### O29: Path registration not idempotent on reconnection

**Location:** `AgentPathManager.kt:66-80`, `ProxyPathManager.kt:58-90`

When an agent reconnects, paths are cleared locally and re-registered with new pathIds. This is by design - each
connection gets fresh path registrations. Old paths from disconnected agents are cleaned up by
AgentContextCleanupService.

### O30: Retry logic uses exponential backoff

**Location:** `AgentHttpService.kt:207-220`

HTTP client properly uses Ktor's `HttpRequestRetry` plugin with exponential backoff and configurable max retries. Retry
count is tracked via header for debugging.

---

## Fixes Implemented

| ID | Severity | File               | Description                                  |
|----|----------|--------------------|----------------------------------------------|
| E1 | High     | ScrapeResults.kt   | IOException returns 503 instead of 404       |
| E2 | Low      | ProxyHttpConfig.kt | Exception handler returns 500 instead of 404 |
| E3 | Low      | Agent.kt           | Added exception parameter to logger calls    |
