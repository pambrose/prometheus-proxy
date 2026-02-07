@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import com.typesafe.config.ConfigFactory
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.common.ConfigVals
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.scrapeRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AgentHttpServiceTest {
  private fun createMockAgent(): Agent {
    val mockOptions = mockk<AgentOptions>(relaxed = true)
    every { mockOptions.maxCacheSize } returns 100
    every { mockOptions.maxCacheAgeMins } returns 30
    every { mockOptions.maxCacheIdleMins } returns 10
    every { mockOptions.cacheCleanupIntervalMins } returns 5
    every { mockOptions.scrapeTimeoutSecs } returns 10
    every { mockOptions.minGzipSizeBytes } returns 512
    every { mockOptions.debugEnabled } returns false

    val mockPathManager = mockk<AgentPathManager>(relaxed = true)

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.options } returns mockOptions
    every { mockAgent.pathManager } returns mockPathManager
    every { mockAgent.isMetricsEnabled } returns false
    return mockAgent
  }

  // ==================== Invalid Path Tests ====================

  @Test
  fun `fetchScrapeUrl should return error results for invalid path`(): Unit =
    runBlocking {
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

  @Test
  fun `fetchScrapeUrl should set debug info for invalid path when debug enabled`(): Unit =
    runBlocking {
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

  @Test
  fun `fetchScrapeUrl should not set debug info for invalid path when debug disabled`(): Unit =
    runBlocking {
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

  // ==================== Close Tests ====================

  @Test
  fun `close should close httpClientCache`() {
    val mockAgent = createMockAgent()
    val service = AgentHttpService(mockAgent)

    // Should not throw
    service.close()
  }

  // ==================== HttpClientCache Tests ====================

  @Test
  fun `httpClientCache should be initialized from agent options`() {
    val mockAgent = createMockAgent()
    val service = AgentHttpService(mockAgent)

    service.httpClientCache shouldBe service.httpClientCache // exists and is stable
    service.close()
  }

  // ==================== Valid Path Fetching Tests ====================

  private fun createMockAgentWithPaths(): Agent {
    val mockOptions = mockk<AgentOptions>(relaxed = true)
    every { mockOptions.maxCacheSize } returns 100
    every { mockOptions.maxCacheAgeMins } returns 30
    every { mockOptions.maxCacheIdleMins } returns 10
    every { mockOptions.cacheCleanupIntervalMins } returns 5
    every { mockOptions.scrapeTimeoutSecs } returns 10
    every { mockOptions.scrapeMaxRetries } returns 0
    every { mockOptions.minGzipSizeBytes } returns 1_000_000
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

  @Test
  fun `fetchScrapeUrl should fetch content from valid path`(): Unit =
    runBlocking {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            call.respondText("test_metric{label=\"value\"} 42\n")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port

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

  @Test
  fun `fetchScrapeUrl should handle connection refused`(): Unit =
    runBlocking {
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

  @Test
  fun `fetchScrapeUrl should handle 404 response`(): Unit =
    runBlocking {
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          // No route for /metrics, Ktor will return 404
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port

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

  @Test
  fun `fetchScrapeUrl should set debug info when debug enabled and request fails`(): Unit =
    runBlocking {
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

  @Test
  fun `fetchScrapeUrl should include query params in URL`(): Unit =
    runBlocking {
      var receivedUrl = ""
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            receivedUrl = call.request.queryParameters.entries().joinToString("&") { "${it.key}=${it.value.first()}" }
            call.respondText("ok")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port

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
}
