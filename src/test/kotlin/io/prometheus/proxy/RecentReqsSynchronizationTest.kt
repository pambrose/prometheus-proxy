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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction", "SwallowedException")

package io.prometheus.proxy

import com.google.common.collect.EvictingQueue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

// Bug #6: The debug servlet read recentReqs (an EvictingQueue backed by ArrayDeque)
// without synchronization while logActivity() writes to it under synchronized(recentReqs).
// This test validates that the synchronized read pattern used in the fix prevents
// ConcurrentModificationException.
class RecentReqsSynchronizationTest : StringSpec() {
  init {
    // Simulates the FIXED pattern: both reads and writes are synchronized on the same lock.
    // This should complete without ConcurrentModificationException.
    "synchronized reads and writes on EvictingQueue should not throw" {
      val queue: EvictingQueue<String> = EvictingQueue.create(50)
      val failed = AtomicBoolean(false)
      val iterations = 5_000
      val barrier = CyclicBarrier(2)

      // Writer thread (simulates logActivity)
      val writer = thread {
        barrier.await()
        repeat(iterations) { i ->
          synchronized(queue) {
            queue.add("entry-$i")
          }
        }
      }

      // Reader thread (simulates debug servlet)
      val reader = thread {
        barrier.await()
        repeat(iterations) {
          try {
            synchronized(queue) {
              if (queue.isNotEmpty()) {
                // This is the exact pattern from the fix:
                // read size + reversed() + joinToString() inside synchronized
                @Suppress("UNUSED_VARIABLE")
                val text = "${queue.size} most recent requests:\n" +
                  queue.reversed().joinToString("\n")
              }
            }
          } catch (e: ConcurrentModificationException) {
            failed.set(true)
          }
        }
      }

      writer.join()
      reader.join()

      failed.get().shouldBeFalse()
    }
  }
}
