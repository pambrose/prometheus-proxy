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

import com.codahale.metrics.health.HealthCheck
import com.google.common.util.concurrent.MoreExecutors
import com.pambrose.common.concurrent.GenericIdleService
import com.pambrose.common.concurrent.genericServiceListener
import com.pambrose.common.dsl.GuavaDsl.toStringElements
import com.pambrose.common.dsl.MetricsDsl.healthCheck
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseQueryString
import io.ktor.http.withCharset
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.prometheus.Proxy
import io.prometheus.common.dashboard.DashboardFrame
import io.prometheus.common.dashboard.DashboardLogin
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.common.dashboard.DashboardServer.Companion.HX_REQUEST_HEADER
import io.prometheus.common.dashboard.DashboardSettings
import io.prometheus.common.dashboard.DashboardSite
import io.prometheus.proxy.ProxyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Operational dashboard, served from its own Ktor server on its own port ([DashboardServer], which every dashboard's
 * login, guards, assets, sessions, and push loop come from). It reads the proxy's state and changes none of it; its one
 * action, scrape-now ([ScrapeNow]), scrapes a path as Prometheus would.
 *
 * Deliberately **not** the admin port: that one is a Jetty servlet container created inside
 * `common-utils`, whose only extension point is a `path -> Servlet` map — Ktor routing, the HTML DSL,
 * and WebSockets cannot attach to it. Deliberately **not** the scrape port either: that is
 * Prometheus-facing, and mixing an operator surface into it would put the dashboard on a listener that must
 * stay predictable.
 *
 * A separate port also means the dashboard can be firewalled independently of `/ping` and `/healthcheck`,
 * which Kubernetes probes target on the admin port.
 *
 * ### How updates reach the browser
 *
 * The server's one shared push loop wakes on either a topology [io.prometheus.proxy.ProxyEvent] or a timer tick,
 * collects a single [ProxySnapshot], and fans the same rendered fragments out to every connected session. An Activity
 * page is sent its table only when the feed or the page's filter has changed since the last one it was sent (see
 * [ActivityTable]).
 *
 * **Scrape completions deliberately do not wake it.** They arrive at scrape rate — tens per second on a
 * busy proxy — and waking on each would run a snapshot collect that often, taking the same path-map
 * monitor every scrape request takes and defeating the decoupling this service is built around. Scrape
 * history is a drifting value, so the timer covers it, for the same reason backlog depths and eviction
 * countdowns are timer-driven.
 *
 * ### Exposure
 *
 * With `proxy.dashboard.password` set, every request needs a session from the login page, or, from a script, the
 * login, `proxy.dashboard.username` (default `admin`) and that password, as HTTP Basic Auth. Without a password, the
 * default, the port has no authentication. See [DashboardServer] for what is bounded either way. The listen address is
 * `proxy.dashboard.host`; binding it to a private address is what keeps the page private.
 */
