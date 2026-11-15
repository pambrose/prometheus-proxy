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

package io.prometheus.common

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Lets something happen at most once every [interval] for each key, such as a WARN that a caller could otherwise cause
 * on every call it makes.
 *
 * A key passes when it has never passed, or last passed [interval] or more ago. A refused call doesn't restart its
 * key's interval, so a caller that keeps calling still passes once every [interval].
 *
 * Bounded however many keys it is asked about: it remembers when each key last passed in a map of at most [maxKeys],
 * ordered by when each was last asked about, and forgets the least recent once a new key would pass the cap. A key it
 * has forgotten passes again as a new one would, so the cap should exceed the keys expected within an [interval].
 *
 * Thread-safe. Every access takes the throttle's own monitor, which is a leaf: nothing else is called while it is held,
 * so a caller holding another lock may call [tryAcquire].
 *
 * @param interval how long after a key passes before it may pass again
 * @param maxKeys the most keys remembered at once
 * @param timeSource where the times come from; injectable so tests move time instead of sleeping
 */
internal class KeyedThrottle<K : Any>(
  private val interval: Duration,
  private val maxKeys: Int,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  init {
    require(interval.isPositive()) { "interval must be positive: $interval" }
    require(maxKeys > 0) { "maxKeys must be positive: $maxKeys" }
  }

  // When each key last passed. Access-ordered, so a key's every request, passed or refused, makes it the most recent,
  // and the eldest is the one asked about least recently. Guarded by its monitor.
  private val lastPassed =
    object : LinkedHashMap<K, TimeMark>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, TimeMark>) = size > maxKeys
    }

  /** How many keys it remembers, never more than maxKeys. */
  val size: Int get() = synchronized(lastPassed) { lastPassed.size }

  /** Whether [key] passes now: true, starting its interval again, when it has never passed or its interval is over. */
  fun tryAcquire(key: K): Boolean =
    synchronized(lastPassed) {
      val last = lastPassed[key]
      if (last != null && last.elapsedNow() < interval)
        return false
      lastPassed[key] = timeSource.markNow()
      true
    }

  private companion object {
    const val INITIAL_CAPACITY = 16
    const val LOAD_FACTOR = 0.75f
  }
}
