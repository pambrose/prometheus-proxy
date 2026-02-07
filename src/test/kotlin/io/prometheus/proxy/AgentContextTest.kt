@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.grpc.RegisterAgentRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AgentContextTest {
  // ==================== Creation Tests ====================

  @Test
  fun `should generate unique agentIds`() {
    val context1 = AgentContext("remote1")
    val context2 = AgentContext("remote2")

    context1.agentId shouldNotBe context2.agentId
  }

  @Test
  fun `should be valid after creation`() {
    val context = AgentContext("remote-addr")

    context.isValid().shouldBeTrue()
    context.isNotValid().shouldBeFalse()
  }

  @Test
  fun `should have default property values`() {
    val context = AgentContext("remote-addr")

    context.hostName shouldBe "Unassigned"
    context.agentName shouldBe "Unassigned"
    context.consolidated shouldBe false
  }

  @Test
  fun `should have zero scrapeRequestBacklogSize initially`() {
    val context = AgentContext("remote-addr")

    context.scrapeRequestBacklogSize shouldBe 0
  }

  // ==================== Property Assignment Tests ====================

  @Test
  fun `assignProperties should update agent fields`() {
    val context = AgentContext("remote-addr")
    val request = mockk<RegisterAgentRequest>()
    every { request.launchId } returns "launch-456"
    every { request.agentName } returns "my-agent"
    every { request.hostName } returns "agent-host"
    every { request.consolidated } returns true

    context.assignProperties(request)

    context.agentName shouldBe "my-agent"
    context.hostName shouldBe "agent-host"
    context.consolidated shouldBe true
  }

  // ==================== Invalidation Tests ====================

  @Test
  fun `invalidate should mark context as not valid`() {
    val context = AgentContext("remote-addr")

    context.invalidate()

    context.isValid().shouldBeFalse()
    context.isNotValid().shouldBeTrue()
  }

  // ==================== Channel Tests ====================

  @Test
  fun `writeScrapeRequest should increment backlog size`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")
      val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(wrapper)

      context.scrapeRequestBacklogSize shouldBe 1
    }

  @Test
  fun `readScrapeRequest should decrement backlog size`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")
      val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(wrapper)
      context.scrapeRequestBacklogSize shouldBe 1

      val result = context.readScrapeRequest()
      result.shouldNotBeNull()
      context.scrapeRequestBacklogSize shouldBe 0
    }

  @Test
  fun `readScrapeRequest should return null after invalidation`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")
      context.invalidate()

      val result = context.readScrapeRequest()
      result.shouldBeNull()
    }

  @Test
  fun `multiple write and read operations should track backlog correctly`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")

      repeat(3) {
        context.writeScrapeRequest(mockk(relaxed = true))
      }
      context.scrapeRequestBacklogSize shouldBe 3

      context.readScrapeRequest()
      context.scrapeRequestBacklogSize shouldBe 2

      context.readScrapeRequest()
      context.scrapeRequestBacklogSize shouldBe 1
    }

  // ==================== Activity Time Tests ====================

  @Test
  fun `markActivityTime should update inactivity duration`() {
    val context = AgentContext("remote-addr")

    Thread.sleep(50)
    val durationBefore = context.inactivityDuration

    context.markActivityTime(true)
    val durationAfter = context.inactivityDuration

    durationAfter shouldNotBe durationBefore
  }

  // ==================== Equality Tests ====================

  @Test
  fun `equals should be based on agentId`() {
    val context1 = AgentContext("remote1")
    val context2 = AgentContext("remote2")

    context1 shouldNotBe context2
    context1 shouldBe context1
  }

  @Test
  fun `hashCode should be based on agentId`() {
    val context = AgentContext("remote-addr")

    context.hashCode() shouldBe context.agentId.hashCode()
  }

  // ==================== toString Tests ====================

  @Test
  fun `toString should include key fields`() {
    val context = AgentContext("10.0.0.1")
    val str = context.toString()

    str shouldContain "agentId"
    str shouldContain "remoteAddr"
    str shouldContain "10.0.0.1"
  }

  @Test
  fun `desc should indicate consolidated mode`() {
    val context = AgentContext("remote-addr")
    val request = mockk<RegisterAgentRequest>()
    every { request.launchId } returns "launch-1"
    every { request.agentName } returns "agent"
    every { request.hostName } returns "host"
    every { request.consolidated } returns true

    context.assignProperties(request)

    context.desc shouldContain "consolidated"
  }

  @Test
  fun `desc should be empty for non-consolidated agent`() {
    val context = AgentContext("remote-addr")

    context.desc shouldBe ""
  }
}
