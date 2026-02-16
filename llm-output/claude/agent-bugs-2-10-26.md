# Agent Package Bug Report

## 1. ~~RACE CONDITION: Backlog Counter Can Go Negative~~ FIXED

**File**: `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`
**Lines**: 292 (increment), 369 (decrement)
**Severity**: CRITICAL

**Status**: FIXED — The backlog increment/decrement was moved to `Agent.kt` (lines 303, 311) inside a `try/finally`
block within each `launch` coroutine. The increment happens at the start of the block and the decrement is guaranteed by
the `finally` clause, preventing negative counts regardless of execution order or exceptions.

~~The `scrapeRequestBacklogSize` counter exhibits a race condition:~~
~~- Line 292: `agent.scrapeRequestBacklogSize += 1` happens AFTER `connectionContext.sendScrapeRequestAction()`~~
~~- Line 369: `agent.scrapeRequestBacklogSize -= 1` happens inside `processScrapeResults()` which runs in a separate
coroutine~~

~~Because the consumer coroutine (`processScrapeResults`) can decrement the counter before the producer has incremented
it, the counter can go negative.~~

~~**Impact**: The counter is used for health checks (Agent.kt, lines 363-369). A negative value would incorrectly
trigger unhealthy status.~~

---

## 2. ~~MISSING BACKLOG DECREMENT IN ERROR CASE~~ FIXED

**File**: `src/main/kotlin/io/prometheus/Agent.kt` and `AgentGrpcService.kt`
**Lines**: Agent.kt 298-306, AgentGrpcService.kt 292, 369
**Severity**: MEDIUM

**Status**: FIXED — The backlog increment/decrement was refactored into `Agent.kt` (lines 303-312) using a `try/finally`
block. The decrement in the `finally` clause guarantees it runs even if `scrapeRequestAction.invoke()` throws an
exception, preventing backlog counter leaks.

~~In the scrape request processing loop (Agent.kt lines 298-306), if an exception occurs in the inner `launch` block
before `connectionContext.sendScrapeResults(scrapeResponse)` is called, the `scrapeRequestBacklogSize` will have been
incremented (in AgentGrpcService.kt line 292) but never decremented.~~

~~The decrement only happens in `processScrapeResults()` (AgentGrpcService.kt line 369) when the result is actually
sent. If the scrape request action throws an exception before returning a result, the backlog counter leaks.~~

~~**Scenario**:~~
~~1. `readRequestsFromProxy` increments backlog (AgentGrpcService.kt:292)~~
~~2. A scrape request is sent to the channel~~
~~3. The lambda in the `launch` block (Agent.kt:299-305) executes~~
~~4. `scrapeRequestAction.invoke()` throws an exception~~
~~5. The exception is not caught, so `connectionContext.sendScrapeResults()` is never called~~
~~6. The result is never sent to `processScrapeResults()`, so decrement never happens~~
~~7. Backlog counter remains elevated~~

---

## 3. ~~RACE CONDITION: AgentClientInterceptor Unsynchronized agentId Assignment~~ FIXED

**File**: `src/main/kotlin/io/prometheus/agent/AgentClientInterceptor.kt`
**Lines**: 53-59
**Severity**: HIGH

**Status**: FIXED — The check-and-set of `agent.agentId` is now wrapped in a `synchronized(agent) { ... }` block (lines
53-62), making the isEmpty check and assignment atomic as a pair.

~~Multiple gRPC interceptor threads could concurrently check and assign `agent.agentId`:~~
~~- Line 53: `if (agent.agentId.isEmpty())` - unsynchronized read~~
~~- Line 56: `agent.agentId = agentId` - unsynchronized write~~

~~Even though `agentId` is backed by `nonNullableReference` (which uses AtomicReference internally), the if-check
followed by assignment is not atomic as a pair. This could result in the same agentId being assigned multiple times by
different threads.~~

~~**Impact**: The `agentId` field is critical for identifying the agent to the proxy. Concurrent assignments could cause
inconsistency.~~

---

## 4. ~~POTENTIAL DEADLOCK: Channel Close During Active Coroutine Execution~~ FIXED

**File**: `src/main/kotlin/io/prometheus/Agent.kt`
**Lines**: 244-313
**Severity**: HIGH

**Status**: FIXED — The original deadlock concern (`runBlocking { accessMutex.withLock {} }` in `close()`) was already
resolved by switching to `synchronized(closeLock)`. The remaining issue was an asymmetry: only 2 of 4 coroutines had
`invokeOnCompletion { connectionContext.close() }`. Added `invokeOnCompletion { connectionContext.close() }` to the
`writeResponsesToProxyUntilDisconnected` and scrape consumer coroutines so all 4 coroutines trigger prompt cleanup on
failure. This is safe because `close()` is idempotent (guarded by `synchronized(closeLock)` + `if (!disconnected)`).

---

## 5. ~~INCONSISTENT SYNCHRONIZATION: resetGrpcStubs~~ FIXED

**File**: `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`
**Lines**: 124-169
**Severity**: ~~MEDIUM~~
**Status**: FIXED — The unsynchronized `channel` access in `AgentClientInterceptor.kt` line 43 was the real issue:
`agent.grpcService.channel.newCall(method, callOptions)` bypassed the gRPC interceptor chain and read `channel` without
holding `grpcLock`. Changed the interceptor to use the `next` parameter (`next.newCall(method, callOptions)`), which is
the correct gRPC interceptor contract. This eliminates the unsynchronized access entirely — all remaining `channel`
reads are within `grpcLock.withLock` blocks. The reentrant lock usage in `resetGrpcStubs()` calling `shutDown()` was
always correct.

