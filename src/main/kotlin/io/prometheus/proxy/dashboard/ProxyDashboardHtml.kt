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

import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.HTML
import kotlinx.html.SPAN
import kotlinx.html.a
import kotlinx.html.ThScope
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.i
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.title
import kotlinx.html.TR
import kotlinx.html.time
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * Which view a session is looking at.
 *
 * Carried per session rather than configured per proxy, because the two layouts answer different
 * questions and an operator switches between them mid-incident: the agent view asks "what is this agent
 * doing", the path view asks "why isn't this target scraping".
 */
internal enum class DashboardLayout {
  AGENT,
  PATH,
}

/**
 * Server-rendered markup for the operational dashboard.
 *
 * Everything is rendered here rather than in the browser: the WebSocket carries HTML fragments, not
 * JSON, so there is no client-side templating and no duplicated view logic. Fragments are marked
 * `hx-swap-oob` and carry stable ids, which lets a single WebSocket frame update the agent list and the
 * detail pane independently without re-rendering the page.
 */
internal object ProxyDashboardHtml {
  private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

  const val AGENT_LIST_ID = "agent-list"
  const val DETAIL_ID = "detail"
  const val STATUS_ID = "status-bar"
  const val PATH_TABLE_ID = "path-table"
  const val ANNOUNCER_ID = "conn-announce"
  const val VIEW_ATTR = "data-view"
  const val COPY_ATTR = "data-copy"
  const val PROXY_PATH_ATTR = "data-proxy-path"
  const val SCRAPE_PORT_ATTR = "data-scrape-port"

  // The two kinds of copy button, by row. Their ids differ before the path does ("copy-t..." and "copy-p..."), so no
  // path can give one kind the id of the other.
  const val COPY_TARGET_ID_PREFIX = "copy-target-"
  const val COPY_PROXY_ID_PREFIX = "copy-proxy-"

  // No region id starts with it, so an agent id, which an agent behind nginx names itself, can't make a row's id
  // equal one.
  const val AGENT_ROW_ID_PREFIX = "agent-row-"

  /** The full page. Only served on a real navigation; every later update arrives over the socket. */
  fun HTML.renderPage(
    snapshot: ProxySnapshot,
    selectedId: String?,
    routeBase: String,
    layout: DashboardLayout = DashboardLayout.AGENT,
  ) {
    // Declared so assistive tech pronounces agent names, paths, and status text as English rather than
    // falling back to the listener's system locale.
    attributes["lang"] = "en"
    head {
      title("prometheus-proxy")
      meta(name = "viewport", content = "width=device-width, initial-scale=1")
      style { unsafe { raw(ProxyDashboardCss.CSS) } }
      // Ahead of the scripts: htmx reads its config once, when htmx.min.js loads.
      meta(name = "htmx-config", content = HTMX_CONFIG)
      script(src = "$routeBase/${ProxyDashboardService.ASSET_DIR}/htmx.min.js") {}
      script(src = "$routeBase/${ProxyDashboardService.ASSET_DIR}/hx-ws.min.js") {}
    }
    body {
      // Loading hx-ws.min.js is what enables the extension; htmx 4 has no hx-ext.
      attributes["hx-ws:connect"] = "$routeBase/events"
      // For the proxy URLs the page builds (see pathCell). On <body>, which no push replaces, since the port doesn't
      // change.
      attributes[SCRAPE_PORT_ATTR] = "${snapshot.scrapePort}"

      div("bar") {
        span("brand") { +"prometheus-proxy" }
        renderNav(routeBase, layout)
        renderStatus(snapshot)
      }
      // The one live region on the page. Kept outside every out-of-band id on purpose: a region the push
      // loop rewrites would re-announce itself on every frame, so this is written only by the socket
      // lifecycle handlers, which fire when the state actually changes.
      div("sr-only") {
        attributes["id"] = ANNOUNCER_ID
        attributes["role"] = "status"
        attributes["aria-live"] = "polite"
      }

      when (layout) {
        DashboardLayout.AGENT -> {
          main("md") {
            renderAgentList(snapshot, selectedId, routeBase)
            renderDetail(snapshot, selectedId)
          }
        }

        DashboardLayout.PATH -> {
          main {
            renderPathTable(snapshot)
          }
        }
      }
      // The one piece htmx does not model: telling the server which agent THIS session is viewing, so
      // the push loop knows whether to send a detail pane. hx-push-url already puts the selection in
      // the URL, so this reads it back rather than tracking state of its own.
      script { unsafe { raw(SELECTION_JS) } }
    }
  }

