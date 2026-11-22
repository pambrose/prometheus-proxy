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

package io.prometheus.proxy

import com.google.protobuf.ByteString
import io.grpc.Context
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.CountingProxyMetrics
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.testConfigVals
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.chunkData
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import io.prometheus.grpc.heartBeatRequest
import io.prometheus.grpc.registerPathRequest
import io.prometheus.grpc.scrapeResponse
import io.prometheus.grpc.summaryData
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import java.util.zip.CRC32

// What each RPC counts in the proxy's metrics: a connect only once it's accepted, a refusal by why, a heartbeat only
// once it's accepted, every path rejection by cause, a call naming another agent by its RPC, a result that came too
// late, a chunked transfer whose scrape ended first, and how long a scrape waited for its agent's stream. Each test
// reads real collectors (CountingProxyMetrics), so it checks the count and its label, not only that a counter was read.
class ProxyServiceImplMetricsTest : StringSpec() {
  private fun mockProxy(
    counts: CountingProxyMetrics,
    transportFilterDisabled: Boolean = false,
    agentContextManager: AgentContextManager = AgentContextManager(isTestMode = true),
    scrapeRequestManager: ScrapeRequestManager = ScrapeRequestManager(),
  ): Proxy {
    val options = mockk<ProxyOptions>(relaxed = true)
    every { options.transportFilterDisabled } returns transportFilterDisabled
    val configVals =
      testConfigVals(
        """
        proxy { auth = [] }
        agent {
          pathConfigs = []
          filters = []
        }
        """,
      )
    val proxy = mockk<Proxy>(relaxed = true)
    every { proxy.options } returns options
    every { proxy.proxyConfigVals } returns configVals.proxy
    every { proxy.agentContextManager } returns agentContextManager
    every { proxy.scrapeRequestManager } returns scrapeRequestManager
    every { proxy.pathManager } returns ProxyPathManager(proxy, isTestMode = true)
    every { proxy.isRunning } returns true
    every { proxy.isStartingOrRunning } returns true
    every { proxy.maxAgentsPerIdentity } returns 0
    return counts.installOn(proxy)
  }

