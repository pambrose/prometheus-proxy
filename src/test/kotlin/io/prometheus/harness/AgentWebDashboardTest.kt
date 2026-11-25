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

import io.prometheus.common.registeredCounts
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.common.registeredSnapshot
import io.prometheus.common.TestPorts.AGENT_DASHBOARD_UI_METRICS_PORT
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

/**
 * The agent's dashboard, served by a real agent: with its proxy down, where only the agent can say what is wrong, and
 * up, with one path registered and one the proxy refuses; a path's scrapes as the agent saw them, and its Scrape now;
 * its WebSocket's pushes; its login; and that it stays off unless asked.
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

  // The agent's sampled gauge [name], from the default registry.
  private fun agentGauge(name: String): Double = registeredSnapshot<GaugeSnapshot>(name).dataPoints.single().value

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
        // The agent's settings, on their own view: its token only as set, never the token.
        client.get("$base/dashboard/settings").bodyAsText().apply {
          this shouldContain """<div class="section"><span>Proxy connection</span></div>"""
          this shouldContain """<span class="k">Token</span><span class="v">set</span>"""
          this shouldContain """<span class="k">Proxy endpoints</span><span class="v">$endpoint</span>"""
          this shouldNotContain "agent-dashboard-team-a-token"
        }
        client.get("$base/dashboard").bodyAsText().apply {
          this shouldContain
            """${AgentDashboardHtml.PATH_ATTR}="agent_dash_allowed_metrics" ${AgentDashboardHtml.STATE_ATTR}="pending""""
          this shouldContain
            """${AgentDashboardHtml.PATH_ATTR}="agent_dash_forbidden_metrics" ${AgentDashboardHtml.STATE_ATTR}="pending""""
          this shouldContain "2 not registered"
          this shouldContain "<title>(1 problem) Agent: ${agent.agentName}</title>"
          // The target's password never reaches the page.
          this shouldNotContain "s3cret"
        }

        // Scrape now works with the proxy down: the agent fetches the target itself, which here can't be resolved.
        client.post("$base/dashboard/scrape?path=agent_dash_allowed_metrics") { header(HX_REQUEST_HEADER, "true") }
          .apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText().apply {
              this shouldContain """<span class="pill crit">"""
              this shouldContain """<div class="sr-failure">"""
              this shouldNotContain "s3cret"
            }
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

    // What only the agent sees of a scrape: the target's own answer, and what the filter did to the body. Scrape now
    // fetches the target from the agent, through the path's filter, and is recorded nowhere.
    "a scrape through the proxy should show in the path's history, and Scrape now should fetch it from the agent" {
      val stub =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
          routing { get("/metrics") { call.respondText(STUB_BODY) } }
        }
      val stubPort = stub.startAndAwaitReady()
      val proxy =
        startProxy(
          args = ["--agent_port", "$AGENT_DASHBOARD_UI_GRPC_PORT"],
          proxyPort = AGENT_DASHBOARD_UI_HTTP_PORT,
          configArgs = ["--config", CONFIG_FILE],
        )
      val agent = dashboardAgent(AGENT_DASHBOARD_UI_GRPC_PORT, AGENT_DASHBOARD_UI_DASHBOARD_PORT)
      val base = "http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DASHBOARD_PORT"
      val client = HttpClient(CIO)
      try {
        agent.awaitInitialConnection(30.seconds).shouldBeTrue()
        agent.pathManager.registerPath(SCRAPED_PATH, "http://$LOOPBACK_HOST:$stubPort/metrics")
        client.get("http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_HTTP_PORT/$SCRAPED_PATH").bodyAsText().apply {
          this shouldContain "kept_metric"
          this shouldNotContain "go_goroutines"
        }

        eventually(30.seconds) {
          client.get("$base/dashboard").bodyAsText() shouldContain
            """aria-label="1 of 1 recent scrapes succeeded""""
        }
        client.get("$base/dashboard/paths/$SCRAPED_PATH").bodyAsText().apply {
          this shouldContain "<span>Recent scrapes</span><span>1</span>"
          this shouldContain "after the filter"
        }

        client.post("$base/dashboard/scrape?path=$SCRAPED_PATH") { header(HX_REQUEST_HEADER, "true") }.apply {
          status shouldBe HttpStatusCode.OK
          // No page on another site may frame the agent's dashboard, to steer a viewer's clicks to this button.
          headers["Content-Security-Policy"].orEmpty() shouldContain "frame-ancestors 'none'"
          headers["X-Frame-Options"] shouldBe "DENY"
          bodyAsText().apply {
            this shouldContain """<span class="pill ok">200</span>"""
            this shouldContain "after the filter"
            this shouldContain "kept_metric"
            this shouldNotContain "go_goroutines"
          }
        }
        // The history still holds the proxy's one scrape.
        agent.scrapeHistory.shouldNotBeNull().snapshot().getValue(SCRAPED_PATH).size shouldBe 1

        // Without the header only the page's own script sends, no scrape: a page elsewhere can't make the agent act.
        client.post("$base/dashboard/scrape?path=$SCRAPED_PATH").status shouldBe HttpStatusCode.Forbidden
        // A browser names a path, never a URL, and only a path the agent knows is scraped. The panel says why.
        client.post("$base/dashboard/scrape?path=nope") { header(HX_REQUEST_HEADER, "true") }.apply {
          status shouldBe HttpStatusCode.NotFound
          bodyAsText() shouldContain "/nope isn't configured, discovered, or registered"
        }
      } finally {
        client.close()
        agent.stopSync(10.seconds)
        proxy.stopSync(10.seconds)
        stub.stop(0, 0)
      }
    }

    // What the agent's log says, on the dashboard: connecting, a refused path, and, once the proxy goes away,
    // the disconnect and the failing connects, each pushed to an open Activity page as it happens.
    "the Activity view should show the agent's connection and paths, and push the proxy going away" {
      val proxy =
        startProxy(
          args = ["--agent_port", "$AGENT_DASHBOARD_UI_GRPC_PORT"],
          proxyPort = AGENT_DASHBOARD_UI_HTTP_PORT,
          configArgs = ["--config", CONFIG_FILE],
        )
      val agent = dashboardAgent(AGENT_DASHBOARD_UI_GRPC_PORT, AGENT_DASHBOARD_UI_DASHBOARD_PORT)
      val base = "http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DASHBOARD_PORT"
      val client = HttpClient(CIO) { install(WebSockets) }
      var proxyStopped = false
      try {
        agent.awaitInitialConnection(30.seconds).shouldBeTrue()
        eventually(30.seconds) {
          client.get("$base/dashboard/activity").bodyAsText().apply {
            this shouldContain ">connected<"
            this shouldContain "/agent_dash_allowed_metrics"
            this shouldContain """<span class="tag crit">refused</span>"""
            this shouldContain "is not authorized to register path /agent_dash_forbidden_metrics"
          }
        }
        client.get("$base/dashboard/activity?q=forbidden").bodyAsText() shouldContain "Showing 1 of"

        client.webSocket("ws://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DASHBOARD_PORT/dashboard/events") {
          send(Frame.Text("""{"select":null,"layout":"ACTIVITY","seq":1}"""))
          firstFrame { """id="${AgentDashboardHtml.ACTIVITY_ID}"""" in it }
          proxy.stopSync(10.seconds)
          proxyStopped = true
          firstFrame {
            """<span class="tag warn">disconnected</span>""" in it &&
              """<span class="tag crit">connect failed</span>""" in it
          }.apply {
            this shouldContain """id="${AgentDashboardHtml.ACTIVITY_ID}" hx-swap-oob="true""""
            this shouldContain "$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_GRPC_PORT"
            // The disconnect says why: the stream's failure, which a connection task met and logged.
            this shouldContain "$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_GRPC_PORT: StatusException"
          }
        }
      } finally {
        client.close()
        agent.stopSync(10.seconds)
        if (!proxyStopped) proxy.stopSync(10.seconds)
      }
    }

    // The metrics count what the Activity view shows: the connect, once the proxy registered the agent, the path it
    // refused, by cause, and the disconnect once the proxy goes away, with the connects that then fail.
    "the agent's metrics should count its connect, its refused path, and its disconnect" {
      val proxy =
        startProxy(
          args = ["--agent_port", "$AGENT_DASHBOARD_UI_GRPC_PORT"],
          proxyPort = AGENT_DASHBOARD_UI_HTTP_PORT,
          configArgs = ["--config", CONFIG_FILE],
        )
      val agent = dashboardAgent(AGENT_DASHBOARD_UI_GRPC_PORT, AGENT_DASHBOARD_UI_DASHBOARD_PORT, METRICS_ARGS)
      var proxyStopped = false
      try {
        agent.awaitInitialConnection(30.seconds).shouldBeTrue()
        eventually(30.seconds) {
          registeredCounts("agent_connect_count", "type")["success"] shouldBe 1.0
          registeredCounts("agent_path_rejections", "cause")["not_authorized"].shouldNotBeNull() shouldBeGreaterThan 0.0
          agentGauge("agent_connected") shouldBe 1.0
        }

        proxy.stopSync(10.seconds)
        proxyStopped = true

        eventually(30.seconds) {
          registeredCounts("agent_disconnects")[""] shouldBe 1.0
          registeredCounts("agent_connect_count", "type")["failure"].shouldNotBeNull() shouldBeGreaterThan 0.0
          agentGauge("agent_connected") shouldBe 0.0
        }
      } finally {
        agent.stopSync(10.seconds)
        if (!proxyStopped) proxy.stopSync(10.seconds)
      }
    }

    "with Scrape now off, the agent's dashboard should have no button and no route for it" {
      val agent =
        dashboardAgent(
          AGENT_DASHBOARD_UI_DOWN_GRPC_PORT,
          AGENT_DASHBOARD_UI_DOWN_DASHBOARD_PORT,
          extraArgs = ["-Dagent.dashboard.scrapeNowEnabled=false"],
        )
      val base = "http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_DOWN_DASHBOARD_PORT"
      val client = HttpClient(CIO)
      try {
        client.post("$base/dashboard/scrape?path=agent_dash_allowed_metrics") { header(HX_REQUEST_HEADER, "true") }
          .status shouldBe HttpStatusCode.NotFound
        client.get("$base/dashboard/paths/agent_dash_allowed_metrics").bodyAsText().apply {
          this shouldNotContain """class="scrape-now""""
          this shouldNotContain """id="${DashboardPage.SCRAPE_RESULT_ID}""""
        }
      } finally {
        client.close()
        agent.stopSync(10.seconds)
      }
    }

    // The proxy's login, the agent's name: its own heading and its own cookie.
    "with a password set, the agent's dashboard should ask for a login, under the agent's name" {
      val agent =
        dashboardAgent(
          AGENT_DASHBOARD_UI_DOWN_GRPC_PORT,
          AGENT_DASHBOARD_UI_LOGIN_DASHBOARD_PORT,
          extraArgs = ["-Dagent.dashboard.password=$PASSWORD"] + METRICS_ARGS,
        )
      val base = "http://$LOOPBACK_HOST:$AGENT_DASHBOARD_UI_LOGIN_DASHBOARD_PORT"
      val client = HttpClient(CIO) { followRedirects = false }
      try {
        client.get("$base/dashboard") { header(HttpHeaders.Accept, "text/html") }.apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.Location] shouldBe "/dashboard/login?next=%2Fdashboard"
        }
        client.get("$base/dashboard/login").apply {
          bodyAsText() shouldContain "<h1>${AgentDashboardService.LOGIN_HEADING}</h1>"
          headers[HttpHeaders.CacheControl] shouldBe "no-store"
          headers["Content-Security-Policy"].orEmpty() shouldContain "frame-ancestors 'none'"
        }
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
        // Counted in agent_dashboard_logins_total, as the proxy's dashboard counts its own.
        registeredCounts("agent_dashboard_logins", "outcome")["success"] shouldBe 1.0
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

    // The agent's metrics on, so its metrics { } blocks count, on a port of the spec's own.
    val METRICS_ARGS = listOf("--metrics", "--metrics_port", "$AGENT_DASHBOARD_UI_METRICS_PORT")

    // Registered at runtime against a stub, with a filter in CONFIG_FILE that drops the go_ family.
    const val SCRAPED_PATH = "agent_dash_allowed_scraped"
    val STUB_BODY =
      """
      # TYPE kept_metric gauge
      kept_metric 1
      # TYPE go_goroutines gauge
      go_goroutines 12

      """.trimIndent()
    const val PASSWORD = "agent-dash-Passw0rd"
    const val MAX_FRAMES = 12
    val FRAME_PATIENCE = 30.seconds
  }
}
