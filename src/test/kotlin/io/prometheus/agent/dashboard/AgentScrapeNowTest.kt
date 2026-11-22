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

package io.prometheus.agent.dashboard

import com.pambrose.common.util.runCatchingCancellable
import com.pambrose.common.util.zip
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import io.prometheus.Agent
import io.prometheus.agent.IN_PROCESS_TARGET
import io.prometheus.agent.MetricsSource
import io.prometheus.agent.ScrapeTarget
import io.prometheus.agent.embeddedAgent
import io.prometheus.agent.filter.MetricFilter
import io.prometheus.common.ScrapeResults
import io.prometheus.common.agentOptionsFromConfig
import io.prometheus.common.dashboard.ScrapeNowLimits
import io.prometheus.common.dashboard.ScrapeNowResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * [AgentScrapeNow], the agent dashboard's Scrape now: only a path the agent has configured, discovered, or registered,
 * through its own URL and filter, never one a browser names; the summary the result panel shows; and the limits that
 * keep clicks from crowding out the scrapes the proxy asks for. The fetch itself, counted nowhere, is covered in
 * `AgentHttpServiceDashboardTest`, and the route in `AgentWebDashboardTest`.
 */
class AgentScrapeNowTest : StringSpec() {
  private val created = mutableListOf<Agent>()

  private fun agent(
    pathConfigs: String =
      """
      { name = orders, path = "/orders_metrics", url = "http://ops:s3cret@orders.invalid/metrics" },
      { name = payments, path = "payments_metrics", url = "http://payments.invalid/metrics" }
      """,
  ): Agent =
    Agent(
      agentOptionsFromConfig(
        """
        agent.pathConfigs = [$pathConfigs]
        agent.filters = [{ path = "orders_metrics", metricNameAllow = [], metricNameDeny = ["go_.*"] }]
        """,
      ),
      inProcessServerName = "agent-scrape-now-${System.nanoTime()}",
      testMode = true,
    ).also { created += it }

  // A fetch that records what it was asked for and answers [results].
  private class Fetches(
    val results: ScrapeResults = ok(),
  ) {
    val asked = mutableListOf<Pair<String, MetricFilter?>>()
    val sources = mutableListOf<MetricsSource?>()

    suspend fun fetch(
      target: ScrapeTarget,
      filter: MetricFilter?,
    ): ScrapeResults {
      asked += target.url to filter
      sources += target.source
      return results
    }
  }

