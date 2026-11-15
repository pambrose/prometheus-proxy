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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.proxy.dashboard.DashboardFixtures.departedPathView
import io.prometheus.proxy.dashboard.DashboardFixtures.renderFullPage
import io.prometheus.proxy.dashboard.DashboardFixtures.scrapeRecord

/**
 * The Paths view's table and search, [PathTableHtml]: the History column, the Identity column, the search, its empty
 * states, and how the socket carries it, and the problem counts the status bar and title take from the paths. The rest
 * of the page, and the table's long-standing columns, are in [ProxyDashboardHtmlTest].
 */
class PathTableHtmlTest : StringSpec() {
  private fun path(name: String) = DashboardFixtures.pathView(path = name)

  private fun snapshot(
    paths: List<PathView> = [path("app_metrics")],
    authEnabled: Boolean = false,
  ) = DashboardFixtures.snapshot(paths = paths, authEnabled = authEnabled)

  // The Paths view's push for these paths, as a session with this filter receives it.
  private fun pathPush(
    paths: List<PathView> = [path("app_metrics")],
    filter: RowFilter = RowFilter.NONE,
    authEnabled: Boolean = false,
  ) = ProxyDashboardHtml.pushFragment(snapshot(paths, authEnabled), null, "/dashboard", DashboardLayout.PATH, 0, filter)

  // The push for one path whose history has these status codes, newest first, as PathHistory hands them over.
  private fun historyTable(vararg codes: Int) =
    pathPush([DashboardFixtures.pathView(history = codes.map { scrapeRecord(statusCode = it) })])

