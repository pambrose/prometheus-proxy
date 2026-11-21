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

package io.prometheus.common.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * [SnapshotCache], which every page and WebSocket render of a dashboard reads from, and which bounds how often a client
 * can make the proxy or the agent collect. `ProxyWebDashboardTest` covers the live routes reading through it.
 */
class SnapshotCacheTest : StringSpec() {
  /** What a collect returns: a new instance each time, named for the call that made it. */
  private class Snapshot(
    val call: Int,
  )

  /** A collect that counts its calls, returns a new snapshot each time, and, given a [gate], waits on it first. */
  private class CountingCollect(
    private val gate: CompletableDeferred<Unit>? = null,
  ) {
    private val calls = AtomicInt(0)
    val count: Int get() = calls.load()

    suspend fun next(): Snapshot {
      val call = calls.incrementAndFetch()
      gate?.await()
      return Snapshot(call)
    }
  }

  init {
    // A page GET or a WebSocket message on the unauthenticated port must not be able to drive a collect, which holds
    // the path-map monitor every scrape takes (November #18). A test clock rather than sleeps, so expiry is exact.
    "the snapshot cache should collect once for any number of reads within the refresh interval" {
      val clock = TestTimeSource()
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, clock) { source.next() }

      val first = cache.recent()
      repeat(READS) { cache.recent() shouldBeSameInstanceAs first }
      clock += REFRESH - 1.milliseconds
      cache.recent() shouldBeSameInstanceAs first
      source.count shouldBe 1
    }

    "the snapshot cache should collect again once the refresh interval has passed" {
      val clock = TestTimeSource()
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, clock) { source.next() }

      val first = cache.recent()
      clock += REFRESH
      cache.recent() shouldNotBeSameInstanceAs first
      source.count shouldBe 2
    }

    // The push loop's timer tick: it must sample drifting values even when a read has just filled the cache, and what
    // it collects is what the next read renders.
    "a fresh collect should always collect, and serve the reads after it" {
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      cache.recent()
      val pushed = cache.fresh()
      source.count shouldBe 2
      cache.recent() shouldBeSameInstanceAs pushed
      source.count shouldBe 2
    }

    // A new agent or path must show on the next page, not up to a refresh interval later.
    "a topology change should make the next read collect" {
      val source = CountingCollect()
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      val before = cache.recent()
      cache.invalidate()
      cache.recent() shouldNotBeSameInstanceAs before
      source.count shouldBe 2
    }

    // A collect that began before a topology change may have read the map before it, so when it finishes after the
    // change its snapshot must not be served as current. Clearing the cache on the change alone lost this race: the
    // late collect refilled it, and with no session open, nothing else would collect for a whole interval.
    "a collect overtaken by a topology change should not be reused" {
      val gate = CompletableDeferred<Unit>()
      val source = CountingCollect(gate)
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      coroutineScope {
        // Undispatched, so the read is parked inside its collect before the change below lands.
        val overtaken = async(start = CoroutineStart.UNDISPATCHED) { cache.recent() }
        source.count shouldBe 1
        cache.invalidate()
        gate.complete(Unit)
        val stale = overtaken.await()

        cache.recent() shouldNotBeSameInstanceAs stale
        source.count shouldBe 2
      }
    }

    // A burst of requests arriving while the cache is empty must not each run a collect: the first collects, and the
    // rest wait for it and render what it collected.
    "concurrent reads of an empty cache should share one collect" {
      val gate = CompletableDeferred<Unit>()
      val source = CountingCollect(gate)
      val cache = SnapshotCache(REFRESH, TestTimeSource()) { source.next() }

      coroutineScope {
        // Undispatched, so every read has reached the cache before the first collect is let finish.
        val reads = List(READS) { async(start = CoroutineStart.UNDISPATCHED) { cache.recent() } }
        gate.complete(Unit)
        val snapshots = reads.awaitAll()

        source.count shouldBe 1
        snapshots.forEach { it shouldBeSameInstanceAs snapshots.first() }
      }
    }
  }

  companion object {
    // The default proxy.dashboard.refreshIntervalSecs.
    private val REFRESH = 2.seconds
    private const val READS = 10
  }
}
