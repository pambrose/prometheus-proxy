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
import io.ktor.http.decodeURLPart
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.prometheus.Agent
import io.prometheus.common.dashboard.DashboardFrame
import io.prometheus.common.dashboard.DashboardLogin
import io.prometheus.common.dashboard.DashboardMessage
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.common.dashboard.DashboardServer.Companion.HX_REQUEST_HEADER
import io.prometheus.common.dashboard.DashboardSettings
import io.prometheus.common.dashboard.DashboardSite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration.Companion.seconds

/**
 * The agent's dashboard, served from its own Ktor server on its own port ([DashboardServer], which the proxy's
 * dashboard shares): what only the agent knows, which the proxy's dashboard can't show. Its connection, even while the
 * proxy can't see it; every path, with the proxy's reason for refusing one; and its filters, discovery, and settings.
 * It reads the agent's state and changes none of it.
 *
 * Off by default (`agent.dashboard.enabled`), so an agent embedded in another app opens no port of its own unless
 * asked. Its updates come from the server's push loop, on its refresh interval: the agent has no event bus, so nothing
 * wakes it sooner.
 *
 * With `agent.dashboard.password` set, every request needs a session from the login page, or, from a script, the login
 * as HTTP Basic Auth. Without a password, the default, the port has no authentication. The listen address is
 * `agent.dashboard.host`; binding it to a private address is what keeps the page private.
 */
internal class AgentDashboardService(
  private val agent: Agent,
  private val dashboardHost: String,
  private val dashboardPort: Int,
  dashboardPath: String,
  // The login every request must carry, from agent.dashboard.username and password, once a password is set; null, the
  // default, leaves the dashboard open.
  login: DashboardLogin? = null,
) : GenericIdleService() {
  private val dashboardConfig = agent.agentConfigVals.dashboard

  private val site =
    object : DashboardSite<AgentView, AgentSnapshot> {
      override val changes: Flow<Any?> = emptyFlow()

      override suspend fun collect(): AgentSnapshot = AgentSnapshot.collect(agent)

      // The script sends the selected path as the page's URL has it, percent-encoded.
      override fun parseView(message: String): AgentView =
        AgentView(
          DashboardMessage.layout(message, AgentLayout.entries, AgentLayout.PATHS),
          DashboardMessage.selection(message)?.let { runCatching { it.decodeURLPart() }.getOrNull() },
          DashboardMessage.seq(message),
        )

      override fun frame(
        snapshot: AgentSnapshot,
        view: AgentView?,
        sent: Any?,
        routeBase: String,
      ) = DashboardFrame(AgentDashboardHtml.pushFragment(snapshot, view, routeBase))

      override fun Route.pages(server: DashboardServer<AgentView, AgentSnapshot>) = pageRoutes(server)
    }

  private val server =
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

  // The agent's pages: its two views, and a selected path's.
  private fun Route.pageRoutes(server: DashboardServer<AgentView, AgentSnapshot>) {
    val routeBase = server.routeBase
    val loginEnabled = server.loginEnabled
    for (layout in AgentLayout.entries) {
      get(layout.href(routeBase)) {
        val snapshot = server.snapshots.recent()
        call.respondHtml {
          with(AgentDashboardHtml) { renderPage(snapshot, AgentView(layout), routeBase, loginEnabled) }
        }
      }
    }

    // As an agent's on the proxy's dashboard: a row click is an htmx swap and wants the detail pane alone, and a full
    // navigation to the same URL, a reload or a shared link, wants the whole page with the path selected.
    get("$routeBase/${AgentDashboardHtml.SELECTION_SEGMENT}/{path}") {
      val path = call.parameters["path"]
      val snapshot = server.snapshots.recent()
      val htmxSwap =
        call.request.headers[HX_REQUEST_HEADER] == "true" &&
          call.request.headers["HX-History-Restore-Request"] != "true"
      if (htmxSwap)
        call.respondText(AgentDashboardHtml.detailFragment(snapshot, path), ContentType.Text.Html, HttpStatusCode.OK)
      else
        call.respondHtml {
          with(AgentDashboardHtml) { renderPage(snapshot, AgentView(selectedPath = path), routeBase, loginEnabled) }
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

    /** The login page's heading. */
    internal const val LOGIN_HEADING = "Prometheus Agent Dashboard"
  }
}