  init {
    // ==================== History column ====================

    "the path table should have a History column" {
      renderFullPage(snapshot(), DashboardLayout.PATH) shouldContain """<th scope="col">History</th>"""
    }

    "the history strip should draw a path's scrapes oldest first, one element per run of like results" {
      // Oldest to newest this is ok, ok, crit, crit, ok.
      val html = historyTable(200, 503, 503, 200, 200)

      html shouldContain
        """<i class="ok" style="--n:2"></i><i class="crit" style="--n:2"></i><i class="ok" style="--n:1"></i>"""
      html shouldContain """role="img" aria-label="3 of 5 recent scrapes succeeded""""
      html shouldContain "</span>60%"
    }

    // 2 of 3 is 66.7%: rounding would be fine here, but 199 of 200 would round to a misleading 100%.
    "a path with any failed scrape should never read 100%" {
      historyTable(200, 200, 503) shouldContain "</span>66%"
    }

    // demos/dashboard/screenshots.sh decides the demo is healthy by counting `class="pill ok"` in the path table
    // against the number of paths, so the strip must not draw with the status pill.
    "the history strip should leave the status pill as the row's only pill" {
      Regex("""class="pill ok"""").findAll(historyTable(200, 200, 200, 200, 200)).count() shouldBe 1
    }

    "the history strip should stay visible under forced colours" {
      ProxyDashboardCss.CSS shouldContain ".hist i { forced-color-adjust:none; }"
    }

    "the path table should be captioned and its headers scoped" {
      val html = renderFullPage(snapshot(), DashboardLayout.PATH)

      html shouldContain """scope="col""""
      html shouldContain "<caption"
      html shouldContain "Registered paths"
    }

    "an empty proxy should say so in the path layout too" {
      pathPush(paths = emptyList()) shouldContain "No paths registered"
    }

    // ==================== Identities ====================

    "the path table should show each path's identity when agent auth is on" {
      val paths = [path("app_metrics").copy(identity = "east"), path("node_metrics").copy(identity = "west")]
      val html = pathPush(paths, authEnabled = true)

      html shouldContain """<th scope="col">Identity</th>"""
      html shouldContain """<td class="dim">east</td>"""
      html shouldContain """<td class="dim">west</td>"""
    }

    // A departed path has no identity, so with only those left the column would vanish under an operator reading them.
    "the Identity column should stay while agent auth is on, though no path left has an identity" {
      val html = pathPush([departedPathView()], authEnabled = true)

      html shouldContain """<th scope="col">Identity</th>"""
    }

    // With agent auth off no path has an identity, and a column of dashes would only take width from the target.
    "the path table should have no Identity column with agent auth off" {
      pathPush() shouldNotContain "Identity</th>"
    }

    "the legacy shared token should read as a shared token, not its internal name" {
      val html = pathPush([path("app_metrics").copy(identity = "legacy-agent-token")], authEnabled = true)

      html shouldContain "shared token"
      html shouldNotContain "legacy-agent-token"
    }

    // ==================== Search and filter ====================

    "a filter should keep only the matching rows and say how many it shows" {
      val paths = [path("app_metrics"), path("node_metrics"), path("db_metrics")]
      val html = pathPush(paths, RowFilter.of("node", problemsOnly = false))

      html shouldContain "/node_metrics"
      html shouldNotContain "/app_metrics"
      html shouldNotContain "/db_metrics"
      html shouldContain """<div class="match-count">Showing 1 of 3 paths</div>"""
      // For the page's script, which says the count aloud once per change of filter.
      html shouldContain """${ProxyDashboardHtml.MATCH_COUNT_ATTR}="Showing 1 of 3 paths""""
    }

    "an unfiltered table should not say how many it shows" {
      pathPush() shouldNotContain """class="match-count""""
    }

    "a filter that leaves no rows should say so, and problems only with nothing wrong should say that" {
      pathPush(filter = RowFilter.of("nothing-like-this", problemsOnly = false)) shouldContain
        "No paths match the filter"
      pathPush(filter = RowFilter.of(null, problemsOnly = true)) shouldContain
        "No problems: every path's last scrape succeeded"
    }

    // The search is a plain GET form, so it works without the script; the page echoes the filter it was served with.
    "the Paths page should render the search with the filter it was opened with, outside the pushed table" {
      val page = renderFullPage(snapshot(), DashboardLayout.PATH, filter = RowFilter.of("east", problemsOnly = true))

      page shouldContain """<form action="/dashboard/paths" method="get" class="filters" role="search">"""
      page shouldContain """id="${ProxyDashboardHtml.FILTER_QUERY_ID}" aria-label="Filter paths""""
      page shouldContain """name="${RowFilter.QUERY_PARAM}""""
      page shouldContain """value="east""""
      page shouldContain """id="${ProxyDashboardHtml.FILTER_PROBLEMS_ID}" value="1" checked="checked""""
      // A push replaces #path-table, so the input someone is typing in must not be inside it.
      (page.indexOf("""<form action""") < page.indexOf("""id="${ProxyDashboardHtml.PATH_TABLE_ID}"""")) shouldBe true
    }

    "a search query should be escaped where the page echoes it" {
      val filter = RowFilter.of("\"><script>", problemsOnly = false)
      val page = renderFullPage(snapshot(), DashboardLayout.PATH, filter = filter)

      page shouldNotContain "\"><script>"
    }

    "the page script should send the URL's query, which holds the filter, with every announce" {
      val page = renderFullPage(snapshot())

      page shouldContain "search: window.location.search"
      page shouldContain "history.replaceState"
    }

    // The socket carries the URL's query as is, so it reads the same as the /paths route does.
    "filter parsing should read the URL's query and tolerate anything a browser might send" {
      ProxyDashboardService.parseFilter("""{"layout":"PATH","search":"?q=east&problems=1"}""") shouldBe
        RowFilter("east", problemsOnly = true)
      ProxyDashboardService.parseFilter("""{"search":"q=team%20a"}""") shouldBe RowFilter("team a")
      ProxyDashboardService.parseFilter("""{"search":"?q=++west++"}""") shouldBe RowFilter("west")
      // Cut, since every push matches it against every row.
      ProxyDashboardService.parseFilter("""{"search":"?q=${"x".repeat(500)}"}""").query.length shouldBe
        RowFilter.MAX_QUERY_LENGTH
      // Only "1" turns problems on, as the form's checkbox sends it.
      ProxyDashboardService.parseFilter("""{"search":"?problems=true"}""") shouldBe RowFilter.NONE
      // Anything else is no filter.
      ProxyDashboardService.parseFilter("""{"search":""}""") shouldBe RowFilter.NONE
      ProxyDashboardService.parseFilter("""{"search":12}""") shouldBe RowFilter.NONE
      ProxyDashboardService.parseFilter("""{"search":"?q=%zz"}""") shouldBe RowFilter.NONE
      ProxyDashboardService.parseFilter("""{"select":"a1"}""") shouldBe RowFilter.NONE
      ProxyDashboardService.parseFilter("not json at all") shouldBe RowFilter.NONE
    }

    // ==================== Problems in the status bar and title ====================

    "the status bar should count failing paths, and the title every problem" {
      val failing = path("app_metrics").copy(history = [scrapeRecord(statusCode = 503)])
      val departed = departedPathView(lastScrape = scrapeRecord(path = "orphan_metrics", statusCode = 503))
      val html = pathPush([failing, departed])

      // A departed path counts once, as departed, not again as failing.
      html shouldContain "<span class=\"crit\">1 failing</span>"
      html shouldContain "<span class=\"crit\">1 departed</span>"
      html shouldContain """${ProxyDashboardHtml.TITLE_ATTR}="(2 problems) prometheus-proxy""""
      renderFullPage(snapshot([failing, departed])) shouldContain "<title>(2 problems) prometheus-proxy</title>"
    }

    "a single problem should read in the singular" {
      val failing = path("app_metrics").copy(history = [scrapeRecord(statusCode = 503)])

      pathPush([failing]) shouldContain """${ProxyDashboardHtml.TITLE_ATTR}="(1 problem) prometheus-proxy""""
    }

    "a healthy proxy's status bar should name no failing paths, and its title no problems" {
      val html = pathPush()

      html shouldNotContain "failing"
      html shouldContain """${ProxyDashboardHtml.TITLE_ATTR}="prometheus-proxy""""
      renderFullPage(snapshot()) shouldContain "<title>prometheus-proxy</title>"
    }
  }
}
