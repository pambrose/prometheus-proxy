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

import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.DashboardPage.COPY_ATTR
import io.prometheus.common.dashboard.DashboardPage.DASH
import io.prometheus.common.dashboard.DashboardPage.PROXY_PATH_ATTR
import io.prometheus.common.dashboard.DashboardPage.historyStrip
import io.prometheus.common.dashboard.DashboardPage.scrapeNowButton
import io.prometheus.common.dashboard.DashboardPage.scrapeResultPanel
import io.prometheus.common.dashboard.DashboardPage.statusPill
import io.prometheus.common.dashboard.DashboardPage.timeOfDay
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.COPY_PROXY_ID_PREFIX
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.COPY_TARGET_ID_PREFIX
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.LINK_ID_PREFIX
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.PATH_TABLE_ID
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.agentLabel
import io.prometheus.proxy.dashboard.ProxyDashboardHtml.renderFilters
import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.TBODY
import kotlinx.html.TR
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.unsafe

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
    renderFilters(routeBase, DashboardLayout.PATH, filter, columns(snapshot))
    if (snapshot.scrapeNowEnabled) scrapeResultPanel(routeBase)
    // The region a push replaces sits inside the scroller rather than being it, so the scroller stays and a table
    // scrolled sideways keeps its place across every refresh; a replaced scroller started again at the left.
    div("scroller") { div { pathTable(snapshot, filter, oob = false) } }
  }

  /**
   * The stylesheet that gives each brand mark the configured [links] show its URL and colours
   * ([ProxyDashboardCss.linkIconCss]), for the page's head, or nothing when no link shows one. In the head, which no
   * push reaches, so every frame's links carry only their classes; the links are fixed at startup, so it never goes
   * stale.
   */
  fun HEAD.linkIconStyle(
    links: List<PathLink>,
    routeBase: String,
  ) {
    val icons = links.mapNotNull { it.icon }.distinct()
    if (icons.isNotEmpty()) style { unsafe { raw(ProxyDashboardCss.linkIconCss(routeBase, icons)) } }
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
    table("ptable") {
      caption("sr-only") { +"Registered paths and the most recent scrape against each" }
      thead { columnHeaders(columns(snapshot)) }
      tbody { rows.forEach { pathRow(it, snapshot) } }
    }
  }

  /**
   * The table's columns, in order, for its header row and its column guide alike; [pathRow] renders a cell for each.
   *
   * The Identity and Links columns come from the config, not the rows, so neither comes and goes as a search narrows or
   * the last live path goes. With agent auth off no path has an identity, and without proxy.dashboard.links no path has
   * a link, so either column would only ever hold dashes.
   */
  private fun columns(snapshot: ProxySnapshot): List<TableColumn> {
    // Its explanation names the Scrape button only when there is one.
    val path = if (snapshot.scrapeNowEnabled) PATH_WITH_SCRAPE_NOW else PATH
    return listOfNotNull(
      path,
      AGENTS,
      IDENTITY.takeIf { snapshot.authEnabled },
      TARGET,
      SOURCE,
      LAST_SCRAPE,
      STATUS,
      DURATION,
      HISTORY,
      LINKS.takeIf { snapshot.links.isNotEmpty() },
    )
  }

  // What the column guide says of each column: one short sentence, in the words the website's dashboard page uses.
  private val PATH =
    TableColumn(
      label = "Path",
      help = "The path Prometheus scrapes on the proxy, with a button to copy its proxy URL.",
    )
  private val PATH_WITH_SCRAPE_NOW =
    TableColumn(
      label = "Path",
      help = "The path Prometheus scrapes on the proxy, with buttons to copy its proxy URL and to scrape it now.",
    )
  private val AGENTS =
    TableColumn(
      label = "Agent(s)",
      help = "The agent serving the path; +N counts the others sharing it, and gone marks a path whose agent left.",
    )
  private val IDENTITY =
    TableColumn(
      label = "Identity",
      help = "The auth identity that registered the path, which any agent joining it must share; the legacy " +
        "agentToken reads shared token.",
    )
  private val TARGET =
    TableColumn(
      label = "Target",
      help = "The URL the agent fetches, its user info and query values redacted, with a button to copy it.",
    )
  private val SOURCE =
    TableColumn(
      label = "Src",
      help = "cfg for a path in the agent's config, disc for one from dynamic discovery.",
    )
  private val LAST_SCRAPE =
    TableColumn(
      label = "Last scrape",
      help = "When Prometheus last scraped the path, in your time zone; hover over it for the date.",
    )
  private val STATUS =
    TableColumn(
      label = "Status",
      help = "The status code that scrape returned, green for a 2xx and red for anything else.",
      numeric = true,
    )
  private val DURATION =
    TableColumn(
      label = "Duration",
      help = "How long that scrape took, as the proxy measured it.",
      numeric = true,
    )
  private val HISTORY =
    TableColumn(
      label = "History",
      help =
        "The path's recent scrapes as dots, oldest on the left, colored as Status is, with faint dots holding the " +
        "places of scrapes still to come, then the share that succeeded.",
    )
  private val LINKS =
    TableColumn(
      label = "Links",
      help = "The path's links into other tools, from proxy.dashboard.links, by name or brand icon; hover over an " +
        "icon for its name. Each opens in a new tab.",
      centered = true,
    )

  // Block bodies throughout this object, not `= td(...) { }`: CodeScene doesn't count the conditionals inside an
  // expression-bodied function's trailing lambda as the function's own, and calls them Global Conditionals.
  private fun TBODY.pathRow(
    path: PathView,
    snapshot: ProxySnapshot,
  ) {
    tr(if (path.isDeparted) "departed" else null) {
      pathCell(path, snapshot.scrapeNowEnabled)
      agentCell(path)
      if (snapshot.authEnabled) td("dim") { +(identityLabel(path.identity) ?: DASH) }
      targetCell(path)
      td("dim") { +sourceLabel(path.pathSource) }
      td("dim") { timeOfDay(path.lastScrape?.at) }
      statusCell(path)
      td("num dim") { +(path.lastScrape?.let { "${it.durationMillis} ms" } ?: DASH) }
      historyCell(path, snapshot)
      if (snapshot.links.isNotEmpty()) linksCell(path, snapshot.links)
    }
  }

  /**
   * A path, and while the proxy serves it, the button that copies its proxy URL, the one to curl to check the path
   * through the proxy. The proxy can't know the name the viewer reaches it by, so the button carries the path, and the
   * page's script joins it to the host the page was opened at and the scrape port,
   * [io.prometheus.proxy.dashboard.DashboardPage.SCRAPE_PORT_ATTR] on `<body>`. With
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
        if (scrapeNow) scrapeNowButton(path.path)
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

  /** The path's recent scrapes as a strip of dots, then the share that succeeded (see [DashboardPage.historyStrip]). */
  private fun TR.historyCell(
    path: PathView,
    snapshot: ProxySnapshot,
  ) {
    td("dim history") { historyStrip(path.historyRuns, snapshot.pathHistorySize) }
  }

  /**
   * The path's links into other tools, `proxy.dashboard.links`, each opening [path] in a new tab, or a dash for a path
   * with none. A link whose template needs a value the path lacks is left out (see [PathLink.urlFor]), so a departed
   * path, whose labels are gone, keeps only the links that use none. Two names in a row take a separator; an icon
   * stands apart by its own margin.
   */
  private fun TR.linksCell(
    path: PathView,
    links: List<PathLink>,
  ) {
    td("dim links ${LINKS.alignment}") {
      // Each link keeps its place in proxy.dashboard.links, which its id carries, though an earlier one is left out.
      val urls = links.withIndex().mapNotNull { (place, link) -> link.urlFor(path)?.let { Triple(place, link, it) } }
      if (urls.isEmpty()) +DASH
      urls.forEachIndexed { index, (place, link, url) ->
        val previous = urls.getOrNull(index - 1)?.second
        if (previous.isName && link.isName) +" · "
        pathLink(path, place, link, url)
      }
    }
  }

  // Whether a link shows its name rather than a brand mark; no link, as before the first, is neither.
  private val PathLink?.isName: Boolean get() = this != null && icon == null

  /**
   * One of [path]'s links, the one at [place] in `proxy.dashboard.links`: its name, or its brand mark, whose look the
   * page's icon stylesheet gives its class (see [ProxyDashboardCss.linkIconCss]), with a `title` saying what it does,
   * *Open in Grafana*, which the browser shows as the copy and scrape-now buttons' titles. Either way its `aria-label`
   * names the path and the tool.
   *
   * Its id is built from [place] and the path, which is unique in the table, so the push that replaces the table every
   * refresh hands focus back to it, as a copy button's id does; without one, a keyboard user lost their place on every
   * refresh.
   */
  private fun FlowContent.pathLink(
    path: PathView,
    place: Int,
    link: PathLink,
    url: String,
  ) {
    val icon = link.icon
    // noopener so the tool's page can't reach back into this one; noreferrer so it isn't told the dashboard's URL.
    a(href = url, target = "_blank", classes = icon?.let { "icon icon-${it.slug}" }) {
      attributes["id"] = "$LINK_ID_PREFIX$place-${path.path}"
      attributes["rel"] = "noopener noreferrer"
      // Names the path, so a screen reader listing the page's links can tell one row's from another's.
      attributes["aria-label"] = "Open /${path.path} in ${link.name}"
      if (icon == null) +link.name else attributes["title"] = "Open in ${link.name}"
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
