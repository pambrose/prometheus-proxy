# Incidental Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the five prometheus-proxy findings from the common-utils extraction review: the agent remote address that is always "Unknown", the proxy that hangs after a failed start, the redundant agent startup-failure steps, an unused `SslSettings` function, and leftover scratch code in the test harness.

**Architecture:** Each finding is its own task and its own commit, bug fixes first. The two bug fixes (Tasks 1 and 2) are test-first; the three cleanups (Tasks 3–5) are behavior-preserving deletions guarded by the existing tests.

**Tech Stack:** Kotlin, gRPC-java (`io.grpc.Grpc`, `ServerTransportFilter`), Guava `Service`, common-utils 5.0.0 (`GenericService`), Kotest `StringSpec`, MockK.

**Spec:** The "Incidental findings in prometheus-proxy" section of the 2026-09-25 common-utils extraction review (items EX-45 and EX-66), re-verified against `master` at `1c0a737` on 2026-09-26:

1. `ProxyServerTransportFilter` reads the remote address with a private `Attributes.Key<String>("remote-addr")` that nothing sets, so every `AgentContext.remoteAddr` is `"Unknown"` (confirmed; the key dates back to 1.3.8).
2. `Agent.releaseAfterFailedStartUp()` repeats the admin, metrics and Zipkin rollback that common-utils 5.0.0's `AbstractGenericService.startUp()` now does itself (confirmed: `Agent.startUp()` only wraps `super.startUp()`).
3. `Proxy.startUp()` starts the gRPC, HTTP, dashboard and cleanup services after `super.startUp()` with no rollback. **Reproduced 2026-09-26:** a second proxy started on a taken agent port threw `BindException` out of `main`, yet 15 s later the process was still running with its admin port listening. The admin server's threads keep the JVM alive, so a restart policy never fires.
4. `SslSettings.getSslContext` is called only from `SslSettingsTest` (confirmed).
5. `MyEnum`, `CustomEnumSerializer` and `TestUtils.main` in `harness/support/HarnessSupport.kt` are unused (confirmed).

## Global Constraints

- Tests use Kotest `StringSpec` with an `init {}` block; MockK where a collaborator needs faking.
- Never bind a product-default port (8080, 8082, 8092, 50051, …) in a test. Use `ServerSocket(0)` for a throwaway port, or a new `TestPorts` entry below 32768.
- Record user-visible changes in **both** `CHANGELOG.md` (`## [Unreleased]`) and `RELEASE_NOTES.md` (`## Unreleased`), each in its own style.
- Gates before the PR: `./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test`, the full `./gradlew test`, and `./gradlew koverVerifyPerClass` in its own invocation.
- Commit only when the maintainer says to. The work is stacked on the `small-cleanups` branch, after its first two commits.

## Review Focus

1. **Address formatting:** IPv4 prints `host:port`, IPv6 prints `[host]:port`, an in-process transport address prints as-is, and a missing address still prints `Unknown`. Nothing may do a reverse DNS lookup (use `hostString`, not `hostName`).
2. **Rollback when a later sub-service fails:** the dashboard or the cleanup service failing to start must still stop gRPC and HTTP, not just the ones before it.
3. **Rollback errors must not mask the startup failure:** the original exception is rethrown, with any stop failures attached as suppressed.
4. **The shutdown hook registered by `super.startUp()`** is removed on rollback. `super.shutDown()` does this; stopping services one by one would not.
5. **The transport-filter-disabled path** (`ProxyServiceImpl.connectAgentWithTransportFilterDisabled`) still records `Unknown`. That is intended: behind nginx the TCP peer is the reverse proxy, not the agent.

---

### Task 1: Record each agent's real remote address

**Files:**
- Modify: `src/main/kotlin/io/prometheus/proxy/ProxyServerTransportFilter.kt`
- Test: `src/test/kotlin/io/prometheus/proxy/ProxyServerTransportFilterTest.kt` (replace the "Remote Address Tests" test)
- Test: `src/test/kotlin/io/prometheus/harness/NettyTestWithAdminMetricsTest.kt` (one new test)
- Modify: `CHANGELOG.md`, `RELEASE_NOTES.md`