internal class ProxyDashboardService(
  private val proxy: Proxy,
  private val dashboardHost: String,
  private val dashboardPort: Int,
  dashboardPath: String,
  // The login every request must carry, from proxy.dashboard.username and password, once a password is set; null, the
  // default, leaves the dashboard open.
  login: DashboardLogin? = null,
) : GenericIdleService() {
  /**
   * What a session's page shows: its layout and the agent selected in it, from the browser's latest message, and that
   * message's [seq], the count of messages the page had sent.
   *
   * One value rather than separate fields, so a push reads all of them at once. Read separately, a push racing the
   * first message could pair its layout with the selection from before it, and render the detail pane with nothing
   * selected -- the flash a null view exists to prevent.
   *
   * Every frame is stamped with [seq], and the page drops a frame stamped below the count it has sent (see
   * ProxyDashboardHtml.pushFragment). That closes the race between a row click and the push loop: a tick rendered from
   * the previous selection can reach the browser after the click's own swap, and without the stamp it put the previous
   * agent back in the detail pane until the next frame.
   */
  private class View(
    val layout: DashboardLayout,
    val selectedId: String?,
    val seq: Long,
    val filter: RowFilter,
  )

  private val dashboardConfig = proxy.proxyConfigVals.dashboard

  // Null unless proxy.dashboard.scrapeNowEnabled, which leaves the route unregistered, so it answers 404.
  private val scrapeNow = if (dashboardConfig.scrapeNowEnabled) ScrapeNow(proxy) else null

  private val site =
    object : DashboardSite<View, ProxySnapshot> {
      // Topology changes wake the loop; scrape completions do not -- see the class KDoc. A getter, read as the server
      // starts: the proxy builds its event bus after this service.
      override val changes: Flow<Any?>
        get() = proxy.eventBus.flow.filter { it !is ProxyEvent.ScrapeCompleted }

      override suspend fun collect(): ProxySnapshot = ProxySnapshot.collect(proxy)

      override fun parseView(message: String): View =
        View(parseLayout(message), parseSelection(message), parseSeq(message), parseFilter(message))

      override fun frame(
        snapshot: ProxySnapshot,
        view: View?,
        sent: Any?,
        routeBase: String,
      ): DashboardFrame {
        val seq = view?.seq ?: 0L
        val filter = view?.filter ?: RowFilter.NONE
        // The Activity table goes only when it differs from the one the page shows; see ActivityTable. For another
        // layout there is no table, and the flag changes nothing.
        val activity = ActivityTable.of(snapshot, view?.layout, seq, filter)
        val text =
          ProxyDashboardHtml.pushFragment(
            snapshot,
            view?.selectedId,
            routeBase,
            view?.layout,
            seq,
            filter,
            withActivity = activity != sent,
          )
        return DashboardFrame(text, activity)
      }

      override fun Route.pages(server: DashboardServer<View, ProxySnapshot>) = pageRoutes(server)
    }

  private val server =
    DashboardServer(
      DashboardSettings(
        role = "proxy",
        host = dashboardHost,
        port = dashboardPort,
        path = dashboardPath,
        refreshInterval = dashboardConfig.refreshIntervalSecs.seconds,
        maxSessions = dashboardConfig.maxSessions,
        allowedOrigins = dashboardConfig.allowedOrigins,
        allowedHosts = dashboardConfig.allowedHosts,
        login = login,
        loginHeading = LOGIN_HEADING,
        icons = LinkIcon.entries.associate { it.fileName to it.resource },
      ),
      site,
    )

  // The proxy's pages: the three layouts, an agent's detail, and scrape-now.
  private fun Route.pageRoutes(server: DashboardServer<View, ProxySnapshot>) {
    val routeBase = server.routeBase
    val loginEnabled = server.loginEnabled
    get(server.basePath) {
      val snapshot = server.snapshots.recent()
      call.respondHtml {
        with(ProxyDashboardHtml) {
          renderPage(snapshot, null, routeBase, DashboardLayout.AGENT, loginEnabled = loginEnabled)
        }
      }
    }

    // A real page rather than a fragment: switching layout is a navigation, so the browser gets a
    // fresh document whose region ids match the layout it is now showing.
    // The filter is in the URL, so a filtered view can be bookmarked or shared; the page sends it back over the
    // socket, which is what keeps the pushed table filtered.
    for (layout in DashboardLayout.entries.filter { it.segment.isNotEmpty() }) {
      get(layout.href(routeBase)) {
        val snapshot = server.snapshots.recent()
        val filter = RowFilter.of(call.request.queryParameters)
        call.respondHtml {
          with(ProxyDashboardHtml) { renderPage(snapshot, null, routeBase, layout, filter, loginEnabled) }
        }
      }
    }

    get("$routeBase/agents/{agentId}") {
      val agentId = call.parameters["agentId"]
      val snapshot = server.snapshots.recent()
      // hx-push-url makes this URL the address bar's, so it must survive a reload or a shared link.
      // A row click is an htmx swap and wants just the detail fragment; a full navigation to the same
      // URL wants the whole dashboard with the agent selected. htmx marks its own requests with
      // HX-Request -- except a history-restore, which fetches the full URL expecting a full page.
      val htmxSwap =
        call.request.headers[HX_REQUEST_HEADER] == "true" &&
          call.request.headers["HX-History-Restore-Request"] != "true"
      if (htmxSwap)
        call.respondText(
          ProxyDashboardHtml.detailFragment(snapshot, agentId),
          ContentType.Text.Html,
          HttpStatusCode.OK,
        )
      else
        call.respondHtml {
          with(ProxyDashboardHtml) {
            renderPage(snapshot, agentId, routeBase, DashboardLayout.AGENT, loginEnabled = loginEnabled)
          }
        }
    }

    scrapeNow?.also { scrapes ->
      route("$routeBase/scrape") {
        install(server.pageGuard)
        post {
          // The address, not the host name, which could cost a reverse DNS lookup on every click.
          val result = scrapes.scrape(
            call.request.queryParameters["path"].orEmpty(),
            call.request.origin.remoteAddress,
          )
          call.respondText(
            ScrapeNowHtml.render(result),
            ContentType.Text.Html.withCharset(Charsets.UTF_8),
            result.status,
          )
        }
      }
    }
  }

  val healthCheck =
    healthCheck {
      if (isRunning)
        HealthCheck.Result.healthy()
      else
        HealthCheck.Result.unhealthy("$simpleClassName is not running")
    }

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    server.start()
    logger.info { "Started $simpleClassName on $dashboardHost:$dashboardPort at ${server.basePath}" }
  }

  override fun shutDown() {
    server.stop()
  }

  /**
   * What a push's Activity table is rendered from. A frame for the Activity view carries the table only when this
   * differs from what the last frame sent to the page carried; otherwise it carries the status bar alone.
   *
   * The table went to every Activity page on every refresh, though the feed changes only when the proxy records
   * something, so a page rewrote the table someone was reading every couple of seconds, undoing a text selection, to
   * show the same rows. Nothing in the table depends on when it is rendered: each time is the event's own, which the
   * page converts to the viewer's zone as the frame arrives, and the Identity column follows `proxy.auth`, fixed at
   * startup. So a table that isn't sent again shows what one sent again would.
   *
   * Equal values render equal tables: the change count covers every line and its count, and the rest is what the
   * page's view contributes. A frame for another layout has no value ([of] returns null), so a page that comes back to
   * the Activity view, as a back or forward navigation does without a new socket, is sent the table again.
   *
   * @param changeCount the activity log's change count, as the snapshot read it with the lines
   * @param seq the count of the message the page's view came from. A new message, even one asking for the same filter,
   *   is sent the table again: the page drops a frame stamped below the count it has sent (see
   *   ProxyDashboardHtml.pushFragment), so the frame that carried the last table may never have been shown, and a back
   *   or forward navigation replaces the page's table with the one its GET rendered before the page says so
   * @param filter the search and "Problems only" the table is filtered by
   */
  internal data class ActivityTable(
    val changeCount: Long,
    val seq: Long,
    val filter: RowFilter,
  ) {
    companion object {
      /** The table a push for [layout] renders from [snapshot], or null when [layout] has no Activity table. */
      fun of(
        snapshot: ProxySnapshot,
        layout: DashboardLayout?,
        seq: Long,
        filter: RowFilter,
      ): ActivityTable? =
        if (layout == DashboardLayout.ACTIVITY) ActivityTable(snapshot.activityChangeCount, seq, filter) else null
    }
  }

  override fun toString() =
    toStringElements {
      add("host", dashboardHost)
      add("port", dashboardPort)
      add("path", server.basePath)
      add("login", if (server.loginEnabled) "on" else "off")
      add("sessions", server.sessionsOpen)
    }

  companion object {
    private val logger = logger {}
    private val json = Json { ignoreUnknownKeys = true }

    /** The login page's heading. */
    internal const val LOGIN_HEADING = "Prometheus Proxy Dashboard"

    /**
     * Reads `{"select": "<agentId>"}` from a session message.
     *
     * Returns null on anything unexpected: this is browser-supplied input on a port that has no auth,
     * so malformed content must degrade to "no selection" rather than propagate an exception into the
     * WebSocket loop and drop the session.
     */
    internal fun parseSelection(text: String): String? =
      runCatching {
        json.parseToJsonElement(text)
          .jsonObject["select"]
          ?.jsonPrimitive
          ?.takeIf { it.isString }
          ?.content
          ?.takeIf { it.isNotEmpty() }
      }.getOrNull()

    /**
     * Reads `{"layout": "PATH"}` from a session message, defaulting to [DashboardLayout.AGENT].
     *
     * Same contract as [parseSelection]: anything unexpected -- garbage, a missing field, an unknown
     * layout name, or a browser predating the field -- resolves to the default rather than throwing
     * inside the WebSocket loop. Falling back to the agent layout is the safe direction, since that is
     * what the page served before layouts existed.
     */
    internal fun parseLayout(text: String): DashboardLayout =
      runCatching {
        json.parseToJsonElement(text)
          .jsonObject["layout"]
          ?.jsonPrimitive
          ?.takeIf { it.isString }
          ?.content
          ?.let { name -> DashboardLayout.entries.firstOrNull { it.name == name } }
      }.getOrNull() ?: DashboardLayout.AGENT

    /**
     * Reads `{"seq": 12}`, the count of messages the page has sent, from a session message, defaulting to 0.
     *
     * Same contract as [parseSelection]: anything unexpected -- garbage, a missing field, a string, a fraction, or a
     * negative number -- resolves to 0 rather than throwing inside the WebSocket loop. A page that predates the field
     * sends none and ignores the stamp, so 0 changes nothing for it.
     */
    internal fun parseSeq(text: String): Long =
      runCatching {
        json.parseToJsonElement(text)
          .jsonObject["seq"]
          ?.jsonPrimitive
          ?.takeUnless { it.isString }
          ?.longOrNull
          ?.takeIf { it >= 0 }
      }.getOrNull() ?: 0L

    /**
     * Reads `{"search": "?q=team-a&problems=1"}`, the query of the page's URL, which holds the Paths view's filter,
     * from a session message, defaulting to [RowFilter.NONE].
     *
     * The URL's own query rather than fields of its own, so the filter has one format, which the `/paths` route reads
     * too. Same contract as [parseSelection]: anything unexpected -- garbage, a missing field, a value of the wrong
     * type, or a query that won't decode -- resolves to no filter rather than throwing inside the WebSocket loop.
     */
    internal fun parseFilter(text: String): RowFilter =
      runCatching {
        json.parseToJsonElement(text)
          .jsonObject["search"]
          ?.jsonPrimitive
          ?.takeIf { it.isString }
          ?.content
          ?.let { RowFilter.of(parseQueryString(it.removePrefix("?"))) }
      }.getOrNull() ?: RowFilter.NONE
  }
}
