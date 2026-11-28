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

package io.prometheus.proxy

import ch.qos.logback.classic.spi.ILoggingEvent
import com.pambrose.common.dsl.KtorDsl.newHttpClient
import com.pambrose.common.util.runCatchingCancellable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLoggingConfig
import io.ktor.server.plugins.compression.CompressionConfig
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.startAndAwaitReady
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedWriteChannelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import org.slf4j.event.Level
import ch.qos.logback.classic.Level as LogbackLevel
import io.ktor.server.cio.CIO as ServerCIO
import io.prometheus.common.proxyOptions
import io.prometheus.common.captureLogs

// Tests for ProxyHttpConfig which configures Ktor server plugins for the proxy HTTP service.
// A few build Ktor's config objects directly; most run an embedded CIO server with the proxy's plugins installed.
class ProxyHttpConfigTest : StringSpec() {
  // ==================== Helper Methods ====================

  private fun callGetFormattedLog(call: ApplicationCall): String {
    val methodName = "getFormattedLog"
    val method = ProxyHttpConfig::class.java.getDeclaredMethod(methodName, ApplicationCall::class.java)
    method.isAccessible = true
    return method.invoke(ProxyHttpConfig, call) as String
  }

  private fun callLogClientGone(
    call: ApplicationCall,
    cause: Throwable,
  ) {
    val method =
      ProxyHttpConfig::class.java.getDeclaredMethod("logClientGone", ApplicationCall::class.java, Throwable::class.java)
    method.isAccessible = true
    method.invoke(ProxyHttpConfig, call, cause)
  }

  private fun createTestProxy(): Proxy =
    Proxy(
      options = proxyOptions(emptyList()),
      inProcessServerName = "config-test-${System.nanoTime()}",
      testMode = true,
    )

