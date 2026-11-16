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
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.proxy.ActivityEntry
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.dashboard.DashboardFixtures.activityEntry
import io.prometheus.proxy.dashboard.DashboardFixtures.renderFullPage

/**
 * The Activity view, [ActivityHtml]: the feed's table, its tags and bursts, its Identity column, its search and empty
 * states, and the push that carries it.
 */
class ActivityHtmlTest : StringSpec() {
  private fun snapshot(
    activity: List<ActivityEntry> = [activityEntry()],
    authEnabled: Boolean = false,
  ) = DashboardFixtures.snapshot(activity = activity, authEnabled = authEnabled)

  // The Activity view's push for these lines, as a session with this filter receives it.
  private fun activityPush(
    activity: List<ActivityEntry> = [activityEntry()],
    filter: RowFilter = RowFilter.NONE,
    authEnabled: Boolean = false,
  ): String {
    val snapshot = snapshot(activity, authEnabled)
    return ProxyDashboardHtml.pushFragment(snapshot, null, "/dashboard", DashboardLayout.ACTIVITY, 0, filter)
  }

  // The cells of the table's first body row, as text.
  private fun firstRow(html: String) =
    html.substringAfter("<tbody>").substringBefore("</tr>").split("</td>").dropLast(1).map {
      it.replace(Regex("<[^>]+>"), "").trim()
    }

  private fun headers(html: String) = Regex("<th[^>]*>([^<]*)</th>").findAll(html).map { it.groupValues[1] }.toList()

  // The column guide's names, and the explanation of the one named [name], as the Activity page renders them.
  private fun guideNames(page: String) = Regex("<dt>([^<]*)</dt>").findAll(page).map { it.groupValues[1] }.toList()

  private fun guideHelp(
    page: String,
    name: String,
  ) = Regex("""<dt>$name</dt>\s*<dd>([^<]*)</dd>""").find(page)?.groupValues?.get(1).orEmpty()

  private fun activityPage(authEnabled: Boolean = false) =
    renderFullPage(snapshot(authEnabled = authEnabled), DashboardLayout.ACTIVITY)

