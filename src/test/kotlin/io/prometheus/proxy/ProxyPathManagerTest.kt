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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProxyPathManagerTest {
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

  @Test
  fun `addPath should add new path successfully`(): Unit =
    runBlocking {
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

  @Test
  fun `addPath should throw when path is empty`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      val exception = assertThrows<IllegalArgumentException> {
        manager.addPath("", """{"job":"test"}""", context)
      }

      exception.message shouldContain "Empty path"
    }

  @Test
  fun `addPath should overwrite non-consolidated path`(): Unit =
    runBlocking {
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

  @Test
  fun `addPath should append to consolidated path`(): Unit =
    runBlocking {
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

  @Test
  fun `removePath should remove path successfully`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)
      manager.pathMapSize shouldBe 1

      val response = manager.removePath("/metrics", context.agentId)

      response.valid.shouldBeTrue()
      manager.pathMapSize shouldBe 0
    }

  @Test
  fun `removePath should fail when path not found`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val response = manager.removePath("/nonexistent", "agent-123")

      response.valid.shouldBeFalse()
      response.reason shouldContain "path not found"
    }

  @Test
  fun `removePath should fail when agent ID mismatch`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val response = manager.removePath("/metrics", "wrong-agent-id")

      response.valid.shouldBeFalse()
      response.reason shouldContain "invalid agentId"
    }

  @Test
  fun `removePath should throw when path is empty`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = assertThrows<IllegalArgumentException> {
        manager.removePath("", "agent-123")
      }

      exception.message shouldContain "Empty path"
    }

  @Test
  fun `removePath should throw when agentId is empty`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = assertThrows<IllegalArgumentException> {
        manager.removePath("/metrics", "")
      }

      exception.message shouldContain "Empty agentId"
    }

  @Test
  fun `removePath should remove one element from consolidated path`(): Unit =
    runBlocking {
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

  @Test
  fun `getAgentContextInfo should return null for non-existent path`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val info = manager.getAgentContextInfo("/nonexistent")

      info.shouldBeNull()
    }

  @Test
  fun `pathMapSize should return correct count`(): Unit =
    runBlocking {
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

  @Test
  fun `allPaths should return all registered paths`(): Unit =
    runBlocking {
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

  @Test
  fun `removeFromPathManager should remove all paths for agent`(): Unit =
    runBlocking {
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

  @Test
  fun `toPlainText should return message when no agents connected`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val text = manager.toPlainText()

      text shouldBe "No agents connected."
    }

  @Test
  fun `toPlainText should return formatted path map`(): Unit =
    runBlocking {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val text = manager.toPlainText()

      text shouldContain "Proxy Path Map"
      text shouldContain "/metrics"
    }
}