**Interfaces:**
- Consumes: `io.grpc.Grpc.TRANSPORT_ATTR_REMOTE_ADDR: Attributes.Key<SocketAddress>`, `ProxyServiceImpl.UNKNOWN_ADDRESS`, `AgentContextManager.agentContextEntries`.
- Produces: `AgentContext.remoteAddr` holds `"host:port"` / `"[v6]:port"`, the in-process address's `toString()`, or `"Unknown"`.

- [ ] **Step 1: Replace the unit test that never checked the address**

In `ProxyServerTransportFilterTest.kt`, replace "transportReady should use remote addr from REMOTE_ADDR_KEY when available" with:

```kotlin
    // gRPC keys attributes by instance, so the filter must read gRPC's own key: a key the filter created itself
    // (as it did since 1.3.8) was never set, and every agent's address read "Unknown".
    "transportReady should record the transport's remote address" {
      listOf(
        InetSocketAddress("192.168.1.100", 50000) to "192.168.1.100:50000",
        InetSocketAddress("::1", 50000) to "[0:0:0:0:0:0:0:1]:50000",
      ).forEach { (address, expected) ->
        val (mockProxy, agentContextManager) = createMockProxy()
        val attrs = Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, address).build()

        val agentId = ProxyServerTransportFilter(mockProxy).transportReady(attrs).get(AGENT_ID_KEY).shouldNotBeNull()

        agentContextManager.getAgentContext(agentId).shouldNotBeNull().remoteAddr shouldBe expected
      }
    }

    "transportReady should record Unknown when the transport has no remote address" {
      val (mockProxy, agentContextManager) = createMockProxy()

      val agentId =
        ProxyServerTransportFilter(mockProxy).transportReady(Attributes.EMPTY).get(AGENT_ID_KEY).shouldNotBeNull()

      agentContextManager.getAgentContext(agentId).shouldNotBeNull().remoteAddr shouldBe UNKNOWN_ADDRESS
    }
```

Imports: `io.grpc.Grpc`, `java.net.InetSocketAddress`, `io.prometheus.proxy.ProxyServiceImpl.Companion.UNKNOWN_ADDRESS`, `io.kotest.matchers.shouldBe` (if not already present).

- [ ] **Step 2: Add the end-to-end check over a real Netty transport**

In `NettyTestWithAdminMetricsTest`'s `init` block, after the existing tests:

```kotlin
    // Netty puts the peer address in the transport attributes before transportReady(); the in-process tests can't
    // show that the filter reads the key Netty actually sets.
    "the proxy should record the connected agent's remote address" {
      val addresses = proxy.agentContextManager.agentContextEntries.map { it.value.remoteAddr }

      addresses.shouldNotBeEmpty()
      addresses.forEach { it shouldMatch Regex("""(127\.0\.0\.1|\[0:0:0:0:0:0:0:1]):\d+""") }
    }
```

The agent connects to `localhost`, which may resolve to IPv4 or IPv6, hence both forms.

- [ ] **Step 3: Run the tests and watch them fail**

Run: `./gradlew test --tests 'io.prometheus.proxy.ProxyServerTransportFilterTest' --tests 'io.prometheus.harness.NettyTestWithAdminMetricsTest'`
Expected: FAIL. "should record the transport's remote address" gets `Unknown` instead of `192.168.1.100:50000`, and the Netty test gets `Unknown`. The "no remote address" test passes already.

- [ ] **Step 4: Read gRPC's key and format the address**

In `ProxyServerTransportFilter.kt`:

```kotlin
  override fun transportReady(attributes: Attributes): Attributes {
    val remoteAddress = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)?.let(::displayAddress) ?: UNKNOWN_ADDRESS
```

Delete `REMOTE_ADDR` and `REMOTE_ADDR_KEY` from the companion object, and add to it:

```kotlin
    // host:port without a reverse DNS lookup (hostString, not hostName), an IPv6 host in brackets, and any other
    // address type (the in-process transport's) as it prints itself.
    private fun displayAddress(address: SocketAddress): String =
      if (address is InetSocketAddress) {
        val host = address.hostString
        if (':' in host) "[$host]:${address.port}" else "$host:${address.port}"
      } else {
        address.toString()
      }
```

Imports: `io.grpc.Grpc`, `java.net.InetSocketAddress`, `java.net.SocketAddress`.

- [ ] **Step 5: Run the tests and watch them pass**

