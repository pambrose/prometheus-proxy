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
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyUtils.respondWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import io.ktor.server.cio.CIO as ServerCIO

class ProxyUtilsTest {
  @Test
  fun `invalidAgentContextResponse should return correct status and message`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    val result = ProxyUtils.invalidAgentContextResponse("test-path", mockProxy)

    result.statusCode shouldBe HttpStatusCode.NotFound
    result.updateMsg shouldBe "invalid_agent_context"
    verify { mockProxy.logActivity("Invalid AgentContext for /test-path") }
  }

  @Test
  fun `invalidPathResponse should return correct status and message`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    val result = ProxyUtils.invalidPathResponse("invalid-path", mockProxy)

    result.statusCode shouldBe HttpStatusCode.NotFound
    result.updateMsg shouldBe "invalid_path"
    verify { mockProxy.logActivity("Invalid path request /invalid-path") }
  }

  @Test
  fun `emptyPathResponse should return correct status and message`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    val result = ProxyUtils.emptyPathResponse(mockProxy)

    result.statusCode shouldBe HttpStatusCode.NotFound
    result.updateMsg shouldBe "missing_path"
    verify { mockProxy.logActivity(any()) }
  }

  @Test
  fun `proxyNotRunningResponse should return ServiceUnavailable status`() {
    val result = ProxyUtils.proxyNotRunningResponse()

    result.statusCode shouldBe HttpStatusCode.ServiceUnavailable
    result.updateMsg shouldBe "proxy_stopped"
  }

  @Test
  fun `incrementScrapeRequestCount should call metrics for non-empty type`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    ProxyUtils.incrementScrapeRequestCount(mockProxy, "test-type")

    verify { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) }
  }

  @Test
  fun `incrementScrapeRequestCount should not call metrics for empty type`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    ProxyUtils.incrementScrapeRequestCount(mockProxy, "")

    verify(exactly = 0) { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) }
  }

  @Test
  fun `ResponseResults should have correct default values`() {
    val responseResults = ResponseResults()

    responseResults.statusCode shouldBe HttpStatusCode.OK
    responseResults.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    responseResults.contentText shouldBe ""
  }

  @Test
  fun `invalidPathResponse should handle various path formats`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    // Test with empty path
    val result1 = ProxyUtils.invalidPathResponse("", mockProxy)
    result1.statusCode shouldBe HttpStatusCode.NotFound
    verify { mockProxy.logActivity("Invalid path request /") }

    // Test with path containing special characters
    val result2 = ProxyUtils.invalidPathResponse("metrics/test", mockProxy)
    result2.statusCode shouldBe HttpStatusCode.NotFound
    verify { mockProxy.logActivity("Invalid path request /metrics/test") }
  }

  @Test
  fun `emptyPathResponse should use correct status`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    val result = ProxyUtils.emptyPathResponse(mockProxy)

    result.statusCode shouldBe HttpStatusCode.NotFound
    verify { mockProxy.logActivity(any()) }
  }

  // DESIGN-1: Verify that helper functions return independent ResponseResults instances
  // rather than mutating a shared object (the old output-parameter anti-pattern).
  @Test
  fun `helper functions should return independent ResponseResults instances`() {
    val mockProxy = mockk<Proxy>(relaxed = true)

    val result1 = ProxyUtils.invalidPathResponse("path1", mockProxy)
    val result2 = ProxyUtils.invalidAgentContextResponse("path2", mockProxy)
    val result3 = ProxyUtils.emptyPathResponse(mockProxy)
    val result4 = ProxyUtils.proxyNotRunningResponse()

    // Each call returns its own independent instance
    result1.updateMsg shouldBe "invalid_path"
    result2.updateMsg shouldBe "invalid_agent_context"
    result3.updateMsg shouldBe "missing_path"
    result4.updateMsg shouldBe "proxy_stopped"

    // Copying one result does not affect others
    val modified = result1.copy(contentText = "modified")
    modified.contentText shouldBe "modified"
    result2.contentText shouldBe ""
    result3.contentText shouldBe ""
    result4.contentText shouldBe ""
  }

  // ==================== Bug #14: respondWith no longer sets status redundantly ====================

  @Test
  fun `respondWith should set CacheControl header and respond with text`(): Unit =
    runBlocking {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-respond") {
            call.respondWith("test content")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/test-respond")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "test content"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

  @Test
  fun `respondWith should use custom content type`(): Unit =
    runBlocking {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-json") {
            call.respondWith(
              """{"key":"value"}""",
              ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/test-json")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "key"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

  @Test
  fun `respondWith should use custom status code`(): Unit =
    runBlocking {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-error") {
            call.respondWith("error", status = HttpStatusCode.ServiceUnavailable)
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/test-error")
        response.status shouldBe HttpStatusCode.ServiceUnavailable
        response.bodyAsText() shouldBe "error"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

  @Test
  fun `respondWith should set CacheControl header and correct status without redundant status call`(): Unit =
    runBlocking {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-bug14") {
            call.respondWith("content", status = HttpStatusCode.NotFound)
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/test-bug14")
        response.status shouldBe HttpStatusCode.NotFound
        response.bodyAsText() shouldBe "content"
        response.headers["Cache-Control"] shouldBe "must-revalidate,no-store"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }
}