  /**
   * The layout switcher: two plain links, no htmx.
   *
   * Deliberately a real navigation rather than a swap. The two layouts have disjoint region ids, so
   * swapping one in would leave the push loop addressing elements that no longer exist; a page load
   * makes that impossible by construction. It also costs nothing worth saving — this fires on an
   * explicit click, not on an update, and the socket re-announces the new layout as soon as it opens.
   */
  fun FlowContent.renderNav(
    routeBase: String,
    layout: DashboardLayout,
  ) = nav("nav") {
    a(href = routeBase.ifEmpty { "/" }, classes = if (layout == DashboardLayout.AGENT) "on" else null) {
      if (layout == DashboardLayout.AGENT) attributes["aria-current"] = "page"
      +"Agents"
    }
    a(href = "$routeBase/paths", classes = if (layout == DashboardLayout.PATH) "on" else null) {
      if (layout == DashboardLayout.PATH) attributes["aria-current"] = "page"
      +"Paths"
    }
  }

  /**
   * Every path on one row, joined with its last scrape.
   *
   * Flat on purpose: no selection, no drill-in, no per-session state, so the whole table is a single
   * out-of-band swap and the push path stays the cheapest of any layout. The row IS the answer, which is
   * the reason to have this view at all.
   */
  fun FlowContent.renderPathTable(
    snapshot: ProxySnapshot,
    oob: Boolean = false,
  ) = div("scroller") { pathTableInner(snapshot, oob) }

  private fun DIV.pathTableInner(
    snapshot: ProxySnapshot,
    oob: Boolean,
  ) {
    attributes["id"] = PATH_TABLE_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    if (snapshot.paths.isEmpty()) {
      div("empty pad") { +"No paths registered" }
      return
    }
    table("ptable") {
      caption("sr-only") { +"Registered paths and the most recent scrape against each" }
      thead {
        tr {
          th(scope = ThScope.col) { +"Path" }
          th(scope = ThScope.col) { +"Agent(s)" }
          th(scope = ThScope.col) { +"Target" }
          th(scope = ThScope.col) { +"Src" }
          th(scope = ThScope.col) { +"Last scrape" }
          th(scope = ThScope.col, classes = "num") { +"Status" }
          th(scope = ThScope.col, classes = "num") { +"Duration" }
          th(scope = ThScope.col) { +"History" }
        }
      }
      tbody {
        snapshot.paths.forEach { path ->
          tr(if (path.isDeparted) "departed" else null) {
            pathCell(path)
            td {
              // A departed path still names who was serving it -- that is usually the whole answer.
              // Named the way the agent list names it, so the two layouts can be read against each other.
              +(path.servingAgent?.let(::agentLabel) ?: DASH)
              if (path.isDeparted) span("tag crit") { +"gone" }
              if (path.additionalAgents > 0) span("dim") { +" +${path.additionalAgents}" }
            }
            targetCell(path)
            td("dim") { +sourceLabel(path.pathSource) }
            td("dim") { scrapeTime(path.lastScrape?.at) }
            td("num") {
              val scrape = path.lastScrape
              if (scrape == null)
                span("dim") { +DASH }
              else
                span(if (scrape.isSuccess) "pill ok" else "pill crit") { +"${scrape.statusCode}" }
            }
            td("num dim") { +(path.lastScrape?.let { "${it.durationMillis} ms" } ?: DASH) }
            historyCell(path)
          }
        }
      }
    }
  }

  /**
   * A path, and while the proxy serves it, the button that copies its proxy URL, the one to curl to check the path
   * through the proxy. The proxy can't know the name the viewer reaches it by, so the button carries the path, and the
   * page's script joins it to the host the page was opened at and the scrape port, [SCRAPE_PORT_ATTR] on `<body>`.
   */
  private fun TR.pathCell(path: PathView) =
    td {
      +"/${path.path}"
      if (!path.isDeparted)
        copyButton("$COPY_PROXY_ID_PREFIX${path.path}", "proxy URL", path) {
          attributes[PROXY_PATH_ATTR] = "/${path.path}"
        }
    }

  /** A path's target URL and the button that copies it, or a dash for a path without one, such as a departed path. */
  private fun TR.targetCell(path: PathView) =
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

