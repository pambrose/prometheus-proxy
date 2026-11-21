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

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.FormMethod
import kotlinx.html.HEAD
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.i
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.time
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * One of a dashboard's views, as its tabs, its routes, and its page's script know it: an enum entry's [name], the route
 * [segment] below the dashboard's base, empty for the view served at the base itself, and the [title] its tab shows.
 */
internal interface DashboardView {
  val name: String
  val segment: String
  val title: String
}

/**
 * The view's URL, under [routeBase]; the base view's is the base itself, which at a root mount is "/". An extension
 * rather than a default method, which would compile to a `DefaultImpls` class no test can cover.
 */
internal fun DashboardView.href(routeBase: String): String =
  if (segment.isEmpty()) routeBase.ifEmpty { "/" } else "$routeBase/$segment"

/**
 * What every dashboard page shares: the ids and attributes its script ([DashboardScript]) reads, the head that loads
 * htmx and the stylesheet, the tabs, the connection indicator, the live region, and Log out. Each dashboard's renderer
 * builds its own regions around these, so the script and the pages can't drift apart.
 */
internal object DashboardPage {
  const val STATUS_ID = "status-bar"
  const val ANNOUNCER_ID = "conn-announce"

  // The tabs, which the page's script moves between with the arrow keys. No path or agent can give an element this id:
  // every id built from one starts with a prefix (agent-row-, copy-, scrape-path-).
  const val NAV_ID = "layout-nav"
  const val VIEW_ATTR = "data-view"
  const val COPY_ATTR = "data-copy"
  const val PROXY_PATH_ATTR = "data-proxy-path"
  const val SCRAPE_PORT_ATTR = "data-scrape-port"

  // The search above the Paths and Activity tables. One set of ids for both: the two never show on one page.
  const val FILTER_QUERY_ID = "filter-q"
  const val FILTER_PROBLEMS_ID = "filter-problems"

  // The column guide under the search, and the button at the search's end that opens it; one pair for both tables, as
  // the search's ids are. No path or agent can give an element either id: every id built from one starts with a prefix
  // (agent-row-, copy-, scrape-path-).
  const val COLUMN_GUIDE_ID = "column-guide"
  const val COLUMN_GUIDE_TOGGLE_ID = "column-guide-toggle"

  // On the table region a push replaces: how many rows the filter left, which the page's script says aloud once per
  // change of filter.
  const val MATCH_COUNT_ATTR = "data-match-count"

  // Scrape-now (ScrapeNow): the panel a result shows in, the route the page's script posts to, on the panel, the
  // prefix of a button's id, which the path follows, and the sentence a result's heading carries for the script to say
  // aloud. No region id starts with the prefix, so no path can give a button the id of the panel or a region.
  const val SCRAPE_RESULT_ID = "scrape-result"
  const val SCRAPE_URL_ATTR = "data-scrape-url"
  const val SCRAPE_ID_PREFIX = "scrape-path-"
  const val SAY_ATTR = "data-say"

  // On the status bar: the page's title, which counts the paths failing or gone, for the page's script to put in the
  // tab.
  const val TITLE_ATTR = "data-title"

  // The Log out form, in the bar, with a login set.
  const val LOGOUT_ID = "logout"

  // An en dash for "the agent never told us", kept distinct from an empty cell, which would read as a
  // rendering bug rather than as absent data.
  const val DASH = "\u2013"

  // The filter's URL parameters, which a search form submits and the page's script writes.
  const val QUERY_PARAM = "q"
  const val PROBLEMS_PARAM = "problems"
  const val PROBLEMS_ON = "1"

  /**
   * htmx's config, read from a `<meta>` tag.
   *
   * `noSwap` keeps htmx 2's rule that a 4xx or 5xx response is not swapped in. htmx 4 swaps every
   * response but a 204 or 304, so a row click that failed would replace `#detail` with the error body, and
   * the pushes, which address `#detail`, would have nothing left to update.
   *
   * `ws` caps the reconnect backoff. The extension's default doubles the wait from 500 ms up to a minute,
   * resetting only on a successful open. That is tuned for public services guarding against a reconnect
   * stampede, the opposite of a dashboard, which has a handful of viewers on a LAN. Left alone, a short
   * outage grows the wait so far that the page can sit idle for tens of seconds after the process is already
   * back. With these values the waits run 500 ms, 1 s, then 2 s ± 250 ms: recovery within about 2 s, and
   * just enough jitter that many dashboards do not reconnect in lockstep.
   */
  const val HTMX_CONFIG =
    """{"noSwap":[204,304,"4xx","5xx"],"ws":{"reconnectDelay":500,"reconnectMaxDelay":2000,"reconnectJitter":0.125}}"""

