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

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.withCharset
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.prometheus.Agent
import io.prometheus.common.dashboard.DashboardFrame
import io.prometheus.common.dashboard.DashboardLogin
import io.prometheus.common.dashboard.DashboardMessage
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.common.dashboard.DashboardService
import io.prometheus.common.dashboard.DashboardSettings
import io.prometheus.common.dashboard.DashboardSite
import io.prometheus.common.dashboard.ScrapeNowHtml
import io.prometheus.common.dashboard.href
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration.Companion.seconds
import io.prometheus.common.dashboard.ActivityTable
import io.prometheus.common.dashboard.RowFilter
import kotlinx.coroutines.flow.drop

/**
 * The agent's dashboard, served from its own Ktor server on its own port ([DashboardServer], which the proxy's
 * dashboard shares): what only the agent knows, which the proxy's dashboard can't show. Its connection, even while the
 * proxy can't see it; every path, with the proxy's reason for refusing one; and its filters, discovery, and settings.
 * It reads the agent's state and changes none of it; its one action, Scrape now ([AgentScrapeNow]), scrapes a path's
 * target from the agent, as Prometheus would through the proxy.
 *
 * Off by default (`agent.dashboard.enabled`), so an agent embedded in another app opens no port of its own unless
 * asked. Its updates come from the server's push loop, on its refresh interval, and at once on each new line of the
 * activity feed ([io.prometheus.agent.AgentActivityLog]).
 *
 * With `agent.dashboard.password` set, every request needs a session from the login page, or, from a script, the login
 * as HTTP Basic Auth. Without a password, the default, the port has no authentication. The listen address is
 * `agent.dashboard.host`; binding it to a private address is what keeps the page private.
 */
internal class AgentDashboardService(
  private val agent: Agent,
  dashboardHost: String,
  dashboardPort: Int,
  dashboardPath: String,
  // The login every request must carry, from agent.dashboard.username and password, once a password is set; null, the
  // default, leaves the dashboard open.
  login: DashboardLogin? = null,
) : DashboardService<AgentView, AgentSnapshot>() {
  private val dashboardConfig = agent.agentConfigVals.dashboard

  // Null unless agent.dashboard.scrapeNowEnabled, which leaves the route unregistered, so it answers 404.
  private val scrapeNow = if (dashboardConfig.scrapeNowEnabled) AgentScrapeNow(agent) else null

  private val site =
    object : DashboardSite<AgentView, AgentSnapshot> {
      // A new line of the activity feed; the first value is the count at the start, which is no change.
      override val changes: Flow<Any?> = agent.activityLog?.changes?.drop(1) ?: emptyFlow()

      override suspend fun collect(): AgentSnapshot = AgentSnapshot.collect(agent)

      // The script sends the selected path as the page's URL has it, percent-encoded.
      override fun parseView(message: String): AgentView =
        AgentView(
          DashboardMessage.layout(message, AgentLayout.entries, AgentLayout.PATHS),
          DashboardMessage.selection(message)?.let { runCatching { it.decodeURLPart() }.getOrNull() },
          DashboardMessage.seq(message),
          RowFilter.fromMessage(message),
        )

      // The Activity table goes only when it differs from the one the page shows; see ActivityTable.
      override fun frame(
        snapshot: AgentSnapshot,
        view: AgentView?,
        sent: Any?,
        routeBase: String,
      ): DashboardFrame {
        val activity =
          view?.takeIf { it.layout == AgentLayout.ACTIVITY }
            ?.let { ActivityTable(snapshot.activityChangeCount, it.seq, it.filter) }
        val text = AgentDashboardHtml.pushFragment(snapshot, view, routeBase, withActivity = activity != sent)
        return DashboardFrame(text, activity)
      }

      override fun Route.pages(server: DashboardServer<AgentView, AgentSnapshot>) = pageRoutes(server)
    }

  override val server =
    DashboardServer(
      DashboardSettings(
        role = "agent",
        host = dashboardHost,
        port = dashboardPort,
        path = dashboardPath,
        refreshInterval = dashboardConfig.refreshIntervalSecs.seconds,
        maxSessions = dashboardConfig.maxSessions,
        allowedOrigins = dashboardConfig.allowedOrigins,
        allowedHosts = dashboardConfig.allowedHosts,
        login = login,
        loginHeading = LOGIN_HEADING,
      ),
      site,
    )

  // The agent's pages: its three views, a selected path's, and Scrape now.
  private fun Route.pageRoutes(server: DashboardServer<AgentView, AgentSnapshot>) {
    val routeBase = server.routeBase
    val loginEnabled = server.loginEnabled
    for (layout in AgentLayout.entries) {
      // The filter is in the URL, so a filtered Activity view can be bookmarked or shared.
      get(layout.href(routeBase)) {
        val snapshot = server.snapshots.recent()
        val view = AgentView(layout, filter = RowFilter.of(call.request.queryParameters))
        call.respondHtml {
          with(AgentDashboardHtml) { renderPage(snapshot, view, routeBase, loginEnabled) }
        }
      }
    }

    // As an agent's on the proxy's dashboard: a row click is an htmx swap and wants the detail pane alone, and a full
    // navigation to the same URL, a reload or a shared link, wants the whole page with the path selected.
    get("$routeBase/${AgentDashboardHtml.SELECTION_SEGMENT}/{path}") {
      val path = call.parameters["path"]
      val snapshot = server.snapshots.recent()
      if (DashboardServer.isHtmxSwap(call.request))
        call.respondText(AgentDashboardHtml.detailFragment(snapshot, path), ContentType.Text.Html, HttpStatusCode.OK)
      else
        call.respondHtml {
          with(AgentDashboardHtml) { renderPage(snapshot, AgentView(selectedPath = path), routeBase, loginEnabled) }
        }
    }

    // Behind pageGuard, as the proxy's is: a POST needs the header only the page's own script sends, so no page on
    // another site can make the agent scrape.
    scrapeNow?.also { scrapes ->
      route("$routeBase/scrape") {
        install(server.pageGuard)
        post {
          // The address, not the host name, which could cost a reverse DNS lookup on every click.
          val result =
            scrapes.scrape(call.request.queryParameters["path"].orEmpty(), call.request.origin.remoteAddress)
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
    internal const val LOGIN_HEADING = "Prometheus Agent Dashboard"
  }
}
