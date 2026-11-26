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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.dashboard.DashboardServer
import io.prometheus.common.dashboard.LoginHtml
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.proxy.dashboard.ProxyDashboardService.Companion.SCREENS_PATH
import kotlin.time.Duration.Companion.seconds

/**
 * The proxy's screens page on a real proxy, over the Netty transport: `/screens` with screens listed and without,
 * behind the dashboard's login, which now sets its cookie at the root and returns to the page, and the framing the
 * dashboard sends with `framingEnabled = false` once the page has to frame it. The page's markup is
 * `ScreensHtmlTest`'s.
 */
class ProxyScreensTest : StringSpec() {
  private val base = "http://$LOOPBACK_HOST:$DASHBOARD_PORT"
  private val cookie = DashboardServer.cookieName("proxy", DASHBOARD_PORT)

  // A proxy whose dashboard lists the screens in web-ui-screens.conf when [screens], with a login when [password] is
  // set, and with [extraArgs], and a client that follows no redirect, so a spec sees each one.
  private suspend fun withProxy(
    screens: Boolean = true,
    password: String? = null,
    extraArgs: List<String> = emptyList(),
    block: suspend HttpClient.() -> Unit,
  ) {
    PrometheusRegistry.defaultRegistry.clear()
    val proxy =
      startProxy(
        args = buildList {
          addAll(["--agent_port", "$GRPC_PORT", "--dashboard", "--dashboard_port", "$DASHBOARD_PORT"])
          password?.also { add("-Dproxy.dashboard.password=$it") }
          addAll(extraArgs)
        },
        proxyPort = HTTP_PORT,
        configArgs = ["--config", if (screens) SCREENS_CONFIG else PLAIN_CONFIG],
      )
    val client = HttpClient(CIO) { followRedirects = false }
    try {
      client.block()
    } finally {
      client.close()
      runCatching { proxy.stopSync(10.seconds) }
    }
  }

  private suspend fun HttpClient.page(path: String): HttpResponse =
    get("$base$path") { header(HttpHeaders.Accept, "text/html,application/xhtml+xml,*/*;q=0.8") }

  // The values of the response's headers named [name] that hold frame-ancestors.
  private fun HttpResponse.frameAncestors() =
    headers.getAll("Content-Security-Policy").orEmpty().filter { "frame-ancestors" in it }

  init {
    "with no screens listed, /screens should answer 404, and the dashboard have no link to it" {
      withProxy(screens = false) {
        page(SCREENS_PATH).status shouldBe HttpStatusCode.NotFound
        page("/dashboard").bodyAsText() shouldNotContain "screens-link"
      }
    }

    "with screens listed, /screens should be a page of its own with the proxy's tab first, and the bar link to it" {
      withProxy {
        val screens = page(SCREENS_PATH)
        screens.status shouldBe HttpStatusCode.OK
        screens.bodyAsText().apply {
          Regex("""<a href="#([^"]+)" class="screen""").findAll(this).map { it.groupValues[1] }.toList() shouldBe
            ["proxy", "agent-east", "grafana"]
          this shouldContain
            """data-url="http://grafana.example.com:3000" data-start="http://grafana.example.com:3000/d/overview?kiosk""""
          // A whole page, with no socket: nothing pushes to it, nor swaps a fragment into it.
          this shouldNotContain "hx-ws:connect"
          this shouldNotContain "hx-swap-oob"
        }
        page("/dashboard").bodyAsText() shouldContain """<a href="$SCREENS_PATH" class="screens-link""""
      }
    }

    // The link's hash never reaches the server; the login page carries it (LoginHtmlTest). The cookie goes at /screens
    // as well as the dashboard's path, or a browser wouldn't send it to the page, outside the base.
    "behind the login, /screens should send a browser to the login page, and signing in return to it" {
      withProxy(password = PASSWORD) {
        page(SCREENS_PATH).apply {
          status shouldBe HttpStatusCode.SeeOther
          headers[HttpHeaders.Location] shouldBe "/dashboard/login?next=%2Fscreens"
        }
        val signIn =
          post("$base/dashboard/login") {
            header(HttpHeaders.Origin, base)
            setBody(
              FormDataContent(
                parameters {
                  append(LoginHtml.USERNAME_FIELD, "admin")
                  append(LoginHtml.PASSWORD_FIELD, PASSWORD)
                  append(LoginHtml.NEXT_FIELD, SCREENS_PATH)
                },
              ),
            )
          }
        signIn.status shouldBe HttpStatusCode.SeeOther
        signIn.headers[HttpHeaders.Location] shouldBe SCREENS_PATH
        val cookies = signIn.headers.getAll(HttpHeaders.SetCookie).orEmpty().filter { it.startsWith("$cookie=") }
        cookies.map { it.substringAfter("; Path=").substringBefore(";") } shouldBe ["/dashboard", SCREENS_PATH]
        val session = cookies.last()
        val token = session.substringAfter("=").substringBefore(";")
        get("$base$SCREENS_PATH") { header(HttpHeaders.Cookie, "$cookie=$token") }.status shouldBe HttpStatusCode.OK
      }
    }

    // Off, the dashboard would refuse its own page's frame; with screens listed, only its own origin may frame it.
    "with framing off and screens listed, the dashboard and the page should be framed only by their own origin" {
      withProxy(extraArgs = ["-Dproxy.dashboard.framingEnabled=false"]) {
        listOf("/dashboard", SCREENS_PATH).forEach { path ->
          page(path).apply {
            frameAncestors() shouldBe ["frame-ancestors 'self'"]
            headers.getAll("X-Frame-Options") shouldBe ["SAMEORIGIN"]
          }
        }
      }
      withProxy(screens = false, extraArgs = ["-Dproxy.dashboard.framingEnabled=false"]) {
        page("/dashboard").frameAncestors() shouldBe ["frame-ancestors 'none'"]
      }
    }
  }

  private companion object {
    const val HTTP_PORT = TestPorts.SCREENS_HTTP_PORT
    const val GRPC_PORT = TestPorts.SCREENS_GRPC_PORT
    const val DASHBOARD_PORT = TestPorts.SCREENS_DASHBOARD_PORT
    const val PASSWORD = "a-long-screens-password"
    const val SCREENS_CONFIG = "config/test-configs/web-ui-screens.conf"
    const val PLAIN_CONFIG = "config/test-configs/web-ui.conf"
  }
}
