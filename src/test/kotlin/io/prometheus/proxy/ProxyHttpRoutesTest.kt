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

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.mockk
import io.prometheus.proxy.ProxyHttpRoutes.ensureLeadingSlash
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.cio.CIO as ServerCIO

// Bug #12: The service discovery endpoint was registered using the raw sdPath config value,
// which defaults to "discovery" (no leading slash). The fix normalizes the path with
// ensureLeadingSlash() to ensure consistent route registration regardless of config format.
class ProxyHttpRoutesTest {
  @Test
  fun `ensureLeadingSlash should add slash when missing`() {
    "discovery".ensureLeadingSlash() shouldBe "/discovery"
  }

  @Test
  fun `ensureLeadingSlash should not double slash when already present`() {
    "/discovery".ensureLeadingSlash() shouldBe "/discovery"
  }

  @Test
  fun `ensureLeadingSlash should handle nested paths without slash`() {
    "api/discovery".ensureLeadingSlash() shouldBe "/api/discovery"
  }

  @Test
  fun `ensureLeadingSlash should handle nested paths with slash`() {
    "/api/discovery".ensureLeadingSlash() shouldBe "/api/discovery"
  }

  // Verifies that a normalized path (with leading slash) correctly registers
  // and matches incoming requests via Ktor routing.
  @Test
  fun `normalized path should match incoming requests`(): Unit =
    runBlocking {
      // Simulate the fix: config value "discovery" -> ensureLeadingSlash -> "/discovery"
      val configPath = "discovery"
      val normalizedPath = configPath.ensureLeadingSlash()

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get(normalizedPath) {
            call.respondText("found")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/discovery")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "found"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

  // Verifies that a config value already containing a leading slash
  // is handled correctly without double-slashing.
  @Test
  fun `config path with leading slash should still match after normalization`(): Unit =
    runBlocking {
      val configPath = "/discovery"
      val normalizedPath = configPath.ensureLeadingSlash()
      normalizedPath shouldBe "/discovery"

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get(normalizedPath) {
            call.respondText("found")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/discovery")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "found"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

  // ==================== ScrapeRequestResponse Tests ====================

  @Test
  fun `ScrapeRequestResponse should store all properties`() {
    val response = ScrapeRequestResponse(
      statusCode = HttpStatusCode.OK,
      updateMsg = "success",
      contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
      contentText = """{"metric":"value"}""",
      failureReason = "",
      url = "http://localhost:8080/metrics",
      fetchDuration = 150.milliseconds,
    )

    response.statusCode shouldBe HttpStatusCode.OK
    response.updateMsg shouldBe "success"
    response.contentText shouldBe """{"metric":"value"}"""
    response.failureReason shouldBe ""
    response.url shouldBe "http://localhost:8080/metrics"
    response.fetchDuration shouldBe 150.milliseconds
  }

  @Test
  fun `ScrapeRequestResponse should default to plain text content type`() {
    val response = ScrapeRequestResponse(
      statusCode = HttpStatusCode.ServiceUnavailable,
      updateMsg = "timed_out",
      fetchDuration = 5000.milliseconds,
    )

    response.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    response.contentText shouldBe ""
    response.failureReason shouldBe ""
    response.url shouldBe ""
  }

  @Test
  fun `ScrapeRequestResponse should handle error status codes`() {
    val response = ScrapeRequestResponse(
      statusCode = HttpStatusCode.NotFound,
      updateMsg = "path_not_found",
      failureReason = "Agent not found for path",
      url = "http://localhost/metrics",
      fetchDuration = 50.milliseconds,
    )

    response.statusCode shouldBe HttpStatusCode.NotFound
    response.updateMsg shouldBe "path_not_found"
    response.failureReason shouldBe "Agent not found for path"
  }

  // ==================== ResponseResults Tests ====================

  @Test
  fun `ResponseResults should have correct defaults`() {
    val results = ResponseResults()

    results.statusCode shouldBe HttpStatusCode.OK
    results.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    results.contentText shouldBe ""
    results.updateMsg shouldBe ""
  }

  @Test
  fun `ResponseResults should accept custom values`() {
    val results = ResponseResults(
      statusCode = HttpStatusCode.ServiceUnavailable,
      contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
      contentText = """{"error":"proxy stopped"}""",
      updateMsg = "proxy_stopped",
    )

    results.statusCode shouldBe HttpStatusCode.ServiceUnavailable
    results.contentText shouldBe """{"error":"proxy stopped"}"""
    results.updateMsg shouldBe "proxy_stopped"
  }

  @Test
  fun `ResponseResults copy should produce modified instance`() {
    val results = ResponseResults()

    val modified = results.copy(
      statusCode = HttpStatusCode.NotFound,
      contentText = "modified content",
      updateMsg = "modified_msg",
    )

    modified.statusCode shouldBe HttpStatusCode.NotFound
    modified.contentText shouldBe "modified content"
    modified.updateMsg shouldBe "modified_msg"
  }

  // ==================== ClosedSendChannelException Handling Tests ====================

  @Test
  fun `writeScrapeRequest on invalidated context should throw ClosedSendChannelException`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")
      val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.invalidate()

      // Verify the exact exception type that submitScrapeRequest needs to catch
      val exception = assertThrows<Exception> {
        context.writeScrapeRequest(wrapper)
      }
      exception.shouldBeInstanceOf<ClosedSendChannelException>()
    }

  // Verifies that writeScrapeRequest on an invalidated AgentContext produces a
  // ClosedSendChannelException, and that backlog stays at zero (Bug #2 fix ensures
  // the counter is decremented on failure). This is the exact exception type that
  // submitScrapeRequest catches (Bug #3 fix) to return 503 ServiceUnavailable
  // instead of propagating as an HTTP 500.
  @Test
  fun `writeScrapeRequest after concurrent invalidation should not corrupt backlog counter`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")
      val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)

      // Write some items, then invalidate, then try to write again
      context.writeScrapeRequest(mockk(relaxed = true))
      context.writeScrapeRequest(mockk(relaxed = true))
      context.scrapeRequestBacklogSize shouldBe 2

      context.invalidate()
      context.scrapeRequestBacklogSize shouldBe 0

      // Attempting to write after invalidation should throw ClosedSendChannelException
      // and the backlog counter should remain at 0 (not go to 1 then fail)
      val exception = assertThrows<Exception> {
        context.writeScrapeRequest(wrapper)
      }
      exception.shouldBeInstanceOf<ClosedSendChannelException>()
      context.scrapeRequestBacklogSize shouldBe 0
    }
}
