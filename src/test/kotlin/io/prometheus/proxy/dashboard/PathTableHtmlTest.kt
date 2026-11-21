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
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.proxy.dashboard.DashboardFixtures.departedPathView
import io.prometheus.proxy.dashboard.DashboardFixtures.iconLinks
import io.prometheus.proxy.dashboard.DashboardFixtures.renderFullPage
import io.prometheus.proxy.dashboard.DashboardFixtures.scrapeRecord

/**
 * The Paths view's table and search, [PathTableHtml]: the History column, the Identity column, the Links column, the
 * search, its empty states, and how the socket carries it, the column guide, whose button, panel, and script the
 * Activity view shares, and the problem counts the status bar and title take from the paths. The table's long-standing
 * columns are in [PathColumnsHtmlTest], the rest of the page in [ProxyDashboardHtmlTest] and [DashboardScriptTest];
 * how a link's template is filled is in [PathLinkTest].
 */
class PathTableHtmlTest : StringSpec() {
  private fun path(name: String) = DashboardFixtures.pathView(path = name)

  private fun snapshot(
    paths: List<PathView> = [path("app_metrics")],
    authEnabled: Boolean = false,
    scrapeNowEnabled: Boolean = false,
    links: List<PathLink> = emptyList(),
  ) = DashboardFixtures.snapshot(
    paths = paths,
    authEnabled = authEnabled,
    scrapeNowEnabled = scrapeNowEnabled,
    links = links,
  )

  // The Paths view's push for these paths, as a session with this filter receives it.
  private fun pathPush(
    paths: List<PathView> = [path("app_metrics")],
    filter: RowFilter = RowFilter.NONE,
    authEnabled: Boolean = false,
    scrapeNowEnabled: Boolean = false,
    links: List<PathLink> = emptyList(),
  ): String {
    val snapshot = snapshot(paths, authEnabled, scrapeNowEnabled, links)
    return ProxyDashboardHtml.pushFragment(snapshot, null, "/dashboard", DashboardLayout.PATH, 0, filter)
  }

  // The demo's two links, one into Prometheus and one into Grafana, each for the path's job label, and a runbook that
  // needs only the path.
  private val demoLinks =
    PathLink.parseAll(
      [
        PathLink.Entry("Prometheus", "http://localhost:9090/graph?g0.expr=up%7Bjob%3D%22{label.job}%22%7D"),
        PathLink.Entry("Grafana", "http://localhost:3000/d/prometheus-proxy-demo?orgId=1&var-job={label.job}"),
      ],
    )
  private val runbook = PathLink.parseAll([PathLink.Entry("Runbook", "https://runbooks.example.com/{path}")])

  private fun pathWithJob(
    name: String,
    job: String,
  ) = path(name).copy(labels = """{"job": "$job"}""")

  // The push for one path whose history has these status codes, newest first, as PathHistory hands them over.
  private fun historyTable(vararg codes: Int) =
    pathPush([DashboardFixtures.pathView(history = codes.map { scrapeRecord(statusCode = it) })])

  // The table's headers, and the column guide's names and explanations, in order, as the Paths page renders them.
  private fun headers(page: String) = Regex("<th[^>]*>([^<]*)</th>").findAll(page).map { it.groupValues[1] }.toList()

  private fun guideNames(page: String) = Regex("<dt>([^<]*)</dt>").findAll(page).map { it.groupValues[1] }.toList()

  private fun guideHelp(page: String) = Regex("<dd>([^<]*)</dd>").findAll(page).map { it.groupValues[1] }.toList()

  private fun pathsPage(snapshot: ProxySnapshot) = renderFullPage(snapshot, DashboardLayout.PATH)

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

