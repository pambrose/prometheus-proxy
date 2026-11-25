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

package io.prometheus.common.dashboard

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.captureLogs
import io.prometheus.common.dashboard.DashboardServer.Companion.ASSET_DIR
import io.prometheus.common.dashboard.DashboardServer.Companion.HX_REQUEST_HEADER
import io.prometheus.common.dashboard.DashboardServer.Companion.ICON_DIR
import io.prometheus.proxy.dashboard.LinkIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withTimeout
import kotlinx.html.body
import java.net.Socket
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What [DashboardServer] answers, against a site the spec stands in for, with no proxy or agent: the headers every
 * response carries, the refusals and error pages included, so no other site's page can frame the dashboard, and, with a
 * login set, none but the versioned assets kept by a browser's cache; and the line a refused Origin or Host is logged
 * with, which needs no login, so it shows the client's header cut and escaped, at INFO once a minute for each address.
 *
 * The pages served by a real proxy and agent are `ProxyWebDashboardTest`'s, `ProxyWebDashboardLoginTest`'s, and
 * `AgentWebDashboardTest`'s.
 */
class DashboardResponsesTest : StringSpec() {
  // A site of a page, a fragment, an action behind pageGuard, and a page that throws, with nothing to push.
  private class PlainSite : DashboardSite<String, Int> {
    override val changes: Flow<Any?> = emptyFlow()

    override suspend fun collect() = 0

    override fun Route.pages(server: DashboardServer<String, Int>) {
      get(server.basePath) { call.respondHtml { body { +"The page" } } }
      get("${server.routeBase}/$FRAGMENT") { call.respondText("<div id=\"detail\"></div>", ContentType.Text.Html) }
      get("${server.routeBase}/$BROKEN") { error("A page that throws") }
      route("${server.routeBase}/$ACTION") {
        install(server.pageGuard)
        post { call.respondText("<p>Done</p>", ContentType.Text.Html) }
      }
    }

    override fun parseView(message: String) = message

    override fun frame(
      snapshot: Int,
      view: String?,
      sent: Any?,
      routeBase: String,
    ) = DashboardFrame("snapshot $snapshot")
  }

