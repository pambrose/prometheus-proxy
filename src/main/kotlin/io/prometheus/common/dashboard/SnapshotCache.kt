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

package io.prometheus.common.dashboard

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.plusAssign
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A dashboard's most recent snapshot of what it shows, served until it is [maxAge] old or the state it reports changes.
 *
 * Every page and WebSocket render reads from here. A client decides how fast its requests and messages arrive, so
 * collecting per request would let it hold whatever lock the state is read under (the proxy's path-map monitor, which
 * every scrape and registration takes) as often as it liked. Through the cache, the collect rate is bounded by the
 * refresh interval instead. The price is drifting values (backlogs, countdowns, scrape history) up to one interval old,
 * the staleness the push loop already accepts.
 *
 * A change is not held that long. [invalidate], called on every change the dashboard is told of, starts a new
 * generation, and an entry is served only while the generation it was collected in is current. So a collect that began
 * before a change and ended after it is not reused. Clearing the entry on the change did not ensure that: the late
 * collect refilled the cache with the old state, and with no session open, nothing else collected for a whole
 * interval. What remains is the moment a change takes to reach the dashboard after it happens.
 *
 * One collect runs at a time. A read that finds no usable entry waits for the collect under way and renders its
 * result, so a burst of requests on an empty cache costs one collect rather than one each.
 */
internal class SnapshotCache<T : Any>(
  private val maxAge: Duration,
  private val clock: TimeSource = TimeSource.Monotonic,
  private val collect: suspend () -> T,
) {
  /** A collected snapshot, when its collect began, and the generation it began in. */
  private class Entry<T : Any>(
    val snapshot: T,
    val taken: TimeMark,
    val generation: Long,
  )

  private val collecting = Mutex()
  private val generation = AtomicLong(0L)

  @Volatile
  private var latest: Entry<T>? = null

  /** Retires every snapshot collected, or being collected, before this call. */
  fun invalidate() {
    generation += 1L
  }

  /** The cached snapshot while it is usable; otherwise a collect, shared with every read that arrives meanwhile. */
  suspend fun recent(): T = usable() ?: collecting.withLock { usable() ?: collectAndStore() }

  /** Always a collect, for the push loop's tick; the reads after it are served what it collected. */
  suspend fun fresh(): T = collecting.withLock { collectAndStore() }

  private fun usable(): T? =
    latest?.takeIf { it.generation == generation.load() && it.taken.elapsedNow() < maxAge }?.snapshot

  // Callers hold collecting. The generation and the time are read before the collect, so an entry never claims to be
  // newer than the state it read.
  private suspend fun collectAndStore(): T {
    val collectedIn = generation.load()
    val taken = clock.markNow()
    return collect().also { latest = Entry(it, taken, collectedIn) }
  }
}
