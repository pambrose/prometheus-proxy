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

package io.prometheus.proxy.dashboard

import io.prometheus.proxy.dashboard.ProxyDashboardHtml.SAY_ATTR
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.pre
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import java.util.Locale

/**
 * The result panel's content for a scrape-now, which the page's script puts in `#scrape-result` (see [ScrapeNow]).
 *
 * A heading line says how the scrape went, and the start of the body follows in a `<pre>`, escaped twice over: its
 * control characters by [ScrapeSummary], and its markup by kotlinx.html, so a target can't put anything on the page
 * but text. The heading carries the sentence the page's script says aloud.
 */
internal object ScrapeNowHtml {
  fun render(result: ScrapeNow.Result): String {
    // Block-bodied, as the other renderers are: CodeScene counts a conditional in an expression body's trailing lambda
    // as a Global Conditional.
    return createHTML().div("sr") {
      when (result) {
        is ScrapeNow.Done -> done(result.summary)
        is ScrapeNow.Refused -> refused(result.reason)
      }
    }
  }

  private fun DIV.done(summary: ScrapeSummary) {
    val status = summary.statusCode.value
    div("sr-head") {
      attributes[SAY_ATTR] =
        "Scrape of /${summary.path} finished: $status in ${summary.duration.inWholeMilliseconds} ms."
      span(if (summary.isSuccess) "pill ok" else "pill crit") { +"$status" }
      span("sr-path") { +"/${summary.path}" }
      span("dim") { +facts(summary).joinToString(" · ") }
      closeButton()
    }
    if (summary.failure.isNotEmpty()) div("sr-failure") { +summary.failure }
    if (summary.excerpt.isNotEmpty()) {
      pre { +summary.excerpt }
      if (!summary.isWhole) div("dim sr-note") { +"The start of a ${size(summary.bytes)} body" }
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

  // The heading's facts, the ones the scrape has: what came back is only worth saying of a body that did.
  private fun facts(summary: ScrapeSummary): List<String> {
    val body = summary.isSuccess
    return listOfNotNull(
      summary.outcome,
      "${summary.duration.inWholeMilliseconds} ms",
      size(summary.bytes).takeIf { summary.bytes > 0 },
      summary.contentType.takeIf { body },
      "${summary.families} families".takeIf { body },
      "${summary.samples} samples".takeIf { body },
      "# EOF".takeIf { summary.hasEof },
    )
  }

  private const val KIB = 1024.0

  internal fun size(bytes: Long): String {
    val kib = bytes / KIB
    return when {
      bytes < KIB -> "$bytes bytes"
      kib < KIB -> String.format(Locale.ROOT, "%.1f KB", kib)
      else -> String.format(Locale.ROOT, "%.1f MB", kib / KIB)
    }
  }
}
