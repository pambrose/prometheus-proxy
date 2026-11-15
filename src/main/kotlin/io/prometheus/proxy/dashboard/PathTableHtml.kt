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

import io.prometheus.proxy.dashboard.ProxyDashboardHtml.COPY_ATTR
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.COPY_PROXY_ID_PREFIX
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.COPY_TARGET_ID_PREFIX
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.DASH
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.PATH_TABLE_ID
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.PROXY_PATH_ATTR
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.SCRAPE_PORT_ATTR
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.agentLabel
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.scrapeTime
import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.InputType
import kotlinx.html.TBODY
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.ThScope
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.i
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr

/**
 * The Paths view: every path on one row, joined with its recent scrapes, and the search above it.
 *
 * Flat on purpose: no selection and no drill-in, so the whole table is a single out-of-band swap and the push path
 * stays the cheapest of any layout. The row IS the answer, which is the reason to have this view at all.
 *
 * Apart from [ProxyDashboardHtml], which renders the page around it and the agent view, and shares with this the
 * helpers both layouts need: [agentLabel], which both must name an agent through, and [scrapeTime].
 */
internal object PathTableHtml {
  const val FILTER_QUERY_ID = "path-q"
  const val FILTER_PROBLEMS_ID = "path-problems"

  // On #path-table, for the page's script, which says how many rows a new filter left.
  const val SHOWN_ATTR = "data-shown"
  const val TOTAL_ATTR = "data-total"

  /**
   * The search and the table, as the page first renders them.
   *
   * The search sits outside the table's out-of-band region, so a push never replaces the input someone is typing in.
   * It is a plain GET form, so it works without the page's script, by navigating to `/paths?q=…&problems=1`; the
   * script applies a change as it is typed instead, and keeps the URL in step (see ProxyDashboardHtml's SELECTION_JS).
   */
  fun FlowContent.renderPathTable(
    snapshot: ProxySnapshot,
    routeBase: String,
    filter: PathFilter,
  ) {
    form(action = "$routeBase/paths", method = FormMethod.get, classes = "filters") {
      attributes["role"] = "search"
      input(type = InputType.search, name = "q") {
        attributes["id"] = FILTER_QUERY_ID
        attributes["aria-label"] = "Filter paths"
        attributes["placeholder"] = "Filter by path, agent, target, or identity"
        attributes["maxlength"] = "${PathFilter.MAX_QUERY_LENGTH}"
        attributes["autocomplete"] = "off"
        value = filter.query
      }
      label {
        input(type = InputType.checkBox, name = "problems") {
          attributes["id"] = FILTER_PROBLEMS_ID
          value = "1"
          checked = filter.problemsOnly
        }
        +"Problems only"
      }
    }
    div("scroller") { pathTable(snapshot, filter, oob = false) }
  }

  /** The table region, `#path-table`, which every push for the Paths view replaces. */
  fun DIV.pathTable(
    snapshot: ProxySnapshot,
    filter: PathFilter,
    oob: Boolean,
  ) {
    attributes["id"] = PATH_TABLE_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    val rows = snapshot.paths.filter(filter::matches)
    attributes[SHOWN_ATTR] = "${rows.size}"
    attributes[TOTAL_ATTR] = "${snapshot.paths.size}"
    when {
      snapshot.paths.isEmpty() -> {
        div("empty pad") { +"No paths registered" }
      }

      rows.isEmpty() && filter.problemsOnly && filter.query.isEmpty() -> {
        div("empty pad") { +"No problems: every path's last scrape succeeded" }
      }

      rows.isEmpty() -> {
        div("empty pad") { +"No paths match the filter" }
      }

      else -> {
        pathRows(snapshot, rows, filter)
      }
    }
  }

  private fun DIV.pathRows(
    snapshot: ProxySnapshot,
    rows: List<PathView>,
    filter: PathFilter,
  ) {
    if (filter.isActive) div("match-count") { +"Showing ${rows.size} of ${snapshot.paths.size} paths" }
    // Judged on every path, not the rows a filter leaves, so the column doesn't come and go as a search narrows. With
    // agent auth off no path has an identity, and the column would only ever hold dashes.
    val showIdentity = snapshot.paths.any { it.identity.isNotEmpty() }
    table("ptable") {
      caption("sr-only") { +"Registered paths and the most recent scrape against each" }
      thead { headerRow(showIdentity) }
      tbody { rows.forEach { pathRow(it, showIdentity) } }
    }
  }

  private fun THEAD.headerRow(showIdentity: Boolean) {
    tr {
      th(scope = ThScope.col) { +"Path" }
      th(scope = ThScope.col) { +"Agent(s)" }
      if (showIdentity) th(scope = ThScope.col) { +"Identity" }
      th(scope = ThScope.col) { +"Target" }
      th(scope = ThScope.col) { +"Src" }
      th(scope = ThScope.col) { +"Last scrape" }
      th(scope = ThScope.col, classes = "num") { +"Status" }
      th(scope = ThScope.col, classes = "num") { +"Duration" }
      th(scope = ThScope.col) { +"History" }
    }
  }

