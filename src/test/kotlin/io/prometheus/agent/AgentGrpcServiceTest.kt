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

import io.grpc.Metadata
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatResponse
import io.prometheus.grpc.registerAgentResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AgentGrpcServiceTest {
  private fun createMockAgent(proxyHostname: String): Agent {
    val mockOptions = mockk<AgentOptions>(relaxed = true)

    every { mockOptions.proxyHostname } returns proxyHostname
    every { mockOptions.consolidated } returns false
    every { mockOptions.certChainFilePath } returns ""
    every { mockOptions.privateKeyFilePath } returns ""
    every { mockOptions.trustCertCollectionFilePath } returns ""
    every { mockOptions.transportFilterDisabled } returns false
    every { mockOptions.chunkContentSizeBytes } returns 32768
    every { mockOptions.keepAliveTimeSecs } returns -1L
    every { mockOptions.keepAliveTimeoutSecs } returns -1L
    every { mockOptions.keepAliveWithoutCalls } returns false
    every { mockOptions.overrideAuthority } returns ""

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.agentId } returns "test-agent-123"
    every { mockAgent.launchId } returns "launch-123"
    every { mockAgent.agentName } returns "test-agent"
    every { mockAgent.options } returns mockOptions
    every { mockAgent.isRunning } returns true
    every { mockAgent.isZipkinEnabled } returns false
    every { mockAgent.proxyHost } returns proxyHostname
    // metrics() is a no-op in tests; invoking the block with a relaxed mock
    // would cause ClassCastException because relaxed Counter mocks don't return Counter.Child
    every { mockAgent.metrics(any<AgentMetrics.() -> Unit>()) } answers {}

    return mockAgent
  }

  @Test
  fun `parseProxyHostname should extract hostname and port correctly`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "localhost"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should use default port when not specified`(): Unit =
    runBlocking {
      val agent = createMockAgent("example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should strip http prefix`(): Unit =
    runBlocking {
      val agent = createMockAgent("http://example.com:8080")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 8080

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should strip https prefix`(): Unit =
    runBlocking {
      val agent = createMockAgent("https://example.com:8443")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 8443

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should handle custom port`(): Unit =
    runBlocking {
      val agent = createMockAgent("proxy.example.org:9090")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "proxy.example.org"
      service.agentPort shouldBe 9090

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should handle IPv4 address`(): Unit =
    runBlocking {
      val agent = createMockAgent("192.168.1.100:5000")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "192.168.1.100"
      service.agentPort shouldBe 5000

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should handle hostname without port and default to 50051`(): Unit =
    runBlocking {
      val agent = createMockAgent("my-proxy-server")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "my-proxy-server"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should strip http prefix and use custom port`(): Unit =
    runBlocking {
      val agent = createMockAgent("http://localhost:9999")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "localhost"
      service.agentPort shouldBe 9999

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should strip https prefix and use custom port`(): Unit =
    runBlocking {
      val agent = createMockAgent("https://secure.proxy.com:443")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "secure.proxy.com"
      service.agentPort shouldBe 443

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should handle http prefix without port`(): Unit =
    runBlocking {
      val agent = createMockAgent("http://example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

  @Test
  fun `parseProxyHostname should handle https prefix without port`(): Unit =
    runBlocking {
      val agent = createMockAgent("https://example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

  // The backlog counter is now incremented on the consumer side (inside the launched coroutine
  // in Agent.kt) rather than the producer side (readRequestsFromProxy). This eliminates leaks
  // when items are discarded from a cancelled channel — items that were never consumed are
  // never counted. The increment and decrement are always paired in the same try-finally scope.
  @Test
  fun `backlog counter should never go negative with consumer-side increment pattern`(): Unit =
    runBlocking {
      val channel = Channel<Int>(UNLIMITED)
      val backlogSize = AtomicInteger(0)
      val wentNegative = AtomicBoolean(false)
      val itemCount = 10_000

      coroutineScope {
        // Producer: mirrors readRequestsFromProxy — no increment, just send
        launch(Dispatchers.IO) {
          repeat(itemCount) { i ->
            channel.send(i)
          }
          channel.close()
        }

        // Consumer: mirrors Agent.kt consumer loop — increment then try/finally decrement
        launch(Dispatchers.IO) {
          for (@Suppress("UnusedPrivateProperty") item in channel) {
            backlogSize.incrementAndGet()
            try {
              // Simulate processing
            } finally {
              val current = backlogSize.decrementAndGet()
              if (current < 0) {
                wentNegative.set(true)
              }
            }
          }
        }
      }

      wentNegative.get() shouldBe false
      backlogSize.get() shouldBe 0
    }

  @Test
  fun `backlog counter should remain non-negative throughout processing`(): Unit =
    runBlocking {
      val channel = Channel<Int>(UNLIMITED)
      val backlogSize = AtomicInteger(0)
      val minObserved = AtomicInteger(Int.MAX_VALUE)
      val itemCount = 10_000

      coroutineScope {
        // Producer: no increment, just send
        launch(Dispatchers.IO) {
          repeat(itemCount) { i ->
            channel.send(i)
          }
          channel.close()
        }

        // Consumer: increment then try/finally decrement, track minimum
        launch(Dispatchers.IO) {
          for (@Suppress("UnusedPrivateProperty") item in channel) {
            backlogSize.incrementAndGet()
            try {
              // Simulate processing
            } finally {
              val current = backlogSize.decrementAndGet()
              minObserved.updateAndGet { min -> minOf(min, current) }
            }
          }
        }
      }

      minObserved.get() shouldBeGreaterThanOrEqual 0
      backlogSize.get() shouldBe 0
    }

  // ==================== ShutDown Tests ====================

  @Test
  fun `shutDown should be safe to call when service has started`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // shutDown should not throw
      service.shutDown()
    }

  @Test
  fun `shutDown should be safe to call multiple times`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Calling shutDown multiple times should not throw
      service.shutDown()
      service.shutDown()
    }

  // ==================== gRPC Stub Tests ====================

  @Test
  fun `grpcStub should be initialized after construction`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // grpcStub should be accessible (no UninitializedPropertyAccessException)
      service.grpcStub.toString().shouldNotBeEmpty()

      service.shutDown()
    }

  // ==================== connectAgent Tests ====================

  @Test
  fun `connectAgent should return true on successful connection`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.connectAgent(any(), any<Metadata>()) } returns EMPTY_INSTANCE
      service.grpcStub = mockStub

      val result = service.connectAgent(transportFilterDisabled = false)

      result.shouldBeTrue()
      service.shutDown()
    }

  @Test
  fun `connectAgent should return false on connection failure`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws RuntimeException("Connection refused")
      service.grpcStub = mockStub

      val result = service.connectAgent(transportFilterDisabled = false)

      result.shouldBeFalse()
      service.shutDown()
    }

  @Test
  fun `connectAgent with transportFilterDisabled should assign agentId`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.connectAgentWithTransportFilterDisabled(any(), any<Metadata>()) } returns agentInfo {
        agentId = "assigned-agent-id"
      }
      service.grpcStub = mockStub

      val result = service.connectAgent(transportFilterDisabled = true)

      result.shouldBeTrue()
      verify { agent.agentId = "assigned-agent-id" }
      service.shutDown()
    }

  // ==================== registerAgent Tests ====================

  @Test
  fun `registerAgent should send request with correct agent details`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse { valid = true }
      service.grpcStub = mockStub

      val latch = CountDownLatch(1)
      service.registerAgent(latch)

      coVerify {
        mockStub.registerAgent(
          match { it.agentId == "test-agent-123" && it.launchId == "launch-123" },
          any<Metadata>(),
        )
      }
      latch.count shouldBe 0L
      service.shutDown()
    }

  @Test
  fun `registerAgent should throw RequestFailureException on invalid response`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse {
        valid = false
        reason = "Agent already registered"
      }
      service.grpcStub = mockStub

      val latch = CountDownLatch(1)
      assertThrows<RequestFailureException> {
        service.registerAgent(latch)
      }
      service.shutDown()
    }

  // ==================== sendHeartBeat Tests ====================

  @Test
  fun `sendHeartBeat should skip when agentId is empty`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      every { agent.agentId } returns ""
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      service.grpcStub = mockStub

      service.sendHeartBeat()

      coVerify(exactly = 0) { mockStub.sendHeartBeat(any(), any<Metadata>()) }
      service.shutDown()
    }

  @Test
  fun `sendHeartBeat should send request when agentId is set`(): Unit =
    runBlocking {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } returns heartBeatResponse { valid = true }
      service.grpcStub = mockStub

      service.sendHeartBeat()

      coVerify { mockStub.sendHeartBeat(match { it.agentId == "test-agent-123" }, any<Metadata>()) }
      service.shutDown()
    }
}
