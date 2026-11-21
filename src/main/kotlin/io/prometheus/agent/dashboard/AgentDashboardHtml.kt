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

package io.prometheus.agent.dashboard

import io.ktor.http.encodeURLPathPart
import io.prometheus.agent.AgentPathManager.RegistrationFailure
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.DashboardPage.DASH
import io.prometheus.common.dashboard.DashboardPage.STATUS_ID
import io.prometheus.common.dashboard.DashboardPage.TITLE_ATTR
import io.prometheus.common.dashboard.DashboardPage.VIEW_ATTR
import io.prometheus.common.dashboard.DashboardPage.announcer
import io.prometheus.common.dashboard.DashboardPage.connectionIndicator
import io.prometheus.common.dashboard.DashboardPage.dashboardHead
import io.prometheus.common.dashboard.DashboardPage.humanize
import io.prometheus.common.dashboard.DashboardPage.logoutForm
import io.prometheus.common.dashboard.DashboardPage.pageTitle
import io.prometheus.common.dashboard.DashboardPage.size
import io.prometheus.common.dashboard.DashboardPage.tabs
import io.prometheus.common.dashboard.DashboardPage.timeOfDay
import io.prometheus.common.dashboard.DashboardScript
import io.prometheus.common.dashboard.DashboardView
import kotlinx.html.DIV
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.SPAN
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.main
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.unsafe
import kotlin.time.Clock

/**
 * Which view of the agent's dashboard a session is looking at: its paths, where each stands with the proxy, or its
 * connection to the proxy, with its settings.
 *
 * @param segment the view's route below the dashboard's base, which the Paths view is served at itself
 * @param title the view's name in the tabs
 */
internal enum class AgentLayout(
  override val segment: String,
  override val title: String,
) : DashboardView {
  PATHS("", "Paths"),
  CONNECTION("connection", "Connection"),
}

/**
 * What a page of the agent's dashboard shows: its [layout], the path selected in it, and the count of messages its
 * browser has sent, [seq], which every frame is stamped with, as on the proxy's dashboard. One value, so a push reads
 * all of it at once.
 */
internal data class AgentView(
  val layout: AgentLayout = AgentLayout.PATHS,
  val selectedPath: String? = null,
  val seq: Long = 0L,
)

/**
 * Server-rendered markup for the agent's dashboard, on the design system the proxy's shares ([DashboardPage]).
 *
 * It shows what only the agent knows: its connection when the proxy can't see it, each path the proxy refused, and
 * why, and its filters, discovery, and settings. As on the proxy's, every update is a WebSocket frame of out-of-band
 * regions, rendered here.
 */
internal object AgentDashboardHtml {
  const val PATH_LIST_ID = "path-list"
  const val DETAIL_ID = "detail"
  const val CONNECTION_ID = "connection-pane"

  // A row's id is this and its path, so a keyboard user keeps their place in the list across the push that replaces it.
  // No region id starts with it, so no path can give a row the id of a region.
  const val PATH_ROW_ID_PREFIX = "path-row-"

  // On each path row, its path and its state, as data, for scripts that read the page.
  const val PATH_ATTR = "data-path"
  const val STATE_ATTR = "data-state"

  /** The route segment a selected path follows: `<base>/paths/<path>`. */
  const val SELECTION_SEGMENT = "paths"

  private const val BRAND = "prometheus-agent"

  // The page's script (DashboardScript), for the agent's views: Paths at the base, a path selected at paths/<path>.
  private val SCRIPT =
    DashboardScript(subject = "agent", views = AgentLayout.entries, selectionSegment = SELECTION_SEGMENT).js

  /** A selected path's URL, under [routeBase]. */
  fun selectionHref(
    routeBase: String,
    path: String,
  ) = "$routeBase/$SELECTION_SEGMENT/${path.encodeURLPathPart()}"

