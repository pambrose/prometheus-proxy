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

import io.prometheus.common.dashboard.DashboardLogo.brand
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.DashboardPage.SCRAPE_PORT_ATTR
import io.prometheus.common.dashboard.DashboardPage.STATUS_ID
import io.prometheus.common.dashboard.DashboardPage.TITLE_ATTR
import io.prometheus.common.dashboard.DashboardPage.VIEW_ATTR
import io.prometheus.common.dashboard.DashboardPage.announcer
import io.prometheus.common.dashboard.DashboardPage.connectionIndicator
import io.prometheus.common.dashboard.DashboardPage.dashboardHead
import io.prometheus.common.dashboard.DashboardPage.humanize
import io.prometheus.common.dashboard.DashboardPage.pageTitle
import io.prometheus.common.dashboard.DashboardPage.statusPill
import io.prometheus.common.dashboard.DashboardPage.tabs
import io.prometheus.common.dashboard.DashboardPage.timeOfDay
import io.prometheus.common.dashboard.DashboardScript
import io.prometheus.common.dashboard.DashboardView
import io.prometheus.common.dashboard.TabIcon
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import io.prometheus.proxy.ScrapeRecord
import io.prometheus.proxy.dashboard.ActivityHtml.activityTable
import io.prometheus.proxy.dashboard.ActivityHtml.renderActivity
import io.prometheus.proxy.dashboard.PathTableHtml.linkIconStyle
import io.prometheus.proxy.dashboard.PathTableHtml.pathTable
import io.prometheus.proxy.dashboard.PathTableHtml.renderPathTable
import kotlinx.html.HTML
import kotlinx.html.SPAN
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlin.time.Clock
import io.prometheus.common.dashboard.RowFilter
import io.prometheus.common.dashboard.DashboardPage.barEnd
import io.prometheus.common.dashboard.DashboardPage.barTail
import io.prometheus.common.dashboard.SettingsHtml.settingsInner

/**
 * Which view a session is looking at.
 *
 * Carried per session rather than configured per proxy, because the layouts answer different
 * questions and an operator switches between them mid-incident: the agent view asks "what is this agent
 * doing", the path view asks "why isn't this target scraping", and the activity view asks "what just happened",
 * which is where a refusal shows, since a refused agent or path is in neither of the others. The settings view asks
 * "how is this proxy set up", as it resolved its flags, env vars, config, and defaults.
 *
 * @param segment the layout's route below the dashboard's base, which the agent layout is served at itself
 * @param title the layout's name in the nav
 * @param icon the layout's icon in the nav, before its name
 * @param searchFields what the search above the layout's table matches, for its placeholder; the agent layout has none
 */
