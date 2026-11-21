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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The dashboard's limit on wrong logins, so a guesser can't try passwords in parallel: at most [perAddress] within
 * [window] from one address, and [overall] from every address together. Past either, every login it covers is refused,
 * the right password included, until the oldest of those wrong logins is [window] old.
 *
 * Behind a reverse proxy, every login comes from the proxy's address, so the per-address limit is everyone's. During an
 * attack the limit refuses the real users' logins too, for at most [window]: the price of refusing the guesser's.
 *
 * Bounded however many addresses it sees: it remembers at most [maxAddresses], the one it heard from least recently
 * going first. Thread-safe: every access takes its monitor, which is a leaf.
 *
 * @param timeSource where the times come from; injectable so tests move time instead of sleeping
 */
internal class LoginLimiter(
  private val perAddress: Int,
  private val overall: Int,
  private val window: Duration = 1.minutes,
  private val maxAddresses: Int = MAX_ADDRESSES,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  // Each address's latest wrong logins, at most perAddress, oldest first. Access-ordered, so the eldest is the address
  // heard from least recently. Guarded by this.
  private val byAddress =
    object : LinkedHashMap<String, ArrayDeque<TimeMark>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<TimeMark>>) =
        size > maxAddresses
    }

  // Every address's latest wrong logins, at most overall, oldest first. Guarded by this.
  private val all = ArrayDeque<TimeMark>()

  /** Whether a login from [address] is refused now, before its password is looked at. */
  @Synchronized
  fun isBlocked(address: String): Boolean =
    isFull(all, overall) || byAddress[address]?.let { isFull(it, perAddress) } == true

  /** Records a wrong login from [address]. True when it's the one that reaches a limit, so the refusing begins. */
  @Synchronized
  fun recordFailure(address: String): Boolean {
    val now = timeSource.markNow()
    val reachedOverall = add(all, now, overall)
    val reachedAddress = add(byAddress.getOrPut(address) { ArrayDeque() }, now, perAddress)
    return reachedOverall || reachedAddress
  }

  // Whether [marks], once those older than the window are dropped, has [limit] of them.
  private fun isFull(
    marks: ArrayDeque<TimeMark>,
    limit: Int,
  ): Boolean {
    while (marks.isNotEmpty() && marks.first().elapsedNow() >= window) {
      marks.removeFirst()
    }
    return marks.size >= limit
  }

  // Adds [now] to [marks], keeping the latest [limit]; true when that makes it full.
  private fun add(
    marks: ArrayDeque<TimeMark>,
    now: TimeMark,
    limit: Int,
  ): Boolean {
    val wasFull = isFull(marks, limit)
    marks.addLast(now)
    while (marks.size > limit) {
      marks.removeFirst()
    }
    return !wasFull && marks.size >= limit
  }

  private companion object {
    const val MAX_ADDRESSES = 1_000
    const val INITIAL_CAPACITY = 16
    const val LOAD_FACTOR = 0.75f
  }
}
