/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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
import io.prometheus.common.ConfigVals
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.unregisterPathResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class AgentPathManagerTest : StringSpec() {
  private fun createMockAgent(): Agent {
    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

    // Create a real ConfigVals with minimal config
    val config = ConfigFactory.parseString(
      """
      agent {
        pathConfigs = []
      }
      proxy {}
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

      manager.registerPath("metrics", "http://localhost:8080/metrics", """{"job":"test"}""")

      coVerify { agent.grpcService.registerPathOnProxy("metrics", """{"job":"test"}""") }

      val context = manager["metrics"]
      context.shouldNotBeNull()
      context.pathId shouldBe 123L
      context.path shouldBe "metrics"
      context.url shouldBe "http://localhost:8080/metrics"
    }

    "registerPath should strip leading slash from path" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 456L
      }

      manager.registerPath("/metrics", "http://localhost:8080/metrics")

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

      manager.registerPath("health", "http://localhost:8080/health")

      coVerify { agent.grpcService.registerPathOnProxy("health", "{}") }
    }

    "registerPath should throw when path is empty" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.registerPath("", "http://localhost:8080/metrics")
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

      manager.registerPath("metrics", "http://localhost:8080/metrics")
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

      manager.registerPath("metrics", "http://localhost:8080/metrics")

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

      manager.registerPath("test", "http://localhost:9090/test", """{"env":"prod"}""")

      val context = manager["test"]
      context.shouldNotBeNull()
      context.pathId shouldBe 999L
      context.path shouldBe "test"
      context.url shouldBe "http://localhost:9090/test"
      context.labels shouldBe """{"env":"prod"}"""
    }

    "clear should remove all paths" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }

      manager.registerPath("path1", "http://localhost:8080/path1")
      manager.registerPath("path2", "http://localhost:8080/path2")
      manager.registerPath("path3", "http://localhost:8080/path3")

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
        url = "http://localhost:8080/metrics",
        labels = """{"job":"test"}""",
      )

      context.pathId shouldBe 123L
      context.path shouldBe "metrics"
      context.url shouldBe "http://localhost:8080/metrics"
      context.labels shouldBe """{"job":"test"}"""
    }

    "multiple paths can be registered" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)

      coEvery { agent.grpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
        valid = true
        pathId = 1L
      }

      manager.registerPath("metrics", "http://localhost:8080/metrics")
      manager.registerPath("health", "http://localhost:8080/health")
      manager.registerPath("info", "http://localhost:8080/info")

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
              url = "http://localhost:8080/metrics"
              labels = "{}"
            }
          ]
        }
        proxy {}
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
        proxy {}
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

    // Bug #6: The gRPC call and local map update in registerPath were not atomic.
    // Two concurrent calls for the same path could result in the local map having a
    // stale pathId (from the slower call that finished last) while the proxy has the
    // pathId from the faster call. The mutex serializes these operations.
    "concurrent registerPath calls for the same path should have consistent final state" {
      val agent = createMockAgent()
      val manager = AgentPathManager(agent)
      val firstCallStarted = CompletableDeferred<Unit>()
      val callCount = AtomicInteger(0)

      coEvery { agent.grpcService.registerPathOnProxy("metrics", any()) } coAnswers {
        val num = callCount.incrementAndGet()
        if (num == 1) {
          firstCallStarted.complete(Unit)
          delay(100) // The first gRPC call is slow
        }
        registerPathResponse {
          valid = true
          pathId = num.toLong()
        }
      }

      coroutineScope {
        launch { manager.registerPath("metrics", "http://localhost:8080/a") }
        firstCallStarted.await() // Ensure the first call is in-flight
        launch { manager.registerPath("metrics", "http://localhost:8080/b") }
      }

      val context = manager["metrics"]
      context.shouldNotBeNull()
      // With the mutex, calls are serialized: first call writes pathId=1,
      // then second call writes pathId=2. The final state should always be
      // pathId=2 from the last call. Without the mutex, the slow first call
      // would overwrite the fast second call, leaving a stale pathId=1.
      context.pathId shouldBe 2L
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
        proxy {}
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
