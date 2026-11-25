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

package io.prometheus.proxy

import java.util.concurrent.ConcurrentHashMap

/**
 * When to warn that a count nears its limit, as each `proxy.internal` limit on what agents hold is warned of before it
 * refuses anything: as the count reaches 80% of the limit, and again only once it has fallen below 70%. A count that
 * hovers at the threshold, as an agent's paths do while its discovery file churns or a fleet's agents do as they
 * reconnect one at a time, is warned of once rather than on each climb back.
 *
 * Each call is thread-safe on its own. A caller that changes a count and then reports it here does both under one lock,
 * or in one compute on the key, so the reports for a key come in the order its count changed.
 *
 * @param K what is counted: an agent, or an auth identity
 */
internal class NearLimitWarnings<K : Any> {
  // The keys warned of, each with the limit it was warned of, until its count falls below 70% of that limit.
  private val warned = ConcurrentHashMap<K, Int>()

  /**
   * Notes that [key]'s count rose to [count], of a limit of [max] (0 or less for none), and returns whether to warn of
   * it now: when it has reached 80% of [max] and hasn't been warned of since it was last below 70%.
   */
  fun rose(
    key: K,
    count: Int,
    max: Int,
  ): Boolean = max > 0 && count.toLong() * 5 >= max.toLong() * 4 && warned.putIfAbsent(key, max) == null

  /** Notes that [key]'s count fell to [count]: below 70% of the limit it was warned of, it may be warned of again. */
  fun fell(
    key: K,
    count: Int,
  ) {
    warned.computeIfPresent(key) { _, max -> max.takeUnless { count.toLong() * 10 < max.toLong() * 7 } }
  }
}
