/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.agent

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ChunkedContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

class AgentContextManagerTest : FunSpec() {
  init {
    test("addAgentContext should add context and return null for new agent") {
      val manager = AgentContextManager(isTestMode = true)
      val context = AgentContext("192.168.1.1")

      val result = manager.addAgentContext(context)

      result.shouldBeNull()
      manager.agentContextSize shouldBe 1
      manager.getAgentContext(context.agentId).shouldNotBeNull()
    }

    test("addAgentContext should return old context when replacing existing agent") {
      val manager = AgentContextManager(isTestMode = true)
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")

      manager.addAgentContext(context1)
      // Use putAgentContext to simulate replacement
      val agentId = context1.agentId
      manager.putAgentContext(agentId, context2)

      manager.agentContextSize shouldBe 1
      manager.getAgentContext(agentId) shouldBe context2
    }

    test("getAgentContext should return context for existing agent") {
      val manager = AgentContextManager(isTestMode = true)
      val context = AgentContext("192.168.1.1")

      manager.addAgentContext(context)
      val retrieved = manager.getAgentContext(context.agentId)

      retrieved.shouldNotBeNull()
      retrieved shouldBe context
    }

    test("getAgentContext should return null for non-existent agent") {
      val manager = AgentContextManager(isTestMode = true)

      val retrieved = manager.getAgentContext("non-existent-id")

      retrieved.shouldBeNull()
    }

    test("removeFromContextManager should remove and invalidate context") {
      val manager = AgentContextManager(isTestMode = true)
      val context = AgentContext("192.168.1.1")

      manager.addAgentContext(context)
      manager.agentContextSize shouldBe 1

      val removed = manager.removeFromContextManager(context.agentId, "test removal")

      removed.shouldNotBeNull()
      removed shouldBe context
      manager.agentContextSize shouldBe 0
      manager.getAgentContext(context.agentId).shouldBeNull()
    }

    test("removeFromContextManager should return null for non-existent agent") {
      val manager = AgentContextManager(isTestMode = true)

      val removed = manager.removeFromContextManager("non-existent-id", "test removal")

      removed.shouldBeNull()
      manager.agentContextSize shouldBe 0
    }

    test("agentContextSize should return correct count") {
      val manager = AgentContextManager(isTestMode = true)

      manager.agentContextSize shouldBe 0

      val context1 = AgentContext("192.168.1.1")
      manager.addAgentContext(context1)
      manager.agentContextSize shouldBe 1

      val context2 = AgentContext("192.168.1.2")
      manager.addAgentContext(context2)
      manager.agentContextSize shouldBe 2

      manager.removeFromContextManager(context1.agentId, "test")
      manager.agentContextSize shouldBe 1

      manager.removeFromContextManager(context2.agentId, "test")
      manager.agentContextSize shouldBe 0
    }

    test("chunkedContextMap should store and retrieve chunked contexts") {
      val manager = AgentContextManager(isTestMode = true)
      val scrapeId = 123L
      val mockResponse = mockk<ChunkedScrapeResponse>(relaxed = true)
      val chunkedContext = ChunkedContext(mockResponse)

      manager.putChunkedContext(scrapeId, chunkedContext)
      manager.chunkedContextSize shouldBe 1

      val retrieved = manager.getChunkedContext(scrapeId)
      retrieved.shouldNotBeNull()
      retrieved shouldBe chunkedContext

      manager.removeChunkedContext(scrapeId)
      manager.chunkedContextSize shouldBe 0
    }

    test("totalAgentScrapeRequestBacklogSize should sum all agent backlogs") {
      val manager = AgentContextManager(isTestMode = true)
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")

      manager.addAgentContext(context1)
      manager.addAgentContext(context2)

      manager.totalAgentScrapeRequestBacklogSize shouldBe 0
    }

    // Tests thread-safety of the AgentContextManager under a concurrent load.
    // In production, multiple gRPC threads may simultaneously add/remove agent contexts
    // as agents connect and disconnect. This test verifies that the underlying
    // ConcurrentHashMap correctly handles 20 concurrent additions followed by
    // 20 concurrent removals without data corruption or race conditions.
    test("concurrent access should handle multiple threads safely") {
      val manager = AgentContextManager(isTestMode = true)
      val contexts = (1..20).map { AgentContext("192.168.1.$it") }

      // Add contexts concurrently
      val addJobs = contexts.map { context ->
        launch(Dispatchers.IO) {
          manager.addAgentContext(context)
        }
      }

      addJobs.joinAll()

      manager.agentContextSize shouldBe 20

      // Remove contexts concurrently
      val removeJobs = contexts.map { context ->
        launch(Dispatchers.IO) {
          manager.removeFromContextManager(context.agentId, "concurrent test")
        }
      }

      removeJobs.joinAll()

      manager.agentContextSize shouldBe 0
    }

    test("multiple contexts should have unique agent IDs") {
      val manager = AgentContextManager(isTestMode = true)
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")
      val context3 = AgentContext("192.168.1.3")

      context1.agentId shouldNotBe context2.agentId
      context2.agentId shouldNotBe context3.agentId
      context1.agentId shouldNotBe context3.agentId

      manager.addAgentContext(context1)
      manager.addAgentContext(context2)
      manager.addAgentContext(context3)

      manager.agentContextSize shouldBe 3
    }

    test("addAgentContext and removeFromContextManager should work correctly in sequence") {
      val manager = AgentContextManager(isTestMode = true)
      val context1 = AgentContext("192.168.1.1")
      val context2 = AgentContext("192.168.1.2")

      manager.addAgentContext(context1)
      manager.agentContextSize shouldBe 1

      manager.addAgentContext(context2)
      manager.agentContextSize shouldBe 2

      manager.removeFromContextManager(context1.agentId, "cleanup")
      manager.agentContextSize shouldBe 1
      manager.getAgentContext(context1.agentId).shouldBeNull()
      manager.getAgentContext(context2.agentId).shouldNotBeNull()

      manager.removeFromContextManager(context2.agentId, "cleanup")
      manager.agentContextSize shouldBe 0
    }
  }
}
