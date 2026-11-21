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

import io.ktor.http.Parameters
import io.prometheus.common.AgentValues
import io.prometheus.common.dashboard.DashboardPage.PROBLEMS_ON
import io.prometheus.common.dashboard.DashboardPage.PROBLEMS_PARAM
import io.prometheus.common.dashboard.DashboardPage.QUERY_PARAM
import io.prometheus.proxy.ActivityEntry

/**
 * Which rows the Paths and Activity views show: those matching a search, and optionally only the problems.
 *
 * Applied on the server, to the table each push renders, rather than in the browser: the push replaces the table every
 * refresh, so a filter applied to the page would be undone by the next frame. It lives in the page's URL
 * (`/paths?q=team-a&problems=1`), so a filtered view can be bookmarked or shared, and reaches the server two ways,
 * both read by [of]: the URL a page is opened at, and that URL's query in each WebSocket message, so a change applies
 * at once without a reload.
 *
 * @param query matched case-insensitively against what a row shows: for a path, the path, its agents' names, its target
 *   URL, and its identity; for an activity line, the event, the agent's name, the identity, the paths, and the detail
 * @param problemsOnly keep only the problems: a path whose last scrape failed or whose agent has gone
 *   ([PathView.isProblem]), and an event an operator has to act on ([io.prometheus.proxy.ActivityKind.isProblem])
 */
internal data class RowFilter(
  val query: String = "",
  val problemsOnly: Boolean = false,
) {
  /** Whether any row could be hidden, which is when the table says how many it shows. */
  val isActive: Boolean get() = query.isNotEmpty() || problemsOnly

  fun matches(path: PathView): Boolean = matches(path.isProblem) { path.searchFields() }

  fun matches(entry: ActivityEntry): Boolean = matches(entry.kind.isProblem) { entry.searchFields() }

  // A row that is a problem, if only problems are kept, and that shows the query in one of its [fields], if there is
  // one.
  private fun matches(
    isProblem: Boolean,
    fields: () -> List<String>,
  ): Boolean =
    (!problemsOnly || isProblem) && (query.isEmpty() || fields().any { it.contains(query, ignoreCase = true) })

  // What the row shows: an identity as its label reads, and the agent the row names (servingAgent): the first of
  // agentNames, or for a departed path with none, the one its last scrape names.
  private fun PathView.searchFields(): List<String> =
    listOfNotNull(path, targetUrl, servingAgent, identityLabel(identity)) + agentNames

  private fun ActivityEntry.searchFields(): List<String> =
    listOfNotNull(kind.label, agentName, identityLabel(identity), detail) + paths

  companion object {
    val NONE = RowFilter()

    // A search box's worth: the query is browser input, on a port with no auth, matched against every row each push.
    internal const val MAX_QUERY_LENGTH = 200

    /** A filter from browser input, which may be absent, padded, or longer than any search needs. */
    fun of(
      query: String?,
      problemsOnly: Boolean,
    ) = RowFilter(AgentValues.truncate(query.orEmpty().trim(), MAX_QUERY_LENGTH), problemsOnly)

    /** A filter from a URL's query parameters, as the search form writes them. */
    fun of(parameters: Parameters) = of(parameters[QUERY_PARAM], parameters[PROBLEMS_PARAM] == PROBLEMS_ON)
  }
}