    // demos/basics/bin/screenshots.sh decides the demo is healthy by counting `class="pill ok"` in the path table
    // against the number of paths, so the strip must not draw with the status pill.
    "the history strip should leave the status pill as the row's only pill" {
      Regex("""class="pill ok"""").findAll(historyTable(200, 200, 200, 200, 200)).count() shouldBe 1
    }

    // Faint dots keep the places of the scrapes still to come, so every strip is as wide as a full one, and the
    // percentages and the Links column stay put as the histories fill.
    "a history that isn't full should keep a faint place for each scrape still to come, before its scrapes" {
      val view = DashboardFixtures.pathView(history = [200, 503].map { scrapeRecord(statusCode = it) })
      val full = DashboardFixtures.pathView(history = [200, 200].map { scrapeRecord(statusCode = it) })

      fun push(
        path: PathView,
        historySize: Int,
      ) = ProxyDashboardHtml.pushFragment(
          snapshot([path]).copy(pathHistorySize = historySize),
          null,
          "/dashboard",
          DashboardLayout.PATH,
          0,
          RowFilter.NONE,
        )

      push(view, 5) shouldContain
        """<i class="slot" style="--n:3"></i><i class="crit" style="--n:1"></i><i class="ok" style="--n:1"></i>"""
      push(full, 2) shouldNotContain """class="slot""""
      ProxyDashboardCss.CSS shouldContain ".hist i.slot { --dot:var(--line); }"
      ProxyDashboardCss.CSS shouldNotContain ".hist i.empty"
    }

    // A table wider than the window scrolls sideways in its scroller. The push replaces the region inside it, not the
    // scroller, which kept starting again at the left on every refresh when it was the region itself.
    "the path table's scroller should stay on the page, and a push should replace only the region inside it" {
      val page = renderFullPage(snapshot(), DashboardLayout.PATH)
      val push = pathPush()

      page shouldContain Regex("""<div class="scroller">\s*<div id="${ProxyDashboardHtml.PATH_TABLE_ID}"""")
      push shouldContain """<div id="${ProxyDashboardHtml.PATH_TABLE_ID}" hx-swap-oob="true""""
      push shouldNotContain "scroller"
    }

    // A wide window's spare width goes to the other columns, so none opens between the percentage and the links.
    "the History and Links columns should keep their content's width" {
      historyTable(200) shouldContain """<td class="dim history">"""
      ProxyDashboardCss.CSS shouldContain ".ptable td.history, .ptable td.links { width:1%; }"
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

    // The column follows the config, not the rows, so it never vanishes under an operator reading the rows left.
    "the Identity column should stay while agent auth is on, though no path left has an identity" {
      val html = pathPush([departedPathView()], authEnabled = true)

      html shouldContain """<th scope="col">Identity</th>"""
    }

    // Follow-ups #4 and #5: a gone row showed a dash for both, though the identity says whose path it was and the
    // target is the first thing to check, with a curl, when a path's agent has gone.
    "a departed path should show the identity and target it had as its agent left" {
      val gone =
        departedPathView(
          path = "orphan_metrics",
          lastScrape = null,
          agentName = "warehouse-01",
          identity = "team-a",
          targetUrl = "http://warehouse/metrics",
        )
      val html = pathPush([gone], authEnabled = true)

      html shouldContain """<td class="dim">team-a</td>"""
      html shouldContain "http://warehouse/metrics"
      html shouldContain """id="${ProxyDashboardHtml.COPY_TARGET_ID_PREFIX}orphan_metrics""""
      html shouldContain "warehouse-01"
      html shouldContain "gone"
    }

    // A path whose agent left before Prometheus scraped it now has a row, from its departure, and nothing to put in its
    // scrape cells.
    "a path that departed before its first scrape should show the en dash for its scrapes" {
      val dash = ProxyDashboardHtml.DASH
      val html = pathPush([departedPathView(lastScrape = null, agentName = "warehouse-01")])

      html shouldContain "warehouse-01"
      html shouldContain """<span class="dim">$dash</span>"""
      html shouldContain """<td class="num dim">$dash</td>"""
      html shouldNotContain """class="hist""""
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

    // ==================== Links ====================

    // Last, so the columns read to find a row keep their places with links on or off; and from the config, not the
    // rows, as the Identity column is, so it stays while no row has a link.
    "the path table should end with a Links column while links are configured, though no row has one" {
      pathPush(links = demoLinks) shouldContain
        Regex("""History</th>\s*<th scope="col" class="center">Links</th>\s*</tr>""")
      pathPush([departedPathView()], links = demoLinks) shouldContain """<th scope="col" class="center">Links</th>"""
      pathPush() shouldNotContain "Links</th>"
    }

    // demos/basics/bin/screenshots.sh counts `class="pill ok"` in the path table against the number of paths.
    "each link should open its path in a new tab, named for a screen reader, and add no status pill" {
      val ok = pathWithJob("orders_metrics", "orders").copy(history = [scrapeRecord(statusCode = 200)])
      val html = pathPush([ok], links = demoLinks)

      html shouldContain
        """<td class="dim links center"><a href="http://localhost:9090/graph?g0.expr=up%7Bjob%3D%22orders%22%7D" """ +
        """target="_blank" id="link-0-orders_metrics" rel="noopener noreferrer" """ +
        """aria-label="Open /orders_metrics in Prometheus">Prometheus</a>""" +
        """ · <a href="http://localhost:3000/d/prometheus-proxy-demo?orgId=1&amp;var-job=orders" target="_blank" """ +
        """id="link-1-orders_metrics" rel="noopener noreferrer" aria-label="Open /orders_metrics in Grafana">""" +
        """Grafana</a></td>"""
      Regex("""class="pill ok"""").findAll(html).count() shouldBe 1
    }

    // A link with an empty slot would open the tool somewhere other than the path; the dash says there is none.
    "a row lacking a value every link needs should show a dash, and a departed row keep the links that use no label" {
      val html = pathPush([path("app_metrics"), departedPathView("orphan_metrics")], links = demoLinks + runbook)

      html shouldContain
        """<td class="dim links center"><a href="https://runbooks.example.com/app_metrics" target="_blank" """
      html shouldContain """aria-label="Open /orphan_metrics in Runbook">Runbook</a></td>"""
      html shouldNotContain "in Prometheus"
      pathPush(links = demoLinks) shouldContain """<td class="dim links center">${ProxyDashboardHtml.DASH}</td>"""
    }

    "the links should be quiet, and take focus as the nav does" {
      ProxyDashboardCss.CSS shouldContain ".ptable td.links a { color:inherit;"
      ProxyDashboardCss.CSS shouldContain ".ptable td.links a:focus-visible {"
    }

    // The mark and its colour come from the class, through the page's icon stylesheet, so a push carries no URL.
    // Its title is the browser's tooltip, as the copy and scrape-now buttons' titles are.
    "an icon link should show its brand mark alone, its name in its title and its aria-label" {
      val html = pathPush([pathWithJob("orders_metrics", "orders")], links = iconLinks)

      html shouldContain
        """<td class="dim links center"><a href="http://localhost:9090/graph?job=orders" target="_blank" """ +
        """class="icon icon-prometheus" id="link-0-orders_metrics" rel="noopener noreferrer" """ +
        """aria-label="Open /orders_metrics in Prometheus" title="Open in Prometheus"></a>""" +
        """<a href="http://localhost:3000/d/demo?var-job=orders" target="_blank" class="icon icon-grafana" """ +
        """id="link-1-orders_metrics" rel="noopener noreferrer" aria-label="Open /orders_metrics in Grafana" """ +
        """title="Open in Grafana"></a></td>"""
      html shouldNotContain DashboardServer.ICON_DIR
      html shouldNotContain """ title="Prometheus""""
    }

    // The push replaces the table every refresh, and htmx hands focus back only to the element with the id the focused
    // one had; the id follows the link's place in the config, so it holds when a link before it is left out.
    "each link's id should name its place in the config and its path, so it keeps its focus across a push" {
      val html = pathPush([departedPathView("orphan_metrics")], links = iconLinks + runbook)

      html shouldContain """id="link-2-orphan_metrics""""
      html shouldNotContain "link-0-"
      html shouldNotContain "link-1-"
    }

    // An icon stands apart by its own margin; a separator beside one would read as a stray dot.
    "a separator should stand only between two names" {
      val logs = PathLink.parseAll([PathLink.Entry("Logs", "https://logs.example.com/{path}")])
      val links = [iconLinks[0]] + runbook + logs + iconLinks[1]
      val html = pathPush([pathWithJob("orders_metrics", "orders")], links = links)

      Regex(" · ").findAll(html.substringAfter("""<td class="dim links center">""")).count() shouldBe 1
      html shouldContain """title="Open in Prometheus"></a><a href="https://runbooks.example.com/orders_metrics""""
      html shouldContain """>Runbook</a> · <a href="https://logs.example.com/orders_metrics""""
      html shouldContain """>Logs</a><a href="http://localhost:3000/d/demo?var-job=orders""""
    }

    "a departed row should drop an icon link that needs a label, and keep a name's" {
      val html = pathPush([departedPathView("orphan_metrics")], links = iconLinks + runbook)

      html shouldContain """<td class="dim links center"><a href="https://runbooks.example.com/orphan_metrics" """
      html shouldNotContain "icon-"
    }

    // On surface-3, the icon buttons' hover ground, the light theme's marks fall below 3:1, so hover rings the target.
    "an icon link should be a 24px target painted in its brand's colour, and keep its mark in forced colours" {
      ProxyDashboardCss.CSS shouldContain ".ptable td.links a.icon { display:inline-flex; align-items:center; " +
        "justify-content:center;"
      ProxyDashboardCss.CSS shouldContain "width:24px; height:24px;"
      ProxyDashboardCss.CSS shouldContain "text-decoration:none; }"
      ProxyDashboardCss.CSS shouldContain ".ptable td.links a.icon::before { content:\"\"; width:16px; height:16px; " +
        "background:var(--brand);"
      ProxyDashboardCss.CSS shouldContain "mask:var(--icon) center/contain no-repeat; }"
      ProxyDashboardCss.CSS shouldNotContain "a.icon:hover { background"
      ProxyDashboardCss.CSS shouldContain ".ptable td.links a.icon:hover { box-shadow:inset 0 0 0 1px var(--ink-3); }"
      ProxyDashboardCss.CSS shouldContain
        ".ptable td.links a.icon::before { forced-color-adjust:none; background:LinkText; }"
    }

    // The browser shows an icon link's title as it does the copy and scrape-now buttons', so the stylesheet draws no
    // tooltip of its own, which looked and timed unlike theirs.
    "an icon link's tooltip should be the browser's, and the column center its marks" {
      ProxyDashboardCss.CSS shouldNotContain "a.icon::after"
      ProxyDashboardCss.CSS shouldNotContain "data-tip"
      ProxyDashboardCss.CSS shouldContain ".ptable .center { text-align:center; }"
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

    // ==================== Column guide ====================

    // The header row and the guide iterate one list, so a column can't reach the header without an explanation.
    "the column guide should explain every header, in the header's order" {
      val page = pathsPage(snapshot(authEnabled = true, scrapeNowEnabled = true, links = demoLinks))

      headers(page) shouldBe
        ["Path", "Agent(s)", "Identity", "Target", "Src", "Last scrape", "Status", "Duration", "History", "Links"]
      guideNames(page) shouldBe headers(page)
      guideHelp(page).size shouldBe headers(page).size
      guideHelp(page).forEach { it.isNotBlank() shouldBe true }
      // Status and Duration keep their right alignment.
      page shouldContain
        Regex("""<th scope="col" class="num">Status</th>\s*<th scope="col" class="num">Duration</th>""")
    }

    // From the config, as the columns are, so the guide never explains a column the table doesn't have.
    "the column guide should list Identity only with agent auth on, and Links only with links configured" {
      guideNames(pathsPage(snapshot())) shouldBe
        ["Path", "Agent(s)", "Target", "Src", "Last scrape", "Status", "Duration", "History"]
      guideNames(pathsPage(snapshot(authEnabled = true))) shouldBe
        ["Path", "Agent(s)", "Identity", "Target", "Src", "Last scrape", "Status", "Duration", "History"]
      guideNames(pathsPage(snapshot(links = demoLinks))).last() shouldBe "Links"
      guideHelp(pathsPage(snapshot(links = iconLinks))).last() shouldContain "brand icon; hover over an icon"
    }

    "the Path column's explanation should name the Scrape button only while scrape-now is on" {
      guideHelp(pathsPage(snapshot(scrapeNowEnabled = true))).first() shouldContain "scrape it now"
      guideHelp(pathsPage(snapshot())).first() shouldNotContain "scrape it now"
    }

    // A push replaces #path-table, header and all, every refresh; the guide below the search outlives every one.
    "the column guide should follow the search, ahead of the scrape result and the pushed table, and no push carry it" {
      val page = pathsPage(snapshot(scrapeNowEnabled = true))
      val guide = page.indexOf("""<section class="column-guide" id="${ProxyDashboardHtml.COLUMN_GUIDE_ID}"""")

      (page.indexOf("</form>") in 0..<guide) shouldBe true
      (guide < page.indexOf("""id="${ProxyDashboardHtml.SCRAPE_RESULT_ID}"""")) shouldBe true
      (guide < page.indexOf("""id="${ProxyDashboardHtml.PATH_TABLE_ID}"""")) shouldBe true
      pathPush(scrapeNowEnabled = true, authEnabled = true, links = demoLinks).apply {
        this shouldNotContain ProxyDashboardHtml.COLUMN_GUIDE_ID
        this shouldNotContain "<dt>"
      }
    }

    // A plain button, so it changes nothing the form sends, and a disclosure: it says whether the guide is open and
    // which element it opens. The form's last item, so it sits at the search's right end. Both tables' pages share it.
    "the column guide's button should be a plain button at the search's end that names the closed guide it opens" {
      listOf(DashboardLayout.PATH, DashboardLayout.ACTIVITY).forEach { layout ->
        val form = renderFullPage(snapshot(), layout).substringAfter("""class="filters"""").substringBefore("</form>")

        form.trimEnd() shouldEndWith
          """<button type="button" class="guide-toggle" id="${ProxyDashboardHtml.COLUMN_GUIDE_TOGGLE_ID}" """ +
          """aria-expanded="false" aria-controls="${ProxyDashboardHtml.COLUMN_GUIDE_ID}">Column guide</button>"""
      }
    }

    // Closed on each page load, as the page renders it; the page's script opens it. The Agents view has no table.
    "the column guide should start hidden, with a close button, and the Agents page should have none" {
      listOf(DashboardLayout.PATH, DashboardLayout.ACTIVITY).forEach { layout ->
        renderFullPage(snapshot(), layout).apply {
          this shouldContain
            """<section class="column-guide" id="${ProxyDashboardHtml.COLUMN_GUIDE_ID}" aria-label="Column guide" """ +
            """hidden="hidden">"""
          this shouldContain """aria-label="Close the column guide""""
        }
      }
      // The script names the ids, so look for the elements.
      renderFullPage(snapshot()).apply {
        this shouldNotContain """id="${ProxyDashboardHtml.COLUMN_GUIDE_ID}""""
        this shouldNotContain """id="${ProxyDashboardHtml.COLUMN_GUIDE_TOGGLE_ID}""""
      }
    }

    "the column guide's button should take focus as the other controls do, and keep its icon in forced colours" {
      ProxyDashboardCss.CSS shouldContain ".close:focus-visible, .guide-toggle:focus-visible,"
      ProxyDashboardCss.CSS shouldContain ".close::before, .guide-toggle::before { forced-color-adjust:none;"
    }

    // ==================== Scrape now ====================

    // The id carries the path, which the page's script reads back out of it.
    "a live path should have a Scrape button, carrying its path in its id, when scrape-now is on" {
      pathPush([path("orders_metrics")], scrapeNowEnabled = true) shouldContain
        """<button type="button" class="scrape-now" id="${ProxyDashboardHtml.SCRAPE_ID_PREFIX}orders_metrics" """ +
        """aria-label="Scrape /orders_metrics now""""
    }

    // The proxy serves a departed path no more, so there is nothing to scrape.
    "a departed path, or any path with scrape-now off, should have no Scrape button" {
      pathPush([departedPathView()], scrapeNowEnabled = true) shouldNotContain """class="scrape-now""""
      pathPush() shouldNotContain """class="scrape-now""""
    }

    // The panel is outside the pushed table, so a push never clears a result someone is reading.
    "the Paths page should have a hidden result panel, naming the route to post to, outside the pushed table" {
      val page = renderFullPage(snapshot(scrapeNowEnabled = true), DashboardLayout.PATH)

      page shouldContain
        """<div class="scrape-result" id="${ProxyDashboardHtml.SCRAPE_RESULT_ID}" """ +
        """${ProxyDashboardHtml.SCRAPE_URL_ATTR}="/dashboard/scrape" hidden="hidden"></div>"""
      (
        page.indexOf("""id="${ProxyDashboardHtml.SCRAPE_RESULT_ID}"""") <
        page.indexOf("""id="${ProxyDashboardHtml.PATH_TABLE_ID}"""")
      ) shouldBe true
      renderFullPage(snapshot(), DashboardLayout.PATH) shouldNotContain
        """id="${ProxyDashboardHtml.SCRAPE_RESULT_ID}""""
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
