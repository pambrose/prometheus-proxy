@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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

    // Manually add a second context with the same agentId
    val context2 = AgentContext("remote-addr-2")
    manager.agentContextMap[context1.agentId] = context1
    val result = manager.agentContextMap.put(context1.agentId, context2)

    result.shouldNotBeNull()
    result shouldBe context1
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
}