  // Runs [block] as a call on the connection the transport filter assigned [connectionAgentId] to.
  private suspend fun <T> onConnection(
    connectionAgentId: String,
    block: suspend () -> T,
  ): T {
    val context = Context.current().withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, connectionAgentId)
    val previous = context.attach()
    return try {
      block()
    } finally {
      context.detach(previous)
    }
  }

  private fun resultFor(
    agentId: String,
    scrapeId: Long,
  ) = scrapeResponse {
    this.agentId = agentId
    this.scrapeId = scrapeId
    validResponse = true
    statusCode = 200
    contentAsText = "metric 1"
  }

  private fun headerFor(
    agentId: String,
    scrapeId: Long,
  ) = chunkedScrapeResponse {
    header = headerData {
      headerValidResponse = true
      headerScrapeId = scrapeId
      headerAgentId = agentId
      headerStatusCode = 200
      headerContentType = "text/plain"
    }
  }

  private val payload = "metric 1".toByteArray()

  private fun chunkFor(scrapeId: Long) =
    chunkedScrapeResponse {
      chunk = chunkData {
        chunkScrapeId = scrapeId
        chunkCount = 1
        chunkByteCount = payload.size
        chunkChecksum = CRC32().apply { update(payload) }.value
        chunkBytes = ByteString.copyFrom(payload)
      }
    }

  private fun summaryFor(scrapeId: Long) =
    chunkedScrapeResponse {
      summary = summaryData {
        summaryScrapeId = scrapeId
        summaryChunkCount = 1
        summaryByteCount = payload.size
        summaryChecksum = CRC32().apply { update(payload) }.value
      }
    }

  // An agent in [contextManager], and a scrape of it the proxy tracks in [scrapeRequestManager].
  private fun trackedScrape(
    contextManager: AgentContextManager,
    scrapeRequestManager: ScrapeRequestManager,
    dashboardScrape: Boolean = false,
  ): ScrapeRequestWrapper {
    val agent = AgentContext("agent-host").also { contextManager.addAgentContext(it) }
    return ScrapeRequestWrapper(agent, "metrics", "", "", null, false, dashboardScrapeVal = dashboardScrape)
      .also { scrapeRequestManager.tryAddToScrapeRequestMap(it, Int.MAX_VALUE) }
  }

  init {
    // ==================== connects ====================

    "an accepted connect should count as a connect, and no refusal" {
      val counts = CountingProxyMetrics()

      ProxyServiceImpl(mockProxy(counts)).connectAgent(EMPTY_INSTANCE) shouldBe EMPTY_INSTANCE

      counts.connects.get() shouldBe 1.0
      counts.connectRefusals.collect().dataPoints.size shouldBe 0
    }

    "a connect with a mismatched transportFilterDisabled should count a refusal, and no connect" {
      val counts = CountingProxyMetrics()

      shouldThrow<StatusException> {
        ProxyServiceImpl(mockProxy(counts, transportFilterDisabled = true)).connectAgent(EMPTY_INSTANCE)
      }
      shouldThrow<StatusException> {
        ProxyServiceImpl(mockProxy(counts)).connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)
      }

      counts.connects.get() shouldBe 0.0
      counts.connectRefusals.labelValues(ProxyMetrics.REFUSAL_TRANSPORT_FILTER_MISMATCH).get() shouldBe 2.0
    }

    // proxy_connect_count_total went up before the maxAgentsPerIdentity check, so a refused connect counted as one.
    "a connect over maxAgentsPerIdentity should count an identity_limit refusal, and no connect" {
      val counts = CountingProxyMetrics()
      val contextManager = mockk<AgentContextManager>(relaxed = true)
      every { contextManager.addAgentContextWithinLimit(any(), any()) } returns false
      val proxy = mockProxy(counts, transportFilterDisabled = true, agentContextManager = contextManager)

      shouldThrow<StatusException> { ProxyServiceImpl(proxy).connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE) }

      counts.connects.get() shouldBe 0.0
      counts.connectRefusals.labelValues(ProxyMetrics.REFUSAL_IDENTITY_LIMIT).get() shouldBe 1.0
    }

    "an accepted connect with the transport filter disabled should count as a connect" {
      val counts = CountingProxyMetrics()
      val proxy = mockProxy(counts, transportFilterDisabled = true)

      ProxyServiceImpl(proxy).connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)

      counts.connects.get() shouldBe 1.0
    }

    // ==================== heartbeats ====================

    // proxy_heartbeat_count_total counted every heartbeat, an evicted agent's refused ones included.
    "a heartbeat should count only when the proxy accepts it" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val agent = AgentContext("agent-host").also { contextManager.addAgentContext(it) }
      val service = ProxyServiceImpl(mockProxy(counts, agentContextManager = contextManager))

      service.sendHeartBeat(heartBeatRequest { agentId = agent.agentId }).valid shouldBe true
      service.sendHeartBeat(heartBeatRequest { agentId = "evicted-agent" }).valid shouldBe false

      counts.heartbeats.get() shouldBe 1.0
    }

    // ==================== path rejections and binding mismatches ====================

    // The activity feed notes a path's first rejection only; the counter counts every one.
    "registerPath should count every rejection by its cause" {
      val counts = CountingProxyMetrics()
      val service = ProxyServiceImpl(mockProxy(counts))

      repeat(2) {
        service.registerPath(
          registerPathRequest {
          agentId = "unknown-agent"
          path = "metrics"
        },
        ).valid shouldBe false
      }

      counts.pathRejections.labelValues("invalid_agent").get() shouldBe 2.0
    }

    "registerPath should not count an accepted path" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val agent = AgentContext("agent-host").also { contextManager.addAgentContext(it) }
      val service = ProxyServiceImpl(mockProxy(counts, agentContextManager = contextManager))

      service.registerPath(
        registerPathRequest {
          agentId = agent.agentId
          path = "metrics"
        },
      ).valid shouldBe true

      counts.pathRejections.collect().dataPoints.size shouldBe 0
    }

    "a call naming another connection's agentId should count a binding mismatch for its RPC" {
      val counts = CountingProxyMetrics()
      val service = ProxyServiceImpl(mockProxy(counts))

      onConnection("connection-agent") {
        service.registerPath(
          registerPathRequest {
          agentId = "other-agent"
          path = "metrics"
        },
        )
        service.sendHeartBeat(heartBeatRequest { agentId = "other-agent" })
      }

      counts.bindingMismatches.labelValues("registerPath").get() shouldBe 1.0
      counts.bindingMismatches.labelValues("sendHeartBeat").get() shouldBe 1.0
      counts.pathRejections.labelValues("binding_mismatch").get() shouldBe 1.0
    }

    "a result for a scrape sent to another agent should count a binding mismatch" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val scrapeRequestManager = ScrapeRequestManager()
      val scrape = trackedScrape(contextManager, scrapeRequestManager)
      val service =
        ProxyServiceImpl(
          mockProxy(counts, agentContextManager = contextManager, scrapeRequestManager = scrapeRequestManager),
        )

      onConnection("intruder-agent") {
        service.writeResponsesToProxy(flowOf(resultFor("intruder-agent", scrape.scrapeId)))
      }

      counts.bindingMismatches.labelValues("writeResponsesToProxy").get() shouldBe 1.0
      counts.lateResults.get() shouldBe 0.0
    }

    // ==================== late results and abandoned transfers ====================

    "a result for a scrape no longer tracked should count as late, and a tracked one should not" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val scrapeRequestManager = ScrapeRequestManager()
      val scrape = trackedScrape(contextManager, scrapeRequestManager)
      val agentId = scrape.agentContext.agentId
      val service =
        ProxyServiceImpl(
          mockProxy(counts, agentContextManager = contextManager, scrapeRequestManager = scrapeRequestManager),
        )

      service.writeResponsesToProxy(
        flowOf(resultFor(agentId, scrape.scrapeId + 1), resultFor(agentId, scrape.scrapeId)),
      )

      counts.lateResults.get() shouldBe 1.0
      scrape.scrapeResults?.srScrapeId shouldBe scrape.scrapeId
    }

    "a chunked transfer for a scrape no longer tracked should count as one late result, and no abandoned transfer" {
      val counts = CountingProxyMetrics()
      val service = ProxyServiceImpl(mockProxy(counts))

      service.writeChunkedResponsesToProxy(
        flowOf(headerFor("agent", 42L), chunkFor(42L), summaryFor(42L)),
      )

      counts.lateResults.get() shouldBe 1.0
      counts.abandonedTransfers.get() shouldBe 0.0
    }

    // The chunk that found its scrape ended dropped the transfer at DEBUG, uncounted, though the handler's removal and
    // the stream's sweep each counted the same event as abandoned.
    "a chunked transfer whose scrape ends mid-transfer should count as abandoned, once" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val scrapeRequestManager = ScrapeRequestManager()
      val scrape = trackedScrape(contextManager, scrapeRequestManager)
      val agentId = scrape.agentContext.agentId
      val service =
        ProxyServiceImpl(
          mockProxy(counts, agentContextManager = contextManager, scrapeRequestManager = scrapeRequestManager),
        )

      service.writeChunkedResponsesToProxy(
        flow<ChunkedScrapeResponse> {
          emit(headerFor(agentId, scrape.scrapeId))
          // The scrape's handler returns, at a timeout, before the transfer's chunk arrives.
          scrapeRequestManager.removeFromScrapeRequestMap(scrape.scrapeId)
          emit(chunkFor(scrape.scrapeId))
          emit(summaryFor(scrape.scrapeId))
        },
      )

      counts.abandonedTransfers.get() shouldBe 1.0
      counts.lateResults.get() shouldBe 0.0
    }

    "a chunked transfer whose scrape ends just before its summary should count as abandoned" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val scrapeRequestManager = ScrapeRequestManager()
      val scrape = trackedScrape(contextManager, scrapeRequestManager)
      val agentId = scrape.agentContext.agentId
      val service =
        ProxyServiceImpl(
          mockProxy(counts, agentContextManager = contextManager, scrapeRequestManager = scrapeRequestManager),
        )

      service.writeChunkedResponsesToProxy(
        flow<ChunkedScrapeResponse> {
          emit(headerFor(agentId, scrape.scrapeId))
          emit(chunkFor(scrape.scrapeId))
          scrapeRequestManager.removeFromScrapeRequestMap(scrape.scrapeId)
          emit(summaryFor(scrape.scrapeId))
        },
      )

      counts.abandonedTransfers.get() shouldBe 1.0
    }

    "a whole chunked transfer for a tracked scrape should count as neither late nor abandoned" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val scrapeRequestManager = ScrapeRequestManager()
      val scrape = trackedScrape(contextManager, scrapeRequestManager)
      val agentId = scrape.agentContext.agentId
      val service =
        ProxyServiceImpl(
          mockProxy(counts, agentContextManager = contextManager, scrapeRequestManager = scrapeRequestManager),
        )

      service.writeChunkedResponsesToProxy(
        flowOf(headerFor(agentId, scrape.scrapeId), chunkFor(scrape.scrapeId), summaryFor(scrape.scrapeId)),
      )

      counts.abandonedTransfers.get() shouldBe 0.0
      counts.lateResults.get() shouldBe 0.0
      scrape.scrapeResults?.srScrapeId shouldBe scrape.scrapeId
    }

    // ==================== queue wait ====================

    "readRequestsFromProxy should record how long Prometheus's scrapes waited, and not a dashboard's" {
      val counts = CountingProxyMetrics()
      val contextManager = AgentContextManager(isTestMode = true)
      val scrapeRequestManager = ScrapeRequestManager()
      val prometheusScrape = trackedScrape(contextManager, scrapeRequestManager)
      val dashboardScrape = trackedScrape(contextManager, scrapeRequestManager, dashboardScrape = true)
      val agent = mockk<AgentContext>(relaxed = true)
      every { agent.agentId } returns "streaming-agent"
      every { agent.isValid() } returnsMany listOf(true, true, false)
      coEvery { agent.readScrapeRequest() } returns prometheusScrape andThen dashboardScrape
      val streamingContexts = mockk<AgentContextManager>(relaxed = true)
      every { streamingContexts.getAgentContext("streaming-agent") } returns agent
      val proxy =
        mockProxy(counts, agentContextManager = streamingContexts, scrapeRequestManager = scrapeRequestManager)

      val emitted = ProxyServiceImpl(proxy).readRequestsFromProxy(agentInfo { agentId = "streaming-agent" }).toList()

      emitted.size shouldBe 2
      counts.queueWaitCount shouldBe 1L
    }
  }
}
