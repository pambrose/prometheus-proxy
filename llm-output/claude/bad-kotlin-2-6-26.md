# Non-Idiomatic Kotlin Review: prometheus-proxy

## KOTLIN-1: `isNull()` / `isNotNull()` extension functions instead of native null checks

**Files:** Throughout the codebase (AgentContextManager.kt, ProxyPathManager.kt, ProxyServiceImpl.kt, ProxyServerTransportFilter.kt, etc.)

The codebase uses custom extension functions `isNull()` and `isNotNull()` from `com.github.pambrose.common.util` instead of Kotlin's built-in null-safety operators:

```kotlin
// Current
if (agentContext.isNull()) { ... }
if (agentContextInfo.isNotNull()) { ... }

// Idiomatic Kotlin
if (agentContext == null) { ... }
if (agentContextInfo != null) { ... }
```

These custom functions obscure Kotlin's smart casting. With `== null` / `!= null`, the compiler automatically smart-casts the variable to non-null in the corresponding branch. With `isNull()`/`isNotNull()`, smart casting does not apply, forcing explicit null handling.

For example, in `AgentContextManager.kt:52-60`:
```kotlin
agentContextMap.remove(agentId)
  .let { agentContext ->         // agentContext is AgentContext?
    if (agentContext.isNull()) { // Smart cast does NOT happen
      ...
    } else {
      agentContext.invalidate()  // Still needs ?.  or trust that it's not null
    }
    agentContext
  }
```

With idiomatic Kotlin:
```kotlin
agentContextMap.remove(agentId)?.also { agentContext ->
  if (!isTestMode) logger.info { "Removed $agentContext ..." }
  agentContext.invalidate()
} ?: run {
  logger.warn { "Missing AgentContext ..." }
  null
}
```

---

## KOTLIN-2: `Integer.valueOf()` instead of `.toInt()`

**File:** `AgentGrpcService.kt:104`

```kotlin
agentPort = Integer.valueOf(vals[1])
```

Kotlin provides `.toInt()` for string-to-integer conversion. `Integer.valueOf()` is a Java idiom that also boxes the result as `java.lang.Integer` before unboxing to `kotlin.Int`.

```kotlin
// Idiomatic
agentPort = vals[1].toInt()
```

---

## KOTLIN-3: Java-style regex splitting instead of Kotlin string `split()`

**File:** `AgentGrpcService.kt:102`

```kotlin
val vals = schemeStripped.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
```

This is a Java-style pattern. Kotlin's `String.split()` accepts plain strings and the result is already a `List<String>`:

```kotlin
// Idiomatic
val vals = schemeStripped.split(":")
agentHostName = vals[0]
agentPort = vals.getOrNull(1)?.toIntOrNull() ?: 50051
```

The `.dropLastWhile { it.isEmpty() }.toTypedArray()` is unnecessary since the code only accesses indices 0 and 1.

---

## KOTLIN-4: Unnecessary `return` inside `synchronized` block

**File:** `ProxyPathManager.kt:53-56`

```kotlin
val allPaths: List<String>
  get() = synchronized(pathMap) {
    return pathMap.keys.toList()  // unnecessary 'return'
  }
```

In Kotlin, the last expression in a lambda is the return value. The explicit `return` is not only unnecessary but also means something different (it returns from the enclosing `get()` function, not the `synchronized` block). While the behavior is the same here, the idiomatic form is:

```kotlin
val allPaths: List<String>
  get() = synchronized(pathMap) { pathMap.keys.toList() }
```

---

## KOTLIN-5: Using `@Synchronized` annotation instead of Kotlin coroutine-aware synchronization

**File:** `AgentGrpcService.kt:128, 136`

```kotlin
@Synchronized
fun shutDown() { ... }

@Synchronized
fun resetGrpcStubs() { ... }
```

The `@Synchronized` annotation is a Java-ism. While functionally equivalent to `synchronized(this) { ... }`, it hides the lock object (implicitly `this`). In a codebase that uses Kotlin coroutines and `Mutex` elsewhere (e.g., `HttpClientCache`), the synchronization approach is inconsistent.