  /**
   * The path's recent scrapes as a row of dots, oldest on the left, then the share that succeeded. Where the Status
   * column says how the last scrape went, this says whether that is news: a target that fails every scrape and one
   * that failed once in twenty read the same in Status alone.
   *
   * Drawn as one element per run of like results, each painting a dot per scrape as a repeating background, rather
   * than an element per scrape: the table is pushed whole every refresh, so a healthy path's strip stays one element.
   * The strip is one image to assistive tech, labelled with the same count the percentage gives.
   *
   * A block body, not `= td(...) { }` as the other cells are written: CodeScene doesn't count the conditionals
   * inside an expression-bodied function's trailing lambda as the function's own, and with this one the file crossed
   * its Global Conditionals threshold.
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
   * A scrape's time, or a dash when there is none. Rendered as `<time datetime="…Z">` with the time of day in the
   * proxy's zone, which the page's script replaces with the viewer's (see SELECTION_JS).
   */
  private fun FlowOrPhrasingContent.scrapeTime(at: Instant?) {
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
  ) = button(type = ButtonType.button, classes = "copy") {
    attributes["id"] = id
    value()
    // Names the path, so a screen reader listing the page's buttons can tell one row's from another's.
    attributes["aria-label"] = "Copy the $what of /${path.path}"
    attributes["title"] = "Copy $what"
  }

  /** Status counters. Driven by the timer rather than the event bus -- these drift, they do not change. */
  fun FlowContent.renderStatus(
    snapshot: ProxySnapshot,
    oob: Boolean = false,
  ) = span("bar-right") { statusInner(snapshot, oob) }

  private fun SPAN.statusInner(
    snapshot: ProxySnapshot,
    oob: Boolean,
    viewSeq: Long = 0L,
  ) {
    attributes["id"] = STATUS_ID
    if (oob) {
      attributes["hx-swap-oob"] = "true"
      // The frame's view stamp; see pushFragment.
      attributes[VIEW_ATTR] = "$viewSeq"
    }
    // Both labels are always rendered; which one shows is driven by the `ws-down` class on <body>,
    // which the WebSocket lifecycle handlers toggle. Body-level rather than here because this region
    // is overwritten by every push -- and a push can only happen while connected, so the server never
    // has to render the disconnected state itself. The class lives on the one element pushes never
    // touch, so it survives the frozen-status-bar window during an outage.
    span("conn") {
      span("beacon") {}
      span("conn-live") { +"live" }
      span("conn-down") { +"reconnecting…" }
    }
    span { +"${snapshot.health.agentCount} agents" }
    span { +"${snapshot.health.pathCount} paths" }
    // health.pathCount counts registrations, so a departed path is deliberately absent from it -- which
    // read as the bar contradicting the table it sits above ("0 paths" over three visible rows). Naming
    // the departed count separately keeps both numbers true and explains the difference.
    val departed = snapshot.paths.count { it.isDeparted }
    if (departed > 0)
      span("crit") { +"$departed departed" }
    val h = snapshot.health
    span(if (h.chunkContextHealthy && h.scrapeMapHealthy) "ok" else "warn") {
      +"chunks ${h.chunkContextSize}/${h.chunkContextThreshold} · scrapes ${h.scrapeMapSize}/${h.scrapeMapThreshold}"
    }
  }

  fun FlowContent.renderAgentList(
    snapshot: ProxySnapshot,
    selectedId: String?,
    routeBase: String,
    oob: Boolean = false,
  ) = div("md-list") { agentListInner(snapshot, selectedId, routeBase, oob) }

  private fun DIV.agentListInner(
    snapshot: ProxySnapshot,
    selectedId: String?,
    routeBase: String,
    oob: Boolean,
  ) {
    attributes["id"] = AGENT_LIST_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    div("md-head") {
      span { +"Agents" }
      span { +"${snapshot.agents.size}" }
    }
    if (snapshot.agents.isEmpty()) {
      div("empty") { +"No agents connected" }
      return
    }
    snapshot.agents.forEach { agent ->
      button(classes = "md-row") {
        // The push loop replaces this list every frame, and htmx gives focus back only to the element with the id
        // the focused one had, so without an id a keyboard user lost their place in the list on every refresh.
        attributes["id"] = "$AGENT_ROW_ID_PREFIX${agent.agentId}"
        attributes["hx-get"] = "$routeBase/agents/${agent.agentId}"
        attributes["hx-target"] = "#$DETAIL_ID"
        // outerHTML, so the returned #detail element replaces the old one in place rather than nesting a
        // second #detail inside it (the fragment IS the element, not its inner content).
        attributes["hx-swap"] = "outerHTML"
        attributes["hx-push-url"] = "true"
        attributes["data-agent"] = agent.agentId
        if (agent.agentId == selectedId) attributes["aria-current"] = "true"
        span {
          span(if (agent.isValid) "led ok" else "led crit") {}
          +agentLabel(agent.agentName)
        }
        span("host") { +"${agent.remoteAddr} · ${agent.paths.size} paths" }
      }
    }
  }

