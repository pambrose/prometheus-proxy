/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.grpc.RegisterAgentRequest
import io.kotest.matchers.maps.shouldHaveSize as mapShouldHaveSize

@Suppress("LargeClass")
class ProxyPathManagerTest : StringSpec() {
  private fun createMockProxy(): Proxy {
    val mockManager = mockk<AgentContextManager>(relaxed = true)
    val proxy = mockk<Proxy>(relaxed = true)
    every { proxy.agentContextManager } returns mockManager
    return proxy
  }

  private fun createMockAgentContext(consolidated: Boolean = false): AgentContext {
    val context = mockk<AgentContext>(relaxed = true)
    val agentId = "agent-${System.currentTimeMillis()}-${(1..1000).random()}"
    every { context.agentId } returns agentId
    every { context.consolidated } returns consolidated
    every { context.isNotValid() } returns false
    every { context.desc } returns if (consolidated) "consolidated " else ""
    return context
  }

  init {
    "addPath should add new path successfully" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.labels shouldBe """{"job":"test"}"""
      info.agentContexts.shouldHaveSize(1)
    }

    "addPath should throw when path is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      val exception = shouldThrow<IllegalArgumentException> {
        manager.addPath("", """{"job":"test"}""", context)
      }

      exception.message shouldContain "Empty path"
    }

    "addPath should overwrite non-consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext()
      val context2 = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test1"}""", context1)
      manager.addPath("/metrics", """{"job":"test2"}""", context2)

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.labels shouldBe """{"job":"test2"}"""
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe context2.agentId
    }

    // Tests consolidated path behavior: when multiple agents register the same path with
    // consolidated=true, they are grouped together. Prometheus scrapes are load-balanced
    // across all agents in the consolidated group. This differs from non-consolidated
    // paths where a new registration overwrites the previous one.
    "addPath should append to consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      info.agentContexts.shouldHaveSize(2)
    }

    "removePath should remove path successfully" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)
      manager.pathMapSize shouldBe 1

      val response = manager.removePath("/metrics", context.agentId)

      response.valid.shouldBeTrue()
      manager.pathMapSize shouldBe 0
    }

    "removePath should fail when path not found" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val response = manager.removePath("/nonexistent", "agent-123")

      response.valid.shouldBeFalse()
      response.reason shouldContain "path not found"
    }

    "removePath should fail when agent ID mismatch" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val response = manager.removePath("/metrics", "wrong-agent-id")

      response.valid.shouldBeFalse()
      response.reason shouldContain "invalid agentId"
    }

    "removePath should throw when path is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.removePath("", "agent-123")
      }

      exception.message shouldContain "Empty path"
    }

    "removePath should throw when agentId is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.removePath("/metrics", "")
      }

      exception.message shouldContain "Empty agentId"
    }

    // Tests partial removal from a consolidated path group. When one agent disconnects,
    // only that agent is removed from the group - the path remains registered with the
    // remaining agents. The path is only fully removed when the last agent disconnects.
    // This ensures continuous availability during rolling deployments or agent restarts.
    "removePath should remove one element from consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      val response = manager.removePath("/metrics", context1.agentId)

      response.valid.shouldBeTrue()
      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe context2.agentId
    }

    "getAgentContextInfo should return null for non-existent path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val info = manager.getAgentContextInfo("/nonexistent")

      info.shouldBeNull()
    }

    "pathMapSize should return correct count" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext()
      val context2 = createMockAgentContext()

      manager.pathMapSize shouldBe 0

      manager.addPath("/metrics1", """{"job":"test"}""", context1)
      manager.pathMapSize shouldBe 1

      manager.addPath("/metrics2", """{"job":"test"}""", context2)
      manager.pathMapSize shouldBe 2

      manager.removePath("/metrics1", context1.agentId)
      manager.pathMapSize shouldBe 1
    }

    "allPaths should return all registered paths" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics1", """{"job":"test"}""", context)
      manager.addPath("/metrics2", """{"job":"test"}""", context)
      manager.addPath("/metrics3", """{"job":"test"}""", context)

      val paths = manager.allPaths

      paths.shouldHaveSize(3)
      paths shouldContain "/metrics1"
      paths shouldContain "/metrics2"
      paths shouldContain "/metrics3"
    }

    "allPathContextInfos should atomically snapshot paths and their info" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics1", """{"job":"test1"}""", context1)
      manager.addPath("/metrics2", """{"job":"test2"}""", context2)

      val snapshot = manager.allPathContextInfos()

      // Should contain all paths with their info
      snapshot.mapShouldHaveSize(2)
      snapshot.keys shouldContain "/metrics1"
      snapshot.keys shouldContain "/metrics2"

      // Info should match what was registered
      val info1 = snapshot["/metrics1"]
      info1.shouldNotBeNull()
      info1.agentContexts.shouldHaveSize(1)
      info1.agentContexts[0].agentId shouldBe context1.agentId

      val info2 = snapshot["/metrics2"]
      info2.shouldNotBeNull()
      info2.agentContexts.shouldHaveSize(1)
      info2.agentContexts[0].agentId shouldBe context2.agentId

      // Removing a path after snapshot should not affect the snapshot
      manager.removePath("/metrics1", context1.agentId)
      snapshot.mapShouldHaveSize(2)
      snapshot["/metrics1"].shouldNotBeNull()

      // New snapshot should reflect the removal
      val snapshot2 = manager.allPathContextInfos()
      snapshot2.mapShouldHaveSize(1)
      snapshot2["/metrics1"].shouldBeNull()
      snapshot2["/metrics2"].shouldNotBeNull()
    }

    "removeFromPathManager should remove all paths for agent" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      every { proxy.agentContextManager.getAgentContext(context.agentId) } returns context

      manager.addPath("/metrics1", """{"job":"test"}""", context)
      manager.addPath("/metrics2", """{"job":"test"}""", context)
      manager.pathMapSize shouldBe 2

      manager.removeFromPathManager(context.agentId, "disconnect")

      manager.pathMapSize shouldBe 0
    }

    // ==================== removeFromPathManager Iteration Safety (Bug #4) ====================

    "removeFromPathManager should remove all paths when many paths registered for one agent" {
      // This test verifies that removeFromPathManager correctly removes ALL paths for a
      // disconnecting agent. The old code modified the map during forEach iteration, which
      // could cause ConcurrentHashMap's weakly consistent iterator to skip entries.
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      every { proxy.agentContextManager.getAgentContext(context.agentId) } returns context

      val pathCount = 50
      for (i in 1..pathCount) {
        manager.addPath("/metrics$i", """{"job":"test$i"}""", context)
      }
      manager.pathMapSize shouldBe pathCount

      manager.removeFromPathManager(context.agentId, "disconnect")

      manager.pathMapSize shouldBe 0
    }

    "removeFromPathManager should only remove paths for the specified agent" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val agent1 = createMockAgentContext()
      val agent2 = createMockAgentContext()

      every { proxy.agentContextManager.getAgentContext(agent1.agentId) } returns agent1
      every { proxy.agentContextManager.getAgentContext(agent2.agentId) } returns agent2

      // Register paths for both agents
      for (i in 1..20) {
        manager.addPath("/agent1_metrics$i", """{"job":"a1"}""", agent1)
      }
      for (i in 1..10) {
        manager.addPath("/agent2_metrics$i", """{"job":"a2"}""", agent2)
      }
      manager.pathMapSize shouldBe 30

      // Remove only agent1's paths
      manager.removeFromPathManager(agent1.agentId, "disconnect")

      // Only agent2's paths should remain
      manager.pathMapSize shouldBe 10
      for (i in 1..10) {
        manager.getAgentContextInfo("/agent2_metrics$i").shouldNotBeNull()
      }
      for (i in 1..20) {
        manager.getAgentContextInfo("/agent1_metrics$i").shouldBeNull()
      }
    }

    "removeFromPathManager should remove agent from consolidated paths without removing the path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val agent1 = createMockAgentContext(consolidated = true)
      val agent2 = createMockAgentContext(consolidated = true)

      every { proxy.agentContextManager.getAgentContext(agent1.agentId) } returns agent1

      // Both agents share consolidated paths
      for (i in 1..10) {
        manager.addPath("/shared$i", """{"job":"shared"}""", agent1)
        manager.addPath("/shared$i", """{"job":"shared"}""", agent2)
      }
      manager.pathMapSize shouldBe 10

      // Remove agent1 -- paths should remain with only agent2
      manager.removeFromPathManager(agent1.agentId, "disconnect")

      manager.pathMapSize shouldBe 10
      for (i in 1..10) {
        val info = manager.getAgentContextInfo("/shared$i")
        info.shouldNotBeNull()
        info.agentContexts.shouldHaveSize(1)
        info.agentContexts[0].agentId shouldBe agent2.agentId
      }
    }

    // ==================== AgentContextInfo.isNotValid() (Bug #8) ====================

    "non-consolidated path should be invalid when single agent is invalid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()
      every { context.isNotValid() } returns true

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isNotValid().shouldBeTrue()
    }

    "non-consolidated path should be valid when single agent is valid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()
      // isNotValid() returns false by default from createMockAgentContext

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isNotValid().shouldBeFalse()
    }

    // Bug #8: The old code had `fun isNotValid() = !isConsolidated && agentContexts[0].isNotValid()`
    // which always returned false for consolidated paths. This meant consolidated paths with all
    // invalid agents were still considered valid, causing requests to time out instead of getting
    // an immediate error response.
    "consolidated path should be invalid when all agents are invalid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)
      every { context1.isNotValid() } returns true
      every { context2.isNotValid() } returns true

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      // Before the fix, this returned false even with all agents invalid
      info.isNotValid().shouldBeTrue()
    }

    "consolidated path should be valid when at least one agent is valid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)
      every { context1.isNotValid() } returns true
      every { context2.isNotValid() } returns false

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      info.isNotValid().shouldBeFalse()
    }

    // ==================== toPlainText ====================

    "toPlainText should return message when no agents connected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val text = manager.toPlainText()

      text shouldBe "No agents connected."
    }

    "toPlainText should return formatted path map" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val text = manager.toPlainText()

      text shouldContain "Proxy Path Map"
      text shouldContain "/metrics"
    }

    // ==================== Consolidated/Non-Consolidated Mismatch Tests ====================

    // Bug #8: addPath now rejects consolidated/non-consolidated mismatch instead of
    // silently allowing mixed types, which could cause unexpected fan-out behavior.
    "addPath should reject consolidated agent on non-consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)
      val consolidatedContext = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", nonConsolidatedContext).shouldBeNull()
      // Consolidated agent should be rejected on a non-consolidated path
      manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext).shouldNotBeNull()

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      // Only the original non-consolidated agent should be present
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe nonConsolidatedContext.agentId
    }

    "addPath should reject non-consolidated agent on consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val consolidatedContext = createMockAgentContext(consolidated = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)

      // First register as consolidated
      manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext).shouldBeNull()
      // Non-consolidated should be rejected on a consolidated path
      manager.addPath("/metrics", """{"job":"test2"}""", nonConsolidatedContext).shouldNotBeNull()

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      // Original consolidated path should be unchanged
      info.isConsolidated.shouldBeTrue()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe consolidatedContext.agentId
    }

    // Bug #11: addPath returns a descriptive reason string on failure instead of just false
    "addPath should return reason containing 'Consolidated' when consolidated agent rejected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)
      val consolidatedContext = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", nonConsolidatedContext)
      val reason = manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext)

      reason.shouldNotBeNull()
      reason shouldContain "Consolidated"
      reason shouldContain "/metrics"
    }

    "addPath should return reason containing 'Non-consolidated' when non-consolidated agent rejected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val consolidatedContext = createMockAgentContext(consolidated = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)

      manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext)
      val reason = manager.addPath("/metrics", """{"job":"test2"}""", nonConsolidatedContext)

      reason.shouldNotBeNull()
      reason shouldContain "Non-consolidated"
      reason shouldContain "/metrics"
    }

    "addPath should return null on success" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      val result = manager.addPath("/metrics", """{"job":"test"}""", context)

      result.shouldBeNull()
    }

    // ==================== removeFromPathManager Edge Cases ====================

    "removeFromPathManager should throw when agentId is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.removeFromPathManager("", "test")
      }

      exception.message shouldContain "Empty agentId"
    }

    "removeFromPathManager should handle missing agent context gracefully" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      every { proxy.agentContextManager.getAgentContext("missing-agent") } returns null

      // Should not throw
      manager.removeFromPathManager("missing-agent", "disconnect")
    }

    // ==================== getAgentContextInfo Defensive Copy ====================

    "getAgentContextInfo should return a snapshot copy" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)

      // Take a snapshot
      val info1 = manager.getAgentContextInfo("/metrics")
      info1.shouldNotBeNull()
      info1.agentContexts.shouldHaveSize(1)

      // Mutate the path map by adding another agent
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      // Original snapshot should be unaffected
      info1.agentContexts.shouldHaveSize(1)

      // New retrieval should reflect the update
      val info2 = manager.getAgentContextInfo("/metrics")
      info2.shouldNotBeNull()
      info2.agentContexts.shouldHaveSize(2)
    }

    "getAgentContextInfo returned list mutation should not affect internal state" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(2)

      // Force-cast and mutate the returned list (simulates a caller bypassing compile-time safety)
      @Suppress("UNCHECKED_CAST")
      (info.agentContexts as MutableList<AgentContext>).clear()
      info.agentContexts.shouldHaveSize(0)

      // Internal state should be unaffected
      val info2 = manager.getAgentContextInfo("/metrics")
      info2.shouldNotBeNull()
      info2.agentContexts.shouldHaveSize(2)
    }

    // ==================== M1: agentContexts typed as MutableList ====================

    // M1: The agentContexts field in AgentContextInfo was typed as List<AgentContext> but
    // was unsafely cast to MutableList at three call sites (addPath, removePath,
    // removeFromPathManager). This relied on the implementation detail that the list was
    // created with mutableListOf(). The fix changed the type to MutableList<AgentContext>
    // to make the mutable usage explicit and eliminate the unsafe casts.
    "consolidated addPath should not require unsafe cast to MutableList" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val contexts = (1..5).map { createMockAgentContext(consolidated = true) }

      // First agent creates the path
      manager.addPath("/metrics", """{"job":"test"}""", contexts[0])

      // Subsequent agents append to the consolidated list — previously required
      // (agentInfo.agentContexts as MutableList) += agentContext
      for (i in 1 until contexts.size) {
        manager.addPath("/metrics", """{"job":"test"}""", contexts[i])
      }

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(5)
      contexts.forEach { ctx ->
        info.agentContexts.map { it.agentId } shouldContain ctx.agentId
      }
    }

    "removePath from consolidated group should not require unsafe cast to MutableList" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)
      val context3 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)
      manager.addPath("/metrics", """{"job":"test"}""", context3)

      // Remove middle agent — previously required
      // (agentInfo.agentContexts as MutableList).remove(agentContext)
      val response = manager.removePath("/metrics", context2.agentId)
      response.valid.shouldBeTrue()

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(2)
      info.agentContexts.map { it.agentId } shouldContain context1.agentId
      info.agentContexts.map { it.agentId } shouldContain context3.agentId
    }

    "removeFromPathManager on consolidated paths should not require unsafe cast to MutableList" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val agent1 = createMockAgentContext(consolidated = true)
      val agent2 = createMockAgentContext(consolidated = true)

      every { proxy.agentContextManager.getAgentContext(agent1.agentId) } returns agent1

      manager.addPath("/metrics", """{"job":"test"}""", agent1)
      manager.addPath("/metrics", """{"job":"test"}""", agent2)

      // Remove agent1 via disconnect path — previously required
      // (v.agentContexts as MutableList).removeIf { it.agentId == agentId }
      manager.removeFromPathManager(agent1.agentId, "disconnect")

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe agent2.agentId
    }

    // Bug #7: addPath logged agentContexts[0] without an empty-list guard when
    // overwriting a non-consolidated path. The fix uses firstOrNull() to prevent a
    // potential IndexOutOfBoundsException. This test exercises the overwrite log path
    // with many rapid overwrites to verify it is safe.
    "rapid non-consolidated overwrites should not crash" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = false)

      // Register and overwrite the same non-consolidated path many times in
      // succession. Each overwrite hits the log line that previously used [0].
      repeat(20) { i ->
        val context = createMockAgentContext()
        manager.addPath("/metrics", """{"job":"test-$i"}""", context)
      }

      // After all overwrites, only the last registration should remain
      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
    }

    // ==================== toPlainText with Multiple Paths ====================

    "toPlainText should format paths with different lengths correctly" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      manager.addPath("/a", """{}""", createMockAgentContext())
      manager.addPath("/very-long-metrics-path", """{}""", createMockAgentContext())
      manager.addPath("/medium", """{}""", createMockAgentContext())

      val text = manager.toPlainText()

      text shouldContain "Proxy Path Map"
      text shouldContain "/a"
      text shouldContain "/very-long-metrics-path"
      text shouldContain "/medium"
    }

    // ==================== AgentContextInfo Tests ====================

    "AgentContextInfo toString should include key fields" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()

      val str = info.toString()
      str shouldContain "AgentContextInfo"
      str shouldContain "isConsolidated"
      str shouldContain "labels"
    }

    // ==================== Bug #6: Displaced Agent Invalidation Tests ====================

    // Bug #6: When a non-consolidated agent overwrites a path, the old agent context(s)
    // were left orphaned — still in the agentContextManager but with no paths in the
    // pathMap. The fix invalidates displaced agents that have no other registered paths.

    "overwriting non-consolidated path should invalidate displaced agent that is already disconnected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      // Use real AgentContexts so invalidate() actually works
      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      oldAgent.isValid().shouldBeTrue()

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Simulate the old agent's connection dying before the overwrite
      oldAgent.invalidate()
      oldAgent.isValid().shouldBeFalse()

      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent was already invalid and had no other paths — invalidate is a no-op but safe
      oldAgent.isValid().shouldBeFalse()
      // New agent remains valid
      newAgent.isValid().shouldBeTrue()

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe newAgent.agentId
    }

    // Orphan invalidation fix: A live (valid) agent displaced from a path should NOT be
    // invalidated, because it may still be mid-registration for additional paths.
    // Only dead (isNotValid) agents are invalidated on displacement.
    "overwriting path should not invalidate displaced agent that is still connected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      oldAgent.isValid().shouldBeTrue()

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Old agent is still connected (valid) — simulates mid-registration
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent should NOT be invalidated because its connection is still alive
      oldAgent.isValid().shouldBeTrue()
      newAgent.isValid().shouldBeTrue()

      // Path should now belong to the new agent
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe newAgent.agentId
    }

    "overwriting path should not invalidate displaced agent that has other paths" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      // Old agent has two paths
      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)
      manager.addPath("/health", """{"job":"test"}""", oldAgent)

      oldAgent.isValid().shouldBeTrue()

      // Overwrite only /metrics
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent should still be valid because it still has /health
      oldAgent.isValid().shouldBeTrue()
      newAgent.isValid().shouldBeTrue()

      // /metrics now points to newAgent
      val metricsInfo = manager.getAgentContextInfo("/metrics")
      metricsInfo.shouldNotBeNull()
      metricsInfo.agentContexts[0].agentId shouldBe newAgent.agentId

      // /health still points to oldAgent
      val healthInfo = manager.getAgentContextInfo("/health")
      healthInfo.shouldNotBeNull()
      healthInfo.agentContexts[0].agentId shouldBe oldAgent.agentId
    }

    "non-consolidated overwrite of consolidated path should be rejected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val consolidated1 = AgentContext("remote-c1")
      consolidated1.assignProperties(
        mockk<RegisterAgentRequest> {
          every { launchId } returns "l1"
          every { agentName } returns "c1"
          every { hostName } returns "h1"
          every { consolidated } returns true
        },
      )
      val consolidated2 = AgentContext("remote-c2")
      consolidated2.assignProperties(
        mockk<RegisterAgentRequest> {
          every { launchId } returns "l2"
          every { agentName } returns "c2"
          every { hostName } returns "h2"
          every { consolidated } returns true
        },
      )
      val newAgent = AgentContext("remote-new")

      // Two consolidated agents share a path
      manager.addPath("/metrics", """{"job":"test"}""", consolidated1)
      manager.addPath("/metrics", """{"job":"test"}""", consolidated2)

      consolidated1.isValid().shouldBeTrue()
      consolidated2.isValid().shouldBeTrue()

      // Non-consolidated agent should be rejected (Bug #8 fix)
      manager.addPath("/metrics", """{"job":"test"}""", newAgent).shouldNotBeNull()

      // Consolidated agents should remain valid and unchanged
      consolidated1.isValid().shouldBeTrue()
      consolidated2.isValid().shouldBeTrue()

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      info.agentContexts.shouldHaveSize(2)
    }

    "overwriting should invalidate displaced dead agents with backlog and drain it" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Build up backlog on old agent
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.scrapeRequestBacklogSize shouldBe 2

      // Simulate the old agent's connection dying
      oldAgent.invalidate()
      oldAgent.isValid().shouldBeFalse()
      oldAgent.scrapeRequestBacklogSize shouldBe 0

      // Overwrite the path — old agent is already dead, invalidation is safe
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      oldAgent.isValid().shouldBeFalse()
    }

    "overwriting should not invalidate displaced live agent even with backlog" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Build up backlog on old agent
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.scrapeRequestBacklogSize shouldBe 2

      // Old agent is still connected — overwrite should NOT invalidate
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent should still be valid (not invalidated)
      oldAgent.isValid().shouldBeTrue()
      // Backlog should still be 2 (not drained)
      oldAgent.scrapeRequestBacklogSize shouldBe 2
    }
  }
}
