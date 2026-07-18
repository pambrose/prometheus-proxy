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

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.agent.discovery.DiscoveredPath
import io.prometheus.common.ConfigVals
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.unregisterPathResponse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.time.Duration.Companion.milliseconds

class AgentPathManagerTest : StringSpec() {
  private fun createMockAgent(): Agent {
    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

    // Default happy-path stubs (valid=true, pathId=1); tests needing specific responses re-stub.
    coEvery { mockGrpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }
    coEvery { mockGrpcService.unregisterPathOnProxy(any()) } returns unregisterPathResponse { valid = true }

    // Create a real ConfigVals with minimal config
    val config = ConfigFactory.parseString(
      """
      agent {
        pathConfigs = []
      }
      proxy { auth = [] }
      """.trimIndent(),
    )
    val configVals = ConfigVals(config)

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

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 123L
      }

      manager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics", """{"job":"test"}""")

      coVerify { agent.grpcService.registerPathOnProxy("metrics", """{"job":"test"}""") }

      val context = manager["metrics"]
      context.shouldNotBeNull()
      context.pathId shouldBe 123L
      context.path shouldBe "metrics"
      context.url shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics"
    }

    "registerPath should strip leading slash from path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 456L
      }

      manager.registerPath("/metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      coVerify { agent.grpcService.registerPathOnProxy("metrics", "{}") }

      val context = manager["metrics"]
      context.shouldNotBeNull()
      context.path shouldBe "metrics"
    }

    "registerPath should use default empty labels when not provided" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 789L
      }

      manager.registerPath("health", "http://localhost:$PROXY_HTTP_PORT/health")

      coVerify { agent.grpcService.registerPathOnProxy("health", "{}") }
    }

    "registerPath should throw when path is empty" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.registerPath("", "http://localhost:$PROXY_HTTP_PORT/metrics")
      }

      exception.message shouldContain "Empty path"
    }

    "registerPath should throw when url is empty" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.registerPath("metrics", "")
      }

      exception.message shouldContain "Empty URL"
    }

    "unregisterPath should remove path from map" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
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

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
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

      exception.message shouldContain "Empty path"
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

    "get operator should return null for non-existent path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      manager["nonexistent"].shouldBeNull()
    }

    "get operator should return PathContext for registered path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
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

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
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
        listOf(
          DiscoveredPath("a", "a_metrics", "http://a/m", "{}"),
          DiscoveredPath("b", "b_metrics", "http://b/m", "{}"),
        ),
      )

      manager["a_metrics"].shouldNotBeNull().source shouldBe PathSource.DISCOVERED
      manager["b_metrics"].shouldNotBeNull()
      coVerify(exactly = 1) { grpc.registerPathOnProxy("a_metrics", any()) }
    }

    "reconcile unregisters discovered paths no longer desired" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      manager.reconcileDiscoveredPaths(
        listOf(
          DiscoveredPath("a", "a_metrics", "http://a/m", "{}"),
          DiscoveredPath("b", "b_metrics", "http://b/m", "{}"),
        ),
      )
      manager.reconcileDiscoveredPaths(listOf(DiscoveredPath("b", "b_metrics", "http://b/m", "{}")))

      manager["a_metrics"].shouldBeNull()
      manager["b_metrics"].shouldNotBeNull()
      coVerify(exactly = 1) { grpc.unregisterPathOnProxy("a_metrics") }
    }

    "reconcile leaves an unchanged discovered path alone (no re-register)" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      val desired = listOf(DiscoveredPath("a", "a_metrics", "http://a/m", "{}"))
      manager.reconcileDiscoveredPaths(desired)
      manager.reconcileDiscoveredPaths(desired)

      // Registered exactly once across two identical reconciles.
      coVerify(exactly = 1) { grpc.registerPathOnProxy("a_metrics", any()) }
    }

    "reconcile re-registers a discovered path whose url changed" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      manager.reconcileDiscoveredPaths(listOf(DiscoveredPath("a", "a_metrics", "http://a/v1", "{}")))
      manager.reconcileDiscoveredPaths(listOf(DiscoveredPath("a", "a_metrics", "http://a/v2", "{}")))

      manager["a_metrics"].shouldNotBeNull().url shouldBe "http://a/v2"
      coVerify(exactly = 1) { grpc.unregisterPathOnProxy("a_metrics") }
      coVerify(exactly = 2) { grpc.registerPathOnProxy("a_metrics", any()) }
    }

    "reconcile skips a discovered path colliding with a static path" {
      val agent = createMockAgent()
      val grpc = agent.grpcService
      val manager = AgentPathManager(agent)

      // Register a static baseline path, then try to discover the same path with a different url.
      manager.registerPath("shared", "http://static/m")
      manager.reconcileDiscoveredPaths(listOf(DiscoveredPath("shared", "shared", "http://discovered/m", "{}")))

      val context = manager["shared"].shouldNotBeNull()
      context.source shouldBe PathSource.STATIC
      context.url shouldBe "http://static/m"
      // Only the initial static registration happened; the colliding discovered entry was skipped.
      coVerify(exactly = 1) { grpc.registerPathOnProxy("shared", any()) }
    }

    "reconcile with an empty desired set removes all discovered but keeps static" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      manager.registerPath("static_path", "http://static/m")
      manager.reconcileDiscoveredPaths(listOf(DiscoveredPath("d", "disc_path", "http://d/m", "{}")))
      manager.reconcileDiscoveredPaths(emptyList())

      manager["disc_path"].shouldBeNull()
      manager["static_path"].shouldNotBeNull().source shouldBe PathSource.STATIC
    }

    "multiple paths can be registered" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
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
      val config = ConfigFactory.parseString(
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
        }
        proxy { auth = [] }
        """.trimIndent(),
      )
      val configVals = ConfigVals(config)

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

    "toPlainText should include URL column for configured paths" {
      val config = ConfigFactory.parseString(
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
        }
        proxy { auth = [] }
        """.trimIndent(),
      )
      val configVals = ConfigVals(config)

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

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } coAnswers {
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
      val config = ConfigFactory.parseString(
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
        }
        proxy { auth = [] }
        """.trimIndent(),
      )
      val configVals = ConfigVals(config)

      val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.grpcService } returns mockGrpcService
      every { mockAgent.configVals } returns configVals
      every { mockAgent.isTestMode } returns true
      every { mockAgent.agentId } returns "test-agent"

      coEvery { mockGrpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }

      val manager = AgentPathManager(mockAgent)
      manager.registerPaths()

      coVerify { mockGrpcService.registerPathOnProxy("metrics1", "{}") }
      coVerify { mockGrpcService.registerPathOnProxy("metrics2", "{}") }

      manager["metrics1"].shouldNotBeNull()
      manager["metrics2"].shouldNotBeNull()
    }
  }
}