Run: the Step 3 command, plus `--tests 'io.prometheus.harness.ProxyWebDashboardTest' --tests 'io.prometheus.proxy.dashboard.*'`
Expected: PASS. The dashboard tests confirm the host line still renders with a real address.

- [ ] **Step 6: Record it**

- `CHANGELOG.md`, Unreleased, `### Bug Fixes`: "Fix every agent's remote address reading `Unknown` on the dashboard and in the proxy's logs. `ProxyServerTransportFilter` read the address with an attribute key it created itself, which gRPC never set; it now reads gRPC's `TRANSPORT_ATTR_REMOTE_ADDR` and records `host:port`. With the transport filter disabled the address is still `Unknown`, since the peer there is the reverse proxy"
- `RELEASE_NOTES.md`, Unreleased, `### Bug Fixes`: "**The dashboard shows each agent's real address.** It showed `Unknown` for every agent; it now shows the address and port the agent connected from."

- [ ] **Step 7: Commit** (when the maintainer says to)

```bash
git add src/main/kotlin/io/prometheus/proxy/ProxyServerTransportFilter.kt \
  src/test/kotlin/io/prometheus/proxy/ProxyServerTransportFilterTest.kt \
  src/test/kotlin/io/prometheus/harness/NettyTestWithAdminMetricsTest.kt CHANGELOG.md RELEASE_NOTES.md
git commit -m "Record each agent's real remote address"
```

---

### Task 2: Stop what the proxy started when its startup fails

**Files:**
- Modify: `src/main/kotlin/io/prometheus/Proxy.kt` (`startUp()`)
- Test: `src/test/kotlin/io/prometheus/proxy/ProxyTest.kt` (one new test)
- Modify: `CHANGELOG.md`, `RELEASE_NOTES.md`

**Interfaces:**
- Consumes: the proxy's `grpcService`, `httpService`, `dashboardService?`, `agentCleanupService?`; `AbstractGenericService.shutDown()` (stops the admin, metrics and Zipkin services and removes the shutdown hook); `metricsService` (visible to tests, as `AgentTest` uses it).
- Produces: after a failed `startSync()`, the proxy is `FAILED` and holds no port, thread, or shutdown hook.

- [ ] **Step 1: Write the failing test**

In `ProxyTest.kt` (imports: `java.net.ServerSocket`, `com.google.common.util.concurrent.Service`, `io.kotest.assertions.assertSoftly`, `io.prometheus.metrics.model.registry.PrometheusRegistry`):

```kotlin
    // ==================== Failed startup ====================

    // Guava calls shutDown() only after startUp() succeeds. When a sub-service failed to start, the admin and metrics
    // servers super.startUp() had started and the sub-services already running were left up: a standalone proxy with
    // a taken port logged the failure but never exited, its admin server's threads keeping the JVM alive.
    "a failed startup should stop what the proxy started" {
      PrometheusRegistry.defaultRegistry.clear()
      val adminPort = ServerSocket(0).use { it.localPort }
      val metricsPort = ServerSocket(0).use { it.localPort }
      ServerSocket(0).use { takenHttpPort ->
        val proxy =
          Proxy(
            options =
              ProxyOptions(
                listOf(
                  "--port", "${takenHttpPort.localPort}",
                  "--admin", "--admin_port", "$adminPort",
                  "--metrics", "--metrics_port", "$metricsPort",
                ),
              ),
            inProcessServerName = "failed-start-${System.nanoTime()}",
            testMode = true,
          )

        shouldThrow<IllegalStateException> { proxy.startSync() }

        // Captured as values first, so each check reports on its own inside assertSoftly.
        val state = proxy.state()
        val adminPortFree = runCatching { ServerSocket(adminPort).close() }.isSuccess
        val metricsRunning = proxy.metricsService.isRunning

        assertSoftly {
          state shouldBe Service.State.FAILED
          adminPortFree.shouldBeTrue()
          metricsRunning.shouldBeFalse()
        }
      }
    }
```

