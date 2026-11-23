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

import com.google.common.collect.EvictingQueue
import io.prometheus.proxy.ProxyPathManager.AgentContextInfo
import io.prometheus.proxy.ProxyPathManager.Companion.pathKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The last few scrapes of each path, kept per path, and the paths that have left the path map, for the dashboard's path
 * table and each agent's Recent scrapes, which lists the agent's latest from every path's ring.
 *
 * Kept per path, not in one queue: the dashboard used to rebuild a path whose agent had gone, and each agent's Recent
 * scrapes, from a recent-scrapes queue that every path shared. On a proxy with a hundred paths scraped every 15
 * seconds that queue's 200 records spanned about 30 seconds, so a gone path left the table within a minute, at the
 * moment an operator came looking for it, and an agent's panel held only its last few scrapes. Here each path keeps its
 * own ring of [historySize] records.
 *
 * A path's departure is kept too, from [recordDeparture], which the path manager calls as the path's last agent goes:
 * when it left, and the agent, identity, target URL, and source its entry showed. Gone rows were rebuilt from scrapes
 * alone, so a path whose agent left before Prometheus scraped it had no row, and none showed its target. A gone path,
 * one held here but no longer registered, stays until [departedRetention] has passed since it was last seen: the later
 * of its last scrape and its departure.
 *
 * Bounded on every write, whether or not a dashboard is open: a path holds at most [historySize] records, [record]
 * keeps at most [livePathCount] plus [maxDepartedPaths] rings, evicting the path scraped least recently, and
 * [recordDeparture] keeps at most [maxDepartedPaths] departures, evicting the earliest. A registered path is scraped on
 * every Prometheus interval, so the rings that go first are the gone ones. The bound can't wait for [snapshot], which
 * runs only while a dashboard page or session collects: the proxy has no limit on its total paths, only
 * `maxPathsPerAgent`, so a discovery file that churns path names would otherwise grow this without end on a proxy
 * nobody was watching. [snapshot] then drops gone paths past their retention, and keeps at most [maxDepartedPaths] of
 * the rest, counting a path once whether it has scrapes, a departure, or both, which is what the page shows.
 *
 * Thread-safe. Every access takes the rings map's own monitor, which is a leaf: nothing else is called while it is
 * held. The path manager calls [recordDeparture] and [forgetDeparture] under its `pathMap` lock, so the order is always
 * the `pathMap` lock, then this monitor; [record] reads [livePathCount], which takes the `pathMap` lock, before it
 * takes this monitor, never while holding it.
 *
 * @param historySize records kept per path, `proxy.dashboard.pathHistorySize`
 * @param departedRetention how long a path that is no longer registered stays, from the later of its last scrape and
 *   its departure, `proxy.dashboard.departedPathRetentionSecs`
 * @param livePathCount how many paths are registered now, which [record] leaves room for beside the gone ones
 * @param clock where "now" comes from when pruning, and when a path departs; a wall clock, not a monotonic
 *   `TimeSource`, since it is compared with each record's wall-clock time. Injectable so tests move time instead of
 *   sleeping
 * @param maxDepartedPaths the most gone paths kept at once
 */
