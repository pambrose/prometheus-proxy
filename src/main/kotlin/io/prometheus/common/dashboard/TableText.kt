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

package io.prometheus.common.dashboard

import io.prometheus.common.dashboard.DashboardPage.MATCH_COUNT_ATTR
import kotlinx.html.DIV
import kotlinx.html.THEAD
import kotlinx.html.ThScope
import kotlinx.html.div
import kotlinx.html.th
import kotlinx.html.tr

/**
 * What a filtered table says when it has no rows to show, and what its rows are: the dashboards' filtered tables differ
 * only in these, and in the rows themselves.
 *
 * @param noun what a row is, in the plural, for "Showing N of M <noun>"
 * @param empty with no rows at all
 * @param noProblems with "Problems only" on, no search, and nothing it keeps
 * @param noMatch with a filter that leaves nothing
 */
internal class TableText(
  val noun: String,
  val empty: String,
  val noProblems: String,
  val noMatch: String,
)

/**
 * One column of a filtered table: its header, and what the column guide says it holds.
 *
 * Each view builds its list of these once per render, and both the header row ([columnHeaders]) and the column guide
 * ([DashboardPage.renderFilters]) iterate it, so a column can't reach the header without an explanation, and the
 * guide lists a column exactly when the table shows it.
 *
 * @param label the header's text, which the guide names the column by
 * @param help one short sentence on what the column holds
 * @param numeric whether its values are compared down the column, so right-aligned with tabular figures
 * @param centered whether its cells are centered, as a column of marks is, so its header stands over them
 */
internal class TableColumn(
  val label: String,
  val help: String,
  val numeric: Boolean = false,
  val centered: Boolean = false,
)

/** The class that aligns a [TableColumn]'s header, and its cells, or null for the default, left. */
internal val TableColumn.alignment: String?
  get() =
    when {
      numeric -> "num"
      centered -> "center"
      else -> null
    }

/** The header row of a filtered table: a scoped `<th>` for each of [columns], in order. */
internal fun THEAD.columnHeaders(columns: List<TableColumn>) {
  tr {
    columns.forEach { column ->
      th(scope = ThScope.col, classes = column.alignment) { +column.label }
    }
  }
}

/**
 * The start of a filtered table's region, which every filtered table shares: the match count, which the page's
 * script says aloud once per change of filter, then the empty state when no row is left, or the "Showing N of M" line
 * when a filter hides some. Returns whether there are rows for the caller to render below it.
 *
 * Call it once the region's id is set and before any of its children: the attribute it sets must come before them,
 * since the HTML streams out as it is built.
 */
internal fun DIV.filteredTableStart(
  text: TableText,
  filter: RowFilter,
  shown: Int,
  total: Int,
): Boolean {
  val matchCount = "Showing $shown of $total ${text.noun}"
  attributes[MATCH_COUNT_ATTR] = matchCount
  val emptyState =
    when {
      total == 0 -> text.empty
      shown > 0 -> null
      filter.problemsOnly && filter.query.isEmpty() -> text.noProblems
      else -> text.noMatch
    }
  if (emptyState != null) {
    div("empty pad") { +emptyState }
    return false
  }
  if (filter.isActive) div("match-count") { +matchCount }
  return true
}
