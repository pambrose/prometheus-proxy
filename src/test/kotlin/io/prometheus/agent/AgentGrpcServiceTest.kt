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

import com.github.pambrose.common.util.zip
import io.grpc.Metadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
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
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatResponse
import io.prometheus.grpc.registerAgentResponse
import io.prometheus.grpc.scrapeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

@Suppress("LargeClass")
class AgentGrpcServiceTest : StringSpec() {
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
    every { mockOptions.unaryDeadlineSecs } returns 30
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

  private suspend fun callProcessScrapeResults(
    service: AgentGrpcService,
    agent: Agent,
    connectionContext: AgentConnectionContext,
    nonChunkedChannel: Channel<ScrapeResponse>,
    chunkedChannel: Channel<ChunkedScrapeResponse>,
  ) {
    val method = AgentGrpcService::class.declaredMemberFunctions.first { it.name == "processScrapeResults" }
    method.isAccessible = true
    method.callSuspend(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)
  }

  init {
    "parseProxyHostname should extract hostname and port correctly" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "localhost"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should use default port when not specified" {
      val agent = createMockAgent("example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should strip http prefix" {
      val agent = createMockAgent("http://example.com:8080")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 8080

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should strip https prefix" {
      val agent = createMockAgent("https://example.com:8443")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 8443

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should handle custom port" {
      val agent = createMockAgent("proxy.example.org:9090")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "proxy.example.org"
      service.agentPort shouldBe 9090

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should handle IPv4 address" {
      val agent = createMockAgent("192.168.1.100:5000")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "192.168.1.100"
      service.agentPort shouldBe 5000

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should handle hostname without port and default to 50051" {
      val agent = createMockAgent("my-proxy-server")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "my-proxy-server"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should strip http prefix and use custom port" {
      val agent = createMockAgent("http://localhost:9999")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "localhost"
      service.agentPort shouldBe 9999

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should strip https prefix and use custom port" {
      val agent = createMockAgent("https://secure.proxy.com:443")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "secure.proxy.com"
      service.agentPort shouldBe 443

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should handle http prefix without port" {
      val agent = createMockAgent("http://example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 50051

      // Clean up
      service.shutDown()
    }

    "parseProxyHostname should handle https prefix without port" {
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
    "backlog counter should never go negative with consumer-side increment pattern" {
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

    "backlog counter should remain non-negative throughout processing" {
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

    "shutDown should be safe to call when service has started" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // shutDown should not throw
      service.shutDown()
    }

    "shutDown should be safe to call multiple times" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Calling shutDown multiple times should not throw
      service.shutDown()
      service.shutDown()
    }

    // ==================== gRPC Stub Tests ====================

    "grpcStub should be initialized after construction" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // grpcStub should be accessible (no UninitializedPropertyAccessException)
      service.grpcStub.toString().shouldNotBeEmpty()

      service.shutDown()
    }

    // ==================== Unary Deadline Tests ====================

    "unaryDeadlineSecs should default to 30" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.unaryDeadlineSecs shouldBe 30L

      service.shutDown()
    }

    // ==================== connectAgent Tests ====================

    "connectAgent should return true on successful connection" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.connectAgent(any(), any<Metadata>()) } returns EMPTY_INSTANCE
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      val result = service.connectAgent(transportFilterDisabled = false)

      result.shouldBeTrue()
      service.shutDown()
    }

    "connectAgent should return false on connection failure" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws RuntimeException("Connection refused")
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      val result = service.connectAgent(transportFilterDisabled = false)