  fun FlowContent.renderDetail(
    snapshot: ProxySnapshot,
    selectedId: String?,
    oob: Boolean = false,
  ) = div("md-detail") { detailInner(snapshot, selectedId, oob) }

  private fun DIV.detailInner(
    snapshot: ProxySnapshot,
    selectedId: String?,
    oob: Boolean,
  ) {
    attributes["id"] = DETAIL_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    if (selectedId == null) {
      div("empty pad") { +"Select an agent to see its paths and recent scrapes" }
      return
    }
    val agent = snapshot.agent(selectedId)
    if (agent == null) {
      // The selected agent disconnected. Say so explicitly rather than leaving a stale pane or a blank
      // one -- a silently frozen detail view is exactly what makes an operator distrust a dashboard.
      div("empty pad gone") { +"Agent $selectedId is no longer connected" }
      return
    }

    div("hero") {
      div("hero-title") {
        span(if (agent.isValid) "led ok" else "led crit") {}
        +agentLabel(agent.agentName)
        if (agent.consolidated) span("tag") { +"consolidated" }
      }
      agent.failoverPosition?.also { position ->
        div("hero-failover") {
          +"via $position"
          if (agent.currentEndpointIndex > 0) span("tag warn") { +"failed over" }
        }
      }
      div("hero-meta") {
        +listOf(
          agent.remoteAddr,
          "host ${agent.hostName}",
          "launch ${agent.launchId}",
          "up ${humanize(Clock.System.now() - agent.connectTime)}",
          "idle ${agent.inactivity.inWholeSeconds}s",
          "backlog ${agent.backlogSize}",
          "evicted in ${agent.evictionCountdownSecs(snapshot.maxAgentInactivitySecs)}s",
        ).joinToString(" · ")
      }
    }

    div("section") {
      span { +"Registered paths" }
      span { +"${agent.paths.size}" }
    }
    if (agent.paths.isEmpty()) {
      div("empty pad") { +"This agent has registered no paths" }
    } else {
      agent.paths.forEach { path ->
        div("kv") {
          span("k") { +"/${path.path}" }
          span("v") {
            +if (path.agentIds.size > 1) "shared with ${path.agentIds.size - 1} other agent(s)" else path.labels
          }
        }
      }
    }

    val scrapes = snapshot.scrapes.filter { it.agentId == agent.agentId }
    div("section") {
      span { +"Recent scrapes" }
      span { +"${scrapes.size}" }
    }
    if (scrapes.isEmpty()) {
      div("empty pad") { +"No scrapes recorded for this agent yet" }
    } else {
      scrapes.take(MAX_DETAIL_SCRAPES).forEach { record ->
        div("kv scrape") {
          span("t") { scrapeTime(record.at) }
          span("k") { +"/${record.path}" }
          span(if (record.isSuccess) "pill ok" else "pill crit") { +"${record.statusCode}" }
          span("v") { +"${record.durationMillis} ms · ${record.contentLength} chars · ${record.outcome}" }
        }
      }
    }
  }

