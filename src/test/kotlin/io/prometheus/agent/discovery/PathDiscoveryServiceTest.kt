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

package io.prometheus.agent.discovery

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.prometheus.agent.AgentPathManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.seconds

class PathDiscoveryServiceTest : StringSpec() {
  init {
    "a read failure skips reconcile so the live set is kept as last-known-good" {
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val service = PathDiscoveryService(pathManager, { throw IOException("boom") }, 30)

      service.reconcileOnce()

      coVerify(exactly = 0) { pathManager.reconcileDiscoveredPaths(any()) }
    }

    "skippedEntries should come from the source" {
      val skipped = [DiscoveredPath("bad", "", "http://bad.local/metrics", "{}")]
      val source =
        object : PathDiscoverySource, SkippedEntryReporter {
          override fun read(): List<DiscoveredPath> = emptyList()

          override val skippedEntries: List<DiscoveredPath> get() = skipped
        }

      PathDiscoveryService(mockk(relaxed = true), source, 1).skippedEntries shouldBe skipped
    }

    // A lambda source reports nothing, which is why the property is not on PathDiscoverySource itself.
    "skippedEntries should be empty for a source that does not report them" {
      val source = PathDiscoverySource { emptyList() }

      PathDiscoveryService(mockk(relaxed = true), source, 1).skippedEntries.shouldBeEmpty()
    }

    "a successful read reconciles the desired set" {
      val desired = [DiscoveredPath("a", "a_metrics", "http://a/m", "{}")]
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val service = PathDiscoveryService(pathManager, { desired }, 30)

      service.reconcileOnce()

      coVerify(exactly = 1) { pathManager.reconcileDiscoveredPaths(desired) }
    }

    "a successful empty read reconciles to empty (removes all discovered)" {
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val service = PathDiscoveryService(pathManager, { emptyList() }, 30)

      service.reconcileOnce()

      coVerify(exactly = 1) { pathManager.reconcileDiscoveredPaths(emptyList()) }
    }

    // run() is what the Agent launches. Its interval wait is sliced so a disconnect ends discovery within one slice,
    // rather than a full interval later, which would otherwise hold up shutdown.
    "run should stop within one wait slice once keepRunning turns false" {
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val running = AtomicBoolean(true)
      val firstRead = CompletableDeferred<Unit>()
      // A 60s interval, so an unsliced wait would keep run() alive far past the timeout below.
      val service =
        PathDiscoveryService(pathManager, { emptyList<DiscoveredPath>().also { firstRead.complete(Unit) } }, 60)

      val job = launch(Dispatchers.Default) { service.run { running.load() } }
      withTimeout(5.seconds) { firstRead.await() }
      running.store(false)
      withTimeout(5.seconds) { job.join() }

      coVerify(exactly = 1) { pathManager.reconcileDiscoveredPaths(emptyList()) }
    }

    // A failed read skips only its own tick: the loop must still reconcile on the next one.
    "run should keep reconciling on later ticks after a read fails" {
      val desired = [DiscoveredPath("a", "a_metrics", "http://a/m", "{}")]
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      val reconciled = CompletableDeferred<Unit>()
      coEvery { pathManager.reconcileDiscoveredPaths(desired) } answers {
        reconciled.complete(Unit)
      }
      val reads = AtomicInt(0)
      val running = AtomicBoolean(true)
      val source = PathDiscoverySource { if (reads.incrementAndFetch() == 1) throw IOException("boom") else desired }
      val service = PathDiscoveryService(pathManager, source, 1)

      val job = launch(Dispatchers.Default) { service.run { running.load() } }
      try {
        withTimeout(10.seconds) { reconciled.await() }
      } finally {
        running.store(false)
        withTimeout(5.seconds) { job.join() }
      }
      reads.load() shouldBeGreaterThanOrEqual 2
    }
  }
}
