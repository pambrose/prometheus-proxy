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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.startAndAwaitReady
import io.prometheus.common.testConfigVals
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.scrapeRequest
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

/**
 * What [AgentHttpService] keeps for the agent's dashboard: each scrape the proxy asks for, recorded in the path's
 * history ([AgentScrapeHistory]) with the body's size before and after the filter, and the dashboard's own Scrape now
 * ([AgentHttpService.fetchForDashboard]), which is counted and recorded nowhere.
 */
class AgentHttpServiceDashboardTest : StringSpec() {
  // A new one for each test, which agent() hands its mock agent.
  private var history = AgentScrapeHistory(historySize = 5, pathCount = { 1 })

  // A mock agent with a real path manager, whose filter on /metrics drops denied_metric, and a real history.
  private fun agent(): Agent {
    val options = mockk<AgentOptions>(relaxed = true)
    every { options.maxCacheSize } returns 100
    every { options.maxCacheAgeMins } returns 30
    every { options.maxCacheIdleMins } returns 10
    every { options.cacheCleanupIntervalMins } returns 5
    every { options.scrapeTimeoutSecs } returns 10
    every { options.minGzipSizeBytes } returns 1_000_000
    every { options.maxContentLengthMBytes } returns 10
    every { options.httpClientTimeoutSecs } returns 90
    every { options.chunkContentSizeBytes } returns 32 * 1024
    val grpc = mockk<AgentGrpcService>(relaxed = true)
    coEvery { grpc.registerPathOnProxy(any(), any(), any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }
    return mockk<Agent>(relaxed = true).also { agent ->
      every { agent.options } returns options
      every { agent.configVals } returns testConfigVals(
        """
        agent {
          pathConfigs = []
          filters = [{ path = "metrics", metricNameAllow = [], metricNameDeny = ["denied_metric"] }]
          internal { cioTimeoutSecs = 90 }
        }
        proxy { auth = [] }
        """,
      )
      every { agent.collectsMetrics } returns false
      every { agent.isTestMode } returns true
      every { agent.grpcService } returns grpc
      every { agent.scrapeHistory } returns history
      val pathManager = AgentPathManager(agent)
      every { agent.pathManager } returns pathManager
    }
  }

  private fun target(): EmbeddedServer<*, *> =
    embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
      routing { get("/metrics") { call.respondText(BODY) } }
    }

  private fun request(
    path: String = "metrics",
    fromDashboard: Boolean = false,
  ) = scrapeRequest {
    agentId = "agent-1"
    scrapeId = 1L
    this.path = path
    dashboardScrape = fromDashboard
  }

  // A port nothing listens on: a server's, once stopped.
  private suspend fun closedPort(): Int {
    val server = target()
    return server.startAndAwaitReady().also { server.stop(0, 0) }
  }

