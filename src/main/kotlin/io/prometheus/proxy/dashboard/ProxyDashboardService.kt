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

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
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
import io.prometheus.common.dashboard.DashboardMessage
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.common.dashboard.DashboardService
import io.prometheus.common.dashboard.DashboardSettings
import io.prometheus.common.dashboard.DashboardSite
import io.prometheus.common.dashboard.ScrapeNowHtml
import io.prometheus.common.dashboard.href
import io.prometheus.proxy.ProxyEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlin.time.Duration.Companion.seconds
import io.prometheus.common.dashboard.RowFilter
import io.prometheus.common.dashboard.ActivityTable
import io.prometheus.common.dashboard.SettingsShown

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
internal class ProxyView(
  val layout: DashboardLayout,
  val selectedId: String?,
  val seq: Long,
  val filter: RowFilter,
)

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
  dashboardHost: String,
  dashboardPort: Int,
  dashboardPath: String,
  // The login every request must carry, from proxy.dashboard.username and password, once a password is set; null, the
  // default, leaves the dashboard open.
  login: DashboardLogin? = null,
) : DashboardService<ProxyView, ProxySnapshot>() {
  private val dashboardConfig = proxy.proxyConfigVals.dashboard

  // Null unless proxy.dashboard.scrapeNowEnabled, which leaves the route unregistered, so it answers 404.
  private val scrapeNow = if (dashboardConfig.scrapeNowEnabled) ScrapeNow(proxy) else null

  // Read at the first collect, once the proxy's services are built, and kept: no setting changes while it runs.
  private val settings by lazy { ProxySettings.of(proxy) }

  private val site =
    object : DashboardSite<ProxyView, ProxySnapshot> {
      // Topology changes wake the loop; scrape completions do not -- see the class KDoc.
      override val changes: Flow<Any?> = proxy.eventBus.flow.filter { it !is ProxyEvent.ScrapeCompleted }

      override suspend fun collect(): ProxySnapshot = ProxySnapshot.collect(proxy, settings)

      override fun parseView(message: String): ProxyView =
        ProxyView(
          parseLayout(message),
          DashboardMessage.selection(message),
          DashboardMessage.seq(message),
          RowFilter.fromMessage(message),
        )

      override fun frame(
        snapshot: ProxySnapshot,
        view: ProxyView?,
        sent: Any?,
        routeBase: String,
      ): DashboardFrame {
        val seq = view?.seq ?: 0L
        val filter = view?.filter ?: RowFilter.NONE
        // The Activity table goes only when it differs from the one the page shows, see ActivityTable, and the
        // settings only to a page not yet sent them, see SettingsShown. For another layout neither flag changes
        // anything.
        val shown = shownRegion(snapshot, view?.layout, seq, filter)
        val text =
          ProxyDashboardHtml.pushFragment(
            snapshot,
            view?.selectedId,
            routeBase,
            view?.layout,
            seq,
            filter,
            changed = shown != sent,
          )
        return DashboardFrame(text, shown)
      }

      override fun Route.pages(server: DashboardServer<ProxyView, ProxySnapshot>) = pageRoutes(server)
    }

  override val server =
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

  // The proxy's pages: the four layouts, an agent's detail, and scrape-now.
  private fun Route.pageRoutes(server: DashboardServer<ProxyView, ProxySnapshot>) {
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
      if (DashboardServer.isHtmxSwap(call.request))
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

  companion object {
    /** The login page's heading. */
    internal const val LOGIN_HEADING = "Prometheus Proxy Dashboard"

    /**
     * Reads `{"layout": "PATH"}` from a session message, defaulting to [DashboardLayout.AGENT], what the page served
     * before layouts existed (see [DashboardMessage.layout]).
     */
    internal fun parseLayout(text: String): DashboardLayout =
      DashboardMessage.layout(text, DashboardLayout.entries, DashboardLayout.AGENT)

    /**
     * What a push for [layout] keeps for the next to compare with (DashboardFrame.sent): the Activity table it renders
     * from [snapshot], that the Settings view was sent its settings, or null for a layout with neither.
     */
    internal fun shownRegion(
      snapshot: ProxySnapshot,
      layout: DashboardLayout?,
      seq: Long,
      filter: RowFilter,
    ): Any? =
      when (layout) {
        DashboardLayout.ACTIVITY -> ActivityTable(snapshot.activityChangeCount, seq, filter)
        DashboardLayout.SETTINGS -> SettingsShown(seq)
        else -> null
      }
  }
}
