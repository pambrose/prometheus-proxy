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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.harness

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.prometheus.Agent
import io.prometheus.agent.dashboard.AgentDashboardHtml
import io.prometheus.agent.dashboard.AgentDashboardService
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_UI_DASHBOARD_PORT
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_UI_DOWN_DASHBOARD_PORT
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_UI_DOWN_GRPC_PORT
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_UI_GRPC_PORT
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_UI_HTTP_PORT
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_UI_LOGIN_DASHBOARD_PORT
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.common.dashboard.DashboardServer.Companion.HX_REQUEST_HEADER
import io.prometheus.common.dashboard.LoginHtml
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * The agent's dashboard, served by a real agent: with its proxy down, where only the agent can say what is wrong, and
 * up, with one path registered and one the proxy refuses; its WebSocket's pushes; its login; and that it stays off
 * unless asked.
 */
class AgentWebDashboardTest : StringSpec() {
  private fun dashboardAgent(
    grpcPort: Int,
    dashboardPort: Int,
    extraArgs: List<String> = emptyList(),
  ): Agent =
    startAgent(
      configArgs = ["--config", CONFIG_FILE],
      args = ["--proxy", "$LOOPBACK_HOST:$grpcPort", "--dashboard", "--dashboard_port", "$dashboardPort"] + extraArgs,
    )

  private suspend fun DefaultClientWebSocketSession.nextText() = (incoming.receive() as Frame.Text).readText()

  /** Reads frames until one [matches], and returns it; MAX_FRAMES and FRAME_PATIENCE bound the wait. */
  private suspend fun DefaultClientWebSocketSession.firstFrame(matches: (String) -> Boolean): String =
    withTimeout(FRAME_PATIENCE) {
      (1..MAX_FRAMES).firstNotNullOfOrNull { nextText().takeIf(matches) }
    }.shouldNotBeNull()

