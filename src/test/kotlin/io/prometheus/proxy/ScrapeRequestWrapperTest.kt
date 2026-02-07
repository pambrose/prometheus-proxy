@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ScrapeRequestWrapperTest {
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

  // ==================== Creation Tests ====================

  @Test
  fun `should create scrape request with correct path`() {
    val wrapper = createWrapper(path = "/test/metrics")

    wrapper.scrapeRequest.path shouldBe "/test/metrics"
  }

  @Test
  fun `should create scrape request with agent id from context`() {
    val context = createAgentContext()
    val wrapper = createWrapper(agentContext = context)

    wrapper.scrapeRequest.agentId shouldBe context.agentId
  }

  @Test
  fun `should generate unique scrapeIds`() {
    val wrapper1 = createWrapper()
    val wrapper2 = createWrapper()

    wrapper1.scrapeId shouldBe wrapper1.scrapeRequest.scrapeId
    wrapper1.scrapeId shouldNotBe wrapper2.scrapeId
  }

  @Test
  fun `should set debug enabled flag`() {
    val wrapper = createWrapper(debugEnabled = true)

    wrapper.scrapeRequest.debugEnabled shouldBe true
  }

  @Test
  fun `should set encoded query params`() {
    val wrapper = createWrapper(encodedQueryParams = "foo=bar&baz=qux")

    wrapper.scrapeRequest.encodedQueryParams shouldBe "foo=bar&baz=qux"
  }

  @Test
  fun `should set accept header`() {
    val wrapper = createWrapper(accept = "text/plain")

    wrapper.scrapeRequest.accept shouldBe "text/plain"
  }

  @Test
  fun `should handle null accept as empty string`() {
    val wrapper = createWrapper(accept = null)

    wrapper.scrapeRequest.accept shouldBe ""
  }

  @Test
  fun `should set auth header`() {
    val wrapper = createWrapper(authHeader = "Bearer token123")

    wrapper.scrapeRequest.authHeader shouldBe "Bearer token123"
  }

  // ==================== ScrapeResults Tests ====================

  @Test
  fun `scrapeResults should be null initially`() {
    val wrapper = createWrapper()

    wrapper.scrapeResults.shouldBeNull()
  }

  // ==================== Age Duration Tests ====================

  @Test
  fun `ageDuration should be non-negative and increase over time`() {
    val wrapper = createWrapper()
    val initialAge = wrapper.ageDuration()

    Thread.sleep(50)

    val laterAge = wrapper.ageDuration()
    (laterAge > initialAge) shouldBe true
  }

  // ==================== Completion Tests ====================

  @Test
  fun `suspendUntilComplete should return true when markComplete is called`(): Unit =
    runBlocking {
      val wrapper = createWrapper()

      launch {
        Thread.sleep(50)
        wrapper.markComplete()
      }

      val result = wrapper.suspendUntilComplete(5.seconds)
      result.shouldBeTrue()
    }

  @Test
  fun `suspendUntilComplete should return false on timeout`(): Unit =
    runBlocking {
      val wrapper = createWrapper()

      // Do not call markComplete — should timeout
      val result = wrapper.suspendUntilComplete(100.milliseconds)
      result.shouldBeFalse()
    }

  // ==================== toString Tests ====================

  @Test
  fun `toString should include scrapeId and path`() {
    val wrapper = createWrapper(path = "/test/path")

    val str = wrapper.toString()
    str shouldContain "scrapeId"
    str shouldContain "/test/path"
  }
}