  // One case's server, and a client that follows no redirect.
  private class Fixture(
    val port: Int,
    val client: HttpClient,
  ) {
    val base = "http://$LOOPBACK_HOST:$port"

    suspend fun get(
      path: String,
      block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.get("$base$path", block)

    suspend fun post(
      path: String,
      block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = client.post("$base$path", block)

    // Waits until the server answers through its routes: an engine still installing them answers everything with 404.
    suspend fun awaitServing() =
      withTimeout(STARTUP_PATIENCE) {
        while (runCatching { get("/dashboard/session").status }.getOrNull() !in SERVING) {
          delay(20.milliseconds)
        }
      }

    // The status line the server answers [request] with, sent as it is, on a socket of its own: the HTTP client refuses
    // a header holding a control character, which the server takes.
    fun rawStatusLine(request: String): String =
      Socket(LOOPBACK_HOST, port).use { socket ->
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        socket.getOutputStream().apply {
          write(request.toByteArray())
          flush()
        }
        socket.getInputStream().bufferedReader().readLine().orEmpty()
      }
  }

  // A server with a login when [login], refusing a Host not in [allowedHosts] when it has any, for [block]; stopped
  // after it.
  private suspend fun withServer(
    port: Int,
    login: Boolean = false,
    allowedHosts: List<String> = emptyList(),
    block: suspend Fixture.() -> Unit,
  ) {
    val settings =
      DashboardSettings(
        role = ROLE,
        host = LOOPBACK_HOST,
        port = port,
        path = "dashboard",
        refreshInterval = 1.hours,
        maxSessions = 10,
        allowedOrigins = emptyList(),
        allowedHosts = allowedHosts,
        login = if (login) DashboardLogin(USERNAME, PASSWORD) else null,
        loginHeading = "Responses test",
        icons = mapOf(ICON.fileName to ICON.resource),
      )
    val server = DashboardServer(settings, PlainSite())
    val client = HttpClient(CIO) { followRedirects = false }
    server.start()
    try {
      Fixture(port, client).apply {
        awaitServing()
        block()
      }
    } finally {
      client.close()
      server.stop()
    }
  }

  init {
    // Only the login page refused framing, so a page on another site could frame the rest, the dashboard's own
    // WebSocket and actions passing the Origin check, and steer a viewer's clicks to Scrape now or Log out.
    "every response should refuse framing, the refusals and error pages included" {
      withServer(TestPorts.DASHBOARD_RESPONSES_FRAMING_PORT, allowedHosts = listOf("dash.internal")) {
        val responses =
          mapOf(
            "page" to get("/dashboard"),
            "fragment" to get("/dashboard/$FRAGMENT") { header(HX_REQUEST_HEADER, "true") },
            "action" to post("/dashboard/$ACTION") { header(HX_REQUEST_HEADER, "true") },
            "foreign origin" to
              post("/dashboard/$ACTION") {
                header(HX_REQUEST_HEADER, "true")
                header(HttpHeaders.Origin, "https://evil.example.com")
              },
            "foreign host" to get("/dashboard") { header(HttpHeaders.Host, "evil.example.com:$port") },
            "asset" to get("/dashboard/$ASSET_DIR/htmx.min.js"),
            "icon" to get("/dashboard/$ICON_DIR/${ICON.fileName}"),
            "session" to get("/dashboard/session"),
            "root" to get("/"),
            "unknown" to get("/dashboard/nothing-here"),
            "broken" to get("/dashboard/$BROKEN"),
          )

        responses.mapValues { it.value.status.value } shouldBe
          mapOf(
            "page" to 200,
            "fragment" to 200,
            "action" to 200,
            "foreign origin" to 403,
            "foreign host" to 403,
            "asset" to 200,
            "icon" to 200,
            "session" to 204,
            "root" to 302,
            "unknown" to 404,
            "broken" to 500,
          )
        responses.filterValues { !it.refusesFraming() }.keys.shouldBeEmpty()

        // A brand mark keeps its own policy, which runs it sandboxed when opened directly, beside the framing one.
        responses.getValue("icon").headers.getAll(CSP).orEmpty() shouldContain "sandbox"

        // Without a login the pages say nothing of caching, and the versioned assets keep their year.
        responses.getValue("page").headers[HttpHeaders.CacheControl] shouldBe null
        responses.getValue("asset").headers[HttpHeaders.CacheControl].orEmpty() shouldContain "max-age=31536000"
      }
    }

    // The pages behind the login could be kept in the browser's cache, and shown again after Log out.
    "with a login set, no response but the versioned assets should be cached, the login page's included" {
      withServer(TestPorts.DASHBOARD_RESPONSES_CACHING_PORT, login = true) {
        val signedIn: HttpRequestBuilder.() -> Unit = { basicAuth(USERNAME, PASSWORD) }
        val responses =
          mapOf(
            "login page" to get("/dashboard/login"),
            "page" to get("/dashboard", signedIn),
            "fragment" to
              get("/dashboard/$FRAGMENT") {
                signedIn()
                header(HX_REQUEST_HEADER, "true")
              },
            "page without a login" to get("/dashboard") { header(HttpHeaders.Accept, "text/html") },
            "session without a login" to get("/dashboard/session"),
            "unknown" to get("/dashboard/nothing-here", signedIn),
            "broken" to get("/dashboard/$BROKEN", signedIn),
          )
        val asset = get("/dashboard/$ASSET_DIR/htmx.min.js", signedIn)

        responses.mapValues { it.value.status.value } shouldBe
          mapOf(
            "login page" to 200,
            "page" to 200,
            "fragment" to 200,
            "page without a login" to 303,
            "session without a login" to 401,
            "unknown" to 404,
            "broken" to 500,
          )
        responses.filterValues { it.headers.getAll(HttpHeaders.CacheControl) != NO_STORE }.keys.shouldBeEmpty()
        responses.filterValues { !it.refusesFraming() }.keys.shouldBeEmpty()

        asset.status shouldBe HttpStatusCode.OK
        asset.headers.getAll(HttpHeaders.CacheControl) shouldBe listOf("max-age=31536000, public")
        asset.refusesFraming() shouldBe true
      }
    }

    // The line went out whole, at INFO, for every refused request, before any login: a client could write terminal
    // escape sequences into the log, a request line's worth at a time, at its own request rate.
    "a refused Origin should be logged cut and escaped, and at INFO once a minute for each address" {
      withServer(TestPorts.DASHBOARD_RESPONSES_ORIGIN_LOG_PORT, login = true) {
        val request =
          "POST /dashboard/login HTTP/1.1\r\nHost: $LOOPBACK_HOST:$port\r\nOrigin: $HOSTILE_VALUE\r\n" +
            "Content-Length: 0\r\nConnection: close\r\n\r\n"

        val logged =
          captureLogs<DashboardServer<*, *>>(Level.DEBUG) {
            repeat(2) { rawStatusLine(request) shouldStartWith "HTTP/1.1 403" }
          }

        logged.refusals("Refused a dashboard request from origin").shouldBeShownOnce()
      }
    }

    "a refused Host should be logged cut and escaped, and at INFO once a minute for each address" {
      withServer(TestPorts.DASHBOARD_RESPONSES_HOST_LOG_PORT, allowedHosts = listOf("dash.internal")) {
        val request = "GET /dashboard HTTP/1.1\r\nHost: $HOSTILE_VALUE\r\nConnection: close\r\n\r\n"

        val logged =
          captureLogs<DashboardServer<*, *>>(Level.DEBUG) {
            repeat(2) { rawStatusLine(request) shouldStartWith "HTTP/1.1 403" }
          }

        logged.refusals("Refused a dashboard request for host").shouldBeShownOnce()
      }
    }
  }

  companion object {
    private const val ROLE = "proxy"
    private const val USERNAME = "admin"
    private const val PASSWORD = "a-long-test-password"
    private const val FRAGMENT = "fragment"
    private const val BROKEN = "broken"
    private const val ACTION = "action"
    private const val SOCKET_TIMEOUT_MILLIS = 10_000
    private const val CSP = "Content-Security-Policy"
    private const val X_FRAME_OPTIONS = "X-Frame-Options"
    private val NO_STORE = listOf("no-store")
    private val ICON = LinkIcon.PROMETHEUS

    // The longest line a refusal may log, well under the client's header and with room for any real origin or host.
    private const val MAX_LOGGED_CHARS = 400

    private val STARTUP_PATIENCE = 30.seconds

    // What /dashboard/session answers once the routes are in, without a login and with one.
    private val SERVING = setOf(HttpStatusCode.NoContent, HttpStatusCode.Unauthorized)

    private val ESC = Char(27)
    private const val BACKSLASH = '\\'

    // A header a terminal would clear its screen at, and 5,000 characters more, which the server's parser takes.
    private val HOSTILE_VALUE = "$ESC[2J" + "x".repeat(5_000)

    // Once each: a header added twice, as for a response answered twice, would be a sign of the rule applied twice.
    private fun HttpResponse.refusesFraming() =
      headers.getAll(CSP).orEmpty().filter { it.contains("frame-ancestors") } == listOf("frame-ancestors 'none'") &&
        headers.getAll(X_FRAME_OPTIONS) == listOf("DENY")

    private fun List<ILoggingEvent>.refusals(prefix: String) = filter { it.formattedMessage.startsWith(prefix) }

    // Two refusals from one address: the first at INFO and the second at DEBUG, each with the escape written out and
    // the rest of the header cut.
    private fun List<ILoggingEvent>.shouldBeShownOnce() {
      map { it.level } shouldBe listOf(Level.INFO, Level.DEBUG)
      forEach {
        it.formattedMessage shouldNotContain ESC.toString()
        it.formattedMessage shouldContain "${BACKSLASH}u001b[2J"
        it.formattedMessage.length shouldBeLessThan MAX_LOGGED_CHARS
      }
    }
  }
}