  init {
    // ==================== Configuration Object Tests ====================

    "ProxyHttpConfig object should exist" {
      // Verify the ProxyHttpConfig object is accessible
      ProxyHttpConfig.shouldNotBeNull()
    }

    // ==================== Compression Configuration Tests ====================

    "CompressionConfig should support gzip configuration" {
      val config = CompressionConfig()

      // Apply gzip configuration similar to ProxyHttpConfig
      config.gzip {
        priority = 10.0
      }

      // Configuration should be applied without throwing
      config.shouldNotBeNull()
    }

    "CompressionConfig should support deflate configuration" {
      val config = CompressionConfig()

      // Apply deflate configuration similar to ProxyHttpConfig
      config.deflate {
        priority = 1.0
        minimumSize(1024)
      }

      // Configuration should be applied without throwing
      config.shouldNotBeNull()
    }

    "CompressionConfig should support multiple encoders" {
      val config = CompressionConfig()

      // Apply both gzip and deflate like ProxyHttpConfig does
      config.gzip {
        priority = 10.0
      }
      config.deflate {
        priority = 1.0
        minimumSize(1024)
      }

      config.shouldNotBeNull()
    }

    // ==================== HTTP Status Code Tests ====================

    "NotFound status code should have correct value" {
      HttpStatusCode.NotFound.value shouldBe 404
      HttpStatusCode.NotFound.description shouldBe "Not Found"
    }

    "Found status code should have correct value" {
      HttpStatusCode.Found.value shouldBe 302
    }

    "status codes should be comparable" {
      val status = HttpStatusCode.OK

      (status == HttpStatusCode.OK) shouldBe true
      (status == HttpStatusCode.NotFound) shouldBe false
    }

    // ==================== Compression Priority Tests ====================

    "gzip should be preferred over deflate for large responses" {
      val proxy = createTestProxy()
      val largeContent = "a".repeat(2000)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        val app = this
        with(ProxyHttpConfig) {
          app.configureKtorServer(proxy, isTestMode = true)
        }
        routing {
          get("/large") { call.respondText(largeContent) }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/large") {
          headers.append(HttpHeaders.AcceptEncoding, "gzip, deflate")
        }
        response.status shouldBe HttpStatusCode.OK
        response.headers[HttpHeaders.ContentEncoding] shouldBe "gzip"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "small responses should not be deflate-compressed" {
      val proxy = createTestProxy()
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        val app = this
        with(ProxyHttpConfig) {
          app.configureKtorServer(proxy, isTestMode = true)
        }
        routing {
          get("/small") { call.respondText("tiny") }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/small") {
          headers.append(HttpHeaders.AcceptEncoding, "deflate")
        }
        response.status shouldBe HttpStatusCode.OK
        response.headers[HttpHeaders.ContentEncoding] shouldBe null

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // Bug #6: gzip was missing minimumSize(1024), causing tiny responses
    // (even a few bytes) to be gzip-compressed with unnecessary overhead.
    // Both gzip and deflate should now skip compression for small responses.
    "Bug #6: small responses should not be gzip-compressed" {
      val proxy = createTestProxy()
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        val app = this
        with(ProxyHttpConfig) {
          app.configureKtorServer(proxy, isTestMode = true)
        }
        routing {
          get("/small") { call.respondText("tiny") }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/small") {
          headers.append(HttpHeaders.AcceptEncoding, "gzip")
        }
        response.status shouldBe HttpStatusCode.OK
        // Response should NOT be gzip-encoded because "tiny" is
        // well below the 1024-byte minimumSize threshold
        val encoding = response.headers[HttpHeaders.ContentEncoding]
        encoding shouldBe null

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== StatusPages Integration Tests ====================

    // The status line as plain text for a 404 with nothing to say: an unknown route, or a scrape of an unregistered
    // path, whose results carry no body. A 404 a route wrote a body for keeps it: the dashboards' Scrape now says there
    // why it refused, which the status line replaced.
    "the 404 handler should give a body only to a 404 without one" {
      val server =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
          install(StatusPages) { with(ProxyHttpConfig) { configureStatusPages() } }
          routing {
            get("/explained") { call.respondText("/x isn't registered", Text.Plain, HttpStatusCode.NotFound) }
            get("/empty") { call.respondText("", Text.Plain, HttpStatusCode.NotFound) }
          }
        }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        client.get("http://localhost:$port/explained").apply {
          status shouldBe HttpStatusCode.NotFound
          bodyAsText() shouldBe "/x isn't registered"
        }
        listOf("/empty", "/nonexistent").forEach { path ->
          client.get("http://localhost:$port$path").apply {
            status shouldBe HttpStatusCode.NotFound
            bodyAsText() shouldBe "404 Not Found"
          }
        }

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "StatusPages exception handler should return InternalServerError" {
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        install(StatusPages) {
          exception<Throwable> { call, _ ->
            call.respond(HttpStatusCode.InternalServerError)
          }
        }
        routing {
          get("/throw") {
            throw IllegalStateException("Test exception")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/throw")
        response.status shouldBe HttpStatusCode.InternalServerError

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== HTTP Status Code Constants ====================

    "Found status should have value 302" {
      HttpStatusCode.Found.value shouldBe 302
      HttpStatusCode.Found.description shouldBe "Found"
    }

    "InternalServerError status should have value 500" {
      HttpStatusCode.InternalServerError.value shouldBe 500
    }

    // ==================== StatusPagesConfig Tests ====================

    "StatusPagesConfig should support exception handler configuration" {
      val config = StatusPagesConfig()

      // Apply exception handler similar to ProxyHttpConfig
      config.exception<Throwable> { call, _ ->
        call.respond(HttpStatusCode.InternalServerError)
      }

      config.shouldNotBeNull()
    }

    "StatusPagesConfig should support status handler configuration" {
      val config = StatusPagesConfig()

      config.status(HttpStatusCode.NotFound) { call, cause ->
        call.respond(
          TextContent("${cause.value} ${cause.description}", Text.Plain.withCharset(Charsets.UTF_8), cause),
        )
      }

      config.shouldNotBeNull()
    }

    // ==================== getFormattedLog Tests ====================
    // getFormattedLog, a member of the internal ProxyHttpConfig object, has two branches:
    // - Found (302): includes Location header in the log
    // - All other statuses: standard log format without Location

    "getFormattedLog should include Location header for Found status" {
      val mockCall = mockk<ApplicationCall>(relaxed = true)

      every { mockCall.response.status() } returns HttpStatusCode.Found
      every { mockCall.response.headers[HttpHeaders.Location] } returns "/new-path"
      every { mockCall.request.origin.remoteHost } returns "192.168.1.1"

      val result = callGetFormattedLog(mockCall)

      result shouldContain "302 Found"
      result shouldContain "/new-path"
      result shouldContain "192.168.1.1"
      result shouldContain "->"
    }

    "getFormattedLog should not include Location for non-Found status" {
      val mockCall = mockk<ApplicationCall>(relaxed = true)

      every { mockCall.response.status() } returns HttpStatusCode.OK
      every { mockCall.request.origin.remoteHost } returns "10.0.0.1"

      val result = callGetFormattedLog(mockCall)

      result shouldContain "200 OK"
      result shouldContain "10.0.0.1"
      result shouldNotContain "->"
    }

    "getFormattedLog should handle null status gracefully" {
      val mockCall = mockk<ApplicationCall>(relaxed = true)

      every { mockCall.response.status() } returns null
      every { mockCall.request.origin.remoteHost } returns "127.0.0.1"

      val result = callGetFormattedLog(mockCall)

      // Null status hits the else branch
      result shouldContain "null"
      result shouldContain "127.0.0.1"
    }

    // November 26 #42: CIO passes control characters other than whitespace in a request's method and target, so the
    // call log shows both escaped, and a long path cut, as the scrape route logs a path; raw, a client could forge
    // lines in the log.
    "getFormattedLog should show the request's method and path escaped" {
      val mockCall = mockk<ApplicationCall>(relaxed = true)

      every { mockCall.response.status() } returns HttpStatusCode.NotFound
      every { mockCall.request.origin.method } returns HttpMethod("GET\r\nX")
      every { mockCall.request.origin.uri } returns "/metrics\r\n200 OK: GET - /forged?token=abc"
      every { mockCall.request.origin.remoteHost } returns "10.0.0.1"

      callGetFormattedLog(mockCall) shouldBe
        "404 Not Found: GET\\r\\nX - /metrics\\r\\n200 OK: GET - /forged - 10.0.0.1"

      every { mockCall.request.origin.uri } returns "/" + "a".repeat(200)

      callGetFormattedLog(mockCall) shouldBe "404 Not Found: GET\\r\\nX - /${"a".repeat(63)}... - 10.0.0.1"
    }

    // The StatusPages handler for a client that hung up, which the dashboards' servers install too
    "a call its client ended should be logged with its path escaped" {
      val mockCall = mockk<ApplicationCall>(relaxed = true)
      every { mockCall.request.origin.uri } returns "/metrics\r\nforged"

      val events =
        captureLogs<ProxyHttpConfig>(LogbackLevel.DEBUG) {
          callLogClientGone(mockCall, CancellationException("gone"))
        }

      events.map { it.formattedMessage } shouldBe
        ["Call to /metrics\\r\\nforged ended by the client: CancellationException - gone"]
    }

    // ==================== configureCallLogging Filter Tests ====================
    // The filter checks call.request.path().startsWith("/")

    "a path starting with a slash should pass the startsWith check the call-logging filter uses" {
      // The filter logic: call.request.path().startsWith("/")
      val path = "/metrics"
      path.startsWith("/") shouldBe true
    }

    "the root path should pass the startsWith check the call-logging filter uses" {
      val path = "/"
      path.startsWith("/") shouldBe true
    }

    "a path without a leading slash should fail the startsWith check the call-logging filter uses" {
      // This case would only happen if request.path() returns a path without leading slash,
      // which is unusual in HTTP but the filter handles it defensively
      val path = "metrics"
      path.startsWith("/") shouldBe false
    }

    // Item 26: per-request call logging is emitted at DEBUG (not INFO) so that, at the default
    // INFO root level, the continuous scrape-rate stream does not bury real WARN/ERROR events.
    "Item 26: configureCallLogging should set the call-logging level to DEBUG" {
      val config = CallLoggingConfig()
      with(ProxyHttpConfig) { config.configureCallLogging() }

      config.level shouldBe Level.DEBUG
    }

    // ==================== configureKtorServer Integration Tests ====================
    // These tests verify the full configureKtorServer() function by running an embedded server
    // with every plugin but CallLogging, which test mode leaves out, then making HTTP requests to verify behavior.

    "configureKtorServer should add X-Engine default header to responses" {
      val proxy = createTestProxy()
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        val app = this
        with(ProxyHttpConfig) { app.configureKtorServer(proxy, isTestMode = true) }
        routing {
          get("/test") { call.respondText("hello") }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/test")
        response.status shouldBe HttpStatusCode.OK
        response.headers["X-Engine"] shouldBe "Ktor"
        response.bodyAsText() shouldBe "hello"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "configureKtorServer should handle NotFound via StatusPages" {
      val proxy = createTestProxy()
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        val app = this
        with(ProxyHttpConfig) { app.configureKtorServer(proxy, isTestMode = true) }
        routing { }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/nonexistent")
        response.status shouldBe HttpStatusCode.NotFound
        response.bodyAsText() shouldContain "404"
        response.bodyAsText() shouldContain "Not Found"
        response.headers["X-Engine"] shouldBe "Ktor"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // A client that disconnects while its response is being written makes the write fail with a ChannelIOException
    // (ChannelWriteException caused by a closed channel). Like a cancellation, that is routine, not a server error.
    "configureKtorServer should log a write to a disconnected client at DEBUG, not WARN" {
      val events =
        captureProxyHttpConfigLogs { client, baseUrl ->
          // The server sends nothing once its write has failed, so the request would wait out the client's 15s
          // request timeout; the handler has logged long before this one.
          withTimeoutOrNull(2.seconds) { runCatchingCancellable { client.get("$baseUrl/write-failed") } }
        }

      events.map { it.formattedMessage }.filter { "ChannelWriteException" in it }.shouldNotBeEmpty()
      events.filter { it.level == LogbackLevel.WARN }.shouldBeEmpty()
    }

    "configureKtorServer should handle exceptions via StatusPages and log them at WARN" {
      val events =
        captureProxyHttpConfigLogs { client, baseUrl ->
          val response = client.get("$baseUrl/throw")
          response.status shouldBe HttpStatusCode.InternalServerError
          response.headers["X-Engine"] shouldBe "Ktor"
        }

      events.filter { it.level == LogbackLevel.WARN }.map { it.throwableProxy?.className } shouldContain
        IllegalStateException::class.java.name
    }

    // Ktor's HttpRequestLifecycle cancels a call when its client disconnects, which happens on every scrape Prometheus
    // abandons at its own timeout. The catch-all handler logged each one at WARN with a stack trace.
    "configureKtorServer should log a call cancelled by a client disconnect at DEBUG, not WARN" {
      val events =
        captureProxyHttpConfigLogs { client, baseUrl ->
          runCatchingCancellable { client.get("$baseUrl/cancelled") }
        }

      events.map { it.formattedMessage }.filter { "CancellationException" in it }.shouldNotBeEmpty()
      events.filter { it.level == LogbackLevel.WARN }.shouldBeEmpty()
    }
  }

  // The routes captureProxyHttpConfigLogs serves, each failing with the exception built here.
  private val failingRoutes: Map<String, () -> Throwable> =
    mapOf(
      "/cancelled" to { CancellationException("Call context was cancelled by `HttpRequestLifecycle` plugin") },
      "/write-failed" to { ChannelWriteException(exception = ClosedWriteChannelException(IOException("Broken pipe"))) },
      "/throw" to { IllegalStateException("Test error") },
    )

  // Runs [block] with a client and the base URL of a proxy-configured server that serves [failingRoutes], and returns
  // what ProxyHttpConfig logged at DEBUG and above. The server's handlers finish before the response is sent, so the
  // events are complete once [block] returns.
  private suspend fun captureProxyHttpConfigLogs(
    block: suspend (client: HttpClient, baseUrl: String) -> Unit,
  ): List<ILoggingEvent> {
    val proxy = createTestProxy()
    val server =
      embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        val app = this
        with(ProxyHttpConfig) { app.configureKtorServer(proxy, isTestMode = true) }
        routing {
          failingRoutes.forEach { (route, failure) -> get(route) { throw failure() } }
        }
      }

    return try {
      val port = server.startAndAwaitReady()
      captureLogs<ProxyHttpConfig>(LogbackLevel.DEBUG) {
        val client = newHttpClient()
        client.use { client ->
          block(client, "http://localhost:$port")
        }
      }
    } finally {
      server.stop(0, 0)
    }
  }
}
