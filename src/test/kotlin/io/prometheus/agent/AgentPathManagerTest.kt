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

import ch.qos.logback.classic.Level
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
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
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.unregisterPathResponse
import io.prometheus.common.captureLogs
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Duration.Companion.milliseconds
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue

@Suppress("LargeClass")
class AgentPathManagerTest : StringSpec() {
  // Captures the "Registered ..." lines doRegisterPath emits, which is the only place the agent reports
  // whether a configured filter actually attached to a path.
  private suspend fun captureRegistrationLogs(block: suspend () -> Unit): List<String> =
    captureLogs<AgentPathManager> { block() }.map { it.formattedMessage }.filter { it.startsWith("Registered ") }

  // [filtersHocon] is spliced into `agent.filters` (empty means no filters), which is what makes the
  // path manager compile and attach a MetricFilter to a matching registered path.
  private fun createMockAgent(filtersHocon: String = ""): Agent {
    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

    // Default happy-path stubs (valid=true, pathId=1); tests needing specific responses re-stub.
    coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }
    coEvery { mockGrpcService.unregisterPathOnProxy(any()) } returns unregisterPathResponse { valid = true }

    // Create a real ConfigVals with minimal config
    val configVals = testConfigVals(
      """
      agent {
        pathConfigs = []
        filters = [$filtersHocon]
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

    // A whitespace path registers on the proxy but can never be scraped: the scrape route answers a blank path
    // with a 404, so it is refused here, where an empty one already is.
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

    "reconcile re-registers a discovered path whose url changed" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v1", "{}")])
      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v2", "{}")])

      manager["a_metrics"].shouldNotBeNull().url shouldBe "http://a/v2"
      coVerify(exactly = 1) { grpc.unregisterPathOnProxy("a_metrics") }
      coVerify(exactly = 2) { grpc.registerPathOnProxy("a_metrics", any(), any(), any()) }
    }

    // Another agent took over the path, so the proxy rejects this agent's unregister. The new URL must still be
    // applied rather than blocked forever behind the failed unregister.
    "reconcile should apply a changed URL when the proxy rejects the unregister" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)
      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v1", "{}")])
      coEvery { grpc.unregisterPathOnProxy("a_metrics") } throws
        RequestFailureException(
          "unregisterPathOnProxy() - Unable to remove path /a_metrics - invalid agentId: 1 -- [2]",
        )

      manager.reconcileDiscoveredPaths([DiscoveredPath("a", "a_metrics", "http://a/v2", "{}")])

      manager["a_metrics"].shouldNotBeNull().url shouldBe "http://a/v2"
      coVerify(exactly = 1) { grpc.registerPathOnProxy("a_metrics", any(), "http://a/v2", any()) }
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

    fun rejectPath(
      grpcService: AgentGrpcService,
      path: String,
      cause: PathRejectionCause,
    ) {
      coEvery { grpcService.registerPathOnProxy(path, any(), any(), any()) } throws
        RequestFailureException("registerPathOnProxy() - proxy rejected path /$path", rejectionCause = cause)
    }

    // A manager whose proxy rejects discovered path d_metrics for cause, and accepts every other registration.
    fun managerRejectingDiscovered(cause: PathRejectionCause): Pair<AgentPathManager, AgentGrpcService> {
      val agent = createMockAgent()
      rejectPath(agent.grpcService, "d_metrics", cause)
      return AgentPathManager(agent) to agent.grpcService
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
      val (manager, grpc) = managerRejectingDiscovered(PathRejectionCause.HELD_BY_ANOTHER_IDENTITY)

      repeat(3) { manager.reconcileDiscoveredPaths(dMetrics) }
      coVerify(exactly = 3) { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) }

      coEvery { grpc.registerPathOnProxy("d_metrics", any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 2L
      }
      manager.reconcileDiscoveredPaths(dMetrics)
      manager["d_metrics"].shouldNotBeNull()
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
        if (failedOnce.compareAndSet(false, true)) throw StatusException(Status.UNAVAILABLE)
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

    // Bug #7: The gRPC call was inside pathMutex, blocking all concurrent path
    // operations for the full RPC duration. The fix moves the gRPC call outside
    // the mutex so concurrent registrations for different paths can proceed in
    // parallel. The mutex now only protects the local pathContextMap update.
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

    // A proxy rejects a single path (valid=false, surfaced as RequestFailureException) when, for example,
    // the agent's identity isn't authorized for it. That rejection must not abort registration of the
    // agent's other paths -- previously it ended the whole connection and the agent reconnected forever
    // with every path down.
    fun agentWithStaticPaths(vararg paths: String): Pair<Agent, AgentGrpcService> {
      val pathConfigsHocon =
        paths.joinToString(",\n") { path ->
          """{ name = "$path", path = "$path", url = "http://localhost:9100/$path", labels = "{}" }"""
        }
      val configVals = testConfigVals(
        """
        agent {
          pathConfigs = [
            $pathConfigsHocon
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
      return mockAgent to mockGrpcService
    }

    // A blank entry in static pathConfigs is dropped at load: registerPaths does not catch the require(), so letting
    // it through would fail the connect attempt and reconnect-loop the agent over one config typo.
    "a blank static pathConfigs entry should be dropped, and the rest still register" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("   ", "metrics2")
      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }
      val manager = AgentPathManager(mockAgent)

      manager.registerPaths()

      manager["metrics2"].shouldNotBeNull()
      coVerify(exactly = 1) { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) }
    }

    "registerPaths should register the remaining paths when the proxy rejects one" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2", "metrics3")
      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }
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
    ): Pair<AgentPathManager, AgentGrpcService> {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths("metrics1", "metrics2")
      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }
      rejectPath(mockGrpcService, "metrics2", cause)
      return AgentPathManager(mockAgent) to mockGrpcService
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

    "retryRejectedStaticPaths should keep a path the proxy still rejects" {
      val (manager, _) = managerRejectingMetrics2()
      manager.registerPaths()

      manager.retryRejectedStaticPaths()

      manager["metrics2"].shouldBeNull()
      manager.hasRejectedStaticPaths.shouldBeTrue()
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

    // Only a rejection by a live agent holding the path clears while the agent stays connected. No other cause
    // does, including none (a proxy predating the field) and one this agent doesn't recognize.
    "registerPaths should keep a rejected static path for retrying only for a cause that can clear" {
      val clearing = setOf(PathRejectionCause.HELD_BY_ANOTHER_IDENTITY, PathRejectionCause.CONSOLIDATION_MISMATCH)
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

    // An agent with no static paths (discovery-only, say) has nothing to reject, so it must still register.
    "registerPaths should succeed when there are no static paths" {
      val (mockAgent, mockGrpcService) = agentWithStaticPaths()
      coEvery { mockGrpcService.registerPathOnProxy(any(), any(), any(), any()) } throws
        RequestFailureException("registerPathOnProxy() - not authorized")

      AgentPathManager(mockAgent).registerPaths()
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
      // "metrics". Deleting that removePrefix("/") call passes every other test (all of which
      // configure filter paths without a leading slash), so it needs its own coverage.
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
    // that signal; isTestMode is flipped off because doRegisterPath suppresses the line under test mode.
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
  }
}