  init {
    beforeTest { history = AgentScrapeHistory(historySize = 5, pathCount = { 1 }) }

    "a scrape of a path the agent serves should be recorded, with the body's size before and after its filter" {
      val server = target()
      try {
        val port = server.startAndAwaitReady()
        val agent = agent()
        agent.pathManager.registerPath("metrics", "http://$LOOPBACK_HOST:$port/metrics")
        val service = AgentHttpService(agent)

        service.fetchScrapeUrl(request())
        service.close()

        val records = history.snapshot()["metrics"].shouldNotBeNull().also { it shouldHaveSize 1 }
        records.single().apply {
          statusCode shouldBe 200
          outcome shouldBe "success"
          bytes shouldBe BODY.length.toLong()
          filteredBytes shouldBeLessThan bytes
          gzipped.shouldBeFalse()
          chunked.shouldBeFalse()
          failure shouldBe ""
          fromProxyDashboard.shouldBeFalse()
        }
      } finally {
        server.stop(0, 0)
      }
    }

    "a scrape the proxy's dashboard asked for should be recorded, marked as one" {
      val server = target()
      try {
        val port = server.startAndAwaitReady()
        val agent = agent()
        agent.pathManager.registerPath("metrics", "http://$LOOPBACK_HOST:$port/metrics")
        val service = AgentHttpService(agent)

        service.fetchScrapeUrl(request(fromDashboard = true))
        service.close()

        history.snapshot().getValue("metrics").single().fromProxyDashboard.shouldBeTrue()
      } finally {
        server.stop(0, 0)
      }
    }

    // The filter's counts, which the dashboard shows and agent_filter_* reports, are of Prometheus's scrapes, as the
    // agent_scrape_* metrics are: a click on either dashboard's Scrape now isn't one.
    "a scrape the proxy's dashboard asked for should leave the filter's counts alone, and Prometheus's add to them" {
      val server = target()
      try {
        val port = server.startAndAwaitReady()
        val agent = agent()
        agent.pathManager.registerPath("metrics", "http://$LOOPBACK_HOST:$port/metrics")
        val filter = agent.pathManager.filtersByPath.getValue("metrics")
        val service = AgentHttpService(agent)

        service.fetchScrapeUrl(request(fromDashboard = true)).srContentAsText shouldNotContain "denied_metric"
        filter.linesDropped shouldBe 0
        verify(exactly = 0) { agent.metrics(any()) }

        service.fetchScrapeUrl(request())
        service.close()
        filter.linesDropped shouldBe 2
        verify(exactly = 1) { agent.metrics(any()) }
      } finally {
        server.stop(0, 0)
      }
    }

    // Not one of the agent's paths, and a ring per path the proxy names would grow without end.
    "a scrape of a path the agent doesn't serve should not be recorded" {
      val service = AgentHttpService(agent())

      service.fetchScrapeUrl(request(path = "nope")).srStatusCode shouldBe 404
      service.close()

      history.snapshot().shouldBeEmpty()
    }

    "a scrape that failed should be recorded with the agent's reason" {
      val port = closedPort()
      val agent = agent()
      agent.pathManager.registerPath("metrics", "http://$LOOPBACK_HOST:$port/metrics")
      val service = AgentHttpService(agent)

      service.fetchScrapeUrl(request())
      service.close()

      history.snapshot().getValue("metrics").single().apply {
        statusCode shouldBeGreaterThan 399
        outcome shouldBe "failure"
        failure shouldNotBe ""
        bytes shouldBe 0
      }
    }

    // As the proxy's Scrape now is kept out of what the proxy reports: a click isn't a scrape Prometheus made.
    "a dashboard fetch should return the filtered body and its size before the filter, and count and record nothing" {
      val server = target()
      try {
        val port = server.startAndAwaitReady()
        val agent = agent()
        val filter = agent.pathManager.filtersByPath.getValue("metrics")
        val service = AgentHttpService(agent)

        val results = service.fetchForDashboard("http://$LOOPBACK_HOST:$port/metrics", filter, 5.seconds)
        service.close()

        results.apply {
          srStatusCode shouldBe 200
          srContentAsText shouldContain "kept_metric"
          srContentAsText shouldNotContain "denied_metric"
          rawSize shouldBe BODY.length
          (filteredSize < rawSize).shouldBeTrue()
        }
        history.snapshot().shouldBeEmpty()
        filter.linesDropped shouldBe 0
        filter.bytesSaved shouldBe 0
        verify(exactly = 0) { agent.updateScrapeCounter(any()) }
        verify(exactly = 0) { agent.updateDashboardScrapeCounter() }
        verify(exactly = 0) { agent.metrics(any()) }
      } finally {
        server.stop(0, 0)
      }
    }

    "a dashboard fetch that failed should say why" {
      val service = AgentHttpService(agent())

      val results = service.fetchForDashboard("http://$LOOPBACK_HOST:${closedPort()}/metrics", null, 5.seconds)
      service.close()

      results.srStatusCode shouldBeGreaterThan 399
      results.srFailureReason shouldNotBe ""
    }
  }

  private companion object {
    val BODY =
      """
      # TYPE kept_metric gauge
      kept_metric 1
      # TYPE denied_metric gauge
      denied_metric 2

      """.trimIndent()
  }
}
