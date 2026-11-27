/*
 * Copyright © 2026 Paul Ambrose
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.prometheus.common.CountingAgentMetrics
import ch.qos.logback.classic.Level
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.agent.discovery.DiscoveredPath
import io.prometheus.common.testConfigVals
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.grpc.PathNotice
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.pathNotice
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.unregisterPathResponse
import io.prometheus.common.captureLogs
import io.prometheus.common.deadlineExceeded
import io.kotest.matchers.ints.shouldBeLessThan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

@Suppress("LargeClass")
class AgentPathManagerTest : StringSpec() {
  // Captures the "Registered ..." lines recordRegistered emits, the only place the agent's log reports whether a
  // configured filter actually attached to a path.
  private suspend fun captureRegistrationLogs(block: suspend () -> Unit): List<String> =
    captureLogs<AgentPathManager> { block() }.map { it.formattedMessage }.filter { it.startsWith("Registered ") }

  // Runs [action] [times] times, advancing this clock by [step] before each -- how a retry or reconcile loop ticks.
  private suspend fun TestTimeSource.tick(
    times: Int,
    step: Duration,
    action: suspend () -> Unit,
  ) {
    repeat(times) {
      this += step
      action()
    }
  }

  // [filtersHocon] is spliced into `agent.filters` (empty means no filters), which is what makes the
  // path manager compile and attach a MetricFilter to a matching registered path; [pathConfigsHocon] into
  // `agent.pathConfigs`; and [agentHocon] into the `agent` block as is, for any other setting.
  private fun createMockAgent(
    filtersHocon: String = "",
    pathConfigsHocon: String = "",
    agentHocon: String = "",
  ): Agent {
    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

    // Default happy-path stubs (valid=true, pathId=1); tests needing specific responses re-stub.
    coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }
    coEvery { mockGrpcService.reclaimPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }
    coEvery { mockGrpcService.unregisterPathOnProxy(any()) } returns unregisterPathResponse { valid = true }

    // Create a real ConfigVals with minimal config
    val configVals = testConfigVals(
      """
      agent {
        pathConfigs = [$pathConfigsHocon]
        filters = [$filtersHocon]
        $agentHocon
      }
      proxy { auth = [] }
      """,
    )

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.grpcService } returns mockGrpcService
    every { mockAgent.configVals } returns configVals
    every { mockAgent.isTestMode } returns true

    return mockAgent
  }

  // The `agent` HOCON that sets agent.internal.rejectedPathRetryMaxSecs, or nothing to keep the default.
  private fun retryMaxHocon(retryMaxSecs: Int?): String =
    retryMaxSecs?.let { "internal.rejectedPathRetryMaxSecs = $it" }.orEmpty()

  init {
    "registerPath should register path with proxy" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 123L
      }

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics", """{"job":"test"}""")

      coVerify { agent.grpcService.registerPathOnProxy("metrics", """{"job":"test"}""", any(), any()) }

      val context = manager["metrics"]
      context.shouldNotBeNull()
      context.pathId shouldBe 123L
      context.path shouldBe "metrics"
      context.url shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics"
    }

    "registerPath should strip leading slash from path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 456L
      }

      manager.registerPath("/metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      coVerify { agent.grpcService.registerPathOnProxy("metrics", "{}", any(), any()) }

      val context = manager["metrics"]
      context.shouldNotBeNull()
      context.path shouldBe "metrics"
    }

    "registerPath should use default empty labels when not provided" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 789L
      }

      manager.registerPath("health", "http://localhost:$PROXY_HTTP_PORT/health")

      coVerify { agent.grpcService.registerPathOnProxy("health", "{}", any(), any()) }
    }

    // A whitespace path can never be scraped, and the proxy refuses it, so it is refused here, before the call, as an
    // empty one is.
    "registerPath should throw when path or url is blank" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      shouldThrow<IllegalArgumentException> {
        manager.registerPath("   ", "http://localhost:$PROXY_HTTP_PORT/metrics")
      }.message shouldContain "Blank path"

      shouldThrow<IllegalArgumentException> { manager.registerPath("metrics", "   ") }
        .message shouldContain "Blank URL"
    }

    "unregisterPath should throw when path is blank" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      shouldThrow<IllegalArgumentException> { manager.unregisterPath("   ") }
        .message shouldContain "Blank path"
    }

    "registerPath should throw when path is empty" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.registerPath("", "http://localhost:$PROXY_HTTP_PORT/metrics")
      }

      exception.message shouldContain "Blank path"
    }

    "registerPath should throw when url is empty" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.registerPath("metrics", "")
      }

      exception.message shouldContain "Blank URL"
    }

    "unregisterPath should remove path from map" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 123L
      }
      coEvery { agent.grpcService.unregisterPathOnProxy(any()) } returns unregisterPathResponse {
        valid = true
      }

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")
      manager["metrics"].shouldNotBeNull()

      manager.unregisterPath("metrics")

      coVerify { agent.grpcService.unregisterPathOnProxy("metrics") }
      manager["metrics"].shouldBeNull()
    }

    "unregisterPath should strip leading slash from path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 123L
      }
      coEvery { agent.grpcService.unregisterPathOnProxy(any()) } returns unregisterPathResponse {
        valid = true
      }

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      manager.unregisterPath("/metrics")

      coVerify { agent.grpcService.unregisterPathOnProxy("metrics") }
      manager["metrics"].shouldBeNull()
    }

    "unregisterPath should throw when path is empty" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.unregisterPath("")
      }

      exception.message shouldContain "Blank path"
    }

    "unregisterPath should not throw when path not in map" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.unregisterPathOnProxy(any()) } returns unregisterPathResponse {
        valid = true
      }

      // Should not throw
      manager.unregisterPath("nonexistent")

      coVerify { agent.grpcService.unregisterPathOnProxy("nonexistent") }
    }

    // The proxy rejects an unregister (valid=false) when it no longer maps the path to this agent: the path is
    // gone, or another agent now holds it. Either way the local entry is stale and must not be kept.
    "unregisterPath should remove the local entry when the proxy rejects the unregister" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")
      coEvery { agent.grpcService.unregisterPathOnProxy("metrics") } throws
        RequestFailureException("unregisterPathOnProxy() - Unable to remove path /metrics - path not found")

      manager.unregisterPath("metrics")

      manager["metrics"].shouldBeNull()
    }

    // A transport failure says nothing about the proxy's state, so the entry stays and the error propagates.
    "unregisterPath should keep the local entry when the unregister fails in transport" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")
      coEvery { agent.grpcService.unregisterPathOnProxy("metrics") } throws StatusException(Status.UNAVAILABLE)

      shouldThrow<StatusException> { manager.unregisterPath("metrics") }

      manager["metrics"].shouldNotBeNull()
    }

    "get operator should return null for non-existent path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      manager["nonexistent"].shouldBeNull()
    }

    "get operator should return PathContext for registered path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 999L
      }

      manager.registerPath("test", "http://localhost:$PROMETHEUS_PORT/test", """{"env":"prod"}""")

      val context = manager["test"]
      context.shouldNotBeNull()
      context.pathId shouldBe 999L
      context.path shouldBe "test"
      context.url shouldBe "http://localhost:$PROMETHEUS_PORT/test"
      context.labels shouldBe """{"env":"prod"}"""
    }

    // A proxy older than #416 drops only the first of a request's leading slashes, so asked for //metrics it sends the
    // agent /metrics. Keys have no leading slash, so the lookup strips them all, and only them.
    "get operator should find a registered path asked for with leading slashes" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      listOf("metrics", "/metrics", "//metrics", "///metrics").forEach { requested ->
        manager[requested].shouldNotBeNull().path shouldBe "metrics"
      }
      listOf("/", "//", "metrics/", "/other_metrics").forEach { requested -> manager[requested].shouldBeNull() }
    }

    "clear should remove all paths" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }

      manager.registerPath("path1", "http://localhost:$PROXY_HTTP_PORT/path1")
      manager.registerPath("path2", "http://localhost:$PROXY_HTTP_PORT/path2")
      manager.registerPath("path3", "http://localhost:$PROXY_HTTP_PORT/path3")

      manager["path1"].shouldNotBeNull()
      manager["path2"].shouldNotBeNull()
      manager["path3"].shouldNotBeNull()

      manager.clear()

      manager["path1"].shouldBeNull()
      manager["path2"].shouldBeNull()
      manager["path3"].shouldBeNull()
    }

    "pathMapSize should return size from grpc service" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.pathMapSize() } returns 42

      val size = manager.pathMapSize()

      size shouldBe 42
      coVerify { agent.grpcService.pathMapSize() }
    }

    "PathContext data class should have correct properties" {
      val context = AgentPathManager.PathContext(
        pathId = 123L,
        path = "metrics",
        url = "http://localhost:$PROXY_HTTP_PORT/metrics",
        labels = """{"job":"test"}""",
        source = PathSource.STATIC,
      )

      context.pathId shouldBe 123L
      context.path shouldBe "metrics"
      context.url shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics"
      context.labels shouldBe """{"job":"test"}"""
      context.source shouldBe PathSource.STATIC
    }

    // ---- reconcileDiscoveredPaths ----
    // grpc is captured in a local so exactly-count verifies don't also count the grpcService getter.

    "reconcile registers new discovered paths tagged DISCOVERED" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      manager.reconcileDiscoveredPaths(
        [
          DiscoveredPath("a", "a_metrics", "http://a/m", "{}"),
          DiscoveredPath("b", "b_metrics", "http://b/m", "{}"),
        ],
      )

      manager["a_metrics"].shouldNotBeNull().source shouldBe PathSource.DISCOVERED
      manager["b_metrics"].shouldNotBeNull()
      coVerify(exactly = 1) { grpc.registerPathOnProxy("a_metrics", any(), any(), any()) }
    }

    "reconcile unregisters discovered paths no longer desired" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      manager.reconcileDiscoveredPaths(
        [
          DiscoveredPath("a", "a_metrics", "http://a/m", "{}"),
          DiscoveredPath("b", "b_metrics", "http://b/m", "{}"),
        ],
      )
      manager.reconcileDiscoveredPaths([DiscoveredPath("b", "b_metrics", "http://b/m", "{}")])

      manager["a_metrics"].shouldBeNull()
      manager["b_metrics"].shouldNotBeNull()
      coVerify(exactly = 1) { grpc.unregisterPathOnProxy("a_metrics") }
    }

    "reconcile leaves an unchanged discovered path alone (no re-register)" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      val desired = [DiscoveredPath("a", "a_metrics", "http://a/m", "{}")]
      manager.reconcileDiscoveredPaths(desired)
      manager.reconcileDiscoveredPaths(desired)

      // Registered exactly once across two identical reconciles.
      coVerify(exactly = 1) { grpc.registerPathOnProxy("a_metrics", any(), any(), any()) }
    }

    // Registered again as a reclaim, the edit never takes the path from an agent that took it meanwhile, and replaces
    // an exclusive path in place: unregistering it first let an agent waiting on the path take it in between.
    "reconcile should register an edited exclusive path again in place, as a reclaim" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v1", "{}")])
      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v2", "{}")])

      manager["a_metrics"].shouldNotBeNull().url shouldBe "http://a/v2"
      coVerify(exactly = 0) { grpc.unregisterPathOnProxy(any()) }
      coVerify(exactly = 1) { grpc.registerPathOnProxy("a_metrics", any(), any(), any()) }
      coVerify(exactly = 1) { grpc.reclaimPathOnProxy("a_metrics", any(), "http://a/v2", "DISCOVERED") }
    }

    // A proxy keeps a consolidated path's labels when an agent registers it again, so the edit unregisters it first.
    // Another agent took over the path, so the proxy rejects this agent's unregister. The new URL must still be
    // applied rather than blocked forever behind the failed unregister.
    "reconcile should apply a changed URL of a consolidated path when the proxy rejects the unregister" {
      val agent = createMockAgent()
      every { agent.options.consolidated } returns true
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v1", "{}")])
      coEvery { grpc.unregisterPathOnProxy("a_metrics") } throws
        RequestFailureException(
          "unregisterPathOnProxy() - Unable to remove path /a_metrics - invalid agentId: 1 -- [2]",
        )

      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v2", "{}")])

      manager["a_metrics"].shouldNotBeNull().url shouldBe "http://a/v2"
      coVerify(exactly = 1) { grpc.unregisterPathOnProxy("a_metrics") }
      coVerify(exactly = 1) { grpc.reclaimPathOnProxy("a_metrics", any(), "http://a/v2", any()) }
    }

    "reconcile should drop a stale discovered path the proxy no longer holds" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/m", "{}")])
      coEvery { grpc.unregisterPathOnProxy("a_metrics") } throws
        RequestFailureException("unregisterPathOnProxy() - Unable to remove path /a_metrics - path not found")

      manager.reconcileDiscoveredPaths(emptyList())

      manager["a_metrics"].shouldBeNull()
    }

    // Each path reconciles on its own: one the proxy rejects must not keep the rest of the desired set from
    // registering. The desired set keeps its order, so the failing path sits between two that must still register.
    "reconcile should still register the other discovered paths when one registration fails" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      coEvery { grpc.registerPathOnProxy("b_metrics", any(), any(), any()) } throws
        RequestFailureException("registerPathOnProxy() - path /b_metrics is not authorized")

      manager.reconcileDiscoveredPaths(
        [
          DiscoveredPath("a", "a_metrics", "http://a/m", "{}"),
          DiscoveredPath("b", "b_metrics", "http://b/m", "{}"),
          DiscoveredPath("c", "c_metrics", "http://c/m", "{}"),
        ],
      )

      manager["a_metrics"].shouldNotBeNull()
      manager["b_metrics"].shouldBeNull()
      manager["c_metrics"].shouldNotBeNull()
    }

    // What the agent dashboard's activity feed shows: a path registered and unregistered, static or discovered. A
    // registerPath call the proxy refuses throws to its caller and isn't recorded; registerPaths records a refusal (see
    // "a refusal should be recorded with the proxy's reason" below).
    "registering and unregistering a path should be recorded in the activity feed, and a refused registerPath not" {
      val log = AgentActivityLog(capacity = 20)
      val agent = createMockAgent()
      every { agent.activityLog } returns log
      val manager = AgentPathManager(agent)
      coEvery { agent.grpcService.registerPathOnProxy("denied_metrics", any(), any(), any()) } throws
        RequestFailureException(
          "registerPathOnProxy() - Agent identity 'team_a' is not authorized to register path /denied_metrics",
          rejectionCause = PathRejectionCause.NOT_AUTHORIZED,
        )

      manager.registerPath("kept_metrics", "http://kept/metrics")
      runCatching { manager.registerPath("denied_metrics", "http://denied/metrics") }
      manager.unregisterPath("kept_metrics")
      manager.reconcileDiscoveredPaths(
        [DiscoveredPath(name = "d", path = "found_metrics", url = "http://found/m", labels = "{}")],
      )

      log.snapshot().entries.map { Triple(it.kind, it.paths, it.detail) }.reversed() shouldBe
        [
          Triple(AgentActivityKind.PATH_REGISTERED, ["kept_metrics"], "static"),
          Triple(AgentActivityKind.PATH_UNREGISTERED, ["kept_metrics"], "static"),
          Triple(AgentActivityKind.PATH_REGISTERED, ["found_metrics"], "discovered"),
        ]
    }

    // The log and the activity feed show a path's first refusal; agent_path_rejections_total counts every one, by
    // the cause the proxy gave.
    "every refusal of a path should be counted by its cause" {
      val agent = createMockAgent(pathConfigsHocon = """{ name = d, path = denied_metrics, url = "http://denied/m" }""")
      val counts = CountingAgentMetrics().also { it.installOn(agent) }
      val manager = AgentPathManager(agent)
      coEvery { agent.grpcService.registerPathOnProxy("denied_metrics", any(), any(), any()) } throws
        RequestFailureException(
          "registerPathOnProxy() - Agent identity 'team_a' is not authorized to register path /denied_metrics",
          rejectionCause = PathRejectionCause.NOT_AUTHORIZED,
        )

      // Registered on each connect, so a second connect is refused again.
      repeat(2) { runCatching { manager.registerPaths() } }

      counts.pathRejections.labelValues(agent.launchId, "not_authorized").get() shouldBe 2.0
    }

    "a refusal should be recorded with the proxy's reason" {
      val log = AgentActivityLog(capacity = 20)
      val agent = createMockAgent(pathConfigsHocon = """{ name = d, path = denied_metrics, url = "http://denied/m" }""")
      every { agent.activityLog } returns log
      val manager = AgentPathManager(agent)
      coEvery { agent.grpcService.registerPathOnProxy("denied_metrics", any(), any(), any()) } throws
        RequestFailureException(
          "registerPathOnProxy() - Agent identity 'team_a' is not authorized to register path /denied_metrics",
          rejectionCause = PathRejectionCause.NOT_AUTHORIZED,
        )

      runCatching { manager.registerPaths() }

      log.snapshot().entries.single().apply {
        kind shouldBe AgentActivityKind.PATH_REFUSED
        paths shouldBe ["denied_metrics"]
        detail shouldContain "is not authorized to register path /denied_metrics"
      }
    }

    // Each of the proxy's reasons names its path, so grouping by reason gave every refused path a line of its own, and
    // an identity refused all its paths filled the feed on every connect.
    "refusals of several paths for one cause should be one line, and two causes two" {
      val log = AgentActivityLog(capacity = 20)
      val agent =
        createMockAgent(
          pathConfigsHocon =
            """
            { name = a, path = a_metrics, url = "http://a/m" },
            { name = b, path = b_metrics, url = "http://b/m" },
            { name = c, path = c_metrics, url = "http://c/m" }
            """,
        )
      every { agent.activityLog } returns log
      val manager = AgentPathManager(agent)
      listOf("a_metrics" to PathRejectionCause.NOT_AUTHORIZED, "b_metrics" to PathRejectionCause.NOT_AUTHORIZED)
        .forEach { (path, cause) ->
          coEvery { agent.grpcService.registerPathOnProxy(path, any(), any(), any()) } throws
            RequestFailureException.fromProxy("registerPathOnProxy", "not authorized to register path /$path", cause)
        }
      coEvery { agent.grpcService.registerPathOnProxy("c_metrics", any(), any(), any()) } throws
        RequestFailureException.fromProxy(
          "registerPathOnProxy",
          "path /c_metrics is served by another identity",
          PathRejectionCause.HELD_BY_ANOTHER_IDENTITY,
        )

      runCatching { manager.registerPaths() }

      log.snapshot().entries.map { it.paths to it.count }.reversed() shouldBe
        [["a_metrics", "b_metrics"] to 2, ["c_metrics"] to 1]
    }

    // Two paths' filters failing open for different reasons are two lines, each with its reason.
    "filters letting scrapes through for different reasons should be recorded apart" {
      val log = AgentActivityLog(capacity = 20)
      val agent =
        createMockAgent(
          filtersHocon =
            """
            { path = a_metrics, metricNameAllow = [], metricNameDeny = ["go_.*"] },
            { path = b_metrics, metricNameAllow = [], metricNameDeny = ["go_.*"] }
            """,
        )
      every { agent.activityLog } returns log
      val manager = AgentPathManager(agent)

      manager.filtersByPath.getValue("a_metrics").filterBytes("up 1\n".encodeToByteArray(), "application/json")
      manager.filtersByPath.getValue("b_metrics").filterBytes(byteArrayOf(0xC3.toByte(), 0x28), "text/plain")

      log.snapshot().entries.map { it.paths to it.detail }.reversed() shouldBe
        [
          ["a_metrics"] to "a scrape's content type, application/json, was not text",
          ["b_metrics"] to "a scrape's body was not valid UTF-8",
        ]
    }

    fun rejectPath(
      grpcService: AgentGrpcService,
      path: String,
      cause: PathRejectionCause,
    ) {
      coEvery { grpcService.registerPathOnProxy(path, any(), any(), any()) } throws
        RequestFailureException("registerPathOnProxy() - proxy rejected path /$path", rejectionCause = cause)
    }

    // A manager whose proxy rejects discovered path d_metrics for cause, and accepts every other registration.
    // [clock] is what the retry backoff is measured on, so a test can advance it instead of waiting.
    fun managerRejectingDiscovered(
      cause: PathRejectionCause,
      clock: TimeSource = TimeSource.Monotonic,
      retryMaxSecs: Int? = null,
    ): Pair<AgentPathManager, AgentGrpcService> {
      val agent = createMockAgent(agentHocon = retryMaxHocon(retryMaxSecs))
      rejectPath(agent.grpcService, "d_metrics", cause)
      return AgentPathManager(agent, clock) to agent.grpcService
    }

    val dMetrics: List<DiscoveredPath> = [DiscoveredPath("d", "d_metrics", "http://d/m", "{}")]

    // Retrying a rejection that can't clear would only repeat it on the proxy every reconcile.
    "reconcile should not retry a discovered path the proxy rejects for a cause that can't clear" {
      val (manager, grpc) = managerRejectingDiscovered(PathRejectionCause.NOT_AUTHORIZED)

      repeat(3) { manager.reconcileDiscoveredPaths(dMetrics) }

      manager["d_metrics"].shouldBeNull()
      coVerify(exactly = 1) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }
    }

    "reconcile should retry a discovered path rejected for a cause that can clear, and register it once accepted" {
      val clock = TestTimeSource()
      val (manager, grpc) = managerRejectingDiscovered(PathRejectionCause.HELD_BY_ANOTHER_IDENTITY, clock)

      // The reconcile loop polls every agent.discovery.reconcileIntervalSecs, 30s by default.
      clock.tick(3, 30.seconds) { manager.reconcileDiscoveredPaths(dMetrics) }
      coVerify(exactly = 3) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }

      coEvery { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 2L
      }
      // Three rejections in, the next retry waits 60s rather than coming on the very next poll.
      clock += 60.seconds
      manager.reconcileDiscoveredPaths(dMetrics)
      manager["d_metrics"].shouldNotBeNull()
    }

    // Reconciling re-registered a rejected path on every poll, so a conflict that lasted cost the proxy a round trip
    // every interval for as long as it lasted.
    "reconcile should back off a discovered path the proxy keeps rejecting" {
      val clock = TestTimeSource()
      val (manager, grpc) = managerRejectingDiscovered(PathRejectionCause.HELD_BY_ANOTHER_IDENTITY, clock)

      clock.tick(6, 30.seconds) { manager.reconcileDiscoveredPaths(dMetrics) }

      // Tried on the first three polls, then once more at 150s -- four times over six polls, not six.
      coVerify(exactly = 4) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }
    }

    // As for static paths, a cap no longer than the loop's interval -- here the 30s reconcile -- turns the backoff off.
    "a retry cap at or below the reconcile interval should retry a discovered path on every poll" {
      val clock = TestTimeSource()
      val (manager, grpc) =
        managerRejectingDiscovered(PathRejectionCause.HELD_BY_ANOTHER_IDENTITY, clock, retryMaxSecs = 30)

      clock.tick(6, 30.seconds) { manager.reconcileDiscoveredPaths(dMetrics) }

      coVerify(exactly = 6) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }
    }

    // A rejection is the proxy's answer, not a fault, so it is logged once and without a stack trace. A retryable one
    // that repeats is logged at DEBUG.
    "reconcile should log a discovered path's rejection once, without a stack trace" {
      for (cause in [PathRejectionCause.NOT_AUTHORIZED, PathRejectionCause.HELD_BY_ANOTHER_IDENTITY]) {
        val (manager, _) = managerRejectingDiscovered(cause)

        val warnings =
          captureLogs<AgentPathManager>(Level.WARN) {
            repeat(3) { manager.reconcileDiscoveredPaths(dMetrics) }
          }.filter { "/d_metrics" in it.formattedMessage }

        withClue(cause) {
          warnings shouldHaveSize 1
          warnings.single().throwableProxy.shouldBeNull()
        }
      }
    }

    "reconcile should try a discovered path rejected for a cause that can't clear again once its entry changes" {
      val (manager, grpc) = managerRejectingDiscovered(PathRejectionCause.NOT_AUTHORIZED)

      repeat(2) { manager.reconcileDiscoveredPaths(dMetrics) }
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "d_metrics", "http://d/v2", "{}")])

      coVerify(exactly = 2) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }
    }

    // An edited entry is a different registration, so it doesn't wait out the backoff the old one earned.
    "reconcile should try a discovered path waiting out its backoff at once when its entry changes" {
      val clock = TestTimeSource()
      val (manager, grpc) = managerRejectingDiscovered(PathRejectionCause.HELD_BY_ANOTHER_IDENTITY, clock)

      // Three rejections in, the next retry waits 60s (see "reconcile should back off ...").
      clock.tick(3, 30.seconds) { manager.reconcileDiscoveredPaths(dMetrics) }
      coVerify(exactly = 3) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }

      clock += 30.seconds
      manager.reconcileDiscoveredPaths(dMetrics)
      coVerify(exactly = 3) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }

      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "d_metrics", "http://d/v2", "{}")])
      coVerify(exactly = 4) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }

      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "d_metrics", "http://d/v2", """{"env":"prod"}""")])
      coVerify(exactly = 5) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }
    }

    // Leaving the file and returning, or reconnecting -- which clears the path manager first -- starts afresh.
    "reconcile should retry a rejected discovered path that leaves the file and returns, or after a reconnect" {
      val (manager, grpc) = managerRejectingDiscovered(PathRejectionCause.NOT_AUTHORIZED)

      manager.reconcileDiscoveredPaths(dMetrics)
      manager.reconcileDiscoveredPaths(emptyList())
      manager.reconcileDiscoveredPaths(dMetrics)
      coVerify(exactly = 2) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }

      manager.clear()
      manager.reconcileDiscoveredPaths(dMetrics)
      coVerify(exactly = 3) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }
    }

    // Likewise for removals. A transport failure keeps its path for the next reconcile to retry, but must not stop
    // the other stale paths being removed. The map's iteration order is unspecified, so the first unregister
    // attempted is the one that fails.
    "reconcile should still remove the other stale discovered paths when one unregister fails" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      val paths = ["a_metrics", "b_metrics", "c_metrics"]
      manager.reconcileDiscoveredPaths(paths.map { DiscoveredPath(it, it, "http://$it/m", "{}") })
      val failedOnce = AtomicBoolean(false)
      coEvery { grpc.unregisterPathOnProxy(any()) } answers {
        if (failedOnce.compareAndSet(expectedValue = false, newValue = true)) throw StatusException(Status.UNAVAILABLE)
        unregisterPathResponse { valid = true }
      }

      manager.reconcileDiscoveredPaths(emptyList())

      coVerify(exactly = 3) { grpc.unregisterPathOnProxy(any()) }
      paths.count { manager[it] != null } shouldBe 1
    }

    "reconcile skips a discovered path colliding with a static path" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      // Register a static baseline path, then try to discover the same path with a different url.
      manager.registerPath("shared", "http://static/m")
      manager.reconcileDiscoveredPaths([DiscoveredPath("shared", "shared", "http://discovered/m", "{}")])

      val context = manager["shared"].shouldNotBeNull()
      context.source shouldBe PathSource.STATIC
      context.url shouldBe "http://static/m"
      // Only the initial static registration happened; the colliding discovered entry was skipped.
      coVerify(exactly = 1) { grpc.registerPathOnProxy("shared", any(), any(), any()) }
    }

    "reconcile with an empty desired set removes all discovered but keeps static" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      manager.registerPath("static_path", "http://static/m")
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "disc_path", "http://d/m", "{}")])
      manager.reconcileDiscoveredPaths(emptyList())

      manager["disc_path"].shouldBeNull()
      manager["static_path"].shouldNotBeNull().source shouldBe PathSource.STATIC
    }

    "multiple paths can be registered" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")
      manager.registerPath("health", "http://localhost:$PROXY_HTTP_PORT/health")
      manager.registerPath("info", "http://localhost:$PROXY_HTTP_PORT/info")

      manager["metrics"].shouldNotBeNull()
      manager["health"].shouldNotBeNull()
      manager["info"].shouldNotBeNull()
    }

    // ==================== toPlainText Tests ====================

    "toPlainText should return header when no paths configured" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      val text = manager.toPlainText()

      text shouldContain "Agent Path Configs"
    }

    "toPlainText with configured paths should include path details" {
      val configVals = testConfigVals(
        """
        agent {
          pathConfigs = [
            {
              name = "node_exporter"
              path = "metrics"
              url = "http://localhost:9100/metrics"
              labels = "{}"
            },
            {
              name = "app_metrics"
              path = "app"
              url = "http://localhost:$PROXY_HTTP_PORT/metrics"
              labels = "{}"
            }
          ]
          filters = []
        }
        proxy { auth = [] }
        """,
      )

      val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.grpcService } returns mockGrpcService
      every { mockAgent.configVals } returns configVals
      every { mockAgent.isTestMode } returns true

      val manager = AgentPathManager(mockAgent)

      val text = manager.toPlainText()

      text shouldContain "Agent Path Configs"
      text shouldContain "metrics"
      text shouldContain "app"
      text.shouldNotBeEmpty()
    }

    // AgentHttpService logs "Fetching $pathContext" at DEBUG on every scrape, next to an already-redacted URL, so the
    // data class's generated toString printed the raw target URL -- userinfo and query secrets included.
    "PathContext toString should redact credentials in the target URL" {
      val context =
        AgentPathManager.PathContext(
          pathId = 1L,
          path = "metrics",
          url = "http://admin:hunter2@target:9100/metrics?token=s3cr3t",
          labels = "{}",
          source = PathSource.STATIC,
        )

      val text = context.toString()

      text shouldNotContain "hunter2"
      text shouldNotContain "s3cr3t"
      text shouldContain "target:9100/metrics"
      text shouldContain "metrics"
    }

    // Path registration logs name the target URL at INFO, so its credentials must be redacted there too.
    "path registration logs should redact credentials in the target URL" {
      val agent = createMockAgent()
      every { agent.isTestMode } returns false
      val manager = AgentPathManager(agent)
      val events =
        captureLogs<AgentPathManager> {
          manager.registerPath("metrics", "http://admin:hunter2@localhost:9100/metrics?token=s3cr3t")
          manager.unregisterPath("metrics")
        }

      val output = events.joinToString("\n") { it.formattedMessage }
      output shouldContain "http://***@localhost:9100/metrics?token=***"
      output shouldNotContain "hunter2"
      output shouldNotContain "s3cr3t"
    }

    // Each configured path is logged once when the path manager is built.
    "the startup log of configured paths should redact credentials in the target URL" {
      val configVals = testConfigVals(
        """
        agent {
          pathConfigs = [
            {
              name = "secured"
              path = "metrics"
              url = "http://admin:hunter2@localhost:9100/metrics?token=s3cr3t"
              labels = "{}"
            }
          ]
          filters = []
        }
        proxy { auth = [] }
        """,
      )
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.grpcService } returns mockk<AgentGrpcService>(relaxed = true)
      every { mockAgent.configVals } returns configVals
      every { mockAgent.isTestMode } returns true
      val events = captureLogs<AgentPathManager> { AgentPathManager(mockAgent) }

      val output = events.joinToString("\n") { it.formattedMessage }
      output shouldContain "http://***@localhost:9100/metrics?token=***"
      output shouldNotContain "hunter2"
      output shouldNotContain "s3cr3t"
    }

    // toPlainText backs the agent's debug output, so a configured URL's credentials must not appear in it.
    "toPlainText should redact credentials in configured target URLs" {
      val configVals = testConfigVals(
        """
        agent {
          pathConfigs = [
            {
              name = "secured"
              path = "metrics"
              url = "http://admin:hunter2@localhost:9100/metrics?token=s3cr3t"
              labels = "{}"
            }
          ]
          filters = []
        }
        proxy { auth = [] }
        """,
      )
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.grpcService } returns mockk<AgentGrpcService>(relaxed = true)
      every { mockAgent.configVals } returns configVals
      every { mockAgent.isTestMode } returns true

      val text = AgentPathManager(mockAgent).toPlainText()

      text shouldContain "http://***@localhost:9100/metrics?token=***"
      text shouldNotContain "hunter2"
      text shouldNotContain "s3cr3t"
    }

    "toPlainText should include URL column for configured paths" {
      val configVals = testConfigVals(
        """
        agent {
          pathConfigs = [
            {
              name = "node_exporter"
              path = "metrics"
              url = "http://localhost:9100/metrics"
              labels = "{}"
            }
          ]
          filters = []
        }
        proxy { auth = [] }
        """,
      )

      val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.grpcService } returns mockGrpcService
      every { mockAgent.configVals } returns configVals
      every { mockAgent.isTestMode } returns true

      val manager = AgentPathManager(mockAgent)

      val text = manager.toPlainText()

      text shouldContain "URL"
      text shouldContain "http://localhost:9100/metrics"
    }

    // registerPath holds pathMutex across the proxy RPC and the write to the local path map, so the two can't
    // disagree; concurrent registrations therefore run one at a time.
    "concurrent registerPath calls are serialized for atomicity (finding 19)" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      val concurrentCalls = AtomicInt(0)
      val maxConcurrent = AtomicInt(0)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } coAnswers {
        val current = concurrentCalls.incrementAndFetch()
        maxConcurrent.update { max -> maxOf(max, current) }
        delay(100.milliseconds) // Simulate slow gRPC call
        concurrentCalls.decrementAndFetch()
        registerPathResponse {
          valid = true
          pathId = firstArg<String>().hashCode().toLong()
        }
      }

      coroutineScope {
        launch { manager.registerPath("path1", "http://localhost:$PROXY_HTTP_PORT/p1") }
        launch { manager.registerPath("path2", "http://localhost:$PROXY_HTTP_PORT/p2") }
      }

      manager["path1"].shouldNotBeNull()
      manager["path2"].shouldNotBeNull()
      // finding 19: the proxy RPC and the local map write are held under pathMutex together so the local
      // map and the proxy's view can't disagree, which serializes concurrent registrations.
      maxConcurrent.load() shouldBe 1
    }

    // "/" passed pathConfigs' check for a path and a url, then normalized to "", and the require() that rejects a blank
    // path threw out of registerPaths: the paths after it never registered, and every connect failed.
    "registerPaths should skip a path of just a slash and register the rest" {
      val agent =
        createMockAgent(
          pathConfigsHocon =
            """
            { name = "root", path = "/", url = "http://localhost:9100/metrics", labels = "{}" },
            { name = "ok", path = "ok", url = "http://localhost:9200/metrics", labels = "{}" }
            """,
        )
      val grpcService = agent.grpcService
      val manager = AgentPathManager(agent)

      manager.registerPaths()

      manager["ok"].shouldNotBeNull()
      // Only "ok" reaches the proxy; the real AgentGrpcService would throw for the blank path "/" normalizes to.
      coVerify(exactly = 1) { grpcService.registerPathOnProxy(any(), any(), any(), any()) }
      coVerify(exactly = 1) { grpcService.registerPathOnProxy("ok", any(), any(), any()) }
    }

    // Two entries for one normalized path both registered, and the second's URL silently replaced the first's, here
    // and on the proxy. agent.filters already rejected the same mistake.
    "construction should reject two pathConfigs entries for the same path, naming it" {
      val agent =
        createMockAgent(
          pathConfigsHocon =
            """
            { name = "a", path = "metrics", url = "http://localhost:9100/metrics", labels = "{}" },
            { name = "b", path = "/metrics", url = "http://localhost:9200/metrics", labels = "{}" }
            """,
        )

      shouldThrow<IllegalArgumentException> { AgentPathManager(agent) }.message shouldContain "/metrics"
    }

    // November #16: the proxy refuses a path with a character no scrape's request path can carry. Refused here too, a
    // pathConfigs entry with one fails at startup, naming it, rather than being refused at every connect. So does one
    // of more than one segment, which the proxy refuses too: "//foo" lost one slash here and one at the proxy, which
    // took it as "foo", while the agent kept it as "/foo", so every scrape of it 404ed (November 26 #11).
    "construction should reject a pathConfigs entry whose path the proxy refuses, naming it" {
      val character = "can't appear in a scrape's request path"
      val refusals =
        ["app metrics", "metrics?format=x", "a#b", """a\tb""", """a\nb"""].map { it to character } +
          ["//foo", "app/metrics", "/app/metrics", "//"].map { it to "one segment" }
      for ((path, reason) in refusals) {
        val agent =
          createMockAgent(
            pathConfigsHocon =
              """
              { name = "ok", path = "metrics", url = "http://localhost:9100/metrics", labels = "{}" },
              { name = "bad", path = "$path", url = "http://localhost:9200/metrics", labels = "{}" }
              """,
          )

        val message = shouldThrow<IllegalArgumentException> { AgentPathManager(agent) }.message.orEmpty()

        withClue(path) {
          message shouldContain "\"bad\""
          message shouldContain reason
          message.none { it == '\n' || it == '\t' }.shouldBeTrue()
        }
      }
    }

    "construction should accept pathConfigs paths with the characters a scrape can carry" {
      val agent =
        createMockAgent(
          pathConfigsHocon =
            """
            { name = "a", path = "/team-a.metrics~1", url = "http://localhost:9100/metrics", labels = "{}" },
            { name = "b", path = "a:b@c!d", url = "http://localhost:9200/metrics", labels = "{}" }
            """,
        )

      AgentPathManager(agent).toPlainText() shouldContain "team-a.metrics~1"
    }

    // A discovered "//metrics" was keyed as "/metrics" and registered as "metrics", so each reconcile found the path it
    // had registered stale, unregistered it, and registered it again, and it took a static "metrics" over as it did
    // (November 26 #11). A discovery source drops such an entry (DiscoveredPath.usable); one that passes it on still
    // never reaches the proxy with it.
    "reconcile should neither register nor unregister a discovered path with a second leading slash" {
      val agent =
        createMockAgent(
          pathConfigsHocon = """{ name = "static", path = "metrics", url = "$METRICS_URL", labels = "{}" }""",
          agentHocon = "discovery.enabled = true",
        )
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.registerPaths()

      repeat(2) {
        manager.reconcileDiscoveredPaths([DiscoveredPath("d", "//metrics", "http://other/metrics", "{}")])
      }

      coVerify(exactly = 1) { grpc.registerPathOnProxy(any(), any(), any(), any()) }
      coVerify(exactly = 0) { grpc.unregisterPathOnProxy(any()) }
      manager["metrics"].shouldNotBeNull().source shouldBe PathSource.STATIC
    }

    // The proxy leaves labels it can't serve out of service discovery, so a pathConfigs entry with them is reported at
    // startup, naming it, where it can be fixed. It still registers: labels matter only to service discovery.
    "construction should warn about a pathConfigs entry whose labels service discovery will leave out" {
      val agent =
        createMockAgent(
          pathConfigsHocon =
            """
            { name = "numeric", path = "metrics", url = "http://localhost:9100/metrics", labels = "{\"a\":1}" }
            """,
        )

      val warnings = captureLogs<AgentPathManager>(Level.WARN) { AgentPathManager(agent) }.map { it.formattedMessage }

      warnings.single() shouldContain "numeric"
      warnings.single() shouldContain "label 'a' must have a string value"
    }

    "registerPaths should register all configured paths" {
      val configVals = testConfigVals(
        """
        agent {
          pathConfigs = [
            {
              name = "exporter1"
              path = "metrics1"
              url = "http://localhost:9100/metrics"
              labels = "{}"
            },
            {
              name = "exporter2"
              path = "metrics2"
              url = "http://localhost:9200/metrics"
              labels = "{}"
            }
          ]
          filters = []
        }
        proxy { auth = [] }
        """,
      )

      val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.grpcService } returns mockGrpcService
      every { mockAgent.configVals } returns configVals
      every { mockAgent.isTestMode } returns true
      every { mockAgent.agentId } returns "test-agent"

      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }

      val manager = AgentPathManager(mockAgent)
      manager.registerPaths()

      coVerify { mockGrpcService.registerPathOnProxy("metrics1", "{}", any(), any()) }
      coVerify { mockGrpcService.registerPathOnProxy("metrics2", "{}", any(), any()) }

      manager["metrics1"].shouldNotBeNull()
      manager["metrics2"].shouldNotBeNull()
    }

    // A mock agent with a static pathConfigs entry for each of [paths].
    fun agentWithStaticPaths(
      vararg paths: String,
      agentHocon: String = "",
    ): Pair<Agent, AgentGrpcService> {
      val pathConfigsHocon =
        paths.joinToString(",\n") { path ->
          """{ name = "$path", path = "$path", url = "http://localhost:9100/$path", labels = "{}" }"""
        }
      val agent = createMockAgent(pathConfigsHocon = pathConfigsHocon, agentHocon = agentHocon)
      every { agent.agentId } returns "test-agent"
      return agent to agent.grpcService
    }

    // A blank entry in static pathConfigs is dropped at load: registerPaths does not catch the require(), so letting
    // it through would fail the connect attempt and reconnect-loop the agent over one config typo.
    "a blank static pathConfigs entry should be dropped, and the rest still register" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("   ", "metrics2")
      val manager = AgentPathManager(mockAgent)

      manager.registerPaths()

      manager["metrics2"].shouldNotBeNull()
      coVerify(exactly = 1) { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) }
    }

    // Reconcile runs every reconcileIntervalSecs, and each one re-found the same collision or duplicate in the file and
    // warned again: about 2,880 WARN lines a day per problem at the default 30s interval.
    fun reconcileWarnings(
      manager: AgentPathManager,
      vararg desiredPerReconcile: List<DiscoveredPath>,
    ): suspend () -> List<String> =
      {
        captureLogs<AgentPathManager>(Level.WARN) {
          desiredPerReconcile.forEach { manager.reconcileDiscoveredPaths(it) }
        }.map { it.formattedMessage }
      }

    "a discovered path colliding with a static path should be warned about once" {
      val (agent, _) = agentWithStaticPaths("metrics")
      val manager = AgentPathManager(agent)
      val colliding = [DiscoveredPath("d", "metrics", "http://d/m", "{}")]

      val warnings = reconcileWarnings(manager, colliding, colliding, colliding)()

      warnings.filter { "collide" in it } shouldHaveSize 1
    }

    "a duplicate discovered path should be warned about once" {
      val manager = AgentPathManager(createMockAgent())
      val duplicated =
        [DiscoveredPath("a", "d_metrics", "http://a/m", "{}"), DiscoveredPath("b", "d_metrics", "http://b/m", "{}")]

      val warnings = reconcileWarnings(manager, duplicated, duplicated, duplicated)()

      warnings.filter { "Duplicate" in it } shouldHaveSize 1
    }

    // The warning names every current collision, so a new one is news -- and so is the file changing back after a fix.
    "a change in the discovered paths that collide should be warned about again" {
      val (agent, _) = agentWithStaticPaths("metrics", "health")
      val manager = AgentPathManager(agent)
      val one = [DiscoveredPath("d", "metrics", "http://d/m", "{}")]
      val two = one + DiscoveredPath("e", "health", "http://e/h", "{}")

      val warnings = reconcileWarnings(manager, one, one, two, two)()

      warnings.filter { "collide" in it } shouldHaveSize 2
      warnings.last { "collide" in it } shouldContain "/health"
    }

    // A reconnect clears the path manager, so the agent reports its discovery problems afresh on the new connection.
    "a collision should be warned about again after a reconnect" {
      val (agent, _) = agentWithStaticPaths("metrics")
      val manager = AgentPathManager(agent)
      val colliding = [DiscoveredPath("d", "metrics", "http://d/m", "{}")]

      reconcileWarnings(manager, colliding)()
      manager.clear()
      val warnings = reconcileWarnings(manager, colliding)()

      warnings.filter { "collide" in it } shouldHaveSize 1
    }

    // A proxy rejects a single path (valid=false, surfaced as RequestFailureException) when, for example,
    // the agent's identity isn't authorized for it. That rejection must not abort registration of the
    // agent's other paths -- previously it ended the whole connection and the agent reconnected forever
    // with every path down.
    "registerPaths should register the remaining paths when the proxy rejects one" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2", "metrics3")
      coEvery { mockGrpcService.registerPathOnProxy("metrics2", any(), any(), any()) } throws
        RequestFailureException("registerPathOnProxy() - path /metrics2 not authorized")

      val manager = AgentPathManager(mockAgent)
      manager.registerPaths()

      manager["metrics1"].shouldNotBeNull()
      manager["metrics2"].shouldBeNull()
      manager["metrics3"].shouldNotBeNull()
    }

    // Static paths metrics1 and metrics2, where the proxy accepts metrics1 and rejects metrics2 -- by default because a
    // live agent of another identity serves it, a rejection that clears while the agent stays connected.
    fun managerRejectingMetrics2(
      cause: PathRejectionCause = PathRejectionCause.HELD_BY_ANOTHER_IDENTITY,
      clock: TimeSource = TimeSource.Monotonic,
      retryMaxSecs: Int? = null,
    ): Pair<AgentPathManager, AgentGrpcService> {
      val (mockAgent, mockGrpcService) =
        agentWithStaticPaths("metrics1", "metrics2", agentHocon = retryMaxHocon(retryMaxSecs))
      rejectPath(mockGrpcService, "metrics2", cause)
      return AgentPathManager(mockAgent, clock) to mockGrpcService
    }

    fun acceptMetrics2(grpcService: AgentGrpcService) {
      coEvery { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 2L
      }
    }

    "registerPaths should remember a rejected static path until a later registerPaths registers it" {
      val (manager, grpcService) = managerRejectingMetrics2()
      manager.registerPaths()
      manager.hasRejectedStaticPaths.shouldBeTrue()

      acceptMetrics2(grpcService)
      manager.registerPaths()
      manager.hasRejectedStaticPaths.shouldBeFalse()
    }

    "retryRejectedStaticPaths should register a rejected static path once the proxy accepts it" {
      val (manager, grpcService) = managerRejectingMetrics2()
      manager.registerPaths()

      acceptMetrics2(grpcService)
      manager.retryRejectedStaticPaths()

      manager["metrics2"].shouldNotBeNull()
      manager.hasRejectedStaticPaths.shouldBeFalse()
    }

    // The connection's retry task ticked for the rest of the connection once a static path was rejected at connect,
    // even after every one had registered. Only registerPaths adds to the set, and it runs only at connect.
    "retryRejectedStaticPathsWhile should return once every rejected static path registers" {
      val (manager, grpcService) = managerRejectingMetrics2()
      manager.registerPaths()
      acceptMetrics2(grpcService)

      withTimeout(2.seconds) { manager.retryRejectedStaticPathsWhile(1.milliseconds) { true } }

      manager["metrics2"].shouldNotBeNull()
    }

    // With the backoff off (a cap at or below rejectedPathRetrySecs), every pass is due, so the loop's passes are real
    // retries. With the backoff on, only the first would reach the proxy, so counting passes proved nothing. Each pass
    // advances the clock by the 10s interval the real loop waits, which the test's 1ms delay stands in for.
    "retryRejectedStaticPathsWhile should keep retrying while a static path is still rejected" {
      val clock = TestTimeSource()
      val (manager, grpcService) = managerRejectingMetrics2(clock = clock, retryMaxSecs = 1)
      manager.registerPaths()
      var checks = 0

      manager.retryRejectedStaticPathsWhile(1.milliseconds) {
        clock += 10.seconds
        ++checks <= 3
      }

      // The connect-time registration, then one retry on each of the three passes.
      coVerify(exactly = 4) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
      manager.hasRejectedStaticPaths.shouldBeTrue()
    }

    "retryRejectedStaticPaths should keep a path the proxy still rejects" {
      val (manager, _) = managerRejectingMetrics2()
      manager.registerPaths()

      manager.retryRejectedStaticPaths()

      manager["metrics2"].shouldBeNull()
      manager.hasRejectedStaticPaths.shouldBeTrue()
    }

    // Rejects metrics2 at connect, then runs ten ticks of the static retry loop, which runs every
    // agent.internal.rejectedPathRetrySecs (10s by default), with the backoff capped at [retryMaxSecs].
    suspend fun retriedOverTenTicks(retryMaxSecs: Int? = null): Pair<AgentPathManager, AgentGrpcService> {
      val clock = TestTimeSource()
      val (manager, grpcService) = managerRejectingMetrics2(clock = clock, retryMaxSecs = retryMaxSecs)
      manager.registerPaths()
      clock.tick(10, 10.seconds) { manager.retryRejectedStaticPaths() }
      return manager to grpcService
    }

    // A conflict that lasts used to cost the proxy a round trip for the path on every tick of the retry loop, for the
    // life of the connection. The first retry still comes one interval later; each further rejection doubles the wait.
    "retryRejectedStaticPaths should back off while the proxy keeps rejecting a static path" {
      val (manager, grpcService) = retriedOverTenTicks()

      // The connect attempt, then retries at 10s, 20s, 40s and 80s -- five round trips over ten ticks, not eleven.
      coVerify(exactly = 5) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
      manager.hasRejectedStaticPaths.shouldBeTrue()
    }

    // Doubling has to stop somewhere, or a conflict lasting a day would leave the path unregistered for hours after
    // it cleared.
    "a static path's retry wait should stop doubling at the cap" {
      val clock = TestTimeSource()
      val (manager, grpcService) = managerRejectingMetrics2(clock = clock)
      manager.registerPaths()

      // Well past the point where the doubling reaches the cap.
      clock.tick(20, 5.minutes) { manager.retryRejectedStaticPaths() }

      // Once the wait is capped every tick this long is due, so all twenty retry, plus the connect attempt.
      coVerify(exactly = 21) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
    }

    "a static path's retry wait should stop doubling at agent.internal.rejectedPathRetryMaxSecs" {
      val (_, grpcService) = retriedOverTenTicks(retryMaxSecs = 20)

      // The connect attempt, then retries at 10s and 20s, and every 20s once the wait reaches the cap: seven round
      // trips over ten ticks, where the default cap allows five.
      coVerify(exactly = 7) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
    }

    "a retry cap at or below the retry interval should retry a static path on every tick" {
      val (_, grpcService) = retriedOverTenTicks(retryMaxSecs = 10)

      coVerify(exactly = 11) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
    }

    // The "Retry backoff is off" lines an AgentPathManager built on [agent] logs.
    fun backoffOffLogs(agent: Agent): List<String> =
      captureLogs<AgentPathManager>(Level.INFO) { AgentPathManager(agent) }
        .map { it.formattedMessage }
        .filter { it.startsWith("Retry backoff is off") }

    // A cap at or below a loop's interval turns that loop's backoff off, which nothing else would show.
    "a retry cap at or below rejectedPathRetrySecs should log that the static backoff is off" {
      val (agent, _) = agentWithStaticPaths("metrics1", agentHocon = retryMaxHocon(10))
      backoffOffLogs(agent).single() shouldContain "static"
    }

    "a retry cap at or below the reconcile interval should log that the discovered backoff is off" {
      val agent = createMockAgent(agentHocon = "discovery.enabled = true\n${retryMaxHocon(30)}")
      // No static paths, and the cap is above their 10s interval anyway, so only the discovered backoff is off.
      backoffOffLogs(agent).single() shouldContain "discovered"
    }

    "the default retry cap should not log that a backoff is off" {
      val (agent, _) = agentWithStaticPaths("metrics1", agentHocon = "discovery.enabled = true")
      backoffOffLogs(agent).shouldBeEmpty()
    }

    // The wait belongs in the log: an operator reading a repeated rejection needs to know it is still coming back.
    "a repeated static rejection should log when the path will be retried" {
      val clock = TestTimeSource()
      val (manager, _) = managerRejectingMetrics2(clock = clock)

      val events =
        captureLogs<AgentPathManager>(Level.DEBUG) {
          manager.registerPaths()
          clock.tick(2, 10.seconds) { manager.retryRejectedStaticPaths() }
        }.filter { "/metrics2" in it.formattedMessage }

      // The first rejection is retried on the next tick, so it promises no particular wait; the repeat names one.
      events.first().formattedMessage shouldContain "retrying:"
      events.any { "retrying in 10s" in it.formattedMessage }.shouldBeTrue()
    }

    // Unlike registerPaths at connect, a retry isolates a transport failure: the connection's other tasks already end
    // the connection when it is really gone.
    "retryRejectedStaticPaths should keep the path, not throw, on a transport failure" {
      val (manager, grpcService) = managerRejectingMetrics2()
      manager.registerPaths()

      coEvery { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) } throws
        StatusException(Status.UNAVAILABLE)
      manager.retryRejectedStaticPaths()

      manager["metrics2"].shouldBeNull()
      manager.hasRejectedStaticPaths.shouldBeTrue()
    }

    // Each path's call was isolated, JVM Errors included, so an agent out of heap or stack in one path's registration
    // logged a failed path and ran on, in the state the Error left it in (November #7). An Error now propagates, from
    // each of the four isolated calls, to fail the connection and stop the agent.
    "a JVM Error retrying a rejected static path should propagate" {
      val (manager, grpcService) = managerRejectingMetrics2()
      manager.registerPaths()
      coEvery { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) } throws OutOfMemoryError("test")

      shouldThrow<OutOfMemoryError> { manager.retryRejectedStaticPaths() }
    }

    "a JVM Error registering a discovered path should propagate from the reconcile" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      coEvery { agent.grpcService.registerPathOnProxy(any(), any(), any(), any()) } throws StackOverflowError("test")

      shouldThrow<StackOverflowError> {
        manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/m", "{}")])
      }
    }

    "a JVM Error unregistering a stale discovered path should propagate from the reconcile" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/m", "{}")])
      coEvery { agent.grpcService.unregisterPathOnProxy(any()) } throws OutOfMemoryError("test")

      shouldThrow<OutOfMemoryError> { manager.reconcileDiscoveredPaths(emptyList()) }
    }

    "a JVM Error reclaiming a taken-over path should propagate" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { agent.grpcService.reclaimPathOnProxy(any(), any(), any(), any()) } throws OutOfMemoryError("test")
      clock += 10.seconds

      shouldThrow<OutOfMemoryError> { manager.reclaimTakenOverPaths() }
    }

    // A retry that kept timing out logged a WARN with its stack trace on every tick, since nothing compared it with the
    // last failure, and a deadline's description carries its elapsed time anyway (November #42). A failure with a gRPC
    // status is compared by its code, as #39 does for a discovered path; the full description is still logged.
    "a static path's retries failing with DEADLINE_EXCEEDED should log one WARN, then DEBUG" {
      val clock = TestTimeSource()
      val (manager, grpcService) = managerRejectingMetrics2(clock = clock)
      manager.registerPaths()
      coEvery { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) } throws
        deadlineExceeded("29.999954000") andThenThrows deadlineExceeded("29.999961000") andThenThrows
        StatusException(Status.UNAVAILABLE.withDescription("io exception"))

      // Each tick is past the cap, so every retry is due whatever the backoff.
      val events =
        captureLogs<AgentPathManager>(Level.DEBUG) {
          clock.tick(3, 5.minutes) { manager.retryRejectedStaticPaths() }
        }.filter { "/metrics2" in it.formattedMessage }

      // A change of status code is still a change.
      events.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.WARN)
      events[0].throwableProxy.shouldNotBeNull().message shouldContain "after 29.999954000s"
      events[1].formattedMessage shouldContain "after 29.999961000s"
      // The failed retry is backed off (see below), so the log says when the next one comes.
      events[0].formattedMessage shouldContain "retrying in 10s"
    }

    // A failed retry is backed off as a rejection is (November #42). It wasn't, so while a transport failure lasted the
    // path was retried on every tick, each time-out holding the retry loop for a whole unary deadline.
    "retryRejectedStaticPaths should back off a static path whose retries keep failing in transport" {
      val clock = TestTimeSource()
      val (manager, grpcService) = managerRejectingMetrics2(clock = clock)
      manager.registerPaths()
      coEvery { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) } throws
        deadlineExceeded("30.000012000")

      clock.tick(10, 10.seconds) { manager.retryRejectedStaticPaths() }

      // As for a rejection: the connect attempt, then retries at 10s, 20s, 40s and 80s.
      coVerify(exactly = 5) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
      manager.hasRejectedStaticPaths.shouldBeTrue()
    }

    // A transport failure on one call needn't mean the connection is gone, so the path stays for retrying, rather than
    // waiting for a reconnect that may not come (November #42).
    "a static path whose retries failed in transport should register once the proxy accepts it" {
      val clock = TestTimeSource()
      val (manager, grpcService) = managerRejectingMetrics2(clock = clock)
      manager.registerPaths()
      coEvery { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) } throws
        deadlineExceeded("30.000012000")
      clock.tick(3, 10.seconds) { manager.retryRejectedStaticPaths() }

      acceptMetrics2(grpcService)
      clock.tick(1, 5.minutes) { manager.retryRejectedStaticPaths() }

      manager["metrics2"].shouldNotBeNull()
      manager.hasRejectedStaticPaths.shouldBeFalse()
    }

    // The proxy answering ends a transport failure, so one that comes back is news again.
    "a static path's transport failure should be warned about again after the proxy answers in between" {
      val clock = TestTimeSource()
      val (manager, grpcService) = managerRejectingMetrics2(clock = clock)
      manager.registerPaths()
      val held =
        RequestFailureException(
          "registerPathOnProxy() - proxy rejected path /metrics2",
          rejectionCause = PathRejectionCause.HELD_BY_ANOTHER_IDENTITY,
        )
      coEvery { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) } throws
        deadlineExceeded("29.999954000") andThenThrows held andThenThrows deadlineExceeded("29.999961000")

      val events =
        captureLogs<AgentPathManager>(Level.DEBUG) {
          clock.tick(3, 5.minutes) { manager.retryRejectedStaticPaths() }
        }.filter { "/metrics2" in it.formattedMessage }

      // The rejection in between is a repeat of the one at connect, so it is logged at DEBUG.
      events.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.WARN)
      events[2].throwableProxy.shouldNotBeNull()
    }

    // Only a rejection by a live agent holding the path clears while the agent stays connected. No other cause
    // does, including none (a proxy predating the field) and one this agent doesn't recognize.
    "registerPaths should keep a rejected static path for retrying only for a cause that can clear" {
      val clearing =
        setOf(
          PathRejectionCause.HELD_BY_ANOTHER_IDENTITY,
          PathRejectionCause.CONSOLIDATION_MISMATCH,
          PathRejectionCause.HELD_BY_ANOTHER_AGENT,
        )
      for (cause in PathRejectionCause.entries) {
        val (manager, _) = managerRejectingMetrics2(cause)
        manager.registerPaths()
        withClue(cause) { manager.hasRejectedStaticPaths shouldBe (cause in clearing) }
      }
    }

    // Retrying a rejection that can't clear would only repeat it on the proxy every interval.
    "registerPaths should try and log once a static path rejected for a cause that can't clear" {
      val (manager, grpcService) = managerRejectingMetrics2(PathRejectionCause.NOT_AUTHORIZED)

      val warnings =
        captureLogs<AgentPathManager>(Level.WARN) {
          manager.registerPaths()
          repeat(3) { manager.retryRejectedStaticPaths() }
        }.filter { "/metrics2" in it.formattedMessage }

      manager.hasRejectedStaticPaths.shouldBeFalse()
      coVerify(exactly = 1) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
      warnings shouldHaveSize 1
    }

    "retryRejectedStaticPaths should stop retrying a path once the proxy rejects it for a cause that can't clear" {
      val (manager, grpcService) = managerRejectingMetrics2()
      manager.registerPaths()

      rejectPath(grpcService, "metrics2", PathRejectionCause.NOT_AUTHORIZED)
      manager.retryRejectedStaticPaths()
      manager.hasRejectedStaticPaths.shouldBeFalse()

      manager.retryRejectedStaticPaths()
      coVerify(exactly = 2) { grpcService.registerPathOnProxy("metrics2", any(), any(), any()) }
    }

    // A transport failure repeats every reconcile while it lasts. The first is worth a stack trace; the same failure
    // on every poll is not, which is the rule rejections already follow.
    "reconcile should log a repeating non-rejection failure in full once, then at DEBUG" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      coEvery { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) } throws
        StatusException(Status.UNAVAILABLE)

      val events =
        captureLogs<AgentPathManager>(Level.DEBUG) {
          repeat(3) { manager.reconcileDiscoveredPaths(dMetrics) }
        }.filter { "d_metrics" in it.formattedMessage }

      events.count { it.level == Level.WARN } shouldBe 1
      events.single { it.level == Level.WARN }.throwableProxy.shouldNotBeNull()
      events.count { it.level == Level.DEBUG } shouldBe 2
      // Still retried: the failure may clear, unlike a rejection whose cause cannot.
      coVerify(exactly = 3) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }
    }

    // gRPC's DEADLINE_EXCEEDED description carries the time the call took, so a registration that kept timing out
    // failed with a new message on every reconcile, and each logged a WARN with its stack trace (November #39). A
    // failure with a gRPC status is compared by its code; the full description is still logged.
    "reconcile should log one WARN for registrations failing with DEADLINE_EXCEEDED that differ only in elapsed time" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      coEvery { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) } throws
        deadlineExceeded("29.999954000") andThenThrows deadlineExceeded("29.999961000") andThenThrows
        StatusException(Status.UNAVAILABLE.withDescription("io exception"))

      val events =
        captureLogs<AgentPathManager>(Level.DEBUG) {
          repeat(3) { manager.reconcileDiscoveredPaths(dMetrics) }
        }.filter { "d_metrics" in it.formattedMessage }

      // A change of status code is still a change.
      events.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.WARN)
      events[0].throwableProxy.shouldNotBeNull().message shouldContain "after 29.999954000s"
      events[1].formattedMessage shouldContain "after 29.999961000s"
    }

    // A stale discovered path whose unregister fails in transport stays for the next reconcile to retry, and each one
    // that failed logged a WARN with its stack trace (November #42). It is compared by gRPC status code, as above.
    "reconcile should log one WARN for unregisters of a stale path failing with DEADLINE_EXCEEDED" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths(dMetrics)
      coEvery { grpc.unregisterPathOnProxy("d_metrics") } throws
        deadlineExceeded("29.999954000") andThenThrows deadlineExceeded("29.999961000") andThenThrows
        StatusException(Status.UNAVAILABLE.withDescription("io exception"))

      val events =
        captureLogs<AgentPathManager>(Level.DEBUG) {
          repeat(3) { manager.reconcileDiscoveredPaths(emptyList()) }
        }.filter { "d_metrics" in it.formattedMessage }

      // A change of status code is still a change.
      events.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.WARN)
      events[0].throwableProxy.shouldNotBeNull().message shouldContain "after 29.999954000s"
      events[1].formattedMessage shouldContain "after 29.999961000s"
      // Still retried on every reconcile, and kept until an unregister gets through.
      coVerify(exactly = 3) { grpc.unregisterPathOnProxy("d_metrics") }
      manager["d_metrics"].shouldNotBeNull()
    }

    // A stale path that returns to the file and then leaves it again is a new removal, so its failure is news again.
    "a stale path's unregister failure should be warned about again once the path has returned and left again" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths(dMetrics)
      coEvery { grpc.unregisterPathOnProxy("d_metrics") } throws deadlineExceeded("29.999954000")

      val warnings =
        captureLogs<AgentPathManager>(Level.WARN) {
          manager.reconcileDiscoveredPaths(emptyList())
          manager.reconcileDiscoveredPaths(dMetrics)
          manager.reconcileDiscoveredPaths(emptyList())
        }.filter { "d_metrics" in it.formattedMessage }

      warnings shouldHaveSize 2
    }

    // A rejection has no gRPC status, so it is still compared by its message, the proxy's reason, which carries no
    // timing: a path the proxy rejects again for another reason is news (November #39).
    "a discovered path rejected again for a different reason should be warned about again" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      val held =
        RequestFailureException(
          "registerPathOnProxy() - Path /d_metrics is served by identity 'a'; identity 'b' cannot take it over",
          rejectionCause = PathRejectionCause.HELD_BY_ANOTHER_IDENTITY,
        )
      val mismatch =
        RequestFailureException(
          "registerPathOnProxy() - Non-consolidated agent rejected for consolidated path /d_metrics",
          rejectionCause = PathRejectionCause.CONSOLIDATION_MISMATCH,
        )
      coEvery { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) } throws
        held andThenThrows held andThenThrows mismatch

      // Each poll is due: the first retry waits only for the next one, and the second 30s.
      val events =
        captureLogs<AgentPathManager>(Level.DEBUG) {
          clock.tick(3, 30.seconds) { manager.reconcileDiscoveredPaths(dMetrics) }
        }.filter { "/d_metrics" in it.formattedMessage }

      events.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.WARN)
      events[2].formattedMessage shouldContain "Non-consolidated"
    }

    // A discovery-only agent showed nothing on its debug page, since toPlainText lists only the static config.
    "discoveredToPlainText should list the discovered paths, and say so when there are none" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      manager.discoveredToPlainText() shouldContain "none"

      manager.reconcileDiscoveredPaths(
        [DiscoveredPath("d", "d_metrics", "http://admin:hunter2@d.local/metrics?token=s3cr3t", "{}")],
      )

      val text = manager.discoveredToPlainText()
      text shouldContain "d_metrics"
      text shouldContain "http://***@d.local/metrics?token=***"
      text shouldNotContain "hunter2"
      text shouldNotContain "s3cr3t"
    }

    // Static wins even while the proxy rejects the static path: discovery must not register the path in the meantime,
    // only for the retry to replace it.
    "reconcile skips a discovered path colliding with a static path the proxy rejected" {
      val (manager, grpcService) = managerRejectingMetrics2()
      manager.registerPaths()

      acceptMetrics2(grpcService)
      manager.reconcileDiscoveredPaths([DiscoveredPath("m2", "metrics2", "http://discovered/m", "{}")])
      manager["metrics2"].shouldBeNull()

      manager.retryRejectedStaticPaths()
      val context = manager["metrics2"].shouldNotBeNull()
      context.source shouldBe PathSource.STATIC
      context.url shouldBe "http://localhost:9100/metrics2"
    }

    // Only a proxy's rejection of an individual path is isolated. A transport failure means the
    // connection itself is gone, so it must still propagate and end the connection attempt.
    "registerPaths should propagate a transport failure" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2")
      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } throws
        StatusException(Status.UNAVAILABLE)

      val manager = AgentPathManager(mockAgent)

      shouldThrow<StatusException> { manager.registerPaths() }
    }

    // A proxy that accepts the agent but rejects every one of its static paths isn't usable. Failing the
    // connection attempt (rather than staying connected with nothing registered) lets EndpointFailover move
    // on to the next proxy endpoint.
    "registerPaths should fail when the proxy rejects every static path" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2")
      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } throws
        RequestFailureException("registerPathOnProxy() - not authorized")

      val manager = AgentPathManager(mockAgent)

      shouldThrow<RequestFailureException> { manager.registerPaths() }
    }

    // A rejection that can clear -- a live agent of another identity holds the path -- clears while the agent stays
    // connected, which is what the retry task is for. Failing the attempt instead reconnected the agent every
    // reconnectPauseSecs, and each reconnect cleared the backoff, so the path was retried without one and any
    // discovered paths, registered only on a connection that stays up, never registered at all.
    "registerPaths should stay connected when the proxy rejects every static path for a cause that can clear" {
      for (cause in [PathRejectionCause.HELD_BY_ANOTHER_IDENTITY, PathRejectionCause.CONSOLIDATION_MISMATCH]) {
        val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2")
        rejectPath(mockGrpcService, "metrics1", cause)
        rejectPath(mockGrpcService, "metrics2", cause)
        val manager = AgentPathManager(mockAgent)

        withClue(cause) {
          manager.registerPaths()
          manager.hasRejectedStaticPaths.shouldBeTrue()
        }
      }
    }

    // One rejection that can clear is enough to stay connected for: the retry task registers that path once it does.
    "registerPaths should stay connected when one of every rejected static path can clear" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2")
      rejectPath(mockGrpcService, "metrics1", PathRejectionCause.NOT_AUTHORIZED)
      rejectPath(mockGrpcService, "metrics2", PathRejectionCause.HELD_BY_ANOTHER_IDENTITY)
      val manager = AgentPathManager(mockAgent)

      manager.registerPaths()

      manager.hasRejectedStaticPaths.shouldBeTrue()
    }

    // An agent kept connected for a rejection that can clear (above) can be told on a later retry that it can't -- the
    // owner left and the proxy now finds the path unauthorized, say. The retry task then stops with no static path
    // registered, and Agent ends the connection rather than idle on a proxy that serves none of them.
    "servesNoStaticPath should report when retries end with every static path still unregistered" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1")
      rejectPath(mockGrpcService, "metrics1", PathRejectionCause.HELD_BY_ANOTHER_IDENTITY)
      val manager = AgentPathManager(mockAgent)
      manager.registerPaths()
      manager.servesNoStaticPath().shouldBeTrue()

      rejectPath(mockGrpcService, "metrics1", PathRejectionCause.NOT_AUTHORIZED)
      manager.retryRejectedStaticPaths()

      manager.hasRejectedStaticPaths.shouldBeFalse()
      manager.servesNoStaticPath().shouldBeTrue()
    }

    "servesNoStaticPath should be false once a static path registers, and for an agent with none configured" {
      val (mockAgent, _) = agentWithStaticPaths("metrics1")
      AgentPathManager(mockAgent).also { it.registerPaths() }.servesNoStaticPath().shouldBeFalse()

      val (noStatic, _) = agentWithStaticPaths()
      AgentPathManager(noStatic).servesNoStaticPath().shouldBeFalse()
    }

    "registerPaths should fail when the proxy rejects every static path for a cause that can't clear" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2")
      rejectPath(mockGrpcService, "metrics1", PathRejectionCause.NOT_AUTHORIZED)
      rejectPath(mockGrpcService, "metrics2", PathRejectionCause.INVALID_PATH)
      val manager = AgentPathManager(mockAgent)

      shouldThrow<RequestFailureException> { manager.registerPaths() }
    }

    // An agent with no static paths (discovery-only, say) has nothing to reject, so it must still register.
    "registerPaths should succeed when there are no static paths" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths()
      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } throws
        RequestFailureException("registerPathOnProxy() - not authorized")

      AgentPathManager(mockAgent).registerPaths()
    }

    // Filters are keyed by normalized path, so a second entry for the same path silently replaced the first -- an
    // operator who split allow and deny rules across two entries lost one of them without a word.
    "two agent.filters entries for the same path should fail at startup" {
      val agent =
        createMockAgent(
          """
          { path = "metrics", metricNameAllow = [], metricNameDeny = ["go_.*"] },
          { path = "/metrics", metricNameAllow = ["http_.*"], metricNameDeny = [] }
          """,
        )

      val exception = shouldThrow<IllegalArgumentException> { AgentPathManager(agent) }

      exception.message shouldContain "metrics"
    }

    "registerPath should attach a configured filter to the path context" {
      val agent = createMockAgent("""{ path = "metrics", metricNameAllow = [], metricNameDeny = ["go_.*"] }""")
      val manager = AgentPathManager(agent)

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      manager["metrics"].shouldNotBeNull().filter.shouldNotBeNull()
    }

    "registerPath should attach a filter configured with a leading slash" {
      // cfg.path normalization in AgentPathManager strips a leading "/" when building filtersByPath,
      // so a user writing path = "/metrics" in agent.filters must still attach to a path registered as
      // "metrics". Deleting that removePrefix("/") call passes every other test (no other test attaches
      // a filter configured with a leading slash), so it needs its own coverage.
      val agent = createMockAgent("""{ path = "/metrics", metricNameAllow = [], metricNameDeny = ["go_.*"] }""")
      val manager = AgentPathManager(agent)

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      manager["metrics"].shouldNotBeNull().filter.shouldNotBeNull()
    }

    "registerPath should leave filter null for a path with no configured filter" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      manager["metrics"].shouldNotBeNull().filter.shouldBeNull()
    }

    // The registration log is the operator-facing signal that a configured filter found its path -- a
    // filter whose path does not match any registered path is otherwise silent. These three tests pin
    // that signal; isTestMode is flipped off because recordRegistered suppresses the line under test mode.
    "registerPath should report an attached filter in the registration log" {
      val agent = createMockAgent("""{ path = "metrics", metricNameAllow = [], metricNameDeny = ["go_.*"] }""")
      every { agent.isTestMode } returns false
      val manager = AgentPathManager(agent)

      val logs = captureRegistrationLogs {
        manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")
      }

      logs.single() shouldContain "with a metric filter"
    }

    "registerPath should not mention a filter when none is configured" {
      val agent = createMockAgent()
      every { agent.isTestMode } returns false
      val manager = AgentPathManager(agent)

      val logs = captureRegistrationLogs {
        manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")
      }

      logs.single() shouldNotContain "metric filter"
    }

    "reconcileDiscoveredPaths should report an attached filter for a discovered path" {
      // The signal has to work for discovered paths specifically: they do not exist at construction
      // time, so no startup-time cross-check against pathConfigs could ever cover this case.
      val agent = createMockAgent("""{ path = "discovered", metricNameAllow = [], metricNameDeny = ["go_.*"] }""")
      every { agent.isTestMode } returns false
      val manager = AgentPathManager(agent)

      val logs = captureRegistrationLogs {
        manager.reconcileDiscoveredPaths(
          [DiscoveredPath(name = "d", path = "discovered", url = "http://localhost:$PROMETHEUS_PORT/m", labels = "{}")],
        )
      }

      logs.single() shouldContain "with a metric filter"
    }

    "reconcileDiscoveredPaths should attach a configured filter to a discovered path" {
      val agent = createMockAgent("""{ path = "discovered", metricNameAllow = [], metricNameDeny = ["go_.*"] }""")
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths(
        [DiscoveredPath(name = "d", path = "discovered", url = "http://localhost:$PROMETHEUS_PORT/m", labels = "{}")],
      )

      manager["discovered"].shouldNotBeNull().filter.shouldNotBeNull()
    }
    // ==================== Taken-Over Path Tests ====================

    // Nothing told an agent the proxy gave its path to another agent, so its dashboard showed the path registered for
    // as long as the other held it. The path stays known, so a scrape sent before the takeover still finds it.
    "a takeover notice should show the path as taken over rather than registered" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)

      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))

      manager["metrics"].shouldNotBeNull()
      manager.registeredPaths.shouldBeEmpty()
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
      val failure = manager.pathFailures.getValue("metrics")
      failure.takenOver.shouldBeTrue()
      failure.retrying.shouldBeTrue()
      failure.reason.message shouldContain "agent-east-copy"
    }

    "a takeover should be logged at WARN and recorded in the activity feed once, however often it's told" {
      val log = AgentActivityLog(capacity = 20)
      val agent = createMockAgent()
      every { agent.activityLog } returns log
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)

      val warnings =
        captureLogs<AgentPathManager>(Level.WARN) {
          manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
          manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
        }

      warnings.single().formattedMessage shouldContain "agent-east-copy"
      val takeovers = log.snapshot().entries.filter { it.kind == AgentActivityKind.PATH_TAKEN_OVER }
      takeovers.single().detail shouldContain "agent-east-copy"
    }

    // The holder's name is another agent's own words, so a line break in it could forge a log line.
    "a takeover should show the holder's name escaped" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)

      val warnings =
        captureLogs<AgentPathManager>(Level.WARN) {
          manager.notePathNotice(takenNotice("metrics", "evil\nINFO forged"))
        }

      warnings.single().formattedMessage shouldNotContain "\n"
      manager.pathFailures.getValue("metrics").reason.message shouldNotContain "\n"
    }

    // The proxy sends a notice with no path to keep the stream open.
    "a keepalive notice should be ignored" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)

      manager.notePathNotice(pathNotice {})

      manager.takenOverContexts.shouldBeEmpty()
      manager.registeredPaths.map { it.path } shouldBe listOf("metrics")
    }

    "a notice about a path the agent doesn't serve should be ignored" {
      val manager = AgentPathManager(createMockAgent())

      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))

      manager.takenOverContexts.shouldBeEmpty()
      manager.pathFailures.shouldBeEmpty()
    }

    // Only a reclaim, which the proxy refuses while a live agent of another launch holds the path, keeps the two from
    // taking it from each other every few seconds.
    "a taken-over path should be registered again as a reclaim once its retry is due" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL, """{"job":"j"}""")
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } returns
        registerPathResponse {
          valid = true
          pathId = 2L
        }

      // Due after rejectedPathRetrySecs (10s by default).
      manager.reclaimTakenOverPaths()
      coVerify(exactly = 0) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      clock += 10.seconds
      manager.reclaimTakenOverPaths()

      coVerify(exactly = 1) {
        grpc.reclaimPathOnProxy("metrics", """{"job":"j"}""", METRICS_URL, "STATIC")
      }
      manager.registeredPaths.map { it.path } shouldBe listOf("metrics")
      manager.takenOverContexts.shouldBeEmpty()
      manager.pathFailures.shouldBeEmpty()
    }

    "a refused reclaim should keep the path taken over and wait longer before the next" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws
        heldRefusal()

      clock.tick(times = 4, step = 10.seconds) { manager.reclaimTakenOverPaths() }

      // Due at 10s, then 20s later at 30s: the 40s tick doesn't try again.
      coVerify(exactly = 2) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
      manager.pathFailures.getValue("metrics").takenOver.shouldBeTrue()
    }

    // The proxy says so as the holder leaves, so the agent takes the path back at once rather than after its backoff.
    "a notice that the path is free should make its reclaim due at once" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } returns
        registerPathResponse { valid = true }

      manager.notePathNotice(freeNotice("metrics"))
      manager.reclaimTakenOverPaths()

      manager.registeredPaths.map { it.path } shouldBe listOf("metrics")
    }

    "a notice that the path is free should say its holder has left" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))

      manager.notePathNotice(freeNotice("metrics"))

      manager.pathFailures.getValue("metrics").reason.message shouldContain "has left"
    }

    // The proxy names the current holder after each refused reclaim, and a notice naming it reset the wait to its
    // first, so the agent retried every rejectedPathRetrySecs for as long as the other agent held the path.
    "a notice naming a new holder should keep the reclaim's backoff" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-a"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws
        heldRefusal()
      clock += 10.seconds
      manager.reclaimTakenOverPaths()

      // Refused at 10s, so the next try is due at 30s, whoever holds the path.
      clock += 5.seconds
      manager.notePathNotice(takenNotice("metrics", "agent-b"))
      clock += 10.seconds
      manager.reclaimTakenOverPaths()

      coVerify(exactly = 1) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      manager.pathFailures.getValue("metrics").reason.message shouldContain "agent-b"
    }

    // The proxy names the holder on the notices stream before it answers the refused reclaim, so the notice can land
    // first. Taken as a new takeover, it left the refused try's backoff where it was, and the agent tried again at
    // once, as fast as the proxy could refuse it.
    "a notice naming the same holder during a refused reclaim should not keep the backoff from growing" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-a"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } answers {
        manager.notePathNotice(takenNotice("metrics", "agent-a"))
        throw heldRefusal()
      }

      // Due at 10s, then 20s later at 30s: the 20s tick doesn't try again.
      clock.tick(times = 2, step = 10.seconds) { manager.reclaimTakenOverPaths() }

      coVerify(exactly = 1) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
    }

    // The proxy's notice after each refused reclaim wakes the reclaim task at once, and a pass then tried every path
    // due within the window: one just refused with a backoff no longer than the window, as every one is with
    // rejectedPathRetryMaxSecs = 1, was tried again at once, as fast as the proxy could refuse it (November 26 #12).
    "a reclaim pass should try no path until one is due" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws heldRefusal()

      // Due at 10s (rejectedPathRetrySecs), so not yet at 9.5s, though within the window.
      clock += 9500.milliseconds
      manager.reclaimTakenOverPaths()
      coVerify(exactly = 0) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      clock += 500.milliseconds
      manager.reclaimTakenOverPaths()
      coVerify(exactly = 1) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
    }

    // The window gathers the paths due soon after one that's due into its pass, so the reclaim task wakes once for
    // them.
    "a reclaim pass should take, with a path that's due, the paths due within a second of it" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("first", METRICS_URL)
      manager.registerPath("second", METRICS_URL)
      manager.notePathNotice(takenNotice("first", "agent-east-copy"))
      clock += 500.milliseconds
      manager.notePathNotice(takenNotice("second", "agent-east-copy"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws heldRefusal()

      // "first" is due at 10s, and "second" half a second later.
      clock += 9500.milliseconds
      manager.reclaimTakenOverPaths()

      coVerify(exactly = 1) { grpc.reclaimPathOnProxy("first", any(), any(), any()) }
      coVerify(exactly = 1) { grpc.reclaimPathOnProxy("second", any(), any(), any()) }
    }

    // The loop slept until a second before the next try was due, which with rejectedPathRetryMaxSecs = 1 was never, so
    // it sent reclaims as fast as the proxy refused them, and otherwise tried each a second early (November 26 #12).
    "the reclaim loop should wait out a one-second backoff between refused reclaims" {
      val agent = createMockAgent(agentHocon = "internal { rejectedPathRetrySecs = 1, rejectedPathRetryMaxSecs = 1 }")
      val grpc = agent.grpcService
      // Never advanced, so the next try stays a second away however long the test takes.
      val manager = AgentPathManager(agent, TestTimeSource())
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      // As after a reconnect: the reclaim is due at once.
      manager.clear()
      // The proxy names the holder again with each refusal, which wakes the loop.
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } answers {
        manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
        throw heldRefusal()
      }
      val passes = AtomicInt(0)
      val active = AtomicBoolean(true)

      coroutineScope {
        // On a dispatcher of its own, so a loop that never suspends can't hold up this test's.
        val loop =
          launch(Dispatchers.Default) {
            manager.reclaimTakenOverPathsWhile {
              passes.incrementAndFetch()
              active.load()
            }
          }
        delay(100.milliseconds)
        active.store(false)
        val passesSeen = passes.load()
        loop.cancelAndJoin()

        withClue("loop passes in a tenth of a second") { passesSeen shouldBeLessThan 10 }
        coVerify(exactly = 1) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      }
    }

    // Freed, the path can go to an agent of another identity, which the proxy names as the reason, not a takeover.
    "a reclaim refused for another cause that can clear should show the proxy's reason, and warn once" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(freeNotice("metrics"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws
        RequestFailureException.fromProxy(
          "registerPathOnProxy",
          "Path /metrics is served by identity 'team-b'",
          PathRejectionCause.HELD_BY_ANOTHER_IDENTITY,
        )

      val warnings =
        captureLogs<AgentPathManager>(Level.WARN) {
          clock.tick(times = 4, step = 10.seconds) { manager.reclaimTakenOverPaths() }
        }

      warnings.filter { "team-b" in it.formattedMessage } shouldHaveSize 1
      manager.pathFailures.getValue("metrics").apply {
        reason.message shouldContain "team-b"
        takenOver.shouldBeFalse()
        retrying.shouldBeTrue()
      }
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
    }

    "a reclaim refused for a cause that can't clear should drop the path, which shows refused" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(freeNotice("metrics"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws
        RequestFailureException.fromProxy("registerPathOnProxy", "limit", PathRejectionCause.PATH_LIMIT_REACHED)

      manager.reclaimTakenOverPaths()

      manager["metrics"].shouldBeNull()
      manager.takenOverContexts.shouldBeEmpty()
      val failure = manager.pathFailures.getValue("metrics")
      failure.retrying.shouldBeFalse()
      failure.takenOver.shouldBeFalse()
    }

    // INVALID_AGENT means the proxy has already dropped this connection's context, so the connection is ending. The
    // takeover stays for the next connection to reclaim, as ProxyRegistry.tla's told does: dropped, the reconnect
    // would register the path without reclaim, and take it from the agent serving it.
    "a reclaim refused for an invalid agent should keep the path taken over for the next connection" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws
        RequestFailureException.fromProxy("registerPathOnProxy", "Invalid agentId", PathRejectionCause.INVALID_AGENT)

      clock.tick(times = 1, step = 10.seconds) { manager.reclaimTakenOverPaths() }

      coVerify(exactly = 1) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
      manager.clear()
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
    }

    // Registered the usual way, a taken-over discovered path would be taken back at once, on every reconcile.
    "reconcile should leave a taken-over discovered path to its reclaim, and keep an edit for it" {
      val agent = createMockAgent(agentHocon = "discovery.enabled = true")
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", METRICS_URL, "{}")])
      manager.notePathNotice(takenNotice("discovered", "agent-east-copy"))

      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", METRICS_URL, "{}")])
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", "http://edited/metrics", "{}")])

      coVerify(exactly = 1) { grpc.registerPathOnProxy("discovered", any(), any(), any()) }
      coVerify(exactly = 0) { grpc.unregisterPathOnProxy(any()) }
      manager.takenOverContexts.single().url shouldBe "http://edited/metrics"
      manager.pathFailures.getValue("discovered").takenOver.shouldBeTrue()
    }

    // The agent's word that another agent took the path can be stale, as when a notice crossed its own registration,
    // and skipping the call left the proxy sending it scrapes for a path it had dropped. The proxy refuses to
    // unregister a path it no longer maps to the agent, which is no news for a path taken over.
    "unregistering a taken-over path should still ask the proxy, and not warn when the proxy no longer maps it" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { grpc.unregisterPathOnProxy(any()) } throws
        RequestFailureException("unregisterPathOnProxy() - proxy no longer maps /metrics to this agent")

      val warnings = captureLogs<AgentPathManager>(Level.WARN) { manager.unregisterPath("metrics") }

      coVerify(exactly = 1) { grpc.unregisterPathOnProxy("metrics") }
      warnings.shouldBeEmpty()
      manager["metrics"].shouldBeNull()
      manager.takenOverContexts.shouldBeEmpty()
      manager.pathFailures.shouldBeEmpty()
    }

    // A notice handled while the path was being unregistered left a taken-over entry for a path the agent no longer
    // had, shown on its dashboard until the next retry dropped it.
    "a notice that lands while the path is unregistered should leave no taken-over entry" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)
      coEvery { grpc.unregisterPathOnProxy(any()) } answers {
        manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
        unregisterPathResponse { valid = true }
      }

      manager.unregisterPath("metrics")

      manager.takenOverContexts.shouldBeEmpty()
      manager.pathFailures.shouldBeEmpty()
    }

    // The proxy's notice and its answer to the registration come on different streams, so the notice can come first.
    // Dropped as a notice about a path the agent didn't have, it left the path shown registered while another agent
    // held it.
    "a notice that arrives before the registration's answer should still mark the path taken over" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      coEvery { grpc.registerPathOnProxy(any(), any(), any(), any()) } answers {
        manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
        registerPathResponse {
          valid = true
          pathId = 1L
        }
      }

      manager.registerPath("metrics", METRICS_URL)

      manager.registeredPaths.shouldBeEmpty()
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
    }

    "a notice that arrives before the proxy refuses the registration should be dropped" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      coEvery { grpc.registerPathOnProxy(any(), any(), any(), any()) } answers {
        manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
        throw RequestFailureException("registerPathOnProxy() - proxy rejected path /metrics")
      }

      shouldThrow<RequestFailureException> { manager.registerPath("metrics", METRICS_URL) }

      manager["metrics"].shouldBeNull()
      manager.takenOverContexts.shouldBeEmpty()
    }

    // Registered the usual way, the edit took the path back from the agent that took it before its notice came.
    "a discovery edit refused because another agent took the path meanwhile should leave it taken over, with the edit" {
      val agent = createMockAgent(agentHocon = "discovery.enabled = true")
      val grpc = agent.grpcService
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws
        heldRefusal("Path /discovered is served by agentId 7 (agent-b), of another launch")
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", METRICS_URL, "{}")])
      val edited = [DiscoveredPath("d", "discovered", "http://edited/metrics", "{}")]

      manager.reconcileDiscoveredPaths(edited)
      manager.reconcileDiscoveredPaths(edited)

      coVerify(exactly = 1) { grpc.registerPathOnProxy("discovered", any(), any(), any()) }
      coVerify(exactly = 1) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      manager.registeredPaths.shouldBeEmpty()
      manager.takenOverContexts.single().url shouldBe "http://edited/metrics"
      manager.pathFailures.getValue("discovered").apply {
        takenOver.shouldBeTrue()
        reason.message shouldContain "agent-b"
      }
    }

    // Until the proxy's notice names the holder, the path's holder is unknown, not gone: a second refusal, before the
    // notice came, showed it as having left. The proxy's refusal names the holder, so it is shown instead.
    "a path refused again before the proxy names its holder should still show the proxy's refusal" {
      val clock = TestTimeSource()
      val agent = createMockAgent(agentHocon = "discovery.enabled = true")
      val grpc = agent.grpcService
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } throws
        heldRefusal("Path /discovered is served by agentId 7 (agent-b), of another launch")
      val manager = AgentPathManager(agent, clock)
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", METRICS_URL, "{}")])
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", "http://edited/metrics", "{}")])

      clock += 10.seconds
      manager.reclaimTakenOverPaths()

      coVerify(exactly = 2) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      manager.pathFailures.getValue("discovered").reason.message.apply {
        this shouldContain "agent-b"
        this shouldNotContain "has left"
      }
    }

    // Waiting to take a path back isn't refused for good, so the connection mustn't end as one serving nothing.
    "a taken-over static path should still count as served" {
      val agent = createMockAgent(pathConfigsHocon = """{ name = m, path = metrics, url = "$METRICS_URL" }""")
      val manager = AgentPathManager(agent)
      manager.registerPaths()

      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))

      manager.servesNoStaticPath().shouldBeFalse()
    }

    // Forgotten at a reconnect, a taken-over path was registered again the usual way on the new connection, taking it
    // back from the agent holding it, which then did the same at its own next reconnect: behind nginx, whose idle
    // timeout cut the agent left without paths every minute, the two swapped the path about every 30 seconds.
    "a reconnect should keep a taken-over path, and forget the rest" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)
      manager.registerPath("other", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))

      manager.clear()

      manager["other"].shouldBeNull()
      manager.registeredPaths.shouldBeEmpty()
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
      manager.pathFailures.getValue("metrics").apply {
        takenOver.shouldBeTrue()
        reason.message shouldContain "agent-east-copy"
      }
    }

    // The proxy may have freed the path while the agent was away, and its notice saying so went with the connection.
    // Tried at once, the reclaim takes the path back if it's free, or puts the agent on the proxy's waiting list.
    "after a reconnect, a taken-over path should be tried at once as a reclaim" {
      val clock = TestTimeSource()
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent, clock)
      manager.registerPath("metrics", METRICS_URL)
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } returns
        registerPathResponse {
          valid = true
          pathId = 2L
        }

      manager.clear()
      manager.reclaimTakenOverPaths()

      coVerify(exactly = 1) { grpc.reclaimPathOnProxy("metrics", any(), METRICS_URL, "STATIC") }
      manager.registeredPaths.map { it.path } shouldBe listOf("metrics")
      manager["metrics"].shouldNotBeNull().pathId shouldBe 2L
    }

    // The reclaim task owns a taken-over path: registered at connect too, it would be tried twice, and a refusal while
    // the other agent holds it would also start the static retry task.
    "after a reconnect, registerPaths should leave a taken-over static path to its reclaim" {
      val (agent, grpc) = agentWithStaticPaths("metrics", "other")
      val manager = AgentPathManager(agent)
      manager.registerPaths()
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))

      manager.clear()
      manager.registerPaths()

      coVerify(exactly = 1) { grpc.registerPathOnProxy("metrics", any(), any(), any()) }
      coVerify(exactly = 2) { grpc.registerPathOnProxy("other", any(), any(), any()) }
      coVerify(exactly = 0) { grpc.reclaimPathOnProxy(any(), any(), any(), any()) }
      manager.hasRejectedStaticPaths.shouldBeFalse()
      manager.takenOverContexts.map { it.path } shouldBe listOf("metrics")
    }

    // An agent whose every path another agent took stays connected to wait for them, as it did before the reconnect.
    "after a reconnect, an agent with every static path taken over should stay connected" {
      val (agent, _) = agentWithStaticPaths("metrics")
      val manager = AgentPathManager(agent)
      manager.registerPaths()
      manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))

      manager.clear()
      manager.registerPaths()

      manager.servesNoStaticPath().shouldBeFalse()
    }

    "after a reconnect, reconcile should leave a taken-over discovered path to its reclaim" {
      val agent = createMockAgent(agentHocon = "discovery.enabled = true")
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", METRICS_URL, "{}")])
      manager.notePathNotice(takenNotice("discovered", "agent-east-copy"))

      manager.clear()
      manager.reconcileDiscoveredPaths([DiscoveredPath("d", "discovered", METRICS_URL, "{}")])

      coVerify(exactly = 1) { grpc.registerPathOnProxy("discovered", any(), any(), any()) }
      manager.takenOverContexts.map { it.path } shouldBe listOf("discovered")
    }

    // The reclaim loop sleeps while nothing is taken over, and a notice wakes it.
    "the reclaim loop should take a freed path back as soon as it's told" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.registerPath("metrics", METRICS_URL)
      coEvery { grpc.reclaimPathOnProxy(any(), any(), any(), any()) } returns
        registerPathResponse { valid = true }
      val active = AtomicBoolean(true)

      coroutineScope {
        val loop = launch { manager.reclaimTakenOverPathsWhile { active.load() } }
        manager.notePathNotice(takenNotice("metrics", "agent-east-copy"))
        manager.notePathNotice(freeNotice("metrics"))
        eventually(5.seconds) { manager.registeredPaths.map { it.path } shouldBe listOf("metrics") }
        active.store(false)
        loop.cancel()
      }
    }
  }
}

private const val METRICS_URL = "http://localhost:$PROXY_HTTP_PORT/metrics"

private fun takenNotice(
  path: String,
  holderName: String,
): PathNotice =
  pathNotice {
    this.path = path
    heldByAgentId = "holder-id"
    heldByAgentName = holderName
  }

private fun freeNotice(path: String): PathNotice = pathNotice { this.path = path }

// The proxy's refusal of a reclaim while another agent holds the path, for [reason].
private fun heldRefusal(reason: String = "held"): RequestFailureException =
  RequestFailureException.fromProxy("registerPathOnProxy", reason, PathRejectionCause.HELD_BY_ANOTHER_AGENT)
