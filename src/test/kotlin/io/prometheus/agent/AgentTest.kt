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

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.minusAssign
import kotlin.concurrent.atomics.plusAssign
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

    // ==================== Bug #12: currentCacheSize non-blocking ====================

    // Bug #12: The health check previously used runBlocking { getCacheStats().totalEntries }
    // which could block the health check thread while waiting for the cache mutex.
    // The fix uses currentCacheSize() which reads from ConcurrentHashMap.size (non-blocking).
    "httpClientCache.currentCacheSize should return 0 for empty cache" {
      val agent = createTestAgent()

      val size = agent.agentHttpService.httpClientCache.currentCacheSize()

      size shouldBe 0
    }

    "httpClientCache.currentCacheSize should not require coroutine context" {
      val agent = createTestAgent()

      // currentCacheSize() reads from ConcurrentHashMap.size and must work from any thread
      // without needing runBlocking or a coroutine context. The old getCacheStats() approach
      // required a coroutine context (mutex), which would deadlock in health check callbacks.
      val thread = Thread {
        val size = agent.agentHttpService.httpClientCache.currentCacheSize()
        size shouldBe 0
      }
      thread.start()
      thread.join(5000)

      thread.isAlive.shouldBeFalse()
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

    // ==================== Bug #5: scrapeRequestBacklogSize should not go negative ====================

    "scrapeRequestBacklogSize should start at zero" {
      val agent = createTestAgent()

      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "scrapeRequestBacklogSize increment and decrement should balance to zero" {
      val agent = createTestAgent()

      agent.scrapeRequestBacklogSize += 1
      agent.scrapeRequestBacklogSize += 1
      agent.scrapeRequestBacklogSize += 1
      agent.scrapeRequestBacklogSize.load() shouldBe 3

      agent.scrapeRequestBacklogSize -= 1
      agent.scrapeRequestBacklogSize -= 1
      agent.scrapeRequestBacklogSize -= 1
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    // Bug #5: Verifies that coroutineScope guarantees all finally blocks run before
    // returning, so a counter incremented in launch and decremented in finally will
    // always return to zero -- no store(0) needed.
    "scrapeRequestBacklogSize pattern should return to zero after coroutineScope completes" {
      val counter = AtomicInt(0)

      coroutineScope {
        repeat(10) {
          launch {
            counter += 1
            try {
              delay(10.milliseconds)
            } finally {
              counter -= 1
            }
          }
        }
      }

      // After coroutineScope, all children have completed including finally blocks
      counter.load() shouldBe 0
    }

    // Bug #5: Simulates the previous bug: a store(0) between in-flight increments
    // and their corresponding decrements drives the counter negative.
    "store(0) during in-flight decrements would produce negative counter" {
      val counter = AtomicInt(0)

      // Simulate: 3 scrapes in flight
      counter += 1
      counter += 1
      counter += 1
      counter.load() shouldBe 3

      // Simulate: reconnect resets to 0 while decrements are still pending
      counter.store(0)
      counter.load() shouldBe 0

      // Simulate: in-flight finally blocks decrement after the reset
      counter -= 1
      counter -= 1
      counter -= 1
      counter.load() shouldBe -3 // Bug! Counter is negative
    }

    // Bug #5: Without store(0), the counter naturally returns to zero.
    "without store(0) counter naturally returns to zero" {
      val counter = AtomicInt(0)

      // Simulate: 3 scrapes in flight
      counter += 1
      counter += 1
      counter += 1
      counter.load() shouldBe 3

      // No store(0) -- just let the finally blocks run
      counter -= 1
      counter -= 1
      counter -= 1
      counter.load() shouldBe 0
    }

    // Bug #5: Verify the health check uses non-negative values
    "scrapeRequestBacklogSize should never be negative for health check" {
      val agent = createTestAgent()

      // Simulate balanced increment/decrement cycles
      repeat(5) {
        agent.scrapeRequestBacklogSize += 1
        agent.scrapeRequestBacklogSize -= 1
      }

      agent.scrapeRequestBacklogSize.load() shouldBeGreaterThanOrEqual 0
    }

    // Bug #20: decrementBacklog should clamp at zero to prevent negative values
    "Bug #20: decrementBacklog should not go below zero" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.store(2)

      // Decrementing by more than current value should clamp at 0
      agent.decrementBacklog(5)
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "Bug #20: decrementBacklog should decrement normally when value is sufficient" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.store(10)

      agent.decrementBacklog(3)
      agent.scrapeRequestBacklogSize.load() shouldBe 7

      agent.decrementBacklog(7)
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "Bug #20: decrementBacklog from zero should stay at zero" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.load() shouldBe 0

      agent.decrementBacklog(1)
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "Bug #20: concurrent decrementBacklog should never go negative" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.store(50)

      runBlocking {
        // Launch 100 coroutines each decrementing by 1 — only 50 should succeed
        coroutineScope {
          repeat(100) {
            launch(Dispatchers.IO) {
              agent.decrementBacklog(1)
            }
          }
        }
      }

      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    // ==================== Bug #8: handleConnectionFailure should rethrow JVM Errors ====================

    // Bug #8: Before the fix, the reconnect loop's onFailure handler caught all Throwable
    // (via runCatchingCancellable + else branch) including Error subclasses like
    // OutOfMemoryError and StackOverflowError. These were logged at warn level and
    // the agent continued running in a potentially corrupted state.
    //
    // The fix adds an `is Error` branch that re-throws, causing the agent to terminate
    // on fatal JVM errors instead of swallowing them.

    "Bug #8: handleConnectionFailure should rethrow StackOverflowError" {
      val agent = createTestAgent()

      shouldThrow<StackOverflowError> {
        agent.handleConnectionFailure(StackOverflowError("test stack overflow"))
      }
    }

    "Bug #8: handleConnectionFailure should rethrow OutOfMemoryError" {
      val agent = createTestAgent()

      shouldThrow<OutOfMemoryError> {
        agent.handleConnectionFailure(OutOfMemoryError("test OOM"))
      }
    }

    "Bug #8: handleConnectionFailure should rethrow VirtualMachineError subclasses" {
      val agent = createTestAgent()

      shouldThrow<InternalError> {
        agent.handleConnectionFailure(InternalError("test internal error"))
      }
    }

    "Bug #8: handleConnectionFailure should rethrow AssertionError" {
      val agent = createTestAgent()

      // AssertionError is an Error subclass
      shouldThrow<AssertionError> {
        agent.handleConnectionFailure(AssertionError("test assertion"))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for RequestFailureException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(RequestFailureException("invalid response"))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for StatusRuntimeException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(StatusRuntimeException(Status.UNAVAILABLE))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for StatusException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(StatusException(Status.UNAVAILABLE))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for RuntimeException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(RuntimeException("network error"))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for IOException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(java.io.IOException("connection reset"))
      }
    }

    // ==================== Bug #1: Bounded Coroutine Creation Tests ====================

    "Bug #1: semaphore.acquire() before launch should limit waiting coroutines" {
      runBlocking {
        val maxConcurrency = 2
        val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrency)
        val activeCoroutines = AtomicInt(0)
        val waitingActions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val processedCount = AtomicInt(0)

        // This test simulates the loop in Agent.kt:294
        val job = launch(Dispatchers.Default) {
          for (action in waitingActions) {
            semaphore.acquire() // This is the fix for Bug #1
            launch {
              activeCoroutines += 1
              try {
                action()
              } finally {
                activeCoroutines -= 1
                processedCount += 1
                semaphore.release()
              }
            }
          }
        }

        // Flood the channel with many actions
        val floodSize = 100
        repeat(floodSize) {
          waitingActions.send {
            delay(10.milliseconds)
          }
        }

        // Wait a bit for processing to start
        delay(50.milliseconds)

        // Without the fix, activeCoroutines would be floodSize (all launched immediately)
        // With the fix, activeCoroutines should be <= maxConcurrency
        activeCoroutines.load() shouldBeGreaterThanOrEqual 0
        activeCoroutines.load() shouldBe maxConcurrency // Exactly maxConcurrency should be active

        // Clean up
        waitingActions.close()
        job.join()
        processedCount.load() shouldBe floodSize
      }
    }
  }
}
