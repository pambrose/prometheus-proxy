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
import com.google.common.util.concurrent.Service
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.common.captureLogs
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
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

    // Guava's stopSync() throws a checked TimeoutException, which Kotlin doesn't declare, so a Java caller couldn't
    // catch it, and a catch of RuntimeException missed it (November 26 review, #21). Unchecked, as a start that times
    // out is, with the timeout as its cause.
    "shutdown without a stop timeout throws an unchecked exception for a stop that outlasts the agent's 30 seconds" {
      val agent = mockk<Agent>()
      every { agent.state() } returns Service.State.RUNNING
      every { agent.stopSync(any<Duration>()) } throws TimeoutException("Timed out waiting for Agent [STOPPING]")
      every { agent.stop() } answers { callOriginal() }

      val thrown = shouldThrow<IllegalStateException> { EmbeddedAgentInfo(agent).shutdown() }

      thrown.message shouldBe "The agent didn't stop within 30 seconds; it goes on stopping"
      thrown.cause.shouldBeInstanceOf<TimeoutException>()
    }

    // So an app's shutdown fits its platform's window: the agent goes on stopping on a thread of its own. The stop is
    // released however the spec ends, so its thread isn't left parked.
    "shutdown with a stop timeout returns once it's up, while the agent still stops" {
      val agent = runningAgent()
      val stopping = CountDownLatch(1)
      val release = CountDownLatch(1)
      every { agent.awaitTerminated() } answers {
        stopping.countDown()
        release.await()
      }

      try {
        EmbeddedAgentInfo(agent, stopTimeout = 50.milliseconds).shutdown()

        // Returned while the agent's stop, which it began, still blocks
        stopping.await(5, TimeUnit.SECONDS) shouldBe true
        release.count shouldBe 1
      } finally {
        release.countDown()
      }
    }

    "shutdown with a stop timeout waits for a stop that ends within it" {
      val agent = runningAgent()
      var stopped = false
      every { agent.awaitTerminated() } answers {
        Thread.sleep(50)
        stopped = true
      }

      EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown()

      stopped shouldBe true
    }

    // stop() waits 30 seconds at most, then throws, which would cut a longer stop timeout short and throw out of
    // shutdown, so the handle's thread stops the agent without that limit, leaving the stop timeout the only one
    "shutdown with a stop timeout stops the agent without stop()'s own limit" {
      val agent = runningAgent()

      EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown()

      verify(exactly = 1) {
        agent.stopAsync()
        agent.awaitTerminated()
      }
      verify(exactly = 0) {
        agent.stop()
        agent.awaitTerminated(any(), any())
      }
    }

    // As stop() does: a failed agent has already released what it held, and Guava rejects its stop
    "shutdown with a stop timeout stops nothing of a failed agent" {
      val agent = runningAgent()
      every { agent.state() } returns Service.State.FAILED

      EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown()

      verify(exactly = 0) { agent.stopAsync() }
    }

    // Duration.INFINITE waits for as long as the stop takes, with no deadline that overflows
    "shutdown with an infinite stop timeout waits for the stop" {
      val agent = runningAgent()
      var stopped = false
      every { agent.awaitTerminated() } answers {
        Thread.sleep(50)
        stopped = true
      }

      EmbeddedAgentInfo(agent, stopTimeout = Duration.INFINITE).shutdown()

      stopped shouldBe true
    }

    // As stop() does, through Guava's awaitTerminated: a caller already interrupted, as a worker loop that ended on an
    // interrupt is, still waits for the stop, and keeps its interrupt for what comes after
    "shutdown with a stop timeout waits through an interrupt, and keeps it" {
      val agent = runningAgent()
      var stopped = false
      every { agent.awaitTerminated() } answers {
        Thread.sleep(50)
        stopped = true
      }

      Thread.currentThread().interrupt()
      val interrupted =
        try {
          EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown()
          Thread.currentThread().isInterrupted
        } finally {
          Thread.interrupted()
        }

      stopped shouldBe true
      interrupted shouldBe true
    }

    "shutdown with a stop timeout passes on the failure of a stop that ends within it" {
      val agent = runningAgent()
      every { agent.awaitTerminated() } throws IllegalStateException("stuck")

      shouldThrow<IllegalStateException> {
        EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown()
      }.message shouldBe "stuck"
    }

    // shutdown has returned by then, so the log is the only place it can go
    "a stop that fails after the stop timeout is up is logged" {
      val agent = runningAgent()
      every { agent.agentName } returns "test-agent"
      val release = CountDownLatch(1)
      val stopping = CompletableFuture<Thread>()
      every { agent.awaitTerminated() } answers {
        stopping.complete(Thread.currentThread())
        release.await()
        throw IllegalStateException("stuck")
      }

      val events =
        captureLogs<EmbeddedAgentInfo>(Level.DEBUG) {
          EmbeddedAgentInfo(agent, stopTimeout = 50.milliseconds).shutdown()
          release.countDown()
          // The stop's thread logs before it ends
          stopping.get(5, TimeUnit.SECONDS).join(5.seconds.inWholeMilliseconds)
        }

      events.map { it.level to it.formattedMessage } shouldBe
        listOf(
          Level.WARN to
            "The Prometheus agent test-agent was still stopping after 50ms, and goes on stopping on its own",
          Level.WARN to "The Prometheus agent test-agent failed as it went on stopping: stuck",
        )
    }

    // A const in a companion object compiles to a field of the class, public unless the const is private (November 26
    // review, #27)
    "the handle has no public fields, its stop thread's name included" {
      EmbeddedAgentInfo::class.java.fields.shouldBeEmpty()
    }

    // AutoCloseable, so Kotlin's use and Java's try-with-resources stop the agent after their block
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

  // A mocked running agent, as a handle with a stop timeout stops it: its stopAsync(), then its awaitTerminated(),
  // which a spec makes its stop
  private fun runningAgent(): Agent =
    mockk<Agent>(relaxed = true).also { agent ->
      every { agent.state() } returns Service.State.RUNNING
      every { agent.stopAsync() } returns agent
    }
}
