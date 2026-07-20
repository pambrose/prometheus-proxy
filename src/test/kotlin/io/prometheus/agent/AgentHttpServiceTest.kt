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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.prometheus.Agent
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.ConfigVals
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.scrapeRequest
import io.prometheus.common.startAndAwaitReady
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.proxy.ProxyUtils.unzip
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
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
        filters = []
        internal {
          cioTimeoutSecs = 90
        }
      }
      proxy { auth = [] }
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
        filters = []
        internal {
          cioTimeoutSecs = 90
        }
      }
      proxy { auth = [] }
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

  // Same shape as createMockAgentWithPaths, but with a caller-supplied `agent.filters` HOCON entry
  // so a real AgentPathManager attaches a compiled MetricFilter to a registered path's PathContext.
  private fun createMockAgentWithFilter(filterHocon: String): Agent {
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
        filters = [$filterHocon]
        internal {
          cioTimeoutSecs = 90
        }
      }
      proxy { auth = [] }
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

  // Same as createMockAgentWithFilter, but also wires agent.metrics { ... } to actually invoke its
  // lambda against a real AgentMetrics instance. A relaxed mockk<Agent> discards a lambda parameter
  // instead of invoking it, so without this wiring the filterLinesDropped/filterBytesSaved recording
  // block in buildScrapeResults's filtering branch is never exercised -- see AgentTest's "metrics
  // should invoke lambda when metrics enabled" and ProxyServiceImplTest's createMockProxy for the
  // same pattern. Clears the default registry first: AgentMetrics registers its collectors on
  // construction, and re-registering a same-named collector in one JVM throws.
  private fun createMockAgentWithFilterAndMetrics(filterHocon: String): Pair<Agent, AgentMetrics> {
    val mockAgent = createMockAgentWithFilter(filterHocon)
    every { mockAgent.launchId } returns "test-launch-id"

    CollectorRegistry.defaultRegistry.clear()
    val realMetrics = AgentMetrics(mockAgent)
    every { mockAgent.metrics(any<AgentMetrics.() -> Unit>()) } answers {
      firstArg<AgentMetrics.() -> Unit>().invoke(realMetrics)
    }

    return mockAgent to realMetrics
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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText("test_metric{label=\"value\"} 42\n")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          // No route for /metrics, Ktor will return 404
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            receivedUrl = call.request.queryParameters.entries().joinToString("&") { "${it.key}=${it.value.first()}" }
            call.respondText("ok")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 50L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = "foo=bar"
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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            existingParam = call.request.queryParameters["existing"]
            fooParam = call.request.queryParameters["foo"]
            call.respondText("ok")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics?existing=1")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 51L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = "foo=bar"
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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(largeContent)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(smallContent)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            receivedAccept = call.request.header("Accept").orEmpty()
            call.respondText("ok")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            receivedAuth = call.request.header("Authorization").orEmpty()
            call.respondText("ok")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            delay(5000.milliseconds) // Delay longer than timeout
            call.respondText("too late")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText("metric_value 1.0\n")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      val requestCount = AtomicInt(0)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            requestCount.incrementAndFetch()
            call.response.status(HttpStatusCode.BadRequest)
            call.respondText("bad request")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 80L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount.load() shouldBe 1
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should not retry 401 Unauthorized" {
      val requestCount = AtomicInt(0)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            requestCount.incrementAndFetch()
            call.response.status(HttpStatusCode.Unauthorized)
            call.respondText("unauthorized")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 81L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount.load() shouldBe 1
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should not retry 403 Forbidden" {
      val requestCount = AtomicInt(0)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            requestCount.incrementAndFetch()
            call.response.status(HttpStatusCode.Forbidden)
            call.respondText("forbidden")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 82L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount.load() shouldBe 1
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should retry 500 Internal Server Error" {
      val requestCount = AtomicInt(0)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            requestCount.incrementAndFetch()
            call.response.status(HttpStatusCode.InternalServerError)
            call.respondText("server error")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
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
        requestCount.load() shouldBeGreaterThan 1
        requestCount.load() shouldBeLessThanOrEqual 3
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should retry 503 Service Unavailable" {
      val requestCount = AtomicInt(0)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            requestCount.incrementAndFetch()
            call.response.status(HttpStatusCode.ServiceUnavailable)
            call.respondText("unavailable")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val mockAgent = createMockAgentWithRetries(2)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 84L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount.load() shouldBeGreaterThan 1
        requestCount.load() shouldBeLessThanOrEqual 3
        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "retry policy should not retry 404 Not Found" {
      val requestCount = AtomicInt(0)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            requestCount.incrementAndFetch()
            call.response.status(HttpStatusCode.NotFound)
            call.respondText("not found")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val mockAgent = createMockAgentWithRetries(3)
        val service = AgentHttpService(mockAgent)
        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 85L
          path = "metrics"
        }

        service.fetchScrapeUrl(request)

        requestCount.load() shouldBe 1
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
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(multiByte)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      mockAgent.pathManager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 70L
        path = "metrics"
      }

      val spiedService = spyk(service)
      // Mock the internal fetchContent call to throw CancellationException
      coEvery {
        spiedService.fetchContent(any<String>(), any(), any())
      } coAnswers {
        throw CancellationException("System shutdown")
      }

      io.kotest.assertions.throwables.shouldThrow<CancellationException> {
        spiedService.fetchScrapeUrl(request)
      }
    }

    // ==================== Finding 4: JVM Errors must not be swallowed into 503 results ====================

    // The scrape path catches Throwable and converts failures into a routine failure ScrapeResults.
    // A JVM Error (OutOfMemoryError, StackOverflowError, ...) means the process is in a corrupted state
    // and must propagate so the agent terminates rather than continuing to "scrape" -- mirroring the
    // Error-rethrow policy already enforced in Agent.handleConnectionFailure() and connectAgent().

    "Finding 4: fetchScrapeUrl should rethrow OutOfMemoryError instead of returning a failure result" {
      val mockAgent = createMockAgentWithPaths()
      val service = AgentHttpService(mockAgent)
      mockAgent.pathManager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 73L
        path = "metrics"
      }

      val spiedService = spyk(service)
      coEvery {
        spiedService.fetchContent(any<String>(), any(), any())
      } coAnswers {
        throw OutOfMemoryError("test OOM")
      }

      io.kotest.assertions.throwables.shouldThrow<OutOfMemoryError> {
        spiedService.fetchScrapeUrl(request)
      }

      service.close()
    }

    "Finding 4: fetchScrapeUrl should rethrow StackOverflowError instead of returning a failure result" {
      val mockAgent = createMockAgentWithPaths()
      val service = AgentHttpService(mockAgent)
      mockAgent.pathManager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 74L
        path = "metrics"
      }

      val spiedService = spyk(service)
      coEvery {
        spiedService.fetchContent(any<String>(), any(), any())
      } coAnswers {
        throw StackOverflowError("test stack overflow")
      }

      io.kotest.assertions.throwables.shouldThrow<StackOverflowError> {
        spiedService.fetchScrapeUrl(request)
      }

      service.close()
    }

    // ==================== Bug #3: Retry timeout bounded by scrapeTimeoutSecs ====================

    "Bug #3: total fetch time should be bounded by scrapeTimeoutSecs despite retries" {
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.response.status(HttpStatusCode.InternalServerError)
            call.respondText("server error")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

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
      mockAgent.pathManager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 71L
        path = "metrics"
      }

      val spiedService = spyk(service)
      // HttpRequestTimeoutException is a CancellationException, but should be caught and converted to 408
      coEvery {
        spiedService.fetchContent(any<String>(), any(), any())
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

    // ==================== Max Content Length Tests ====================

    "fetchScrapeUrl returns 413 when Content-Length header exceeds max" {
      // respondText sets a real Content-Length header. With maxContentLengthMBytes=0,
      // any positive Content-Length trips the pre-read guard before the body is fetched.
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText("ok")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        val options = mockAgent.options
        every { options.maxContentLengthMBytes } returns 0
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 200L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe HttpStatusCode.PayloadTooLarge.value
        results.srValidResponse.shouldBeFalse()
        // Debug disabled: URL and reason are blanked out.
        results.srUrl shouldBe ""
        results.srFailureReason shouldBe ""

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl populates debug fields on 413 from Content-Length when debug enabled" {
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText("ok")
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        val options = mockAgent.options
        every { options.maxContentLengthMBytes } returns 0
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 201L
          path = "metrics"
          accept = ""
          debugEnabled = true
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe HttpStatusCode.PayloadTooLarge.value
        results.srUrl shouldContain "/metrics"
        results.srFailureReason shouldContain "exceeds maximum allowed size"
        results.srFailureReason shouldContain "Content length"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl returns 413 when body bytes exceed max with chunked encoding" {
      // respondTextWriter uses chunked transfer-encoding (no Content-Length header),
      // so the first guard is skipped and the post-read body-size guard fires instead.
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondTextWriter {
              write("a".repeat(2048))
            }
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        val options = mockAgent.options
        every { options.maxContentLengthMBytes } returns 0
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 202L
          path = "metrics"
          accept = ""
          debugEnabled = true
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe HttpStatusCode.PayloadTooLarge.value
        results.srValidResponse.shouldBeFalse()
        results.srFailureReason shouldContain "Content size"
        results.srFailureReason shouldContain "exceeds maximum allowed size"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl 413 from chunked body has empty url and reason when debug disabled" {
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondTextWriter {
              write("a".repeat(2048))
            }
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        val options = mockAgent.options
        every { options.maxContentLengthMBytes } returns 0
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 203L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe HttpStatusCode.PayloadTooLarge.value
        results.srUrl shouldBe ""
        results.srFailureReason shouldBe ""

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl reads the full chunked body (no Content-Length) when under the limit" {
      // respondTextWriter streams with chunked transfer-encoding and no Content-Length, so the
      // bounded readRemaining(maxContentLength + 1) path is exercised. The body is well under the
      // 10 MB default limit, so it must be returned intact (not truncated at the read cap).
      val content = "metric_a 1.0\nmetric_b 2.0\nmetric_c 3.0\n".repeat(50)
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondTextWriter {
              write(content)
            }
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        // minGzipSizeBytes is 1_000_000, so this small body stays on the non-zipped text path.
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 204L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        results.srZipped.shouldBeFalse()
        results.srContentAsText shouldBe content

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "fetchScrapeUrl decodes a multi-byte UTF-8 body correctly on the text path" {
      // Guards the switch from bodyAsText() (response charset) to contentBytes.decodeToString()
      // (always UTF-8): a multi-byte body under the gzip threshold must round-trip byte-for-byte.
      val multiByte = "café_µ_世界 42.0\n".repeat(5) // mix of 2- and 3-byte UTF-8 chars
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(multiByte)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        // minGzipSizeBytes is 1_000_000, so this stays on the non-zipped text path.
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 205L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        results.srZipped.shouldBeFalse()
        results.srContentAsText shouldBe multiByte

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Item 29: timeout resolution precedence ====================
    // resolveTimeoutSecs honors the deprecated cioTimeoutSecs only when it was explicitly
    // overridden (non-default) while httpClientTimeoutSecs was left at the default. Otherwise
    // httpClientTimeoutSecs always wins.

    "Item 29: cioTimeoutSecs wins when overridden and httpClientTimeoutSecs is at the default" {
      AgentHttpService.resolveTimeoutSecs(cioTimeoutSecs = 30, httpClientTimeoutSecs = 90, default = 90) shouldBe 30
    }

    "Item 29: httpClientTimeoutSecs wins when it is overridden" {
      AgentHttpService.resolveTimeoutSecs(cioTimeoutSecs = 30, httpClientTimeoutSecs = 120, default = 90) shouldBe 120
    }

    "Item 29: httpClientTimeoutSecs wins when both are at the default" {
      AgentHttpService.resolveTimeoutSecs(cioTimeoutSecs = 90, httpClientTimeoutSecs = 90, default = 90) shouldBe 90
    }

    // ==================== Item 31: wrapped timeout detection ====================

    // Item 31: a CancellationException whose cause is an HttpRequestTimeoutException (Ktor
    // sometimes wraps the timeout) must be converted to a 408 via the cause-walk, not rethrown.
    "Item 31: fetchScrapeUrl converts a wrapped-timeout CancellationException to 408" {
      val mockAgent = createMockAgentWithPaths()
      val service = AgentHttpService(mockAgent)
      mockAgent.pathManager.registerPath("metrics", "http://localhost:$PROXY_HTTP_PORT/metrics")

      val request = scrapeRequest {
        agentId = "agent-1"
        scrapeId = 72L
        path = "metrics"
      }

      val spiedService = spyk(service)
      // No cause-accepting constructor exists on CancellationException, so set it via initCause.
      coEvery {
        spiedService.fetchContent(any<String>(), any(), any())
      } coAnswers {
        throw CancellationException("wrapped timeout").apply {
          initCause(io.ktor.client.plugins.HttpRequestTimeoutException("url", 1000L))
        }
      }

      val results = spiedService.fetchScrapeUrl(request)

      results.srStatusCode shouldBe 408
      results.srValidResponse shouldBe false

      service.close()
    }

    // ==================== Per-CA HTTPS trust store (#19 option 2) ====================
    // resolveHttpsTrustManager selects the X509TrustManager for the HTTPS scrape client.

    "resolveHttpsTrustManager returns TrustAllX509TrustManager when trust-all is enabled" {
      AgentHttpService.resolveHttpsTrustManager(
        trustAllX509Certificates = true,
        trustStorePath = "",
        trustStorePassword = "",
      ) shouldBe TrustAllX509TrustManager
    }

    "resolveHttpsTrustManager prefers trust-all over a configured trust store" {
      val (path, password) = createTempKeyStore("agent-https-truststore-test")
      AgentHttpService.resolveHttpsTrustManager(
        trustAllX509Certificates = true,
        trustStorePath = path,
        trustStorePassword = password,
      ) shouldBe TrustAllX509TrustManager
    }

    "resolveHttpsTrustManager loads an X509TrustManager from a configured trust store" {
      val (path, password) = createTempKeyStore("agent-https-truststore-test")
      val tm =
        AgentHttpService.resolveHttpsTrustManager(
          trustAllX509Certificates = false,
          trustStorePath = path,
          trustStorePassword = password,
        )
      tm.shouldBeInstanceOf<X509TrustManager>()
      tm shouldNotBe TrustAllX509TrustManager
    }

    "resolveHttpsTrustManager returns null for the JDK default (no trust-all, empty path)" {
      AgentHttpService.resolveHttpsTrustManager(
        trustAllX509Certificates = false,
        trustStorePath = "",
        trustStorePassword = "",
      ).shouldBeNull()
    }

    // ==================== Task 5: content-type filter gate ====================

    "isFilterableContentType should accept text and openmetrics types" {
      AgentHttpService.isFilterableContentType("") shouldBe true
      AgentHttpService.isFilterableContentType("text/plain") shouldBe true
      AgentHttpService.isFilterableContentType("text/plain; version=0.0.4; charset=utf-8") shouldBe true
      AgentHttpService.isFilterableContentType("TEXT/PLAIN") shouldBe true
      AgentHttpService.isFilterableContentType("application/openmetrics-text; version=1.0.0") shouldBe true
    }

    "isFilterableContentType should reject binary and other types" {
      AgentHttpService.isFilterableContentType("application/vnd.google.protobuf") shouldBe false
      AgentHttpService.isFilterableContentType("application/octet-stream") shouldBe false
      AgentHttpService.isFilterableContentType("text/html") shouldBe false
    }

    // ==================== Fix pass: filter/guard ordering properties ====================
    // These pin the three load-bearing properties of buildScrapeResults that had zero coverage:
    // the maxContentLength guard reads RAW bytes; zipped/srContentAsText/srContentAsZipped all read
    // FILTERED bytes; and an unfiltered path is untouched. See task-5-report.md fix pass.

    "Fix pass: maxContentLength guard evaluates raw bytes, not filtered bytes" {
      // maxContentLengthMBytes must be >= 1 (it's whole megabytes), so the read cap
      // (maxContentLength + 1 bytes, see buildScrapeResults) is >1MB here -- big enough that the
      // capped raw chunk actually contains real filterable content rather than a single truncated
      // byte. Deny every family with no blank lines in the payload, so the filtered result is the
      // empty string: if the guard were moved after filtering, or made to compare filteredBytes.size,
      // it would see ~0 bytes and never trip, even though the raw (capped) chunk is over the limit.
      val mockAgent = createMockAgentWithFilter(
        """{ path = "metrics", metricNameAllow = [], metricNameDeny = [".*"] }""",
      )
      val options = mockAgent.options
      every { options.maxContentLengthMBytes } returns 1
      val maxContentLength = 1 * 1024 * 1024
      // 39 bytes/unit * 30_000 = 1_170_000 bytes, safely over the 1_048_576-byte limit so the
      // channel read is actually capped (not just reading the whole body under the limit).
      val rawContent = "# TYPE foo_denied counter\nfoo_denied 1\n".repeat(30_000)
      rawContent.encodeToByteArray().size shouldBeGreaterThan maxContentLength

      // respondTextWriter uses chunked transfer-encoding (no Content-Length header), so the
      // pre-read header guard is bypassed and the post-read raw-byte-size guard under test fires.
      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondTextWriter(contentType = ContentType.Text.Plain) {
              write(rawContent)
            }
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 300L
          path = "metrics"
          accept = ""
          debugEnabled = true
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe HttpStatusCode.PayloadTooLarge.value
        results.srValidResponse.shouldBeFalse()
        results.srFailureReason shouldContain "Content size"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "Fix pass: filtered path emits filtered content on the unzipped text path" {
      val mockAgent = createMockAgentWithFilter(
        """{ path = "metrics", metricNameAllow = [], metricNameDeny = ["denied_metric"] }""",
      )
      // minGzipSizeBytes stays at the createMockAgentWithFilter default (1_000_000), so this small
      // payload stays on the non-zipped text path -- srContentAsText is what's under test.
      val content =
        "# TYPE allowed_metric counter\nallowed_metric 1\n" +
          "# TYPE denied_metric counter\ndenied_metric 2\n"

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(content, ContentType.Text.Plain)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 301L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        results.srZipped.shouldBeFalse()
        results.srContentAsText shouldContain "allowed_metric"
        results.srContentAsText shouldNotContain "denied_metric"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "Fix pass: filtered path emits filtered content on the zipped path (srContentAsZipped)" {
      // Distinct from the unzipped-path test above: zipped/srContentAsZipped is a separate
      // expression from srContentAsText, so it needs its own pin (see AgentHttpService.kt).
      val mockAgent = createMockAgentWithFilter(
        """{ path = "metrics", metricNameAllow = [], metricNameDeny = ["denied_metric"] }""",
      )
      val options = mockAgent.options
      every { options.minGzipSizeBytes } returns 100

      // The allowed block alone is already > 100 bytes, so zipped=true is driven by the FILTERED
      // size, independent of how much of the denied block also gets stripped out.
      val allowedBlock =
        "# TYPE allowed_metric counter\n" + (1..10).joinToString("") { "allowed_metric{i=\"$it\"} $it\n" }
      val deniedBlock = "# TYPE denied_metric counter\n" + "denied_metric 999\n".repeat(50)
      val content = allowedBlock + deniedBlock

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(content, ContentType.Text.Plain)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 302L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        results.srZipped.shouldBeTrue()

        val decoded = results.srContentAsZipped.unzip(1_000_000L).text
        decoded shouldContain "allowed_metric"
        decoded shouldNotContain "denied_metric"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "Fix pass: a path with no filter passes the payload through byte-identical" {
      // Content that a filter would drop if one were configured for this path, but
      // createMockAgentWithPaths registers with filters = [], so pathContext.filter is null.
      val content = "# TYPE would_be_denied_metric counter\nwould_be_denied_metric 42\n"

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(content, ContentType.Text.Plain)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()

        val mockAgent = createMockAgentWithPaths()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 303L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        results.srContentAsText shouldBe content
        results.srContentAsText shouldContain "would_be_denied_metric"

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Fix pass: exercise the recording call site ====================
    // A relaxed mockk<Agent> never invokes a lambda parameter -- it just returns a default and
    // discards it -- so the prior suite's createMockAgent*() helpers left the agent.metrics { ... }
    // recording block in buildScrapeResults's filtering branch as dead code: swapping the two
    // counters, swapping their label arguments, or inverting the bytes-saved arithmetic would all
    // still pass every test. This test drives a real filtered scrape with metrics wired through a
    // real AgentMetrics instance (see createMockAgentWithFilterAndMetrics) and asserts exact counts.

    "Fix pass: filtered scrape records filterLinesDropped and filterBytesSaved with launch_id and path labels" {
      val (mockAgent, metrics) = createMockAgentWithFilterAndMetrics(
        """{ path = "metrics", metricNameAllow = [], metricNameDeny = ["denied_metric"] }""",
      )

      // The denied family (its TYPE comment line plus both sample lines) is dropped in its entirety;
      // the allowed family survives untouched. expectedFilteredContent/expectedLinesDropped are
      // reasoned out independently from MetricFilter's family-scoped semantics (not obtained by
      // calling filterText), so the assertions below pin an expected value rather than merely
      // re-deriving whatever the production code happened to produce.
      val rawContent =
        "# TYPE allowed_metric counter\n" +
          "allowed_metric 1\n" +
          "# TYPE denied_metric counter\n" +
          "denied_metric 2\n" +
          "denied_metric 3\n"
      val expectedFilteredContent =
        "# TYPE allowed_metric counter\n" +
          "allowed_metric 1\n"
      val expectedLinesDropped = 3.0
      val expectedBytesSaved =
        (rawContent.encodeToByteArray().size - expectedFilteredContent.encodeToByteArray().size).toDouble()

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") {
            call.respondText(rawContent, ContentType.Text.Plain)
          }
        }
      }

      try {
        val port = server.startAndAwaitReady()
        val service = AgentHttpService(mockAgent)

        mockAgent.pathManager.registerPath("metrics", "http://localhost:$port/metrics")

        val request = scrapeRequest {
          agentId = "agent-1"
          scrapeId = 304L
          path = "metrics"
          accept = ""
          debugEnabled = false
          encodedQueryParams = ""
          authHeader = ""
        }

        val results = service.fetchScrapeUrl(request)

        results.srStatusCode shouldBe 200
        results.srValidResponse.shouldBeTrue()
        results.srZipped.shouldBeFalse()
        results.srContentAsText shouldBe expectedFilteredContent

        metrics.filterLinesDropped.labels("test-launch-id", "metrics").get() shouldBe expectedLinesDropped
        metrics.filterBytesSaved.labels("test-launch-id", "metrics").get() shouldBe expectedBytesSaved

        service.close()
      } finally {
        server.stop(0, 0)
      }
    }
  }
}
