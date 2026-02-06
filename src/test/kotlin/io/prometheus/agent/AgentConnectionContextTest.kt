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

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentConnectionContextTest {
  @Test
  fun `close should disconnect context`(): Unit =
    runBlocking {
      val context = AgentConnectionContext()
      context.connected.shouldBeTrue()

      context.close()

      context.connected.shouldBeFalse()
    }

  @Test
  fun `close should be idempotent`(): Unit =
    runBlocking {
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
  @Test
  fun `close should not deadlock when called from invokeOnCompletion`(): Unit =
    runBlocking {
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
  @Test
  fun `concurrent close calls should not throw or deadlock`(): Unit =
    runBlocking {
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
}
