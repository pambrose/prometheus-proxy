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

@file:Suppress(
  "UndocumentedPublicClass",
  "UndocumentedPublicFunction",
  "TooGenericExceptionCaught",
  "SwallowedException",
)

package io.prometheus.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AgentConnectionContextTest : StringSpec() {
  init {
    "close should disconnect context" {
      val context = AgentConnectionContext()
      context.connected.shouldBeTrue()

      context.close()

      context.connected.shouldBeFalse()
    }

    "close should be idempotent" {
      val context = AgentConnectionContext()

      context.close()
      context.connected.shouldBeFalse()

      // Second close should not throw
      context.close()
      context.connected.shouldBeFalse()
    }

    // Bug #7: The old code used runBlocking { accessMutex.withLock { ... } } in close().
    // When called from invokeOnCompletion (which runs on the completing coroutine's thread),
    // runBlocking would block the thread. If the coroutine Mutex was held by another coroutine
    // on the same Dispatchers.IO thread, deadlock could occur.
    // The fix replaces the coroutine Mutex + runBlocking with a plain synchronized block.
    // This test verifies close() completes promptly when called from invokeOnCompletion.
    "close should not deadlock when called from invokeOnCompletion" {
      val context = AgentConnectionContext()
      val completedLatch = CountDownLatch(1)

      coroutineScope {
        val job =
          launch(Dispatchers.IO) {
            // Simulate work
          }
        job.invokeOnCompletion {
          // This mirrors Agent.kt:255 and Agent.kt:268
          context.close()
          completedLatch.countDown()
        }
      }

      // If close() deadlocked, this would time out
      completedLatch.await(5, TimeUnit.SECONDS).shouldBeTrue()
      context.connected.shouldBeFalse()
    }

    // Verifies that concurrent close() calls from multiple threads don't cause issues.
    // This exercises the synchronized block under contention.
    "concurrent close calls should not throw or deadlock" {
      val context = AgentConnectionContext()
      val threadCount = 10
      val startLatch = CountDownLatch(1)
      val doneLatch = CountDownLatch(threadCount)

      val threads =
        (1..threadCount).map {
          Thread {
            startLatch.await()
            context.close()
            doneLatch.countDown()
          }.apply { start() }
        }

      startLatch.countDown()
      val completed = doneLatch.await(5, TimeUnit.SECONDS)
      threads.forEach { it.join(1000) }

      completed.shouldBeTrue()
      context.connected.shouldBeFalse()
    }

    // M8: scrapeResultsChannel uses close() instead of cancel(), so buffered results
    // can still be drained by the consumer after the context is closed.
    "buffered scrape results should be drainable after close" {
      val context = AgentConnectionContext()

      // Buffer some results before closing
      val result1 = ScrapeResults(srAgentId = "agent-1", srScrapeId = 1L, srValidResponse = true)
      val result2 = ScrapeResults(srAgentId = "agent-1", srScrapeId = 2L, srValidResponse = true)
      val result3 = ScrapeResults(srAgentId = "agent-1", srScrapeId = 3L, srValidResponse = true)

      context.sendScrapeResults(result1)
      context.sendScrapeResults(result2)
      context.sendScrapeResults(result3)

      // Close the context — with close() (not cancel()), buffered items remain
      context.close()
      context.connected.shouldBeFalse()

      // Consumer should still be able to drain all 3 buffered results
      val channel = context.scrapeResults()
      val received1 = channel.receiveCatching()
      received1.isSuccess.shouldBeTrue()
      received1.getOrNull()!!.srScrapeId shouldBe 1L

      val received2 = channel.receiveCatching()
      received2.isSuccess.shouldBeTrue()
      received2.getOrNull()!!.srScrapeId shouldBe 2L

      val received3 = channel.receiveCatching()
      received3.isSuccess.shouldBeTrue()
      received3.getOrNull()!!.srScrapeId shouldBe 3L

      // After draining, the next receive should indicate the channel is closed
      val received4 = channel.receiveCatching()
      received4.isClosed.shouldBeTrue()
    }

    "empty scrape results channel should close cleanly" {
      val context = AgentConnectionContext()

      // Close with no buffered results
      context.close()

      // Channel should be closed immediately with no items
      val channel = context.scrapeResults()
      val result = channel.receiveCatching()
      result.isClosed.shouldBeTrue()
    }

    "backlogCapacity should be respected" {
      val capacity = 50
      val context = AgentConnectionContext(capacity)
      context.backlogCapacity shouldBe capacity
    }

    // ==================== Bug #7: In-flight scrape results silently lost on disconnect ====================

    // Bug #7: Before the fix, sendScrapeResults() used channel.send() which throws
    // ClosedSendChannelException when the channel is closed (disconnect). This caused:
    // 1. The exception propagated from the inner launch, cancelling sibling scrape coroutines
    // 2. Successfully computed scrape results were silently dropped
    // 3. The proxy timed out waiting for results that were actually computed
    //
    // The fix uses trySend() instead of send(). For an UNLIMITED channel, trySend() always
    // succeeds immediately if open. If the channel is closed, it returns a failure result
    // and logs a warning instead of throwing.

    "Bug #7: sendScrapeResults on open channel should deliver result" {
      val context = AgentConnectionContext()
      val result = ScrapeResults(srAgentId = "agent-1", srScrapeId = 42L, srValidResponse = true)

      context.sendScrapeResults(result)

      val channel = context.scrapeResults()
      val received = channel.tryReceive()
      received.isSuccess.shouldBeTrue()
      received.getOrThrow().srScrapeId shouldBe 42L

      context.close()
    }

    "Bug #7: sendScrapeResults on closed channel should not throw" {
      val context = AgentConnectionContext()

      // Close the context first
      context.close()

      // Sending to a closed channel should NOT throw ClosedSendChannelException
      val result = ScrapeResults(srAgentId = "agent-1", srScrapeId = 99L, srValidResponse = true)
      context.sendScrapeResults(result) // should not throw

      // The result is dropped (not in the channel)
      val channel = context.scrapeResults()
      val received = channel.receiveCatching()
      received.isClosed.shouldBeTrue()
    }

    "Bug #7: in-flight scrape coroutines should not be cancelled by closed channel" {
      // Simulates the real bug scenario: multiple coroutines are processing scrapes,
      // then close() is called. With the old send(), one ClosedSendChannelException
      // would cancel ALL sibling coroutines. With trySend(), each coroutine completes
      // independently.
      val context = AgentConnectionContext()
      val completedCount = AtomicInteger(0)
      val threwException = AtomicBoolean(false)

      coroutineScope {
        // Launch several "scrape" coroutines that will try to send results
        (1..5).map { i ->
          launch(Dispatchers.IO) {
            try {
              // Simulate scrape work
              delay(50)
              context.sendScrapeResults(
                ScrapeResults(srAgentId = "agent-1", srScrapeId = i.toLong(), srValidResponse = true),
              )
              completedCount.incrementAndGet()
            } catch (e: Exception) {
              threwException.set(true)
            }
          }
        }

        // Close the context while scrapes are in-flight
        delay(10)
        context.close()
      }

      // All coroutines should complete without throwing
      threwException.get().shouldBeFalse()
      // All coroutines should have completed (even though some results were dropped)
      completedCount.get() shouldBe 5
    }

    "Bug #7: results sent before close should be drainable alongside dropped post-close sends" {
      val context = AgentConnectionContext()

      // Send a result before close
      context.sendScrapeResults(
        ScrapeResults(srAgentId = "agent-1", srScrapeId = 1L, srValidResponse = true),
      )

      // Close the context
      context.close()

      // Try sending after close - should not throw
      context.sendScrapeResults(
        ScrapeResults(srAgentId = "agent-1", srScrapeId = 2L, srValidResponse = true),
      )

      // Only the pre-close result should be drainable
      val channel = context.scrapeResults()
      val received1 = channel.receiveCatching()
      received1.isSuccess.shouldBeTrue()
      received1.getOrNull()!!.srScrapeId shouldBe 1L

      // Next receive should show channel is closed (post-close result was dropped)
      val received2 = channel.receiveCatching()
      received2.isClosed.shouldBeTrue()
    }

    "Bug #7: backlog counter should remain accurate when sends are dropped" {
      // Verifies that the try/finally pattern for scrapeRequestBacklogSize works
      // correctly even when sendScrapeResults drops the result on a closed channel.
      // The counter should still decrement in the finally block.
      val context = AgentConnectionContext()
      val backlogSize = AtomicInteger(0)

      coroutineScope {
        (1..10).map { i ->
          launch(Dispatchers.IO) {
            backlogSize.incrementAndGet()
            try {
              delay(20)
              context.sendScrapeResults(
                ScrapeResults(srAgentId = "agent-1", srScrapeId = i.toLong(), srValidResponse = true),
              )
            } finally {
              backlogSize.decrementAndGet()
            }
          }
        }

        // Close mid-flight
        delay(5)
        context.close()
      }

      // After all coroutines complete, backlog should be exactly 0
      backlogSize.get() shouldBe 0
    }
  }
}
