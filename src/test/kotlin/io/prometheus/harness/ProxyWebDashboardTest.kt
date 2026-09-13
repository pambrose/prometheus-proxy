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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.proxy.dashboard.ProxyDashboardHtml
import io.prometheus.proxy.dashboard.ProxySnapshot
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * The operational dashboard (Feature 5) over the Netty transport.
 *
 * Drives the real service rather than the renderer: serves the page, opens the WebSocket, and asserts
 * that a fragment arrives carrying an agent that connected *after* the socket was already open. That
 * ordering is the point — it proves the push path works, not merely that the initial render does.
 */
class ProxyWebDashboardTest : StringSpec() {
  /** The proxy, client and optional agent one spec runs against. Torn down by [withDashboard]. */
  private class DashboardEnv(
    val client: HttpClient,
    private val grpcPort: Int,
    private val configFile: String,
    dashboardPort: Int,
  ) {
    var agent: Agent? = null
      private set

    /** Origin of the dashboard's own listener; every spec builds its URLs from this. */
    val base = "http://$LOOPBACK_HOST:$dashboardPort"

    /** The push socket, under the default `/dashboard` base path. */
    val socketUrl = "ws://$LOOPBACK_HOST:$dashboardPort/dashboard/events"

    /**
     * Starts an agent against this proxy and waits for it to connect. Recorded so the fixture can stop it,
     * which is what lets a spec start one mid-test without owning a `finally`.
     *
     * @param proxySpec overridable for the failover spec, which needs a dead endpoint ahead of the live one.
     */
    suspend fun connectAgent(proxySpec: String = "$LOOPBACK_HOST:$grpcPort"): Agent =
      startAgent(configArgs = ["--config", configFile], args = ["--proxy", proxySpec])
        .also {
          agent = it
          // A cap, not a wait: this returns as soon as the agent connects, so the generous value is only
          // ever spent by a test that is already failing.
          it.awaitInitialConnection(30.seconds).shouldBeTrue()
        }
  }

  /**
   * Stands a dashboard-enabled proxy up on the given ports, hands the block a client and the fixture, and
   * tears all of it down.
   *
   * Eight specs used to repeat this frame verbatim. The only genuinely per-spec inputs are the port triple,
   * the config file, the dashboard arguments (empty for the one spec that checks the feature stays off) and
   * how the client is built — so those are the parameters, and everything else lives here once.
   */
  private suspend fun withDashboard(
    httpPort: Int,
    grpcPort: Int,
    dashboardPort: Int,
    configFile: String = CONFIG_FILE,
    dashboardArgs: List<String> = ["--dashboard", "--dashboard_port", "$dashboardPort"],
    newClient: () -> HttpClient = { HttpClient(CIO) },
    block: suspend DashboardEnv.() -> Unit,
  ) {
    CollectorRegistry.defaultRegistry.clear()

    val t0 = System.nanoTime()
    val proxy =
      startProxy(
        args = ["--agent_port", "$grpcPort"] + dashboardArgs,
        proxyPort = httpPort,
        configArgs = ["--config", configFile],
      )
    val t1 = System.nanoTime()

    val env = DashboardEnv(newClient(), grpcPort, configFile, dashboardPort)
    val t2 = System.nanoTime()
    try {
      env.block()
    } finally {
      val t3 = System.nanoTime()
      env.agent?.also { if (it.isRunning) runCatching { it.stopSync(10.seconds) } }
      val t4 = System.nanoTime()
      env.client.close()
      val t5 = System.nanoTime()
      runCatching { proxy.stopSync(10.seconds) }
      val t6 = System.nanoTime()
      println(
        "TIMING port=" + dashboardPort +
          " startProxy=" + (t1 - t0) / 1_000_000 +
          " newClient=" + (t2 - t1) / 1_000_000 +
          " block=" + (t3 - t2) / 1_000_000 +
          " stopAgent=" + (t4 - t3) / 1_000_000 +
          " closeClient=" + (t5 - t4) / 1_000_000 +
          " stopProxy=" + (t6 - t5) / 1_000_000,
      )
    }
  }