  /**
   * The full page. Only served on a real navigation; every later update arrives over the socket. With [loginEnabled],
   * the bar ends in a Log out button.
   */
  fun HTML.renderPage(
    snapshot: AgentSnapshot,
    view: AgentView,
    routeBase: String,
    loginEnabled: Boolean = false,
  ) {
    val layout = view.layout
    val selectedPath = view.selectedPath
    // Declared so assistive tech pronounces paths and status text as English.
    attributes["lang"] = "en"
    head { dashboardHead(pageTitle(snapshot), routeBase) }
    body {
      // Loading hx-ws.min.js is what enables the extension; htmx 4 has no hx-ext.
      attributes["hx-ws:connect"] = "$routeBase/events"
      div("bar") {
        span("brand") { +BRAND }
        tabs(AgentLayout.entries, layout, routeBase)
        span("bar-right") { statusInner(snapshot, oob = false) }
        if (loginEnabled) logoutForm(routeBase)
      }
      announcer()
      when (layout) {
        AgentLayout.PATHS -> {
          main("md") {
            div("md-list") { pathListInner(snapshot, selectedPath, routeBase, oob = false) }
            div("md-detail") { detailInner(snapshot, selectedPath, oob = false) }
          }
        }

        AgentLayout.CONNECTION -> {
          main { div { connectionInner(snapshot, oob = false) } }
        }
      }
      script { unsafe { raw(SCRIPT) } }
    }
  }

  /**
   * One WebSocket frame carrying the regions [view] shows, as top-level siblings, the status bar first, stamped with
   * the view's message count, as the proxy's are (see ProxyDashboardHtml.pushFragment). A null [view], before the page
   * says which it shows, carries the status bar alone.
   */
  fun pushFragment(
    snapshot: AgentSnapshot,
    view: AgentView?,
    routeBase: String,
  ): String =
    buildString {
      append(createHTML().span("bar-right") { statusInner(snapshot, oob = true, view?.seq ?: 0L) })
      val selectedPath = view?.selectedPath
      when (view?.layout) {
        AgentLayout.PATHS -> {
          append(createHTML().div("md-list") { pathListInner(snapshot, selectedPath, routeBase, oob = true) })
          append(createHTML().div("md-detail") { detailInner(snapshot, selectedPath, oob = true) })
        }

        AgentLayout.CONNECTION -> {
          append(createHTML().div { connectionInner(snapshot, oob = true) })
        }

        null -> {}
      }
    }

  /** The detail pane alone, for the `hx-get` a row click issues, which swaps it with `outerHTML`. */
  fun detailFragment(
    snapshot: AgentSnapshot,
    selectedPath: String?,
  ): String = createHTML().div("md-detail") { detailInner(snapshot, selectedPath, oob = false) }

  // The status bar: the dashboard's own socket, then the agent's connection, its paths, and its backlog.
  private fun SPAN.statusInner(
    snapshot: AgentSnapshot,
    oob: Boolean,
    viewSeq: Long = 0L,
  ) {
    attributes["id"] = STATUS_ID
    if (oob) {
      attributes["hx-swap-oob"] = "true"
      attributes[VIEW_ATTR] = "$viewSeq"
    }
    attributes[TITLE_ATTR] = pageTitle(snapshot)
    connectionIndicator()
    val connection = snapshot.connection
    span(connectionText(connection.state)) { +"${connection.state.label} · ${connection.endpoint}" }
    span { +"${snapshot.paths.size} paths" }
    val unregistered = snapshot.unregistered.size
    if (unregistered > 0)
      span("crit") { +"$unregistered not registered" }
    span { +"backlog ${snapshot.backlog}" }
  }

  private fun DIV.pathListInner(
    snapshot: AgentSnapshot,
    selectedPath: String?,
    routeBase: String,
    oob: Boolean,
  ) {
    attributes["id"] = PATH_LIST_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    div("md-head") {
      span { +"Paths" }
      span { +"${snapshot.paths.size}" }
    }
    if (snapshot.paths.isEmpty()) {
      div("empty") { +"No paths configured or discovered" }
      return
    }
    snapshot.paths.forEach { path ->
      button(classes = "md-row") {
        attributes["id"] = "$PATH_ROW_ID_PREFIX${path.path}"
        attributes["hx-get"] = selectionHref(routeBase, path.path)
        attributes["hx-target"] = "#$DETAIL_ID"
        // outerHTML, so the returned #detail replaces the old one in place rather than nesting inside it.
        attributes["hx-swap"] = "outerHTML"
        attributes["hx-push-url"] = "true"
        attributes[PATH_ATTR] = path.path
        attributes[STATE_ATTR] = path.state.name.lowercase()
        if (path.path == selectedPath) attributes["aria-current"] = "true"
        span {
          span(led(path.state == PathState.REGISTERED)) {}
          +"/${path.path}"
          stateTag(path.state)
        }
        span("host") { +listOf(path.source.name.lowercase(), path.url.ifEmpty { DASH }).joinToString(" · ") }
      }
    }
  }

