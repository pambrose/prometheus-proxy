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

import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AgentGrpcServiceTest {
  private fun createMockAgent(proxyHostname: String): Agent {
    val mockMetrics = mockk<AgentMetrics>(relaxed = true)
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
    every { mockAgent.metrics(any<AgentMetrics.() -> Unit>()) } answers {
      val block = firstArg<AgentMetrics.() -> Unit>()
      block(mockMetrics)
    }

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

  // Bug #9: The old code incremented scrapeRequestBacklogSize AFTER sending the action to the
  // channel. Because processScrapeResults runs in a separate coroutine, it could consume the
  // action and decrement the counter before the producer incremented it, causing the counter
  // to go negative. The fix moves the increment to BEFORE the channel send.
  // This test simulates the same producer-consumer channel pattern and verifies the counter
  // never goes negative.
  @Test
  fun `backlog counter should never go negative with increment-before-send pattern`(): Unit =
    runBlocking {
      val channel = Channel<Int>(UNLIMITED)
      val backlogSize = AtomicInteger(0)
      val wentNegative = AtomicBoolean(false)
      val itemCount = 10_000

      coroutineScope {
        // Producer: mirrors readRequestsFromProxy — increment BEFORE send (the fix)
        launch(Dispatchers.IO) {
          repeat(itemCount) { i ->
            backlogSize.incrementAndGet()
            channel.send(i)
          }
          channel.close()
        }

        // Consumer: mirrors processScrapeResults — decrement after receive
        launch(Dispatchers.IO) {
          for (@Suppress("UnusedPrivateProperty") item in channel) {
            val current = backlogSize.decrementAndGet()
            if (current < 0) {
              wentNegative.set(true)
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
        // Producer: increment before send
        launch(Dispatchers.IO) {
          repeat(itemCount) { i ->
            backlogSize.incrementAndGet()
            channel.send(i)
          }
          channel.close()
        }

        // Consumer: decrement and track minimum value observed
        launch(Dispatchers.IO) {
          for (@Suppress("UnusedPrivateProperty") item in channel) {
            val current = backlogSize.decrementAndGet()
            minObserved.updateAndGet { min -> minOf(min, current) }
          }
        }
      }

      minObserved.get() shouldBeGreaterThanOrEqual 0
      backlogSize.get() shouldBe 0
    }
}
