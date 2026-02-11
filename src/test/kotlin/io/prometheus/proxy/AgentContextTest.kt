@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.grpc.RegisterAgentRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

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

  // M3: invalidate() now drains buffered scrape requests and calls closeChannel() on each
  // so HTTP handlers waiting on awaitCompleted() are notified immediately instead of
  // waiting for the full scrape timeout to expire.
  @Test
  fun `invalidate should close pending scrape request wrappers`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")
      val wrapper1 = mockk<ScrapeRequestWrapper>(relaxed = true)
      val wrapper2 = mockk<ScrapeRequestWrapper>(relaxed = true)
      val wrapper3 = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.writeScrapeRequest(wrapper1)
      context.writeScrapeRequest(wrapper2)
      context.writeScrapeRequest(wrapper3)
      context.scrapeRequestBacklogSize shouldBe 3

      context.invalidate()

      context.isValid().shouldBeFalse()
      // All buffered wrappers should have had closeChannel() called
      verify(exactly = 1) { wrapper1.closeChannel() }
      verify(exactly = 1) { wrapper2.closeChannel() }
      verify(exactly = 1) { wrapper3.closeChannel() }
    }

  @Test
  fun `invalidate should unblock awaitCompleted on buffered wrappers`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.isMetricsEnabled } returns false

      // Create a real ScrapeRequestWrapper so awaitCompleted() works end-to-end
      val wrapper = ScrapeRequestWrapper(context, mockProxy, "/metrics", "", "", null, false)

      context.writeScrapeRequest(wrapper)

      // Start awaiting completion in a separate coroutine with a long timeout
      val startTime = System.currentTimeMillis()
      val deferred = async {
        wrapper.awaitCompleted(30.seconds)
      }

      // Invalidate should drain the channel and close the wrapper's completion channel
      context.invalidate()

      // awaitCompleted should unblock almost immediately.
      // It returns false because scrapeResults was never assigned (agent disconnected).
      val completed = deferred.await()
      val elapsed = System.currentTimeMillis() - startTime

      completed.shouldBeFalse()
      // Should unblock in well under the 30-second timeout
      elapsed shouldBeLessThan 5000L
    }

  @Test
  fun `invalidate with no buffered requests should not fail`() {
    val context = AgentContext("remote-addr")

    // Should not throw when channel is empty
    context.invalidate()

    context.isValid().shouldBeFalse()
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

  // ==================== Backlog Counter Consistency Tests ====================

  @Test
  fun `invalidate should decrement backlog size for drained items`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")

      context.writeScrapeRequest(mockk(relaxed = true))
      context.writeScrapeRequest(mockk(relaxed = true))
      context.writeScrapeRequest(mockk(relaxed = true))
      context.scrapeRequestBacklogSize shouldBe 3

      context.invalidate()

      // After invalidation, backlog should be 0 because drained items are decremented
      context.scrapeRequestBacklogSize shouldBe 0
    }

  @Test
  fun `writeScrapeRequest to closed channel should not leak backlog count`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")

      // Invalidate first so channel is closed
      context.invalidate()
      context.scrapeRequestBacklogSize shouldBe 0

      // Attempting to write to a closed channel should throw,
      // but the backlog counter should remain at 0 (not increment without a matching decrement)
      val threw = try {
        context.writeScrapeRequest(mockk(relaxed = true))
        false
      } catch (_: Exception) {
        true
      }

      threw.shouldBeTrue()
      context.scrapeRequestBacklogSize shouldBe 0
    }

  @Test
  fun `invalidate with mixed read and unread items should have correct backlog`(): Unit =
    runBlocking {
      val context = AgentContext("remote-addr")

      // Write 5 items
      repeat(5) { context.writeScrapeRequest(mockk(relaxed = true)) }
      context.scrapeRequestBacklogSize shouldBe 5

      // Read 2 items (each decrements backlog)
      context.readScrapeRequest()
      context.readScrapeRequest()
      context.scrapeRequestBacklogSize shouldBe 3

      // Invalidate should drain remaining 3 and decrement for each
      context.invalidate()
      context.scrapeRequestBacklogSize shouldBe 0
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

  // ==================== markActivityTime Branch Tests ====================

  @Test
  fun `markActivityTime with isRequest false should update inactivity but not request time`() {
    val context = AgentContext("remote-addr")

    Thread.sleep(50)
    val inactivityBefore = context.inactivityDuration

    context.markActivityTime(false)
    val inactivityAfter = context.inactivityDuration

    // Inactivity duration should have reset (become shorter)
    (inactivityAfter < inactivityBefore) shouldBe true
  }

  // ==================== Equality Edge Case Tests ====================

  @Test
  @Suppress("EqualsNullCall")
  fun `equals with null should return false`() {
    val context = AgentContext("remote-addr")

    (context.equals(null)) shouldBe false
    (context == null) shouldBe false
  }

  @Test
  fun `equals with different type should return false`() {
    val context = AgentContext("remote-addr")

    (context.equals("a string")) shouldBe false
  }

  @Test
  fun `equals with same instance should return true`() {
    val context = AgentContext("remote-addr")

    (context == context) shouldBe true
  }

  // L6: markActivityTime should use a single clock.markNow() so both timestamps
  // are consistent. Before the fix, two separate markNow() calls could drift.
  @Test
  fun `markActivityTime with isRequest true should keep both timestamps consistent`() {
    val context = AgentContext("remote-addr")

    Thread.sleep(100)
    context.markActivityTime(true)

    // Both inactivityDuration and lastRequestDuration (accessed via toString)
    // should be very close to zero since we just marked activity.
    // The key invariant: inactivityDuration should not be significantly different
    // from what lastRequestDuration would be, since both were set from the same markNow().
    val inactivity = context.inactivityDuration
    inactivity.inWholeMilliseconds shouldBeLessThan 50L
  }
}
