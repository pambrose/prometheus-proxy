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

package io.prometheus.proxy.ui

import io.prometheus.Proxy
import io.prometheus.proxy.ScrapeRecord
import java.time.Instant
import kotlin.time.Duration

/** One registered path as the UI shows it. */
internal data class PathView(
  val path: String,
  val agentIds: List<String>,
  val labels: String,
)

/** One connected agent as the UI shows it. */
internal data class AgentView(
  val agentId: String,
  val agentName: String,
  val hostName: String,
  val remoteAddr: String,
  val launchId: String,
  val consolidated: Boolean,
  val isValid: Boolean,
  val connectTime: Instant,
  val inactivity: Duration,
  val backlogSize: Int,
  val paths: List<PathView>,
) {
  /** Seconds until the stale-agent cleanup would evict this agent, floored at zero once overdue. */
  fun evictionCountdownSecs(maxInactivitySecs: Long): Long =
    (maxInactivitySecs - inactivity.inWholeSeconds).coerceAtLeast(0)
}

/** Counters that drift rather than change at an identifiable moment, sampled on a timer. */
internal data class HealthView(
  val agentCount: Int,
  val pathCount: Int,
  val chunkContextSize: Int,
  val chunkContextThreshold: Int,
  val scrapeMapSize: Int,
  val scrapeMapThreshold: Int,
) {
  val chunkContextHealthy: Boolean get() = chunkContextSize < chunkContextThreshold
  val scrapeMapHealthy: Boolean get() = scrapeMapSize < scrapeMapThreshold
}

/**
 * An immutable point-in-time view of everything the UI renders.
 *
 * Materialized rather than referencing live state: [io.prometheus.proxy.AgentContextManager.agentContextEntries]
 * is the `ConcurrentHashMap`'s **live entry set**, not a snapshot, so two passes over it can disagree.
 * Copying once here means every WebSocket session renders the same consistent picture.
 */
internal data class ProxySnapshot(
  val agents: List<AgentView>,
  val paths: List<PathView>,
  val scrapes: List<ScrapeRecord>,
  val health: HealthView,
  val maxAgentInactivitySecs: Long,
) {
  fun agent(agentId: String): AgentView? = agents.firstOrNull { it.agentId == agentId }

  companion object {
    /**
     * Collects a snapshot from live proxy state.
     *
     * **Must not run on a Ktor CIO thread.** `ProxyPathManager` guards its map with `synchronized`, and
     * Kotlin's `synchronized` parks the underlying *carrier* thread rather than suspending the
     * coroutine — so collecting on the event loop would couple the operator UI to scrape latency.
     *
     * Deliberately avoids two accessors that look useful and are not:
     * - `ProxyPathManager.toPlainText()` holds the path monitor across a full sort plus a `toString()`
     *   per entry.
     * - `AgentContextManager.totalAgentScrapeRequestBacklogSize`, which recomputes an aggregate the
     *   `proxy_cumulative_agent_backlog_size` gauge already samples.
     *
     * One cost is paid rather than avoided: per-agent `scrapeRequestBacklogSize` is
     * `ConcurrentLinkedQueue.size()`, an O(depth) traversal, so a collect is O(agents × backlog depth).
     * That is acceptable only because collects are bounded by the refresh interval — which is why
     * scrape completions deliberately do not wake the push loop.
     *
     * Cost is two monitor acquisitions per call regardless of how many sessions are connected, which is
     * why the caller collects once and fans out rather than collecting per session.
     */
    fun collect(proxy: Proxy): ProxySnapshot {
      val internal = proxy.proxyConfigVals.internal

      // One monitor acquire; values are immutable so they are safe to hold onto.
      val pathInfos = proxy.pathManager.allPathContextInfos()

      val paths =
        pathInfos
          .map { (path, info) -> PathView(path, info.agentContexts.map { it.agentId }, info.labels) }
          .sortedBy { it.path }

      // Derived from the already-sorted list, so each agent's paths come out sorted for free.
      val pathsByAgent = paths.flatMap { view -> view.agentIds.map { it to view } }.groupBy({ it.first }, { it.second })

      // Materialize the live entry set before reading fields off it.
      val agents =
        proxy.agentContextManager.agentContextEntries
          .map { (agentId, context) ->
            AgentView(
              agentId = agentId,
              agentName = context.agentName,
              hostName = context.hostName,
              remoteAddr = context.remoteAddr,
              launchId = context.launchId,
              consolidated = context.consolidated,
              isValid = context.isValid(),
              connectTime = context.connectTime,
              inactivity = context.inactivityDuration,
              backlogSize = context.scrapeRequestBacklogSize,
              paths = pathsByAgent[agentId].orEmpty(),
            )
          }
          .sortedBy { it.agentName }

      return ProxySnapshot(
        agents = agents,
        paths = paths,
        scrapes = proxy.recentScrapes(),
        health =
          HealthView(
            agentCount = agents.size,
            pathCount = paths.size,
            chunkContextSize = proxy.agentContextManager.chunkedContextSize,
            chunkContextThreshold = internal.chunkContextMapUnhealthySize,
            scrapeMapSize = proxy.scrapeRequestManager.scrapeMapSize,
            scrapeMapThreshold = internal.scrapeRequestMapUnhealthySize,
          ),
        maxAgentInactivitySecs = internal.maxAgentInactivitySecs.toLong(),
      )
    }
  }
}
