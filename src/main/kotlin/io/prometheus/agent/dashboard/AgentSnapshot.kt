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

package io.prometheus.agent.dashboard

import io.prometheus.Agent
import io.prometheus.agent.AgentPathManager.PathContext
import io.prometheus.agent.AgentPathManager.RegistrationFailure
import io.prometheus.agent.AgentScrapeRecord
import io.prometheus.agent.PathSource
import io.prometheus.agent.ShownFailure
import io.prometheus.agent.filter.MetricFilter
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.common.dashboard.DashboardPage
import io.prometheus.common.dashboard.SettingsGroup
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.prometheus.agent.AgentActivityEntry
import io.prometheus.agent.AgentActivityLog

/** Where the agent stands with its proxy. */
internal enum class ConnectionState(
  val label: String,
) {
  /** Connected, its paths registered or being registered. */
  CONNECTED("connected"),

  /** Not connected, and no connect has failed since the last success: starting, or reconnecting after a drop. */
  CONNECTING("connecting"),

  /** Not connected, after a connect failed: the proxy is down, unreachable, or refuses the agent. */
  FAILING("failing"),
}

/**
 * One proxy endpoint, in the agent's failover order.
 *
 * @param current whether the agent is connected to it, or trying it now
 * @param failure the latest failure to connect to it, until a connect to it succeeds
 */
internal data class EndpointView(
  val spec: String,
  val current: Boolean,
  val failure: ShownFailure? = null,
)

/**
 * The agent's connection to its proxy, as only the agent can see it: the proxy shows nothing of an agent that can't
 * reach it.
 *
 * @param since when the current connection began, while connected
 * @param connects how many times the agent has connected
 * @param transport how the agent reaches its proxy: plaintext, TLS, or mutual TLS
 * @param tokenSet whether the agent presents a token, never the token
 */
internal data class ConnectionView(
  val state: ConnectionState,
  val endpoints: List<EndpointView>,
  val since: Instant? = null,
  val connects: Int = 0,
  val transport: String = "plaintext",
  val tokenSet: Boolean = false,
) {
  /** The endpoint in use, or being tried. */
  val endpoint: String get() = endpoints.firstOrNull { it.current }?.spec.orEmpty()

  /** Whether the endpoint in use is not the first, the primary. */
  val failedOver: Boolean get() = endpoints.indexOfFirst { it.current } > 0
}

/** Where a path stands with the proxy. */
internal enum class PathState(
  val label: String,
) {
  /** Registered: the proxy routes the path's scrapes to this agent. */
  REGISTERED("registered"),

  /** Not registered: the proxy refused it, or couldn't be asked, for a cause that can clear, so it is tried again. */
  RETRYING("retrying"),

  /** Not registered: the proxy refused it for a cause that can't clear before the agent reconnects. */
  REFUSED("refused"),

  /** Not registered yet: the agent isn't connected, or hasn't reached it. */
  PENDING("pending"),
}

/**
 * A path's metric filter (`agent.filters`), and what it has done.
 *
 * @param failedOpen why it has let a scrape through unfiltered, or null when it never has
 */
internal data class FilterView(
  val allow: List<String>,
  val deny: List<String>,
  val linesDropped: Long,
  val bytesSaved: Long,
  val failedOpen: String? = null,
)

/**
 * One of the agent's paths, configured, discovered, or registered, and where it stands.
 *
 * @param path the path, without its leading slash
 * @param name the name `agent.pathConfigs` gives it, empty for a discovered path
 * @param url the URL it scrapes, its userinfo and query values redacted
 * @param failure its last failure to register, until it registers
 * @param history its recent scrapes as the agent saw them, newest first, at most `agent.dashboard.pathHistorySize`
 */
internal data class AgentPathView(
  val path: String,
  val source: PathSource,
  val url: String,
  val state: PathState,
  val name: String = "",
  val labels: String = "",
  val failure: RegistrationFailure? = null,
  val filter: FilterView? = null,
  val history: List<AgentScrapeRecord> = emptyList(),
) {
  /** The path's most recent scrape, or null for one not scraped since the agent started. */
  val lastScrape: AgentScrapeRecord? get() = history.firstOrNull()

  /** [history] as the strip draws it (see [DashboardPage.historyRuns]); computed once, as a snapshot renders often. */
  val historyRuns: List<Pair<Boolean, Int>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
    DashboardPage.historyRuns(history.map { it.isSuccess })
  }
}

