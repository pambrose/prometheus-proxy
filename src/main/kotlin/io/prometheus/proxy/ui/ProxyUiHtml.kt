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

package io.prometheus.proxy.ui

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Server-rendered markup for the operational web UI.
 *
 * Everything is rendered here rather than in the browser: the WebSocket carries HTML fragments, not
 * JSON, so there is no client-side templating and no duplicated view logic. Fragments are marked
 * `hx-swap-oob` and carry stable ids, which lets a single WebSocket frame update the agent list and the
 * detail pane independently without re-rendering the page.
 */
internal object ProxyUiHtml {
  private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

  const val AGENT_LIST_ID = "agent-list"
  const val DETAIL_ID = "detail"
  const val STATUS_ID = "status-bar"

  /** The full page. Only served on a real navigation; every later update arrives over the socket. */
  fun HTML.renderPage(
    snapshot: ProxySnapshot,
    selectedId: String?,
    uiPath: String,
  ) {
    head {
      title("prometheus-proxy")
      meta(name = "viewport", content = "width=device-width, initial-scale=1")
      style { unsafe { raw(CSS) } }
      script(src = "$uiPath/assets/htmx.min.js") {}
      script(src = "$uiPath/assets/ws.js") {}
    }
    body {
      attributes["hx-ext"] = "ws"
      attributes["ws-connect"] = "$uiPath/events"

      div("bar") {
        span("brand") { +"prometheus-proxy" }
        renderStatus(snapshot)
      }
      div("md") {
        renderAgentList(snapshot, selectedId, uiPath)
        renderDetail(snapshot, selectedId)
      }
      // The one piece htmx does not model: telling the server which agent THIS session is viewing, so
      // the push loop knows whether to send a detail pane. hx-push-url already puts the selection in
      // the URL, so this reads it back rather than tracking state of its own.
      script { unsafe { raw(SELECTION_JS) } }
    }
  }

  /** Status counters. Driven by the timer rather than the event bus -- these drift, they do not change. */
  fun FlowContent.renderStatus(
    snapshot: ProxySnapshot,
    oob: Boolean = false,
  ) {
    span("bar-right") {
      attributes["id"] = STATUS_ID
      if (oob) attributes["hx-swap-oob"] = "true"
      span("live") {
        span("beacon") {}
        +"live"
      }
      span { +"${snapshot.health.agentCount} agents" }
      span { +"${snapshot.health.pathCount} paths" }
      val h = snapshot.health
      span(if (h.chunkContextHealthy && h.scrapeMapHealthy) "ok" else "warn") {
        +"chunks ${h.chunkContextSize}/${h.chunkContextThreshold} · scrapes ${h.scrapeMapSize}/${h.scrapeMapThreshold}"
      }
    }
  }

  fun FlowContent.renderAgentList(
    snapshot: ProxySnapshot,
    selectedId: String?,
    uiPath: String,
    oob: Boolean = false,
  ) = div("md-list") {
    attributes["id"] = AGENT_LIST_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    div("md-head") {
      span { +"Agents" }
      span { +"${snapshot.agents.size}" }
    }
    if (snapshot.agents.isEmpty()) {
      div("empty") { +"No agents connected" }
      return@div
    }
    snapshot.agents.forEach { agent ->
      button(classes = "md-row") {
        attributes["hx-get"] = "$uiPath/agents/${agent.agentId}"
        attributes["hx-target"] = "#$DETAIL_ID"
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
  ) = div("md-detail") {
    attributes["id"] = DETAIL_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    if (selectedId == null) {
      div("empty pad") { +"Select an agent to see its paths and recent scrapes" }
      return@div
    }
    val agent = snapshot.agent(selectedId)
    if (agent == null) {
      // The selected agent disconnected. Say so explicitly rather than leaving a stale pane or a blank
      // one -- a silently frozen detail view is exactly what makes an operator distrust a dashboard.
      div("empty pad gone") { +"Agent $selectedId is no longer connected" }
      return@div
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

  /** One WebSocket frame carrying every out-of-band region that changed. */
  fun pushFragment(
    snapshot: ProxySnapshot,
    selectedId: String?,
    uiPath: String,
  ): String =
    createHTML().div {
      renderAgentList(snapshot, selectedId, uiPath, oob = true)
      renderDetail(snapshot, selectedId, oob = true)
      renderStatus(snapshot, oob = true)
    }

  /** The detail pane alone, for the `hx-get` a row click issues. */
  fun detailFragment(
    snapshot: ProxySnapshot,
    selectedId: String?,
  ): String = createHTML().div { renderDetail(snapshot, selectedId) }

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

  private const val UNASSIGNED = "Unassigned"
  private const val MAX_DETAIL_SCRAPES = 25

  // Reads the selection back out of the URL that hx-push-url already set, and tells the server, so the
  // push loop knows whether this session wants a detail pane. Kept deliberately tiny: everything else
  // on the page is htmx.
  private val SELECTION_JS =
    """
    (function () {
      function selectedId() {
        var m = window.location.pathname.match(/\/agents\/([^/]+)$/);
        return m ? m[1] : null;
      }
      function announce(evt) {
        var socket = evt && evt.detail && evt.detail.socketWrapper;
        if (socket) window.__proxyUiSocket = socket;
        var s = window.__proxyUiSocket;
        if (s) s.send(JSON.stringify({ select: selectedId() }));
      }
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
    .live { display:inline-flex; align-items:center; gap:6px; color:var(--ok); }
    .beacon { width:7px; height:7px; border-radius:50%; background:var(--ok); animation:pulse 2.4s ease-out infinite; }
    @keyframes pulse { 0%{opacity:1} 70%{opacity:.4} 100%{opacity:1} }
    @media (prefers-reduced-motion: reduce) { .beacon { animation:none } }
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
    @media (max-width:720px) { .md { grid-template-columns:1fr; } .md-list { border-right:0; border-bottom:1px solid var(--line); } }
    """.trimIndent()
}
