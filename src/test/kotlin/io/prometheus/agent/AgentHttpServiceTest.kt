/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction", "LargeClass")

package io.prometheus.agent

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.fail
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.prometheus.Agent
import io.prometheus.common.ConfigVals
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.scrapeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.measureTime
import io.ktor.server.cio.CIO as ServerCIO

class AgentHttpServiceTest : StringSpec() {
  private fun createMockAgent(): Agent {
    val mockOptions = mockk<AgentOptions>(relaxed = true)
    every { mockOptions.maxCacheSize } returns 100
    every { mockOptions.maxCacheAgeMins } returns 30
    every { mockOptions.maxCacheIdleMins } returns 10
    every { mockOptions.cacheCleanupIntervalMins } returns 5
    every { mockOptions.scrapeTimeoutSecs } returns 10
    every { mockOptions.minGzipSizeBytes } returns 512
    every { mockOptions.maxContentLengthMBytes } returns 10
    every { mockOptions.debugEnabled } returns false

    val mockPathManager = mockk<AgentPathManager>(relaxed = true)

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.options } returns mockOptions
    every { mockAgent.pathManager } returns mockPathManager
    every { mockAgent.isMetricsEnabled } returns false
    return mockAgent
  }

  private fun createMockAgentWithPaths(): Agent {
    val mockOptions = mockk<AgentOptions>(relaxed = true)
    every { mockOptions.maxCacheSize } returns 100
    every { mockOptions.maxCacheAgeMins } returns 30
    every { mockOptions.maxCacheIdleMins } returns 10
    every { mockOptions.cacheCleanupIntervalMins } returns 5
    every { mockOptions.scrapeTimeoutSecs } returns 10
    every { mockOptions.scrapeMaxRetries } returns 0
    every { mockOptions.minGzipSizeBytes } returns 1_000_000
    every { mockOptions.maxContentLengthMBytes } returns 10
    every { mockOptions.debugEnabled } returns false
    every { mockOptions.httpClientTimeoutSecs } returns 90
    every { mockOptions.trustAllX509Certificates } returns false

    val config = ConfigFactory.parseString(
      """
      agent {
        pathConfigs = []
        internal {
          cioTimeoutSecs = 90
        }
      }
      proxy {}
      """.trimIndent(),
    )
    val configVals = ConfigVals(config)

    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.options } returns mockOptions
    every { mockAgent.configVals } returns configVals
    every { mockAgent.isMetricsEnabled } returns false
    every { mockAgent.isTestMode } returns true
    every { mockAgent.grpcService } returns mockGrpcService

    coEvery { mockGrpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }

    val pathManager = AgentPathManager(mockAgent)
    every { mockAgent.pathManager } returns pathManager

    return mockAgent
  }

  private fun createMockAgentWithRetries(maxRetries: Int): Agent {
    val mockOptions = mockk<AgentOptions>(relaxed = true)
    every { mockOptions.maxCacheSize } returns 100
    every { mockOptions.maxCacheAgeMins } returns 30
    every { mockOptions.maxCacheIdleMins } returns 10
    every { mockOptions.cacheCleanupIntervalMins } returns 5
    every { mockOptions.scrapeTimeoutSecs } returns 10
    every { mockOptions.scrapeMaxRetries } returns maxRetries
    every { mockOptions.minGzipSizeBytes } returns 1_000_000
    every { mockOptions.maxContentLengthMBytes } returns 10
    every { mockOptions.debugEnabled } returns false
    every { mockOptions.httpClientTimeoutSecs } returns 90
    every { mockOptions.trustAllX509Certificates } returns false

    val config = ConfigFactory.parseString(
      """
      agent {
        pathConfigs = []
        internal {
          cioTimeoutSecs = 90
        }
      }
      proxy {}
      """.trimIndent(),
    )
    val configVals = ConfigVals(config)

    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.options } returns mockOptions
    every { mockAgent.configVals } returns configVals
    every { mockAgent.isMetricsEnabled } returns false
    every { mockAgent.isTestMode } returns true
    every { mockAgent.grpcService } returns mockGrpcService

    coEvery { mockGrpcService.registerPathOnProxy(any(), any()) } returns registerPathResponse {
      valid = true
      pathId = 1L
    }

    val pathManager = AgentPathManager(mockAgent)
    every { mockAgent.pathManager } returns pathManager

    return mockAgent
  }

  private suspend fun startServerAndGetPort(server: EmbeddedServer<*, *>): Int {
    server.start(wait = false)
    val port = server.engine.resolvedConnectors().first().port
    // Allow the CIO server engine to fully start accepting connections
    delay(100)
    return port
  }

  init {
    // ==================== Invalid Path Tests ====================

    "fetchScrapeUrl should return error results for invalid path" {
      val mockAgent = createMockAgent()
      // Return null for unknown path
      every { mockAgent.pathManager[any()] } returns null

      val service = AgentHttpService(mockAgent)
      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 1L
        path = "/nonexistent"
        accept = ""
        debugEnabled = false
        encodedQueryParams = ""
        authHeader = ""
      }

      val results = service.fetchScrapeUrl(request)

      results.srAgentId shouldBe "agent-1"
      results.srScrapeId shouldBe 1L
      results.srValidResponse.shouldBeFalse()
    }

    "fetchScrapeUrl should set debug info for invalid path when debug enabled" {
      val mockAgent = createMockAgent()
      every { mockAgent.pathManager[any()] } returns null

      val service = AgentHttpService(mockAgent)
      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 2L
        path = "/bad/path"
        accept = ""
        debugEnabled = true
        encodedQueryParams = ""
        authHeader = ""
      }

      val results = service.fetchScrapeUrl(request)

      results.srUrl shouldBe "None"
      results.srFailureReason shouldContain "Invalid path"
    }

    "fetchScrapeUrl should not set debug info for invalid path when debug disabled" {
      val mockAgent = createMockAgent()
      every { mockAgent.pathManager[any()] } returns null

      val service = AgentHttpService(mockAgent)
      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 3L
        path = "/bad/path"
        accept = ""
        debugEnabled = false
        encodedQueryParams = ""
        authHeader = ""
      }

      val results = service.fetchScrapeUrl(request)

      results.srUrl shouldBe ""
      results.srFailureReason shouldBe ""
    }

    // ==================== Bug #9: Invalid Path Status Code Test ====================

    "fetchScrapeUrl should return 404 Not Found for invalid path" {
      val mockAgent = createMockAgent()
      every { mockAgent.pathManager[any()] } returns null

      val service = AgentHttpService(mockAgent)
      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 90L
        path = "/nonexistent"
        accept = ""
        debugEnabled = false
        encodedQueryParams = ""
        authHeader = ""
      }

      val results = service.fetchScrapeUrl(request)

      results.srStatusCode shouldBe 404
      results.srValidResponse.shouldBeFalse()
    }

    // ==================== Close Tests ====================

    "close should close httpClientCache" {
      val mockAgent = createMockAgent()
      val service = AgentHttpService(mockAgent)

      // Should not throw
      service.close()
    }

    // ==================== HttpClientCache Tests ====================

    "httpClientCache should be initialized from agent options" {
      val mockAgent = createMockAgent()
      val service = AgentHttpService(mockAgent)

      service.httpClientCache.shouldNotBeNull()
      service.close()
    }

    // ==================== Valid Path Fetching Tests ====================

    "fetchScrapeUrl should fetch content from valid path" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            call.respondText("test_metric{label=\"value\"} 42\n")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 10L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srAgentId shouldBe "agent-1"
        results.srScrapeId shouldBe 10L
        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        results.srContentAsText shouldContain "test_metric"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl should handle connection refused" {
      val mockAgent = createMockAgentWithPaths()
      val service = AgentHttpService(mockAgent)

      mockAgent.pathManager.registerPath("metrics", "http://localhost:1/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 20L
        path = "metrics"
        accept = ""
        debugEnabled = false
        encodedQueryParams = ""
        authHeader = ""
      }

      val results = service.fetchScrapeUrl(request)

      results.srAgentId shouldBe "agent-1"
      results.srStatusCode shouldBeGreaterThan 399
      results.srValidResponse.shouldBeFalse()

      service.close()
    }

    "fetchScrapeUrl should handle 404 response" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          // No route for /metrics, Ktor will return 404
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 30L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 404

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl should set debug info when debug enabled and request fails" {
      val mockAgent = createMockAgentWithPaths()
      val service = AgentHttpService(mockAgent)

      // Use a URL that will fail - debug info should still be set
      mockAgent.pathManager.registerPath("debug-metrics", "http://localhost:1/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 40L
        path = "debug-metrics"
        accept = ""
        debugEnabled = true
        encodedQueryParams = ""
        authHeader = ""
      }

      val results = service.fetchScrapeUrl(request)

      // When debug is enabled, URL and failure reason should be set even on failure
      results.srUrl shouldContain "http://localhost:1/metrics"
      results.srFailureReason.isNotEmpty().shouldBeTrue()

      service.close()
    }

    "fetchScrapeUrl should include query params in URL" {
      var receivedUrl = ""
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            receivedUrl = call.request.queryParameters.entries().joinToString("&") { "${it.key}=${it.value.first()}" }
            call.respondText("ok")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 50L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = "foo%3Dbar"
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srValidResponse.shouldBeTrue()
        receivedUrl shouldContain "foo"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl should append query params to existing query" {
      var existingParam: String? = null
      var fooParam: String? = null
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            existingParam = call.request.queryParameters["existing"]
            fooParam = call.request.queryParameters["foo"]
            call.respondText("ok")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics?existing=1")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 51L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = "foo%3Dbar"
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srValidResponse.shouldBeTrue()
        existingParam shouldBe "1"
        fooParam shouldBe "bar"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Gzip Compression Tests ====================

    "fetchScrapeUrl should gzip content larger than minGzipSizeBytes" {
      val largeContent = "a".repeat(2000)
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(largeContent)
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        // Set low gzip threshold so content gets zipped
        val options = mockAgent.options
        every { options.minGzipSizeBytes } returns 100
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 60L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srValidResponse.shouldBeTrue()
        results.srZipped.shouldBeTrue()
        results.srContentAsZipped.isNotEmpty().shouldBeTrue()

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl should not gzip content smaller than minGzipSizeBytes" {
      val smallContent = "small"
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(smallContent)
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        // minGzipSizeBytes is already 1_000_000 from createMockAgentWithPaths
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 61L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srValidResponse.shouldBeTrue()
        results.srZipped.shouldBeFalse()
        results.srContentAsText shouldBe smallContent

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Header Forwarding Tests ====================

    "fetchScrapeUrl should forward accept header to target" {
      var receivedAccept = ""
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            receivedAccept = call.request.header("Accept").orEmpty()
            call.respondText("ok")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 62L
          path = "metrics"
          accept = "application/openmetrics-text"
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srValidResponse.shouldBeTrue()
        receivedAccept shouldBe "application/openmetrics-text"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl should forward authorization header to target" {
      var receivedAuth = ""
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            receivedAuth = call.request.header("Authorization").orEmpty()
            call.respondText("ok")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 63L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = "Bearer test-token-123"
        }

        val results = service.fetchScrapeUrl(request)

        results.srValidResponse.shouldBeTrue()
        receivedAuth shouldBe "Bearer test-token-123"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Timeout Tests ====================

    "fetchScrapeUrl should handle timeout gracefully" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            delay(5000) // Delay longer than timeout
            call.respondText("too late")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        // Set a very short timeout
        val options = mockAgent.options
        every { options.scrapeTimeoutSecs } returns 1
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 64L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        // Should have a failure status code (timeout)
        results.srValidResponse.shouldBeFalse()
        results.srStatusCode shouldBeGreaterThan 399

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Counter Message Tests ====================

    "fetchScrapeUrl should set success counter message on successful fetch" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            call.respondText("metric_value 1.0\n")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 65L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srValidResponse.shouldBeTrue()
        results.scrapeCounterMsg shouldBe "success"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Retry Policy Tests ====================

    "retry policy should not retry 400 Bad Request" {
      var requestCount = 0
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            requestCount++
            call.response.status(HttpStatusCode.BadRequest)
            call.respondText("bad request")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 80L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount shouldBe 1
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should not retry 401 Unauthorized" {
      var requestCount = 0
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            requestCount++
            call.response.status(HttpStatusCode.Unauthorized)
            call.respondText("unauthorized")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 81L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount shouldBe 1
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should not retry 403 Forbidden" {
      var requestCount = 0
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            requestCount++
            call.response.status(HttpStatusCode.Forbidden)
            call.respondText("forbidden")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 82L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount shouldBe 1
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should retry 500 Internal Server Error" {
      var requestCount = 0
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            requestCount++
            call.response.status(HttpStatusCode.InternalServerError)
            call.respondText("server error")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val mockAgent = createMockAgentWithRetries(2)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 83L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        // 1 initial + up to 2 retries = up to 3 total
        requestCount shouldBeGreaterThan 1
        requestCount shouldBeLessThanOrEqual 3
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should retry 503 Service Unavailable" {
      var requestCount = 0
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            requestCount++
            call.response.status(HttpStatusCode.ServiceUnavailable)
            call.respondText("unavailable")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val mockAgent = createMockAgentWithRetries(2)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 84L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount shouldBeGreaterThan 1
        requestCount shouldBeLessThanOrEqual 3
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should not retry 404 Not Found" {
      var requestCount = 0
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            requestCount++
            call.response.status(HttpStatusCode.NotFound)
            call.respondText("not found")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 85L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount shouldBe 1
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Bug #6: Byte Count vs Char Count Tests ====================

    "content byte count should differ from char count for multi-byte UTF-8" {
      // Demonstrates the bug: content.length (char count) can undercount for multi-byte chars
      val content = "\u4e16\u754c" // "世界" - 2 chars but 6 bytes in UTF-8
      content.length shouldBe 2
      content.encodeToByteArray().size shouldBe 6
    }

    "content byte count should equal char count for ASCII" {
      val content = "hello_world_metric 42.0"
      content.length shouldBe content.encodeToByteArray().size
    }

    "Bug #2: gzip threshold should use byte count not char count for multi-byte content" {
      // Create content with multi-byte UTF-8 chars where charCount < threshold < byteCount
      // Each CJK char is 3 bytes in UTF-8, so 200 chars = 600 bytes
      val multiByte = "\u4e16".repeat(200) // 200 chars, 600 bytes
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(multiByte)
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithPaths()
        // Set threshold between char count (200) and byte count (600)
        val options = mockAgent.options
        every { options.minGzipSizeBytes } returns 300
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 66L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        // With the fix, byte count (600) > threshold (300) -> zipped
        // Without the fix, char count (200) < threshold (300) -> not zipped
        results.srZipped.shouldBeTrue()

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Cancellation Handling Tests (Bug #3) ====================

    "fetchScrapeUrl should rethrow generic CancellationException" {
      val mockAgent = createMockAgentWithPaths()
      val service = AgentHttpService(mockAgent)
      mockAgent.pathManager.registerPath("metrics", "http://localhost:8080/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 70L
        path = "metrics"
      }

      val spiedService = spyk(service)
      // Mock the internal fetchContent call to throw CancellationException
      coEvery {
        spiedService.fetchContent(any<String>(), any())
      } coAnswers {
        throw CancellationException("System shutdown")
      }

      io.kotest.assertions.throwables.shouldThrow<CancellationException> {
        spiedService.fetchScrapeUrl(request)
      }
    }

    // ==================== Bug #3: Retry timeout bounded by scrapeTimeoutSecs ====================

    "Bug #3: total fetch time should be bounded by scrapeTimeoutSecs despite retries" {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            call.response.status(HttpStatusCode.InternalServerError)
            call.respondText("server error")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)

        val mockAgent = createMockAgentWithRetries(10)
        // Set a short scrape timeout of 3 seconds
        every { mockAgent.options.scrapeTimeoutSecs } returns 3
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 86L
          path = "metrics"
        }

        val elapsed = measureTime {
          service.fetchScrapeUrl(request)
        }

        // Total time should be bounded by scrapeTimeoutSecs (3s) + some margin
        // Without the fix, 10 retries with exponential delay could take much longer
        elapsed.inWholeSeconds shouldBeLessThan 6L

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl should NOT rethrow HttpRequestTimeoutException" {
      val mockAgent = createMockAgentWithPaths()
      val service = AgentHttpService(mockAgent)
      mockAgent.pathManager.registerPath("metrics", "http://localhost:8080/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 71L
        path = "metrics"
      }

      val spiedService = spyk(service)
      // HttpRequestTimeoutException is a CancellationException, but should be caught and converted to 408
      coEvery {
        spiedService.fetchContent(any<String>(), any())
      } coAnswers {
        throw io.ktor.client.plugins.HttpRequestTimeoutException("url", 1000L)
      }

      // If it throws, it's a bug (swallowing timeout or rethrowing shutdown)
      // but let's see what it actually throws if it does
      try {
        val results = spiedService.fetchScrapeUrl(request)
        results.srStatusCode shouldBe 408
        results.srValidResponse shouldBe false
      } catch (e: Throwable) {
        // If it throws HttpRequestTimeoutException (or a CancellationException wrapping it),
        // then our isTimeout check failed.
        val isTimeout = e is io.ktor.client.plugins.HttpRequestTimeoutException ||
          e.cause is io.ktor.client.plugins.HttpRequestTimeoutException ||
          e.javaClass.name.endsWith("HttpRequestTimeoutException") ||
          e.cause?.javaClass?.name?.endsWith("HttpRequestTimeoutException") == true

        if (isTimeout) {
          fail("fetchScrapeUrl rethrew HttpRequestTimeoutException (or wrapper) but it should have been caught: $e")
        } else {
          throw e // Rethrow real cancellations
        }
      }
    }
  }
}