      result.shouldBeFalse()
      service.shutDown()
    }

    "connectAgent with transportFilterDisabled should assign agentId" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.connectAgentWithTransportFilterDisabled(any(), any<Metadata>()) } returns agentInfo {
        agentId = "assigned-agent-id"
      }
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      val result = service.connectAgent(transportFilterDisabled = true)

      result.shouldBeTrue()
      verify { agent.agentId = "assigned-agent-id" }
      service.shutDown()
    }

    // ==================== registerAgent Tests ====================

    "registerAgent should send request with correct agent details" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse { valid = true }
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

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

    "registerAgent should throw RequestFailureException on invalid response" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse {
        valid = false
        reason = "Agent already registered"
      }
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      val latch = CountDownLatch(1)
      shouldThrow<RequestFailureException> {
        service.registerAgent(latch)
      }
      service.shutDown()
    }

    // ==================== Channel Termination Tests (M9) ====================

    "shutDown should fully terminate the channel" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Before shutdown, channel should not be terminated
      service.channel.isTerminated.shouldBeFalse()

      service.shutDown()

      // After shutdown with awaitTermination, channel should be fully terminated
      service.channel.isTerminated.shouldBeTrue()
    }

    "resetGrpcStubs should terminate old channel before creating new one" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val oldChannel = service.channel
      oldChannel.isTerminated.shouldBeFalse()

      // resetGrpcStubs should shut down the old channel and create a new one
      service.resetGrpcStubs()

      // Old channel should be fully terminated
      oldChannel.isTerminated.shouldBeTrue()

      // New channel should be a different instance and not terminated
      val newChannel = service.channel
      (newChannel !== oldChannel).shouldBeTrue()
      newChannel.isTerminated.shouldBeFalse()

      service.shutDown()
    }

    // ==================== Concurrent shutDown / resetGrpcStubs Tests ====================

    "concurrent shutDown and resetGrpcStubs should not deadlock" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Run resetGrpcStubs and shutDown concurrently on separate threads.
      // Before the fix, resetGrpcStubs held grpcLock and called shutDown(),
      // which also acquired grpcLock. With ReentrantLock the self-call was fine,
      // but a concurrent external shutDown() call would block for the entire
      // duration of channel creation + awaitTermination (up to 5s).
      // After the fix, resetGrpcStubs calls shutDownLocked() directly
      // (no nested lock acquisition), so the lock is held for the minimum
      // necessary time and concurrent callers are not blocked excessively.

      val completedWithinTimeout = AtomicBoolean(false)
      val iterations = 10
      val latch = CountDownLatch(2)

      val thread1 = Thread {
        repeat(iterations) {
          service.resetGrpcStubs()
        }
        latch.countDown()
      }

      val thread2 = Thread {
        repeat(iterations) {
          service.shutDown()
        }
        latch.countDown()
      }

      thread1.start()
      thread2.start()

      // Both threads should complete well within 10 seconds.
      // Before the fix, each resetGrpcStubs iteration could block shutDown
      // for up to 5s (awaitTermination) with the redundant nested lock.
      completedWithinTimeout.set(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))

      completedWithinTimeout.get().shouldBeTrue()

      // Service should still be in a usable state -- either shut down or with a valid channel
      // Final cleanup
      service.shutDown()
    }

    "resetGrpcStubs called multiple times should not deadlock" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Before the fix, each resetGrpcStubs call internally called shutDown(),
      // which redundantly re-acquired grpcLock. While ReentrantLock allowed this,
      // it was unnecessary overhead. After the fix, shutDownLocked() is called
      // directly without the extra lock acquisition.

      val completedWithinTimeout = AtomicBoolean(false)
      val iterations = 20
      val latch = CountDownLatch(1)

      val thread = Thread {
        repeat(iterations) {
          service.resetGrpcStubs()
        }
        latch.countDown()
      }

      thread.start()
      completedWithinTimeout.set(latch.await(10, java.util.concurrent.TimeUnit.SECONDS))

      completedWithinTimeout.get().shouldBeTrue()
      service.channel.isTerminated.shouldBeFalse()

      service.shutDown()
    }

    "concurrent resetGrpcStubs from multiple threads should not deadlock" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val threadCount = 4
      val iterationsPerThread = 5
      val latch = CountDownLatch(threadCount)

      val threads = (1..threadCount).map {
        Thread {
          repeat(iterationsPerThread) {
            service.resetGrpcStubs()
          }
          latch.countDown()
        }
      }

      threads.forEach { it.start() }

      val completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
      completed.shouldBeTrue()

      // After all concurrent resets, channel should still be usable
      service.channel.isTerminated.shouldBeFalse()
      service.grpcStub.toString().shouldNotBeEmpty()

      service.shutDown()
    }

    // ==================== sendHeartBeat Tests ====================

    "sendHeartBeat should skip when agentId is empty" {
      val agent = createMockAgent("localhost:50051")
      every { agent.agentId } returns ""
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      service.sendHeartBeat()

      coVerify(exactly = 0) { mockStub.sendHeartBeat(any(), any<Metadata>()) }
      service.shutDown()
    }

    "sendHeartBeat should send request when agentId is set" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } returns heartBeatResponse { valid = true }
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      service.sendHeartBeat()

      coVerify { mockStub.sendHeartBeat(match { it.agentId == "test-agent-123" }, any<Metadata>()) }
      service.shutDown()
    }

    // ==================== readRequestsFromProxy Tests ====================

    "readRequestsFromProxy should forward scrape requests to connectionContext" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      val request1 = scrapeRequest {
        agentId = "test-agent-123"
        scrapeId = 1L
        path = "/metrics"
      }
      val request2 = scrapeRequest {
        agentId = "test-agent-123"
        scrapeId = 2L
        path = "/health"
      }
      // readRequestsFromProxy is not a suspend function, so use every (not coEvery)
      every { mockStub.readRequestsFromProxy(any(), any()) } returns flowOf(request1, request2)
      service.grpcStub = mockStub

      val connectionContext = AgentConnectionContext()
      val mockHttpService = mockk<AgentHttpService>(relaxed = true)

      service.readRequestsFromProxy(mockHttpService, connectionContext)

      // After readRequestsFromProxy completes, items are buffered in the UNLIMITED channel.
      // Use tryReceive to read them without cancelling the channel.
      val channel = connectionContext.scrapeRequestActions()
      channel.tryReceive().isSuccess.shouldBeTrue()
      channel.tryReceive().isSuccess.shouldBeTrue()
      channel.tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    "readRequestsFromProxy should handle empty flow from proxy" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      every { mockStub.readRequestsFromProxy(any(), any()) } returns flowOf()
      service.grpcStub = mockStub

      val connectionContext = AgentConnectionContext()
      val mockHttpService = mockk<AgentHttpService>(relaxed = true)

      // Should complete without error
      service.readRequestsFromProxy(mockHttpService, connectionContext)

      // No items should be in the channel
      connectionContext.scrapeRequestActions().tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    // ==================== processScrapeResults Tests ====================
    // processScrapeResults is private, so we test it via Kotlin reflection.
    // It routes ScrapeResults to either nonChunkedChannel or chunkedChannel based on
    // whether the content is zipped and whether the zipped size exceeds chunkContentSizeBytes.

    "processScrapeResults should route non-zipped result to nonChunkedChannel" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val connectionContext = AgentConnectionContext()
      val nonChunkedChannel = Channel<ScrapeResponse>(UNLIMITED)
      val chunkedChannel = Channel<ChunkedScrapeResponse>(UNLIMITED)

      connectionContext.sendScrapeResults(
        ScrapeResults(
          srAgentId = "test-agent-123",
          srScrapeId = 42L,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain",
          srZipped = false,
          srContentAsText = "metric_name 1.0",
        ),
      )
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      val result = nonChunkedChannel.tryReceive()
      result.isSuccess.shouldBeTrue()
      result.getOrThrow().scrapeId shouldBe 42L
      result.getOrThrow().contentAsText shouldBe "metric_name 1.0"
      result.getOrThrow().zipped shouldBe false

      // Chunked channel should be empty
      chunkedChannel.tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    "processScrapeResults should route zipped small result to nonChunkedChannel" {
      val agent = createMockAgent("localhost:50051")
      // chunkContentSizeBytes defaults to 32768 in mock
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val connectionContext = AgentConnectionContext()
      val nonChunkedChannel = Channel<ScrapeResponse>(UNLIMITED)
      val chunkedChannel = Channel<ChunkedScrapeResponse>(UNLIMITED)

      val smallContent = "small metric data"
      val zippedContent = smallContent.zip()

      connectionContext.sendScrapeResults(
        ScrapeResults(
          srAgentId = "test-agent-123",
          srScrapeId = 99L,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain",
          srZipped = true,
          srContentAsZipped = zippedContent,
        ),
      )
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      val result = nonChunkedChannel.tryReceive()
      result.isSuccess.shouldBeTrue()
      result.getOrThrow().scrapeId shouldBe 99L
      result.getOrThrow().zipped shouldBe true

      // Chunked channel should be empty
      chunkedChannel.tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    "processScrapeResults should route zipped large result to chunkedChannel with CRC32" {
      val agent = createMockAgent("localhost:50051")
      // Set a very small chunk size so our test data gets chunked
      // Access the mock options directly to avoid MockK chain-mock issues
      val mockOptions = agent.options
      every { mockOptions.chunkContentSizeBytes } returns 10
      val service = AgentGrpcService(agent, mockOptions, "test-server")

      val connectionContext = AgentConnectionContext()
      val nonChunkedChannel = Channel<ScrapeResponse>(UNLIMITED)
      val chunkedChannel = Channel<ChunkedScrapeResponse>(UNLIMITED)

      val largeContent = "a]".repeat(500)
      val zippedContent = largeContent.zip()
      (zippedContent.size >= 10).shouldBeTrue()

      connectionContext.sendScrapeResults(
        ScrapeResults(
          srAgentId = "test-agent-123",
          srScrapeId = 200L,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain",
          srZipped = true,
          srContentAsZipped = zippedContent,
        ),
      )
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      // Non-chunked channel should be empty
      nonChunkedChannel.tryReceive().isSuccess.shouldBeFalse()

      // Drain chunked channel
      val capturedChunked = mutableListOf<ChunkedScrapeResponse>()
      while (true) {
        val item = chunkedChannel.tryReceive()
        if (item.isSuccess) capturedChunked.add(item.getOrThrow()) else break
      }

      // Should have: 1 header + N chunks + 1 summary
      (capturedChunked.size >= 3).shouldBeTrue()

      // First item should be a header
      capturedChunked[0].hasHeader().shouldBeTrue()
      capturedChunked[0].header.headerScrapeId shouldBe 200L
      capturedChunked[0].header.headerStatusCode shouldBe 200

      // Last item should be a summary
      val summary = capturedChunked.last()
      summary.hasSummary().shouldBeTrue()
      summary.summary.summaryScrapeId shouldBe 200L
      summary.summary.summaryByteCount shouldBe zippedContent.size

      // Middle items should be chunks
      val chunks = capturedChunked.drop(1).dropLast(1)
      chunks.forEach { it.hasChunk().shouldBeTrue() }

      // Verify total bytes from chunks equals zipped content size
      val totalBytes = chunks.sumOf { it.chunk.chunkByteCount }
      totalBytes shouldBe zippedContent.size

      // Verify CRC32 checksum in summary matches computed checksum
      val crc = java.util.zip.CRC32()
      chunks.forEach { chunk ->
        crc.update(chunk.chunk.chunkBytes.toByteArray())
      }
      summary.summary.summaryChecksum shouldBe crc.value

      service.shutDown()
    }

    "processScrapeResults should handle multiple results in sequence" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val connectionContext = AgentConnectionContext()
      val nonChunkedChannel = Channel<ScrapeResponse>(UNLIMITED)
      val chunkedChannel = Channel<ChunkedScrapeResponse>(UNLIMITED)

      repeat(3) { i ->
        connectionContext.sendScrapeResults(
          ScrapeResults(
            srAgentId = "test-agent-123",
            srScrapeId = i.toLong(),
            srValidResponse = true,
            srStatusCode = 200,
            srContentType = "text/plain",
            srZipped = false,
            srContentAsText = "metric_$i 1.0",
          ),
        )
      }
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      val scrapeIds = mutableSetOf<Long>()
      repeat(3) {
        val item = nonChunkedChannel.tryReceive()
        item.isSuccess.shouldBeTrue()
        scrapeIds.add(item.getOrThrow().scrapeId)
      }
      scrapeIds shouldBe setOf(0L, 1L, 2L)

      // No more items
      nonChunkedChannel.tryReceive().isSuccess.shouldBeFalse()
      chunkedChannel.tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    // ==================== sendHeartBeat Error Handling Tests ====================

    "sendHeartBeat should handle NOT_FOUND status from proxy" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } returns heartBeatResponse {
        valid = false
        reason = "Agent not found"
      }
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      // Should not throw - error is caught internally and logged
      service.sendHeartBeat()

      service.shutDown()
    }

    "sendHeartBeat should handle generic exception without throwing" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } throws RuntimeException("Network error")
      service.grpcStub = mockStub
      service.unaryDeadlineSecs = 0

      // Should not throw - error is caught internally and logged
      service.sendHeartBeat()

      service.shutDown()
    }

    "registerAgent should throw on empty agentId" {
      val agent = createMockAgent("localhost:50051")
      every { agent.agentId } returns ""
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val latch = CountDownLatch(1)
      shouldThrow<IllegalArgumentException> {
        service.registerAgent(latch)
      }
      service.shutDown()
    }

    // ==================== Bug #6: grpcStarted should only be true after channel is initialized ====================

    // Bug #6: Before the fix, resetGrpcStubs() set grpcStarted=true (via an else branch)
    // BEFORE the channel was assigned. If channel() threw on the first call,
    // grpcStarted was true but channel was an uninitialized lateinit var.
    // The next call to resetGrpcStubs() would enter shutDownLocked() (because
    // grpcStarted was true) and crash with UninitializedPropertyAccessException
    // when accessing channel.shutdownNow().
    //
    // The fix moves grpcStarted=true to AFTER channel is successfully assigned.

    "Bug #6: after construction channel and grpcStub should be initialized" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // channel is accessible (no UninitializedPropertyAccessException)
      service.channel.isTerminated.shouldBeFalse()

      // grpcStub is accessible
      service.grpcStub.toString().shouldNotBeEmpty()

      // shutDown accesses channel via shutDownLocked — should succeed
      service.shutDown()
      service.channel.isTerminated.shouldBeTrue()
    }

    "Bug #6: simulated stale grpcStarted=true with null channel should crash" {
      // Demonstrates the old bug: grpcStarted=true but channel not initialized.
      // If the `else grpcStarted = true` line had run but channel() threw,
      // calling shutDown() would access the uninitialized lateinit channel.
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Use reflection to null-out the channel, simulating an uninitialized lateinit
      val channelField = AgentGrpcService::class.java.getDeclaredField("channel")
      channelField.isAccessible = true
      channelField.set(service, null) // makes lateinit "uninitialized"

      // grpcStarted is true (from the successful init), so shutDownLocked()
      // will try to access channel.shutdownNow() → UninitializedPropertyAccessException
      shouldThrow<UninitializedPropertyAccessException> {
        service.shutDown()
      }
    }

    "Bug #6: resetGrpcStubs should recover after shutDown" {
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Shut down the service
      service.shutDown()
      service.channel.isTerminated.shouldBeTrue()

      // resetGrpcStubs should create a new channel and restore the service
      service.resetGrpcStubs()

      service.channel.isTerminated.shouldBeFalse()
      service.grpcStub.toString().shouldNotBeEmpty()

      service.shutDown()
    }

    "Bug #6: grpcStarted should be false when channel is uninitialized" {
      // Verify the invariant: grpcStarted=true implies channel is initialized.
      // We use reflection to read the grpcStarted delegate and verify
      // it was set correctly after construction.
      val agent = createMockAgent("localhost:50051")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Read grpcStarted via the delegate field
      val delegateField = service.javaClass.getDeclaredField("grpcStarted\$delegate")
      delegateField.isAccessible = true
      val delegate = delegateField.get(service)

      // The delegate wraps an AtomicBoolean in a field named "atomicVal"
      val atomicValField = delegate.javaClass.getDeclaredField("atomicVal")
      atomicValField.isAccessible = true
      val atomicBool = atomicValField.get(delegate) as java.util.concurrent.atomic.AtomicBoolean

      // After successful construction, grpcStarted should be true
      atomicBool.get().shouldBeTrue()

      // And channel should be initialized
      service.channel.isTerminated.shouldBeFalse()

      service.shutDown()
    }
  }
}
