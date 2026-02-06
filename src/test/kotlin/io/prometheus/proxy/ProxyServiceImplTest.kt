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

package io.prometheus.proxy

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatRequest
import io.prometheus.grpc.pathMapSizeRequest
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.grpc.registerPathRequest
import io.prometheus.grpc.unregisterPathRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProxyServiceImplTest {
  private fun createMockProxy(
    transportFilterDisabled: Boolean = false,
    isRunning: Boolean = true,
  ): Proxy {
    val mockOptions = mockk<ProxyOptions>(relaxed = true)
    every { mockOptions.transportFilterDisabled } returns transportFilterDisabled

    val mockMetrics = mockk<ProxyMetrics>(relaxed = true)
    val mockAgentContextManager = mockk<AgentContextManager>(relaxed = true)
    val mockPathManager = mockk<ProxyPathManager>(relaxed = true)
    val mockScrapeRequestManager = mockk<ScrapeRequestManager>(relaxed = true)

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.options } returns mockOptions
    every { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers {
      val block = firstArg<ProxyMetrics.() -> Unit>()
      block(mockMetrics)
    }
    every { mockProxy.agentContextManager } returns mockAgentContextManager
    every { mockProxy.pathManager } returns mockPathManager
    every { mockProxy.scrapeRequestManager } returns mockScrapeRequestManager
    every { mockProxy.isRunning } returns isRunning

    return mockProxy
  }

  @Test
  fun `connectAgent should succeed when transportFilterDisabled matches`() =
    runBlocking {
      val proxy = createMockProxy(transportFilterDisabled = false)
      val service = ProxyServiceImpl(proxy)

      val result = service.connectAgent(EMPTY_INSTANCE)

      result shouldBe EMPTY_INSTANCE
      verify { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
    }

  @Test
  fun `connectAgent should throw when transportFilterDisabled mismatch`(): Unit =
    runBlocking {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val service = ProxyServiceImpl(proxy)

      val exception = assertThrows<RequestFailureException> {
        service.connectAgent(EMPTY_INSTANCE)
      }

      exception.message shouldContain "do not have matching transportFilterDisabled config values"
    }

  // Tests the transport filter security mechanism: when transportFilterDisabled=true on both
  // proxy and agent, a direct gRPC connection is established without the transport filter.
  // This creates an AgentContext that tracks the agent's state throughout its connection lifetime.
  @Test
  fun `connectAgentWithTransportFilterDisabled should create agent context`() =
    runBlocking {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextSlot = slot<AgentContext>()

      every { proxy.agentContextManager.addAgentContext(capture(agentContextSlot)) } returns null

      val service = ProxyServiceImpl(proxy)
      val result = service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)

      result.agentId.isNotEmpty().shouldBeTrue()
      agentContextSlot.isCaptured.shouldBeTrue()
      agentContextSlot.captured.agentId shouldBe result.agentId

      verify { proxy.agentContextManager.addAgentContext(any()) }
      verify { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
    }

  @Test
  fun `connectAgentWithTransportFilterDisabled should throw when transportFilterDisabled is false`(): Unit =
    runBlocking {
      val proxy = createMockProxy(transportFilterDisabled = false)
      val service = ProxyServiceImpl(proxy)

      val exception = assertThrows<RequestFailureException> {
        service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)
      }

      exception.message shouldContain "do not have matching transportFilterDisabled config values"
    }

  @Test
  fun `registerAgent should succeed with valid agent context`() =
    runBlocking {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = registerAgentRequest {
        agentId = testAgentId
        agentName = "test-agent"
        hostName = "test-host"
        launchId = "launch-123"
        consolidated = false
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerAgent(request)

      response.valid.shouldBeTrue()
      response.agentId shouldBe testAgentId

      verify { mockAgentContext.assignProperties(request) }
      verify { mockAgentContext.markActivityTime(false) }
    }

  @Test
  fun `registerAgent should fail with missing agent context`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = registerAgentRequest {
        agentId = testAgentId
        agentName = "test-agent"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerAgent(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
    }

  @Test
  fun `registerPath should succeed with valid agent context`() =
    runBlocking {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"
      val testPath = "/metrics"
      val testLabels = "job=\"test\""

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { proxy.pathManager.pathMapSize } returns 5

      val request = registerPathRequest {
        agentId = testAgentId
        path = testPath
        labels = testLabels
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeTrue()
      response.pathId shouldNotBe -1
      response.pathCount shouldBe 5

      verify { proxy.pathManager.addPath(testPath, testLabels, mockAgentContext) }
      verify { mockAgentContext.markActivityTime(false) }
    }

  @Test
  fun `registerPath should fail with missing agent context`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null
      every { proxy.pathManager.pathMapSize } returns 0

      val request = registerPathRequest {
        agentId = testAgentId
        path = "/metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeFalse()
      response.pathId shouldBe -1
      response.reason shouldContain "Invalid agentId"
    }

  @Test
  fun `unregisterPath should succeed with valid agent context`() =
    runBlocking {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"
      val testPath = "/metrics"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      coEvery { proxy.pathManager.removePath(testPath, testAgentId) } returns mockk(relaxed = true) {
        every { valid } returns true
      }

      val request = unregisterPathRequest {
        agentId = testAgentId
        path = testPath
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.unregisterPath(request)

      response.valid.shouldBeTrue()

      coVerify { proxy.pathManager.removePath(testPath, testAgentId) }
      verify { mockAgentContext.markActivityTime(false) }
    }

  @Test
  fun `unregisterPath should fail with missing agent context`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = unregisterPathRequest {
        agentId = testAgentId
        path = "/metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.unregisterPath(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
    }

  @Test
  fun `pathMapSize should return path count`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      every { proxy.pathManager.pathMapSize } returns 42

      val request = pathMapSizeRequest {}

      val service = ProxyServiceImpl(proxy)
      val response = service.pathMapSize(request)

      response.pathCount shouldBe 42
    }

  @Test
  fun `sendHeartBeat should succeed with valid agent context`() =
    runBlocking {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeTrue()

      verify { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
      verify { mockAgentContext.markActivityTime(false) }
    }

  // Bug #5: reason was unconditionally set to the error message even on valid heartbeats
  @Test
  fun `sendHeartBeat should not set reason when valid`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-456"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeTrue()
      // Before the fix, this was "Invalid agentId: test-agent-456 (sendHeartBeat)"
      response.reason shouldBe ""
    }

  @Test
  fun `sendHeartBeat should fail with missing agent context`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
    }

  @Test
  fun `sendHeartBeat should set reason only when invalid`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-789"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain testAgentId
      response.reason shouldContain "sendHeartBeat"
    }

  // Tests the gRPC streaming flow for scrape requests from proxy to agent.
  // The proxy continuously streams scrape requests to the agent while:
  // 1. The proxy is running
  // 2. The agent context is valid
  // This test simulates the agent becoming invalid after processing one request,
  // which terminates the stream. The isValid() mock returns [true, false] to
  // simulate one iteration of the loop before the agent disconnects.
  @Test
  fun `readRequestsFromProxy should emit scrape requests when agent is valid`(): Unit =
    runBlocking {
      val proxy = createMockProxy(isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val mockScrapeRequestWrapper = mockk<ScrapeRequestWrapper>(relaxed = true)
      val mockScrapeRequest = mockk<io.prometheus.grpc.ScrapeRequest>(relaxed = true)
      val testAgentId = "test-agent-123"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returnsMany listOf(true, false)
      coEvery { mockAgentContext.readScrapeRequest() } returns mockScrapeRequestWrapper andThen null
      every { mockScrapeRequestWrapper.scrapeRequest } returns mockScrapeRequest
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val emittedRequests = mutableListOf<io.prometheus.grpc.ScrapeRequest>()
      flow.collect { emittedRequests.add(it) }

      emittedRequests.size shouldBe 1
      emittedRequests[0] shouldBe mockScrapeRequest
    }

  @Test
  fun `readRequestsFromProxy should emit nothing when agent context is missing`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = agentInfo {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val emittedRequests = mutableListOf<io.prometheus.grpc.ScrapeRequest>()
      flow.collect { emittedRequests.add(it) }

      emittedRequests.size shouldBe 0
    }
}