  /**
   * One WebSocket frame carrying every out-of-band region that changed.
   *
   * The regions are concatenated as **top-level siblings**, never wrapped in a container. The htmx
   * WebSocket extension swaps a message into the element that opened the socket, `<body>`, after htmx has
   * pulled every `hx-swap-oob` region out of it. A frame of regions alone leaves nothing behind, and the
   * extension skips an empty swap. A wrapper would be left behind, empty but still an element, and swapped
   * in as `<body>`'s whole content, wiping the page. (htmx 2's extension instead ignored every region
   * nested in a wrapper, so the page updated only on a manual reload.)
   *
   * Emits only the regions that exist for this session's layout. A swap addressed at an absent id is a
   * silent no-op, so this keeps the frame honest and small -- a path-layout session has no agent list.
   *
   * A null [layout] means the session has not yet said which page it shows, so the frame carries only the
   * status bar, which every layout has. Rendering a guess instead (the agent layout, nothing selected)
   * would replace a page's detail pane with the "select an agent" placeholder until the browser's message
   * arrived -- a flash on every connect and reconnect.
   *
   * [viewSeq] is the count the page sent with the message this frame was rendered from, stamped on the
   * status region; the page drops a frame stamped below the count it has sent since, which describes a
   * page it no longer shows. The status region goes first, so the stamp is on the frame's first tag: the
   * page matches it there, anchored at the start, which no agent-supplied value can reach.
   */
  fun pushFragment(
    snapshot: ProxySnapshot,
    selectedId: String?,
    routeBase: String,
    layout: DashboardLayout? = DashboardLayout.AGENT,
    viewSeq: Long = 0L,
  ): String =
    buildString {
      append(createHTML().span("bar-right") { statusInner(snapshot, oob = true, viewSeq) })
      when (layout) {
        DashboardLayout.AGENT -> {
          append(createHTML().div("md-list") { agentListInner(snapshot, selectedId, routeBase, oob = true) })
          append(createHTML().div("md-detail") { detailInner(snapshot, selectedId, oob = true) })
        }

        DashboardLayout.PATH -> {
          append(createHTML().div("scroller") { pathTableInner(snapshot, oob = true) })
        }

        null -> {}
      }
    }

  /**
   * The detail pane alone, for the `hx-get` a row click issues.
   *
   * Returns the `#detail` element itself, not a wrapper around it, because the row swaps it with
   * `outerHTML`: the element replaces its namesake in place, keeping the id (and the `.md-detail` layout
   * class) so the next WebSocket push still finds it. A wrapper here would nest a second `#detail` inside
   * the first, duplicating the id.
   */
  fun detailFragment(
    snapshot: ProxySnapshot,
    selectedId: String?,
  ): String = createHTML().div("md-detail") { detailInner(snapshot, selectedId, oob = false) }

  /**
   * Identity arrives at `registerAgent`, which is strictly after the transport filter created the
   * context — so a just-connected agent legitimately has none yet. Showing the raw placeholder would
   * read as corruption rather than as a normal transient state.
   *
   * Shared by both layouts so one agent never appears under two different labels.
   */
  private fun agentLabel(name: String): String = if (name == UNASSIGNED) "(registering…)" else name