  init {
    afterTest {
      created.forEach { agent ->
        runCatching { agent.grpcService.shutDown() }
        runCatchingCancellable { agent.agentHttpService.close() }
      }
      created.clear()
    }

    // The agent isn't connected, so no path is registered: a refused path's target can still be checked.
    "a configured path should be scraped through its own URL and filter, and summarized as Prometheus gets it" {
      val agent = agent()
      val fetches = Fetches()

      val result = AgentScrapeNow(agent, fetch = fetches::fetch).scrape("orders_metrics", "10.0.0.9")

      fetches.asked.single().apply {
        first shouldBe "http://ops:s3cret@orders.invalid/metrics"
        second shouldBe agent.pathManager.filtersByPath["orders_metrics"]
      }
      result.shouldBeInstanceOf<ScrapeNowResult.Done>().summary.apply {
        path shouldBe "orders_metrics"
        statusCode shouldBe HttpStatusCode.OK
        outcome shouldBe "success"
        bytes shouldBe 900
        filteredBytes shouldBe 300
        contentType shouldBe "text/plain"
        families shouldBe 1
        samples shouldBe 1
        excerpt shouldBe BODY
      }
    }

    // A path an embedded host serves from code has no URL to fetch: the scrape goes through its source.
    "a path served in-process should be scraped through its source" {
      val source = MetricsSource { _, _, _ -> "" }
      val agent =
        Agent(
          embeddedAgent { source(path = "app_metrics", source = source) }.agentOptions(),
          inProcessServerName = "agent-scrape-now-${System.nanoTime()}",
          testMode = true,
        ).also { created += it }
      val fetches = Fetches()

      AgentScrapeNow(agent, fetch = fetches::fetch).scrape("app_metrics", "c")
        .shouldBeInstanceOf<ScrapeNowResult.Done>()

      fetches.asked.single().first shouldBe IN_PROCESS_TARGET
      fetches.sources.single() shouldBeSameInstanceAs source
    }

    "a gzipped body should be shown unzipped, and a path asked for with a leading slash found" {
      val fetches = Fetches(ok(zipped = true))

      val result = AgentScrapeNow(agent(), fetch = fetches::fetch).scrape("/payments_metrics", "c")

      fetches.asked.single().first shouldBe "http://payments.invalid/metrics"
      fetches.asked.single().second.shouldBeNull()
      result.shouldBeInstanceOf<ScrapeNowResult.Done>().summary.excerpt shouldBe BODY
    }

    "a failed scrape should carry its reason" {
      val failed =
        ScrapeResults(
          srAgentId = "",
          srScrapeId = 0,
          srStatusCode = 503,
          srFailureReason = "ConnectException - Connection refused",
          scrapeCounterMsg = "failure",
        )

      AgentScrapeNow(agent(), fetch = Fetches(failed)::fetch).scrape("orders_metrics", "c")
        .shouldBeInstanceOf<ScrapeNowResult.Done>()
        .summary
        .apply {
          statusCode shouldBe HttpStatusCode.ServiceUnavailable
          failure shouldBe "ConnectException - Connection refused"
          excerpt shouldBe ""
        }
    }

    // Only a URL from the agent's own config, discovery, or registrations is ever fetched, so a browser can't point the
    // agent at an address of its choosing. The path it named is shown escaped, and cut.
    "a path the agent doesn't know should be refused, without a fetch, and shown escaped" {
      val fetches = Fetches()

      val result = AgentScrapeNow(agent(), fetch = fetches::fetch).scrape("nope\u001b[2J", "c")

      fetches.asked.shouldBeEmpty()
      result.shouldBeInstanceOf<ScrapeNowResult.Refused>().apply {
        status shouldBe HttpStatusCode.NotFound
        reason shouldContain "isn't configured, discovered, or registered"
        reason shouldNotContain "\u001b"
      }
    }

    "a path should be scraped at most once a cooldown, and another path meanwhile" {
      val time = TestTimeSource()
      val scrapes = AgentScrapeNow(agent(), time, Fetches()::fetch)

      scrapes.scrape("orders_metrics", "c").shouldBeInstanceOf<ScrapeNowResult.Done>()
      scrapes.scrape("orders_metrics", "c") shouldBe
        ScrapeNowResult.Refused(
          HttpStatusCode.TooManyRequests,
          "/orders_metrics was scraped from the dashboard less than ${ScrapeNowLimits.COOLDOWN} ago",
        )
      scrapes.scrape("payments_metrics", "c").shouldBeInstanceOf<ScrapeNowResult.Done>()
      time += ScrapeNowLimits.COOLDOWN
      scrapes.scrape("orders_metrics", "c").shouldBeInstanceOf<ScrapeNowResult.Done>()
    }

    "no more than its limit should run at once" {
      val started = AtomicInt(0)
      val release = CompletableDeferred<Unit>()
      val limit = ScrapeNowLimits.MAX_CONCURRENT
      val agent =
        agent((1..limit + 1).joinToString(",") { """{ name = p$it, path = p$it, url = "http://p$it.invalid" }""" })
      val scrapes =
        AgentScrapeNow(agent) { _, _ ->
          started.incrementAndFetch()
          release.await()
          ok()
        }

      coroutineScope {
        val running = (1..limit).map { async { scrapes.scrape("p$it", "c") } }
        eventually(5.seconds) { started.load() shouldBe limit }
        scrapes.scrape("p${limit + 1}", "c") shouldBe
          ScrapeNowResult.Refused(HttpStatusCode.TooManyRequests, "$limit dashboard scrapes are running")
        release.complete(Unit)
        running.awaitAll().forEach { it.shouldBeInstanceOf<ScrapeNowResult.Done>() }
      }
    }
  }

  private companion object {
    const val BODY = "# TYPE up gauge\nup 1\n"

    fun ok(zipped: Boolean = false) =
      ScrapeResults(
        srAgentId = "",
        srScrapeId = 0,
        srValidResponse = true,
        srStatusCode = 200,
        srContentType = "text/plain; version=0.0.4; charset=utf-8",
        srZipped = zipped,
        srContentAsText = if (zipped) "" else BODY,
        srContentAsZipped = if (zipped) BODY.encodeToByteArray().zip() else ByteArray(0),
        scrapeCounterMsg = "success",
        rawSize = 900,
        filteredSize = 300,
      )
  }
}
