# Bad Design Review: prometheus-proxy

## DESIGN-1: `ResponseResults` is a mutable bag passed through the call chain

**Files:** `ProxyHttpRoutes.kt:299-304`, `ProxyUtils.kt:98-113`

`ResponseResults` is a mutable class with `var` properties that gets created at the top of a request handler and then mutated by various functions deep in the call chain:

```kotlin
class ResponseResults(
  var statusCode: HttpStatusCode = HttpStatusCode.OK,
  var contentType: ContentType = Text.Plain.withCharset(Charsets.UTF_8),
  var contentText: String = "",
  var updateMsg: String = "",
)
```

This "output parameter" pattern makes the data flow hard to follow. Multiple functions (`proxyNotRunningResponse`, `emptyPathResponse`, `invalidPathResponse`, `invalidAgentContextResponse`, `processRequests`) all mutate the same object via side effects. The reader has to trace through every branch to understand what values `responseResults` will hold.

**Recommendation:** Return result values from functions instead of mutating a shared object. Each branch could return a sealed class or data class representing the response.

---

## DESIGN-2: `ProxyUtils` helper functions take `KLogger` and `logLevel` as parameters

**File:** `ProxyUtils.kt:33-113`

Every helper function in `ProxyUtils` takes a `KLogger` parameter, and the private `updateResponse()` function additionally takes `logLevel` as a function reference:

```kotlin
private fun updateResponse(
  message: String,
  proxy: Proxy?,
  logger: KLogger,
  logLevel: (KLogger, () -> String) -> Unit,
  responseResults: ResponseResults,
  ...
)
```

Passing a logger as a parameter is unusual. The logger is always the same `ProxyHttpRoutes.logger`, making the parameter redundant. The `logLevel` function-type parameter adds complexity for what amounts to choosing between `logger.error` and `logger.info`.

**Recommendation:** Make `ProxyUtils` functions use their own logger or accept a log-level enum instead of a function reference.

---

## DESIGN-3: Configuration value resolution uses sentinel values and repeated boilerplate

**Files:** `BaseOptions.kt:159-225`, `AgentOptions.kt:132-213`, `ProxyOptions.kt`

The configuration resolution pattern is repeated 20+ times across `BaseOptions`, `AgentOptions`, and `ProxyOptions`:

```kotlin
if (someValue == -1)          // or isEmpty(), or !someBoolean
  someValue = SOME_ENV.getEnv(configDefault)
logger.info { "someValue: $someValue" }
```

Each property uses a sentinel value (`-1` for ints/longs, `""` for strings, `false` for booleans) to detect whether it was set via CLI. This pattern:
- Is error-prone (what if -1 is a valid value?)
- Results in significant code duplication
- Mixes configuration resolution with logging
- Makes it hard to add new configuration properties

**Recommendation:** Use a configuration resolution abstraction (e.g., a `ConfigProperty` delegate) that encapsulates the CLI > env var > config file > default precedence chain.

---

## DESIGN-4: `ScrapeResults` uses `sr` prefix on all properties

**File:** `ScrapeResults.kt:39-50`

All properties in `ScrapeResults` are prefixed with `sr`:

```kotlin
class ScrapeResults(
  val srAgentId: String,
  val srScrapeId: Long,
  var srValidResponse: Boolean = false,
  var srStatusCode: Int = NotFound.value,
  var srContentType: String = "",
  ...
)
```

This Hungarian-notation-style prefix serves no purpose in Kotlin. It clutters every call site with `scrapeResults.srStatusCode` instead of `scrapeResults.statusCode`. The prefix was likely added to avoid name collisions with protobuf builder DSL properties (e.g., in `toScrapeResponse()` where both `statusCode` and `srStatusCode` appear), but this is better solved with explicit receiver qualification.

**Recommendation:** Remove the `sr` prefix; use `this.statusCode` at builder call sites where name collisions occur.

---

## DESIGN-5: `ScrapeResults` is a mutable class with mixed concerns

**File:** `ScrapeResults.kt:39-135`

`ScrapeResults` combines:
1. Data transfer (holding scrape result data)
2. Protobuf serialization (`toScrapeResponse()`, `toScrapeResponseHeader()`)
3. Error code mapping (`errorCode()` companion function)
4. Mutable state tracking (`scrapeCounterMsg: AtomicReference<String>`)

The class has 8 mutable `var` properties, an `AtomicReference` for counter messages, and is passed around between agent and proxy code. The `scrapeCounterMsg` field is especially odd -- it's an atomic reference used to pass a metrics label string from `AgentHttpService` back to the caller, which is an indirect communication channel through a data class.

**Recommendation:** Split into an immutable data class for transport and separate concerns for serialization and error mapping.

---

## DESIGN-6: String-based dispatch for chunked response types

**File:** `ProxyServiceImpl.kt:195`

The chunked response handler dispatches on the protobuf oneof case name converted to lowercase string:

```kotlin
when (ooc.name.toLowercase()) {
  "header" -> { ... }
  "chunk" -> { ... }
  "summary" -> { ... }
  else -> { error("Invalid field name") }
}
```

This is fragile -- renaming a field in the `.proto` file silently breaks the dispatch with a runtime error instead of a compile-time error. Protobuf generates enum constants for oneof cases that should be matched directly.

**Recommendation:** Match on the generated `ChunkOneOfCase` enum values instead of string names.

---

## DESIGN-7: `ProxyPathManager.AgentContextInfo` exposes a `MutableList` publicly

**File:** `ProxyPathManager.kt:35-44`

```kotlin
class AgentContextInfo(
  val isConsolidated: Boolean,
  val labels: String,
  val agentContexts: MutableList<AgentContext>,
)
```