  private fun DIV.detailInner(
    snapshot: AgentSnapshot,
    selectedPath: String?,
    oob: Boolean,
  ) {
    attributes["id"] = DETAIL_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    if (selectedPath == null) {
      div("empty pad") { +"Select a path to see where it stands with the proxy" }
      return
    }
    val path = snapshot.paths.firstOrNull { it.path == selectedPath }
    if (path == null) {
      // Say so rather than leave a stale pane: the path left the config, the discovery file, or the proxy.
      div("empty pad gone") { +"/$selectedPath is no longer configured, discovered, or registered" }
      return
    }
    div("hero") {
      div("hero-title") {
        span(led(path.state == PathState.REGISTERED)) {}
        +"/${path.path}"
        stateTag(path.state, always = true)
      }
      div("hero-meta") {
        +listOfNotNull(path.name.ifEmpty { null }, path.source.name.lowercase(), path.url.ifEmpty { null })
          .joinToString(" · ")
      }
    }
    path.failure?.also { failureSection(it) }
    div("section") { span { +"Target" } }
    kv("URL", path.url.ifEmpty { DASH })
    kv("Labels", path.labels.ifEmpty { DASH })
    path.filter?.also { filterSection(it) }
  }

  // Why the path isn't registered: the proxy's reason, or what kept it from answering, when, and when it's next tried.
  private fun DIV.failureSection(failure: RegistrationFailure) {
    div("section") { span { +if (failure.refused) "Refused by the proxy" else "Couldn't reach the proxy" } }
    div("kv") {
      span("t") { timeOfDay(failure.reason.at) }
      span("v") { +failure.reason.message.ifEmpty { DASH } }
    }
    kv("Next try", nextTry(failure))
  }

  // When a path is next tried, as a person reads it.
  internal fun nextTry(failure: RegistrationFailure): String {
    if (!failure.retrying)
      return "not until the agent reconnects"
    val left = failure.retryAt?.let { -it.elapsedNow() }
    return if (left == null || !left.isPositive()) "at the next retry" else "in ${humanize(left)}"
  }

  private fun DIV.filterSection(filter: FilterView) {
    div("section") { span { +"Metric filter" } }
    kv("Keeps", filter.allow.joinToString(", ").ifEmpty { "every name the deny list doesn't drop" })
    kv("Drops", filter.deny.joinToString(", ").ifEmpty { DASH })
    kv("Dropped so far", "${filter.linesDropped} lines, ${size(filter.bytesSaved)}")
    filter.failedOpen?.also { kv("Let through unfiltered", "once or more: $it") }
  }

  private fun DIV.connectionInner(
    snapshot: AgentSnapshot,
    oob: Boolean,
  ) {
    attributes["id"] = CONNECTION_ID
    if (oob) attributes["hx-swap-oob"] = "true"
    val connection = snapshot.connection
    div("hero") {
      div("hero-title") {
        span(led(connection.state == ConnectionState.CONNECTED)) {}
        +"${connection.state.label} · ${connection.endpoint}"
        if (connection.failedOver) span("tag warn") { +"failed over" }
      }
      div("hero-meta") { +heroMeta(snapshot) }
    }
    div("section") {
      span { +"Proxy endpoints" }
      span { +"${connection.endpoints.size}" }
    }
    // The endpoint the agent is on, or is trying.
    val currentTag = if (connection.state == ConnectionState.CONNECTED) "in use" else "trying"
    connection.endpoints.forEach { endpoint ->
      div("kv") {
        span("k") {
          +endpoint.spec
          if (endpoint.current) span("tag") { +currentTag }
        }
        span("v") {
          val failure = endpoint.failure
          if (failure == null) {
            +DASH
          } else {
            timeOfDay(failure.at)
            +" ${failure.message}"
          }
        }
      }
    }
    snapshot.discovery?.also { discoverySection(it) }
    div("section") { span { +"Settings" } }
    kv("Version", snapshot.version)
    snapshot.settings.forEach { (name, value) -> kv(name, value) }
  }