  /**
   * The page's head: its [title], the stylesheet, any [extra] style after it, and htmx with its WebSocket extension,
   * from the versioned asset route under [routeBase].
   */
  fun HEAD.dashboardHead(
    title: String,
    routeBase: String,
    extra: HEAD.() -> Unit = {},
  ) {
    title(title)
    meta(name = "viewport", content = "width=device-width, initial-scale=1")
    style { unsafe { raw(DashboardCss.CSS) } }
    extra()
    // Ahead of the scripts: htmx reads its config once, when htmx.min.js loads.
    meta(name = "htmx-config", content = HTMX_CONFIG)
    script(src = "$routeBase/${DashboardServer.ASSET_DIR}/htmx.min.js") {}
    script(src = "$routeBase/${DashboardServer.ASSET_DIR}/hx-ws.min.js") {}
  }

  /**
   * The tabs: a plain link per view, no htmx.
   *
   * Deliberately a real navigation rather than a swap. The views have disjoint region ids, so swapping one in would
   * leave the push loop addressing elements that no longer exist; a page load makes that impossible by construction.
   * It also costs nothing worth saving — this fires on an explicit click, not on an update, and the socket
   * re-announces the new view as soon as it opens.
   *
   * Links in a `<nav>` with `aria-current`, not ARIA tabs: each opens a page of its own, which `role="tab"` would
   * misstate. The page's script adds the arrow keys (see DashboardScript).
   */
  fun FlowContent.tabs(
    views: List<DashboardView>,
    current: DashboardView,
    routeBase: String,
  ) {
    nav("nav") {
      attributes["id"] = NAV_ID
      views.forEach { view ->
        val on = view == current
        a(href = view.href(routeBase), classes = if (on) "on" else null) {
          if (on) attributes["aria-current"] = "page"
          +view.title
        }
      }
    }
  }

  /**
   * A page's title: the [brand], after a count of the [problems] while there are any, so a dashboard left in a
   * background tab still says when something is wrong.
   */
  fun pageTitle(
    brand: String,
    problems: Int,
  ): String =
    when (problems) {
      0 -> brand
      1 -> "(1 problem) $brand"
      else -> "($problems problems) $brand"
    }

  /**
   * The connection indicator, at the start of the status bar. Both labels are always rendered; which one shows is
   * driven by the `ws-down` class on `<body>`, which the WebSocket lifecycle handlers toggle. Body-level rather than
   * here because the status bar is overwritten by every push -- and a push can only happen while connected, so the
   * server never has to render the disconnected state itself. The class lives on the one element pushes never touch,
   * so it survives the frozen-status-bar window during an outage.
   */
  fun FlowContent.connectionIndicator() {
    span("conn") {
      span("beacon") {}
      span("conn-live") { +"live" }
      span("conn-down") { +"reconnecting…" }
    }
  }

  /** Log out, at the bar's end, after the status and outside it, which a push replaces: a form the browser posts. */
  fun FlowContent.logoutForm(routeBase: String) {
    form(action = "$routeBase/logout", method = FormMethod.post, classes = "logout") {
      attributes["id"] = LOGOUT_ID
      button(type = ButtonType.submit) { +"Log out" }
    }
  }

  /**
   * The one live region on the page. Kept outside every out-of-band id on purpose: a region the push loop rewrites
   * would re-announce itself on every frame, so this is written only by the socket lifecycle handlers, which fire when
   * the state actually changes, and by the result of a click.
   */
  fun FlowContent.announcer() {
    div("sr-only") {
      attributes["id"] = ANNOUNCER_ID
      attributes["role"] = "status"
      attributes["aria-live"] = "polite"
    }
  }

  /**
   * A time, or a dash when there is none. Rendered as `<time datetime="…Z">` with the time of day in the server's
   * zone, which the page's script replaces with the viewer's (see DashboardScript).
   */
  fun FlowOrPhrasingContent.timeOfDay(at: Instant?) {
    if (at == null) {
      +DASH
      return
    }
    time {
      attributes["datetime"] = Instant.fromEpochSeconds(at.epochSeconds).toString()
      // java.time's formatter, since the standard library has none that takes a zone.
      +timeFormat.format(at.toJavaInstant())
    }
  }