The in-process gRPC server starts first, so the HTTP service's bind failure also exercises stopping a sub-service that already started.

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'io.prometheus.proxy.ProxyTest'`
Expected: FAIL on `adminPortFree` (and `metricsRunning`): the admin and metrics servers are still up.

- [ ] **Step 3: Roll back a failed startup**

Replace the body of `Proxy.startUp()` after `super.startUp()`:

```kotlin
  override fun startUp() {
    super.startUp()

    // Guava calls shutDown() only after startUp() succeeds, so when a sub-service fails to start, stop the ones that
    // started, most recent first, then what super.startUp() started; super.shutDown() also removes its shutdown hook.
    // Left running, the admin server's threads kept a standalone proxy's JVM alive after main() threw.
    val stopActions = ArrayDeque<() -> Unit>()
    runCatching {
      grpcService.startSync()
      stopActions.addFirst { grpcService.stopSync() }
      httpService.startSync()
      stopActions.addFirst { httpService.stopSync() }
      dashboardService?.also { dashboard ->
        dashboard.startSync()
        stopActions.addFirst { dashboard.stopSync() }
      }

      // (keep the existing transportFilterDisabled comment here)
      if (agentCleanupService != null) {
        if (!proxyConfigVals.internal.staleAgentCheckEnabled)
          logger.warn { "Forcing agent eviction thread on: transportFilterDisabled requires stale agent cleanup" }
        agentCleanupService.startSync()
        stopActions.addFirst { agentCleanupService.stopSync() }
      } else {
        logger.info { "Agent eviction thread not started" }
      }
    }.exceptionOrNull()?.let { e ->
      stopActions.forEach { stop -> runCatching(stop).exceptionOrNull()?.let(e::addSuppressed) }
      runCatching { super.shutDown() }.exceptionOrNull()?.let(e::addSuppressed)
      throw e
    }
  }
```

The stop steps are recorded as functions, as common-utils' own `AbstractGenericService.startUp()` does: `startSync()` and `stopSync()` are members of common-utils' `GenericIdleService` and `GenericExecutionThreadService`, which share no type, so a `Service`-typed helper (this plan's first draft) doesn't compile. common-utils' `runEach` helper is private, so the loop stays local.

- [ ] **Step 4: Run the tests and watch them pass**

Run: `./gradlew test --tests 'io.prometheus.proxy.ProxyTest' --tests 'io.prometheus.harness.*'`
Expected: PASS. The harness specs confirm a normal start and stop are unchanged.

- [ ] **Step 5: Re-run the reproduction against the JAR**

```bash
./gradlew -q proxyJar
java -jar build/libs/prometheus-proxy.jar -p 28180 -a 28151 &                  # holds the agent port
java -jar build/libs/prometheus-proxy.jar -p 28181 -a 28151 -r -i 28194; echo "exit=$?"
```

Expected: the second proxy logs the `BindException`, exits non-zero within a few seconds, and port 28194 is free. Before the fix it was still running 15 s later with 28194 listening. Stop the first proxy afterwards.

- [ ] **Step 6: Record it**

- `CHANGELOG.md`, Unreleased, `### Bug Fixes`: "Fix a proxy that failed to start (for example, on a taken agent or HTTP port) hanging instead of exiting. `Proxy.startUp()` started its gRPC, HTTP, dashboard and cleanup services after the admin and metrics servers with no rollback, and Guava doesn't call `shutDown()` after a failed start, so those servers' threads kept the JVM alive. A failed start now stops everything it started, most recent first, and removes the shutdown hook"
- `RELEASE_NOTES.md`, Unreleased, `### Bug Fixes`: "**A proxy that fails to start now exits.** With a port already taken, it logged the error but kept running, holding its admin and metrics ports, so a service manager's restart policy never fired."

- [ ] **Step 7: Commit** (when the maintainer says to): `git commit -m "Stop what the proxy started when its startup fails"` with `Proxy.kt`, `ProxyTest.kt` and the two logs.

`specs/tla/ProxyRegistry.tla` models `Proxy.removeAgentContext`, not `Proxy.startUp()`, so no TLA+ change is needed.

---

### Task 3: Drop the agent's redundant startup-failure steps

**Files:**
- Modify: `src/main/kotlin/io/prometheus/Agent.kt` (`releaseAfterFailedStartUp()` and its comment)

**Interfaces:**
- Consumes: common-utils 5.0.0 `AbstractGenericService.startUp()`, which stops the Zipkin, metrics, JMX and servlet services it started when it throws.
- Produces: unchanged behavior; `AgentTest`'s "a failed startup should release what the agent holds and leave stop() safe to call" still passes.

- [ ] **Step 1: Run the guarding test (baseline)**

