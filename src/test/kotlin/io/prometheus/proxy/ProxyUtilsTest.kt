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

import com.pambrose.common.dsl.KtorDsl.newHttpClient
import com.pambrose.common.util.zip
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.prometheus.common.startAndAwaitReady
import io.prometheus.proxy.ProxyUtils.respondWith
import io.prometheus.proxy.ProxyUtils.unzip
import io.ktor.server.cio.CIO as ServerCIO

class ProxyUtilsTest : StringSpec() {
  init {
    "invalidAgentContextResponse should return correct status and message" {
      val mockProxy = mockk<Proxy>(relaxed = true)

      val result = ProxyUtils.invalidAgentContextResponse("test-path", mockProxy)

      result.statusCode shouldBe HttpStatusCode.NotFound
      result.updateMsgs shouldBe listOf("invalid_agent_context")
      verify { mockProxy.logActivity("Invalid AgentContext for /test-path") }
    }

    "invalidPathResponse should return correct status and message" {
      val mockProxy = mockk<Proxy>(relaxed = true)

      val result = ProxyUtils.invalidPathResponse("invalid-path", mockProxy)

      result.statusCode shouldBe HttpStatusCode.NotFound
      result.updateMsgs shouldBe listOf("invalid_path")
      verify { mockProxy.logActivity("Invalid path request /invalid-path") }
    }

    "emptyPathResponse should return correct status and message" {
      val mockProxy = mockk<Proxy>(relaxed = true)

      val result = ProxyUtils.emptyPathResponse(mockProxy)

      result.statusCode shouldBe HttpStatusCode.NotFound
      result.updateMsgs shouldBe listOf("missing_path")
      verify { mockProxy.logActivity(any()) }
    }

    "proxyNotRunningResponse should return ServiceUnavailable status" {
      val result = ProxyUtils.proxyNotRunningResponse()

      result.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      result.updateMsgs shouldBe listOf("proxy_stopped")
    }

    "incrementScrapeRequestCount should call metrics for non-empty type" {
      val mockProxy = mockk<Proxy>(relaxed = true)

      ProxyUtils.incrementScrapeRequestCount(mockProxy, "test-type")

      verify { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) }
    }

    "incrementScrapeRequestCount should not call metrics for empty type" {
      val mockProxy = mockk<Proxy>(relaxed = true)

      ProxyUtils.incrementScrapeRequestCount(mockProxy, "")

      verify(exactly = 0) { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) }
    }

    "ResponseResults should have correct default values" {
      val responseResults = ResponseResults()

      responseResults.statusCode shouldBe HttpStatusCode.OK
      responseResults.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
      responseResults.contentText shouldBe ""
    }

    "invalidPathResponse should handle various path formats" {
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

    "emptyPathResponse should use correct status" {
      val mockProxy = mockk<Proxy>(relaxed = true)

      val result = ProxyUtils.emptyPathResponse(mockProxy)

      result.statusCode shouldBe HttpStatusCode.NotFound
      verify { mockProxy.logActivity(any()) }
    }

    // DESIGN-1: Verify that helper functions return independent ResponseResults instances
    // rather than mutating a shared object (the old output-parameter anti-pattern).
    "helper functions should return independent ResponseResults instances" {
      val mockProxy = mockk<Proxy>(relaxed = true)

      val result1 = ProxyUtils.invalidPathResponse("path1", mockProxy)
      val result2 = ProxyUtils.invalidAgentContextResponse("path2", mockProxy)
      val result3 = ProxyUtils.emptyPathResponse(mockProxy)
      val result4 = ProxyUtils.proxyNotRunningResponse()

      // Each call returns its own independent instance
      result1.updateMsgs shouldBe listOf("invalid_path")
      result2.updateMsgs shouldBe listOf("invalid_agent_context")
      result3.updateMsgs shouldBe listOf("missing_path")
      result4.updateMsgs shouldBe listOf("proxy_stopped")

      // Copying one result does not affect others
      val modified = result1.copy(contentText = "modified")
      modified.contentText shouldBe "modified"
      result2.contentText shouldBe ""
      result3.contentText shouldBe ""
      result4.contentText shouldBe ""
    }

    // ==================== Bug #14: respondWith no longer sets status redundantly ====================

    "respondWith should set CacheControl header and respond with text" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-respond") {
            call.respondWith("test content")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/test-respond")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "test content"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "respondWith should use custom content type" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-json") {
            call.respondWith(
              """{"key":"value"}""",
              ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/test-json")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "key"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "respondWith should use custom status code" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-error") {
            call.respondWith("error", status = HttpStatusCode.ServiceUnavailable)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/test-error")
        response.status shouldBe HttpStatusCode.ServiceUnavailable
        response.bodyAsText() shouldBe "error"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "respondWith should set CacheControl header and correct status without redundant status call" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/test-bug14") {
            call.respondWith("content", status = HttpStatusCode.NotFound)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/test-bug14")
        response.status shouldBe HttpStatusCode.NotFound
        response.bodyAsText() shouldBe "content"
        response.headers["Cache-Control"] shouldBe "must-revalidate,no-store"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Item 11: unzip returns decoded text + byte count ====================

    "unzip should round-trip content and report its UTF-8 byte count" {
      val original = "metric_value 42\n# EOF"
      val decoded = original.zip().unzip(1_000_000)

      decoded.text shouldBe original
      decoded.byteCount shouldBe original.toByteArray().size.toLong()
    }

    "unzip should report the byte count for multi-byte UTF-8 content" {
      // "世界" is 2 chars but 6 UTF-8 bytes; byteCount must reflect bytes, not char length.
      val original = "世界"
      val decoded = original.zip().unzip(1_000_000)

      decoded.text shouldBe original
      decoded.byteCount shouldBe 6L
    }

    "unzip should return empty text and zero byte count for empty input" {
      val decoded = ByteArray(0).unzip(1_000_000)

      decoded.text shouldBe ""
      decoded.byteCount shouldBe 0L
    }

    "unzip should throw ZipBombException when the decoded size exceeds the limit" {
      val zipped = "a".repeat(1000).zip()
      shouldThrow<ProxyUtils.ZipBombException> { zipped.unzip(100) }
    }
  }
}
