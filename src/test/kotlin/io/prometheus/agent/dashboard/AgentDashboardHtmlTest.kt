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

package io.prometheus.agent.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.agent.AgentPathManager.RegistrationFailure
import io.prometheus.agent.AgentActivityEntry
import io.prometheus.agent.AgentActivityKind
import io.prometheus.agent.AgentScrapeRecord
import io.prometheus.common.dashboard.RowFilter
import io.prometheus.agent.PathSource
import io.prometheus.agent.ShownFailure
import io.prometheus.agent.dashboard.AgentDashboardHtml.renderPage
import io.prometheus.common.dashboard.DashboardLogo
import io.prometheus.common.dashboard.DashboardCss
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.Setting
import io.prometheus.common.dashboard.SettingsGroup
import io.prometheus.common.dashboard.SettingsHtml
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

/**
 * [AgentDashboardHtml]: the agent dashboard's markup, from snapshots built here. Every value a path, a proxy, or a
 * target supplies escaped; an en dash for a value never given; the empty and gone states; the regions a push carries
 * for each view; the title's count of problems; and when a refused path is tried again. The routes are covered live
 * in `AgentWebDashboardTest`.
 */
class AgentDashboardHtmlTest : StringSpec() {
  private val at = Instant.parse("2026-11-21T12:00:00Z")

  private fun snapshot(
    paths: List<AgentPathView> = emptyList(),
    connection: ConnectionView = ConnectionView(ConnectionState.CONNECTED, [EndpointView("proxy:50051", true)]),
    scrapeNow: Boolean = true,
    activity: List<AgentActivityEntry> = emptyList(),
  ) = AgentSnapshot(
    agentName = "agent-7",
    agentId = "12",
    launchId = "launch-1",
    uptime = 5.minutes,
    backlog = 0,
    connection = connection,
    paths = paths,
    discovery = null,
    settings = [SettingsGroup("Scraping", [Setting("Scrape timeout", "15s"), Setting("Trust store", "<JDK default>")])],
    pathHistorySize = 5,
    scrapeNowEnabled = scrapeNow,
    activity = activity,
    activityChangeCount = activity.size.toLong(),
  )

  private fun line(
    kind: AgentActivityKind,
    path: String = "",
    detail: String = "",
    count: Int = 1,
  ) = AgentActivityEntry(
    at,
    kind,
    if (path.isEmpty()) emptyList() else [path],
    count,
    detail,
    pathCount = if (path.isEmpty()) 0 else count,
  )

  private val activity =
    [
      line(AgentActivityKind.CONNECT_FAILED, detail = "proxy-a:50051: StatusException: UNAVAILABLE"),
      line(AgentActivityKind.FAILED_OVER, detail = "from proxy-a:50051 to proxy-b:50051", count = 3),
      line(AgentActivityKind.PATH_REFUSED, "<script>x</script>", "not authorized"),
      line(AgentActivityKind.PATH_REGISTERED, "orders", "static", count = 7),
    ]

  private fun activityPage(filter: RowFilter = RowFilter.NONE) =
    createHTML().html {
      renderPage(snapshot(activity = activity), AgentView(AgentLayout.ACTIVITY, filter = filter), "/dashboard")
    }

  private fun scrape(
    statusCode: Int = 200,
    bytes: Long = 4300,
    filteredBytes: Long = bytes,
    gzipped: Boolean = false,
    chunked: Boolean = false,
    failure: String = "",
    fromProxyDashboard: Boolean = false,
  ) = AgentScrapeRecord(
    statusCode = statusCode,
    outcome = if (statusCode == 200) "success" else "unsuccessful",
    durationMillis = 12,
    bytes = bytes,
    filteredBytes = filteredBytes,
    gzipped = gzipped,
    chunked = chunked,
    failure = failure,
    fromProxyDashboard = fromProxyDashboard,
    at = at,
  )

  private fun scraped(vararg history: AgentScrapeRecord) =
    AgentPathView(
      "orders",
      PathSource.STATIC,
      "http://orders/metrics",
      PathState.REGISTERED,
      history = history.toList(),
    )

  private fun page(
    snapshot: AgentSnapshot,
    selected: String? = null,
    layout: AgentLayout = AgentLayout.PATHS,
  ) = createHTML().html { renderPage(snapshot, AgentView(layout, selected), "/dashboard") }

