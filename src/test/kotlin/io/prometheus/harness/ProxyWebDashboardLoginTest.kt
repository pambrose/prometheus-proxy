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
import ch.qos.logback.classic.Level
import com.pambrose.common.util.runCatchingCancellable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parameters
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.captureLogs
import io.prometheus.common.registeredSnapshot
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.common.dashboard.DashboardServer.Companion.ASSET_DIR
import io.prometheus.common.dashboard.DashboardServer.Companion.HX_REQUEST_HEADER
import io.prometheus.common.dashboard.DashboardServer.Companion.SEC_FETCH_MODE
import io.prometheus.common.dashboard.LoginHtml
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.proxy.dashboard.ProxyDashboardHtml
import io.prometheus.proxy.dashboard.ProxyDashboardService
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * The dashboard with a login, `proxy.dashboard.password`, over the Netty transport: the login page and its session
 * cookie, Log out, and Basic Auth for scripts. Each spec starts its own proxy, on the same ports, one after another, so
 * no spec's sessions or throttled warnings reach another's. Split from [ProxyWebDashboardTest], whose specs all run
 * without a login.
 */
class ProxyWebDashboardLoginTest : StringSpec() {
  private val base = "http://$LOOPBACK_HOST:$DASHBOARD_PORT"
  private val socketUrl = "ws://$LOOPBACK_HOST:$DASHBOARD_PORT/dashboard/events"
  private val cookie = DashboardServer.cookieName("proxy", DASHBOARD_PORT)

  // A proxy with its dashboard at [path] and, unless [password] is null, a login, and a client that follows no
  // redirect, so a spec sees each one.
  private suspend fun withProxy(
    path: String = "dashboard",
    password: String? = PASSWORD,
    // Whether the proxy's metrics are on, so its proxy.metrics { } blocks count.
    metrics: Boolean = false,
    block: suspend HttpClient.() -> Unit,
  ) {
    PrometheusRegistry.defaultRegistry.clear()
    val proxy =
      startProxy(
        args = buildList {
          addAll(["--agent_port", "$GRPC_PORT", "--dashboard", "--dashboard_port", "$DASHBOARD_PORT"])
          addAll(["--dashboard_path", path])
          password?.also { add("-Dproxy.dashboard.password=$it") }
          // Refused here, so these specs check that the setting reaches the dashboard; ProxyWebDashboardTest checks
          // the default, which allows framing.
          add("-Dproxy.dashboard.framingEnabled=false")
          if (metrics) addAll(["--metrics", "--metrics_port", "$METRICS_PORT"])
        },
        proxyPort = HTTP_PORT,
        configArgs = ["--config", CONFIG_FILE],
      )
    val client =
      HttpClient(CIO) {
        install(WebSockets)
        followRedirects = false
      }
    try {
      client.block()
    } finally {
      client.close()
      runCatching { proxy.stopSync(10.seconds) }
    }
  }

  private fun HttpRequestBuilder.session(token: String?) {
    token?.also { header(HttpHeaders.Cookie, "$cookie=$it") }
  }

  // What a browser asks for when it navigates to a page.
  private suspend fun HttpClient.page(
    path: String,
    token: String? = null,
    also: HttpRequestBuilder.() -> Unit = {},
  ) = get("$base$path") {
    header(HttpHeaders.Accept, "text/html,application/xhtml+xml,*/*;q=0.8")
    session(token)
    also()
  }

  private suspend fun HttpClient.logIn(
    username: String = USERNAME,
    password: String = PASSWORD,
    next: String = "/dashboard",
    origin: String? = base,
    at: String = "/dashboard/login",
  ) = post("$base$at") {
    origin?.also { header(HttpHeaders.Origin, it) }
    setBody(
      FormDataContent(
        parameters {
          append(LoginHtml.USERNAME_FIELD, username)
          append(LoginHtml.PASSWORD_FIELD, password)
          append(LoginHtml.NEXT_FIELD, next)
        },
      ),
    )
  }

