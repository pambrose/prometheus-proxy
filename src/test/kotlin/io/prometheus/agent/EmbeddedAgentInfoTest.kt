/*
 * Copyright © 2026 Paul Ambrose
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

import ch.qos.logback.classic.Level
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.common.captureLogs
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EmbeddedAgentInfoTest : StringSpec() {
  init {
    "should expose launchId from agent" {
      val agent = mockk<Agent>()
      every { agent.launchId } returns "launch-123"
      every { agent.agentName } returns "test-agent"

      val info = EmbeddedAgentInfo(agent)

      info.launchId shouldBe "launch-123"
    }

    "should expose agentName from agent" {
      val agent = mockk<Agent>()
      every { agent.agentName } returns "test-agent"

      val info = EmbeddedAgentInfo(agent)

      info.agentName shouldBe "test-agent"
    }

    "should expose the agent's connection, live" {
      val agent = mockk<Agent>()
      every { agent.isConnectedToProxy } returnsMany listOf(true, false)
      every { agent.connectedProxyEndpoint } returnsMany listOf("proxy.example.com:50051", null)

      val info = EmbeddedAgentInfo(agent)

      info.isConnected shouldBe true
      info.proxyEndpoint shouldBe "proxy.example.com:50051"
      info.isConnected shouldBe false
      info.proxyEndpoint shouldBe null
    }

    "shutdown should call agent stop" {
      val agent = mockk<Agent>(relaxed = true)

      val info = EmbeddedAgentInfo(agent)
      info.shutdown()

      verify { agent.stop() }
    }

    // AutoCloseable, so Kotlin's use and Java's try-with-resources stop the agent after their block
    // So an app's shutdown fits its platform's window: the agent goes on stopping on a thread of its own
    "shutdown with a stop timeout returns once it's up, while the agent still stops" {
      val agent = mockk<Agent>(relaxed = true)
      val release = CountDownLatch(1)
      every { agent.stop() } answers { release.await() }

      EmbeddedAgentInfo(agent, stopTimeout = 50.milliseconds).shutdown()

      // Returned while the agent's stop still blocks
      release.count shouldBe 1
      release.countDown()
    }

    "shutdown with a stop timeout waits for a stop that ends within it" {
      val agent = mockk<Agent>(relaxed = true)
      var stopped = false
      every { agent.stop() } answers {
        Thread.sleep(50)
        stopped = true
      }

      EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown()

      stopped shouldBe true
    }

    "shutdown with a stop timeout passes on the failure of a stop that ends within it" {
      val agent = mockk<Agent>(relaxed = true)
      every { agent.stop() } throws IllegalStateException("stuck")

      shouldThrow<IllegalStateException> {
        EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown()
      }.message shouldBe "stuck"
    }

    // shutdown has returned by then, so the log is the only place it can go
    "a stop that fails after the stop timeout is up is logged" {
      val agent = mockk<Agent>(relaxed = true)
      every { agent.agentName } returns "test-agent"
      val release = CountDownLatch(1)
      every { agent.stop() } answers {
        release.await()
        throw IllegalStateException("stuck")
      }

      val events =
        captureLogs<EmbeddedAgentInfo>(Level.DEBUG) {
          EmbeddedAgentInfo(agent, stopTimeout = 50.milliseconds).shutdown()
          release.countDown()
          // The stop's thread logs before it ends
          eventually(5.seconds) {
            Thread.getAllStackTraces().keys.none { it.name == "prometheus-agent-stop" } shouldBe true
          }
        }

      events.map { it.level to it.formattedMessage } shouldBe
        listOf(
          Level.WARN to
            "The Prometheus agent test-agent was still stopping after 50ms, and goes on stopping on its own",
          Level.WARN to "The Prometheus agent test-agent failed as it went on stopping: stuck",
        )
    }

    "use should stop the agent after its block, and run the block on a null handle" {
      val agent = mockk<Agent>(relaxed = true)
      val ran = mutableListOf<String>()

      EmbeddedAgentInfo(agent).use { ran += "with an agent" }
      val noAgent: EmbeddedAgentInfo? = null
      noAgent.use { ran += "without one" }

      ran shouldBe listOf("with an agent", "without one")
      verify(exactly = 1) { agent.stop() }
    }
  }
}