/**
 * The agent's path discovery (`agent.discovery`), and how its last read went.
 *
 * @param failure the last reconcile's failure, or null after a success
 * @param entries how many entries the last good read found
 * @param skipped the entries the last read dropped as unusable, missing a path or a URL, their URLs redacted
 * @param collisions discovered paths that collide with static paths, which keep them
 * @param duplicates discovered paths the file lists more than once
 */
internal data class DiscoveryView(
  val file: String,
  val intervalSecs: Int,
  val failure: ShownFailure? = null,
  val entries: Int = 0,
  val skipped: List<Pair<String, String>> = emptyList(),
  val collisions: List<String> = emptyList(),
  val duplicates: List<String> = emptyList(),
)

/**
 * What the agent's dashboard shows, read from the agent in one pass, so a page and a push render one moment.
 *
 * @param agentId the id the proxy gave the agent when it connected, empty while it isn't
 * @param discovery the agent's path discovery, or null when it is off
 * @param settings the agent's settings, by group, none of them a secret, which its Settings view shows (see
 *   [AgentSettings])
 * @param pathHistorySize the scrapes a path's history holds when full, `agent.dashboard.pathHistorySize`, which each
 *   path's strip keeps the places of
 * @param scrapeNowEnabled whether each path has a Scrape now button, `agent.dashboard.scrapeNowEnabled`
 * @param activity the activity feed's lines, newest first (see [io.prometheus.agent.AgentActivityLog])
 * @param activityChangeCount the activity log's change count, read with [activity], which a push compares to tell a
 *   feed that changed from one that didn't
 */
