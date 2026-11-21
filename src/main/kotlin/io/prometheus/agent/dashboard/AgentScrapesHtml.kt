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

package io.prometheus.agent.dashboard

import io.prometheus.agent.AgentScrapeRecord
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_ID_PREFIX
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_RESULT_ID
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_URL_ATTR
import io.prometheus.common.dashboard.DashboardPage.historyStrip
import io.prometheus.common.dashboard.DashboardPage.size
import io.prometheus.common.dashboard.DashboardPage.statusPill
import io.prometheus.common.dashboard.DashboardPage.timeOfDay
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.span

/**
 * The agent dashboard's scrapes, as [AgentDashboardHtml] renders them on its Paths view: each row's history strip and
 * last result, the detail pane's Recent scrapes and Scrape now button, and the panel a Scrape now's result shows in
 * ([AgentScrapeNow]). Apart from [AgentDashboardHtml], as the proxy's path table is apart from the page around it.
 */
internal object AgentScrapesHtml {
  // How a row's last scrape went, then its recent scrapes as a strip: one line, which fits the list's narrow column, so
  // the duration is left to the detail pane. A registered path Prometheus hasn't scraped says so, which is news; an
  // unregistered one can't be scraped, so says nothing.
  fun FlowContent.scrapesLine(
    path: AgentPathView,
    capacity: Int,
  ) {
    val last = path.lastScrape
    when {
      last != null -> {
        span("host") {
          statusPill(last.statusCode, last.isSuccess)
          +" "
          historyStrip(path.historyRuns, capacity)
        }
      }

      path.state == PathState.REGISTERED -> {
        span("host") { +"not scraped since the agent started" }
      }
    }
  }

  // Where a Scrape now's result shows, filled by the page's script from the route it names. Outside every pushed
  // region, so a push never clears a result someone is reading.
  fun FlowContent.scrapeResultPanel(routeBase: String) {
    div("scrape-result") {
      attributes["id"] = SCRAPE_RESULT_ID
      attributes[SCRAPE_URL_ATTR] = "$routeBase/scrape"
      attributes["hidden"] = "hidden"
    }
  }

  // Scrapes the path's target from the agent at once; the page's script posts it and reads the path back out of the
  // id, as it does for the proxy's button. Its id is the path's, unique on the page, so the push that replaces the pane
  // hands focus back to it.
  fun FlowContent.scrapeNowButton(path: String) {
    button(type = ButtonType.button, classes = "scrape-now") {
      attributes["id"] = "$SCRAPE_ID_PREFIX$path"
      attributes["aria-label"] = "Scrape /$path now"
      attributes["title"] = "Scrape now"
    }
  }

  // The path's scrapes as the agent saw them, newest first: the target's status, and what the agent did with the body.
  fun DIV.recentScrapes(scrapes: List<AgentScrapeRecord>) {
    div("section") {
      span { +"Recent scrapes" }
      span { +"${scrapes.size}" }
    }
    if (scrapes.isEmpty()) {
      div("empty pad") { +"Not scraped since the agent started" }
      return
    }
    scrapes.forEach { scrape ->
      div("kv scrape") {
        span("t") { timeOfDay(scrape.at) }
        statusPill(scrape.statusCode, scrape.isSuccess)
        span("v") { +scrapeFacts(scrape) }
        // Beside the facts rather than in them, so the row's gap sets it apart.
        if (scrape.fromProxyDashboard) span("tag") { +"from the proxy's dashboard" }
      }
    }
  }

  // What a scrape came to, the facts it has: a body's size only when one came back, its size after the filter only
  // when the filter changed it, and its failure only when it failed.
  fun scrapeFacts(scrape: AgentScrapeRecord): String =
    listOfNotNull(
      scrape.outcome,
      "${scrape.durationMillis} ms",
      size(scrape.bytes).takeIf { scrape.bytes > 0 },
      "${size(scrape.filteredBytes)} after the filter".takeIf { scrape.filteredBytes != scrape.bytes },
      "gzipped".takeIf { scrape.gzipped },
      "chunked".takeIf { scrape.chunked },
      scrape.failure.ifEmpty { null },
    ).joinToString(" · ")
}