internal class PathHistory(
  private val historySize: Int,
  private val departedRetention: Duration,
  private val livePathCount: () -> Int,
  private val clock: Clock = Clock.System,
  private val maxDepartedPaths: Int = MAX_DEPARTED_PATHS,
) {
  /**
   * A path as it left the path map, its last agent gone: what its gone row shows that its scrapes can't.
   *
   * Copied out of the path's entry rather than holding it, which would keep the agent's context, and its queue, alive.
   * The agent-supplied values were bounded already: the agent's name as the agent registered
   * (`ProxyServiceImpl.registerAgent`), and the target URL and source as the path did (`ProxyPathManager.addPath`). So
   * this needs no bound of its own.
   *
   * @param at when the path left, from which its retention runs unless it was scraped later
   * @param agentName the name of the agent that served it last
   * @param identity the auth identity the path was registered as, empty with agent auth off
   * @param targetUrl the URL that agent scraped for it, redacted, or empty when the agent didn't report one
   * @param pathSource how that agent came to serve it, `STATIC` or `DISCOVERED`, or empty
   */
  data class Departure(
    val at: Instant,
    val agentName: String,
    val identity: String,
    val targetUrl: String,
    val pathSource: String,
  )

  /**
   * What [snapshot] hands the dashboard, each keyed as the path map keys a path.
   *
   * @param scrapes each path's scrapes, **newest first**
   * @param departures each path that has left the path map since it last registered
   */
  data class Snapshot(
    val scrapes: Map<String, List<ScrapeRecord>> = emptyMap(),
    val departures: Map<String, Departure> = emptyMap(),
  )

  // Each ring evicts its oldest record itself. Access-ordered, so the path scraped least recently is always first, and
  // record() evicts from the front without sorting.
  private val rings = LinkedHashMap<String, EvictingQueue<ScrapeRecord>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

  // Guarded by rings' monitor, one lock for both, so a snapshot reads them at one moment. Insertion-ordered, and a path
  // that departs again is put back at the end, so the earliest departure is always first.
  private val departures = LinkedHashMap<String, Departure>()

  /**
   * Adds one completed scrape to its path's history, evicting the path's oldest record once it holds [historySize], and
   * the path scraped least recently once there are more than [livePathCount] plus [maxDepartedPaths].
   */
  fun record(record: ScrapeRecord) {
    if (historySize <= 0) return
    // Keyed as the path map keys a path, so the snapshot's join to the registered paths holds even for a record whose
    // path keeps a leading slash; the scrape route, which strips every one, records none. The get moves the path to the
    // back.
    val key = pathKey(record.path)
    synchronized(rings) {
      rings[key]?.also {
        it.add(record)
        return
      }
    }
    // A path's first scrape, the only time the map grows, so the only time the bound is read: livePathCount takes the
    // path map's lock, which a scrape of a path already here shouldn't pay for. Read before taking the monitor, which
    // stays a leaf.
    val maxPaths = livePathCount() + maxDepartedPaths
    synchronized(rings) {
      rings.getOrPut(key) { EvictingQueue.create(historySize) }.add(record)
      // The path just recorded is at the back, so it is never the one evicted.
      val leastRecent = rings.keys.iterator()
      while (rings.size > maxPaths) {
        val _ = leastRecent.next()
        leastRecent.remove()
      }
    }
  }

  /**
   * Remembers that [path] has left the path map, with what [entry], the entry it had there, showed of it, evicting the
   * earliest departure once there are more than [maxDepartedPaths].
   *
   * Called by the path manager under its `pathMap` lock, as the path's last agent goes, so a departure is always
   * ordered with the path's next registration, which forgets it. The entry is read before taking the monitor. The bound
   * is [maxDepartedPaths] alone, since every departure is a gone path, so it needs no [livePathCount], whose `pathMap`
   * lock must never be taken while holding the monitor.
   */
  fun recordDeparture(
    path: String,
    entry: AgentContextInfo,
  ) {
    val key = pathKey(path)
    val departure =
      Departure(
        at = clock.now(),
        agentName = entry.agentContexts.firstOrNull()?.agentName.orEmpty(),
        identity = entry.identityName,
        targetUrl = entry.targetUrl,
        pathSource = entry.pathSource,
      )
    synchronized(rings) {
      // Removed first, so a path that departs again goes to the end, as the latest.
      departures.remove(key)
      departures[key] = departure
      val earliest = departures.keys.iterator()
      while (departures.size > maxDepartedPaths) {
        val _ = earliest.next()
        earliest.remove()
      }
    }
  }

  /**
   * Forgets [path]'s departure as it registers again, so it is a live path, not a gone one. Its scrapes stay, so the
   * path keeps the history it had while gone. Called by the path manager under its `pathMap` lock.
   */
  fun forgetDeparture(path: String) {
    val key = pathKey(path)
    synchronized(rings) { departures.remove(key) }
  }

  /**
   * Each path's history and each departure, after dropping gone paths past their retention.
   *
   * @param registered the paths currently registered, by path-map key; every other path is a gone one
   */
  fun snapshot(registered: Set<String>): Snapshot =
    synchronized(rings) {
      prune(registered)
      Snapshot(rings.mapValues { (_, ring) -> ring.reversed() }, departures.toMap())
    }

  // Callers hold the monitor. A gone path goes once it was last seen, at the later of its last scrape and its
  // departure, before the retention's cutoff, and past the cap the gone paths seen least recently go first, from both
  // maps. Read by iterating, never with get, which would move a ring to the back of the access order record() evicts
  // by. A ring is never empty: record() creates one only to add to it.
  //
  // A departure is never dropped for a path in [registered], though the path map may no longer hold it: the dashboard
  // reads [registered] before calling, so a path that left in between is still in it, and dropping its departure would
  // lose a path never scraped for good. A path the path map does hold has no departure: forgetDeparture runs under the
  // same lock as its registration.
  private fun prune(registered: Set<String>) {
    // Linked, and the sort is stable, so gone paths last seen at the same moment go in the order record() evicts by.
    val lastSeen = LinkedHashMap<String, Instant>()
    rings.forEach { (path, ring) -> if (path !in registered) lastSeen[path] = ring.last().at }
    departures.forEach { (path, departure) ->
      if (path !in registered) lastSeen.merge(path, departure.at) { scraped, departed -> maxOf(scraped, departed) }
    }
    val cutoff = clock.now() - departedRetention
    // Oldest first, so the expired paths lead, and dropping from the front takes them and, past the cap, the oldest of
    // the rest.
    val oldestFirst = lastSeen.entries.sortedBy { it.value }
    val dropped = maxOf(oldestFirst.count { it.value < cutoff }, oldestFirst.size - maxDepartedPaths)
    oldestFirst.take(dropped).forEach { (path, _) ->
      rings.remove(path)
      departures.remove(path)
    }
  }

  companion object {
    /**
     * Gone paths kept at most at once: at the default 20 records each, a few MB with their path strings, and as much
     * again for their departures, each holding a target URL of up to the default `maxTargetUrlLength`, 2048.
     */
    internal const val MAX_DEPARTED_PATHS = 1000

    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f
  }
}
