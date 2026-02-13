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

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
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
import io.prometheus.common.ConfigVals
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.chunkData
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import io.prometheus.grpc.heartBeatRequest
import io.prometheus.grpc.pathMapSizeRequest
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.grpc.registerPathRequest
import io.prometheus.grpc.scrapeResponse
import io.prometheus.grpc.summaryData
import io.prometheus.grpc.unregisterPathRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.zip.CRC32

@Suppress("LargeClass")
class ProxyServiceImplTest : StringSpec() {
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
    every { mockScrapeRequestManager.containsScrapeRequest(any()) } returns true

    val config = ConfigFactory.parseString(
      """
      proxy {
        internal {
          maxZippedContentSizeMBytes = 5
          maxUnzippedContentSizeMBytes = 10
        }
      }
      agent {
        pathConfigs = []
      }
      """.trimIndent(),
    )
    val configVals = ConfigVals(config)

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.options } returns mockOptions
    every { mockProxy.proxyConfigVals } returns configVals.proxy
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

  init {
    "connectAgent should succeed when transportFilterDisabled matches" {
      val proxy = createMockProxy(transportFilterDisabled = false)
      val service = ProxyServiceImpl(proxy)

      val result = service.connectAgent(EMPTY_INSTANCE)

      result shouldBe EMPTY_INSTANCE
      verify { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
    }

    "connectAgent should throw when transportFilterDisabled mismatch" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val service = ProxyServiceImpl(proxy)

      val exception = shouldThrow<RequestFailureException> {
        service.connectAgent(EMPTY_INSTANCE)
      }

      exception.message shouldContain "do not have matching transportFilterDisabled config values"
    }

    // Tests the transport filter security mechanism: when transportFilterDisabled=true on both
    // proxy and agent, a direct gRPC connection is established without the transport filter.
    // This creates an AgentContext that tracks the agent's state throughout its connection lifetime.
    "connectAgentWithTransportFilterDisabled should create agent context" {
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

    "connectAgentWithTransportFilterDisabled should throw when transportFilterDisabled is false" {
      val proxy = createMockProxy(transportFilterDisabled = false)
      val service = ProxyServiceImpl(proxy)

      val exception = shouldThrow<RequestFailureException> {
        service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)
      }

      exception.message shouldContain "do not have matching transportFilterDisabled config values"
    }

    "registerAgent should succeed with valid agent context" {
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

    "registerAgent should fail with missing agent context" {
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

    "registerPath should succeed with valid agent context" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"
      val testPath = "/metrics"
      val testLabels = "job=\"test\""

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { proxy.pathManager.addPath(testPath, testLabels, mockAgentContext) } returns null
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

    // Bug #11: registerPath should propagate the actual failure reason from addPath,
    // not always say "Invalid agentId" which is misleading for consolidated mismatch.
    "registerPath should include addPath failure reason when path registration rejected" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-consolidated"
      val testPath = "/metrics"
      val rejectionReason = "Consolidated agent rejected for non-consolidated path /metrics"

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { proxy.pathManager.addPath(testPath, any(), mockAgentContext) } returns rejectionReason
      every { proxy.pathManager.pathMapSize } returns 0

      val request = registerPathRequest {
        agentId = testAgentId
        path = testPath
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeFalse()
      response.pathId shouldBe -1
      // Before the fix, this was "Invalid agentId: test-agent-consolidated (registerPath)"
      response.reason shouldBe rejectionReason
      response.reason shouldContain "Consolidated"
    }

    "registerPath should say Invalid agentId when agent context is missing" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-456"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null
      every { proxy.pathManager.pathMapSize } returns 0

      val request = registerPathRequest {
        agentId = testAgentId
        path = "/metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
      response.reason shouldContain testAgentId
    }

    "registerPath should fail with missing agent context" {
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

    "unregisterPath should succeed with valid agent context" {
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

    "unregisterPath should fail with missing agent context" {
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

    "pathMapSize should return path count" {
      val proxy = createMockProxy()
      every { proxy.pathManager.pathMapSize } returns 42

      val request = pathMapSizeRequest {}

      val service = ProxyServiceImpl(proxy)
      val response = service.pathMapSize(request)

      response.pathCount shouldBe 42
    }

    "sendHeartBeat should succeed with valid agent context" {
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
    "sendHeartBeat should not set reason when valid" {
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

    "sendHeartBeat should fail with missing agent context" {
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

    "sendHeartBeat should set reason only when invalid" {
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
    "readRequestsFromProxy should emit scrape requests when agent is valid" {
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

    "readRequestsFromProxy should emit nothing when agent context is missing" {
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

    // ==================== writeResponsesToProxy Tests ====================

    "writeResponsesToProxy should process non-chunked scrape responses" {
      val proxy = createMockProxy()

      val response = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 100L
        validResponse = true
        statusCode = 200
        contentType = "text/plain"
        zipped = false
        contentAsText = "metrics data"
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf(response))

      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should handle empty flow" {
      val proxy = createMockProxy()

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf())

      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should process multiple responses" {
      val proxy = createMockProxy()

      val response1 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 101L
        validResponse = true
        statusCode = 200
      }
      val response2 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 102L
        validResponse = true
        statusCode = 200
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf(response1, response2))

      result shouldBe EMPTY_INSTANCE
    }

    // ==================== writeChunkedResponsesToProxy Tests ====================

    "writeChunkedResponsesToProxy should handle empty flow" {
      val proxy = createMockProxy()

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf())

      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should process header-chunk-summary sequence" {
      val proxy = createMockProxy()
      val scrapeId = 200L
      val data = "test chunk data".toByteArray()
      val crc = CRC32()
      crc.update(data)

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }

      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data)
        }
      }

      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = crc.value
        }
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk, summary))

      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should process multi-chunk sequence" {
      val proxy = createMockProxy()
      val scrapeId = 300L
      val data1 = "chunk-one-data".toByteArray()
      val data2 = "chunk-two-data".toByteArray()
      val crc = CRC32()

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }

      crc.update(data1)
      val chunk1 = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data1.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data1)
        }
      }

      crc.update(data2)
      val chunk2 = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 2
          chunkByteCount = data2.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data2)
        }
      }

      val totalSize = data1.size + data2.size
      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 2
          summaryByteCount = totalSize
          summaryChecksum = crc.value
        }
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk1, chunk2, summary))

      result shouldBe EMPTY_INSTANCE
    }

    // ==================== readRequestsFromProxy Edge Case Tests ====================

    "readRequestsFromProxy should stop when proxy stops running" {
      val proxy = createMockProxy(isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-proxy-stop"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returns true
      // Simulate proxy stopping after first check
      every { proxy.isRunning } returnsMany listOf(true, false)
      coEvery { mockAgentContext.readScrapeRequest() } returns null
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val emittedRequests = mutableListOf<io.prometheus.grpc.ScrapeRequest>()
      flow.collect { emittedRequests.add(it) }

      emittedRequests.size shouldBe 0
    }

    "readRequestsFromProxy should skip null readScrapeRequest results" {
      val proxy = createMockProxy(isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-null-read"

      every { mockAgentContext.agentId } returns testAgentId
      // Valid for two iterations then invalid
      every { mockAgentContext.isValid() } returnsMany listOf(true, true, false)
      // Return null both times (channel drained)
      coEvery { mockAgentContext.readScrapeRequest() } returns null
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val emittedRequests = mutableListOf<io.prometheus.grpc.ScrapeRequest>()
      flow.collect { emittedRequests.add(it) }

      // readScrapeRequest returned null, so nothing emitted
      emittedRequests.size shouldBe 0
    }

    // ==================== readRequestsFromProxy Cleanup Tests ====================

    "readRequestsFromProxy should clean up agent context when transportFilterDisabled" {
      val proxy = createMockProxy(transportFilterDisabled = true, isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-cleanup"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returns false
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      flow.collect {}

      // Agent context should be cleaned up since transportFilterDisabled is true
      verify { proxy.removeAgentContext(testAgentId, any()) }
    }

    "readRequestsFromProxy should not clean up agent context when transportFilter enabled" {
      val proxy = createMockProxy(transportFilterDisabled = false, isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-no-cleanup"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returns false
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      flow.collect {}

      // Agent context should NOT be cleaned up — ProxyServerTransportFilter handles it
      verify(exactly = 0) { proxy.removeAgentContext(any(), any()) }
    }

    "readRequestsFromProxy should clean up on stream cancellation when transportFilterDisabled" {
      val proxy = createMockProxy(transportFilterDisabled = true, isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val mockScrapeRequestWrapper = mockk<ScrapeRequestWrapper>(relaxed = true)
      val mockScrapeRequest = mockk<io.prometheus.grpc.ScrapeRequest>(relaxed = true)
      val testAgentId = "test-agent-cancel-cleanup"

      every { mockAgentContext.agentId } returns testAgentId
      // Valid for one iteration, then stream will be cancelled by the test
      every { mockAgentContext.isValid() } returnsMany listOf(true, false)
      coEvery { mockAgentContext.readScrapeRequest() } returns mockScrapeRequestWrapper andThen null
      every { mockScrapeRequestWrapper.scrapeRequest } returns mockScrapeRequest
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      flow.collect {}

      verify { proxy.removeAgentContext(testAgentId, any()) }
    }

    // ==================== writeResponsesToProxy Error Handling Tests ====================

    "writeResponsesToProxy should handle flow error gracefully when proxy running" {
      val proxy = createMockProxy(isRunning = true)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ScrapeResponse> = flow {
        throw IllegalStateException("Simulated flow error")
      }

      // Should not throw — error is caught in onFailure
      val result = service.writeResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should suppress error when proxy not running" {
      val proxy = createMockProxy(isRunning = false)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ScrapeResponse> = flow {
        throw IllegalStateException("Simulated flow error")
      }

      val result = service.writeResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should continue processing after single message failure" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager

      val goodResponse1 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 601L
        validResponse = true
        statusCode = 200
      }
      val goodResponse2 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 602L
        validResponse = true
        statusCode = 200
      }

      // Make assignScrapeResults throw on the first call, succeed on the second
      var callCount = 0
      every { scrapeRequestManager.assignScrapeResults(any()) } answers {
        callCount++
        if (callCount == 1) error("Simulated processing failure")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf(goodResponse1, goodResponse2))

      result shouldBe EMPTY_INSTANCE
      // Both messages should have been attempted (stream not killed by first failure)
      verify(exactly = 2) { scrapeRequestManager.assignScrapeResults(any()) }
    }

    // M2: Previously, writeChunkedResponsesToProxy used string-based dispatch on
    // ooc.name.lowercase() which would throw IllegalStateException on CHUNKONEOF_NOT_SET,
    // crashing the entire chunked stream. Now it uses enum constants and handles NOT_SET gracefully.
    "writeChunkedResponsesToProxy should skip message with no oneOf field set" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val service = ProxyServiceImpl(proxy)

      // A default ChunkedScrapeResponse has CHUNKONEOF_NOT_SET
      val emptyResponse = chunkedScrapeResponse {}

      val result = service.writeChunkedResponsesToProxy(flowOf(emptyResponse))

      result shouldBe EMPTY_INSTANCE
      // No chunked context should have been created
      verify(exactly = 0) { contextManager.putChunkedContext(any(), any()) }
    }

    "writeChunkedResponsesToProxy should continue processing after NOT_SET message" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val service = ProxyServiceImpl(proxy)

      val scrapeId = 300L
      val data = "test data".toByteArray()
      val crc = CRC32()
      crc.update(data)
      val checksum = crc.value

      val emptyResponse = chunkedScrapeResponse {}
      val header = chunkedScrapeResponse {
        header = headerData {
          headerScrapeId = scrapeId
          headerValidResponse = true
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }
      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkBytes = ByteString.copyFrom(data)
          chunkByteCount = data.size
          chunkCount = 1
          chunkChecksum = checksum
        }
      }

      crc.reset()
      crc.update(data)
      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = checksum
        }
      }

      every { contextManager.putChunkedContext(scrapeId, any()) } returns Unit
      every { contextManager.getChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000)
      every { contextManager.removeChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000).apply {
        applyChunk(data, data.size, 1, checksum)
      }
      every { scrapeRequestManager.assignScrapeResults(any()) } returns Unit

      // NOT_SET message first, then a valid header-chunk-summary sequence
      val result = service.writeChunkedResponsesToProxy(flowOf(emptyResponse, header, chunk, summary))

      result shouldBe EMPTY_INSTANCE
      // The valid sequence should still be processed despite the NOT_SET message
      verify(exactly = 1) { contextManager.putChunkedContext(scrapeId, any()) }
      verify(exactly = 1) { scrapeRequestManager.assignScrapeResults(any()) }
    }

    // ==================== writeChunkedResponsesToProxy Error Handling Tests ====================

    "writeChunkedResponsesToProxy should handle flow error gracefully" {
      val proxy = createMockProxy(isRunning = true)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ChunkedScrapeResponse> = flow {
        throw IllegalStateException("Simulated chunked flow error")
      }

      val result = service.writeChunkedResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should suppress error when proxy not running" {
      val proxy = createMockProxy(isRunning = false)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ChunkedScrapeResponse> = flow {
        throw IllegalStateException("Simulated chunked flow error")
      }

      val result = service.writeChunkedResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should clean up orphaned contexts on stream failure" {
      val proxy = createMockProxy(isRunning = true)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId = 400L

      val chunkedContext = mockk<ChunkedContext>(relaxed = true)
      every { contextManager.removeChunkedContext(scrapeId) } returns chunkedContext

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }

      // Flow emits a header then fails before sending a summary
      val failingFlow: Flow<ChunkedScrapeResponse> = flow {
        emit(header)
        error("Simulated agent disconnect")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(failingFlow)

      result shouldBe EMPTY_INSTANCE

      // Verify the header was stored
      verify { contextManager.putChunkedContext(scrapeId, any()) }
      // Verify the orphaned context was cleaned up
      verify { contextManager.removeChunkedContext(scrapeId) }
      // Bug #4: Verify the waiting HTTP handler is notified via failScrapeRequest
      verify { scrapeRequestManager.failScrapeRequest(scrapeId, match { it.contains("abandoned") }) }
    }

    "writeChunkedResponsesToProxy should clean up multiple orphaned contexts on stream failure" {
      val proxy = createMockProxy(isRunning = true)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId1 = 401L
      val scrapeId2 = 402L

      every { contextManager.removeChunkedContext(scrapeId1) } returns mockk(relaxed = true)
      every { contextManager.removeChunkedContext(scrapeId2) } returns mockk(relaxed = true)

      val header1 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId1
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics1"
        }
      }
      val header2 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId2
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics2"
        }
      }

      val failingFlow: Flow<ChunkedScrapeResponse> = flow {
        emit(header1)
        emit(header2)
        error("Simulated agent disconnect")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(failingFlow)

      result shouldBe EMPTY_INSTANCE

      verify { contextManager.putChunkedContext(scrapeId1, any()) }
      verify { contextManager.putChunkedContext(scrapeId2, any()) }
      // Both orphaned contexts should be cleaned up
      verify { contextManager.removeChunkedContext(scrapeId1) }
      verify { contextManager.removeChunkedContext(scrapeId2) }
      // Bug #4: Both orphaned scrape requests should be failed
      verify { scrapeRequestManager.failScrapeRequest(scrapeId1, match { it.contains("abandoned") }) }
      verify { scrapeRequestManager.failScrapeRequest(scrapeId2, match { it.contains("abandoned") }) }
    }

    "writeChunkedResponsesToProxy should not clean up completed contexts on stream failure" {
      val proxy = createMockProxy(isRunning = true)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val completedScrapeId = 403L
      val orphanedScrapeId = 404L

      val data = "test data".toByteArray()
      val crc = CRC32()
      crc.update(data)

      // Set up the completed context's chunked context for the summary removal
      val completedChunkedContext = ChunkedContext(
        chunkedScrapeResponse {
          header = headerData {
            headerValidResponse = true
            headerScrapeId = completedScrapeId
            headerAgentId = "agent-1"
            headerStatusCode = 200
            headerContentType = "text/plain"
            headerUrl = "http://test/metrics"
          }
        },
        1000000,
      )
      completedChunkedContext.applyChunk(data, data.size, 1, crc.value)

      // Return the real chunked context when summary removes it
      every { contextManager.removeChunkedContext(completedScrapeId) } returns completedChunkedContext
      every { contextManager.removeChunkedContext(orphanedScrapeId) } returns mockk(relaxed = true)

      val header1 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = completedScrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }
      val chunk1 = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = completedScrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data)
        }
      }
      val summary1 = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = completedScrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = crc.value
        }
      }
      val header2 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = orphanedScrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics2"
        }
      }

      val failingFlow: Flow<ChunkedScrapeResponse> = flow {
        // First transfer completes normally
        emit(header1)
        emit(chunk1)
        emit(summary1)
        // Second transfer starts but fails before summary
        emit(header2)
        error("Simulated agent disconnect")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(failingFlow)

      result shouldBe EMPTY_INSTANCE

      // Completed context was removed by summary processing (assignScrapeResults called)
      verify(exactly = 1) { contextManager.removeChunkedContext(completedScrapeId) }
      verify(exactly = 1) { scrapeRequestManager.assignScrapeResults(any()) }
      // Orphaned context should be cleaned up and failed during cleanup phase
      verify { contextManager.removeChunkedContext(orphanedScrapeId) }
      // Bug #4: Only the orphaned scrape should be failed, not the completed one
      verify { scrapeRequestManager.failScrapeRequest(orphanedScrapeId, match { it.contains("abandoned") }) }
      verify(exactly = 0) { scrapeRequestManager.failScrapeRequest(eq(completedScrapeId), any()) }
    }

    // Bug #2: Chunk validation failure left the HTTP handler waiting until timeout.
    // The fix calls failScrapeRequest() to notify the handler immediately.

    "writeChunkedResponsesToProxy should notify handler on chunk validation failure" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId = 500L

      // Create a ChunkedContext from a real header, then set up the mock
      // to return it. Send a chunk with a bad checksum to trigger validation failure.
      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }

      val data = "test chunk data".toByteArray()
      val badChecksum = 12345L // Wrong checksum to trigger ChunkValidationException

      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = badChecksum
          chunkBytes = ByteString.copyFrom(data)
        }
      }

      // Use a real ChunkedContext so applyChunk() actually validates
      every { contextManager.getChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000)

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk))

      result shouldBe EMPTY_INSTANCE

      // The waiting HTTP handler should have been notified via failScrapeRequest
      verify { scrapeRequestManager.failScrapeRequest(scrapeId, match { it.contains("Chunk") }) }
      // Context should have been cleaned up
      verify { contextManager.removeChunkedContext(scrapeId) }
    }

    "writeChunkedResponsesToProxy should notify handler on summary validation failure" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId = 501L

      val data = "test chunk data".toByteArray()
      val crc = CRC32()
      crc.update(data)
      val correctChecksum = crc.value

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }

      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = correctChecksum
          chunkBytes = ByteString.copyFrom(data)
        }
      }

      // Summary with wrong checksum to trigger ChunkValidationException
      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = 99999L // Wrong checksum
        }
      }

      // Use a real ChunkedContext that has had the chunk applied, so applySummary() validates
      val realContext = ChunkedContext(header, 1000000).apply {
        applyChunk(data, data.size, 1, correctChecksum)
      }
      every { contextManager.getChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000)
      every { contextManager.removeChunkedContext(scrapeId) } returns realContext

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk, summary))

      result shouldBe EMPTY_INSTANCE

      // The waiting HTTP handler should have been notified via failScrapeRequest
      verify { scrapeRequestManager.failScrapeRequest(scrapeId, match { it.contains("Summary") }) }
    }

    "writeResponsesToProxy should call assignScrapeResults for each response" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager
      val service = ProxyServiceImpl(proxy)

      val response1 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 501L
        validResponse = true
        statusCode = 200
      }
      val response2 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 502L
        validResponse = true
        statusCode = 200
      }

      service.writeResponsesToProxy(flowOf(response1, response2))

      verify(exactly = 2) { scrapeRequestManager.assignScrapeResults(any()) }
    }
  }
}
