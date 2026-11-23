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

import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.CountingProxyMetrics
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.testConfigVals
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatRequest
import io.prometheus.grpc.registerPathRequest
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

// What each RPC counts in the proxy's metrics: a connect only once it's accepted, a refusal by why, a heartbeat only
// once it's accepted, every path rejection by cause, a call naming another agent by its RPC, a result that came too
// late, a chunked transfer whose scrape ended first, and how long a scrape waited for its agent's stream. Each test
// reads real collectors (CountingProxyMetrics), so it checks the count and its label, not only that a counter was read.
class ProxyServiceImplMetricsTest : StringSpec() {
  // A mocked proxy with real context, path, and scrape managers, the counts its metrics { } blocks make, and the
  // service a test calls.
  private class Fixture(
    transportFilterDisabled: Boolean = false,
    val contextManager: AgentContextManager = AgentContextManager(isTestMode = true),
  ) {
    val counts = CountingProxyMetrics()
    val scrapeRequestManager = ScrapeRequestManager()
    val proxy: Proxy =
      counts.installOn(mockk(relaxed = true)).also { proxy ->
        val options = mockk<ProxyOptions>(relaxed = true)
        every { options.transportFilterDisabled } returns transportFilterDisabled
        every { proxy.options } returns options
        every { proxy.proxyConfigVals } returns CONFIG_VALS.proxy
        every { proxy.agentContextManager } returns contextManager
        every { proxy.scrapeRequestManager } returns scrapeRequestManager
        every { proxy.pathManager } returns ProxyPathManager(proxy, isTestMode = true)
        every { proxy.isRunning } returns true
        every { proxy.isStartingOrRunning } returns true
      }
    val service = ProxyServiceImpl(proxy)

    // An agent the proxy has, connected.
    fun connectedAgent() = AgentContext("agent-host").also { contextManager.addAgentContext(it) }

    // A scrape of a newly connected agent, which the proxy tracks.
    fun trackScrape(dashboardScrape: Boolean = false): ScrapeRequestWrapper =
      ScrapeRequestWrapper(connectedAgent(), "metrics", "", "", null, false, dashboardScrapeVal = dashboardScrape)
        .also { scrapeRequestManager.tryAddToScrapeRequestMap(it, Int.MAX_VALUE) }
  }

  init {
    // ==================== connects ====================

    "an accepted connect should count as a connect, and no refusal" {
      val fixture = Fixture()

      fixture.service.connectAgent(EMPTY_INSTANCE) shouldBe EMPTY_INSTANCE

      fixture.counts.connects.get() shouldBe 1.0
      fixture.counts.connectRefusals.collect().dataPoints.size shouldBe 0
    }

    "a connect with a mismatched transportFilterDisabled should count a refusal, and no connect" {
      val filterOff = Fixture(transportFilterDisabled = true)
      val filterOn = Fixture()

      shouldThrow<StatusException> { filterOff.service.connectAgent(EMPTY_INSTANCE) }
      shouldThrow<StatusException> { filterOn.service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE) }

      listOf(filterOff, filterOn).forEach { fixture ->
        fixture.counts.connects.get() shouldBe 0.0
        fixture.counts.connectRefusals.labelValues(ProxyMetrics.REFUSAL_TRANSPORT_FILTER_MISMATCH).get() shouldBe 1.0
      }
    }

    // proxy_connect_count_total went up before the maxAgentsPerIdentity check, so a refused connect counted as one.
    "a connect over maxAgentsPerIdentity should count an identity_limit refusal, and no connect" {
      val contextManager = mockk<AgentContextManager>(relaxed = true)
      every { contextManager.addAgentContextWithinLimit(any(), any()) } returns false
      val fixture = Fixture(transportFilterDisabled = true, contextManager = contextManager)

      shouldThrow<StatusException> { fixture.service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE) }

