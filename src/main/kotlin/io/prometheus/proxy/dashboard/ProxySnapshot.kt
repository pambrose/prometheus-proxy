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

package io.prometheus.proxy.dashboard

import io.prometheus.Proxy
import io.prometheus.proxy.ScrapeRecord
import java.time.Instant
import kotlin.time.Duration

/**
 * One path as the dashboard shows it, joined with the last scrape that ran against it.
 *
 * A view may be **departed** — a path that is no longer registered but that [lastScrape] still
 * remembers. Those exist because the proxy deletes a path the moment its last agent goes
 * ([io.prometheus.proxy.ProxyPathManager.removeFromPathManager]), so an agent-backed view of state can
 * only ever show what is currently live. A path that stopped serving is the single most useful thing an
 * operator can be shown, and without this it is the one thing that silently disappears.
 */
internal data class PathView(
  val path: String,
  val agentIds: List<String>,
  val labels: String,
  val targetUrl: String = "",
  val pathSource: String = "",
  val lastScrape: ScrapeRecord? = null,
  val isDeparted: Boolean = false,
  val agentNames: List<String> = emptyList(),
) {
  /**
   * The agent that last served this path, named the way the agent list names it.
   *
   * Deliberately a name and not an [agentIds] entry: the two layouts are read together — an operator
   * finds a failing path here and then looks that agent up in the agent view — and an internal id would
   * force them to translate between the two by hand. For a departed path the name comes from the last
   * scrape, which is the only attribution that outlives the agent.
   */
  val servingAgent: String? get() = agentNames.firstOrNull() ?: lastScrape?.agentName

  /** How many agents beyond the first back this path, for the `+N` marker on a consolidated row. */
  val additionalAgents: Int get() = (agentIds.size - 1).coerceAtLeast(0)
}

/** One connected agent as the dashboard shows it. */
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
  val proxyEndpoints: List<String> = emptyList(),
  val currentEndpointIndex: Int = 0,
) {
  /**
   * How this agent reached this proxy, when it has failover configured — for example
   * `"proxy-b:50051 (2 of 2)"`. Null when the agent reported no endpoints, which covers both a
   * single-endpoint agent and one predating the fields.
   *
   * An index above the first is the visible signal that an agent failed over to this proxy, which the
   * dashboard otherwise cannot show: each proxy sees only its own agents, so a failover looks like a
   * disappearance on one and an appearance on the other.
   */
  val failoverPosition: String?
    get() =
      proxyEndpoints
        .getOrNull(currentEndpointIndex)
        ?.takeIf { proxyEndpoints.size > 1 }
        ?.let { "$it (${currentEndpointIndex + 1} of ${proxyEndpoints.size})" }

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
 * An immutable point-in-time view of everything the dashboard renders.
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
     * Joins each registered path to its most recent scrape, then adds a row for every path the scrape
     * history remembers but the path map no longer holds.
     *
     * Those extra rows are the point of the path-centric layout. `ProxyPathManager` deletes a path the
     * instant its last agent goes, so a view built only from live registrations cannot show a target
     * that *stopped* working — the moment an operator most needs to see it. The scrape history outlives
     * the agent, and is bounded by `proxy.dashboard.recentScrapesQueueSize`, so this cannot grow without limit.
     *
     * Pure and separated from [collect] so it is testable without standing up a proxy.
     *
     * @param registered live path registrations, without their scrape join
     * @param scrapes recent scrapes, **newest first** — as [Proxy.recentScrapes] returns them
     */
    internal fun buildPathViews(
      registered: List<PathView>,
      scrapes: List<ScrapeRecord>,
    ): List<PathView> {
      // Newest-first input means the first record per path is already the latest: no sort, no max-by.
      val latestByPath = scrapes.groupBy { it.path }.mapValues { (_, records) -> records.first() }
      val registeredPaths = registered.mapTo(mutableSetOf()) { it.path }

      val live = registered.map { it.copy(lastScrape = latestByPath[it.path]) }
      val departed =
        latestByPath
          .filterKeys { it !in registeredPaths }
          .map { (path, record) ->
            // agentNames stays empty: the agent is gone, so servingAgent falls through to the name the
            // scrape record captured while it was still here.
            PathView(path = path, agentIds = emptyList(), labels = "", lastScrape = record, isDeparted = true)
          }

      // One sorted list rather than two sections, so a departed path sits where the eye expects to find
      // it instead of somewhere an operator has to think to look.
      return (live + departed).sortedBy { it.path }
    }

    /**
     * Collects a snapshot from live proxy state.
     *
     * **Must not run on a Ktor CIO thread.** `ProxyPathManager` guards its map with `synchronized`, and
     * Kotlin's `synchronized` parks the underlying *carrier* thread rather than suspending the
     * coroutine — so collecting on the event loop would couple the operator dashboard to scrape latency.
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
      val scrapes = proxy.recentScrapes()

      val registered =
        pathInfos
          .map { (path, info) ->
            PathView(
              path = path,
              agentIds = info.agentContexts.map { it.agentId },
              labels = info.labels,
              targetUrl = info.targetUrl,
              pathSource = info.pathSource,
              agentNames = info.agentContexts.map { it.agentName },
            )
          }
          .sortedBy { it.path }

      val paths = buildPathViews(registered, scrapes)

      // Derived from the registered list only: a departed path has no agent to attribute it to, and the
      // agent-centric layout is by definition showing agents that are still here.
      val pathsByAgent =
        paths
          .filterNot { it.isDeparted }
          .flatMap { view -> view.agentIds.map { it to view } }
          .groupBy({ it.first }, { it.second })

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
              proxyEndpoints = context.proxyEndpoints,
              currentEndpointIndex = context.currentEndpointIndex,
            )
          }
          .sortedBy { it.agentName }

      return ProxySnapshot(
        agents = agents,
        paths = paths,
        scrapes = scrapes,
        health =
          HealthView(
            agentCount = agents.size,
            // Live registrations only. A departed path is shown in the table but is not something the
            // proxy is currently serving, so counting it here would overstate the proxy's state.
            pathCount = registered.size,
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