internal enum class DashboardLayout(
  override val segment: String,
  override val title: String,
  override val icon: TabIcon,
  override val searchFields: String = "",
) : DashboardView {
  AGENT("", "Agents", TabIcon.AGENTS),
  PATH("paths", "Paths", TabIcon.PATHS, "path, agent, target, or identity"),
  ACTIVITY("activity", "Activity", TabIcon.ACTIVITY, "event, agent, identity, path, or detail"),
  SETTINGS("settings", "Settings", TabIcon.SETTINGS),
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
  const val AGENT_LIST_ID = "agent-list"
  const val DETAIL_ID = "detail"
  const val PATH_TABLE_ID = "path-table"
  const val ACTIVITY_ID = "activity"

  // The two kinds of copy button, by row. Their ids differ before the path does ("copy-t..." and "copy-p..."), so no
  // path can give one kind the id of the other.
  const val COPY_TARGET_ID_PREFIX = "copy-target-"
  const val COPY_PROXY_ID_PREFIX = "copy-proxy-"

  // A row's links (PathTableHtml): the prefix of each one's id, which the link's place in proxy.dashboard.links and
  // then the path follow, so a link keeps its focus across the push that replaces the table.
  const val LINK_ID_PREFIX = "link-"

  // No region id starts with it, so an agent id, which an agent behind nginx names itself, can't make a row's id
  // equal one.
  const val AGENT_ROW_ID_PREFIX = "agent-row-"

  // What demos/basics/bin/screenshots.sh needs about an agent, as data: on each agent-list row, its id, its name as
  // the row shows it, and its path count, and on its detail's Recent scrapes header, their count. The script matched
  // the row's text before, and every change to its look, a tag, an LED, or a word, broke a regex.
  const val AGENT_ATTR = "data-agent"
  const val AGENT_NAME_ATTR = "data-agent-name"
  const val PATH_COUNT_ATTR = "data-path-count"
  const val SCRAPE_COUNT_ATTR = "data-scrape-count"

  /**
   * The full page. Only served on a real navigation; every later update arrives over the socket. With [loginEnabled],
   * the bar ends in a Log out button.
   */
  fun HTML.renderPage(
    snapshot: ProxySnapshot,
    selectedId: String?,
    routeBase: String,
    layout: DashboardLayout = DashboardLayout.AGENT,
    filter: RowFilter = RowFilter.NONE,
    loginEnabled: Boolean = false,
  ) {
    // Declared so assistive tech pronounces agent names, paths, and status text as English rather than
    // falling back to the listener's system locale.
    attributes["lang"] = "en"
    head {
      dashboardHead(pageTitle(snapshot), routeBase) {
        linkIconStyle(snapshot.links, routeBase)
        if (snapshot.screensPage) style { unsafe { raw(ProxyDashboardCss.SCREENS_LINK_CSS) } }
      }
    }
    body {
      // Loading hx-ws.min.js is what enables the extension; htmx 4 has no hx-ext.
      attributes["hx-ws:connect"] = "$routeBase/events"
      // For the proxy URLs the page builds (see PathTableHtml's pathCell). On <body>, which no push replaces, since
      // the port doesn't change.
      attributes[SCRAPE_PORT_ATTR] = "${snapshot.scrapePort}"

      div("bar") {
        brand(snapshot.dashboardName)
        renderNav(routeBase, layout)
        barTail {
          renderStatus(snapshot)
          if (snapshot.screensPage) screensLink()
          barEnd(loginEnabled, routeBase)
        }
      }
      announcer()

      when (layout) {
        DashboardLayout.AGENT -> {
          main("md") {
            renderAgentList(snapshot, selectedId, routeBase)
            renderDetail(snapshot, selectedId)
          }
        }

        DashboardLayout.PATH -> {
          main {
            renderPathTable(snapshot, routeBase, filter)
          }
        }

        DashboardLayout.ACTIVITY -> {
          main {
            renderActivity(snapshot, routeBase, filter)
          }
        }

        DashboardLayout.SETTINGS -> {
          main { div { settingsInner(snapshot.settings, oob = false) } }
        }
      }
      // What htmx does not model (see DashboardScript): above all, telling the server which agent THIS session is
      // viewing, so the push loop knows whether to send a detail pane. hx-push-url already puts the selection in
      // the URL, so this reads it back rather than tracking state of its own.
      script { unsafe { raw(SCRIPT) } }
    }
  }

  // The link to the screens page, outside the base, so the path alone. In the top window: in the page's own proxy tab,
  // it would otherwise open the page inside itself.
  private fun FlowContent.screensLink() =
    a(href = ProxyDashboardService.SCREENS_PATH, classes = "screens-link") {
      target = "_top"
      +"Screens"
    }

  /** The layout switcher: a tab per [DashboardLayout] (see [DashboardPage.tabs]). */
  fun FlowContent.renderNav(
    routeBase: String,
    layout: DashboardLayout,
  ) = tabs(DashboardLayout.entries, layout, routeBase)

  /** Status counters. Every push renders them, on a change or a tick: the tick keeps the drifting ones live. */
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
    // Every page has the bar and every push renders it, so the tab's title can follow on any layout. Set before the
    // first child: the HTML streams out as it is built, so a tag's attributes can't change once its content has begun.
    attributes[TITLE_ATTR] = pageTitle(snapshot)
    val departed = snapshot.paths.count { it.isDeparted }
    val failing = snapshot.paths.count { it.isFailing }
    connectionIndicator()
    span { +"${snapshot.health.agentCount} agents" }
    span { +"${snapshot.health.pathCount} paths" }
    // health.pathCount counts registrations, so a departed path is deliberately absent from it -- which
    // read as the bar contradicting the table it sits above ("0 paths" over three visible rows). Naming
    // the departed count separately keeps both numbers true and explains the difference.
    if (failing > 0)
      span("crit") { +"$failing failing" }
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
        attributes[AGENT_ATTR] = agent.agentId
        attributes[AGENT_NAME_ATTR] = agentLabel(agent.agentName)
        attributes[PATH_COUNT_ATTR] = "${agent.paths.size}"
        if (agent.agentId == selectedId) attributes["aria-current"] = "true"
        span {
          span(if (agent.isValid) "led ok" else "led crit") {}
          +agentLabel(agent.agentName)
          // A tag on the name's line rather than text on the address's, which the list's width wraps.
          identityLabel(agent.identity)?.also { span("tag") { +it } }
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
      goneAgent(snapshot, selectedId)
      return
    }

    agentHero(agent, snapshot.evictAfterSecs)

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
            +if (path.agentIds.size > 1) "shared with ${path.agentIds.size - 1} other agent(s)" else "${path.labels}"
          }
        }
      }
    }

    recentScrapes(snapshot.scrapesOf(agent.agentId))
  }

  // The selected agent disconnected. Say so explicitly rather than leaving a stale pane or a blank one -- a silently
  // frozen detail view is exactly what makes an operator distrust a dashboard. Named as the other views named it, never
  // by its id, a counter none of them shows.
  private fun DIV.goneAgent(
    snapshot: ProxySnapshot,
    agentId: String,
  ) {
    val shown = snapshot.goneAgentName(agentId)?.let(::agentLabel) ?: "The selected agent"
    div("empty pad gone") { +"$shown is no longer connected" }
  }

  // The detail's head: the agent's name and state, how it reached this proxy when it has failover configured, and a
  // line of what else to know about it. [evictAfterSecs] is the snapshot's: null while the stale-agent check doesn't
  // run, when the line has no countdown.
  private fun DIV.agentHero(
    agent: AgentView,
    evictAfterSecs: Long?,
  ) {
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
        +listOfNotNull(
          agent.remoteAddr,
          "host ${agent.hostName}",
          identityLabel(agent.identity)?.let { "identity $it" },
          "launch ${agent.launchId}",
          "up ${humanize(Clock.System.now() - agent.connectTime)}",
          "idle ${agent.inactivity.inWholeSeconds}s",
          "backlog ${agent.backlogSize}",
          // Only while the stale-agent check runs: with it off, no agent is ever evicted, and an idle one read
          // "evicted in 0s" for as long as it stayed.
          evictAfterSecs?.let { "evicted in ${agent.evictionCountdownSecs(it)}s" },
        ).joinToString(" · ")
      }
    }
  }

  // The detail's Recent scrapes: a header carrying their count as data too, for demos/basics/bin/screenshots.sh,
  // then a row for each. The snapshot has already cut them to the agent's latest ProxySnapshot.MAX_DETAIL_SCRAPES, so
  // the count is the rows shown.
  private fun DIV.recentScrapes(scrapes: List<ScrapeRecord>) {
    div("section") {
      attributes[SCRAPE_COUNT_ATTR] = "${scrapes.size}"
      span { +"Recent scrapes" }
      span { +"${scrapes.size}" }
    }
    if (scrapes.isEmpty()) {
      div("empty pad") { +"No scrapes recorded for this agent yet" }
    } else {
      scrapes.forEach { record ->
        div("kv scrape") {
          span("t") { timeOfDay(record.at) }
          span("k") { +"/${record.path}" }
          statusPill(record.statusCode, record.isSuccess)
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
   *
   * [changed] false leaves the Activity table out of an Activity frame, and the settings out of a Settings frame, which
   * then carries the status bar alone: the push loop passes it when the session's page already shows what this frame
   * would render (see io.prometheus.common.dashboard.ActivityTable and io.prometheus.common.dashboard.SettingsShown).
   * Every other region is rendered whatever it says.
   */
  fun pushFragment(
    snapshot: ProxySnapshot,
    selectedId: String?,
    routeBase: String,
    layout: DashboardLayout? = DashboardLayout.AGENT,
    viewSeq: Long = 0L,
    filter: RowFilter = RowFilter.NONE,
    changed: Boolean = true,
  ): String =
    buildString {
      append(createHTML().span("bar-right") { statusInner(snapshot, oob = true, viewSeq) })
      when (layout) {
        DashboardLayout.AGENT -> {
          append(createHTML().div("md-list") { agentListInner(snapshot, selectedId, routeBase, oob = true) })
          append(createHTML().div("md-detail") { detailInner(snapshot, selectedId, oob = true) })
        }

        DashboardLayout.PATH -> {
          // The region alone, not its scroller, which stays on the page with its scroll position.
          append(createHTML().div { pathTable(snapshot, filter, oob = true) })
        }

        DashboardLayout.ACTIVITY -> {
          if (changed) append(createHTML().div { activityTable(snapshot, filter, oob = true) })
        }

        DashboardLayout.SETTINGS -> {
          if (changed) append(createHTML().div { settingsInner(snapshot.settings, oob = true) })
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
   * Shared by the Agents, Paths, and Activity views so one agent never appears under two different labels.
   */
  internal fun agentLabel(name: String): String = if (name == UNASSIGNED) "(registering…)" else name

  /**
   * The page's title: the dashboard's name, after a count of the paths failing or gone while there are any, so a
   * dashboard left in a background tab still says when something is wrong. Counted by [PathView.isProblem], as the
   * "Problems only" filter counts them, so the title and the filtered table agree.
   */
  private fun pageTitle(snapshot: ProxySnapshot): String =
    pageTitle(snapshot.dashboardName, snapshot.paths.count { it.isProblem })

  // The page's script (DashboardScript), for the proxy's layouts: the agent layout at the base, an agent selected at
  // agents/<id>.
  private val SCRIPT =
    DashboardScript(subject = "proxy", views = DashboardLayout.entries, selectionSegment = "agents").js

  private const val UNASSIGNED = "Unassigned"
}
