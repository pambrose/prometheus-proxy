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

/**
 * Which rows the Paths view shows: those matching a search, and optionally only the problems.
 *
 * Applied on the server, to the table each push renders, rather than in the browser: the push replaces the table every
 * refresh, so a filter applied to the page would be undone by the next frame. It reaches the server two ways, which
 * carry the same two values: the page's URL (`/paths?q=team-a&problems=1`), so a filtered view can be bookmarked or
 * shared, and each WebSocket message, so a change applies at once without a reload.
 *
 * @param query matched case-insensitively against the path, its agents' names, its target URL, and its identity
 * @param problemsOnly keep only the paths [PathView.isProblem] picks out: a failing last scrape, or an agent gone
 */
internal data class PathFilter(
  val query: String = "",
  val problemsOnly: Boolean = false,
) {
  /** Whether any row could be hidden, which is when the table says how many it shows. */
  val isActive: Boolean get() = query.isNotEmpty() || problemsOnly

  fun matches(path: PathView): Boolean =
    (!problemsOnly || path.isProblem) &&
      (query.isEmpty() || path.searchFields().any { it.contains(query, ignoreCase = true) })

  private fun PathView.searchFields(): List<String> =
    listOf(path, targetUrl, identity) + agentNames + listOfNotNull(servingAgent, identityLabel(identity))

  companion object {
    val NONE = PathFilter()

    // A search box's worth: the query is browser input, on a port with no auth, matched against every row each push.
    internal const val MAX_QUERY_LENGTH = 200

    /** A filter from browser input, which may be absent, padded, or longer than any search needs. */
    fun of(
      query: String?,
      problemsOnly: Boolean,
    ) = PathFilter(query.orEmpty().trim().take(MAX_QUERY_LENGTH), problemsOnly)
  }
}
