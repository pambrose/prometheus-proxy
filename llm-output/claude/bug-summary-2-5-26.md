# Bug Summary

## Bug 1: Scrape requests processed sequentially instead of concurrently

**File:** `Agent.kt:290-297`

The scrape request processing loop uses a `Semaphore` to limit concurrency, but the `for` loop
runs within a single coroutine. Without launching a new coroutine per request, `semaphore.withPermit`
is called sequentially — only one permit is ever held at a time, so the semaphore serves no purpose.
All scrape requests are processed one at a time regardless of `maxConcurrentHttpClients`.

```kotlin
for (scrapeRequestAction in connectionContext.scrapeRequestActions()) {
    semaphore.withPermit {
        val scrapeResponse = scrapeRequestAction.invoke()
        connectionContext.sendScrapeResults(scrapeResponse)
    }
}
```

The loop needs an inner `launch` so each iteration runs in its own coroutine, with the semaphore
actually limiting how many execute concurrently.

---

## Bug 2: Chunked transfer sends and checksums full buffer instead of actual bytes read

**File:** `AgentGrpcService.kt:341,349`

When chunking large gzipped content, the last chunk may have fewer bytes than the buffer size.
However, `checksum.update` and `copyFrom` both use the full buffer:

```kotlin
checksum.update(buffer, 0, buffer.size)    // should be readByteCount
...
chunkBytes = copyFrom(buffer)              // should be copyFrom(buffer, 0, readByteCount)
```

For all chunks except the last, `readByteCount == buffer.size`, so this is correct. For the
last chunk, stale bytes from the previous read are included in the checksum and transmitted
over the network. The receiver (`ChunkedContext.applyChunk`) also checksums the full data
array (`data.size`), so the checksums happen to agree — but both sides are checksumming stale
data. This wastes bandwidth and means the checksum does not validate actual content integrity
for the final chunk.

---

## Bug 3: Dynamic parameter values include key name

**File:** `BaseOptions.kt:246-247`

When processing `-D` dynamic parameters, the system property value is set to `"key=value"`
instead of just `"value"`:

```kotlin
val prop = "$k=$qval"
System.setProperty(k, prop)   // sets key "foo" to value "foo=bar" instead of "bar"
```

`System.setProperty(k, prop)` should be `System.setProperty(k, qval)`. The `prop` variable
(which includes the key) is correctly used on the next line for Typesafe Config parsing, but it
should not be used as the system property value.

---

## Bug 4: Path removal during iteration can skip entries

**File:** `ProxyPathManager.kt:146-163`

`removeFromPathManager` iterates over `pathMap` with `forEach` while calling `pathMap.remove(k)`
inside the loop body. `ConcurrentHashMap` iterators are weakly consistent and will not throw
`ConcurrentModificationException`, but removing entries during iteration can cause entries to
be skipped. When an agent disconnects, some of its paths may not be cleaned up.

```kotlin
synchronized(pathMap) {
    pathMap.forEach { (k, v) ->
        if (v.agentContexts.size == 1) {
            if (v.agentContexts[0].agentId == agentId)
                pathMap.remove(k)   // modifies map during iteration
```

The fix is to collect keys to remove first, then remove them in a second pass.

---

## Bug 5: Heartbeat response always contains error reason

**File:** `ProxyServiceImpl.kt:150-160`

The `sendHeartBeat` response unconditionally sets `reason` to an error message, even when
the heartbeat is valid:

```kotlin
heartBeatResponse {
    valid = agentContext.isNotNull()
    reason = "Invalid agentId: ${request.agentId} (sendHeartBeat)"   // always set
}
```

The `reason` field should only be set when `valid` is false.

---

## Bug 6: EvictingQueue accessed without synchronization in debug servlet

**File:** `Proxy.kt:170,215-217`

The `recentReqs` EvictingQueue (backed by `ArrayDeque`, which is not thread-safe) is properly
synchronized in `logActivity()`, but is read without synchronization in the debug servlet lambda:

```kotlin
if (recentReqs.isNotEmpty()) "\n${recentReqs.size} most recent requests:" else "",
recentReqs.reversed().joinToString("\n"),
```