  // Block bodies throughout this object, not `= td(...) { }`: CodeScene doesn't count the conditionals inside an
  // expression-bodied function's trailing lambda as the function's own, and calls them Global Conditionals.
  private fun TBODY.pathRow(
    path: PathView,
    showIdentity: Boolean,
  ) {
    tr(if (path.isDeparted) "departed" else null) {
      pathCell(path)
      agentCell(path)
      if (showIdentity) td("dim") { +(identityLabel(path.identity) ?: DASH) }
      targetCell(path)
      td("dim") { +sourceLabel(path.pathSource) }
      td("dim") { scrapeTime(path.lastScrape?.at) }
      statusCell(path)
      td("num dim") { +(path.lastScrape?.let { "${it.durationMillis} ms" } ?: DASH) }
      historyCell(path)
    }
  }

  /**
   * A path, and while the proxy serves it, the button that copies its proxy URL, the one to curl to check the path
   * through the proxy. The proxy can't know the name the viewer reaches it by, so the button carries the path, and the
   * page's script joins it to the host the page was opened at and the scrape port, [SCRAPE_PORT_ATTR] on `<body>`.
   */
  private fun TR.pathCell(path: PathView) {
    td {
      +"/${path.path}"
      if (!path.isDeparted)
        copyButton("$COPY_PROXY_ID_PREFIX${path.path}", "proxy URL", path) {
          attributes[PROXY_PATH_ATTR] = "/${path.path}"
        }
    }
  }

  // A departed path still names who was serving it -- that is usually the whole answer. Named the way the agent list
  // names it, so the two layouts can be read against each other.
  private fun TR.agentCell(path: PathView) {
    td {
      +(path.servingAgent?.let(::agentLabel) ?: DASH)
      if (path.isDeparted) span("tag crit") { +"gone" }
      if (path.additionalAgents > 0) span("dim") { +" +${path.additionalAgents}" }
    }
  }

  /** A path's target URL and the button that copies it, or a dash for a path without one, such as a departed path. */
  private fun TR.targetCell(path: PathView) {
    td("dim") {
      if (path.targetUrl.isEmpty()) {
        +DASH
      } else {
        +path.targetUrl
        copyButton("$COPY_TARGET_ID_PREFIX${path.path}", "target URL", path) {
          attributes[COPY_ATTR] = path.targetUrl
        }
      }
    }
  }

  private fun TR.statusCell(path: PathView) {
    td("num") {
      val scrape = path.lastScrape
      if (scrape == null)
        span("dim") { +DASH }
      else
        span(if (scrape.isSuccess) "pill ok" else "pill crit") { +"${scrape.statusCode}" }
    }
  }

  /**
   * The path's recent scrapes as a row of dots, oldest on the left, then the share that succeeded. Where the Status
   * column says how the last scrape went, this says whether that is news: a target that fails every scrape and one
   * that failed once in twenty read the same in Status alone.
   *
   * Drawn as one element per run of like results, each painting a dot per scrape as a repeating background, rather
   * than an element per scrape: the table is pushed whole every refresh, so a healthy path's strip stays one element.
   * The strip is one image to assistive tech, labelled with the same count the percentage gives.
   */
  private fun TR.historyCell(path: PathView) {
    td("dim") {
      val total = path.history.size
      if (total == 0) {
        +DASH
      } else {
        val succeeded = path.historySucceeded
        val summary = "$succeeded of $total recent scrapes succeeded"
        span("hist") {
          attributes["role"] = "img"
          attributes["aria-label"] = summary
          attributes["title"] = summary
          path.historyRuns.forEach { (ok, count) ->
            i(if (ok) "ok" else "crit") { attributes["style"] = "--n:$count" }
          }
        }
        // Floored, so a path with any failure never reads 100%.
        +"${succeeded * 100 / total}%"
      }
    }
  }

  /**
   * A button that copies [what] for [path] to the clipboard; [value] puts what it copies in an attribute.
   *
   * The page's script does the copying (see SELECTION_JS). The [id] is built from the path, which is unique in the
   * table, because the push loop replaces the whole table every frame: htmx gives focus back to the element whose id
   * the focused one had, so a keyboard user keeps their place, and the script puts the copied mark back on the button
   * with that id until the mark expires.
   */
  private fun FlowContent.copyButton(
    id: String,
    what: String,
    path: PathView,
    value: BUTTON.() -> Unit,
  ) {
    button(type = ButtonType.button, classes = "copy") {
      attributes["id"] = id
      value()
      // Names the path, so a screen reader listing the page's buttons can tell one row's from another's.
      attributes["aria-label"] = "Copy the $what of /${path.path}"
      attributes["title"] = "Copy $what"
    }
  }

  // PathSource names are agent-side enum constants carried as free text, so an unrecognized value is
  // shown verbatim rather than swallowed -- an agent newer than the proxy stays legible.
  private fun sourceLabel(source: String): String =
    when (source) {
      "STATIC" -> "cfg"
      "DISCOVERED" -> "disc"
      "" -> DASH
      else -> source.lowercase()
    }
}
