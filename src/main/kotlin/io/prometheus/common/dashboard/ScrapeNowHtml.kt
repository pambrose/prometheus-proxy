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

package io.prometheus.common.dashboard

import io.ktor.http.HttpStatusCode
import io.prometheus.common.Exposition
import io.prometheus.common.dashboard.DashboardPage.SAY_ATTR
import io.prometheus.common.dashboard.DashboardPage.size
import io.prometheus.common.dashboard.DashboardPage.statusPill
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.stream.createHTML

/** What a dashboard's scrape-now came to: a [Done] scrape, or a request [Refused] before any scrape, and why. */
internal sealed interface ScrapeNowResult {
  val status: HttpStatusCode

  data class Done(
    val summary: ScrapeSummary,
  ) : ScrapeNowResult {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  data class Refused(
    override val status: HttpStatusCode,
    val reason: String,
  ) : ScrapeNowResult
}

/**
 * The result panel's content for a scrape-now, the proxy's (`ScrapeNow`) or the agent's (`AgentScrapeNow`), which the
 * page's script puts in `#scrape-result`.
 *
 * A heading line says how the scrape went, and the start of the body follows in a `<pre>`, escaped twice over: its
 * control characters by [ScrapeSummary], and its markup by kotlinx.html, so a target can't put anything on the page
 * but text. The heading carries the sentence the page's script says aloud.
 */
internal object ScrapeNowHtml {
  fun render(result: ScrapeNowResult): String {
    // Block-bodied, as the other renderers are: CodeScene counts a conditional in an expression body's trailing lambda
    // as a Global Conditional.
    return createHTML().div {
      when (result) {
        is ScrapeNowResult.Done -> done(result.summary)
        is ScrapeNowResult.Refused -> refused(result.reason)
      }
    }
  }

  private fun DIV.done(summary: ScrapeSummary) {
    val status = summary.statusCode.value
    div("sr-head") {
      attributes[SAY_ATTR] = "Scrape of /${summary.path} finished: $status in ${summary.durationMillis} ms."
      statusPill(status, summary.isSuccess)
      span("sr-path") { +"/${summary.path}" }
      span("dim") { +facts(summary).joinToString(" · ") }
      closeButton()
    }
    if (summary.failure.isNotEmpty()) div("sr-failure") { +summary.failure }
    if (summary.excerpt.isNotEmpty()) {
      pre { +summary.excerpt }
      // The excerpt is of the body as the proxy gets it, after an agent's filter.
      val shown = summary.filteredBytes ?: summary.bytes
      if (!summary.isWhole) div("dim sr-note") { +"The start of a ${size(shown)} body" }
    }
  }

  private fun DIV.refused(reason: String) {
    div("sr-head") {
      attributes[SAY_ATTR] = "Not scraped: $reason."
      span("tag crit") { +"not scraped" }
      span { +reason }
      closeButton()
    }
  }

  private fun DIV.closeButton() {
    button(type = ButtonType.button, classes = "close") {
      attributes["aria-label"] = "Close the scrape result"
      attributes["title"] = "Close"
    }
  }

  // The heading's facts, the ones the scrape has: what came back is only worth saying of a body that did, its size
  // after an agent's filter only where the filter changed it, and its families and samples only of a body in a text
  // format, the only kind whose lines are counted. Of any other, such as protobuf, "0 families · 0 samples" read as an
  // empty text body; its content type alone says what it is.
  private fun facts(summary: ScrapeSummary): List<String> {
    val body = summary.isSuccess
    val counted = body && summary.isText
    return listOfNotNull(
      summary.outcome,
      "${summary.durationMillis} ms",
      size(summary.bytes).takeIf { summary.bytes > 0 },
      summary.filteredBytes?.takeIf { it != summary.bytes }?.let { "${size(it)} after the filter" },
      summary.contentType.takeIf { body },
      "${summary.families} families".takeIf { counted },
      "${summary.samples} samples".takeIf { counted },
      Exposition.EOF_MARKER.takeIf { summary.hasEof },
    )
  }
}
