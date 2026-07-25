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

import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.SPAN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.head
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
import kotlinx.html.tr
import kotlinx.html.unsafe
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
  ;

  companion object {
    /** Derived from the request path, so the layout is bookmarkable and survives a reload. */
    fun of(path: String): DashboardLayout = if (path.trimEnd('/').endsWith("/paths")) PATH else AGENT
  }
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

  /** The full page. Only served on a real navigation; every later update arrives over the socket. */
  fun HTML.renderPage(
    snapshot: ProxySnapshot,
    selectedId: String?,
    dashboardPath: String,
    layout: DashboardLayout = DashboardLayout.AGENT,
  ) {
    head {
      title("prometheus-proxy")
      meta(name = "viewport", content = "width=device-width, initial-scale=1")
      style { unsafe { raw(CSS) } }
      script(src = "$dashboardPath/assets/htmx.min.js") {}
      script(src = "$dashboardPath/assets/ws.js") {}
    }
    body {
      attributes["hx-ext"] = "ws"
      attributes["ws-connect"] = "$dashboardPath/events"

      div("bar") {
        span("brand") { +"prometheus-proxy" }
        renderNav(dashboardPath, layout)
        renderStatus(snapshot)
      }
      when (layout) {
        DashboardLayout.AGENT -> {
          div("md") {
            renderAgentList(snapshot, selectedId, dashboardPath)
            renderDetail(snapshot, selectedId)
          }
        }

        DashboardLayout.PATH -> {
          renderPathTable(snapshot)
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
    dashboardPath: String,
    layout: DashboardLayout,
  ) = nav("nav") {
    a(href = dashboardPath, classes = if (layout == DashboardLayout.AGENT) "on" else null) {
      if (layout == DashboardLayout.AGENT) attributes["aria-current"] = "page"
      +"Agents"
    }
    a(href = "$dashboardPath/paths", classes = if (layout == DashboardLayout.PATH) "on" else null) {
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
      thead {
        tr {
          th { +"Path" }
          th { +"Agent(s)" }
          th { +"Target" }
          th { +"Src" }
          th { +"Last scrape" }
          th(classes = "num") { +"Status" }
          th(classes = "num") { +"Duration" }
        }
      }
      tbody {
        snapshot.paths.forEach { path ->
          tr(if (path.isDeparted) "departed" else null) {
            td { +"/${path.path}" }
            td {
              // A departed path still names who was serving it -- that is usually the whole answer.
              +(path.servingAgent ?: DASH)
              if (path.isDeparted) span("tag crit") { +"gone" }
              if (path.additionalAgents > 0) span("dim") { +" +${path.additionalAgents}" }
            }
            td("dim") { +path.targetUrl.ifEmpty { DASH } }
            td("dim") { +sourceLabel(path.pathSource) }
            td("dim") { +(path.lastScrape?.let { timeFormat.format(it.at) } ?: DASH) }
            td("num") {
              val scrape = path.lastScrape
              if (scrape == null)
                span("dim") { +DASH }
              else
                span(if (scrape.isSuccess) "pill ok" else "pill crit") { +"${scrape.statusCode}" }
            }
            td("num dim") { +(path.lastScrape?.let { "${it.durationMillis} ms" } ?: DASH) }
          }
        }
      }
    }
  }

  /** Status counters. Driven by the timer rather than the event bus -- these drift, they do not change. */
  fun FlowContent.renderStatus(
    snapshot: ProxySnapshot,
    oob: Boolean = false,
  ) = span("bar-right") { statusInner(snapshot, oob) }

  private fun SPAN.statusInner(
    snapshot: ProxySnapshot,
    oob: Boolean,
  ) {
    attributes["id"] = STATUS_ID
    if (oob) attributes["hx-swap-oob"] = "true"
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
    val h = snapshot.health
    span(if (h.chunkContextHealthy && h.scrapeMapHealthy) "ok" else "warn") {
      +"chunks ${h.chunkContextSize}/${h.chunkContextThreshold} · scrapes ${h.scrapeMapSize}/${h.scrapeMapThreshold}"
    }
  }

  fun FlowContent.renderAgentList(
    snapshot: ProxySnapshot,
    selectedId: String?,
    dashboardPath: String,
    oob: Boolean = false,
  ) = div("md-list") { agentListInner(snapshot, selectedId, dashboardPath, oob) }

  private fun DIV.agentListInner(
    snapshot: ProxySnapshot,
    selectedId: String?,
    dashboardPath: String,
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
        attributes["hx-get"] = "$dashboardPath/agents/${agent.agentId}"
        attributes["hx-target"] = "#$DETAIL_ID"
        // outerHTML, so the returned #detail element replaces the old one in place rather than nesting a
        // second #detail inside it (the fragment IS the element, not its inner content).
        attributes["hx-swap"] = "outerHTML"
        attributes["hx-push-url"] = "true"
        attributes["data-agent"] = agent.agentId
        if (agent.agentId == selectedId) attributes["aria-current"] = "true"
        span {
          span(if (agent.isValid) "led ok" else "led crit") {}
          +agent.displayName()
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
        +agent.displayName()
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
          "up ${humanize(Duration.between(agent.connectTime, Instant.now()))}",
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
          span("t") { +timeFormat.format(record.at) }
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
   * WebSocket extension applies a message by iterating only the *immediate* children of the parsed
   * fragment and calling `oobSwap` on each; a wrapping element would be the sole child it sees, it has no
   * id to match, and the real regions nested inside it would be silently ignored — the whole page would
   * then update only on a manual reload.
   *
   * Emits only the regions that exist for this session's layout. A swap addressed at an absent id is a
   * silent no-op, so this keeps the frame honest and small -- a path-layout session has no agent list.
   */
  fun pushFragment(
    snapshot: ProxySnapshot,
    selectedId: String?,
    dashboardPath: String,
    layout: DashboardLayout = DashboardLayout.AGENT,
  ): String =
    buildString {
      when (layout) {
        DashboardLayout.AGENT -> {
          append(createHTML().div("md-list") { agentListInner(snapshot, selectedId, dashboardPath, oob = true) })
          append(createHTML().div("md-detail") { detailInner(snapshot, selectedId, oob = true) })
        }

        DashboardLayout.PATH -> {
          append(createHTML().div("scroller") { pathTableInner(snapshot, oob = true) })
        }
      }
      append(createHTML().span("bar-right") { statusInner(snapshot, oob = true) })
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

  private fun AgentView.displayName(): String =
    // Identity arrives at registerAgent, which is strictly after the transport filter created this
    // context -- so a just-connected agent legitimately has none yet. Showing the raw placeholder
    // would read as corruption rather than as a normal transient state.
    if (agentName == UNASSIGNED) "(registering…)" else agentName

  private fun humanize(duration: Duration): String =
    when {
      duration.toDays() > 0 -> "${duration.toDays()}d ${duration.toHoursPart()}h"
      duration.toHours() > 0 -> "${duration.toHours()}h ${duration.toMinutesPart()}m"
      duration.toMinutes() > 0 -> "${duration.toMinutes()}m"
      else -> "${duration.seconds}s"
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

  // An en dash for "the agent never told us", kept distinct from an empty cell, which would read as a
  // rendering bug rather than as absent data.
  private const val DASH = "\u2013"

  // Reads the selection back out of the URL that hx-push-url already set, and tells the server, so the
  // push loop knows whether this session wants a detail pane. Kept deliberately tiny: everything else
  // on the page is htmx.
  private val SELECTION_JS =
    """
    (function () {
      // htmx-ext-ws defaults to 'full-jitter' backoff: the retry window doubles on every failed attempt
      // (1s, 2s, 4s ... capped at 64s) and only resets on a successful open. That is tuned for public
      // services guarding against a reconnect stampede -- the opposite of this dashboard, which has a
      // handful of viewers on a LAN. Left alone, a short outage grows the window so far that the page can
      // sit idle for tens of seconds after the proxy is already back. Replace it with a low, lightly
      // jittered cap: recover within ~2s, keep just enough jitter that many dashboards do not reconnect
      // in lockstep. Read on each retry, so setting it before the first disconnect is sufficient.
      if (window.htmx) {
        htmx.config.wsReconnectDelay = function (retryCount) {
          return Math.min(500 * Math.pow(2, retryCount), 2000) + Math.random() * 250;
        };
      }
      function selectedId() {
        var m = window.location.pathname.match(/\/agents\/([^/]+)$/);
        return m ? m[1] : null;
      }
      function layout() {
        return /\/paths\/?$/.test(window.location.pathname) ? 'PATH' : 'AGENT';
      }
      function announce(evt) {
        var socket = evt && evt.detail && evt.detail.socketWrapper;
        if (socket) window.__proxyUiSocket = socket;
        var s = window.__proxyUiSocket;
        if (s) s.send(JSON.stringify({ select: selectedId(), layout: layout() }));
      }
      // The connection indicator. htmx-ext-ws reconnects on its own (abnormal-closure backoff); this
      // only reflects that effort so the page does not keep claiming "live" while frozen. Toggled on
      // <body> so it persists while no push can arrive to re-render the status bar, and is cleared the
      // instant the socket reopens -- the very next push then re-renders the bar in its healthy state.
      // Deliberately not set on 'htmx:wsConnecting': that also fires for the first connect on load, and
      // reacting to it would flash the indicator red before the initial open.
      function connected(up) { document.body.classList.toggle('ws-down', !up); }
      document.body.addEventListener('htmx:wsOpen', function () { connected(true); });
      document.body.addEventListener('htmx:wsClose', function () { connected(false); });
      document.body.addEventListener('htmx:wsError', function () { connected(false); });
      document.body.addEventListener('htmx:wsOpen', announce);
      document.body.addEventListener('htmx:pushedIntoHistory', announce);
      window.addEventListener('popstate', announce);
    })();
    """.trimIndent()

  private val CSS =
    """
    :root {
      --bg:#f2f5f8; --surface:#fff; --surface-2:#e9edf2; --surface-3:#dde3ea;
      --line:#cfd7e0; --line-soft:#e3e9ef; --ink:#131820; --ink-2:#48525f; --ink-3:#78828f;
      --accent:#c8461f; --ok:#1f8a4c; --ok-soft:#e3f4ea; --warn:#a8710a; --warn-soft:#f9f0da;
      --crit:#c02a20; --crit-soft:#fbe6e4;
      --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
    }
    @media (prefers-color-scheme: dark) {
      :root {
        --bg:#0d1015; --surface:#151a21; --surface-2:#1c222b; --surface-3:#242c37;
        --line:#2b333f; --line-soft:#212934; --ink:#e7ebf1; --ink-2:#a4aebc; --ink-3:#6f7986;
        --accent:#ff7043; --ok:#3fb950; --ok-soft:#12251a; --warn:#d29922; --warn-soft:#2a2110;
        --crit:#f85149; --crit-soft:#2d1512;
      }
    }
    * { box-sizing:border-box; margin:0; padding:0; }
    body { background:var(--bg); color:var(--ink); font-family:var(--mono); font-size:12.5px; line-height:1.5; }
    .bar { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px;
           padding:11px 15px; border-bottom:1px solid var(--line); background:var(--surface); }
    .brand { font-weight:600; }
    .bar-right { display:flex; align-items:center; gap:14px; color:var(--ink-3); font-size:11.5px; }
    .bar-right .ok { color:var(--ok); } .bar-right .warn { color:var(--warn); }
    .conn { display:inline-flex; align-items:center; gap:6px; color:var(--ok); }
    .conn .conn-down { display:none; }
    .beacon { width:7px; height:7px; border-radius:50%; background:var(--ok); animation:pulse 2.4s ease-out infinite; }
    @keyframes pulse { 0%{opacity:1} 70%{opacity:.4} 100%{opacity:1} }
    /* Disconnected: green calm becomes red blink, and the label swaps to name the effort. The faster,
       harder blink reads as "trying" rather than the solid dot of something simply dead. Driven off the
       body class so a frozen status bar still flips, and it wins over the healthy rules by specificity. */
    body.ws-down .conn { color:var(--crit); }
    body.ws-down .conn .conn-live { display:none; }
    body.ws-down .conn .conn-down { display:inline; }
    body.ws-down .beacon { background:var(--crit); animation:blink 1s steps(2, start) infinite; }
    @keyframes blink { 0%{opacity:1} 50%{opacity:.25} 100%{opacity:1} }
    @media (prefers-reduced-motion: reduce) { .beacon, body.ws-down .beacon { animation:none } }
    .md { display:grid; grid-template-columns:262px 1fr; min-height:calc(100vh - 44px); }
    .md-list { border-right:1px solid var(--line); background:var(--surface-2); }
    .md-head, .section { display:flex; justify-content:space-between; padding:9px 15px; font-size:10px;
      letter-spacing:.09em; text-transform:uppercase; color:var(--ink-3); font-weight:600;
      border-bottom:1px solid var(--line); background:var(--surface-2); }
    .md-row { display:block; width:100%; text-align:left; font:inherit; cursor:pointer; background:none;
      border:0; border-bottom:1px solid var(--line-soft); border-left:3px solid transparent;
      padding:10px 15px; color:var(--ink); }
    .md-row:hover { background:var(--surface-3); }
    .md-row:focus-visible { outline:2px solid var(--accent); outline-offset:-2px; }
    .md-row[aria-current="true"] { background:var(--surface); border-left-color:var(--accent); }
    .md-row .host { display:block; color:var(--ink-3); font-size:11px; margin-top:2px; }
    .md-detail { background:var(--surface); }
    .hero { padding:14px 18px; border-bottom:1px solid var(--line); }
    .hero-title { font-size:15px; font-weight:640; display:flex; align-items:center; gap:9px; }
    .hero-meta { color:var(--ink-3); font-size:11.5px; margin-top:5px; }
    .hero-failover { font-size:11.5px; margin-top:5px; display:flex; align-items:center; gap:8px; }
    .tag.warn { background:var(--warn-soft); color:var(--warn); }
    .tag { font-size:10px; background:var(--surface-3); color:var(--ink-2); padding:2px 7px; border-radius:20px; }
    .kv { display:flex; gap:10px; padding:7px 18px; border-bottom:1px solid var(--line-soft); align-items:center; }
    .kv .k { min-width:220px; } .kv .v { color:var(--ink-3); } .kv .t { color:var(--ink-3); min-width:64px; }
    .led { display:inline-block; width:7px; height:7px; border-radius:50%; margin-right:7px; }
    .led.ok { background:var(--ok); } .led.crit { background:var(--crit); }
    .pill { font-size:10.5px; font-weight:600; padding:2px 7px; border-radius:20px; }
    .pill.ok { background:var(--ok-soft); color:var(--ok); }
    .pill.crit { background:var(--crit-soft); color:var(--crit); }
    .empty { color:var(--ink-3); padding:14px 15px; }
    .empty.pad { padding:22px 18px; }
    .empty.gone { color:var(--crit); }
    .nav { display:flex; gap:4px; margin-right:auto; margin-left:18px; }
    .nav a { color:var(--ink-3); text-decoration:none; padding:3px 10px; border-radius:20px; }
    .nav a:hover { background:var(--surface-3); color:var(--ink); }
    .nav a:focus-visible { outline:2px solid var(--accent); outline-offset:1px; }
    .nav a.on { background:var(--surface-3); color:var(--ink); font-weight:600; }
    /* The table is the one region that can outgrow a narrow viewport, so it scrolls inside its own
       container rather than letting the page body scroll sideways. */
    .scroller { overflow-x:auto; background:var(--surface); }
    .ptable { width:100%; border-collapse:collapse; white-space:nowrap; }
    .ptable th { text-align:left; font-size:10px; letter-spacing:.09em; text-transform:uppercase;
      color:var(--ink-3); font-weight:600; padding:9px 15px; background:var(--surface-2);
      border-bottom:1px solid var(--line); position:sticky; top:0; }
    .ptable td { padding:8px 15px; border-bottom:1px solid var(--line-soft); }
    .ptable .num { text-align:right; font-variant-numeric:tabular-nums; }
    .ptable .dim { color:var(--ink-3); }
    .ptable tr:hover td { background:var(--surface-2); }
    .ptable tr.departed td:first-child { color:var(--crit); }
    .tag.crit { background:var(--crit-soft); color:var(--crit); margin-left:7px; }
    @media (max-width:720px) { .md { grid-template-columns:1fr; } .md-list { border-right:0; border-bottom:1px solid var(--line); } }
    """.trimIndent()
}