  init {
    "the activity table should show each line's time, event, agent, path, and detail, newest first" {
      val html =
        activityPush(
          [
            activityEntry(ActivityKind.AGENT_CONNECTED, paths = emptyList(), detail = "From 10.0.1.14:5555"),
            activityEntry(ActivityKind.PATH_REGISTERED, agentName = "agent-west", paths = ["orders_metrics"]),
          ],
        )

      headers(html) shouldBe ["Time", "Event", "Agent", "Path", "Detail"]
      html shouldContain "<time datetime="
      firstRow(html).drop(1) shouldBe ["connected", "agent-east", ProxyDashboardHtml.DASH, "From 10.0.1.14:5555"]
      (html.indexOf("From 10.0.1.14:5555") < html.indexOf("/orders_metrics")) shouldBe true
    }

    // A take-over is news but no fault: a redeploy takes over every path its old agent held.
    "a problem should be a crit tag, a take-over a warn tag, and the ordinary rest plain text" {
      activityPush([activityEntry(ActivityKind.PATH_REJECTED)]) shouldContain
        """<td class="event"><span class="tag crit">rejected</span></td>"""
      activityPush([activityEntry(ActivityKind.PATH_TAKEN_OVER)]) shouldContain
        """<td class="event"><span class="tag warn">taken over</span></td>"""
      activityPush([activityEntry(ActivityKind.PATH_REGISTERED)]) shouldContain """<td class="event">registered</td>"""
    }

    "a burst should say how many events it folded and name its first paths" {
      val burst = activityEntry(paths = ["a_metrics", "b_metrics", "c_metrics"], count = 40)

      firstRow(activityPush([burst])).take(4).drop(1) shouldBe
        ["registered ×40", "agent-east", "/a_metrics, /b_metrics, /c_metrics and 37 more"]
    }

    "a line with no agent should show a dash for it" {
      val badToken = activityEntry(ActivityKind.BAD_TOKEN, agentName = "", paths = emptyList())

      firstRow(activityPush([badToken]))[2] shouldBe ProxyDashboardHtml.DASH
    }

    "the Identity column should show while agent auth is on, the legacy token as a shared token" {
      val lines = [activityEntry(identity = "east"), activityEntry(identity = "legacy-agent-token")]

      activityPush(lines, authEnabled = true).apply {
        headers(this) shouldBe ["Time", "Event", "Agent", "Identity", "Path", "Detail"]
        this shouldContain """<td class="dim">east</td>"""
        this shouldContain """<td class="dim">shared token</td>"""
      }
      activityPush(lines) shouldNotContain "Identity</th>"
    }

    "an agent-supplied value should be escaped" {
      activityPush([activityEntry(agentName = "<script>x</script>", detail = "<b>bold</b>")]).apply {
        this shouldNotContain "<script>x"
        this shouldNotContain "<b>bold"
      }
    }

    // ==================== Search and empty states ====================

    "a filter should keep the matching lines and say how many it shows" {
      val lines =
        [
          activityEntry(ActivityKind.PATH_REJECTED, paths = ["team_b_metrics"], detail = "not authorized"),
          activityEntry(paths = ["orders_metrics"]),
          activityEntry(ActivityKind.AGENT_CONNECTED, paths = emptyList()),
        ]

      activityPush(lines, RowFilter.of("team_b", problemsOnly = false)).apply {
        this shouldContain "/team_b_metrics"
        this shouldNotContain "/orders_metrics"
        this shouldContain """<div class="match-count">Showing 1 of 3 events</div>"""
        this shouldContain """${ProxyDashboardHtml.MATCH_COUNT_ATTR}="Showing 1 of 3 events""""
      }
      activityPush(lines, RowFilter.of(null, problemsOnly = true)).apply {
        this shouldContain "rejected"
        this shouldNotContain "/orders_metrics"
      }
    }

    "an empty feed, a feed with no problems, and a filter with no match should each say so" {
      activityPush(emptyList()) shouldContain "No activity yet"
      activityPush(filter = RowFilter.of(null, problemsOnly = true)) shouldContain
        "No problems: nothing refused, rejected, or evicted"
      activityPush(filter = RowFilter.of("nothing-like-this", problemsOnly = false)) shouldContain
        "No activity matches the filter"
    }

    "the Activity page should render the search at its own route, outside the pushed table" {
      val page = renderFullPage(snapshot(), DashboardLayout.ACTIVITY, filter = RowFilter.of("east", false))

      page shouldContain """<form action="/dashboard/activity" method="get" class="filters" role="search">"""
      page shouldContain """aria-label="Filter activity""""
      page shouldContain """value="east""""
      (page.indexOf("<form action") < page.indexOf("""id="${ProxyDashboardHtml.ACTIVITY_ID}"""")) shouldBe true
    }

    // ==================== Column guide ====================

    // The header row and the guide iterate one list, so the guide lists a column exactly when the table shows it.
    "the column guide should explain every header, in the header's order, Identity only with agent auth on" {
      listOf(false, true).forEach { authEnabled ->
        val page = activityPage(authEnabled)

        guideNames(page) shouldBe headers(page)
        guideNames(page).forEach { guideHelp(page, it).isNotBlank() shouldBe true }
      }
      guideNames(activityPage()) shouldBe ["Time", "Event", "Agent", "Path", "Detail"]
      guideNames(activityPage(authEnabled = true)) shouldBe ["Time", "Event", "Agent", "Identity", "Path", "Detail"]
    }

    // Read from ActivityKind, so a new problem kind can't be left out of the guide.
    "the Event column's explanation should name every problem kind, the take-over, and a burst's count" {
      val event = guideHelp(activityPage(), "Event")

      ActivityKind.entries.filter { it.isProblem }.forEach { event shouldContain it.label }
      event shouldContain ActivityKind.PATH_TAKEN_OVER.label
      event shouldContain "×N"
    }

    "the column guide should follow the search, ahead of the pushed table, and no push carry it" {
      val page = activityPage()
      val guide = page.indexOf("""<section class="column-guide" id="${ProxyDashboardHtml.COLUMN_GUIDE_ID}"""")

      (page.indexOf("</form>") in 0..<guide) shouldBe true
      (guide < page.indexOf("""id="${ProxyDashboardHtml.ACTIVITY_ID}"""")) shouldBe true
      activityPush(authEnabled = true).apply {
        this shouldNotContain ProxyDashboardHtml.COLUMN_GUIDE_ID
        this shouldNotContain "<dt>"
      }
    }

    // ==================== The push ====================

    "a push for the Activity view should carry the status bar and the activity table, out of band, and nothing else" {
      val html = activityPush()

      html shouldContain """id="${ProxyDashboardHtml.ACTIVITY_ID}" hx-swap-oob="true""""
      html shouldNotContain ProxyDashboardHtml.PATH_TABLE_ID
      html shouldNotContain ProxyDashboardHtml.AGENT_LIST_ID
      html shouldContain ProxyDashboardHtml.STATUS_ID
    }

    // The push loop leaves the table out when the page already shows it. The frame is then the status bar alone, still
    // its first tag, so the page's stale-frame filter reads its stamp as before; the other layouts' regions don't
    // depend on the flag.
    "a push without the activity table should carry the status bar alone, and leave the other layouts' regions" {
      val snap = snapshot()
      val bare =
        ProxyDashboardHtml.pushFragment(snap, null, "/dashboard", DashboardLayout.ACTIVITY, 7, withActivity = false)

      bare shouldStartWith """<span class="bar-right" id="${ProxyDashboardHtml.STATUS_ID}" hx-swap-oob="true" """
      bare shouldContain """${ProxyDashboardHtml.VIEW_ATTR}="7""""
      bare shouldNotContain ProxyDashboardHtml.ACTIVITY_ID
      bare shouldEndWith "</span>"
      listOf(DashboardLayout.AGENT, DashboardLayout.PATH).forEach { layout ->
        ProxyDashboardHtml.pushFragment(snap, null, "/dashboard", layout, withActivity = false) shouldBe
          ProxyDashboardHtml.pushFragment(snap, null, "/dashboard", layout)
      }
    }

    "the Detail column should wrap where the rest of the row doesn't" {
      ProxyDashboardCss.CSS shouldContain ".ptable td.wrap { white-space:normal;"
    }
  }
}