      fixture.counts.connects.get() shouldBe 0.0
      fixture.counts.connectRefusals.labelValues(ProxyMetrics.REFUSAL_IDENTITY_LIMIT).get() shouldBe 1.0
    }

    "an accepted connect with the transport filter disabled should count as a connect" {
      val fixture = Fixture(transportFilterDisabled = true)

      fixture.service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)

      fixture.counts.connects.get() shouldBe 1.0
    }

    // ==================== heartbeats ====================

    // proxy_heartbeat_count_total counted every heartbeat, an evicted agent's refused ones included.
    "a heartbeat should count only when the proxy accepts it" {
      val fixture = Fixture()
      val agent = fixture.connectedAgent()

      fixture.service.sendHeartBeat(heartBeatRequest { agentId = agent.agentId }).valid shouldBe true
      fixture.service.sendHeartBeat(heartBeatRequest { agentId = "evicted-agent" }).valid shouldBe false

      fixture.counts.heartbeats.get() shouldBe 1.0
    }

    // ==================== path rejections and binding mismatches ====================

    // The counter counts every rejection, even one like this unknown agent's, which never reaches the activity feed.
    // The feed notes a connected agent's rejection once for each cause.
    "registerPath should count every rejection by its cause" {
      val fixture = Fixture()
      val request =
        registerPathRequest {
          agentId = "unknown-agent"
          path = "metrics"
        }

      repeat(2) { fixture.service.registerPath(request).valid shouldBe false }

      fixture.counts.pathRejections.labelValues("invalid_agent").get() shouldBe 2.0
    }

    "registerPath should not count an accepted path" {
      val fixture = Fixture()
      val request =
        registerPathRequest {
          agentId = fixture.connectedAgent().agentId
          path = "metrics"
        }

      fixture.service.registerPath(request).valid shouldBe true

      fixture.counts.pathRejections.collect().dataPoints.size shouldBe 0
    }

    "a call naming another connection's agentId should count a binding mismatch for its RPC" {
      val fixture = Fixture()
      val request =
        registerPathRequest {
          agentId = "other-agent"
          path = "metrics"
        }

      onConnection("connection-agent") {
        fixture.service.registerPath(request)
        fixture.service.sendHeartBeat(heartBeatRequest { agentId = "other-agent" })
      }

      fixture.counts.bindingMismatches.labelValues("registerPath").get() shouldBe 1.0
      fixture.counts.bindingMismatches.labelValues("sendHeartBeat").get() shouldBe 1.0
      fixture.counts.pathRejections.labelValues("binding_mismatch").get() shouldBe 1.0
    }

    "a result for a scrape sent to another agent should count a binding mismatch" {
      val fixture = Fixture()
      val scrape = fixture.trackScrape()

      onConnection("intruder-agent") {
        fixture.service.writeResponsesToProxy(flowOf(resultFrom("intruder-agent", scrape.scrapeId)))
      }

      fixture.counts.bindingMismatches.labelValues("writeResponsesToProxy").get() shouldBe 1.0
      fixture.counts.lateResults.get() shouldBe 0.0
    }

    // ==================== late results and abandoned transfers ====================

    "a result for a scrape no longer tracked should count as late, and a tracked one should not" {
      val fixture = Fixture()
      val scrape = fixture.trackScrape()
      val agentId = scrape.agentContext.agentId

      fixture.service.writeResponsesToProxy(
        flowOf(resultFrom(agentId, scrape.scrapeId + 1), resultFrom(agentId, scrape.scrapeId)),
      )

      fixture.counts.lateResults.get() shouldBe 1.0
      scrape.scrapeResults?.srScrapeId shouldBe scrape.scrapeId
    }

    "a chunked transfer for a scrape no longer tracked should count as one late result, and no abandoned transfer" {
      val fixture = Fixture()

      fixture.service.writeChunkedResponsesToProxy(transferFrom("agent", 42L).asFlow())

      fixture.counts.lateResults.get() shouldBe 1.0
      fixture.counts.abandonedTransfers.get() shouldBe 0.0
    }

    // The chunk or summary that found its scrape ended dropped the transfer at DEBUG, uncounted, though the handler's
    // removal and the stream's sweep each counted the same event as abandoned.
    "a chunked transfer whose scrape ends before its chunk or its summary should count as abandoned, once" {
      // The message the scrape's handler returns just before, at a timeout: the chunk (1) or the summary (2).
      listOf(1, 2).forEach { endsBefore ->
        val fixture = Fixture()
        val scrape = fixture.trackScrape()
        val messages = transferFrom(scrape.agentContext.agentId, scrape.scrapeId)

        fixture.service.writeChunkedResponsesToProxy(
          flow {
            messages.forEachIndexed { i, message ->
              if (i == endsBefore)
                fixture.scrapeRequestManager.removeFromScrapeRequestMap(scrape.scrapeId)
              emit(message)
            }
          },
        )

        withClue("scrape ended before message $endsBefore") {
          fixture.counts.abandonedTransfers.get() shouldBe 1.0
          fixture.counts.lateResults.get() shouldBe 0.0
        }
      }
    }

    "a whole chunked transfer for a tracked scrape should count as neither late nor abandoned" {
      val fixture = Fixture()
      val scrape = fixture.trackScrape()

      fixture.service.writeChunkedResponsesToProxy(transferFrom(scrape.agentContext.agentId, scrape.scrapeId).asFlow())

      fixture.counts.abandonedTransfers.get() shouldBe 0.0
      fixture.counts.lateResults.get() shouldBe 0.0
      scrape.scrapeResults?.srScrapeId shouldBe scrape.scrapeId
    }

    // ==================== queue wait ====================

    "readRequestsFromProxy should record how long Prometheus's scrapes waited, and not a dashboard's" {
      val streamingAgent = mockk<AgentContext>(relaxed = true)
      val contextManager = mockk<AgentContextManager>(relaxed = true)
      every { contextManager.getAgentContext("streaming-agent") } returns streamingAgent
      val fixture = Fixture(contextManager = contextManager)
      val prometheusScrape = fixture.trackScrape()
      val dashboardScrape = fixture.trackScrape(dashboardScrape = true)
      every { streamingAgent.agentId } returns "streaming-agent"
      every { streamingAgent.isValid() } returnsMany listOf(true, true, false)
      coEvery { streamingAgent.readScrapeRequest() } returns prometheusScrape andThen dashboardScrape

      val emitted = fixture.service.readRequestsFromProxy(agentInfo { agentId = "streaming-agent" }).toList()

      emitted.size shouldBe 2
      fixture.counts.queueWaitCount shouldBe 1L
    }
  }

  private companion object {
    val CONFIG_VALS =
      testConfigVals(
        """
        proxy { auth = [] }
        agent {
          pathConfigs = []
          filters = []
        }
        """,
      )
  }
}
