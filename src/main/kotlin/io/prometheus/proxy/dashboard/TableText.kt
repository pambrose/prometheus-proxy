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

import io.prometheus.proxy.dashboard.ProxyDashboardHtml.MATCH_COUNT_ATTR
import kotlinx.html.DIV
import kotlinx.html.div

/**
 * What a filtered table says when it has no rows to show, and what its rows are: the Paths and Activity views' tables
 * differ only in these, and in the rows themselves.
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
 * The start of a filtered table's region, which the Paths and Activity views share: the match count, which the page's
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