  init {
    beforeTest { PrometheusRegistry.defaultRegistry.clear() }

    // The case the proxy's dashboard can never show: an agent that can't reach it.
    "with its proxy down, the agent's dashboard should say it's failing, with the endpoint's error, all paths pending" {
      val agent = dashboardAgent(AGENT_DASHBOARD_UI_DOWN_GRPC_PORT, AGENT_DASHBOARD_UI_DOWN_DASHBOARD_PORT)
      val base = "http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DOWN_DASHBOARD_PORT"
      val endpoint = "$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DOWN_GRPC_PORT"
      val client = HttpClient(CIO) { install(WebSockets) }
      try {
        eventually(30.seconds) {
          client.get("$base/dashboard/connection").bodyAsText().apply {
            this shouldContain "failing · $endpoint"
            this shouldContain """<span class="tag">trying</span>"""
            this shouldContain "UNAVAILABLE"
            this shouldContain "token set"
          }
        }
        client.get("$base/dashboard").bodyAsText().apply {
          this shouldContain
            """${AgentDashboardHtml.PATH_ATTR}="agent_dash_allowed_metrics" ${AgentDashboardHtml.STATE_ATTR}="pending""""
          this shouldContain
            """${AgentDashboardHtml.PATH_ATTR}="agent_dash_forbidden_metrics" ${AgentDashboardHtml.STATE_ATTR}="pending""""
          this shouldContain "2 not registered"
          this shouldContain "<title>(1 problem) prometheus-agent</title>"
          // The target's password never reaches the page.
          this shouldNotContain "s3cret"
        }

        client.webSocket("ws://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DOWN_DASHBOARD_PORT/dashboard/events") {
          // The status bar at once, before the page says which view it shows.
          nextText() shouldStartWith """<span class="bar-right" id="${DashboardPage.STATUS_ID}""""
          send(Frame.Text("""{"select":"agent_dash_allowed_metrics","layout":"PATHS","seq":1}"""))
          firstFrame { """${DashboardPage.VIEW_ATTR}="1"""" in it && "Metric filter" in it }.apply {
            this shouldContain """id="${AgentDashboardHtml.PATH_LIST_ID}" hx-swap-oob="true""""
            this shouldContain "go_.*"
          }
          send(Frame.Text("""{"select":null,"layout":"CONNECTION","seq":2}"""))
          firstFrame { """${DashboardPage.VIEW_ATTR}="2"""" in it }.apply {
            this shouldContain """id="${AgentDashboardHtml.CONNECTION_ID}" hx-swap-oob="true""""
            this shouldContain "failing · $endpoint"
            this shouldNotContain AgentDashboardHtml.PATH_LIST_ID
          }
        }
      } finally {
        client.close()
        agent.stopSync(10.seconds)
      }
    }

    // A refusal the proxy keeps to itself on its own dashboard: the agent shows it, with the proxy's reason.
    "connected, the agent's dashboard should show each path registered or refused, with the proxy's reason" {
      val proxy =
        startProxy(
          args = ["--agent_port", "$AGENT_DASHBOARD_UI_GRPC_PORT"],
          proxyPort = AGENT_DASHBOARD_UI_HTTP_PORT,
          configArgs = ["--config", CONFIG_FILE],
        )
      val agent = dashboardAgent(AGENT_DASHBOARD_UI_GRPC_PORT, AGENT_DASHBOARD_UI_DASHBOARD_PORT)
      val base = "http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DASHBOARD_PORT"
      val client = HttpClient(CIO) { followRedirects = false }
      try {
        agent.awaitInitialConnection(30.seconds).shouldBeTrue()
        eventually(30.seconds) {
          client.get("$base/dashboard").bodyAsText().apply {
            this shouldContain
              """${AgentDashboardHtml.PATH_ATTR}="agent_dash_allowed_metrics" ${AgentDashboardHtml.STATE_ATTR}="registered""""
            this shouldContain
              """${AgentDashboardHtml.PATH_ATTR}="agent_dash_forbidden_metrics" ${AgentDashboardHtml.STATE_ATTR}="refused""""
            this shouldContain "1 not registered"
          }
        }

        // A plain navigation gets the whole page with the path selected, and htmx's swap the detail pane alone.
        client.get("$base/dashboard/paths/agent_dash_forbidden_metrics").bodyAsText().apply {
          this shouldContain "<html"
          this shouldContain "Refused by the proxy"
          this shouldContain "is not authorized to register path /agent_dash_forbidden_metrics"
          this shouldContain "not until the agent reconnects"
        }
        client.get("$base/dashboard/paths/agent_dash_forbidden_metrics") { header(HX_REQUEST_HEADER, "true") }
          .bodyAsText()
          .apply {
            this shouldStartWith """<div class="md-detail" id="${AgentDashboardHtml.DETAIL_ID}">"""
            this shouldNotContain "<html"
          }

        client.get("$base/dashboard/connection").bodyAsText().apply {
          this shouldContain "connected · $LOOPBACK_HOST:$AGENT_DASHBOARD_UI_GRPC_PORT"
          this shouldContain """<span class="tag">in use</span>"""
          this shouldContain "1 connect ·"
          this shouldContain "token set"
          this shouldNotContain "agent-dashboard-team-a-token"
        }

        // The bare root goes to the dashboard.
        client.get("$base/").apply {
          status shouldBe HttpStatusCode.Found
          headers[HttpHeaders.Location] shouldBe "/dashboard"
        }
      } finally {
        client.close()
        agent.stopSync(10.seconds)
        proxy.stopSync(10.seconds)
      }
    }

    // The proxy's login, the agent's name: its own heading and its own cookie.
    "with a password set, the agent's dashboard should ask for a login, under the agent's name" {
      val agent =
        dashboardAgent(
          AGENT_DASHBOARD_UI_DOWN_GRPC_PORT,
          AGENT_DASHBOARD_UI_LOGIN_DASHBOARD_PORT,
          extraArgs = ["-Dagent.dashboard.password=$PASSWORD"],
        )
      val base = "http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_LOGIN_DASHBOARD_PORT"
      val client = HttpClient(CIO) { followRedirects = false }
      try {
        client.get("$base/dashboard") { header(HttpHeaders.Accept, "text/html") }.apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.Location] shouldBe "/dashboard/login?next=%2Fdashboard"
        }
        client.get("$base/dashboard/login").bodyAsText() shouldContain "<h1>${AgentDashboardService.LOGIN_HEADING}</h1>"
        client.post("$base/dashboard/login") {
          header(HttpHeaders.Origin, base)
          setBody(
            FormDataContent(
              parameters {
                append(LoginHtml.USERNAME_FIELD, "admin")
                append(LoginHtml.PASSWORD_FIELD, PASSWORD)
                append(LoginHtml.NEXT_FIELD, "/dashboard")
              },
            ),
          )
        }.apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.SetCookie].shouldNotBeNull() shouldStartWith
            "${DashboardServer.cookieName("agent", AGENT_DASHBOARD_UI_LOGIN_DASHBOARD_PORT)}="
        }
      } finally {
        client.close()
        agent.stopSync(10.seconds)
      }
    }

    "without --dashboard, the agent should have no dashboard" {
      val agent =
        startAgent(
          configArgs = ["--config", CONFIG_FILE],
          args = ["--proxy", "$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DOWN_GRPC_PORT"],
        )
      try {
        agent.dashboardService.shouldBeNull()
      } finally {
        agent.stopSync(10.seconds)
      }
    }
  }

  private companion object {
    const val CONFIG_FILE = "config/test-configs/agent-dashboard.conf"
    const val PASSWORD = "agent-dash-Passw0rd"
    const val MAX_FRAMES = 12
    val FRAME_PATIENCE = 30.seconds
  }
}