Run: `./gradlew test --tests 'io.prometheus.agent.AgentTest' --tests 'io.prometheus.harness.EmbeddedAgentApiTest'`
Expected: PASS.

- [ ] **Step 2: Keep only the agent's own steps**

```kotlin
  // super.startUp() stops the admin, metrics and Zipkin services it started when it fails (common-utils 5.0.0), but
  // not the gRPC channel and HTTP client cache this class's constructor built. Each step runs whether or not the
  // other succeeds.
  private fun releaseAfterFailedStartUp() {
    val steps: List<() -> Unit> =
      [
        { grpcService.shutDown() },
        { runBlocking { agentHttpService.close() } },
      ]
    steps.forEach { step ->
      runCatching(step).onFailure { e -> logger.debug(e) { "Releasing after a failed startup: ${e.message}" } }
    }
  }
```

- [ ] **Step 3: Run the Step 1 command again**

Expected: PASS. Its `metricsService.isRunning.shouldBeFalse()` now holds through common-utils' rollback.

- [ ] **Step 4: Commit** (when the maintainer says to): `git commit -m "Drop the agent's startup-failure steps common-utils now does"`. There's no behavior change, so no log entry.

---

### Task 4: Remove the unused `SslSettings.getSslContext`

**Files:**
- Modify: `src/main/kotlin/io/prometheus/agent/SslSettings.kt`
- Test: `src/test/kotlin/io/prometheus/agent/SslSettingsTest.kt`

- [ ] **Step 1: Delete** `getSslContext(fileName, password)` from `SslSettings`, and its now-unused `javax.net.ssl.SSLContext` import. `SslSettings` is `internal`, so this is not a public API change.
- [ ] **Step 2: Delete its two tests** ("getSslContext should throw for non-existent keystore" and "getSslContext should return a TLS context for valid keystore"), and drop `getSslContext` from the comment that lists the success branches the valid-keystore tests exercise.
- [ ] **Step 3: Verify**

Run: `grep -rn getSslContext src docs website` (expect nothing), then `./gradlew test --tests 'io.prometheus.agent.SslSettingsTest'` and, in its own invocation, `./gradlew koverVerifyPerClass`.
Expected: PASS. `getTrustManager`'s tests still cover `getKeyStore` and `getTrustManagerFactory`.

- [ ] **Step 4: Commit** (when the maintainer says to): `git commit -m "Remove the unused SslSettings.getSslContext"`.

---

### Task 5: Remove the scratch code from `HarnessSupport.kt`

**Files:**
- Modify: `src/test/kotlin/io/prometheus/harness/support/HarnessSupport.kt`

- [ ] **Step 1: Delete** `enum class MyEnum`, `object CustomEnumSerializer`, and `TestUtils.main`, and the imports only they used: `kotlinx.serialization.KSerializer`, `.Serializable`, `.descriptors.PrimitiveKind`, `.descriptors.PrimitiveSerialDescriptor`, `.descriptors.SerialDescriptor`, `.encoding.Decoder`, `.encoding.Encoder`, `.json.Json`. Keep `TestUtils` and its `logger`, which the rest of the object uses.
- [ ] **Step 2: Verify**

Run: `grep -rn "MyEnum\|CustomEnumSerializer" src` (expect nothing), then `./gradlew lintKotlinTest compileTestKotlin`.
Expected: PASS.

- [ ] **Step 3: Commit** (when the maintainer says to): `git commit -m "Remove leftover scratch code from the test harness"`.

---

### Finish

- [ ] Gates: `./gradlew detekt && ./gradlew lintKotlinMain lintKotlinTest && ./gradlew build -x test`, the full `./gradlew test`, then `./gradlew koverVerifyPerClass` on its own.
- [ ] Open one PR for the five commits (no `(#N)` in the title), and merge after CI is green.

### As built

- **Task 2** uses the stop-function list shown in Step 3 (the first draft's `Service`-typed helper didn't compile), and gained a second test from the final review, "a failed startup should also stop the sub-services that started before the failure". It fails at the dashboard, after a Netty gRPC server and the HTTP service have started, because the first test passes even with the sub-service stops removed.
- **Task 5** also renamed `HarnessSupport.kt` to `TestUtils.kt`: with the scratch classes gone, `TestUtils` is the file's only top-level class, and detekt's `MatchingDeclarationName` rule failed the final gates.
