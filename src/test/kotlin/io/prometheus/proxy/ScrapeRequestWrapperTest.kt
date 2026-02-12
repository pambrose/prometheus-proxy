@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScrapeRequestWrapperTest : StringSpec() {
  private fun createMockProxy(): Proxy {
    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.isMetricsEnabled } returns false
    return mockProxy
  }

  private fun createAgentContext(): AgentContext = AgentContext("test-remote-addr")

  private fun createWrapper(
    proxy: Proxy = createMockProxy(),
    agentContext: AgentContext = createAgentContext(),
    path: String = "/metrics",
    encodedQueryParams: String = "",
    authHeader: String = "",
    accept: String? = null,
    debugEnabled: Boolean = false,
  ) = ScrapeRequestWrapper(
    agentContext = agentContext,
    proxy = proxy,
    pathVal = path,
    encodedQueryParamsVal = encodedQueryParams,
    authHeaderVal = authHeader,
    acceptVal = accept,
    debugEnabledVal = debugEnabled,
  )

  init {
    // ==================== Creation Tests ====================

    "should create scrape request with correct path" {
      val wrapper = createWrapper(path = "/test/metrics")

      wrapper.scrapeRequest.path shouldBe "/test/metrics"
    }

    "should create scrape request with agent id from context" {
      val context = createAgentContext()
      val wrapper = createWrapper(agentContext = context)

      wrapper.scrapeRequest.agentId shouldBe context.agentId
    }

    "should generate unique scrapeIds" {
      val wrapper1 = createWrapper()
      val wrapper2 = createWrapper()

      wrapper1.scrapeId shouldBe wrapper1.scrapeRequest.scrapeId
      wrapper1.scrapeId shouldNotBe wrapper2.scrapeId
    }

    "should set debug enabled flag" {
      val wrapper = createWrapper(debugEnabled = true)

      wrapper.scrapeRequest.debugEnabled shouldBe true
    }

    "should set encoded query params" {
      val wrapper = createWrapper(encodedQueryParams = "foo=bar&baz=qux")

      wrapper.scrapeRequest.encodedQueryParams shouldBe "foo=bar&baz=qux"
    }

    "should set accept header" {
      val wrapper = createWrapper(accept = "text/plain")

      wrapper.scrapeRequest.accept shouldBe "text/plain"
    }

    "should handle null accept as empty string" {
      val wrapper = createWrapper(accept = null)

      wrapper.scrapeRequest.accept shouldBe ""
    }

    "should set auth header" {
      val wrapper = createWrapper(authHeader = "Bearer token123")

      wrapper.scrapeRequest.authHeader shouldBe "Bearer token123"
    }

    // ==================== ScrapeResults Tests ====================

    "scrapeResults should be null initially" {
      val wrapper = createWrapper()

      wrapper.scrapeResults.shouldBeNull()
    }

    // ==================== Age Duration Tests ====================

    "ageDuration should be non-negative and increase over time" {
      val wrapper = createWrapper()
      val initialAge = wrapper.ageDuration()

      Thread.sleep(50)

      val laterAge = wrapper.ageDuration()
      (laterAge > initialAge) shouldBe true
    }

    // ==================== Completion Tests ====================

    "awaitCompleted should return true when markComplete is called with results" {
      val wrapper = createWrapper()

      launch {
        Thread.sleep(50)
        wrapper.scrapeResults = ScrapeResults(srAgentId = "agent-1", srScrapeId = wrapper.scrapeId)
        wrapper.markComplete()
      }

      val result = wrapper.awaitCompleted(5.seconds)
      result.shouldBeTrue()
    }

    "awaitCompleted should return false on timeout" {
      val wrapper = createWrapper()

      // Do not call markComplete — should timeout
      val result = wrapper.awaitCompleted(100.milliseconds)
      result.shouldBeFalse()
    }

    "awaitCompleted should return false when channel closed without results" {
      val wrapper = createWrapper()

      launch {
        Thread.sleep(50)
        // Close channel directly without setting scrapeResults
        wrapper.closeChannel()
      }

      val result = wrapper.awaitCompleted(5.seconds)
      result.shouldBeFalse()
    }

    // ==================== toString Tests ====================

    "toString should include scrapeId and path" {
      val wrapper = createWrapper(path = "/test/path")

      val str = wrapper.toString()
      str shouldContain "scrapeId"
      str shouldContain "/test/path"
    }

    // ==================== markComplete with Metrics Tests ====================

    "markComplete with metrics enabled should observe duration" {
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.isMetricsEnabled } returns true
      val mockMetrics = mockk<ProxyMetrics>(relaxed = true)
      every { mockProxy.metrics } returns mockMetrics

      val wrapper = createWrapper(proxy = mockProxy)

      // Should not throw — observeDuration called on the timer
      wrapper.markComplete()
    }

    "markComplete without metrics should not throw" {
      val wrapper = createWrapper()

      // Should not throw
      wrapper.markComplete()
    }

    // ==================== awaitCompleted Edge Cases ====================

    "awaitCompleted should return true immediately when already completed with results" {
      val wrapper = createWrapper()

      wrapper.scrapeResults = ScrapeResults(srAgentId = "agent-1", srScrapeId = wrapper.scrapeId)
      wrapper.markComplete()

      // After markComplete with results, awaitCompleted should return true quickly
      val result = wrapper.awaitCompleted(5.seconds)
      result.shouldBeTrue()
    }

    // ==================== Bug #13: awaitCompleted parameter name ====================
    // The parameter was renamed from `waitMillis` to `timeout` since its type is Duration,
    // not milliseconds. These tests verify the named parameter works correctly.

    "awaitCompleted timeout parameter should accept Duration values" {
      val wrapper = createWrapper()

      launch {
        Thread.sleep(50)
        wrapper.scrapeResults = ScrapeResults(srAgentId = "agent-1", srScrapeId = wrapper.scrapeId)
        wrapper.markComplete()
      }

      // Using named parameter `timeout`
      val result = wrapper.awaitCompleted(timeout = 5.seconds)
      result.shouldBeTrue()
    }

    "awaitCompleted timeout should respect short durations" {
      val wrapper = createWrapper()

      // Using named parameter `timeout` with a short duration — should time out
      val result = wrapper.awaitCompleted(timeout = 50.milliseconds)
      result.shouldBeFalse()
    }

    // ==================== Constructor Validation Tests ====================

    "constructor should throw on empty agentId" {
      val mockContext = mockk<AgentContext>(relaxed = true)
      every { mockContext.agentId } returns ""

      shouldThrow<IllegalArgumentException> {
        ScrapeRequestWrapper(
          agentContext = mockContext,
          proxy = createMockProxy(),
          pathVal = "/metrics",
          encodedQueryParamsVal = "",
          authHeaderVal = "",
          acceptVal = null,
          debugEnabledVal = false,
        )
      }
    }

    // ==================== markComplete Idempotency Tests ====================

    "markComplete should not throw when called multiple times" {
      val wrapper = createWrapper()
      wrapper.scrapeResults = ScrapeResults(srAgentId = "agent-1", srScrapeId = wrapper.scrapeId)

      wrapper.markComplete()
      // Second call should not throw — channel is already closed
      wrapper.markComplete()
    }
  }
}