More importantly, `@Synchronized` blocks the thread, which is fine for these short-running methods but inconsistent with the coroutine-first philosophy of the rest of the codebase.

---

## KOTLIN-6: Using `.also` where it adds no value

**File:** `AgentHttpService.kt:136`

```kotlin
request.accept.also { if (it.isNotEmpty()) header(ACCEPT, it) }
```

The `.also` block returns the receiver, but the return value is never used (it's a statement, not an expression). A simple `if` is clearer:

```kotlin
if (request.accept.isNotEmpty()) header(ACCEPT, request.accept)
```

Similar unnecessary `.also` usage appears in `AgentGrpcService.kt:258-280` (sendHeartBeat) and `AgentOptions.kt:205` (retryOnException).

---

## KOTLIN-7: Using `.let` where the result is always the same type as input

**File:** `AgentContextManager.kt:51-61`

```kotlin
agentContextMap.remove(agentId)
  .let { agentContext ->
    if (agentContext.isNull()) {
      logger.warn { ... }
    } else {
      ...
      agentContext.invalidate()
    }
    agentContext
  }
```

`.let` is typically used for null-safe transformations or scope limiting. Here it wraps an if/else block and just returns the same value. The `?.also`/`?:` pattern or a simple local variable would be clearer:

```kotlin
val agentContext = agentContextMap.remove(agentId)
if (agentContext == null) {
  logger.warn { "Missing AgentContext for agentId: $agentId ($reason)" }
} else {
  if (!isTestMode) logger.info { "Removed $agentContext ..." }
  agentContext.invalidate()
}
return agentContext
```

---

## KOTLIN-8: Using `.apply` on logger results for side effects

**File:** `ProxyServiceImpl.kt:63-66`

```kotlin
"Agent (false) and Proxy (true) do not have matching transportFilterDisabled config values"
  .also { msg ->
    logger.error { msg }
    throw RequestFailureException(msg)
  }
```

Using `.also` on a string literal to log and throw is a convoluted way to write:

```kotlin
val msg = "Agent (false) and Proxy (true) do not have matching transportFilterDisabled config values"
logger.error { msg }
throw RequestFailureException(msg)
```

This pattern appears multiple times in `ProxyServiceImpl.kt` (lines 63-66, 75-79).

---

## KOTLIN-9: `when` without `else` used as expression where `if/else` would be clearer

**File:** `AgentPathManager.kt:88-91`

```kotlin
when {
  pathContext.isNull() -> logger.info { "No path value ..." }
  !agent.isTestMode -> logger.info { "Unregistered ..." }
}
```

This `when` has no `else` branch and only two conditions. The second condition (`!agent.isTestMode`) is only reached if `pathContext` is not null, but this isn't obvious. An `if/else` chain would be clearer:

```kotlin
if (pathContext == null) {
  logger.info { "No path value /$path found ..." }
} else if (!agent.isTestMode) {
  logger.info { "Unregistered /$path for ${pathContext.url}" }
}
```

---

## KOTLIN-10: Using `Guava` collections where Kotlin stdlib suffices

**Files:** `AgentContextManager.kt:23`, `ProxyPathManager.kt:23`, `ScrapeRequestManager.kt:21`

```kotlin
import com.google.common.collect.Maps.newConcurrentMap
...
val agentContextMap: ConcurrentMap<String, AgentContext> = newConcurrentMap()
```

Kotlin (and Java) stdlib provides `ConcurrentHashMap` directly:

```kotlin
val agentContextMap = ConcurrentHashMap<String, AgentContext>()
```

`Guava.Maps.newConcurrentMap()` just delegates to `ConcurrentHashMap()`. The Guava import adds an unnecessary dependency for something the stdlib already provides.

Similarly, `Proxy.kt:31` uses `com.google.common.base.Joiner` where `joinToString()` is the idiomatic Kotlin equivalent.

---

## KOTLIN-11: Using `Pair<Boolean, String>` instead of a result type

**File:** `ProxyPathManager.kt:101-125`

```kotlin
val results =
  if (agentInfo.isNull()) {
    ...
    false to msg
  } else {
    ...
    true to ""
  }
return unregisterPathResponse {
  valid = results.first
  reason = results.second
}
```

Using `Pair<Boolean, String>` with `results.first` and `results.second` loses semantic meaning. A sealed class, named pair, or simply constructing the response directly in each branch would be clearer.

---

## KOTLIN-12: Using `wildcard import` for `java.util.*`

**File:** `Utils.kt:33`

```kotlin
import java.util.*
```

Kotlin style prefers explicit imports. The only class used from `java.util` here is `Locale`. A specific import is clearer and prevents namespace pollution:

```kotlin
import java.util.Locale
```

---

## KOTLIN-13: `Boolean.ifTrue` extension when `if` is perfectly clear

**File:** `Utils.kt:54-56`, used in `AgentHttpService.kt:170, 173, 246`

```kotlin
fun Boolean.ifTrue(block: () -> Unit) {
  if (this) block()
}

// Usage:
scrapeRequest.debugEnabled.ifTrue { setDebugInfo(url) }
```

While this is valid Kotlin, the standard `if` statement is universally understood and doesn't require looking up a custom extension:

```kotlin
if (scrapeRequest.debugEnabled) setDebugInfo(url)
```

The `ifTrue` extension adds cognitive overhead for zero gain. Kotlin deliberately doesn't include this in stdlib because `if` already reads clearly.

---

## KOTLIN-14: `String.toLowercase()` wrapper around stdlib function

**File:** `Utils.kt:58`

```kotlin
fun String.toLowercase() = this.lowercase(Locale.getDefault())
```

This wraps `lowercase(Locale.getDefault())` in a custom extension. However:
1. Kotlin's `lowercase()` (no args) already uses the default locale via `Locale.ROOT` for consistent behavior
2. The name `toLowercase()` is confusingly similar to the deprecated `toLowerCase()` from Java
3. It adds indirection for every call site

If locale-specific lowercasing is truly needed, it should be called explicitly at the few call sites that need it rather than creating a global extension that hides the locale choice.

---

## KOTLIN-15: Unnecessary `object` wrapper for top-level type alias

**File:** `TypeAliases.kt:23`

```kotlin
@Suppress("unused")
object TypeAliases

typealias ScrapeRequestAction = suspend () -> ScrapeResults
```

The `object TypeAliases` declaration serves no purpose. Type aliases in Kotlin are top-level declarations and don't need a containing object. The `@Suppress("unused")` annotation on the empty object adds to the confusion.

---

## KOTLIN-16: Using `notNull()` delegate for late-initialized non-null `var` properties

**Files:** `BaseOptions.kt:125-128`, `Agent.kt:174, 180`, `ScrapeRequestWrapper.kt:62`

```kotlin
private var config: Config by notNull()
var configVals: ConfigVals by notNull()
var scrapeResults: ScrapeResults by nonNullableReference()
```

Kotlin provides `lateinit var` for exactly this purpose -- properties that are initialized after construction but before use:

```kotlin
private lateinit var config: Config
lateinit var configVals: ConfigVals
```

`lateinit` provides better error messages (`UninitializedPropertyAccessException` with the property name) and supports `::property.isInitialized` checks. The `notNull()` delegate from Kotlin stdlib and `nonNullableReference()` from the utils library provide the same semantics with more overhead.

Note: `lateinit` cannot be used with primitive types, but all usages here are reference types.

---

## KOTLIN-17: Excessive nesting with scope functions

**File:** `AgentOptions.kt:133-213`

The `assignConfigVals()` method wraps its entire body in `configVals.agent.also { agentConfigVals -> ... }`, creating an unnecessary nesting level for the entire function body:

```kotlin
override fun assignConfigVals() {
  configVals.agent
    .also { agentConfigVals ->
      // 80 lines of code at one extra indent level
    }
}
```

A simple local variable avoids the nesting:

```kotlin
override fun assignConfigVals() {
  val agentConfigVals = configVals.agent
  // 80 lines of code at normal indent level
}
```

Similarly in `Agent.kt:385`, `agentConfigVals.internal.apply { ... }` wraps a 17-line block where `this` refers to the config values. Using a local variable would be clearer.
