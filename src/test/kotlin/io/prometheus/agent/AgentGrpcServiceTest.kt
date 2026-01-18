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

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

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
    every { mockOptions.chunkContentSizeKbs } returns 32768
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
}
