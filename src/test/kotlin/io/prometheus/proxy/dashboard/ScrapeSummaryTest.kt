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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.ktor.http.HttpStatusCode
import io.prometheus.proxy.ScrapeRequestResponse
import kotlin.time.Duration.Companion.milliseconds

class ScrapeSummaryTest : StringSpec() {
  private val body =
    """
    # HELP demo_up Whether the demo is up.
    # TYPE demo_up gauge
    demo_up 1
    # TYPE demo_requests counter
    demo_requests_total{code="200"} 42
    demo_requests_total{code="500"} 3

    """.trimIndent()

  private fun ok(
    text: String = body,
    durationMs: Long = 120,
  ) = DashboardFixtures.scrapeResponse(text, durationMs)

  init {
    "a summary should say how the scrape went and count what came back" {
      ScrapeSummary.of("demo_metrics", [ok()]).apply {
        statusCode shouldBe HttpStatusCode.OK
        isSuccess.shouldBeTrue()
        outcome shouldBe "success"
        duration shouldBe 120.milliseconds
        bytes shouldBe body.length.toLong()
        contentType shouldBe "text/plain"
        families shouldBe 2
        samples shouldBe 3
        hasEof.shouldBeFalse()
        failure shouldBe ""
        excerpt shouldBe body
        isWhole.shouldBeTrue()
      }
    }

    // As the scrape route merges a consolidated path's agents for Prometheus: one EOF at the end, the slowest agent.
    "a consolidated path's agents should be merged as Prometheus sees them" {
      val openMetrics = "# TYPE up gauge\nup 1\n# EOF\n"

      ScrapeSummary.of("up_metrics", [ok(openMetrics, 50), ok(openMetrics, 300)]).apply {
        duration shouldBe 300.milliseconds
        hasEof.shouldBeTrue()
        families shouldBe 1
        samples shouldBe 2
        excerpt shouldEndWith "# EOF"
      }
    }

    "a failed scrape should carry the agent's reason, cut and escaped, and no body" {
      val failed =
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "upstream_error",
          failureReason = "Connection refused\nforged line",
          fetchDuration = 8.milliseconds,
        )

      ScrapeSummary.of("down_metrics", [failed]).apply {
        isSuccess.shouldBeFalse()
        outcome shouldBe "upstream_error"
        failure shouldBe "Connection refused\\nforged line"
        excerpt shouldBe ""
        bytes shouldBe 0
      }
    }

    "a long body should be cut at its last line break within the excerpt" {
      val line = "metric_with_a_long_name{label=\"value\"} 1\n"
      val long = line.repeat(ScrapeSummary.MAX_EXCERPT_CHARS / line.length + 10)

      ScrapeSummary.of("long_metrics", [ok(long)]).apply {
        isWhole.shouldBeFalse()
        (excerpt.length <= ScrapeSummary.MAX_EXCERPT_CHARS).shouldBeTrue()
        excerpt shouldEndWith "\n"
      }
    }

    // One line longer than the excerpt has no break to cut at; the cut must still not split a character.
    "a body with no line break in its excerpt should be cut short of a surrogate pair" {
      val text = "x".repeat(ScrapeSummary.MAX_EXCERPT_CHARS - 1) + "\uD83D\uDE00" + "tail"

      ScrapeSummary.excerpt(text) shouldBe "x".repeat(ScrapeSummary.MAX_EXCERPT_CHARS - 1)
    }

    // A target's body is shown to whoever opens the dashboard; a control character could hide what follows it.
    "control characters in the body should be escaped, but not its line breaks, tabs, and carriage returns" {
      ScrapeSummary.of("x", [ok("a\tb\r\nc\u001b[2Jd\u2028e\n")]).excerpt shouldBe "a\tb\r\nc\\u001b[2Jd\\u2028e\n"
    }
  }
}
