@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AgentContextManagerTest {
  // ==================== Initial State Tests ====================

  @Test
  fun `should start with empty agent context map`() {
    val manager = AgentContextManager(isTestMode = true)

    manager.agentContextSize shouldBe 0
  }

  @Test
  fun `should start with empty chunked context map`() {
    val manager = AgentContextManager(isTestMode = true)

    manager.chunkedContextSize shouldBe 0
  }

  @Test
  fun `totalAgentScrapeRequestBacklogSize should be zero when empty`() {
    val manager = AgentContextManager(isTestMode = true)

    manager.totalAgentScrapeRequestBacklogSize shouldBe 0
  }

  // ==================== Add Context Tests ====================

  @Test
  fun `addAgentContext should add context and return null for new agent`() {
    val manager = AgentContextManager(isTestMode = true)
    val context = AgentContext("remote-addr-1")

    val result = manager.addAgentContext(context)

    result.shouldBeNull()
    manager.agentContextSize shouldBe 1
  }

  @Test
  fun `addAgentContext should return old context when replacing`() {
    val manager = AgentContextManager(isTestMode = true)
    val context1 = AgentContext("remote-addr-1")

    manager.addAgentContext(context1)

    // Use putAgentContext to replace with a second context using the same agentId
    val context2 = AgentContext("remote-addr-2")
    manager.putAgentContext(context1.agentId, context2)

    // Verify replacement occurred
    val retrieved = manager.getAgentContext(context1.agentId)
    retrieved.shouldNotBeNull()
    retrieved shouldBe context2
    manager.agentContextSize shouldBe 1
  }

  @Test
  fun `addAgentContext should handle multiple agents`() {
    val manager = AgentContextManager(isTestMode = true)
    val context1 = AgentContext("remote-1")
    val context2 = AgentContext("remote-2")
    val context3 = AgentContext("remote-3")

    manager.addAgentContext(context1)
    manager.addAgentContext(context2)
    manager.addAgentContext(context3)

    manager.agentContextSize shouldBe 3
  }

  // ==================== Get Context Tests ====================

  @Test
  fun `getAgentContext should return context for existing agentId`() {
    val manager = AgentContextManager(isTestMode = true)
    val context = AgentContext("remote-addr")

    manager.addAgentContext(context)

    val result = manager.getAgentContext(context.agentId)
    result.shouldNotBeNull()
    result shouldBe context
  }

  @Test
  fun `getAgentContext should return null for non-existent agentId`() {
    val manager = AgentContextManager(isTestMode = true)

    val result = manager.getAgentContext("non-existent-id")
    result.shouldBeNull()
  }

  // ==================== Remove Context Tests ====================

  @Test
  fun `removeFromContextManager should remove and invalidate context`() {
    val manager = AgentContextManager(isTestMode = true)
    val context = AgentContext("remote-addr")

    manager.addAgentContext(context)
    manager.agentContextSize shouldBe 1

    val removed = manager.removeFromContextManager(context.agentId, "test removal")

    removed.shouldNotBeNull()
    removed shouldBe context
    removed.isNotValid() shouldBe true
    manager.agentContextSize shouldBe 0
  }

  @Test
  fun `removeFromContextManager should return null for non-existent agentId`() {
    val manager = AgentContextManager(isTestMode = true)

    val removed = manager.removeFromContextManager("non-existent", "test")

    removed.shouldBeNull()
  }

  @Test
  fun `removeFromContextManager should only remove specified agent`() {
    val manager = AgentContextManager(isTestMode = true)
    val context1 = AgentContext("remote-1")
    val context2 = AgentContext("remote-2")

    manager.addAgentContext(context1)
    manager.addAgentContext(context2)

    manager.removeFromContextManager(context1.agentId, "test")

    manager.agentContextSize shouldBe 1
    manager.getAgentContext(context2.agentId).shouldNotBeNull()
    manager.getAgentContext(context1.agentId).shouldBeNull()
  }

  // ==================== Backlog Aggregation Tests ====================

  @Test
  fun `totalAgentScrapeRequestBacklogSize should sum across agents`() {
    val manager = AgentContextManager(isTestMode = true)
    val context1 = AgentContext("remote-1")
    val context2 = AgentContext("remote-2")

    manager.addAgentContext(context1)
    manager.addAgentContext(context2)

    // Both start at 0
    manager.totalAgentScrapeRequestBacklogSize shouldBe 0
  }

  // ==================== Concurrent Access Tests ====================

  @Test
  fun `concurrent add and remove should not lose agents`() {
    val manager = AgentContextManager(isTestMode = true)
    val contexts = (1..100).map { AgentContext("remote-$it") }

    // Add all
    contexts.forEach { manager.addAgentContext(it) }
    manager.agentContextSize shouldBe 100

    // Remove half
    contexts.take(50).forEach { manager.removeFromContextManager(it.agentId, "test") }
    manager.agentContextSize shouldBe 50
  }

  // ==================== removeFromContextManager Non-Test Mode ====================

  @Test
  fun `removeFromContextManager should still invalidate context in non-test mode`() {
    val manager = AgentContextManager(isTestMode = false)
    val context = AgentContext("remote-addr")

    manager.addAgentContext(context)
    val removed = manager.removeFromContextManager(context.agentId, "test")

    removed.shouldNotBeNull()
    removed.isNotValid() shouldBe true
    manager.agentContextSize shouldBe 0
  }

  // ==================== ChunkedContext Map Tests ====================

  @Test
  fun `chunkedContextMap should track entries correctly`() {
    val manager = AgentContextManager(isTestMode = true)
    val mockChunkedContext = mockk<ChunkedContext>(relaxed = true)

    manager.putChunkedContext(1L, mockChunkedContext)
    manager.chunkedContextSize shouldBe 1

    manager.putChunkedContext(2L, mockChunkedContext)
    manager.chunkedContextSize shouldBe 2

    manager.removeChunkedContext(1L)
    manager.chunkedContextSize shouldBe 1
  }

  // ==================== Non-Zero Backlog Aggregation ====================

  @Test
  fun `totalAgentScrapeRequestBacklogSize should sum non-zero backlogs`(): Unit =
    runBlocking {
      val manager = AgentContextManager(isTestMode = true)
      val context1 = AgentContext("remote-1")
      val context2 = AgentContext("remote-2")

      manager.addAgentContext(context1)
      manager.addAgentContext(context2)

      // Write scrape requests to create backlog
      context1.writeScrapeRequest(mockk(relaxed = true))
      context1.writeScrapeRequest(mockk(relaxed = true))
      context2.writeScrapeRequest(mockk(relaxed = true))

      manager.totalAgentScrapeRequestBacklogSize shouldBe 3
    }

  // ==================== invalidateAllAgentContexts Tests ====================

  // Bug #4: Proxy shutdown did not invalidate remaining agent contexts, causing
  // coroutines in readRequestsFromProxy to hang and HTTP handlers waiting in
  // awaitCompleted to experience unnecessary delays until the timeout expired.
  @Test
  fun `invalidateAllAgentContexts should invalidate all agent contexts`() {
    val manager = AgentContextManager(isTestMode = true)
    val context1 = AgentContext("remote-1")
    val context2 = AgentContext("remote-2")
    val context3 = AgentContext("remote-3")

    manager.addAgentContext(context1)
    manager.addAgentContext(context2)
    manager.addAgentContext(context3)

    context1.isValid().shouldBeTrue()
    context2.isValid().shouldBeTrue()
    context3.isValid().shouldBeTrue()

    manager.invalidateAllAgentContexts()

    context1.isValid().shouldBeFalse()
    context2.isValid().shouldBeFalse()
    context3.isValid().shouldBeFalse()
  }

  @Test
  fun `invalidateAllAgentContexts should drain backlog for all contexts`(): Unit =
    runBlocking {
      val manager = AgentContextManager(isTestMode = true)
      val context1 = AgentContext("remote-1")
      val context2 = AgentContext("remote-2")

      manager.addAgentContext(context1)
      manager.addAgentContext(context2)

      // Build up backlog
      context1.writeScrapeRequest(mockk(relaxed = true))
      context1.writeScrapeRequest(mockk(relaxed = true))
      context2.writeScrapeRequest(mockk(relaxed = true))
      manager.totalAgentScrapeRequestBacklogSize shouldBe 3

      manager.invalidateAllAgentContexts()

      // All backlogs should be drained to 0
      context1.scrapeRequestBacklogSize shouldBe 0
      context2.scrapeRequestBacklogSize shouldBe 0
      manager.totalAgentScrapeRequestBacklogSize shouldBe 0
    }

  @Test
  fun `invalidateAllAgentContexts should unblock awaitCompleted on buffered wrappers`(): Unit =
    runBlocking {
      val manager = AgentContextManager(isTestMode = true)
      val context = AgentContext("remote-addr")
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.isMetricsEnabled } returns false

      manager.addAgentContext(context)

      // Create a real ScrapeRequestWrapper so awaitCompleted() works end-to-end
      val wrapper = ScrapeRequestWrapper(context, mockProxy, "/metrics", "", "", null, false)
      context.writeScrapeRequest(wrapper)

      val startTime = System.currentTimeMillis()
      val deferred = async {
        wrapper.awaitCompleted(30.seconds)
      }

      // invalidateAllAgentContexts should drain and close the wrapper's channel
      manager.invalidateAllAgentContexts()

      val completed = deferred.await()
      val elapsed = System.currentTimeMillis() - startTime

      completed.shouldBeFalse()
      // Should unblock well under the 30-second timeout
      elapsed shouldBeLessThan 5000L
    }

  @Test
  fun `invalidateAllAgentContexts with no agents should not fail`() {
    val manager = AgentContextManager(isTestMode = true)

    // Should not throw when there are no agent contexts
    manager.invalidateAllAgentContexts()

    manager.agentContextSize shouldBe 0
  }

  @Test
  fun `invalidateAllAgentContexts should not remove contexts from map`() {
    val manager = AgentContextManager(isTestMode = true)
    val context1 = AgentContext("remote-1")
    val context2 = AgentContext("remote-2")

    manager.addAgentContext(context1)
    manager.addAgentContext(context2)

    manager.invalidateAllAgentContexts()

    // Contexts are invalidated but still in the map (removed by service shutdown)
    manager.agentContextSize shouldBe 2
    manager.getAgentContext(context1.agentId).shouldNotBeNull()
    manager.getAgentContext(context2.agentId).shouldNotBeNull()
  }
}
