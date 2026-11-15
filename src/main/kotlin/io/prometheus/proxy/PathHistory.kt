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

import kotlin.time.Clock
import kotlin.time.Duration

/**
 * The last few scrapes of each path, kept per path, for the dashboard's path table.
 *
 * Kept per path, not in one queue: the dashboard used to rebuild a path whose agent had gone from the recent-scrapes
 * queue, which every path shares. On a proxy with a hundred paths scraped every 15 seconds that queue's 200 records
 * span about 30 seconds, so a gone path left the table within a minute, at the moment an operator came looking for
 * it. Here each path keeps its own ring of [historySize] records, and a path that is no longer registered stays until
 * [departedRetention] has passed since its last scrape.
 *
 * Bounded on every write, whether or not a dashboard is open: a path holds at most [historySize] records, and
 * [record] keeps at most [livePathCount] plus [maxDepartedPaths] paths, evicting the one scraped least recently. A
 * registered path is scraped on every Prometheus interval, so the paths that go first are the gone ones. The bound
 * can't wait for [snapshot], which runs only while a dashboard page or session collects: the proxy has no limit on its
 * total paths, only `maxPathsPerAgent`, so a discovery file that churns path names would otherwise grow this without
 * end on a proxy nobody was watching. [snapshot] then drops gone paths past their retention, and keeps at most
 * [maxDepartedPaths] of the rest, which is what the page shows.
 *
 * Thread-safe. Every access takes the map's own monitor, which is a leaf: nothing else is called while it is held.
 *
 * @param historySize records kept per path, `proxy.dashboard.pathHistorySize`
 * @param departedRetention how long a path that is no longer registered stays, from its last scrape,
 *   `proxy.dashboard.departedPathRetentionSecs`
 * @param livePathCount how many paths are registered now, which [record] leaves room for beside the gone ones
 * @param clock where "now" comes from when pruning; a wall clock, not a monotonic `TimeSource`, since it is compared
 *   with each record's wall-clock time. Injectable so tests move time instead of sleeping
 * @param maxDepartedPaths the most gone paths kept at once
 */
internal class PathHistory(
  private val historySize: Int,
  private val departedRetention: Duration,
  private val livePathCount: () -> Int,
  private val clock: Clock = Clock.System,
  private val maxDepartedPaths: Int = MAX_DEPARTED_PATHS,
) {
  // Oldest record first in each ring, so adding is addLast and evicting is removeFirst. Access-ordered, so the path
  // scraped least recently is always first, and record() evicts from the front without searching.
  private val rings = LinkedHashMap<String, ArrayDeque<ScrapeRecord>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

  /**
   * Adds one completed scrape to its path's history, evicting the path's oldest record once it holds [historySize], and
   * the path scraped least recently once there are more than [livePathCount] plus [maxDepartedPaths].
   */
  fun record(record: ScrapeRecord) {
    if (historySize <= 0) return
    // Read before taking the monitor, which stays a leaf: the path map has its own.
    val maxPaths = livePathCount() + maxDepartedPaths
    synchronized(rings) {
      // Keyed as the path map keys a path, without a leading slash, so the snapshot's join to registered paths holds
      // for a request that reached the scrape route as //path. getOrPut's get moves the path to the back.
      val ring = rings.getOrPut(record.path.removePrefix("/")) { ArrayDeque(historySize) }
      if (ring.size >= historySize) ring.removeFirst()
      ring.addLast(record)
      // The path just recorded is at the back, so it is never the one evicted.
      val leastRecent = rings.keys.iterator()
      while (rings.size > maxPaths) {
        leastRecent.next()
        leastRecent.remove()
      }
    }
  }

  /**
   * Each path's history, **newest first**, after dropping gone paths past their retention.
   *
   * @param registered the paths currently registered, by path-map key; every other path is a gone one
   */
  fun snapshot(registered: Set<String>): Map<String, List<ScrapeRecord>> =
    synchronized(rings) {
      prune(registered)
      rings.mapValues { (_, ring) -> ring.reversed() }
    }

  // Callers hold the monitor. A ring is never empty: record() creates one only to add to it.
  private fun prune(registered: Set<String>) {
    val cutoff = clock.now() - departedRetention
    val departed = rings.filterKeys { it !in registered }
    val (expired, kept) = departed.entries.partition { it.value.last().at < cutoff }
    expired.forEach { rings.remove(it.key) }
    if (kept.size > maxDepartedPaths)
      kept
        .sortedBy { it.value.last().at }
        .take(kept.size - maxDepartedPaths)
        .forEach { rings.remove(it.key) }
  }

  companion object {
    /** Gone paths kept at most at once; about 2 MB at the default 20 records each. */
    internal const val MAX_DEPARTED_PATHS = 1000

    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f
  }
}