  private suspend fun DefaultClientWebSocketSession.nextText() = (incoming.receive() as Frame.Text).readText()

  /** Reads frames until one carries [marker]. `any` short-circuits, so MAX_FRAMES is a budget, not a count. */
  private suspend fun DefaultClientWebSocketSession.awaitFrame(marker: String) =
    (1..MAX_FRAMES).any { nextText().contains(marker) }.shouldBeTrue()

  init {
    "the dashboard serves a page and pushes updates over the WebSocket" {
      withDashboard(
        PROXY_HTTP_PORT,
        PROXY_GRPC_PORT,
        DASHBOARD_PORT,
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        // The page must render with no agents at all -- an operator opening the dashboard on a fresh proxy
        // should see an explanation, not a blank pane or a stack trace.
        eventually(20.seconds) {
          val response = client.get("$base/dashboard")
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain "No agents connected"
        }

        // htmx itself must be served from the classpath, not a CDN: the fat JAR has to work with no
        // outbound network access, which is the normal condition for a proxy bridging a firewall.
        client.get("$base/dashboard/assets/htmx.min.js").also {
          it.status shouldBe HttpStatusCode.OK
          it.bodyAsText() shouldContain "htmx"
        }

        // The asset route is an allowlist, not a classpath lookup, so anything off it is a plain 404.
        client.get("$base/dashboard/assets/nope.js").status shouldBe HttpStatusCode.NotFound

        client.webSocket(socketUrl) {
          // The immediate frame on connect, so a browser renders without waiting for a tick.
          nextText() shouldContain "hx-swap-oob"

          // Connect an agent only NOW, with the socket already open, so the frame below can only have
          // been produced by the push path.
          connectAgent()

          awaitFrame(AGENT_NAME)
        }
      }
    }

    "the dashboard must stay off unless enabled" {
      withDashboard(OFF_HTTP_PORT, OFF_GRPC_PORT, OFF_DASHBOARD_PORT, dashboardArgs = []) {
        // Nothing should be listening on the dashboard port: the feature is opt-in, matching admin and metrics.
        runCatching { client.get("$base/dashboard") }
          .isFailure
          .shouldBeTrue()
      }
    }

    // The bare root would 404, since the dashboard lives under /dashboard. Sending someone who typed the host:port to
    // the dashboard is the friendly default.
    "the root should redirect to the dashboard base path" {
      withDashboard(
        ROOT_HTTP_PORT,
        ROOT_GRPC_PORT,
        ROOT_DASHBOARD_PORT,
        // Do not auto-follow, so the redirect itself is observable rather than its destination.
        newClient = { HttpClient(CIO) { followRedirects = false } },
      ) {
        val response = client.get("$base/")
        response.status shouldBe HttpStatusCode.Found
        response.headers["Location"] shouldBe "/dashboard"
      }
    }

    // hx-push-url makes /dashboard/agents/{id} the address-bar URL, so reloading or bookmarking it must return
    // the whole dashboard, not the bare detail fragment the row-click swap uses. The two are told apart
    // by the HX-Request header htmx sets on its own requests.
    "an agent URL should serve the full page on a plain navigation and a fragment to htmx" {
      withDashboard(NAV_HTTP_PORT, NAV_GRPC_PORT, NAV_DASHBOARD_PORT) {
        val url = "$base/dashboard/agents/1"

        // A browser reload sends no HX-Request header -> the full document, shell and all.
        val full = client.get(url).bodyAsText()
        full shouldContain "<body"
        full shouldContain "id=\"agent-list\""
        full shouldContain "ws-connect"

        // htmx's row click sends HX-Request -> just the detail pane, no shell.
        val fragment = client.get(url) { header("HX-Request", "true") }.bodyAsText()
        fragment shouldNotContain "<body"
        fragment shouldNotContain "ws-connect"
        fragment shouldContain "id=\"detail\""

        // A history-restore fetch carries HX-Request but expects the full page back.
        val restore =
          client.get(url) {
            header("HX-Request", "true")
            header("HX-History-Restore-Request", "true")
          }.bodyAsText()
        restore shouldContain "<body"
      }
    }

    // The whole HA story in one assertion: an agent configured with two endpoints, whose primary does
    // not exist, connects to the secondary -- and this proxy's dashboard says so. Without the endpoint list on
    // the wire the dashboard could not distinguish that from a fresh start.
    "the dashboard reports an agent that reached this proxy via failover" {
      withDashboard(FAILOVER_HTTP_PORT, FAILOVER_GRPC_PORT, FAILOVER_DASHBOARD_PORT) {
        // Primary is a port nothing listens on, so the agent must advance to the second entry.
        connectAgent("$LOOPBACK_HOST:$DEAD_PORT,$LOOPBACK_HOST:$FAILOVER_GRPC_PORT")

        eventually(30.seconds) {
          val listing = client.get("$base/dashboard").bodyAsText()
          val agentId = AGENT_ID_PATTERN.find(listing)?.groupValues?.get(1).orEmpty()
          val detail = client.get("$base/dashboard/agents/$agentId").bodyAsText()

          detail shouldContain "via $LOOPBACK_HOST:$FAILOVER_GRPC_PORT (2 of 2)"
          detail shouldContain "failed over"
        }
      }
    }

    // The full chain in one assertion: the agent reports its target URL and path source at registration,
    // the proxy stores them on the path map, the snapshot joins them, and the table renders them. A break
    // anywhere between the .proto and the CSS fails here.
    "the path layout should show a registered path with its target and source" {
      withDashboard(PATHS_HTTP_PORT, PATHS_GRPC_PORT, PATHS_DASHBOARD_PORT, configFile = PATHS_CONFIG_FILE) {
        connectAgent()

        eventually(30.seconds) {
          val table = client.get("$base/dashboard/paths").bodyAsText()

          table shouldContain "/ui_path_metrics"
          // The target URL exists nowhere on the proxy except via the registration RPC.
          table shouldContain "http://localhost:9558/metrics"
          // STATIC renders as "cfg" -- proving path_source crossed the wire, not just target_url.
          table shouldContain "cfg"
        }

        // The agent layout must still be the default, so the new route is additive rather than a
        // redirect that changes what an existing bookmark to /dashboard does.
        client.get("$base/dashboard").bodyAsText() shouldContain "Agents"
      }
    }

    // The browser-to-proxy half of the socket. A row click and a layout switch each send one JSON message;
    // the service must parse it, record it on the session, and re-render at once rather than on the next
    // tick. The parsers are unit-tested; this is the only place the session state they feed is exercised.
    "a selection sent over the WebSocket should switch the pushed regions to that agent and layout" {
      withDashboard(
        SELECT_HTTP_PORT,
        SELECT_GRPC_PORT,
        SELECT_DASHBOARD_PORT,
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        val agentId = connectAgent().agentId

        client.webSocket(socketUrl) {
          // Nothing is selected on connect, so the first frame marks no row current.
          nextText() shouldNotContain SELECTED_ROW

          // A row click sends this exact message; the selection must show up in a pushed agent list.
          send(Frame.Text("""{"select":"$agentId","layout":"AGENT"}"""))
          awaitFrame(SELECTED_ROW)

          // Switching layout the same way changes which regions the push carries.
          send(Frame.Text("""{"layout":"PATH"}"""))
          awaitFrame("""id="${ProxyDashboardHtml.PATH_TABLE_ID}"""")
        }
      }
    }

    // The base path is configurable down to "/" itself, and the service special-cases that: the page owns
    // the root, so the root-to-base redirect is skipped, and every other route hangs directly off "/".
    "the dashboard can be mounted at the root path" {
      withDashboard(
        MOUNT_HTTP_PORT,
        MOUNT_GRPC_PORT,
        MOUNT_DASHBOARD_PORT,
        dashboardArgs = ["--dashboard", "--dashboard_port", "$MOUNT_DASHBOARD_PORT", "--dashboard_path", "/"],
        // Do not auto-follow, so a redirect would fail the status assertion rather than be hidden.
        newClient = { HttpClient(CIO) { followRedirects = false } },
      ) {
        val page = client.get("$base/")
        page.status shouldBe HttpStatusCode.OK
        page.bodyAsText() shouldContain "No agents connected"

        // The rendered links are ProxyDashboardHtmlTest's subject. What only a live server can show is that
        // the routes registered under a root base actually answer, rather than passing because Ktor happens
        // to discard an empty path segment.
        client.get("$base/paths").status shouldBe HttpStatusCode.OK
        client.get("$base/assets/htmx.min.js").status shouldBe HttpStatusCode.OK
        client.get("$base/agents/1").bodyAsText() shouldContain "<body"
      }
    }

    // The same-origin policy does not cover WebSockets, so without an Origin check a page on any site could read the
    // dashboard through an operator's browser. The refusal comes at the handshake, before a single frame is sent.
    "a WebSocket from a foreign origin should be refused at the handshake" {
      withDashboard(
        ORIGIN_HTTP_PORT,
        ORIGIN_GRPC_PORT,
        ORIGIN_DASHBOARD_PORT,
        // A file, because a list cannot be passed as -D: those overrides parse as properties, where values are strings.
        configFile = ORIGINS_CONFIG_FILE,
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        val refused =
          runCatching {
            client.webSocket(socketUrl, request = { header(HttpHeaders.Origin, "https://evil.example.com") }) {
              nextText()
            }
          }
        refused.exceptionOrNull()?.message.orEmpty() shouldContain "403"

        // The dashboard's own page, a configured origin, and a non-browser client that sends no Origin are let in.
        listOf(base, ALLOWED_ORIGIN, null).forEach { origin ->
          client.webSocket(socketUrl, request = { origin?.also { header(HttpHeaders.Origin, it) } }) {
            nextText() shouldContain "hx-swap-oob"
          }
        }
      }
    }

    // Every session costs a socket and a render per push, on a port with no authentication, so the count is capped.
    // A session over the cap is closed at once with a retry-later code, and closing a session frees its slot.
    "sessions beyond maxSessions should be turned away until one closes" {
      withDashboard(
        CAP_HTTP_PORT,
        CAP_GRPC_PORT,
        CAP_DASHBOARD_PORT,
        dashboardArgs = ["--dashboard", "--dashboard_port", "$CAP_DASHBOARD_PORT", "-Dproxy.dashboard.maxSessions=1"],
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        client.webSocket(socketUrl) {
          nextText() shouldContain "hx-swap-oob"

          client.webSocket(socketUrl) {
            withTimeout(10.seconds) { incoming.receiveCatching().getOrNull().shouldBeNull() }
            closeReason.await()?.knownReason shouldBe CloseReason.Codes.TRY_AGAIN_LATER
          }
        }

        // The slot is released when the first session closes, which may land just after the client returns.
        eventually(10.seconds) {
          client.webSocket(socketUrl) { nextText() shouldContain "hx-swap-oob" }
        }
      }
    }

    // Ktor accepts frames of any size by default and buffers each one whole. A browser message is a few dozen bytes,
    // so a frame past the cap closes the session rather than costing the proxy memory.
    "an oversized message should close the session" {
      withDashboard(
        FRAME_HTTP_PORT,
        FRAME_GRPC_PORT,
        FRAME_DASHBOARD_PORT,
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        client.webSocket(socketUrl) {
          nextText() shouldContain "hx-swap-oob"
          send(Frame.Text("x".repeat(OVERSIZED_MESSAGE_CHARS)))
          withTimeout(10.seconds) { closeReason.await() }?.knownReason shouldBe CloseReason.Codes.TOO_BIG
        }
      }
    }

    // A browser message used to run a full snapshot collect, which takes the path-map lock every scrape takes, so a
    // client looping messages coupled scrape latency to the dashboard. A message now re-renders the recent snapshot.
    // No agent connects and the refresh interval outlasts the spec, so nothing may collect while the spy counts.
    "messages over the WebSocket should re-render without collecting a snapshot each" {
      withDashboard(
        CACHE_HTTP_PORT,
        CACHE_GRPC_PORT,
        CACHE_DASHBOARD_PORT,
        dashboardArgs = [
          "--dashboard",
          "--dashboard_port",
          "$CACHE_DASHBOARD_PORT",
          "-Dproxy.dashboard.refreshIntervalSecs=60",
        ],
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        client.webSocket(socketUrl) {
          nextText() shouldContain "hx-swap-oob"

          mockkObject(ProxySnapshot.Companion)
          try {
            repeat(MESSAGE_COUNT) {
              send(Frame.Text("""{"layout":"AGENT"}"""))
              nextText() shouldContain "hx-swap-oob"
            }
            verify(exactly = 0) { ProxySnapshot.collect(any()) }
          } finally {
            unmockkObject(ProxySnapshot.Companion)
          }
        }
      }
    }
  }

  companion object {
    private const val CONFIG_FILE = "config/test-configs/web-ui.conf"
    private const val AGENT_NAME = "web-ui-agent"

    // Dedicated ports, following the one-off convention the other standalone harness specs use.
    private const val PROXY_HTTP_PORT = 9540
    private const val PROXY_GRPC_PORT = 9541
    private const val DASHBOARD_PORT = 9542
    private const val OFF_HTTP_PORT = 9543
    private const val OFF_GRPC_PORT = 9544
    private const val OFF_DASHBOARD_PORT = 9545

    // Bounded so a push that never carries the agent fails the assertion rather than hanging the suite.
    private const val MAX_FRAMES = 12

    private const val FAILOVER_HTTP_PORT = 9546
    private const val FAILOVER_GRPC_PORT = 9547
    private const val FAILOVER_DASHBOARD_PORT = 9548

    // Nothing listens here, so the agent's first endpoint fails and it advances to the second.
    private const val DEAD_PORT = 9549

    private val AGENT_ID_PATTERN = """hx-get="/dashboard/agents/([^"]+)"""".toRegex()

    private const val NAV_HTTP_PORT = 9550
    private const val NAV_GRPC_PORT = 9551
    private const val NAV_DASHBOARD_PORT = 9552

    private const val ROOT_HTTP_PORT = 9553
    private const val ROOT_GRPC_PORT = 9554
    private const val ROOT_DASHBOARD_PORT = 9558

    // Registers a real path, so it needs its own config and its own ports.
    private const val PATHS_CONFIG_FILE = "config/test-configs/web-ui-paths.conf"
    private const val PATHS_HTTP_PORT = 9555
    private const val PATHS_GRPC_PORT = 9556
    private const val PATHS_DASHBOARD_PORT = 9557

    private const val SELECT_HTTP_PORT = 9570
    private const val SELECT_GRPC_PORT = 9571
    private const val SELECT_DASHBOARD_PORT = 9572

    private const val MOUNT_HTTP_PORT = 9573
    private const val MOUNT_GRPC_PORT = 9574
    private const val MOUNT_DASHBOARD_PORT = 9575

    private const val ORIGIN_HTTP_PORT = 9576
    private const val ORIGIN_GRPC_PORT = 9577
    private const val ORIGIN_DASHBOARD_PORT = 9578
    private const val ORIGINS_CONFIG_FILE = "config/test-configs/web-ui-origins.conf"

    // Must match the allowedOrigins entry in ORIGINS_CONFIG_FILE.
    private const val ALLOWED_ORIGIN = "https://dash.example.com"

    private const val CAP_HTTP_PORT = 9579
    private const val CAP_GRPC_PORT = 9580
    private const val CAP_DASHBOARD_PORT = 9581

    private const val FRAME_HTTP_PORT = 9582
    private const val FRAME_GRPC_PORT = 9583
    private const val FRAME_DASHBOARD_PORT = 9584

    // Far past the incoming frame cap, and far past any message the dashboard page sends.
    private const val OVERSIZED_MESSAGE_CHARS = 1_000_000

    private const val CACHE_HTTP_PORT = 9585
    private const val CACHE_GRPC_PORT = 9586
    private const val CACHE_DASHBOARD_PORT = 9587
    private const val MESSAGE_COUNT = 5

    // The agent-list row marker for the session's selected agent (the nav uses aria-current="page").
    private const val SELECTED_ROW = """aria-current="true""""
  }
}
