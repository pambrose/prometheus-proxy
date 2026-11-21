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
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.proxy.ActivityEntry
import io.prometheus.proxy.ActivityLog
import io.prometheus.proxy.AgentAuthManager.Companion.LEGACY_IDENTITY_NAME
import io.prometheus.proxy.PathHistory
import io.prometheus.proxy.ScrapeRecord
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One path as the dashboard shows it, joined with the recent scrapes that ran against it.
 *
 * A view may be **departed** — a path that is no longer registered but that the proxy still remembers,
 * by its departure, its scrapes, or both. Those exist because the proxy deletes a path the moment its
 * last agent goes ([io.prometheus.proxy.ProxyPathManager.removeFromPathManager]), so an agent-backed view
 * of state can only ever show what is currently live. A path that stopped serving is the single most
 * useful thing an operator can be shown, and without this it is the one thing that silently disappears.
 *
 * @param targetUrl the URL the path's agent scrapes; for a departed path, the one it scraped as it left
 * @param agentNames the names of the agents backing the path; for a departed path, the agent its departure names, or
 *   none, when the proxy kept only its scrapes
 * @param history the path's recent scrapes, newest first, at most `proxy.dashboard.pathHistorySize` of them
 * @param identity the auth identity of the path's first registrant, which any agent joining it must share; empty with
 *   agent auth off. A departed path keeps the identity it left with, or failing that its last scrape's
 */
internal data class PathView(
  val path: String,
  val agentIds: List<String>,
  val labels: String,
  val targetUrl: String = "",
  val pathSource: String = "",
  val isDeparted: Boolean = false,
  val agentNames: List<String> = emptyList(),
  val history: List<ScrapeRecord> = emptyList(),
  val identity: String = "",
) {
  /** The path's most recent scrape, or null for one not yet scraped. */
  val lastScrape: ScrapeRecord? get() = history.firstOrNull()

  /** Whether the last scrape of this registered path failed. A departed path counts separately. */
  val isFailing: Boolean get() = !isDeparted && lastScrape?.isSuccess == false

  /** What the "problems only" filter keeps: a path whose last scrape failed, or whose agent has gone. */
  val isProblem: Boolean get() = isFailing || isDeparted

  /**
   * The agent that last served this path, named the way the agent list names it.
   *
   * Deliberately a name and not an [agentIds] entry: the two layouts are read together — an operator
   * finds a failing path here and then looks that agent up in the agent view — and an internal id would
   * force them to translate between the two by hand. For a departed path the name comes from its
   * departure, or, when the proxy kept only its scrapes, from the last scrape, both of which outlive the agent.
   */
  val servingAgent: String? get() = agentNames.firstOrNull() ?: lastScrape?.agentName

  /** How many agents beyond the first back this path, for the `+N` marker on a consolidated row. */
  val additionalAgents: Int get() = (agentIds.size - 1).coerceAtLeast(0)

  // The two below are computed once per snapshot, not on every read: a snapshot is rendered for every session and
  // page that shows it.

  /**
   * [history] as runs of consecutive like results, **oldest first**, each a `(succeeded, count)` pair: what the path
   * table's history strip draws, one element per run.
   */
  val historyRuns: List<Pair<Boolean, Int>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    DashboardPage.historyRuns(history.map { it.isSuccess })
  }

  /**
   * The labels the path registered with, by name, which fill a dashboard link's `{label.<name>}` placeholders (see
   * [PathLink]); none for a departed path, whose labels went with its entry. Read tolerantly, since an agent supplied
   * them: [labels] that aren't a JSON object give none, and a label whose value is null, an object, or an array is left
   * out. Parsed only when a link uses a label.
   */
  val labelValues: Map<String, String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    val parsed = runCatching { labels.toJsonElement() }.getOrNull() as? JsonObject
    parsed.orEmpty()
      .mapNotNull { (name, value) ->
        (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let { name to it.content }
      }.toMap()
  }
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
  val identity: String = "",
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

/**
 * An auth identity as the dashboard names it, or null when there is none to show, which is when agent auth is off. The
 * legacy `proxy.agentToken` is an identity in name only, one token every agent shares, so it reads as that rather than
 * as its internal name, which [io.prometheus.proxy.AgentAuthManager.create] keeps any `proxy.auth` identity from
 * taking.
 */
