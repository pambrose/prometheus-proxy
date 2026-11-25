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

import io.prometheus.agent.AgentActivityEntry
import io.prometheus.agent.AgentActivityKind
import io.prometheus.agent.AgentActivityLog
import io.prometheus.common.dashboard.DashboardPage.DASH
import io.prometheus.common.dashboard.DashboardPage.renderFilters
import io.prometheus.common.dashboard.DashboardPage.timeOfDay
import io.prometheus.common.dashboard.RowFilter
import io.prometheus.common.dashboard.RowFilter.Companion.pathAsShown
import io.prometheus.common.dashboard.TableColumn
import io.prometheus.common.dashboard.TableText
import io.prometheus.common.dashboard.columnHeaders
import io.prometheus.common.dashboard.filteredTableStart
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
import io.prometheus.common.dashboard.activityEventCell
import io.prometheus.common.dashboard.activityPathsCell

/**
 * Whether the agent's Activity view shows [entry]: its event, its paths, with their slashes, and its detail are what it
 * shows, and it is a problem when an operator has to act on it ([AgentActivityKind.isProblem]).
 */
internal fun RowFilter.matches(entry: AgentActivityEntry): Boolean =
  matches(entry.kind.isProblem) { listOf(entry.kind.label, entry.detail) + entry.paths.map(::pathAsShown) }

/**
 * The agent dashboard's Activity view: what happened to the agent's connection, paths, discovery, and filters, newest
 * first, with the search above, on the table the proxy's Activity view is built from. Flat, so the whole table is one
 * out-of-band swap.
 *
 * The lines come from [AgentActivityLog], which keeps them bounded and folds a burst or a repeat into one.
 */
internal object AgentActivityHtml {
  /** The search and the table, as the page first renders them; see `DashboardPage.renderFilters`. */
  fun FlowContent.renderActivity(
    snapshot: AgentSnapshot,
    routeBase: String,
    filter: RowFilter,
  ) {
    renderFilters(routeBase, AgentLayout.ACTIVITY, filter, COLUMNS)
    // Inside the scroller, as the proxy's table is, so a table scrolled sideways keeps its place on a push.
    div("scroller") { div { activityTable(snapshot, filter, oob = false) } }
  }

  /** The table region, `#activity`, which a push for the Activity view replaces. */
  fun DIV.activityTable(
    snapshot: AgentSnapshot,
    filter: RowFilter,
    oob: Boolean,
  ) {
    attributes["id"] = AgentDashboardHtml.ACTIVITY_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    val rows = if (filter.isActive) snapshot.activity.filter(filter::matches) else snapshot.activity
    if (filteredTableStart(TEXT, filter, rows.size, snapshot.activity.size)) {
      table("ptable") {
        caption("sr-only") { +"What happened to the agent's connection, paths, discovery, and filters, newest first" }
        thead { columnHeaders(COLUMNS) }
        tbody { rows.forEach { activityRow(it) } }
      }
    }
  }

  private val TEXT =
    TableText(
      noun = "events",
      empty = "Nothing has happened yet: the agent connecting, its paths registering, and anything refused show here",
      noProblems = "No problems: nothing refused, failing, or let through unfiltered",
      noMatch = "No activity matches the filter",
    )

  // Read from AgentActivityKind, so a new kind can't be left out of the guide.
  private val PROBLEM_KINDS = AgentActivityKind.entries.filter { it.isProblem }.joinToString { it.label }
  private val NOTICE_KINDS = AgentActivityKind.entries.filter { it.isNotice }.joinToString { it.label }

  // The table's columns, in order, for its header row and its column guide alike; activityRow renders a cell for each.
  private val COLUMNS =
    listOf(
      TableColumn(
        label = "Time",
        help = "When it happened, or a run of like events began, in your time zone; hover over it for the date.",
      ),
      TableColumn(
        label = "Event",
        help = "What happened; problems are red ($PROBLEM_KINDS), $NOTICE_KINDS amber, and ×N counts a run of " +
          "like events.",
      ),
      TableColumn(
        label = "Path",
        help = "The paths it concerns; a run names its first ${AgentActivityLog.MAX_SAMPLE_PATHS} and counts the rest.",
      ),
      TableColumn(
        label = "Detail",
        help = "What else to know, such as the endpoint, why a path was refused, or why a connect failed.",
      ),
    )

  private fun TBODY.activityRow(entry: AgentActivityEntry) {
    tr {
      td { timeOfDay(entry.at) }
      activityEventCell(entry.kind.label, entry.kind.isProblem, entry.kind.isNotice, entry.count)
      activityPathsCell(entry.paths, entry.pathCount - entry.paths.size)
      // The one column that wraps: a refusal's reason is a sentence.
      td("dim wrap") { +entry.detail.ifEmpty { DASH } }
    }
  }
}
