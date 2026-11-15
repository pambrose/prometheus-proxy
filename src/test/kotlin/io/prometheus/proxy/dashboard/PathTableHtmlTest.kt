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
import kotlinx.html.html
import kotlinx.html.stream.createHTML

/**
 * The Paths view's table and search, [PathTableHtml]: the History column, the Identity column, the search and its
 * empty states, and the problem counts the status bar takes from the paths. The rest of the page, and the table's
 * long-standing columns, are in [ProxyDashboardHtmlTest].
 */
class PathTableHtmlTest : StringSpec() {
  private fun path(name: String) = DashboardFixtures.pathView(path = name)

  private fun snapshot(paths: List<PathView> = [path("app_metrics")]) = DashboardFixtures.snapshot(paths = paths)

  // The path layout's push for one path whose history has these status codes, newest first, as PathHistory hands
  // them over.
  private fun historyTable(vararg codes: Int): String {
    val history = codes.map { DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = it) }
    val served = DashboardFixtures.pathView(path = "app_metrics", history = history)
    return ProxyDashboardHtml.pushFragment(snapshot(paths = [served]), null, "/dashboard", DashboardLayout.PATH)
  }

  private fun renderFullPage(layout: DashboardLayout = DashboardLayout.AGENT) =
    createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard", layout) } }

  init {
    // ==================== History column ====================

    "the path table should have a History column" {
      renderFullPage(DashboardLayout.PATH) shouldContain """<th scope="col">History</th>"""
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

    // ==================== Identities ====================

    "the path table should show each path's identity when agent auth is on" {
      val paths = [path("app_metrics").copy(identity = "east"), path("node_metrics").copy(identity = "west")]
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = paths), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain """<th scope="col">Identity</th>"""
      html shouldContain """<td class="dim">east</td>"""
      html shouldContain """<td class="dim">west</td>"""
    }

    // With agent auth off no path has an identity, and a column of dashes would only take width from the target.
    "the path table should have no Identity column when no path has an identity" {
      ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.PATH) shouldNotContain
        "Identity</th>"
    }

    "the legacy shared token should read as a shared token, not its internal name" {
      val legacy = path("app_metrics").copy(identity = "legacy-agent-token")
      val html = ProxyDashboardHtml.pushFragment(snapshot(paths = [legacy]), null, "/dashboard", DashboardLayout.PATH)

      html shouldContain "shared token"
      html shouldNotContain "legacy-agent-token"
    }

    // ==================== Search and filter ====================

    "a filter should keep only the matching rows and say how many it shows" {
      val paths = [path("app_metrics"), path("node_metrics"), path("db_metrics")]
      val html =
        ProxyDashboardHtml.pushFragment(
          snapshot(paths = paths),
          null,
          "/dashboard",
          DashboardLayout.PATH,
          filter = PathFilter.of("node", problemsOnly = false),
        )

      html shouldContain "/node_metrics"
      html shouldNotContain "/app_metrics"
      html shouldNotContain "/db_metrics"
      html shouldContain "Showing 1 of 3 paths"
      // For the page's script, which says the count aloud once per change of filter.
      html shouldContain """${PathTableHtml.SHOWN_ATTR}="1" ${PathTableHtml.TOTAL_ATTR}="3""""
    }

    "an unfiltered table should not say how many it shows" {
      ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.PATH) shouldNotContain "Showing"
    }

    "a filter that leaves no rows should say so, and problems only with nothing wrong should say that" {
      fun table(filter: PathFilter) =
        ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.PATH, filter = filter)

      table(PathFilter.of("nothing-like-this", problemsOnly = false)) shouldContain "No paths match the filter"
      table(PathFilter.of(null, problemsOnly = true)) shouldContain "No problems: every path's last scrape succeeded"
    }

    // The search is a plain GET form, so it works without the script; the page echoes the filter it was served with.
    "the Paths page should render the search with the filter it was opened with, outside the pushed table" {
      val page =
        createHTML().html {
          with(ProxyDashboardHtml) {
            renderPage(snapshot(), null, "/dashboard", DashboardLayout.PATH, PathFilter.of("east", problemsOnly = true))
          }
        }

      page shouldContain """<form action="/dashboard/paths" method="get" class="filters" role="search">"""
      page shouldContain """id="${PathTableHtml.FILTER_QUERY_ID}" aria-label="Filter paths""""
      page shouldContain """value="east""""
      page shouldContain """id="${PathTableHtml.FILTER_PROBLEMS_ID}" value="1" checked="checked""""
      // A push replaces #path-table, so the input someone is typing in must not be inside it.
      (page.indexOf("""<form action""") < page.indexOf("""id="${ProxyDashboardHtml.PATH_TABLE_ID}"""")) shouldBe true
    }

    "a search query should be escaped where the page echoes it" {
      val page =
        createHTML().html {
          with(ProxyDashboardHtml) {
            renderPage(snapshot(), null, "", DashboardLayout.PATH, PathFilter.of("\"><script>", problemsOnly = false))
          }
        }

      page shouldNotContain "\"><script>"
    }

    "the page script should send the URL's filter with every announce" {
      val page = createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard") } }

      page shouldContain "q: f.q, problems: f.problems"
      page shouldContain "history.replaceState"
    }

    // ==================== Problems in the status bar and title ====================

    "the status bar should count failing paths, and carry every problem for the tab's title" {
      val failing = path("app_metrics").copy(history = [DashboardFixtures.scrapeRecord(statusCode = 503)])
      val departed =
        DashboardFixtures.pathView(
          path = "orphan_metrics",
          agentIds = emptyList(),
          isDeparted = true,
          lastScrape = DashboardFixtures.scrapeRecord(path = "orphan_metrics", statusCode = 503),
        )
      val html =
        ProxyDashboardHtml.pushFragment(snapshot(paths = [failing, departed]), null, "/dashboard", DashboardLayout.PATH)

      // A departed path counts once, as departed, not again as failing.
      html shouldContain "<span class=\"crit\">1 failing</span>"
      html shouldContain "<span class=\"crit\">1 departed</span>"
      html shouldContain """${ProxyDashboardHtml.PROBLEMS_ATTR}="2""""
    }

    "a healthy proxy's status bar should name no failing paths" {
      val html = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.PATH)

      html shouldNotContain "failing"
      html shouldContain """${ProxyDashboardHtml.PROBLEMS_ATTR}="0""""
    }

    "the path table should be captioned and its headers scoped" {
      val html = renderFullPage(DashboardLayout.PATH)

      html shouldContain """scope="col""""
      html shouldContain "<caption"
      html shouldContain "Registered paths"
    }

    // ProxyDashboardService hands the renderer a base with no trailing slash, which is empty at a root
    // mount. Every sub-route must still join with exactly one separator: "//events" is a
    // protocol-relative URL, which sends the browser to a host named "events".
    "a root-mounted dashboard should not emit protocol-relative links" {
      val page =
        createHTML().html { with(ProxyDashboardHtml) { renderPage(snapshot(), null, "", DashboardLayout.AGENT) } }
      page shouldContain """hx-ws:connect="/events""""
      page shouldContain """src="/${ProxyDashboardService.ASSET_DIR}/htmx.min.js""""
      page shouldContain """href="/paths""""
      page shouldContain """hx-get="/agents/1""""
      page shouldNotContain "\"//"
      // The Agents link is the root itself, which must stay "/" rather than collapse to an empty href.
      page shouldContain """href="/" class="on""""
    }

    "layout parsing should tolerate anything a browser might send" {
      ProxyDashboardService.parseLayout("""{"layout":"PATH"}""") shouldBe DashboardLayout.PATH
      ProxyDashboardService.parseLayout("""{"layout":"AGENT"}""") shouldBe DashboardLayout.AGENT
      // Everything unexpected falls back to the layout the page served before layouts existed.
      ProxyDashboardService.parseLayout("""{"layout":"SIDEWAYS"}""") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("""{"select":"a1"}""") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("not json at all") shouldBe DashboardLayout.AGENT
      ProxyDashboardService.parseLayout("") shouldBe DashboardLayout.AGENT
    }

    "the nav should mark the current layout" {
      val agentPage = ProxyDashboardHtml.pushFragment(snapshot(), null, "/dashboard", DashboardLayout.AGENT)
      // The nav lives on the page shell rather than in a pushed region, so assert it where it renders.
      val page = createHTML().html {
        with(ProxyDashboardHtml) { renderPage(snapshot(), null, "/dashboard", DashboardLayout.PATH) }
      }
      page shouldContain """href="/dashboard/paths""""
      page shouldContain """aria-current="page""""
      agentPage shouldNotContain "aria-current=\"page\""
    }
  }
}
