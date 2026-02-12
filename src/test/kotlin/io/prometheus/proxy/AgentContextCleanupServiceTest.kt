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

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals
import kotlin.time.Duration.Companion.seconds

// Tests for AgentContextCleanupService which evicts inactive agents from the proxy.
// The service runs periodically and removes agents that haven't had activity
// within the configured maxAgentInactivitySecs threshold.
class AgentContextCleanupServiceTest : FunSpec() {
  private fun createConfigVals(
    maxAgentInactivitySecs: Int = 60,
    staleAgentCheckPauseSecs: Int = 10,
  ): ConfigVals.Proxy2.Internal2 {
    val overrideConfig = ConfigFactory.parseString(
      """
      proxy {
        internal {
          maxAgentInactivitySecs = $maxAgentInactivitySecs
          staleAgentCheckPauseSecs = $staleAgentCheckPauseSecs
        }
      }
      """.trimIndent(),
    )
    // Merge with reference config to get all required defaults
    val config = overrideConfig.withFallback(ConfigFactory.load())
    return ConfigVals(config).proxy.internal
  }

  init {
    // ==================== Configuration Tests ====================

    test("toString should include max inactivity seconds") {
      val configVals = createConfigVals(maxAgentInactivitySecs = 120)
      val agentContextManager = AgentContextManager(isTestMode = true)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager

      val service = AgentContextCleanupService(mockProxy, configVals)

      val str = service.toString()
      str shouldContain "max inactivity secs"
      str shouldContain "120"
    }

    test("toString should include pause seconds") {
      val configVals = createConfigVals(staleAgentCheckPauseSecs = 30)
      val agentContextManager = AgentContextManager(isTestMode = true)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager

      val service = AgentContextCleanupService(mockProxy, configVals)

      val str = service.toString()
      str shouldContain "pause secs"
      str shouldContain "30"
    }

    // ==================== Eviction Logic Tests ====================

    test("cleanup should evict agent with inactivity exceeding threshold") {
      val configVals = createConfigVals(
        maxAgentInactivitySecs = 1,
        staleAgentCheckPauseSecs = 1,
      )

      // Create a real AgentContextManager with a stale agent context
      val agentContextManager = AgentContextManager(isTestMode = true)

      // Mock an agent context that appears stale
      val staleAgentContext = mockk<AgentContext>(relaxed = true)
      every { staleAgentContext.agentId } returns "stale-agent-123"
      every { staleAgentContext.inactivityDuration } returns 5.seconds

      // Add stale context directly to the map
      agentContextManager.putAgentContext("stale-agent-123", staleAgentContext)

      val mockMetrics = mockk<ProxyMetrics>(relaxed = true)
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager
      every { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers {
        val block = firstArg<ProxyMetrics.() -> Unit>()
        block(mockMetrics)
      }
      every { mockProxy.removeAgentContext(any(), any()) } answers {
        agentContextManager.removeFromContextManager(firstArg<String>(), secondArg<String>())
      }

      val service = AgentContextCleanupService(mockProxy, configVals)

      // Start and let one cleanup cycle run
      service.startAsync()
      Thread.sleep(1500) // Allow at least one cleanup cycle
      service.stopAsync().awaitTerminated()

      // Verify the stale agent was removed
      verify(atLeast = 1) { mockProxy.removeAgentContext("stale-agent-123", "Eviction") }
    }

    test("cleanup should not evict active agent") {
      val configVals = createConfigVals(
        maxAgentInactivitySecs = 60,
        staleAgentCheckPauseSecs = 1,
      )

      val agentContextManager = AgentContextManager(isTestMode = true)

      // Create an agent with recent activity (within threshold)
      val activeAgentContext = mockk<AgentContext>(relaxed = true)
      every { activeAgentContext.agentId } returns "active-agent-123"
      every { activeAgentContext.inactivityDuration } returns 5.seconds

      agentContextManager.putAgentContext("active-agent-123", activeAgentContext)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager

      val service = AgentContextCleanupService(mockProxy, configVals)

      // Start and quickly stop
      service.startAsync()
      Thread.sleep(1500)
      service.stopAsync().awaitTerminated()

      // Verify the active agent was NOT removed
      verify(exactly = 0) { mockProxy.removeAgentContext("active-agent-123", any()) }
    }

    // ==================== Service Lifecycle Tests ====================

    test("service should be stoppable") {
      val configVals = createConfigVals()
      val agentContextManager = AgentContextManager(isTestMode = true)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager

      val service = AgentContextCleanupService(mockProxy, configVals)

      service.startAsync().awaitRunning()
      service.isRunning shouldBe true

      service.stopAsync().awaitTerminated()
    }

    test("service init block should be called during construction") {
      val configVals = createConfigVals()
      val agentContextManager = AgentContextManager(isTestMode = true)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager

      var initBlockCalled = false

      val service = AgentContextCleanupService(mockProxy, configVals) {
        initBlockCalled = true
      }

      initBlockCalled shouldBe true
    }

    // ==================== Empty Agent Map Tests ====================

    test("cleanup should handle empty agent context map gracefully") {
      val configVals = createConfigVals(staleAgentCheckPauseSecs = 1)
      val agentContextManager = AgentContextManager(isTestMode = true)
      // Leave the map empty

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager

      val service = AgentContextCleanupService(mockProxy, configVals)

      // Should not throw even with empty map
      service.startAsync()
      Thread.sleep(1500)
      service.stopAsync().awaitTerminated()

      // Verify no removal was attempted
      verify(exactly = 0) { mockProxy.removeAgentContext(any(), any()) }
    }

    // ==================== Bug #20: Prompt shutdown (no blocking sleep) ====================

    test("service should stop promptly without waiting for full pause duration") {
      // Use a very long pause time so that blocking sleep would be obvious
      val configVals = createConfigVals(staleAgentCheckPauseSecs = 60)
      val agentContextManager = AgentContextManager(isTestMode = true)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager

      val service = AgentContextCleanupService(mockProxy, configVals)

      service.startAsync().awaitRunning()

      // Give the service thread time to enter the wait
      Thread.sleep(200)

      val stopStart = System.currentTimeMillis()
      service.stopAsync().awaitTerminated()
      val stopDuration = System.currentTimeMillis() - stopStart

      // Before the fix, this would block for up to 60 seconds.
      // With the CountDownLatch fix, it should stop within a few seconds.
      stopDuration shouldBeLessThan 5000L
    }

    // ==================== Multiple Stale Agents Test ====================

    test("cleanup should evict multiple stale agents in single cycle") {
      val configVals = createConfigVals(
        maxAgentInactivitySecs = 1,
        staleAgentCheckPauseSecs = 1,
      )

      val agentContextManager = AgentContextManager(isTestMode = true)

      // Create multiple stale agents
      val staleAgent1 = mockk<AgentContext>(relaxed = true)
      every { staleAgent1.agentId } returns "stale-1"
      every { staleAgent1.inactivityDuration } returns 5.seconds

      val staleAgent2 = mockk<AgentContext>(relaxed = true)
      every { staleAgent2.agentId } returns "stale-2"
      every { staleAgent2.inactivityDuration } returns 10.seconds

      val staleAgent3 = mockk<AgentContext>(relaxed = true)
      every { staleAgent3.agentId } returns "stale-3"
      every { staleAgent3.inactivityDuration } returns 15.seconds

      agentContextManager.putAgentContext("stale-1", staleAgent1)
      agentContextManager.putAgentContext("stale-2", staleAgent2)
      agentContextManager.putAgentContext("stale-3", staleAgent3)

      val mockMetrics = mockk<ProxyMetrics>(relaxed = true)
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.agentContextManager } returns agentContextManager
      every { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers {
        val block = firstArg<ProxyMetrics.() -> Unit>()
        block(mockMetrics)
      }
      every { mockProxy.removeAgentContext(any(), any()) } answers {
        agentContextManager.removeFromContextManager(firstArg<String>(), secondArg<String>())
      }

      val service = AgentContextCleanupService(mockProxy, configVals)

      service.startAsync()
      Thread.sleep(1500)
      service.stopAsync().awaitTerminated()

      // All three stale agents should have been evicted
      verify(atLeast = 1) { mockProxy.removeAgentContext("stale-1", "Eviction") }
      verify(atLeast = 1) { mockProxy.removeAgentContext("stale-2", "Eviction") }
      verify(atLeast = 1) { mockProxy.removeAgentContext("stale-3", "Eviction") }
    }
  }
}
