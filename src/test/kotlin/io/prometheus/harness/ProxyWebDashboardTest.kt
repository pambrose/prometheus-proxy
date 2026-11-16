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

import com.pambrose.common.util.runCatchingCancellable
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.startAndAwaitReady
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.common.TestPorts
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.proxy.dashboard.DashboardFixtures.scrapeRecord
import io.prometheus.proxy.dashboard.ProxyDashboardHtml
import io.prometheus.proxy.dashboard.ProxyDashboardService.Companion.ASSET_DIR
import io.prometheus.proxy.dashboard.ProxyDashboardService.Companion.HX_REQUEST_HEADER
import io.prometheus.proxy.dashboard.ProxySnapshot
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

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
    val proxy: Proxy,
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
     * @param metricsPort the port the agent serves its metrics on, for a spec that reads them; null leaves them off.
     */
    fun connectAgent(
      proxySpec: String = "$LOOPBACK_HOST:$grpcPort",
      metricsPort: Int? = null,
    ): Agent =
      startAgent(
        metricsEnabled = metricsPort != null,
        configArgs = ["--config", configFile],
        args = ["--proxy", proxySpec] + metricsPort?.let { listOf("--metrics_port", "$it") }.orEmpty(),
      ).also {
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
    metricsEnabled: Boolean = false,
    block: suspend DashboardEnv.() -> Unit,
  ) {
    PrometheusRegistry.defaultRegistry.clear()

    val t0 = System.nanoTime()
    val proxy =
      startProxy(
        args = ["--agent_port", "$grpcPort"] + dashboardArgs,
        proxyPort = httpPort,
        configArgs = ["--config", configFile],
        metricsEnabled = metricsEnabled,
      )
    val t1 = System.nanoTime()

    val env = DashboardEnv(proxy, newClient(), grpcPort, configFile, dashboardPort)
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

  // Every series of [name] in the default registry, summed: a counter's values, or a histogram's observation counts.
  private fun counted(name: String): Double =
    PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == name }?.dataPoints.orEmpty().sumOf { point ->
      when (point) {
        is CounterSnapshot.CounterDataPointSnapshot -> point.value
        is HistogramSnapshot.HistogramDataPointSnapshot -> point.count.toDouble()
        else -> 0.0
      }
    }

  // The status of a GET for [path] on this dashboard whose Host header names [host] rather than the address it
  // connects to.
  private suspend fun DashboardEnv.statusWithHost(
    path: String,
    host: String,
  ) = client.get("$base$path") { header(HttpHeaders.Host, host) }.status

  /** Reads frames until one carries [marker]. `any` short-circuits, so MAX_FRAMES is a budget, not a count. */
  private suspend fun DefaultClientWebSocketSession.awaitFrame(marker: String) =
    (1..MAX_FRAMES).any { nextText().contains(marker) }.shouldBeTrue()

  /** Reads frames until one [matches], and returns it; MAX_FRAMES and FRAME_PATIENCE bound the wait. */
  private suspend fun DefaultClientWebSocketSession.firstFrame(matches: (String) -> Boolean): String =
    withTimeout(FRAME_PATIENCE) {
      (1..MAX_FRAMES).firstNotNullOfOrNull { nextText().takeIf(matches) }
    }.shouldNotBeNull()

  // The stamp of a frame rendered from the browser's message [seq] (see ProxyDashboardHtml.pushFragment).
  private fun viewOf(seq: Long) = """${ProxyDashboardHtml.VIEW_ATTR}="$seq""""

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
        client.get("$base/dashboard/$ASSET_DIR/htmx.min.js").also {
          it.status shouldBe HttpStatusCode.OK
          it.bodyAsText() shouldContain "htmx"
        }
        // The WebSocket extension lives inside the htmx.org WebJar, under its own path.
        client.get("$base/dashboard/$ASSET_DIR/hx-ws.min.js").also {
          it.status shouldBe HttpStatusCode.OK
          it.bodyAsText() shouldContain "registerExtension"
        }

        // The asset route is an allowlist, not a classpath lookup, so anything off it is a plain 404.
        client.get("$base/dashboard/$ASSET_DIR/nope.js").status shouldBe HttpStatusCode.NotFound
        // So is the unversioned path the assets were served at before. The assets are cached for a year, and
        // only a URL that changes with the version keeps a browser from running a stale htmx.
        client.get("$base/dashboard/assets/htmx.min.js").status shouldBe HttpStatusCode.NotFound

        client.webSocket(socketUrl) {
          // The immediate frame on connect, so a browser renders without waiting for a tick. It carries only
          // the status bar, since the session has not yet said which page it shows.
          nextText().also {
            it shouldContain "hx-swap-oob"
            it shouldNotContain """id="${ProxyDashboardHtml.AGENT_LIST_ID}""""
          }
          // What the page sends as soon as its socket opens.
          send(Frame.Text("""{"select":null,"layout":"AGENT"}"""))

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
        runCatchingCancellable { client.get("$base/dashboard") }
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
        full shouldContain "hx-ws:connect"

        // htmx's row click sends HX-Request -> just the detail pane, no shell.
        val fragment = client.get(url) { header(HX_REQUEST_HEADER, "true") }.bodyAsText()
        fragment shouldNotContain "<body"
        fragment shouldNotContain "hx-ws:connect"
        fragment shouldContain "id=\"detail\""

        // A history-restore fetch carries HX-Request but expects the full page back.
        val restore =
          client.get(url) {
            header(HX_REQUEST_HEADER, "true")
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

    // The full chain in one assertion: the agent reports its target URL, path source, and labels at
    // registration, the proxy stores them on the path map, the snapshot joins them, and the table renders
    // them, the labels through the config's dashboard link. A break anywhere between the .proto and the CSS
    // fails here.
    "the path layout should show a registered path with its target, source, and link" {
      withDashboard(PATHS_HTTP_PORT, PATHS_GRPC_PORT, PATHS_DASHBOARD_PORT, configFile = PATHS_CONFIG_FILE) {
        connectAgent()

        eventually(30.seconds) {
          val table = client.get("$base/dashboard/paths").bodyAsText()

          table shouldContain "/ui_path_metrics"
          // The target URL exists nowhere on the proxy except via the registration RPC.
          table shouldContain "http://ui-path-target.invalid/metrics"
          // STATIC renders as "cfg" -- proving path_source crossed the wire, not just target_url.
          table shouldContain "cfg"
          // The link the config's template builds from the path's job label.
          table shouldContain """<a href="http://prometheus.invalid/graph?job=ui-path-job" target="_blank""""
        }

        // The agent layout must still be the default, so the new route is additive rather than a
        // redirect that changes what an existing bookmark to /dashboard does.
        client.get("$base/dashboard").bodyAsText() shouldContain "Agents"
      }
    }

    // The full chain for a path whose agent has gone: Proxy.recordScrape, PathHistory, the snapshot, the table. The
    // gone path's one scrape is followed by more than the recent-scrapes queue, since retired, held (200), which before
    // PathHistory pushed it out of the table; it must now stay, with its history strip.
    "a gone path should stay in the path layout after more scrapes than the recent-scrapes queue held" {
      withDashboard(HISTORY_HTTP_PORT, HISTORY_GRPC_PORT, HISTORY_DASHBOARD_PORT) {
        proxy.recordScrape(
          scrapeRecord(agentId = "9", agentName = "warehouse-01", path = "orphan_metrics", statusCode = 503),
        )
        repeat(250) { proxy.recordScrape(scrapeRecord(agentId = "7", agentName = "busy-01", path = "busy_metrics")) }

        eventually(20.seconds) {
          val table = client.get("$base/dashboard/paths").bodyAsText()

          table shouldContain "/orphan_metrics"
          table shouldContain "warehouse-01"
          table shouldContain """aria-label="0 of 1 recent scrapes succeeded""""
          // Each path keeps only pathHistorySize (20) records, however many it had.
          table shouldContain """aria-label="20 of 20 recent scrapes succeeded""""
        }
      }
    }

    // Identities and the search, end to end: the proxy records the identity the agent's token resolves to, at
    // registerAgent and on its path; the Paths view shows it, and filters by the URL's search and by the socket's. The
    // agent is refused a path its identity's glob doesn't match, which only the Activity view shows.
    "the path layout should show identities and filter by the URL's search and the socket's" {
      withDashboard(
        IDENTITY_HTTP_PORT,
        IDENTITY_GRPC_PORT,
        IDENTITY_DASHBOARD_PORT,
        configFile = IDENTITY_CONFIG_FILE,
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        connectAgent()

        eventually(30.seconds) {
          val table = client.get("$base/dashboard/paths?q=team_a").bodyAsText()

          table shouldContain "/ui_identity_metrics"
          table shouldContain """<td class="dim">team_a</td>"""
          // The page echoes the search it was opened with.
          table shouldContain """value="team_a""""
        }
        client.get("$base/dashboard/paths?q=nothing-like-this").bodyAsText() shouldContain "No paths match the filter"
        client.get("$base/dashboard").bodyAsText() shouldContain
          """web-ui-identity-agent<span class="tag">team_a</span>"""

        eventually(30.seconds) {
          val feed = client.get("$base/dashboard/activity").bodyAsText()

          feed shouldContain """<td class="event">connected</td>"""
          feed shouldContain """<td class="event"><span class="tag crit">rejected</span></td>"""
          feed shouldContain "/ui_forbidden_metrics"
          feed shouldContain "is not authorized to register path /ui_forbidden_metrics"
        }
        client.get("$base/dashboard/activity?problems=1").bodyAsText() shouldNotContain "connected</td>"

        client.webSocket(socketUrl) {
          send(Frame.Text("""{"layout":"PATH","seq":1,"search":"?q=nothing-like-this"}"""))
          awaitFrame("No paths match the filter")
          send(Frame.Text("""{"layout":"PATH","seq":2,"search":"?q=ui_identity"}"""))
          awaitFrame("/ui_identity_metrics")
          send(Frame.Text("""{"layout":"ACTIVITY","seq":3,"search":"?problems=1"}"""))
          awaitFrame("""id="${ProxyDashboardHtml.ACTIVITY_ID}" hx-swap-oob="true" data-match-count="Showing 1 of""")
        }
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
          // The first frame comes before the session has said what its page shows, so it carries only the
          // status bar: no agent list to mark a row in, and no detail pane to blank.
          nextText().also {
            it shouldContain """id="${ProxyDashboardHtml.STATUS_ID}""""
            it shouldNotContain """id="${ProxyDashboardHtml.AGENT_LIST_ID}""""
            it shouldNotContain """id="${ProxyDashboardHtml.DETAIL_ID}""""
          }

          // A row click sends this exact message; the selection must show up in a pushed agent list.
          send(Frame.Text("""{"select":"$agentId","layout":"AGENT","seq":1}"""))
          awaitFrame(SELECTED_ROW)
          // Every frame rendered from that message carries its count, which the page compares against the
          // count it has sent to drop a frame rendered from an older message.
          awaitFrame("""${ProxyDashboardHtml.VIEW_ATTR}="1"""")

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
        client.get("$base/$ASSET_DIR/htmx.min.js").status shouldBe HttpStatusCode.OK
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
          runCatchingCancellable {
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

    // The DNS-rebinding Host check (see ProxyDashboardService.isHostAllowed) covers every route. It is opt-in; every
    // other spec runs without it.
    "with allowedHosts set, a request naming an unknown Host should be refused on every route" {
      withDashboard(HOST_HTTP_PORT, HOST_GRPC_PORT, HOST_DASHBOARD_PORT, configFile = HOSTS_CONFIG_FILE) {
        val routes = listOf("/dashboard", "/dashboard/paths", "/dashboard/events", "/dashboard/$ASSET_DIR/htmx.min.js")
        routes.forEach { path ->
          statusWithHost(path, "evil.example.com:$HOST_DASHBOARD_PORT") shouldBe HttpStatusCode.Forbidden
        }
        listOf(ALLOWED_HOST, "localhost:$HOST_DASHBOARD_PORT", "$LOOPBACK_HOST:$HOST_DASHBOARD_PORT").forEach { host ->
          statusWithHost("/dashboard", host) shouldBe HttpStatusCode.OK
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
    //
    // The close code the client sees is a race in TCP, not the proxy. Ktor rejects the frame on reading its header,
    // sends a TOO_BIG close frame, and closes the socket with the rest of the frame still unread, so the kernel resets
    // the connection; a reset that reaches the client before it reads the close frame surfaces as CLOSED_ABNORMALLY.
    // That happened in CI, so either code passes, and a fresh session afterwards must still render, which a proxy
    // that had died rather than closing the session would fail. Small messages keeping the session open is the next
    // spec's to check.
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
          val code = withTimeout(10.seconds) { closeReason.await() }?.code
          code shouldBeIn listOf(CloseReason.Codes.TOO_BIG.code, ABNORMAL_CLOSURE_CODE)
        }
        client.webSocket(socketUrl) {
          nextText() shouldContain "hx-swap-oob"
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

    // A send fails when the browser has gone, which is routine, but a render that throws is a bug in the page. Caught
    // with the sends, it froze the page with nothing logged above DEBUG. It is logged now, apart from the send, and the
    // session stays open, so the next push that renders reaches it. Two failures, so the second takes the path that has
    // already logged. The refresh interval outlasts the spec, so only the messages render.
    "a push that fails to render should leave the session open for the next one" {
      withDashboard(
        RENDER_FAILURE_HTTP_PORT,
        RENDER_FAILURE_GRPC_PORT,
        RENDER_FAILURE_DASHBOARD_PORT,
        dashboardArgs = [
          "--dashboard",
          "--dashboard_port",
          "$RENDER_FAILURE_DASHBOARD_PORT",
          "-Dproxy.dashboard.refreshIntervalSecs=60",
        ],
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        client.webSocket(socketUrl) {
          nextText() shouldContain "hx-swap-oob"

          mockkObject(ProxyDashboardHtml)
          try {
            every { ProxyDashboardHtml.pushFragment(any(), any(), any(), any(), any(), any(), any()) } throws
              IllegalStateException("a render bug")
            send(Frame.Text("""{"layout":"AGENT","seq":1}"""))
            send(Frame.Text("""{"layout":"AGENT","seq":2}"""))
            verify(timeout = 10_000, atLeast = 2) {
              ProxyDashboardHtml.pushFragment(any(), any(), any(), any(), any(), any(), any())
            }
          } finally {
            unmockkObject(ProxyDashboardHtml)
          }
          send(Frame.Text("""{"layout":"AGENT","seq":3}"""))
          nextText() shouldContain """${ProxyDashboardHtml.VIEW_ATTR}="3""""
        }
      }
    }

    // A scrape from the dashboard end to end, against a target the test serves. It returns what the target had, and
    // leaves no trace in what Prometheus reads: the proxy's and the agent's scrape metrics, and the path's History,
    // which the agent's Recent scrapes are read from, all of which a scrape through the scrape port does change. The
    // agent counts it apart, in agent_dashboard_scrapes_total. The route refuses the same path again within its
    // cooldown, a path no agent serves, a request without htmx's header, and one from another origin. Off, it isn't
    // there at all.
    "a scrape-now should return what the target had, leaving what Prometheus reads untouched" {
      val stub =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
          routing { get("/metrics") { call.respondText(SCRAPE_STUB_BODY) } }
        }
      val stubPort = stub.startAndAwaitReady()
      try {
        withDashboard(
          SCRAPE_HTTP_PORT,
          SCRAPE_GRPC_PORT,
          SCRAPE_DASHBOARD_PORT,
          dashboardArgs = [
            "--dashboard",
            "--dashboard_port",
            "$SCRAPE_DASHBOARD_PORT",
            "-Dproxy.metrics.port=$SCRAPE_METRICS_PORT",
          ],
          metricsEnabled = true,
        ) {
          val agent = connectAgent(metricsPort = SCRAPE_AGENT_METRICS_PORT)
          agent.pathManager.registerPath("ui_scrape_metrics", "http://$LOOPBACK_HOST:$stubPort/metrics")
          eventually(30.seconds) {
            client.get("$base/dashboard/paths").bodyAsText() shouldContain
              """id="${ProxyDashboardHtml.SCRAPE_ID_PREFIX}ui_scrape_metrics""""
          }

          // The path's History as the proxy holds it, which the Paths view and the agent's Recent scrapes show.
          fun recorded() =
            proxy.pathHistory.shouldNotBeNull().snapshot(setOf("ui_scrape_metrics")).scrapes["ui_scrape_metrics"]
              .orEmpty()
              .size

          // A Prometheus scrape, through the scrape port, which the metrics and the History record.
          client.get("http://$LOOPBACK_HOST:$SCRAPE_HTTP_PORT/ui_scrape_metrics").bodyAsText() shouldContain "demo_up 1"
          val requests = counted("proxy_scrape_requests")
          val bytes = counted("proxy_scrape_response_bytes")
          val recent = recorded()
          requests shouldBe 1.0
          bytes shouldBe 1.0
          // The agent counts it in its own scrape metrics: the request, its result, and its latency. The result is
          // counted once the agent has sent it, which can be after the proxy has answered.
          eventually(10.seconds) { AGENT_SCRAPE_METRICS.map(::counted) shouldBe [1.0, 1.0, 1.0] }
          counted(AGENT_DASHBOARD_SCRAPES) shouldBe 0.0

          suspend fun scrapeNow(
            path: String = "ui_scrape_metrics",
            headers: HttpRequestBuilder.() -> Unit = { header(HX_REQUEST_HEADER, "true") },
          ) = client.post("$base/dashboard/scrape?path=$path", headers)

          scrapeNow().apply {
            status shouldBe HttpStatusCode.OK
            bodyAsText().apply {
              this shouldContain """<span class="pill ok">200</span>"""
              this shouldContain "2 families · 2 samples"
              this shouldContain "demo_up 1"
            }
          }
          scrapeNow().status shouldBe HttpStatusCode.TooManyRequests
          scrapeNow("nothing_here").status shouldBe HttpStatusCode.NotFound
          scrapeNow { }.status shouldBe HttpStatusCode.Forbidden
          scrapeNow {
            header(HX_REQUEST_HEADER, "true")
            header(HttpHeaders.Origin, "http://elsewhere.example")
          }.status shouldBe HttpStatusCode.Forbidden

          counted("proxy_scrape_requests") shouldBe requests
          counted("proxy_scrape_response_bytes") shouldBe bytes
          recorded() shouldBe recent
          // A page renders the snapshot of the last refresh, which may predate the scrapes above.
          eventually(10.seconds) {
            client.get("$base/dashboard/paths").bodyAsText() shouldContain
              """aria-label="1 of 1 recent scrapes succeeded""""
            // The agent's Recent scrapes, read from the same History, count Prometheus's scrape alone.
            client.get("$base/dashboard/agents/${agent.agentId}").bodyAsText() shouldContain
              """${ProxyDashboardHtml.SCRAPE_COUNT_ATTR}="1""""
            client.get("$base/dashboard/activity").bodyAsText() shouldContain "scraped now"
          }
          // The agent was told the dashboard asked, so it counted the one scrape-now that reached it apart, and left
          // its scrape metrics as Prometheus's scrape left them. Checked last, well after the agent sent its result.
          counted(AGENT_DASHBOARD_SCRAPES) shouldBe 1.0
          AGENT_SCRAPE_METRICS.map(::counted) shouldBe [1.0, 1.0, 1.0]
        }

        withDashboard(
          SCRAPE_HTTP_PORT,
          SCRAPE_GRPC_PORT,
          SCRAPE_DASHBOARD_PORT,
          dashboardArgs = [
            "--dashboard",
            "--dashboard_port",
            "$SCRAPE_DASHBOARD_PORT",
            "-Dproxy.dashboard.scrapeNowEnabled=false",
          ],
        ) {
          client.post("$base/dashboard/scrape?path=ui_scrape_metrics") { header(HX_REQUEST_HEADER, "true") }
            .status shouldBe HttpStatusCode.NotFound
          client.get("$base/dashboard/paths").bodyAsText() shouldNotContain
            """id="${ProxyDashboardHtml.SCRAPE_RESULT_ID}""""
        }
      } finally {
        stub.stop(0, 0)
      }
    }

    // The same coupling through the page routes, which need no WebSocket at all: each GET used to collect a snapshot,
    // so a client looping GETs held the path-map lock as often as it liked (November #18). A page now renders the
    // recent snapshot. No agent connects, no session opens, and the refresh interval outlasts the spec, so the first
    // GET is the only collect: nothing filled the cache before it, and nothing invalidates it after. Exactly one,
    // rather than at most one, so a spy that never saw a collect cannot pass.
    "page GETs should render without collecting a snapshot each" {
      withDashboard(
        PAGE_CACHE_HTTP_PORT,
        PAGE_CACHE_GRPC_PORT,
        PAGE_CACHE_DASHBOARD_PORT,
        dashboardArgs = [
          "--dashboard",
          "--dashboard_port",
          "$PAGE_CACHE_DASHBOARD_PORT",
          "-Dproxy.dashboard.refreshIntervalSecs=60",
        ],
      ) {
        // An asset, which collects nothing, to wait for the listener before the spy starts counting.
        eventually(20.seconds) {
          client.get("$base/dashboard/$ASSET_DIR/htmx.min.js").status shouldBe HttpStatusCode.OK
        }

        mockkObject(ProxySnapshot.Companion)
        try {
          repeat(PAGE_ROUNDS) {
            listOf("$base/dashboard", "$base/dashboard/paths", "$base/dashboard/agents/1").forEach { url ->
              client.get(url).status shouldBe HttpStatusCode.OK
            }
            client.get("$base/dashboard/agents/1") { header(HX_REQUEST_HEADER, "true") }.status shouldBe
              HttpStatusCode.OK
          }
          verify(exactly = 1) { ProxySnapshot.collect(any()) }
        } finally {
          unmockkObject(ProxySnapshot.Companion)
        }
      }
    }

    // The other half of the cache's contract: a topology change shows on the next page, not a refresh interval later.
    // The interval outlasts the spec, so only the invalidation a topology event triggers can pass this. Each change
    // gets a moment, since the event reaches the dashboard asynchronously, but far less than the interval.
    "a page GET after a topology change should show the change" {
      withDashboard(
        PAGE_FRESH_HTTP_PORT,
        PAGE_FRESH_GRPC_PORT,
        PAGE_FRESH_DASHBOARD_PORT,
        configFile = PATHS_CONFIG_FILE,
        dashboardArgs = [
          "--dashboard",
          "--dashboard_port",
          "$PAGE_FRESH_DASHBOARD_PORT",
          "-Dproxy.dashboard.refreshIntervalSecs=60",
        ],
      ) {
        // Fill the cache with the empty proxy first, so a page that ignored the change would still show it empty.
        eventually(20.seconds) { client.get("$base/dashboard").bodyAsText() shouldContain "No agents connected" }
        client.get("$base/dashboard/paths").bodyAsText() shouldNotContain PATHS_PATH

        val agent = connectAgent()
        eventually(TOPOLOGY_PATIENCE) {
          client.get("$base/dashboard").bodyAsText() shouldContain PATHS_AGENT_NAME
          client.get("$base/dashboard/paths").bodyAsText() shouldContain PATHS_PATH
        }

        agent.stopSync(10.seconds)
        eventually(TOPOLOGY_PATIENCE) {
          client.get("$base/dashboard").bodyAsText() shouldContain "No agents connected"
          // The path stays, marked gone, though nothing scraped it: its departure is recorded as its agent leaves. A
          // gone row's path cell holds the path alone, without the live row's copy button. A page GET is indented.
          client.get("$base/dashboard/paths").bodyAsText() shouldContain
            Regex("""<tr class="departed">\s*<td>$PATHS_PATH</td>""")
        }
      }
    }

    // The push loop leaves the Activity table out of a frame when the page already shows the table that frame would
    // carry (ProxyDashboardService.ActivityTable), so a reader's table isn't rewritten every refresh to show the same
    // rows. The comparison has unit tests; this runs it through the real loop and socket, at the config's 1s refresh.
    // A tick can land on either side of a browser's message, so each step reads frames until the one it is about,
    // rather than counting them, and then the next QUIET_FRAMES, at least one of which is a tick.
    "an Activity page should be sent its table again only when the feed changes or the page sends a message" {
      withDashboard(
        ACTIVITY_PUSH_HTTP_PORT,
        ACTIVITY_PUSH_GRPC_PORT,
        ACTIVITY_PUSH_DASHBOARD_PORT,
        newClient = { HttpClient(CIO) { install(WebSockets) } },
      ) {
        client.webSocket(socketUrl) {
          // Each frame after a table must carry the status bar and leave the table out: with no agent connected,
          // nothing is recorded in the activity log, and the page's message hasn't changed.
          suspend fun expectQuiet() =
            repeat(QUIET_FRAMES) {
              withTimeout(FRAME_PATIENCE) { nextText() }.also {
                it shouldContain """id="${ProxyDashboardHtml.STATUS_ID}""""
                it shouldNotContain ACTIVITY_TABLE
              }
            }

          // The page hasn't been sent a table, so the first frame rendered from its message carries one.
          send(Frame.Text("""{"layout":"ACTIVITY","seq":1}"""))
          firstFrame { viewOf(1L) in it } shouldContain ACTIVITY_TABLE
          expectQuiet()

          // A new message, here asking for the same view, is sent the table again: the page drops a frame stamped
          // below the count it has sent, so it may never have shown the frame that carried the last one.
          send(Frame.Text("""{"layout":"ACTIVITY","seq":2}"""))
          firstFrame { viewOf(2L) in it } shouldContain ACTIVITY_TABLE
          expectQuiet()

          // The agent's connection is recorded in the activity log, which changes the feed, so it is sent again.
          connectAgent()
          firstFrame { ACTIVITY_TABLE in it } shouldContain CONNECTED_EVENT
        }
      }
    }
  }

  companion object {
    private const val CONFIG_FILE = "config/test-configs/web-ui.conf"
    private const val AGENT_NAME = "web-ui-agent"

    // Dedicated ports, following the one-off convention the other standalone harness specs use.
    private const val PROXY_HTTP_PORT = TestPorts.DASHBOARD_UI_PROXY_HTTP_PORT
    private const val PROXY_GRPC_PORT = TestPorts.DASHBOARD_UI_PROXY_GRPC_PORT
    private const val DASHBOARD_PORT = TestPorts.DASHBOARD_UI_DASHBOARD_PORT
    private const val OFF_HTTP_PORT = TestPorts.DASHBOARD_UI_OFF_HTTP_PORT
    private const val OFF_GRPC_PORT = TestPorts.DASHBOARD_UI_OFF_GRPC_PORT
    private const val OFF_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_OFF_DASHBOARD_PORT

    // Bounded so a push that never carries the agent fails the assertion rather than hanging the suite.
    private const val MAX_FRAMES = 12

    private const val FAILOVER_HTTP_PORT = TestPorts.DASHBOARD_UI_FAILOVER_HTTP_PORT
    private const val FAILOVER_GRPC_PORT = TestPorts.DASHBOARD_UI_FAILOVER_GRPC_PORT
    private const val FAILOVER_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_FAILOVER_DASHBOARD_PORT

    // Nothing listens here, so the agent's first endpoint fails and it advances to the second.
    private const val DEAD_PORT = TestPorts.DASHBOARD_UI_DEAD_PORT

    private val AGENT_ID_PATTERN = """hx-get="/dashboard/agents/([^"]+)"""".toRegex()

    private const val NAV_HTTP_PORT = TestPorts.DASHBOARD_UI_NAV_HTTP_PORT
    private const val NAV_GRPC_PORT = TestPorts.DASHBOARD_UI_NAV_GRPC_PORT
    private const val NAV_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_NAV_DASHBOARD_PORT

    private const val ROOT_HTTP_PORT = TestPorts.DASHBOARD_UI_ROOT_HTTP_PORT
    private const val ROOT_GRPC_PORT = TestPorts.DASHBOARD_UI_ROOT_GRPC_PORT
    private const val ROOT_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_ROOT_DASHBOARD_PORT

    // Registers a real path, so it needs its own config and its own ports.
    private const val PATHS_CONFIG_FILE = "config/test-configs/web-ui-paths.conf"
    private const val PATHS_HTTP_PORT = TestPorts.DASHBOARD_UI_PATHS_HTTP_PORT
    private const val PATHS_GRPC_PORT = TestPorts.DASHBOARD_UI_PATHS_GRPC_PORT
    private const val PATHS_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_PATHS_DASHBOARD_PORT

    private const val SELECT_HTTP_PORT = TestPorts.DASHBOARD_UI_SELECT_HTTP_PORT
    private const val SELECT_GRPC_PORT = TestPorts.DASHBOARD_UI_SELECT_GRPC_PORT
    private const val SELECT_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_SELECT_DASHBOARD_PORT

    private const val MOUNT_HTTP_PORT = TestPorts.DASHBOARD_UI_MOUNT_HTTP_PORT
    private const val MOUNT_GRPC_PORT = TestPorts.DASHBOARD_UI_MOUNT_GRPC_PORT
    private const val MOUNT_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_MOUNT_DASHBOARD_PORT

    private const val ORIGIN_HTTP_PORT = TestPorts.DASHBOARD_UI_ORIGIN_HTTP_PORT
    private const val ORIGIN_GRPC_PORT = TestPorts.DASHBOARD_UI_ORIGIN_GRPC_PORT
    private const val ORIGIN_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_ORIGIN_DASHBOARD_PORT
    private const val ORIGINS_CONFIG_FILE = "config/test-configs/web-ui-origins.conf"

    // Must match the allowedOrigins entry in ORIGINS_CONFIG_FILE.
    private const val ALLOWED_ORIGIN = "https://dash.example.com"

    private const val HOST_HTTP_PORT = TestPorts.DASHBOARD_UI_HOST_HTTP_PORT
    private const val HOST_GRPC_PORT = TestPorts.DASHBOARD_UI_HOST_GRPC_PORT
    private const val HOST_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_HOST_DASHBOARD_PORT
    private const val HOSTS_CONFIG_FILE = "config/test-configs/web-ui-hosts.conf"

    // Must match the allowedHosts entry in HOSTS_CONFIG_FILE.
    private const val ALLOWED_HOST = "dash.internal"

    private const val CAP_HTTP_PORT = TestPorts.DASHBOARD_UI_CAP_HTTP_PORT
    private const val CAP_GRPC_PORT = TestPorts.DASHBOARD_UI_CAP_GRPC_PORT
    private const val CAP_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_CAP_DASHBOARD_PORT

    // An agent that connects as an identity and registers one path, so it needs its own config and ports.
    private const val IDENTITY_CONFIG_FILE = "config/test-configs/web-ui-identity.conf"
    private const val IDENTITY_HTTP_PORT = TestPorts.DASHBOARD_UI_IDENTITY_HTTP_PORT
    private const val IDENTITY_GRPC_PORT = TestPorts.DASHBOARD_UI_IDENTITY_GRPC_PORT
    private const val IDENTITY_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_IDENTITY_DASHBOARD_PORT

    private const val HISTORY_HTTP_PORT = TestPorts.DASHBOARD_UI_HISTORY_HTTP_PORT
    private const val HISTORY_GRPC_PORT = TestPorts.DASHBOARD_UI_HISTORY_GRPC_PORT
    private const val HISTORY_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_HISTORY_DASHBOARD_PORT

    private const val FRAME_HTTP_PORT = TestPorts.DASHBOARD_UI_FRAME_HTTP_PORT
    private const val FRAME_GRPC_PORT = TestPorts.DASHBOARD_UI_FRAME_GRPC_PORT
    private const val FRAME_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_FRAME_DASHBOARD_PORT

    // Far past the incoming frame cap, and far past any message the dashboard page sends.
    private const val OVERSIZED_MESSAGE_CHARS = 1_000_000

    // RFC 6455's code for a connection that ended without a close frame; Ktor's CloseReason.Codes.CLOSED_ABNORMALLY
    // is internal API.
    private const val ABNORMAL_CLOSURE_CODE: Short = 1006

    private const val CACHE_HTTP_PORT = TestPorts.DASHBOARD_UI_CACHE_HTTP_PORT
    private const val CACHE_GRPC_PORT = TestPorts.DASHBOARD_UI_CACHE_GRPC_PORT
    private const val CACHE_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_CACHE_DASHBOARD_PORT
    private const val MESSAGE_COUNT = 5

    private const val RENDER_FAILURE_HTTP_PORT = TestPorts.DASHBOARD_UI_RENDER_FAILURE_HTTP_PORT
    private const val RENDER_FAILURE_GRPC_PORT = TestPorts.DASHBOARD_UI_RENDER_FAILURE_GRPC_PORT
    private const val RENDER_FAILURE_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_RENDER_FAILURE_DASHBOARD_PORT

    private const val SCRAPE_HTTP_PORT = TestPorts.DASHBOARD_UI_SCRAPE_HTTP_PORT
    private const val SCRAPE_GRPC_PORT = TestPorts.DASHBOARD_UI_SCRAPE_GRPC_PORT
    private const val SCRAPE_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_SCRAPE_DASHBOARD_PORT
    private const val SCRAPE_METRICS_PORT = TestPorts.DASHBOARD_UI_SCRAPE_METRICS_PORT
    private const val SCRAPE_AGENT_METRICS_PORT = TestPorts.DASHBOARD_UI_SCRAPE_AGENT_METRICS_PORT

    // The agent's per-scrape metrics, by the names their snapshots carry (a counter's without _total), which count only
    // Prometheus's scrapes, and the counter that counts the dashboard's instead.
    private val AGENT_SCRAPE_METRICS =
      ["agent_scrape_request_count", "agent_scrape_result_count", "agent_scrape_request_latency_seconds"]
    private const val AGENT_DASHBOARD_SCRAPES = "agent_dashboard_scrapes"
    private val SCRAPE_STUB_BODY =
      """
      # HELP demo_up Whether the demo is up.
      # TYPE demo_up gauge
      demo_up 1
      # TYPE demo_requests counter
      demo_requests_total 42

      """.trimIndent()

    private const val PAGE_CACHE_HTTP_PORT = TestPorts.DASHBOARD_UI_PAGE_CACHE_HTTP_PORT
    private const val PAGE_CACHE_GRPC_PORT = TestPorts.DASHBOARD_UI_PAGE_CACHE_GRPC_PORT
    private const val PAGE_CACHE_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_PAGE_CACHE_DASHBOARD_PORT

    // Each round GETs all three page routes, and the agent route both as a page and as an htmx fragment.
    private const val PAGE_ROUNDS = 5

    private const val PAGE_FRESH_HTTP_PORT = TestPorts.DASHBOARD_UI_PAGE_FRESH_HTTP_PORT
    private const val PAGE_FRESH_GRPC_PORT = TestPorts.DASHBOARD_UI_PAGE_FRESH_GRPC_PORT
    private const val PAGE_FRESH_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_PAGE_FRESH_DASHBOARD_PORT

    // What PATHS_CONFIG_FILE's agent is called and registers.
    private const val PATHS_AGENT_NAME = "web-ui-path-agent"
    private const val PATHS_PATH = "/ui_path_metrics"

    // Far below the 60s refresh interval the freshness spec sets, so only an invalidation can pass within it.
    private val TOPOLOGY_PATIENCE = 10.seconds

    // The agent-list row marker for the session's selected agent (the nav uses aria-current="page").
    private const val SELECTED_ROW = """aria-current="true""""

    private const val ACTIVITY_PUSH_HTTP_PORT = TestPorts.DASHBOARD_UI_ACTIVITY_PUSH_HTTP_PORT
    private const val ACTIVITY_PUSH_GRPC_PORT = TestPorts.DASHBOARD_UI_ACTIVITY_PUSH_GRPC_PORT
    private const val ACTIVITY_PUSH_DASHBOARD_PORT = TestPorts.DASHBOARD_UI_ACTIVITY_PUSH_DASHBOARD_PORT

    // The Activity table's region as a frame carries it, and the line an agent's connection adds to it.
    private const val ACTIVITY_TABLE = """id="${ProxyDashboardHtml.ACTIVITY_ID}""""
    private const val CONNECTED_EVENT = """<td class="event">connected</td>"""

    // Two, so that at least one is a tick: the frame before them may be the tick that rendered the browser's message
    // just before the socket's own push of it, which comes next.
    private const val QUIET_FRAMES = 2

    // Far longer than MAX_FRAMES ticks at the 1s refresh, so it ends only a wait that is already failing.
    private val FRAME_PATIENCE = 30.seconds
  }
}