The `agentContexts` list is directly exposed and mutated from multiple call sites (`addPath`, `removePath`, `removeFromPathManager`). There's no encapsulation -- any code that gets an `AgentContextInfo` reference can modify the list.

While mutations currently happen under `synchronized(pathMap)`, the readers (e.g., `executeScrapeRequests`, `buildServiceDiscoveryJson`) don't hold that lock, creating a data race.

**Recommendation:** Return a snapshot (immutable copy) from `getAgentContextInfo()`, or make `AgentContextInfo` manage its own synchronization.

---

## DESIGN-8: `lambda()` utility function adds unnecessary indirection

**File:** `Utils.kt:52`

```kotlin
fun <T> lambda(block: T) = block
```

This function does nothing -- it returns its argument unchanged. The comment says "eliminates an extra set of paren in when blocks," but the usage sites don't clearly demonstrate a readability improvement. For example, in `AgentContextCleanupService.kt:35`:

```kotlin
initBlock: (AgentContextCleanupService.() -> Unit) = lambda {},
```

This could simply be `= {}` (an empty lambda). In `AgentHttpService.kt:132`:

```kotlin
private fun prepareRequestHeaders(request: ScrapeRequest): HttpRequestBuilder.() -> Unit =
    lambda { ... }
```

This could simply be `= { ... }`.

**Recommendation:** Remove `lambda()` and use standard Kotlin lambda syntax directly.

---

## DESIGN-9: `AgentPathManager` uses `Map<String, String>` for path configuration

**File:** `AgentPathManager.kt:41-50`

Path configurations are stored as `List<Map<String, String>>`:

```kotlin
private val pathConfigs =
  agentConfigVals.pathConfigs
    .map {
      mapOf(
        NAME to """"${it.name}"""",
        PATH to it.path,
        URL to it.url,
        LABELS to it.labels,
      )
    }
```

Using a `Map<String, String>` with string keys (`"name"`, `"path"`, `"url"`, `"labels"`) instead of a data class means:
- No compile-time safety for key names (typo in `it[PATH]` compiles fine)
- All values are nullable `String?` requiring `isNotNull()` checks (line 60)
- The `NAME` value wraps the name in extra quotes (`""""${it.name}""""`) which embeds literal quote characters for no apparent reason

**Recommendation:** Define a `PathConfig` data class and map to it instead.

---

## DESIGN-10: `HttpClientCache` uses `LinkedHashMap` without access-order mode

**File:** `HttpClientCache.kt:50`

```kotlin
private val accessOrder = LinkedHashMap<String, Long>()
```

The cache claims LRU eviction, but the `LinkedHashMap` is created with default insertion-order (not access-order). The `evictLeastRecentlyUsed()` method works around this by using `minByOrNull { it.value }` on timestamps, which is O(n).

With `LinkedHashMap(16, 0.75f, true)` (access-order mode), the first entry would always be the LRU entry, making eviction O(1). However, this would require modifying access patterns to use `get()` to trigger reordering.

The current implementation is functionally correct but defeats the purpose of using `LinkedHashMap` -- a plain `HashMap` would behave identically.

**Recommendation:** Either use `LinkedHashMap` in access-order mode or replace with a plain `HashMap` since the LRU lookup is already O(n) via `minByOrNull`.

---

## DESIGN-11: `Proxy.run()` is a busy-wait loop

**File:** `Proxy.kt:254-260`

```kotlin
override fun run() {
  runBlocking {
    while (isRunning) {
      delay(500.milliseconds)
    }
  }
}
```

The proxy's main thread sits in a polling loop checking `isRunning` every 500ms. This is a waste of a thread and adds 0-500ms latency to shutdown.

**Recommendation:** Use a `CountDownLatch`, `CompletableDeferred`, or similar signaling mechanism to block until shutdown is requested.

---

## DESIGN-12: `AgentContextCleanupService` uses blocking `sleep()` instead of coroutine `delay()`

**File:** `AgentContextCleanupService.kt:58`

```kotlin
while (isRunning) {
  proxy.agentContextManager.agentContextMap.forEach { ... }
  sleep(pauseTime)
}
```

The cleanup service extends `GenericExecutionThreadService` (Guava's thread-based service) and uses `com.github.pambrose.common.util.sleep`, which blocks the thread. In a codebase that otherwise uses Kotlin coroutines throughout, this is inconsistent and wastes a thread that could be a coroutine.

**Recommendation:** Consider using a coroutine-based service instead, or at minimum document why the thread-based approach is chosen.

---

## DESIGN-13: `ConfigWrappers` type names use numeric suffixes (`Proxy2`, `Admin2`, `Internal2`)

**File:** `ConfigWrappers.kt`, `ConfigVals` (generated)

Throughout the codebase, configuration value types use numeric suffixes like `ConfigVals.Proxy2`, `ConfigVals.Agent`, `ConfigVals.Proxy2.Admin2`, `ConfigVals.Proxy2.Internal2.Zipkin2`. These names are confusing and appear to be artifacts of the config generation tooling rather than intentional naming.

**Impact:** Makes the code harder to read and understand. Developers encountering `Proxy2` vs `Proxy` have no clear indication of what the "2" means.

---

## DESIGN-14: `submitScrapeRequest()` is a 77-line function with 7 return paths

**File:** `ProxyHttpRoutes.kt:192-269`

This function handles:
1. Scrape request creation
2. Adding to the scrape request map
3. Writing to the agent channel
4. Polling for completion with timeout
5. Timeout handling
6. Result extraction and content-type parsing
7. Content decompression (unzip)
8. Error vs success response construction

Multiple concerns are packed into a single function with deeply nested `also`/`run` blocks and non-obvious early returns.

**Recommendation:** Extract into smaller functions: timeout handling, result unwrapping, response construction.