~~- `shutDown()` (lines 124-130) is synchronized with `synchronized(this)`~~
~~- `resetGrpcStubs()` (lines 132-169) is also synchronized with `synchronized(this)`~~

~~Inside `resetGrpcStubs()`, it calls `shutDown()` (line 137), which attempts to re-enter the same monitor. While Kotlin
allows reentrant synchronization, the `channel` field is accessed without synchronization elsewhere (e.g.,
AgentClientInterceptor.kt line 43).~~

---

## 6. ~~CHANNEL RESOURCE LEAK ON ERROR~~ FIXED

**File**: `src/main/kotlin/io/prometheus/agent/AgentGrpcService.kt`
**Lines**: 377-378, 413-416
**Severity**: ~~MEDIUM~~
**Status**: FIXED — Added a `try/finally` block around the producer coroutine (`processScrapeResults`) that closes both
`nonChunkedChannel` and `chunkedChannel` when the producer finishes. This follows the standard producer-consumer
pattern: the producer closes channels when done, signaling the consumers' `consumeAsFlow()` to complete. Previously, if
the producer finished before the gRPC streams ended, consumers would hang waiting for channel data, preventing
`coroutineScope` from returning and the outer `finally` from executing. The outer `finally` block is retained as a
safety net but is now effectively a no-op since `Channel.close()` is idempotent.

~~The `nonChunkedChannel` and `chunkedChannel` created at lines 377-378 are only guaranteed to be closed in
the `finally` block (lines 413-416). If an exception occurs in the `coroutineScope` block before both channels are
properly consumed, and if `connectionContext.close()` is called before all launched coroutines have properly attached
their `invokeOnCompletion` handlers, channel closure ordering may be incorrect.~~

---

## 7. ~~MISSING ERROR HANDLING: agentId Assignment Failure~~ FIXED

**File**: `src/main/kotlin/io/prometheus/agent/AgentClientInterceptor.kt`
**Lines**: 54-59
**Severity**: ~~MEDIUM~~
**Status**: FIXED — Replaced `logger.error { "Headers missing AGENT_ID key" }` with
`error("Headers missing AGENT_ID key")` which throws `IllegalStateException`. The agent now fails fast if the proxy
doesn't provide an agentId header, rather than proceeding in an invalid state. The gRPC call will fail and the agent's
reconnection logic will handle recovery.

~~- Line 54: `headers.get(META_AGENT_ID_KEY)` could return null~~
~~- Line 59: If the header is missing, only an error log is produced, but execution continues~~
~~- The gRPC request proceeds without a valid agentId, causing silent failures downstream~~

~~**Impact**: If the proxy fails to send the agentId header, the agent will proceed in an invalid state without proper
error handling or recovery.~~

---

## 8. ~~RACE CONDITION: Concurrent HashMap Read/Clear~~ FIXED

**File**: `src/main/kotlin/io/prometheus/agent/AgentPathManager.kt`
**Lines**: 32-36
**Severity**: ~~MEDIUM~~
**Status**: FIXED — Replaced `ConcurrentHashMap` with a `HashMap` synchronized on `pathContextMap` for all operations (
`get`, `clear`, `registerPath`, `unregisterPath`), matching the `ProxyPathManager` pattern. All map access is now
guarded by `synchronized(pathContextMap)`, ensuring `get()` cannot read from a partially-cleared or
partially-repopulated map. The gRPC calls in `registerPath`/`unregisterPath` remain outside the synchronized blocks (
only the map mutations are synchronized).

~~While `ConcurrentHashMap` is used (line 32), there's a timing issue:~~
~~- Line 34: `operator fun get()` retrieves a value without holding a lock~~
~~- Line 36: `fun clear()` clears the entire map without holding a lock~~
~~- Line 78-79: `registerPath()` adds entries~~

~~These operations are individually atomic, but sequences of operations are not. For example:~~
~~1. Thread A calls `get("path")` and gets a PathContext~~
~~2. Thread B calls `clear()` removing all paths~~
~~3. Thread A uses the retrieved PathContext object (which is now stale)~~

~~This is a use-after-clear race condition.~~

---

## Summary

| # | Severity   | File                          | Lines             | Category           | Status |
|---|------------|-------------------------------|-------------------|--------------------|--------|
| 1 | CRITICAL   | AgentGrpcService.kt           | 292, 369          | Race Condition     | FIXED  |
| 2 | MEDIUM     | Agent.kt, AgentGrpcService.kt | 298-306, 292, 369 | Resource Leak      | FIXED  |
| 3 | HIGH       | AgentClientInterceptor.kt     | 53-59             | Race Condition     | FIXED  |
| 4 | HIGH       | Agent.kt                      | 244-313           | Deadlock Potential | FIXED  |
| 5 | ~~MEDIUM~~ | AgentGrpcService.kt           | 124-169           | Inconsistent Sync  | FIXED  |
| 6 | ~~MEDIUM~~ | AgentGrpcService.kt           | 377-416           | Resource Leak      | FIXED  |
| 7 | ~~MEDIUM~~ | AgentClientInterceptor.kt     | 54-59             | Error Handling     | FIXED  |
| 8 | ~~MEDIUM~~ | AgentPathManager.kt           | 32-36             | Race Condition     | FIXED  |
