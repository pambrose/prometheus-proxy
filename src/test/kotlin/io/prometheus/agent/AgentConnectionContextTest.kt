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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentConnectionContextTest : FunSpec() {
  init {
    test("close should disconnect context") {
      val context = AgentConnectionContext()
      context.connected.shouldBeTrue()

      context.close()

      context.connected.shouldBeFalse()
    }

    test("close should be idempotent") {
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
    test("close should not deadlock when called from invokeOnCompletion") {
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
    test("concurrent close calls should not throw or deadlock") {
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
    test("buffered scrape results should be drainable after close") {
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

    test("empty scrape results channel should close cleanly") {
      val context = AgentConnectionContext()

      // Close with no buffered results
      context.close()

      // Channel should be closed immediately with no items
      val channel = context.scrapeResults()
      val result = channel.receiveCatching()
      result.isClosed.shouldBeTrue()
    }
  }
}