  private fun refusal(
    reason: String = "Agent identity 'team_a' is not authorized to register path /x",
    retrying: Boolean = false,
  ) = RegistrationFailure(ShownFailure(reason, at), refused = true, retrying = retrying, retryAt = null)

  init {
    "a path, a reason, and a URL from outside should be escaped, never markup" {
      val hostile = "<script>alert(1)</script>"
      val snapshot =
        snapshot(
          [
            AgentPathView(
              path = hostile,
              source = PathSource.DISCOVERED,
              url = "http://t/$hostile",
              state = PathState.REFUSED,
              failure = refusal(reason = hostile),
            ),
          ],
        )
      listOf(page(snapshot), page(snapshot, selected = hostile)).forEach { html ->
        html shouldNotContain hostile
        html shouldContain "&lt;script&gt;"
      }
    }

    "a path refused for a cause that can't clear should say so, with the proxy's reason and when it's tried again" {
      val path =
        AgentPathView("orders", PathSource.STATIC, "http://orders/metrics", PathState.REFUSED, failure = refusal())
      page(snapshot([path]), selected = "orders").apply {
        this shouldContain "Refused by the proxy"
        this shouldContain "is not authorized to register path /x"
        this shouldContain "not until the agent reconnects"
        this shouldContain """<span class="led crit"></span>/orders<span class="tag crit">refused</span>"""
      }
    }

    // The proxy took nothing from the agent: it gave the path to another agent, which the agent waits out.
    "a path another agent took over should say so, with who holds it, as retrying" {
      val failure = refusal(reason = "Taken over by agent-east-copy", retrying = true).copy(takenOver = true)
      val path =
        AgentPathView("orders", PathSource.STATIC, "http://orders/metrics", PathState.RETRYING, failure = failure)
      page(snapshot([path]), selected = "orders").apply {
        this shouldContain "Taken over by another agent"
        this shouldNotContain "Refused by the proxy"
        this shouldContain "Taken over by agent-east-copy"
        this shouldContain """<span class="tag warn">retrying</span>"""
      }
    }

    "a retry's wait should read as a countdown, and one already due as the next retry" {
      val clock = TestTimeSource()
      val due = clock.markNow() + 90.seconds
      AgentDashboardHtml.nextTry(refusal(retrying = true).copy(retryAt = due)) shouldBe "in 1m"
      clock += 2.minutes
      AgentDashboardHtml.nextTry(refusal(retrying = true).copy(retryAt = due)) shouldBe "at the next retry"
      AgentDashboardHtml.nextTry(refusal(retrying = true)) shouldBe "at the next retry"
    }

    "a path with no labels should show an en dash, not an empty cell" {
      val path = AgentPathView("orders", PathSource.STATIC, "http://orders/metrics", PathState.REGISTERED)
      page(snapshot([path]), selected = "orders") shouldContain
        """<span class="k">Labels</span><span class="v">${DashboardPage.DASH}</span>"""
    }

    "with nothing selected, or a path no longer known, the detail pane should say so" {
      page(snapshot()) shouldContain "Select a path to see where it stands with the proxy"
      page(snapshot(), selected = "gone_metrics") shouldContain
        "/gone_metrics is no longer configured, discovered, or registered"
      page(snapshot()) shouldContain "No paths configured or discovered"
    }

    "a failing connection should show each endpoint's last error, and the endpoint being tried" {
      val connection =
        ConnectionView(
          ConnectionState.FAILING,
          [
            EndpointView(
              "proxy-a:50051",
              current = false,
              failure = ShownFailure("StatusException: UNAVAILABLE", at),
            ),
            EndpointView("proxy-b:50051", current = true),
          ],
        )
      page(snapshot(connection = connection), layout = AgentLayout.CONNECTION).apply {
        this shouldContain "failing · proxy-b:50051"
        this shouldContain """<span class="tag warn">failed over</span>"""
        this shouldContain "StatusException: UNAVAILABLE"
        this shouldContain """proxy-b:50051<span class="tag">trying</span>"""
      }
    }

    // The discovery file's last read, what it skipped, and its conflicts, on the Connection view.
    "with discovery on, the Connection view should show its file, its last read's failure, and its conflicts" {
      val discovery =
        DiscoveryView(
          file = "/etc/agent/targets.conf",
          intervalSecs = 30,
          failure = ShownFailure("ConfigException: line 3: expecting a value", at),
          entries = 2,
          skipped = ["no_url" to ""],
          collisions = ["orders_metrics"],
          duplicates = ["billing_metrics"],
        )
      createHTML().html {
        renderPage(snapshot().copy(discovery = discovery), AgentView(AgentLayout.CONNECTION), "/dashboard")
      }
        .apply {
          this shouldContain "/etc/agent/targets.conf"
          this shouldContain "failed, keeping the paths last read: ConfigException: line 3: expecting a value"
          this shouldContain "'no_url' ''"
          this shouldContain "/orders_metrics"
          this shouldContain "/billing_metrics"
        }
      createHTML().html {
        renderPage(
          snapshot().copy(discovery = DiscoveryView("/f", 30)),
          AgentView(AgentLayout.CONNECTION),
          "/dashboard",
        )
      } shouldContain """<span class="k">Last read</span><span class="v">ok</span>"""
    }

    "the title should count the paths refused or retrying, and a failing connection" {
      val refused = AgentPathView("a", PathSource.STATIC, "", PathState.REFUSED)
      val retrying = AgentPathView("b", PathSource.STATIC, "", PathState.RETRYING)
      val pending = AgentPathView("c", PathSource.STATIC, "", PathState.PENDING)
      AgentDashboardHtml.pageTitle(snapshot()) shouldBe "Agent: agent-7"
      AgentDashboardHtml.pageTitle(snapshot([refused, pending])) shouldBe "(1 problem) Agent: agent-7"
      AgentDashboardHtml.pageTitle(
        snapshot([refused, retrying], ConnectionView(ConnectionState.FAILING, [EndpointView("p:1", true)])),
      ) shouldBe "(3 problems) Agent: agent-7"
    }

    "a push should carry the status bar first, stamped, and the regions of the view the page shows" {
      val snapshot = snapshot([AgentPathView("orders", PathSource.STATIC, "", PathState.REGISTERED)])
      AgentDashboardHtml.pushFragment(snapshot, null, "/dashboard").apply {
        this shouldStartWith
          """<span class="bar-right" id="${DashboardPage.STATUS_ID}" hx-swap-oob="true" ${DashboardPage.VIEW_ATTR}="0""""
        this shouldNotContain AgentDashboardHtml.PATH_LIST_ID
      }
      AgentDashboardHtml.pushFragment(snapshot, AgentView(AgentLayout.PATHS, "orders", seq = 4), "/dashboard").apply {
        this shouldContain """${DashboardPage.VIEW_ATTR}="4""""
        this shouldContain """id="${AgentDashboardHtml.PATH_LIST_ID}" hx-swap-oob="true""""
        this shouldContain """id="${AgentDashboardHtml.DETAIL_ID}" hx-swap-oob="true""""
        this shouldNotContain AgentDashboardHtml.CONNECTION_ID
      }
      AgentDashboardHtml.pushFragment(snapshot, AgentView(AgentLayout.CONNECTION), "/dashboard").apply {
        this shouldContain """id="${AgentDashboardHtml.CONNECTION_ID}" hx-swap-oob="true""""
        this shouldNotContain AgentDashboardHtml.PATH_LIST_ID
      }
    }

    // Newest last on the strip, as the proxy's path table draws it, with the places of the scrapes still to come.
    "a path row should show its recent scrapes as a strip, and how its last one went" {
      page(snapshot([scraped(scrape(503), scrape(), scrape())])).apply {
        this shouldContain """<span class="hist" role="img" aria-label="2 of 3 recent scrapes succeeded""""
        this shouldContain """<i class="slot" style="--n:2"></i><i class="ok" style="--n:2"></i>"""
        this shouldContain """<i class="crit" style="--n:1"></i></span>66%"""
        // The last result first, then the strip: one line, in the list's narrow column.
        this shouldContain """<span class="host"><span class="pill crit">503</span> <span class="hist" """
      }
    }

    // Prometheus not scraping a registered path is news; an unregistered path can't be scraped, so says nothing.
    "a registered path not scraped yet should say so, and an unregistered one nothing" {
      val pending = AgentPathView("pending_metrics", PathSource.STATIC, "http://p/metrics", PathState.PENDING)
      page(snapshot([scraped(), pending])).apply {
        this shouldContain "not scraped since the agent started"
        Regex("not scraped since").findAll(this).count() shouldBe 1
      }
    }

    "the detail pane should list a path's recent scrapes, with what the agent did with each body" {
      val path =
        scraped(
          scrape(bytes = 90_000, filteredBytes = 30_000, gzipped = true, chunked = true, fromProxyDashboard = true),
          scrape(503, bytes = 0, failure = "Connection refused <b>"),
        )
      page(snapshot([path]), selected = "orders").apply {
        this shouldContain """<span>Recent scrapes</span><span>2</span>"""
        this shouldContain "success · 12 ms · 87.9 KB · 29.3 KB after the filter · gzipped · chunked"
        this shouldContain """<span class="tag">from the proxy's dashboard</span>"""
        this shouldContain "unsuccessful · 12 ms · Connection refused &lt;b&gt;"
        this shouldNotContain "<b>"
      }
      page(snapshot([scraped()]), selected = "orders") shouldContain "Not scraped since the agent started"
    }

    // The button sits in the detail pane, which every push replaces, and the panel outside every pushed region, so a
    // push never clears a result someone is reading. The page's script reads the path back out of the button's id.
    "with Scrape now on, the detail pane should have its button, and the Paths view its result panel" {
      page(snapshot([scraped()]), selected = "orders").apply {
        this shouldContain
          """<button type="button" class="scrape-now" id="${DashboardPage.SCRAPE_ID_PREFIX}orders" aria-label="Scrape /orders now""""
        this shouldContain
          """<div class="scrape-result" id="${DashboardPage.SCRAPE_RESULT_ID}" ${DashboardPage.SCRAPE_URL_ATTR}="/dashboard/scrape" hidden="hidden">"""
      }
      // The page's stylesheet and script name both classes, so only the markup is matched.
      val button = "class=\"scrape-now\""
      val panel = "id=\"${DashboardPage.SCRAPE_RESULT_ID}\""
      AgentDashboardHtml.pushFragment(snapshot([scraped()]), AgentView(selectedPath = "orders"), "/dashboard").apply {
        this shouldContain button
        this shouldNotContain panel
      }
      page(snapshot([scraped()], scrapeNow = false), selected = "orders").apply {
        this shouldNotContain button
        this shouldNotContain panel
      }
      page(snapshot([scraped()]), layout = AgentLayout.CONNECTION) shouldNotContain panel
    }

    // Newest first, a problem a crit tag and news a warn one, a run counted, and the search and column guide above.
    "the Activity view should list what happened, its problems in red, and a burst counted" {
      activityPage().apply {
        this shouldContain """<form action="/dashboard/activity" method="get" class="filters" role="search">"""
        this shouldContain "Filter by event, path, or detail"
        this shouldContain """<span class="tag crit">connect failed</span>"""
        this shouldContain """<span class="tag warn">failed over</span><span class="dim"> ×3</span>"""
        this shouldContain """registered<span class="dim"> ×7</span>"""
        // The path a burst names, and the count it doesn't.
        this shouldContain """/orders<span class="dim"> and 6 more</span>"""
        this shouldContain "&lt;script&gt;x&lt;/script&gt;"
        this shouldNotContain "<script>x</script>"
        listOf("Time", "Event", "Path", "Detail").forEach { this shouldContain "<dt>$it</dt>" }
        this.indexOf("connect failed") shouldBeLessThan this.indexOf(">registered<")
      }
    }

    "the Activity view should keep only the lines its filter matches, and say how many" {
      // The column guide names every kind, so only the table's own markup is matched.
      activityPage(RowFilter.of("orders", problemsOnly = false)).apply {
        this shouldContain "Showing 1 of 4 events"
        this shouldNotContain """<span class="tag crit">connect failed</span>"""
      }
      activityPage(RowFilter.of(null, problemsOnly = true)).apply {
        this shouldContain "Showing 2 of 4 events"
        this shouldNotContain """registered<span class="dim">"""
      }
      createHTML().html { renderPage(snapshot(), AgentView(AgentLayout.ACTIVITY), "/dashboard") } shouldContain
        "Nothing has happened yet"
    }

    // The Path cell writes a path with its slash, as the proxy and Prometheus do, so a search for it as shown finds it.
    "the Activity view's search should match a path with its slash, as its row shows it" {
      activityPage(RowFilter.of("/orders", problemsOnly = false)) shouldContain "Showing 1 of 4 events"
    }

    // dashboard-constraints.md: the header row and the column guide iterate one list, so a column can't reach the
    // header without an explanation.
    "the Activity view's column guide should explain each header, in the header's order" {
      val page = activityPage()
      val headers = Regex("<th[^>]*>([^<]*)</th>").findAll(page).map { it.groupValues[1] }.toList()
      val guide = Regex("<dt>([^<]*)</dt>").findAll(page).map { it.groupValues[1] }.toList()

      headers shouldBe ["Time", "Event", "Path", "Detail"]
      guide shouldBe headers
    }

    // dashboard-constraints.md: under the search, where no push reaches it, so it stays open as #activity is replaced.
    "the Activity view's column guide should follow the search, ahead of the pushed table, and no push carry it" {
      val page = activityPage()
      val guide = page.indexOf("""<section class="column-guide" id="${DashboardPage.COLUMN_GUIDE_ID}"""")

      (page.indexOf("</form>") in 0..<guide) shouldBe true
      (guide < page.indexOf("""id="${AgentDashboardHtml.ACTIVITY_ID}"""")) shouldBe true
      AgentLayout.entries.forEach { layout ->
        AgentDashboardHtml.pushFragment(snapshot(activity = activity), AgentView(layout), "/dashboard").apply {
          this shouldNotContain DashboardPage.COLUMN_GUIDE_ID
          this shouldNotContain "<dt>"
        }
      }
    }

    // dashboard-constraints.md: the page's one live region, which the page's script writes on a connection change or
    // the user's own act. In a pushed region it would re-announce itself on every frame.
    "every view should carry a polite live region, and no push should carry it" {
      AgentLayout.entries.forEach { layout ->
        val page = createHTML().html { renderPage(snapshot(activity = activity), AgentView(layout), "/dashboard") }
        val announcer = Regex("""<div [^>]*id="${DashboardPage.ANNOUNCER_ID}"[^>]*>""").find(page)?.value.orEmpty()

        announcer shouldContain """aria-live="polite""""
        announcer shouldContain """role="status""""
      }
      (AgentLayout.entries.map { AgentView(it, selectedPath = "orders") } + null).forEach { view ->
        AgentDashboardHtml.pushFragment(snapshot(activity = activity), view, "/dashboard") shouldNotContain
          DashboardPage.ANNOUNCER_ID
      }
    }

    // As on the proxy's: a frame carries the feed only when it differs from the one the page shows.
    "a push for the Activity view should carry the feed out of band, or the status bar alone when told to" {
      val view = AgentView(AgentLayout.ACTIVITY, seq = 4)
      AgentDashboardHtml.pushFragment(snapshot(activity = activity), view, "/dashboard").apply {
        this shouldStartWith """<span class="bar-right" id="${DashboardPage.STATUS_ID}" hx-swap-oob="true""""
        this shouldContain """id="${AgentDashboardHtml.ACTIVITY_ID}" hx-swap-oob="true""""
      }
      AgentDashboardHtml.pushFragment(snapshot(activity = activity), view, "/dashboard", changed = false)
        .apply { this shouldNotContain AgentDashboardHtml.ACTIVITY_ID }
    }

    // The settings moved from the bottom of the Connection view to a view of their own.
    "the Settings view should show each group's settings, escaped, and the Connection view none of them" {
      page(snapshot(), layout = AgentLayout.SETTINGS).apply {
        this shouldContain """<div id="${SettingsHtml.SETTINGS_ID}">"""
        this shouldContain """<div class="section"><span>Scraping</span></div>"""
        this shouldContain """<span class="k">Scrape timeout</span><span class="v">15s</span>"""
        this shouldContain "&lt;JDK default&gt;"
        this shouldContain """href="/dashboard/settings" class="tab-settings on" aria-current="page">Settings</a>"""
      }
      page(snapshot(), layout = AgentLayout.CONNECTION).apply {
        this shouldNotContain "Scrape timeout"
        this shouldNotContain """<span>Settings</span>"""
        this shouldContain """<a href="/dashboard/settings" class="tab-settings">Settings</a>"""
      }
      page(snapshot().copy(settings = emptyList()), layout = AgentLayout.SETTINGS) shouldContain "No settings to show"
    }

    // Each tab's icon comes from its class, so its name is its title alone, and each view's is its own.
    "every tab should carry its view's icon, a different one for each view" {
      val page = page(snapshot(), layout = AgentLayout.CONNECTION)
      AgentLayout.entries.map { it.icon }.toSet().size shouldBe AgentLayout.entries.size
      AgentLayout.entries.forEach { view ->
        page shouldContain Regex("""<a href="[^"]*" class="${view.icon.cssClass}( on)?"[^>]*>${view.title}</a>""")
      }
      page shouldContain """class="tab-connection on" aria-current="page">Connection</a>"""
    }

    // Sent once to a page, which then keeps them selectable; the frames after carry the status bar alone.
    "a push for the Settings view should carry the settings out of band, or the status bar alone when told to" {
      val view = AgentView(AgentLayout.SETTINGS, seq = 2)
      AgentDashboardHtml.pushFragment(snapshot(), view, "/dashboard").apply {
        this shouldStartWith
          """<span class="bar-right" id="${DashboardPage.STATUS_ID}" hx-swap-oob="true" ${DashboardPage.VIEW_ATTR}="2""""
        this shouldContain """<div id="${SettingsHtml.SETTINGS_ID}" hx-swap-oob="true">"""
        this shouldNotContain DashboardPage.ANNOUNCER_ID
      }
      AgentDashboardHtml.pushFragment(snapshot(), view, "/dashboard", changed = false).apply {
        this shouldNotContain SettingsHtml.SETTINGS_ID
        this shouldNotContain "Scrape timeout"
      }
      AgentDashboardHtml.pushFragment(snapshot(), AgentView(AgentLayout.CONNECTION), "/dashboard") shouldNotContain
        SettingsHtml.SETTINGS_ID
    }

    // Which agent this is, by the name its Settings view gives, so two agents' dashboards are told apart at a glance.
    // Outside every pushed region, so it goes out once, with the page, and no push carries its data URL.
    "the agent's bar should show the logo's small-size cut beside the agent's name, and no push should carry it" {
      page(snapshot()).replace(Regex(">\\s+<"), "><") shouldContain
        """<span class="brand"><img alt="" src="${DashboardLogo.SMALL_LOGO_URL}" """ +
        """width="${DashboardLogo.BRAND_LOGO_SIZE}" height="${DashboardLogo.BRAND_LOGO_SIZE}">""" +
        """<span class="brand-name">Agent: agent-7</span></span>"""
      AgentLayout.entries.forEach { layout ->
        AgentDashboardHtml.pushFragment(snapshot(), AgentView(layout), "/dashboard") shouldNotContain
          DashboardLogo.SMALL_LOGO_URL
      }
    }

    // An agent's name is its config's, any length: past the limit the bar cuts it, and its tooltip gives it whole.
    "a name too long for the bar should be cut there, and given whole in its tooltip" {
      val long = "a".repeat(DashboardLogo.BRAND_MAX_CHARS)
      page(snapshot().copy(agentName = long)) shouldContain """<span class="brand-name" title="Agent: $long">"""
      page(snapshot()) shouldContain """<span class="brand-name">"""
      DashboardCss.CSS shouldContain ".brand-name { max-width:${DashboardLogo.BRAND_MAX_CHARS}ch; overflow:hidden;"
    }

    "the agent's page should carry the logo as its icon, inline in its head" {
      page(snapshot()).substringBefore("</head>") shouldContain
        """<link href="${DashboardLogo.SMALL_LOGO_URL}" rel="icon" type="image/svg+xml">"""
    }

    "the agent's page should apply a stored theme in its head, with the theme button in its bar and in no push" {
      page(snapshot()).apply {
        substringBefore("</head>") shouldContain DashboardPage.THEME_SCRIPT
        this shouldContain """<button type="button" class="theme" id="${DashboardPage.THEME_BUTTON_ID}""""
      }
      AgentDashboardHtml.pushFragment(snapshot(), AgentView(), "/dashboard") shouldNotContain
        DashboardPage.THEME_BUTTON_ID
    }

    "a path row should select it by its URL, encoded, and the page's script should know the agent's views" {
      val snapshot = snapshot([AgentPathView("a b", PathSource.STATIC, "", PathState.PENDING)])
      page(snapshot).apply {
        this shouldContain """hx-get="/dashboard/paths/a%20b""""
        this shouldContain """var LAYOUTS = {"connection":"CONNECTION","activity":"ACTIVITY","settings":"SETTINGS"};"""
        this shouldContain "Connection to the agent lost. Reconnecting."
        this shouldContain "if (window.__agentUiInit) { window.__agentUiInit(); return; }"
      }
    }
  }
}