  private fun heroMeta(snapshot: AgentSnapshot): String {
    val connection = snapshot.connection
    return listOfNotNull(
      "agent ${snapshot.agentName}",
      snapshot.agentId.ifEmpty { null }?.let { "id $it" },
      "launch ${snapshot.launchId}",
      "up ${humanize(snapshot.uptime)}",
      connection.since?.let { "connected ${humanize(Clock.System.now() - it)}" },
      "${connection.connects} ${if (connection.connects == 1) "connect" else "connects"}",
      "backlog ${snapshot.backlog}",
      connection.transport,
      if (connection.tokenSet) "token set" else "no token",
    ).joinToString(" · ")
  }

  private fun DIV.discoverySection(discovery: DiscoveryView) {
    div("section") {
      span { +"Discovery" }
      span { +"${discovery.entries}" }
    }
    kv("File", discovery.file)
    kv("Read every", "${discovery.intervalSecs}s")
    val failure = discovery.failure
    div("kv") {
      span("k") { +"Last read" }
      span("v") {
        if (failure == null) {
          +"ok"
        } else {
          timeOfDay(failure.at)
          +" failed, keeping the paths last read: ${failure.message}"
        }
      }
    }
    kv(
      "Skipped, missing a path or URL",
      discovery.skipped.joinToString(", ") { (path, url) ->
      "'$path' '$url'"
    }.ifEmpty { DASH },
    )
    kv("Colliding with static paths", discovery.collisions.joinToString(", ") { "/$it" }.ifEmpty { DASH })
    kv("Listed twice", discovery.duplicates.joinToString(", ") { "/$it" }.ifEmpty { DASH })
  }

  private fun DIV.kv(
    name: String,
    value: String,
  ) {
    div("kv") {
      span("k") { +name }
      span("v") { +value }
    }
  }

  // A path's state as a tag, which says what its LED can't: a lamp is binary (docs/DESIGN.md, The No Amber Dot Rule),
  // so retrying is an amber tag beside a red LED, and pending a neutral one. A registered path's green LED says it all,
  // so its tag shows only where the state is the heading, [always].
  private fun FlowContent.stateTag(
    state: PathState,
    always: Boolean = false,
  ) {
    when (state) {
      PathState.REGISTERED -> if (always) span("tag") { +state.label }
      PathState.RETRYING -> span("tag warn") { +state.label }
      PathState.REFUSED -> span("tag crit") { +state.label }
      PathState.PENDING -> span("tag") { +state.label }
    }
  }

  // An LED's classes, binary as every LED is: a path green while the proxy routes its scrapes here, a connection green
  // while connected, and either red otherwise.
  private fun led(ok: Boolean): String = if (ok) "led ok" else "led crit"

  // A connection's state in the status bar, as text, where amber may say what is true but not yet a failure: still
  // connecting, which no failure has interrupted.
  private fun connectionText(state: ConnectionState): String =
    when (state) {
      ConnectionState.CONNECTED -> "ok"
      ConnectionState.CONNECTING -> "warn"
      ConnectionState.FAILING -> "crit"
    }

  /**
   * The page's title: the product's name, after a count of the problems while there are any, so a dashboard in a
   * background tab still says when something is wrong: each path refused or retrying, and a connection failing.
   */
  internal fun pageTitle(snapshot: AgentSnapshot): String {
    val paths = snapshot.paths.count { it.state == PathState.REFUSED || it.state == PathState.RETRYING }
    return pageTitle(BRAND, paths + if (snapshot.connection.state == ConnectionState.FAILING) 1 else 0)
  }
}
