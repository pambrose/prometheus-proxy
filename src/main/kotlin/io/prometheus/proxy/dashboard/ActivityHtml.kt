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

import io.prometheus.common.dashboard.DashboardPage.DASH
import io.prometheus.common.dashboard.DashboardPage.timeOfDay
import io.prometheus.proxy.ActivityEntry
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.ActivityLog
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.ACTIVITY_ID
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.agentLabel
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr
import io.prometheus.common.dashboard.RowFilter
import io.prometheus.common.dashboard.TableColumn
import io.prometheus.common.dashboard.TableText
import io.prometheus.common.dashboard.columnHeaders
import io.prometheus.common.dashboard.filteredTableStart
import io.prometheus.common.dashboard.DashboardPage.renderFilters
import io.prometheus.common.dashboard.activityEventCell
import io.prometheus.common.dashboard.activityPathsCell

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
  /** The search and the table, as the page first renders them; see `DashboardPage.renderFilters`. */
  fun FlowContent.renderActivity(
    snapshot: ProxySnapshot,
    routeBase: String,
    filter: RowFilter,
  ) {
    renderFilters(routeBase, DashboardLayout.ACTIVITY, filter, columns(snapshot.authEnabled))
    // Inside the scroller, as the Paths table's region is, so a table scrolled sideways keeps its place on a push.
    div("scroller") { div { activityTable(snapshot, filter, oob = false) } }
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
      thead { columnHeaders(columns(showIdentity)) }
      tbody { rows.forEach { activityRow(it, showIdentity) } }
    }
  }

  // The table's columns, in order, for its header row and its column guide alike; activityRow renders a cell for each.
  private fun columns(showIdentity: Boolean): List<TableColumn> =
    listOfNotNull(TIME, EVENT, AGENT, IDENTITY.takeIf { showIdentity }, PATH, DETAIL)

  // What the column guide says of each column: one short sentence, in the words the website's dashboard page uses.
  private val TIME =
    TableColumn(
      label = "Time",
      help = "When it happened, or a burst began, in your time zone; hover over it for the date.",
    )

  // Read from ActivityKind, so a new problem or notice kind can't be left out of the guide.
  private val PROBLEM_KINDS = ActivityKind.entries.filter { it.isProblem }.joinToString { it.label }
  private val NOTICE_KINDS = ActivityKind.entries.filter { it.isNotice }.joinToString { it.label }

  // The tags activityEventCell draws: a problem's crit, a take-over's warn, and a burst's count.
  private val EVENT =
    TableColumn(
      label = "Event",
      help = "What happened; problems are red ($PROBLEM_KINDS), $NOTICE_KINDS is amber, " +
        "and ×N counts a burst of like events.",
    )
  private val AGENT =
    TableColumn(
      label = "Agent",
      help = "The agent it concerns, named as the Agents view names it, or a dash for none, as with a bad token.",
    )
  private val IDENTITY =
    TableColumn(
      label = "Identity",
      help = "The auth identity the agent acted as; the legacy agentToken reads shared token.",
    )
  private val PATH =
    TableColumn(
      label = "Path",
      help = "The paths it concerns; a burst names its first ${ActivityLog.MAX_SAMPLE_PATHS} and counts the rest.",
    )
  private val DETAIL =
    TableColumn(
      label = "Detail",
      help = "What else to know, such as why a path was rejected or where a bad token came from.",
    )

  // Block bodies throughout, as in PathTableHtml: CodeScene counts the conditionals inside an expression-bodied
  // function's trailing lambda as Global Conditionals.
  private fun TBODY.activityRow(
    entry: ActivityEntry,
    showIdentity: Boolean,
  ) {
    tr {
      td { timeOfDay(entry.at) }
      activityEventCell(entry.kind.label, entry.kind.isProblem, entry.kind.isNotice, entry.count)
      // Named as the other views name it, so a line can be read against the agent list.
      td { +(entry.agentName.takeIf { it.isNotEmpty() }?.let(::agentLabel) ?: DASH) }
      if (showIdentity) td("dim") { +(identityLabel(entry.identity) ?: DASH) }
      activityPathsCell(entry.paths, entry.pathCount - entry.paths.size)
      // The one column that wraps: a rejection's reason is a sentence.
      td("dim wrap") { +entry.detail.ifEmpty { DASH } }
    }
  }
}