internal data class AgentSnapshot(
  val agentName: String,
  val agentId: String,
  val launchId: String,
  val uptime: Duration,
  val backlog: Int,
  val connection: ConnectionView,
  val paths: List<AgentPathView>,
  val discovery: DiscoveryView?,
  val settings: List<SettingsGroup> = emptyList(),
  val pathHistorySize: Int = 0,
  val scrapeNowEnabled: Boolean = false,
  val activity: List<AgentActivityEntry> = emptyList(),
  val activityChangeCount: Long = 0L,
) {
  /** The paths not registered: refused, retrying, or still pending. */
  val unregistered: List<AgentPathView> get() = paths.filter { it.state != PathState.REGISTERED }

  /** Which agent this is, in the bar and the tab's title: its name, as the Settings view gives it too. */
  val dashboardName: String get() = "Agent: $agentName"

  companion object {
    /**
     * Reads the snapshot from [agent], with its [settings], which the dashboard reads once ([AgentSettings]), since
     * they don't change while the agent runs, and passes to each collect.
     */
    fun collect(
      agent: Agent,
      settings: List<SettingsGroup> = emptyList(),
    ): AgentSnapshot {
      val activity = agent.activityLog?.snapshot() ?: AgentActivityLog.Snapshot()
      return AgentSnapshot(
        agentName = agent.agentName,
        agentId = agent.agentId,
        launchId = agent.launchId,
        uptime = agent.upTime,
        backlog = agent.scrapeRequestBacklogSize.load(),
        connection = connection(agent),
        paths = paths(agent),
        discovery = discovery(agent),
        settings = settings,
        pathHistorySize = agent.agentConfigVals.dashboard.pathHistorySize,
        scrapeNowEnabled = agent.agentConfigVals.dashboard.scrapeNowEnabled,
        activity = activity.entries,
        activityChangeCount = activity.changeCount,
      )
    }

    private fun connection(agent: Agent): ConnectionView {
      val grpc = agent.grpcService
      val failures = grpc.connectFailuresByEndpoint
      val state =
        when {
          agent.isConnectedToProxy -> ConnectionState.CONNECTED
          grpc.lastConnectFailed -> ConnectionState.FAILING
          else -> ConnectionState.CONNECTING
        }
      val current = grpc.currentEndpointIndex
      return ConnectionView(
        state = state,
        endpoints = grpc.endpointSpecs.mapIndexed { i, spec -> EndpointView(spec, i == current, failures[spec]) },
        since = agent.connectedAt,
        connects = grpc.connects,
        transport = grpc.tlsDescription,
        tokenSet = agent.options.agentToken.isNotEmpty(),
      )
    }

    // Every path the agent knows: configured, discovered, or registered, one row each, by path. Configured first, so a
    // path's name and labels come from its config entry. A path whose registration failed is configured or discovered.
    internal fun paths(agent: Agent): List<AgentPathView> {
      val manager = agent.pathManager
      val registered = manager.registeredPaths.associateBy { it.path }
      val failures = manager.pathFailures
      val histories = agent.scrapeHistory?.snapshot().orEmpty()
      val known =
        configured(agent) + discovered(agent) +
          registered.values.map { KnownPath(it.path, it.source, it.url, labels = it.labels) }
      return known
        .distinctBy { it.path }
        .map {
          view(it, registered[it.path], failures[it.path], manager.filtersByPath[it.path])
            .copy(history = histories[it.path].orEmpty())
        }
        .sortedBy { it.path }
    }

    // A path the agent knows, before it is joined to its registration, its failure, and its filter.
    private class KnownPath(
      val path: String,
      val source: PathSource,
      val url: String,
      val name: String = "",
      val labels: String = "",
    )

    private fun configured(agent: Agent): List<KnownPath> =
      agent.pathManager.pathConfigs.map {
        KnownPath(it.path.removePrefix("/"), PathSource.STATIC, it.url, it.name, it.labels)
      }

    private fun discovered(agent: Agent): List<KnownPath> =
      agent.pathDiscoveryService?.discoveredEntries.orEmpty().map {
        KnownPath(it.path.removePrefix("/"), PathSource.DISCOVERED, it.url, labels = it.labels)
      }

    // [known] as the dashboard shows it: its registration's source, URL, and labels while it has one, its failure, and
    // its filter. Its recent scrapes are joined after.
    private fun view(
      known: KnownPath,
      context: PathContext?,
      failure: RegistrationFailure?,
      filter: MetricFilter?,
    ) = AgentPathView(
      path = known.path,
      source = context?.source ?: known.source,
      url = sanitizeUrl(context?.url ?: known.url),
      state = stateOf(context != null, failure),
      name = known.name,
      labels = shownLabels(context?.labels ?: known.labels),
      failure = failure,
      filter = filter?.let {
        FilterView(it.allowPatterns, it.denyPatterns, it.linesDropped, it.bytesSaved, it.failedOpen)
      },
    )

    // [labels], a JSON object as the config or the agent stores it, or empty for an empty object, which is how the
    // agent stores none, however it's spaced. Anything that isn't an object is shown as given.
    private fun shownLabels(labels: String): String =
      if (runCatching {
        Json.parseToJsonElement(labels).jsonObject.isEmpty()
      }.getOrDefault(labels.isBlank())
      )
        ""
        else
        labels

    private fun stateOf(
      registered: Boolean,
      failure: RegistrationFailure?,
    ): PathState =
      when {
        registered -> PathState.REGISTERED
        failure == null -> PathState.PENDING
        failure.retrying -> PathState.RETRYING
        else -> PathState.REFUSED
      }

    private fun discovery(agent: Agent): DiscoveryView? {
      val service = agent.pathDiscoveryService ?: return null
      val config = agent.agentConfigVals.discovery
      val conflicts = agent.pathManager.discoveryConflicts
      return DiscoveryView(
        file = config.file.path,
        intervalSecs = config.reconcileIntervalSecs,
        failure = service.reconcileFailure,
        entries = service.discoveredEntries.size,
        skipped = service.skippedEntries.map { it.path to sanitizeUrl(it.url) },
        collisions = conflicts.collisions,
        duplicates = conflicts.duplicates,
      )
    }
  }
}