internal fun identityLabel(identity: String): String? =
  when (identity) {
    "" -> null
    LEGACY_IDENTITY_NAME -> "shared token"
    else -> identity
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
  val health: HealthView,
  val maxAgentInactivitySecs: Long,
  // The port Prometheus scrapes, from which the page builds each path's proxy URL.
  val scrapePort: Int,
  // Whether agent auth is on, which is when a path has an identity to show.
  val authEnabled: Boolean = false,
  // The activity feed, newest first; empty with the dashboard off, when nothing records.
  val activity: List<ActivityEntry> = emptyList(),
  // The activity log's change count as [activity] was read with it: equal counts mean equal feeds, which is how the
  // push loop knows a session's page already shows this feed (see ProxyDashboardService.ActivityTable).
  val activityChangeCount: Long = 0L,
  // Whether each live path has a Scrape button, proxy.dashboard.scrapeNowEnabled.
  val scrapeNowEnabled: Boolean = false,
  // Each path's links into other tools, proxy.dashboard.links, parsed at startup; none hides the Links column.
  val links: List<PathLink> = emptyList(),
  // The scrapes a path's history holds when full, proxy.dashboard.pathHistorySize, which each row's History strip
  // keeps a place for; 0 keeps none.
  val pathHistorySize: Int = 0,
) {
  fun agent(agentId: String): AgentView? = agents.firstOrNull { it.agentId == agentId }

  /**
   * Each connected agent's latest scrapes, newest first, at most [MAX_DETAIL_SCRAPES] of each, which its detail's
   * Recent scrapes shows.
   *
   * Read from the paths' histories, every one the snapshot holds, live or gone. A path's history keeps each scrape of
   * it, whichever agent served it, so a consolidated path's holds several agents' scrapes, and an agent's scrapes of a
   * path another agent has since taken are still its own. They came from one queue every path shared, which on a busy
   * proxy held only seconds of history, so an agent's panel could be nearly empty.
   *
   * Computed once per snapshot, at the first read, never per session or page: every session renders the same snapshot,
   * and this walks every scrape it holds, at most the paths it shows times `proxy.dashboard.pathHistorySize`, then
   * sorts each agent's. Lazily, so a snapshot no session shows an agent's detail from costs nothing more. It takes no
   * lock: the histories are the copies [PathHistory.snapshot] made.
   */
  private val agentScrapes: Map<String, List<ScrapeRecord>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    // Only the agents still here: the detail of one that has gone says so rather than listing its scrapes.
    val connected = agents.mapTo(HashSet()) { it.agentId }
    paths
      .asSequence()
      .flatMap { it.history }
      .filter { it.agentId in connected }
      .groupBy { it.agentId }
      .mapValues { (_, records) -> records.sortedByDescending { it.at }.take(MAX_DETAIL_SCRAPES) }
  }

  /** [agentId]'s latest scrapes, newest first, at most [MAX_DETAIL_SCRAPES]; none for an agent not connected. */
  fun scrapesOf(agentId: String): List<ScrapeRecord> = agentScrapes[agentId].orEmpty()

  companion object {
    /** The most scrapes an agent's detail lists, its latest. */
    const val MAX_DETAIL_SCRAPES = 25

    /**
     * Joins each registered path to its recent scrapes, then adds a row for every path the history
     * remembers, by a departure or by scrapes, but the path map no longer holds.
     *
     * Those extra rows are the point of the path-centric layout. `ProxyPathManager` deletes a path the
     * instant its last agent goes, so a view built only from live registrations cannot show a target
     * that *stopped* working — the moment an operator most needs to see it. The history outlives the
     * agent, and [PathHistory] bounds it, by `proxy.dashboard.departedPathRetentionSecs`
     * and a cap on gone paths, so this cannot grow without limit.
     *
     * Pure and separated from [collect] so it is testable without standing up a proxy.
     *
     * @param registered live path registrations, without their scrape join
     * @param history each path's recent scrapes, **newest first**, and each departure — as [PathHistory.snapshot]
     *   returns them
     */
    internal fun buildPathViews(
      registered: List<PathView>,
      history: PathHistory.Snapshot,
    ): List<PathView> {
      val registeredPaths = registered.mapTo(mutableSetOf()) { it.path }

      val live = registered.map { it.copy(history = history.scrapes[it.path].orEmpty()) }
      // A path the path map held as it was read is live, though it may have departed before the history was read; the
      // next refresh shows it gone.
      val departed =
        (history.scrapes.keys + history.departures.keys)
          .filterNot { it in registeredPaths }
          .map { departedView(it, history.scrapes[it].orEmpty(), history.departures[it]) }

      // One sorted list rather than two sections, so a departed path sits where the eye expects to find
      // it instead of somewhere an operator has to think to look.
      return (live + departed).sortedBy { it.path }
    }

    /**
     * The row for [path], whose agent has gone, with its [records], newest first, and its [departure], if the history
     * still holds it. The departure, what the path's entry showed as it left, names the agent, the identity, the
     * target, and the source; without one, the row names the agent and identity its last scrape recorded while the
     * agent was still here, and has no target. A path that left before its first scrape has no records, so its History
     * and last-scrape cells show the en dash.
     */
    private fun departedView(
      path: String,
      records: List<ScrapeRecord>,
      departure: PathHistory.Departure?,
    ): PathView {
      val lastScrape = records.firstOrNull()
      return PathView(
        path = path,
        agentIds = emptyList(),
        labels = "",
        targetUrl = departure?.targetUrl.orEmpty(),
        pathSource = departure?.pathSource.orEmpty(),
        isDeparted = true,
        // Left empty without a departure's name, so servingAgent falls through to the last scrape's.
        agentNames = listOfNotNull(departure?.agentName?.ifEmpty { null }),
        history = records,
        identity = departure?.identity ?: lastScrape?.identity.orEmpty(),
      )
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
     * Cost is three monitor acquisitions per call (the path map, the path history, the activity log),
     * taken one after another and never nested, regardless of how many sessions are connected, which is
     * why the caller collects once and fans out rather than collecting per session.
     */
    fun collect(proxy: Proxy): ProxySnapshot {
      val internal = proxy.proxyConfigVals.internal

      // One monitor acquire; values are immutable so they are safe to hold onto.
      val pathInfos = proxy.pathManager.allPathContextInfos()

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
              identity = info.identityName,
            )
          }
          .sortedBy { it.path }

      // Taken after the path map, so a path registered in between reads as gone for one refresh rather than a path
      // that went reading as live.
      // Null only with the dashboard off, when nothing collects.
      val history = proxy.pathHistory?.snapshot(pathInfos.keys) ?: PathHistory.Snapshot()
      val paths = buildPathViews(registered, history)

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
              identity = context.identityName,
            )
          }
          .sortedBy { it.agentName }

      // The lines and their change count in one read, so the count never claims lines the snapshot doesn't hold.
      val activity = proxy.activityLog?.snapshot() ?: ActivityLog.Snapshot()

      return ProxySnapshot(
        agents = agents,
        paths = paths,
        health =
          HealthView(
            agentCount = agents.size,
            // Live registrations only. A departed path is shown in the table but is not something the
            // proxy is currently serving, so counting it here would overstate the proxy's state.
            pathCount = registered.size,
            chunkContextSize = proxy.agentContextManager.chunkedContextSize,
            chunkContextThreshold = proxy.options.chunkContextMapUnhealthySize,
            scrapeMapSize = proxy.scrapeRequestManager.scrapeMapSize,
            scrapeMapThreshold = proxy.options.scrapeRequestMapUnhealthySize,
          ),
        maxAgentInactivitySecs = internal.maxAgentInactivitySecs.toLong(),
        scrapePort = proxy.scrapePort,
        authEnabled = proxy.agentAuthManager.isEnabled,
        activity = activity.entries,
        activityChangeCount = activity.changeCount,
        scrapeNowEnabled = proxy.proxyConfigVals.dashboard.scrapeNowEnabled,
        links = proxy.options.dashboardLinks,
        pathHistorySize = proxy.proxyConfigVals.dashboard.pathHistorySize,
      )
    }
  }
}
