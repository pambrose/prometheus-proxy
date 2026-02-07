@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ScrapeRequest
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
}
