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

import io.ktor.http.Parameters
import io.ktor.http.parseQueryString
import io.prometheus.common.AgentValues
import io.prometheus.common.dashboard.DashboardPage.PROBLEMS_ON
import io.prometheus.common.dashboard.DashboardPage.PROBLEMS_PARAM
import io.prometheus.common.dashboard.DashboardPage.QUERY_PARAM

/**
 * Which rows a dashboard's filtered table shows, the proxy's Paths and Activity views' and the agent's Activity view's:
 * those matching a search, and optionally only the problems. Each dashboard says how a row of its own matches, by what
 * the row shows and whether it is a problem ([matches]).
 *
 * Applied on the server, to the table each push renders, rather than in the browser: the push replaces the table every
 * refresh, so a filter applied to the page would be undone by the next frame. It lives in the page's URL
 * (`/paths?q=team-a&problems=1`), so a filtered view can be bookmarked or shared, and reaches the server two ways,
 * both read by [of]: the URL a page is opened at, and that URL's query in each WebSocket message, so a change applies
 * at once without a reload.
 *
 * @param query matched case-insensitively against what a row shows
 * @param problemsOnly keep only the problems, the rows an operator has to act on
 */
internal data class RowFilter(
  val query: String = "",
  val problemsOnly: Boolean = false,
) {
  /** Whether any row could be hidden, which is when the table says how many it shows. */
  val isActive: Boolean get() = query.isNotEmpty() || problemsOnly

  /**
   * Whether a row is shown: one that is a problem, [isProblem], if only problems are kept, and that shows the query in
   * one of its [fields], what the row shows, if there is one.
   */
  fun matches(
    isProblem: Boolean,
    fields: () -> List<String>,
  ): Boolean =
    (!problemsOnly || isProblem) && (query.isEmpty() || fields().any { it.contains(query, ignoreCase = true) })

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

    /**
     * Reads `{"search": "?q=team-a&problems=1"}`, the query of the page's URL, which holds the view's filter, from a
     * session message, defaulting to [NONE].
     *
     * The URL's own query rather than fields of its own, so the filter has one format, which the view's route reads
     * too. Same contract as [DashboardMessage.selection]: anything unexpected -- garbage, a missing field, a value of
     * the wrong type, or a query that won't decode -- resolves to no filter rather than throwing inside the WebSocket
     * loop.
     */
    fun fromMessage(text: String): RowFilter =
      DashboardMessage.search(text)
        ?.let { runCatching { of(parseQueryString(it.removePrefix("?"))) }.getOrNull() }
        ?: NONE
  }
}