  /** An agent's uptime at the precision the hero line wants: the two largest units from a day up, then one. */
  internal fun humanize(duration: Duration): String =
    duration.toComponents { days, hours, minutes, seconds, _ ->
      when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
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

  private const val UNASSIGNED = "Unassigned"
  private const val MAX_DETAIL_SCRAPES = 25

  // How long a copy button shows its result: long enough to notice after the click, brief enough that it reads
  // as the click's answer rather than as a state of the row.
  private const val COPY_MARK_MILLIS = 1500

  // An en dash for "the agent never told us", kept distinct from an empty cell, which would read as a
  // rendering bug rather than as absent data.
  private const val DASH = "\u2013"

  /**
   * htmx's config, read from a `<meta>` tag.
   *
   * `noSwap` keeps htmx 2's rule that a 4xx or 5xx response is not swapped in. htmx 4 swaps every
   * response but a 204 or 304, so a row click that failed would replace `#detail` with the error body, and
   * the pushes, which address `#detail`, would have nothing left to update.
   *
   * `ws` caps the reconnect backoff. The extension's default doubles the wait from 500 ms up to a minute,
   * resetting only on a successful open. That is tuned for public services guarding against a reconnect
   * stampede, the opposite of this dashboard, which has a handful of viewers on a LAN. Left alone, a short
   * outage grows the wait so far that the page can sit idle for tens of seconds after the proxy is already
   * back. With these values the waits run 500 ms, 1 s, then 2 s ± 250 ms: recovery within about 2 s, and
   * just enough jitter that many dashboards do not reconnect in lockstep.
   */
  private const val HTMX_CONFIG =
    """{"noSwap":[204,304,"4xx","5xx"],"ws":{"reconnectDelay":500,"reconnectMaxDelay":2000,"reconnectJitter":0.125}}"""

  // Reads the selection back out of the URL that hx-push-url already set, and tells the server, so the
  // push loop knows whether this session wants a detail pane. Also runs the connection indicator, the path table's
  // copy buttons, and the scrape times' conversion to the viewer's zone, the only things on the page that htmx does not
  // model; everything else is htmx.
  private val SELECTION_JS =
    """
    (function () {
      // htmx 4 runs the inline scripts of content it swaps in, and every back or forward navigation
      // refetches the page and swaps it into <body>, so this script runs again on each one. The body, its
      // listeners, and the socket all survive that swap, so a second run would only stack another set of
      // listeners, and one announce per navigation would become two, then three.
      if (window.__proxyUiInit) return;
      window.__proxyUiInit = true;
      function selectedId() {
        var m = window.location.pathname.match(/\/agents\/([^/]+)$/);
        return m ? m[1] : null;
      }
      function layout() {
        return /\/paths\/?$/.test(window.location.pathname) ? 'PATH' : 'AGENT';
      }
      // The extension's connection, which outlives its socket: each reconnect replaces connection.socket,
      // so this keeps the connection and reads the socket when it sends.
      var conn = null;
      // The count of messages this page has sent. The server stamps every frame with the count of the
      // message it rendered the frame from, so a frame stamped lower describes a page this one no longer
      // shows. Without it a row click raced the push loop: a tick rendered from the previous selection could
      // land after the click's own swap and put the previous agent back until the next frame.
      var seq = 0;
      function announce() {
        var s = conn && conn.socket;
        // Only on an open socket. Nothing is lost by skipping: every open announces again from the URL.
        if (s && s.readyState === WebSocket.OPEN) {
          seq += 1;
          s.send(JSON.stringify({ select: selectedId(), layout: layout(), seq: seq }));
        }
      }
      // Drops a frame rendered from an older message. The stamp is on the frame's first tag, the status
      // region, which the server writes whole; the match is anchored there, so no agent-supplied value can
      // stand in for it.
      document.body.addEventListener('htmx:ws:before:message:incoming', function (evt) {
        var data = evt.detail.message.data;
        var m = typeof data === 'string' && /^<span[^>]*\s$VIEW_ATTR="(\d+)"/.exec(data);
        if ((m ? Number(m[1]) : 0) < seq) evt.preventDefault();
      });
      // The connection indicator. The extension reconnects on its own (with the backoff capped in the
      // htmx-config meta tag); this only reflects that effort so the page does not keep claiming "live"
      // while frozen. Toggled on <body> so it persists while no push can arrive to re-render the status bar,
      // and is cleared the instant the socket reopens -- the very next push then re-renders the bar in its
      // healthy state. Deliberately not set on 'htmx:ws:before:connection': that also fires for the first
      // connect on load, and reacting to it would flash the indicator red before the initial open. Nor on
      // 'htmx:ws:error', which also reports a message that failed to send or to swap while the socket stays
      // open; a failed connect always ends in 'htmx:ws:close' too.
      //
      // A hidden tab is the other exception. The extension closes the socket while the tab is hidden and
      // reopens it when the tab is shown again, which frees the session for the proxy. That is a pause, not
      // an outage, so a close while the tab is hidden changes nothing. An outage that begins while the tab is
      // hidden still shows: the reconnect that runs when the tab is shown fails while it is visible.
      //
      // The same transition is spoken into the live region. The beacon reports this visually with colour
      // and a change of rhythm, neither of which reaches a screen reader; without this the page goes
      // silently stale, which is the one failure this dashboard exists to make impossible to miss.
      // Scoped to the connection and the result of a copy click -- announcing every pushed counter would
      // make the region unusable.
      var wasDown = false;
      function announceConn(up) {
        var region = document.getElementById('$ANNOUNCER_ID');
        if (!region) return;
        // Only transitions are news. Every failed retry closes the socket again, and rewriting the region on
        // each one would re-announce the outage every couple of seconds for as long as it lasts. Nothing to
        // say about the first successful connect either; only a recovery from a real outage.
        if (!up && !wasDown) region.textContent = 'Connection to the proxy lost. Reconnecting.';
        else if (up && wasDown) region.textContent = 'Connection to the proxy restored.';
      }
      function connected(up) {
        document.body.classList.toggle('ws-down', !up);
        announceConn(up);
        wasDown = !up;
      }
      document.body.addEventListener('htmx:ws:after:connection', function (evt) {
        conn = evt.detail.connection;
        connected(true);
        // At once: until this message arrives, the server pushes a new session only the status bar.
        announce();
      });
      document.body.addEventListener('htmx:ws:close', function () {
        if (!document.hidden) connected(false);
      });
      // htmx 4 dispatches its history events on document, which a listener on <body> never hears.
      document.addEventListener('htmx:after:history:push', announce);
      window.addEventListener('popstate', announce);
      // The copy buttons beside the path table's target URLs. One listener on <body> rather than one per
      // button: the push loop replaces the table every frame, so a button's own listener would last one
      // frame. The result mark is kept here for the same reason, by button id, and put back on that id's
      // new button after each frame until it expires.
      var mark = null;
      var markTimer = 0;
      function applyMark() {
        var btn = mark && document.getElementById(mark.id);
        if (btn) btn.classList.add(mark.cls);
      }
      function showMark(id, cls) {
        var prev = mark && document.getElementById(mark.id);
        if (prev) prev.classList.remove(mark.cls);
        mark = { id: id, cls: cls };
        applyMark();
        clearTimeout(markTimer);
        markTimer = setTimeout(function () {
          var btn = document.getElementById(id);
          if (btn) btn.classList.remove(cls);
          mark = null;
        }, $COPY_MARK_MILLIS);
      }
      document.body.addEventListener('htmx:ws:after:message:incoming', applyMark);
      // navigator.clipboard exists only in a secure context, and the dashboard is usually plain http on a
      // LAN address, where it is undefined. There the copy goes through a hidden textarea and execCommand,
      // deprecated but still the only way to write the clipboard outside a secure context.
      function writeClipboard(text) {
        if (navigator.clipboard && window.isSecureContext) return navigator.clipboard.writeText(text);
        var focused = document.activeElement;
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.top = '0';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        area.setSelectionRange(0, text.length);
        var ok = false;
        try { ok = document.execCommand('copy'); } catch (e) {}
        document.body.removeChild(area);
        // Selecting the textarea took focus; hand it back so a keyboard user stays on the button.
        if (focused && focused.focus) focused.focus({ preventScroll: true });
        return ok ? Promise.resolve() : Promise.reject();
      }
      // The result goes to the live region too: the mark is an icon and a colour, which a screen reader
      // does not report. A click is the user's own act, so this speaks once per click, never per push.
      function say(text) {
        var region = document.getElementById('$ANNOUNCER_ID');
        if (region) region.textContent = text;
      }
      document.body.addEventListener('click', function (evt) {
        var btn = evt.target.closest && evt.target.closest('button.copy');
        if (!btn) return;
        // The id, not the element: a push can replace the button before the clipboard write settles.
        var id = btn.id;
        var path = btn.getAttribute('$PROXY_PATH_ATTR');
        var what = path ? 'Proxy URL' : 'Target URL';
        writeClipboard(path ? proxyUrl(path) : btn.getAttribute('$COPY_ATTR')).then(function () {
          showMark(id, 'copied');
          say(what + ' copied.');
        }, function () {
          showMark(id, 'copy-failed');
          say('Could not copy the ' + what.toLowerCase() + '.');
        });
      });
      // A path's proxy URL, the one to curl to check it through the proxy: the host this page was opened at, which the
      // proxy can't know, and the port Prometheus scrapes, which is plain HTTP. An IPv6 host keeps its brackets.
      function proxyUrl(path) {
        return 'http://' + window.location.hostname + ':' + document.body.getAttribute('$SCRAPE_PORT_ATTR') + path;
      }
      // Scrape times arrive as <time datetime="...Z"> showing the proxy's zone, and are shown in the viewer's, the
      // zone Grafana uses, so a failure here and the spike it caused there read the same clock. Every frame and row
      // click is converted in its fragment before htmx swaps it in, so no frame shows the proxy's zone first. The
      // title gives the date and the zone's name, which the column has no room for.
      function pad(n) {
        return (n < 10 ? '0' : '') + n;
      }
      function localTimes(root) {
        root.querySelectorAll('time[datetime]').forEach(function (el) {
          var at = new Date(el.getAttribute('datetime'));
          if (isNaN(at.getTime())) return;
          el.textContent = pad(at.getHours()) + ':' + pad(at.getMinutes()) + ':' + pad(at.getSeconds());
          el.title = at.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'long' });
        });
      }
      localTimes(document);
      document.addEventListener('htmx:before:swap', function (evt) {
        (evt.detail.tasks || []).forEach(function (task) {
          if (task.fragment) localTimes(task.fragment);
        });
      });
    })();
    """.trimIndent()
}
