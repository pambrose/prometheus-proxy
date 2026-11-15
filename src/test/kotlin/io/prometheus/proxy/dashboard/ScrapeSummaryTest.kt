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
import io.ktor.http.ContentType
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

    // Prometheus 3 writes a name outside the legacy character set quoted. Cut at its first space, both names below read
    // as `"a`, one family.
    "a quoted family name should be counted whole, as the agent's metric filter reads it" {
      val quoted =
        "# HELP \"a b\" A spaced counter.\n# TYPE \"a b\" counter\n{\"a b\"} 1\n" +
          "# TYPE \"a c\" counter\n{\"a c\"} 2\n"

      ScrapeSummary.of("quoted_metrics", [ok(quoted)]).apply {
        families shouldBe 2
        samples shouldBe 2
      }
    }

    // The agent's metric filter opens a family on any of the three, and ends the keyword at a space or a tab.
    "a tab after the keyword, and a HELP or UNIT line with no TYPE, should each open a family" {
      val text = "# TYPE\tfoo counter\nfoo_total 1\n# HELP bar Bar.\nbar 2\n# UNIT baz_seconds seconds\nbaz_seconds 3\n"

      ScrapeSummary.of("mixed_metrics", [ok(text)]).apply {
        families shouldBe 3
        samples shouldBe 3
      }
    }

    // The text format allows blanks before a line's first token, so an indented comment is still a comment.
    "an indented comment should open its family and not be counted as a sample" {
      val indented = "  # HELP up Whether it is up.\n\t# TYPE up gauge\n  up 1\n \t\n"

      ScrapeSummary.of("indented_metrics", [ok(indented)]).apply {
        families shouldBe 1
        samples shouldBe 1
      }
    }

    // A carriage return that ends a line is not part of its last token, but one inside a line doesn't end the line.
    "lines should be split at line feeds alone, as the agent's metric filter splits them" {
      val text = "# HELP up Up.\r\n# TYPE up\r\nup 1\r\nfirst 1\rsecond 2\n"

      ScrapeSummary.of("crlf_metrics", [ok(text)]).apply {
        families shouldBe 1
        samples shouldBe 2
      }
    }

    "an OpenMetrics body should be counted" {
      val openMetrics = ContentType("application", "openmetrics-text").withParameter("version", "1.0.0")

      ScrapeSummary.of("om_metrics", [ok().copy(contentType = openMetrics)]).apply {
        contentType shouldBe "application/openmetrics-text"
        families shouldBe 2
        samples shouldBe 3
      }
    }

    // A protobuf body reaches the dashboard decoded as text, where its bytes would read as sample lines.
    "a body in neither text format should count no families or samples" {
      val protobuf = ContentType("application", "vnd.google.protobuf").withParameter("encoding", "delimited")

      ScrapeSummary.of("protobuf_metrics", [ok().copy(contentType = protobuf)]).apply {
        contentType shouldBe "application/vnd.google.protobuf"
        families shouldBe 0
        samples shouldBe 0
        excerpt shouldBe body
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