  // The session cookie a response sets, whole, or null.
  private fun HttpResponse.setSessionCookie(): String? =
    headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith("$cookie=") }

  private fun HttpResponse.sessionToken(): String? =
    setSessionCookie()?.substringAfter("=")?.substringBefore(";")?.takeIf { it.isNotEmpty() }

  private suspend fun HttpClient.loggedIn(): String = logIn().sessionToken().shouldNotBeNull()

  // What the session check answers a request with [token], or with none.
  private suspend fun HttpClient.sessionStatus(token: String? = null) =
    get("$base/dashboard/session") { session(token) }.status

  // A request as a browser makes it, in the Fetch Metadata [mode] it marks it with, sending a Basic login it saved.
  private fun HttpRequestBuilder.browserBasic(
    mode: String,
    password: String = PASSWORD,
  ) {
    basicAuth(USERNAME, password)
    header(SEC_FETCH_MODE, mode)
  }

  init {
    "a page navigation without a session should go to the login page" {
      withProxy {
        page("/dashboard/paths?q=a").apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.Location] shouldBe "/dashboard/login?next=%2Fdashboard%2Fpaths%3Fq%3Da"
        }
      }
    }

    // A browser never shows its own prompt: no WWW-Authenticate, on anything.
    "anything else without a session should get a 401 and no challenge" {
      withProxy {
        val responses =
          listOf(
            get("$base/dashboard"),
            get("$base/dashboard/session"),
            get("$base/dashboard/$ASSET_DIR/htmx.min.js"),
            post("$base/dashboard/scrape?path=anything") {
              header(HttpHeaders.Origin, base)
              header(HX_REQUEST_HEADER, "true")
            },
          )
        responses.forEach {
          it.status shouldBe HttpStatusCode.Unauthorized
          it.headers[HttpHeaders.WWWAuthenticate].shouldBeNull()
        }
        runCatchingCancellable {
          webSocket(socketUrl) { incoming.receive() }
        }
          .exceptionOrNull()?.message.orEmpty() shouldContain "401"
      }
    }

    "the login page should be reachable, and show the heading" {
      withProxy {
        page("/dashboard/login?next=%2Fdashboard%2Fpaths").apply {
          status shouldBe HttpStatusCode.OK
          headers[HttpHeaders.CacheControl].orEmpty() shouldContain "no-store"
          headers["Content-Security-Policy"].orEmpty() shouldContain "frame-ancestors 'none'"
          bodyAsText().apply {
            this shouldContain ProxyDashboardService.LOGIN_HEADING
            this shouldContain """name="${LoginHtml.NEXT_FIELD}" value="/dashboard/paths""""
          }
        }
      }
    }

    "a wrong login should say so, and be logged at WARN once, without the username given" {
      withProxy {
        val logs =
          captureLogs<DashboardServer<*, *>>(Level.DEBUG) {
            repeat(2) {
              val response = logIn(username = WRONG_USERNAME, password = "wrong")
              response.status shouldBe HttpStatusCode.Unauthorized
              response.setSessionCookie().shouldBeNull()
              response.bodyAsText().apply {
                this shouldContain LoginHtml.FAILED
                this shouldContain """value="$WRONG_USERNAME""""
              }
            }
          }
        val refusals = logs.filter { "wrong login" in it.formattedMessage }
        refusals.size shouldBe 2
        refusals.count { it.level == Level.WARN } shouldBe 1
        refusals.none { WRONG_USERNAME in it.formattedMessage }.shouldBeTrue()
      }
    }

    "a right login should set the session cookie and return to the page asked for" {
      withProxy {
        logIn(next = "/dashboard/paths?q=a").apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.Location] shouldBe "/dashboard/paths?q=a"
          setSessionCookie().shouldNotBeNull().apply {
            this shouldContain "HttpOnly"
            this shouldContain "SameSite=Lax"
            this shouldContain "Path=/dashboard;"
            this shouldContain "Max-Age=604800"
            this shouldNotContain "Secure"
          }
        }
        // Off-site, or back to the login page, it goes to the dashboard instead.
        logIn(next = "//evil.example/").headers[HttpHeaders.Location] shouldBe "/dashboard"
        // From an https origin, as behind a TLS-terminating reverse proxy listed in allowedOrigins, it's Secure.
        logIn(origin = ALLOWED_ORIGIN).setSessionCookie().orEmpty() shouldContain "Secure"
      }
    }

    "with the session cookie, the dashboard should work" {
      withProxy {
        val token = loggedIn()
        page("/dashboard", token).apply {
          status shouldBe HttpStatusCode.OK
          // Behind a login, a page is never kept in the browser's cache, to be shown again after Log out, or framed.
          headers[HttpHeaders.CacheControl] shouldBe "no-store"
          headers["Content-Security-Policy"].orEmpty() shouldContain "frame-ancestors 'none'"
          bodyAsText() shouldContain """id="${DashboardPage.LOGOUT_ID}""""
        }
        sessionStatus(token) shouldBe HttpStatusCode.NoContent
        page("/dashboard/login", token).apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.Location] shouldBe "/dashboard"
        }
        post("$base/dashboard/scrape?path=anything") {
          session(token)
          header(HttpHeaders.Origin, base)
          header(HX_REQUEST_HEADER, "true")
        }.status shouldNotBe HttpStatusCode.Unauthorized
        webSocket(socketUrl, request = { session(token) }) {
          (incoming.receive() as Frame.Text).readText() shouldContain "hx-swap-oob"
        }
      }
    }

    "Log out should end the session" {
      withProxy {
        val token = loggedIn()
        post("$base/dashboard/logout") {
          session(token)
          header(HttpHeaders.Origin, base)
        }.apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.Location] shouldBe "/dashboard/login"
          setSessionCookie().orEmpty() shouldContain "Max-Age=0"
        }
        sessionStatus(token) shouldBe HttpStatusCode.Unauthorized
      }
    }

    "another site's page should not be able to log in or out" {
      withProxy {
        logIn(origin = "https://evil.example.com").apply {
          status shouldBe HttpStatusCode.Forbidden
          setSessionCookie().shouldBeNull()
        }
        val token = loggedIn()
        post("$base/dashboard/logout") {
          session(token)
          header(HttpHeaders.Origin, "https://evil.example.com")
        }.status shouldBe HttpStatusCode.Forbidden
        sessionStatus(token) shouldBe HttpStatusCode.NoContent
      }
    }

    // Unauthenticated, so the route mustn't read a body of any size.
    "an oversized or chunked login body should be refused" {
      withProxy {
        logIn(username = "a".repeat(5_000)).status shouldBe HttpStatusCode.PayloadTooLarge
        post("$base/dashboard/login") {
          header(HttpHeaders.Origin, base)
          setBody(
            object : OutgoingContent.WriteChannelContent() {
              override val contentType = ContentType.Application.FormUrlEncoded

              override suspend fun writeTo(channel: ByteWriteChannel) {
                channel.writeStringUtf8("username=admin&password=$PASSWORD&next=%2Fdashboard")
              }
            },
          )
        }.status shouldBe HttpStatusCode.PayloadTooLarge
      }
    }

    "scripts should still log in with Basic Auth" {
      withProxy {
        get("$base/dashboard") { basicAuth(USERNAME, PASSWORD) }.status shouldBe HttpStatusCode.OK
        get("$base/dashboard") { basicAuth(USERNAME, "wrong") }.status shouldBe HttpStatusCode.Unauthorized
      }
    }

    // A limit, not a delay, which a guesser sending logins in parallel wouldn't feel: past it, every login from the
    // address is refused until the oldest wrong one is a minute old, the right password included, from the form or as
    // Basic Auth. Each spec has a proxy of its own, so the limit starts from nothing here.
    "too many wrong logins from one address should be refused for the rest of the minute" {
      withProxy {
        repeat(DashboardServer.MAX_BAD_LOGINS_PER_ADDRESS) {
          logIn(password = "wrong").status shouldBe HttpStatusCode.Unauthorized
        }
        logIn().apply {
          status shouldBe HttpStatusCode.TooManyRequests
          headers[HttpHeaders.RetryAfter] shouldBe "60"
          setSessionCookie().shouldBeNull()
          bodyAsText() shouldContain LoginHtml.LIMITED
        }
        get("$base/dashboard") { basicAuth(USERNAME, PASSWORD) }.status shouldBe HttpStatusCode.TooManyRequests
      }
    }

    // proxy_dashboard_logins_total: a sign-in on the login page, a wrong login from the page or a script, and one
    // refused for the limit. A script's right Basic Auth logs in on every request, so it isn't counted as a sign-in.
    "each login should be counted by its outcome" {
      withProxy(metrics = true) {
        logIn().status shouldBe HttpStatusCode.SeeOther
        get("$base/dashboard") { basicAuth(USERNAME, PASSWORD) }.status shouldBe HttpStatusCode.OK
        get("$base/dashboard") { basicAuth(USERNAME, "wrong") }.status shouldBe HttpStatusCode.Unauthorized
        repeat(DashboardServer.MAX_BAD_LOGINS_PER_ADDRESS - 1) { logIn(password = "wrong") }
        logIn().status shouldBe HttpStatusCode.TooManyRequests

        val logins = registeredCounts("proxy_dashboard_logins", "outcome")
        logins shouldBe
          mapOf("success" to 1.0, "failure" to DashboardServer.MAX_BAD_LOGINS_PER_ADDRESS.toDouble(), "blocked" to 1.0)
      }
    }

    // The login is checked when a page's WebSocket opens; a socket outlives that, so the push loop closes one whose
    // session has ended, and Log out closes its own at once. The page then asks for its session and gets the login
    // page.
    "an open dashboard's WebSocket should close when its session ends" {
      withProxy {
        val token = loggedIn()
        webSocket(socketUrl, request = { session(token) }) {
          (incoming.receive() as Frame.Text).readText() shouldContain "hx-swap-oob"
          post("$base/dashboard/logout") {
            session(token)
            header(HttpHeaders.Origin, base)
          }.status shouldBe HttpStatusCode.SeeOther
          withTimeout(10.seconds) {
            while (!incoming.receiveCatching().isClosed) {
              // Frames pushed before the close; the next one is the close itself.
            }
          }
          closeReason.await()?.knownReason shouldBe CloseReason.Codes.VIOLATED_POLICY
        }
      }
    }

    // A browser that once saved a Basic login, from a prompt for this origin, sends it with every request. If that let
    // it in, it would skip the login page, and Log out couldn't sign it out. Browsers mark every request with
    // Sec-Fetch-Mode, which a script doesn't send, so a browser's Basic login is ignored, not even logged as wrong.
    "a browser's saved Basic login should not get past the login page" {
      withProxy {
        val logs =
          captureLogs<DashboardServer<*, *>>(Level.DEBUG) {
            page("/dashboard") { browserBasic("navigate") }.apply {
              status shouldBe HttpStatusCode.SeeOther
              headers[HttpHeaders.Location] shouldBe "/dashboard/login?next=%2Fdashboard"
            }
            get("$base/dashboard/session") { browserBasic("cors") }.status shouldBe HttpStatusCode.Unauthorized
            runCatchingCancellable {
              webSocket(
                socketUrl,
                request = { browserBasic("websocket") },
              ) { incoming.receive() }
            }.exceptionOrNull()?.message.orEmpty() shouldContain "401"
            page("/dashboard") { browserBasic("navigate", password = "stale") }.status shouldBe HttpStatusCode.SeeOther
          }
        logs.none { "wrong login" in it.formattedMessage }.shouldBeTrue()
      }
    }

    "at a root mount, the login page should be at /login" {
      withProxy(path = "/") {
        page("/agents/x").headers[HttpHeaders.Location] shouldBe "/login?next=%2Fagents%2Fx"
        logIn(next = "/paths", at = "/login").apply {
          headers[HttpHeaders.Location] shouldBe "/paths"
          setSessionCookie().orEmpty() shouldContain "Path=/;"
        }
      }
    }

    "with no password set, there should be no login page and no Log out" {
      withProxy(password = null) {
        page("/dashboard/login").status shouldBe HttpStatusCode.NotFound
        page("/dashboard").bodyAsText() shouldNotContain """id="${DashboardPage.LOGOUT_ID}""""
        sessionStatus() shouldBe HttpStatusCode.NoContent
        page("/dashboard").setSessionCookie().shouldBeNull()
      }
    }
  }

  private companion object {
    const val HTTP_PORT = TestPorts.DASHBOARD_UI_LOGIN_HTTP_PORT
    const val GRPC_PORT = TestPorts.DASHBOARD_UI_LOGIN_GRPC_PORT
    const val DASHBOARD_PORT = TestPorts.DASHBOARD_UI_LOGIN_DASHBOARD_PORT
    const val METRICS_PORT = TestPorts.DASHBOARD_UI_LOGIN_METRICS_PORT

    // allowedOrigins lists ALLOWED_ORIGIN, an https origin, as a reverse proxy's would be.
    const val CONFIG_FILE = "config/test-configs/web-ui-origins.conf"
    const val ALLOWED_ORIGIN = "https://dash.example.com"
    const val PASSWORD = "s3cret-Passw0rd"
    const val USERNAME = "admin"
    const val WRONG_USERNAME = "intruder"
  }
}