Since `reversed()` iterates the queue while `logActivity()` can concurrently add to it, this
can throw `ConcurrentModificationException` or produce inconsistent results.

---

## Bug 7: `runBlocking` in `AgentConnectionContext.close()` risks deadlock

**File:** `AgentConnectionContext.kt:49-60`

`close()` uses `runBlocking` to acquire a coroutine `Mutex`. It is called from
`invokeOnCompletion` callbacks in `Agent.kt`, which execute on the coroutine's completing
thread. If the mutex is currently held by another coroutine running on the same
`Dispatchers.IO` thread, `runBlocking` blocks the thread while the mutex holder needs
that thread to release the mutex, causing a deadlock.

```kotlin
fun close() {
    runBlocking {
        accessMutex.withLock {
            ...
        }
    }
}
```

---

## Bug 8: Consolidated path validity check doesn't verify all agent contexts

**File:** `ProxyPathManager.kt:40`

For consolidated paths (multiple agents serving the same path), `isNotValid()` always returns
`false`, meaning the path is always considered valid even if every agent context is disconnected:

```kotlin
fun isNotValid() = !isConsolidated && agentContexts[0].isNotValid()
```

For consolidated paths, the check should verify that at least one agent context is still valid.
Otherwise, requests are routed to consolidated paths whose agents have all disconnected,
resulting in `ServiceUnavailable` responses after a timeout instead of an immediate error.

---

## Bug 9: Scrape backlog counter can briefly go negative

**File:** `AgentGrpcService.kt:297` and `AgentGrpcService.kt:373`

The scrape backlog size is incremented *after* the action is placed on the channel:

```kotlin
connectionContext.sendScrapeRequestAction { agentHttpService.fetchScrapeUrl(grpcRequest) }
agent.scrapeRequestBacklogSize += 1   // increment after send
```

The decrement happens in `processScrapeResults` when the response is processed. Because
scrape processing runs in a separate coroutine, the response can be completed and the
counter decremented before the increment occurs, causing the counter to briefly go negative.
This produces incorrect health check and metrics data.

---

## Bug 10: `HttpClientCache` cleanup coroutine leaks on cache close

**File:** `HttpClientCache.kt:54-63,225-235`

The `init` block launches a cleanup coroutine via `scope.launch`. In `close()`, `scope.cancel()`
is called inside `accessMutex.withLock`. However, if `close()` is called while the cleanup
coroutine is waiting for `accessMutex.withLock` (which it acquires at line 58), the cancellation
may not take effect until the mutex is released. The sequence `scope.cancel()` followed by
`cache.values.forEach { it.client.close() }` inside the same mutex lock means the cleanup
coroutine cannot interfere, but there is a brief window where the cleanup coroutine could be
holding the mutex when `close()` tries to acquire it, causing `close()` to block indefinitely
since `runBlocking` is used.

---

## Bug 11: `acceptVal` from Prometheus may be null, silently dropped

**File:** `ProxyHttpRoutes.kt:281` and `ScrapeRequestWrapper.kt:56`

The `Accept` header from the incoming Prometheus request is read via:

```kotlin
acceptVal = request.header(HttpHeaders.Accept),
```

This value can be `null`. In `ScrapeRequestWrapper`, it is converted:

```kotlin
accept = acceptVal.orEmpty()
```

Then in `AgentHttpService.kt:198`, the header is set on the outgoing request:

```kotlin
scrapeRequest.accept.also { if (it.isNotEmpty()) header(ACCEPT, it) }
```

When Prometheus sends `Accept: application/openmetrics-text`, this works correctly. But when
the proxy's `defaultRequest` block sets the Accept header, it is set on the `HttpClient`
instance, not the individual request. Since `HttpClient` instances are cached and reused,
an Accept header from one scrape request could persist and be applied to a different scrape
request that has a different Accept value. The `defaultRequest` headers are merged with
per-request headers, so a cached client may carry stale Accept headers.

---

## Bug 12: Service discovery endpoint path may miss leading slash

**File:** `ProxyHttpRoutes.kt:71`

The service discovery endpoint is registered as:

```kotlin
get(proxy.options.sdPath) { ... }
```

If `sdPath` from configuration does not include a leading `/`, the Ktor route may not match
incoming requests as expected. The configuration value is used directly without normalization.
