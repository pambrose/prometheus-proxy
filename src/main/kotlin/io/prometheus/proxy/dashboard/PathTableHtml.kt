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
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.SCRAPE_ID_PREFIX
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.SCRAPE_RESULT_ID
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.SCRAPE_URL_ATTR
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.agentLabel
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.renderFilters
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.scrapeTime
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.statusPill
import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.ThScope
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.i
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
  /** The search and the table, as the page first renders them; see [ProxyDashboardHtml.renderFilters]. */
  fun FlowContent.renderPathTable(
    snapshot: ProxySnapshot,
    routeBase: String,
    filter: RowFilter,
  ) {
    renderFilters(routeBase, DashboardLayout.PATH, filter)
    if (snapshot.scrapeNowEnabled) scrapeResultPanel(routeBase)
    div("scroller") { pathTable(snapshot, filter, oob = false) }
  }

  // Where a scrape-now's result shows, filled by the page's script from the route it names. Outside the table's
  // out-of-band region, so a push never clears a result someone is reading.
  private fun FlowContent.scrapeResultPanel(routeBase: String) {
    div("scrape-result") {
      attributes["id"] = SCRAPE_RESULT_ID
      attributes[SCRAPE_URL_ATTR] = "$routeBase/scrape"
      attributes["hidden"] = "hidden"
    }
  }

  /** The table region, `#path-table`, which every push for the Paths view replaces. */
  fun DIV.pathTable(
    snapshot: ProxySnapshot,
    filter: RowFilter,
    oob: Boolean,
  ) {
    attributes["id"] = PATH_TABLE_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    val rows = if (filter.isActive) snapshot.paths.filter(filter::matches) else snapshot.paths
    if (filteredTableStart(TEXT, filter, rows.size, snapshot.paths.size)) pathRows(snapshot, rows)
  }

  private val TEXT =
    TableText(
      noun = "paths",
      empty = "No paths registered",
      noProblems = "No problems: every path's last scrape succeeded",
      noMatch = "No paths match the filter",
    )

  private fun DIV.pathRows(
    snapshot: ProxySnapshot,
    rows: List<PathView>,
  ) {
    // From the config, not the rows, so the column doesn't come and go as a search narrows or the last live path goes.
    // With agent auth off no path has an identity, and the column would only ever hold dashes.
    val showIdentity = snapshot.authEnabled
    table("ptable") {
      caption("sr-only") { +"Registered paths and the most recent scrape against each" }
      thead { headerRow(showIdentity) }
      tbody { rows.forEach { pathRow(it, showIdentity, snapshot.scrapeNowEnabled) } }
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
    scrapeNow: Boolean,
  ) {
    tr(if (path.isDeparted) "departed" else null) {
      pathCell(path, scrapeNow)
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
   * page's script joins it to the host the page was opened at and the scrape port,
   * [io.prometheus.proxy.dashboard.ProxyDashboardHtml.SCRAPE_PORT_ATTR] on `<body>`. With
   * [scrapeNow], the button that scrapes it at once follows.
   */
  private fun TR.pathCell(
    path: PathView,
    scrapeNow: Boolean,
  ) {
    td {
      +"/${path.path}"
      if (!path.isDeparted) {
        copyButton("$COPY_PROXY_ID_PREFIX${path.path}", "proxy URL", path) {
          attributes[PROXY_PATH_ATTR] = "/${path.path}"
        }
        if (scrapeNow) scrapeButton(path)
      }
    }
  }

  /**
   * The button that scrapes [path] now, through its agent, and shows the result above the table (see [ScrapeNow]). Its
   * id is built from the path, as a copy button's is, so the push that replaces the table hands focus back to it.
   */
  private fun FlowContent.scrapeButton(path: PathView) {
    button(type = ButtonType.button, classes = "scrape-now") {
      // The page's script reads the path back out of the id.
      attributes["id"] = "$SCRAPE_ID_PREFIX${path.path}"
      attributes["aria-label"] = "Scrape /${path.path} now"
      attributes["title"] = "Scrape now"
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

  /**
   * A path's target URL and the button that copies it, or a dash for a path without one: its agent didn't report one,
   * or it departed and the proxy kept only its scrapes. A departed path otherwise shows the target it left with.
   */
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
        statusPill(scrape.statusCode, scrape.isSuccess)
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
   * The page's script does the copying (see DashboardScript). The [id] is built from the path, which is unique in the
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
