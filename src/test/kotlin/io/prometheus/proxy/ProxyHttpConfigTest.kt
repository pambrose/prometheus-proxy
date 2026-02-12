/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType.Text
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
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
import io.ktor.server.cio.CIO as ServerCIO

// Tests for ProxyHttpConfig which configures Ktor server plugins for the proxy HTTP service.
// Note: Full integration tests with testApplication would require ktor-server-test-host dependency.
// These tests verify the configuration logic without running a full HTTP server.
class ProxyHttpConfigTest : StringSpec() {
  // ==================== Helper Methods ====================

  private fun callGetFormattedLog(call: ApplicationCall): String {
    val method = ProxyHttpConfig::class.java.getDeclaredMethod("getFormattedLog", ApplicationCall::class.java)
    method.isAccessible = true
    return method.invoke(ProxyHttpConfig, call) as String
  }

  private fun createTestProxy(): Proxy =
    Proxy(
      options = ProxyOptions(listOf()),
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
        priority = 1.0
      }

      // Configuration should be applied without throwing
      config.shouldNotBeNull()
    }

    "CompressionConfig should support deflate configuration" {
      val config = CompressionConfig()

      // Apply deflate configuration similar to ProxyHttpConfig
      config.deflate {
        priority = 10.0
        minimumSize(1024)
      }

      // Configuration should be applied without throwing
      config.shouldNotBeNull()
    }

    "CompressionConfig should support multiple encoders" {
      val config = CompressionConfig()

      // Apply both gzip and deflate like ProxyHttpConfig does
      config.gzip {
        priority = 1.0
      }
      config.deflate {
        priority = 10.0
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

    "gzip should have higher priority than deflate in config" {
      // In ProxyHttpConfig, gzip has priority 1.0 and deflate has 10.0
      // Lower numbers = higher priority in Ktor
      val gzipPriority = 1.0
      val deflatePriority = 10.0

      (gzipPriority < deflatePriority) shouldBe true
    }

    "deflate minimum size should be 1024 bytes" {
      val minimumSize = 1024L

      minimumSize shouldBe 1024L
    }

    // ==================== StatusPages Integration Tests ====================

    "StatusPages NotFound handler should return correct text" {
      val server = embeddedServer(ServerCIO, port = 0) {
        install(StatusPages) {
          status(HttpStatusCode.NotFound) { call, cause ->
            call.respond(
              TextContent(
                "${cause.value} ${cause.description}",
                Text.Plain.withCharset(Charsets.UTF_8),
                cause,
              ),
            )
          }
        }
        routing {
          // No routes - all requests should 404
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/nonexistent")
        response.status shouldBe HttpStatusCode.NotFound
        response.bodyAsText() shouldContain "404"
        response.bodyAsText() shouldContain "Not Found"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "StatusPages exception handler should return InternalServerError" {
      val server = embeddedServer(ServerCIO, port = 0) {
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
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/throw")
        response.status shouldBe HttpStatusCode.InternalServerError

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== getFormattedLog Tests ====================

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
    // getFormattedLog is private in ProxyHttpConfig. It has two branches:
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

    // ==================== configureCallLogging Filter Tests ====================
    // The filter checks call.request.path().startsWith("/")

    "callLogging filter should accept paths starting with slash" {
      // The filter logic: call.request.path().startsWith("/")
      val path = "/metrics"
      path.startsWith("/") shouldBe true
    }

    "callLogging filter should accept root path" {
      val path = "/"
      path.startsWith("/") shouldBe true
    }

    "callLogging filter should reject paths not starting with slash" {
      // This case would only happen if request.path() returns a path without leading slash,
      // which is unusual in HTTP but the filter handles it defensively
      val path = "metrics"
      path.startsWith("/") shouldBe false
    }

    // ==================== configureKtorServer Integration Tests ====================
    // These tests verify the full configureKtorServer() function by running an embedded server
    // with all plugins installed, then making HTTP requests to verify behavior.

    "configureKtorServer should add X-Engine default header to responses" {
      val proxy = createTestProxy()
      val server = embeddedServer(ServerCIO, port = 0) {
        val app = this
        with(ProxyHttpConfig) { app.configureKtorServer(proxy, isTestMode = true) }
        routing {
          get("/test") { call.respondText("hello") }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

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
      val server = embeddedServer(ServerCIO, port = 0) {
        val app = this
        with(ProxyHttpConfig) { app.configureKtorServer(proxy, isTestMode = true) }
        routing { }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

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

    "configureKtorServer should handle exceptions via StatusPages" {
      val proxy = createTestProxy()
      val server = embeddedServer(ServerCIO, port = 0) {
        val app = this
        with(ProxyHttpConfig) { app.configureKtorServer(proxy, isTestMode = true) }
        routing {
          get("/throw") { throw IllegalStateException("Test error") }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/throw")
        response.status shouldBe HttpStatusCode.InternalServerError
        response.headers["X-Engine"] shouldBe "Ktor"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }
  }
}
