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

package io.prometheus.proxy.dashboard

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.ActivityLog
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.ProxyPathManager.AgentContextInfo
import io.prometheus.proxy.ScrapeRequestResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** The dashboard's scrape-now, [ScrapeNow], with its scrape stood in for: the refusals, the limits, and the feed. */
class ScrapeNowTest : StringSpec() {
  private val ok = DashboardFixtures.scrapeResponse()

  private fun info(valid: Boolean = true) =
    AgentContextInfo(
      isConsolidated = false,
      labels = "{}",
      agentContexts = [
        AgentContext("10.0.1.14:5555").apply {
          assignProperties(registerAgentRequest { agentName = "agent-east" })
          if (!valid) invalidate()
        },
      ],
    )

  private fun proxy(
    log: ActivityLog = ActivityLog(100),
    running: Boolean = true,
    info: AgentContextInfo? = info(),
  ): Proxy =
    mockk<Proxy>(relaxed = true).also {
      every { it.isRunning } returns running
      every { it.pathManager.getAgentContextInfo(any()) } returns info
      every { it.activityLog } returns log
    }

  init {
    "a scrape should summarize what came back and go in the activity feed, naming the browser it came from" {
      val log = ActivityLog(100)
      val scrapes = ScrapeNow(proxy(log), submit = { _, _ -> [ok] })

      val result = scrapes.scrape("orders_metrics", "10.9.9.9")

      result.shouldBeInstanceOf<ScrapeNow.Done>().summary.apply {
        path shouldBe "orders_metrics"
        statusCode shouldBe HttpStatusCode.OK
        samples shouldBe 1
      }
      result.status shouldBe HttpStatusCode.OK
      log.entries().single().apply {
        kind shouldBe ActivityKind.SCRAPED_NOW
        agentName shouldBe "agent-east"
        paths shouldBe ["orders_metrics"]
        detail shouldBe "200 success in 120 ms, from 10.9.9.9"
      }
    }

    // Each is refused before any scrape, so no stand-in is needed.
    "a path that isn't registered, or has no agent, or a proxy that isn't running should be refused" {
      ScrapeNow(proxy(info = null)).scrape("/nope", "c") shouldBe
        ScrapeNow.Refused(HttpStatusCode.NotFound, "/nope isn't registered")
      ScrapeNow(proxy(info = info(valid = false))).scrape("gone", "c") shouldBe
        ScrapeNow.Refused(HttpStatusCode.ServiceUnavailable, "/gone has no agent connected")
      ScrapeNow(proxy(running = false)).scrape("orders_metrics", "c") shouldBe
        ScrapeNow.Refused(HttpStatusCode.ServiceUnavailable, "The proxy isn't running")
    }

    "a path should be scraped from the dashboard at most once a cooldown" {
      val time = TestTimeSource()
      val scrapes = ScrapeNow(proxy(), time, submit = { _, _ -> [ok] })

      scrapes.scrape("orders_metrics", "c").shouldBeInstanceOf<ScrapeNow.Done>()
      scrapes.scrape("orders_metrics", "c") shouldBe
        ScrapeNow.Refused(
          HttpStatusCode.TooManyRequests,
          "/orders_metrics was scraped from the dashboard less than 5s ago",
        )
      // Another path isn't held up.
      scrapes.scrape("payments_metrics", "c").shouldBeInstanceOf<ScrapeNow.Done>()

      time += ScrapeNow.COOLDOWN
      scrapes.scrape("orders_metrics", "c").shouldBeInstanceOf<ScrapeNow.Done>()
    }

    // Clicks mustn't take the in-flight slots Prometheus's scrapes need.
    "at most a few dashboard scrapes should run at once" {
      val gate = CompletableDeferred<Unit>()
      val started = AtomicInt(0)
      val scrapes =
        ScrapeNow(proxy()) { _, _ ->
          started.incrementAndFetch()
          gate.await()
          [ok]
        }

      coroutineScope {
        val running = (1..ScrapeNow.MAX_CONCURRENT).map { async { scrapes.scrape("p$it", "c") } }
        eventually(5.seconds) { started.load() shouldBe ScrapeNow.MAX_CONCURRENT }

        scrapes.scrape("one_more", "c") shouldBe
          ScrapeNow.Refused(HttpStatusCode.TooManyRequests, "${ScrapeNow.MAX_CONCURRENT} dashboard scrapes are running")

        gate.complete(Unit)
        running.awaitAll().forEach { it.shouldBeInstanceOf<ScrapeNow.Done>() }
      }
      scrapes.scrape("one_more", "c").shouldBeInstanceOf<ScrapeNow.Done>()
    }

    // The feed folds a burst of one browser's scrapes into a line, but only while they end alike.
    "a failed scrape-now should not fold into a successful one in the activity feed" {
      val log = ActivityLog(100)
      val failed =
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "upstream_error",
          fetchDuration = 5.milliseconds,
        )
      var answer = ok
      val scrapes = ScrapeNow(proxy(log)) { _, _ -> [answer] }

      scrapes.scrape("orders_metrics", "10.9.9.9")
      answer = failed
      scrapes.scrape("payments_metrics", "10.9.9.9")

      log.entries().map { it.detail.substringBefore(" in ") } shouldBe ["503 upstream_error", "200 success"]
    }
  }
}
