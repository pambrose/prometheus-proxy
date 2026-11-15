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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.milliseconds

/** The scrape-now result panel's content, [ScrapeNowHtml]. */
class ScrapeNowHtmlTest : StringSpec() {
  private fun summary(
    statusCode: HttpStatusCode = HttpStatusCode.OK,
    outcome: String = "success",
    bytes: Long = 4300,
    failure: String = "",
    excerpt: String = "# TYPE up gauge\nup 1\n",
    isWhole: Boolean = true,
    hasEof: Boolean = false,
    contentType: String = "text/plain",
    families: Int = 1,
    samples: Int = 1,
  ) = ScrapeSummary(
    path = "orders_metrics",
    statusCode = statusCode,
    outcome = outcome,
    duration = 120.milliseconds,
    bytes = bytes,
    contentType = contentType,
    families = families,
    samples = samples,
    hasEof = hasEof,
    failure = failure,
    excerpt = excerpt,
    isWhole = isWhole,
  )

  private fun done(summary: ScrapeSummary = summary()) = ScrapeNowHtml.render(ScrapeNow.Done(summary))

  init {
    "a finished scrape should show its status, path, and facts, then the start of the body" {
      done().apply {
        this shouldContain """<span class="pill ok">200</span>"""
        this shouldContain """<span class="sr-path">/orders_metrics</span>"""
        this shouldContain "success · 120 ms · 4.2 KB · text/plain · 1 families · 1 samples"
        this shouldContain "<pre># TYPE up gauge\nup 1\n</pre>"
        // Said aloud by the page's script, from the heading.
        this shouldContain """${ProxyDashboardHtml.SAY_ATTR}="Scrape of /orders_metrics finished: 200 in 120 ms.""""
        this shouldContain """<button type="button" class="close" aria-label="Close the scrape result""""
        this shouldNotContain "sr-note"
      }
    }

    "a cut body should say it is the start of a larger one, and an EOF should be named" {
      done(summary(isWhole = false, hasEof = true)).apply {
        this shouldContain "The start of a 4.2 KB body"
        this shouldContain "· # EOF"
      }
    }

    "a failed scrape should show its reason, and nothing about a body it didn't get" {
      done(summary(HttpStatusCode.ServiceUnavailable, "upstream_error", 0, "Connection refused", "")).apply {
        this shouldContain """<span class="pill crit">503</span>"""
        this shouldContain """<div class="sr-failure">Connection refused</div>"""
        this shouldContain "upstream_error · 120 ms</span>"
        this shouldNotContain "<pre>"
      }
    }

    // ScrapeSummary counts a body's lines only in a text format, so of a protobuf body "0 families · 0 samples" read as
    // an empty text body. The panel leaves both out, keeps the facts around them, and the content type says what it is.
    "a body in neither text format should show its content type, and no families or samples" {
      val protobuf = "application/vnd.google.protobuf"

      done(summary(contentType = protobuf, families = 0, samples = 0, hasEof = true)).apply {
        this shouldContain "success · 120 ms · 4.2 KB · $protobuf · # EOF</span>"
        this shouldNotContain "families"
        this shouldNotContain "samples"
        this shouldContain "<pre>"
      }
    }

    "a body in either text format should show its families and samples" {
      listOf("text/plain", "application/openmetrics-text").forEach { contentType ->
        withClue(contentType) {
          done(summary(contentType = contentType, families = 2, samples = 3)) shouldContain "2 families · 3 samples"
        }
      }
    }

    // The body is a target's own text, shown to whoever opens the dashboard.
    "the body should be escaped" {
      done(summary(excerpt = "<script>alert(1)</script>")).apply {
        this shouldNotContain "<script>"
        this shouldContain "&lt;script&gt;"
      }
    }

    "a refused scrape should say why" {
      ScrapeNowHtml.render(ScrapeNow.Refused(HttpStatusCode.TooManyRequests, "4 dashboard scrapes are running")).apply {
        this shouldContain """<span class="tag crit">not scraped</span>"""
        this shouldContain "4 dashboard scrapes are running"
        this shouldContain """${ProxyDashboardHtml.SAY_ATTR}="Not scraped: 4 dashboard scrapes are running.""""
      }
    }

    "a size should read in bytes, KB, or MB" {
      ScrapeNowHtml.size(900) shouldBe "900 bytes"
      ScrapeNowHtml.size(4300) shouldBe "4.2 KB"
      ScrapeNowHtml.size(3 * 1024 * 1024) shouldBe "3.0 MB"
    }
  }
}
