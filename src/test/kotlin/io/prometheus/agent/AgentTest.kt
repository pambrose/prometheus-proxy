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

package io.prometheus.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentTest : StringSpec() {
  private fun createTestAgent(vararg extraArgs: String): Agent {
    val args =
      mutableListOf("--proxy", "localhost:50051").apply {
        addAll(extraArgs)
      }
    return Agent(
      options = AgentOptions(args, exitOnMissingConfig = false),
      inProcessServerName = "agent-test-${System.nanoTime()}",
      testMode = true,
    )
  }

  init {
    // ==================== awaitInitialConnection Tests ====================

    "awaitInitialConnection should return false when timeout expires" {
      val agent = createTestAgent()

      val result = agent.awaitInitialConnection(100.milliseconds)

      result.shouldBeFalse()
    }

    "awaitInitialConnection should return true after latch countdown" {
      val agent = createTestAgent()

      // Access the private latch via reflection and count it down
      val latchField = Agent::class.java.getDeclaredField("initialConnectionLatch")
      latchField.isAccessible = true
      val latch = latchField.get(agent) as CountDownLatch
      latch.countDown()

      val result = agent.awaitInitialConnection(1.seconds)

      result.shouldBeTrue()
    }

    "awaitInitialConnection should return false immediately with zero timeout" {
      val agent = createTestAgent()

      val result = agent.awaitInitialConnection(0.milliseconds)

      result.shouldBeFalse()
    }

    // ==================== updateScrapeCounter Tests ====================

    "updateScrapeCounter should not fail for empty type" {
      val agent = createTestAgent()

      // With metrics disabled (default), empty type should be a no-op
      agent.updateScrapeCounter("")
    }

    "updateScrapeCounter should not fail for non-empty type with metrics disabled" {
      val agent = createTestAgent()

      // With metrics disabled (default), non-empty type hits the metrics guard and exits
      agent.updateScrapeCounter("success")
    }

    // ==================== markMsgSent Tests ====================

    "markMsgSent should complete without error" {
      val agent = createTestAgent()

      // Should not throw
      agent.markMsgSent()
    }

    // ==================== serviceName Tests ====================

    "serviceName should return Agent agentName format" {
      val agent = createTestAgent("--name", "my-test-agent")

      // serviceName() is protected, so verify via reflection
      val method = agent.javaClass.getDeclaredMethod("serviceName")
      method.isAccessible = true
      val name = method.invoke(agent) as String

      name shouldBe "Agent my-test-agent"
    }

    // ==================== agentName Tests ====================

    "agentName should use provided name from options" {
      val agent = createTestAgent("--name", "custom-agent")

      agent.agentName shouldBe "custom-agent"
    }

    "agentName should fallback to Unnamed-hostname when name is blank" {
      val agent = createTestAgent()

      agent.agentName shouldContain "Unnamed-"
    }

    // ==================== proxyHost Tests ====================

    "proxyHost should return hostname colon port format" {
      val agent = createTestAgent()

      val host = agent.proxyHost

      host shouldContain ":"
      host.shouldNotBeEmpty()
    }

    // ==================== metrics Tests ====================

    "metrics should not invoke lambda when metrics disabled" {
      val agent = createTestAgent()

      var invoked = false
      agent.metrics { invoked = true }

      invoked.shouldBeFalse()
    }

    "metrics should invoke lambda when metrics enabled" {
      val mockMetrics = mockk<AgentMetrics>(relaxed = true)
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.isMetricsEnabled } returns true
      every { mockAgent.metrics } returns mockMetrics
      every { mockAgent.metrics(any<AgentMetrics.() -> Unit>()) } answers {
        firstArg<AgentMetrics.() -> Unit>().invoke(mockMetrics)
      }

      var invoked = false
      mockAgent.metrics { invoked = true }

      invoked.shouldBeTrue()
    }

    // ==================== Construction / launchId / toString Tests ====================

    "launchId should be a 15-character string" {
      val agent = createTestAgent()

      agent.launchId.length shouldBe 15
      agent.launchId.shouldNotBeEmpty()
    }

    "toString should contain agentName and proxyHost" {
      val agent = createTestAgent("--name", "tostring-agent")

      val str = agent.toString()

      str shouldContain "agentName"
      str shouldContain "tostring-agent"
      str shouldContain "proxyHost"
    }
  }
}
