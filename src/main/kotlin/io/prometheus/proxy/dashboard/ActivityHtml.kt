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

import io.prometheus.proxy.ActivityEntry
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.ACTIVITY_ID
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.DASH
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.agentLabel
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.renderFilters
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.scrapeTime
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.ThScope
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * The Activity view: the proxy's recent comings and goings, newest first, with the search above them.
 *
 * Where the other views show what is, this shows what happened, which is the only place a refusal shows at all: a path
 * an identity may not register, a connect over an identity's limit, or a bad token leaves nothing in the Agents or
 * Paths view, since nothing was added. Flat, like the Paths view, so the whole table is one out-of-band swap.
 *
 * The lines come from [io.prometheus.proxy.ActivityLog], which keeps them bounded and folds a burst into one.
 */
internal object ActivityHtml {
  /** The search and the table, as the page first renders them; see [ProxyDashboardHtml.renderFilters]. */
  fun FlowContent.renderActivity(
    snapshot: ProxySnapshot,
    routeBase: String,
    filter: RowFilter,
  ) {
    renderFilters(routeBase, DashboardLayout.ACTIVITY, filter)
    div("scroller") { activityTable(snapshot, filter, oob = false) }
  }

  /** The table region, `#activity`, which every push for the Activity view replaces. */
  fun DIV.activityTable(
    snapshot: ProxySnapshot,
    filter: RowFilter,
    oob: Boolean,
  ) {
    attributes["id"] = ACTIVITY_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    val rows = if (filter.isActive) snapshot.activity.filter(filter::matches) else snapshot.activity
    if (filteredTableStart(TEXT, filter, rows.size, snapshot.activity.size)) activityRows(snapshot, rows)
  }

  private val TEXT =
    TableText(
      noun = "events",
      empty = "No activity yet: agents connecting, paths registering, and anything refused show here",
      noProblems = "No problems: nothing refused, rejected, or evicted",
      noMatch = "No activity matches the filter",
    )

  private fun DIV.activityRows(
    snapshot: ProxySnapshot,
    rows: List<ActivityEntry>,
  ) {
    // As in the Paths view: from the config, so the column doesn't come and go as the lines do.
    val showIdentity = snapshot.authEnabled
    table("ptable") {
      caption("sr-only") { +"Agents and paths coming, going, and refused, newest first" }
      thead { headerRow(showIdentity) }
      tbody { rows.forEach { activityRow(it, showIdentity) } }
    }
  }

  private fun THEAD.headerRow(showIdentity: Boolean) {
    tr {
      th(scope = ThScope.col) { +"Time" }
      th(scope = ThScope.col) { +"Event" }
      th(scope = ThScope.col) { +"Agent" }
      if (showIdentity) th(scope = ThScope.col) { +"Identity" }
      th(scope = ThScope.col) { +"Path" }
      th(scope = ThScope.col) { +"Detail" }
    }
  }

  // Block bodies throughout, as in PathTableHtml: CodeScene counts the conditionals inside an expression-bodied
  // function's trailing lambda as Global Conditionals.
  private fun TBODY.activityRow(
    entry: ActivityEntry,
    showIdentity: Boolean,
  ) {
    tr {
      td { scrapeTime(entry.at) }
      eventCell(entry)
      // Named as the other views name it, so a line can be read against the agent list.
      td { +(entry.agentName.takeIf { it.isNotEmpty() }?.let(::agentLabel) ?: DASH) }
      if (showIdentity) td("dim") { +(identityLabel(entry.identity) ?: DASH) }
      pathsCell(entry)
      // The one column that wraps: a rejection's reason is a sentence.
      td("dim wrap") { +entry.detail.ifEmpty { DASH } }
    }
  }

  // A problem is a crit tag and a take-over a warn one, which is news but not a fault: a redeploy takes over every path
  // its old agent held. The rest is plain text, the ordinary coming and going. A burst says how many it folded.
  private fun TR.eventCell(entry: ActivityEntry) {
    td("event") {
      val kind = entry.kind
      when {
        kind.isProblem -> span("tag crit") { +kind.label }
        kind == ActivityKind.PATH_TAKEN_OVER -> span("tag warn") { +kind.label }
        else -> +kind.label
      }
      if (entry.count > 1) span("dim") { +" ×${entry.count}" }
    }
  }

  // The paths a line concerns: a burst names its first few and counts the rest.
  private fun TR.pathsCell(entry: ActivityEntry) {
    td {
      if (entry.paths.isEmpty()) {
        +DASH
      } else {
        +entry.paths.joinToString(", ") { "/$it" }
        val more = entry.count - entry.paths.size
        if (more > 0) span("dim") { +" and $more more" }
      }
    }
  }
}