  /**
   * Where a scrape-now's result shows, filled by the page's script from the route it names (see DashboardScript).
   * Rendered outside every pushed region, so a push never clears a result someone is reading.
   */
  fun FlowContent.scrapeResultPanel(routeBase: String) {
    div("scrape-result") {
      attributes["id"] = SCRAPE_RESULT_ID
      attributes[SCRAPE_URL_ATTR] = "$routeBase/scrape"
      attributes["hidden"] = "hidden"
    }
  }

  /**
   * The button that scrapes [path] now and shows the result in the [scrapeResultPanel]. The page's script reads the
   * path back out of its id, which is built from the path, unique on the page, so the push that replaces the region it
   * is in hands focus back to it.
   */
  fun FlowContent.scrapeNowButton(path: String) {
    button(type = ButtonType.button, classes = "scrape-now") {
      attributes["id"] = "$SCRAPE_ID_PREFIX$path"
      attributes["aria-label"] = "Scrape /$path now"
      attributes["title"] = "Scrape now"
    }
  }

  /** A scrape's status code as a pill, `ok` for a success and `crit` for anything else, as every view shows one. */
  fun FlowOrPhrasingContent.statusPill(
    statusCode: Int,
    isSuccess: Boolean,
  ) {
    span(if (isSuccess) "pill ok" else "pill crit") { +"$statusCode" }
  }

  /**
   * A path's recent scrapes, [succeeded] each, **newest first**, as runs of consecutive like results, **oldest first**,
   * each a `(succeeded, count)` pair: what [historyStrip] draws, one element per run.
   */
  fun historyRuns(succeeded: List<Boolean>): List<Pair<Boolean, Int>> =
    buildList {
      succeeded.asReversed().forEach { ok ->
        val last = lastOrNull()
        if (last != null && last.first == ok)
          set(lastIndex, ok to last.second + 1)
        else
          add(ok to 1)
      }
    }

  /**
   * A path's recent scrapes as a row of dots, oldest on the left, then the share that succeeded, or a dash for a path
   * with none: [runs], from [historyRuns], in a history that holds [capacity] scrapes when full. Where a status says
   * how the last scrape went, this says whether that is news: a target that fails every scrape and one that failed once
   * in twenty read the same in a status alone.
   *
   * Drawn as one element per run of like results, each painting a dot per scrape as a repeating background, rather
   * than an element per scrape: a dashboard pushes its paths whole every refresh, so a healthy path's strip stays one
   * element. The strip is one image to assistive tech, labelled with the same count the percentage gives.
   *
   * Until the history holds [capacity] scrapes, faint dots keep the places of the ones still to come, on the left, so
   * every strip is as wide as a full one: the newest scrape and the percentage sit in the same place on every row, and
   * a column of strips doesn't widen on every refresh as the histories fill.
   */
  fun FlowOrPhrasingContent.historyStrip(
    runs: List<Pair<Boolean, Int>>,
    capacity: Int,
  ) {
    val total = runs.sumOf { it.second }
    if (total == 0) {
      +DASH
      return
    }
    val succeeded = runs.filter { it.first }.sumOf { it.second }
    val summary = "$succeeded of $total recent scrapes succeeded"
    span("hist") {
      attributes["role"] = "img"
      attributes["aria-label"] = summary
      attributes["title"] = summary
      val toCome = capacity - total
      if (toCome > 0) i("slot") { attributes["style"] = "--n:$toCome" }
      runs.forEach { (ok, count) ->
        i(if (ok) "ok" else "crit") { attributes["style"] = "--n:$count" }
      }
    }
    // Floored, so a path with any failure never reads 100%.
    +"${succeeded * 100 / total}%"
  }

  /** An uptime at the precision a hero line wants: the two largest units from a day up, then one. */
  fun humanize(duration: Duration): String =
    duration.toComponents { days, hours, minutes, seconds, _ ->
      when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
      }
    }

  private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

  private const val KIB = 1024.0

  /** A size in bytes, as a person reads it: bytes below a kilobyte, then KB, then MB, to one decimal place. */
  fun size(bytes: Long): String {
    val kib = bytes / KIB
    return when {
      bytes < KIB -> "$bytes bytes"
      kib < KIB -> String.format(Locale.ROOT, "%.1f KB", kib)
      else -> String.format(Locale.ROOT, "%.1f MB", kib / KIB)
    }
  }
}
