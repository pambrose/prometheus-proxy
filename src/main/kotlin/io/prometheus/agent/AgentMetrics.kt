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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.prometheus.Agent
import io.prometheus.BuildConfig
import io.prometheus.common.MetricBuilders.REJECTION_CAUSES
import io.prometheus.common.MetricBuilders.metricLabel
import io.prometheus.common.MetricBuilders.LOGIN_OUTCOMES
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.common.MetricBuilders.SCRAPE_LATENCY_BUCKETS
import io.prometheus.common.MetricBuilders.counter
import io.prometheus.common.MetricBuilders.gauge
import io.prometheus.common.MetricBuilders.histogram
import io.prometheus.common.MetricBuilders.sampledGauge
import io.prometheus.common.MetricBuilders.setToCurrentTime
import io.prometheus.common.SharedCollectors
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.metrics.core.metrics.StatefulMetric

/**
 * An agent's handle on the agent metrics, which every agent in the JVM shares (see [SharedCollectors]); each series
 * carries the agent's `launch_id`. Creating one adds the agent to them, registering them if it is the first, and
 * [close] removes the agent and every series with its `launch_id`.
 */
internal class AgentMetrics(
  private val agent: Agent,
) : AutoCloseable {
  val scrapeRequestCount: Counter = SCRAPE_REQUEST_COUNT
  val scrapeResultCount: Counter = SCRAPE_RESULT_COUNT
  val connectCount: Counter = CONNECT_COUNT
  val scrapeRequestLatency: Histogram = SCRAPE_REQUEST_LATENCY
  val filterLinesDropped: Counter = FILTER_LINES_DROPPED
  val filterBytesSaved: Counter = FILTER_BYTES_SAVED
  val dashboardScrapeCount: Counter = DASHBOARD_SCRAPE_COUNT
  val disconnectCount: Counter = DISCONNECT_COUNT
  val endpointAdvanceCount: Counter = ENDPOINT_ADVANCE_COUNT
  val heartbeatCount: Counter = HEARTBEAT_COUNT
  val pathRejectionCount: Counter = PATH_REJECTION_COUNT
  val discoveryReconcileCount: Counter = DISCOVERY_RECONCILE_COUNT
  val discoveryLastSuccess: Gauge = DISCOVERY_LAST_SUCCESS
  val scrapeRetryCount: Counter = SCRAPE_RETRY_COUNT
  val filterFailOpenCount: Counter = FILTER_FAIL_OPEN_COUNT
  val scrapeQueueWait: Histogram = SCRAPE_QUEUE_WAIT
  val dashboardLoginCount: Counter = DASHBOARD_LOGIN_COUNT

  init {
    SHARED.acquire(agent.launchId, agent)
    START_TIME.labelValues(agent.launchId).setToCurrentTime()
    BUILD_INFO.labelValues(agent.launchId, agent.agentName, BuildConfig.APP_VERSION).set(1.0)
    // Each counter's known series, at zero, so increase() and rate() see the first event rather than only a second one.
    PER_LAUNCH.filter { it.startsFor(agent) }.forEach { it.startAtZero(agent.launchId) }
  }

  override fun close() {
    PER_LAUNCH.forEach { it.metric.removeIf { labelValues -> labelValues.first() == agent.launchId } }
    SHARED.release(agent.launchId)
  }

  /**
   * A metric whose series carry `launch_id` first, which [close] removes for the closing agent. [atZero] are the series
   * each agent starts at zero, by the values of its labels after `launch_id`: [EACH] of a label's known values, or
   * [LAUNCH_ONLY] for a counter labeled by `launch_id` alone. [startsFor] says whether an agent has them, as only one
   * with discovery has discovery's.
   */
  private class PerLaunch(
    val metric: StatefulMetric<*, *>,
    private val atZero: List<List<String>> = emptyList(),
    val startsFor: (Agent) -> Boolean = { true },
  ) {
    fun startAtZero(launchId: String) = atZero.forEach { (metric as Counter).labelValues(launchId, *it.toTypedArray()) }
  }

  companion object {
    private const val LAUNCH_ID = "launch_id"
    private const val AGENT_NAME = "agent_name"
    private const val TYPE = "type"
    private const val PATH = "path"
    private const val START_TIME_NAME = "agent_start_time_seconds"
    private const val OUTCOME = "outcome"
    const val SUCCESS = "success"
    const val FAILURE = "failure"
    const val REJECTED = "rejected"
    const val RESULT_NON_GZIPPED = "non-gzipped"
    const val RESULT_GZIPPED = "gzipped"
    const val RESULT_CHUNKED = "chunked"
    const val RESULT_DROPPED = "dropped"

    /**
     * A connect attempt's outcome, from how registering the agent ended: [SUCCESS] for no [failure], [REJECTED] for
     * the proxy refusing the registration, and [FAILURE] for anything else.
     */
    fun connectOutcome(failure: Throwable?): String =
      when (failure) {
        null -> SUCCESS
        is RequestFailureException -> REJECTED
        else -> FAILURE
      }

    // The values of a label each agent starts at zero, as PerLaunch takes them.
    private fun each(values: List<String>) = values.map { listOf(it) }

    private val LAUNCH_ONLY = listOf(emptyList<String>())

    private val SCRAPE_REQUEST_COUNT =
      counter {
        name("agent_scrape_request_count_total")
        help("Agent scrape request count")
        labelNames(LAUNCH_ID, TYPE)
      }

    private val SCRAPE_RESULT_COUNT =
      counter {
        name("agent_scrape_result_count_total")
        help("Agent scrape result count")
        labelNames(LAUNCH_ID, TYPE)
      }

    private val CONNECT_COUNT =
      counter {
        name("agent_connect_count_total")
        help("Agent connect count")
        labelNames(LAUNCH_ID, TYPE)
      }

    private val SCRAPE_REQUEST_LATENCY =
      histogram {
        name("agent_scrape_request_latency_seconds")
        help("Agent scrape request latency in seconds")
        labelNames(LAUNCH_ID, AGENT_NAME)
        classicUpperBounds(*SCRAPE_LATENCY_BUCKETS)
      }

    private val FILTER_LINES_DROPPED =
      counter {
        name("agent_filter_lines_dropped_total")
        help("Agent metric filter lines dropped")
        labelNames(LAUNCH_ID, PATH)
      }

    private val FILTER_BYTES_SAVED =
      counter {
        name("agent_filter_bytes_saved_total")
        help("Agent metric filter bytes saved")
        labelNames(LAUNCH_ID, PATH)
      }

    // The scrapes the proxy's dashboard asked for (its scrape-now), which the agent_scrape_* metrics leave out, so they
    // stay a record of Prometheus's scrapes. No outcome label: the person who asked sees the outcome, and the proxy's
    // activity feed records it, so this counts only the load such scrapes put on the targets.
    private val DASHBOARD_SCRAPE_COUNT =
      counter {
        name("agent_dashboard_scrapes_total")
        help("Agent scrapes the proxy's dashboard asked for, which the agent_scrape_* metrics leave out")
        labelNames(LAUNCH_ID)
      }

    // A connection that ended after the connect succeeded, however it ended.
    private val DISCONNECT_COUNT =
      counter {
        name("agent_disconnects_total")
        help("Agent connections to the proxy that ended")
        labelNames(LAUNCH_ID)
      }

    // A failed connect moving on to the next endpoint in agent.proxy.endpoints; a return to the primary shows as
    // agent_proxy_endpoint_index going back to 0.
    private val ENDPOINT_ADVANCE_COUNT =
      counter {
        name("agent_endpoint_advances_total")
        help("Agent moves to the next proxy endpoint after a failed connect")
        labelNames(LAUNCH_ID)
      }

    private val HEARTBEAT_COUNT =
      counter {
        name("agent_heartbeats_total")
        help("Agent heartbeats sent to the proxy, by outcome")
        labelNames(LAUNCH_ID, OUTCOME)
      }

    // Every refusal, retries included, by the cause the proxy gave, which is unspecified from a proxy predating it.
    private val PATH_REJECTION_COUNT =
      counter {
        name("agent_path_rejections_total")
        help("Agent path registrations the proxy refused, by cause")
        labelNames(LAUNCH_ID, "cause")
      }

    private val DISCOVERY_RECONCILE_COUNT =
      counter {
        name("agent_discovery_reconciles_total")
        help("Agent path discovery reconciles, by outcome")
        labelNames(LAUNCH_ID, OUTCOME)
      }

    private val DISCOVERY_LAST_SUCCESS =
      gauge {
        name("agent_discovery_last_success_timestamp_seconds")
        help("Agent path discovery's last successful reconcile, in Unix seconds")
        labelNames(LAUNCH_ID)
      }

    // agent.scrapeMaxRetries retries of Prometheus's scrapes, not a dashboard's.
    private val SCRAPE_RETRY_COUNT =
      counter {
        name("agent_scrape_retries_total")
        help("Agent retries of a scrape's request to its target")
        labelNames(LAUNCH_ID)
      }

    // A scrape a path's filter let through unfiltered: a body that isn't text, or isn't valid UTF-8.
    private val FILTER_FAIL_OPEN_COUNT =
      counter {
        name("agent_filter_fail_open_total")
        help("Agent scrapes a metric filter passed through unfiltered, by reason")
        labelNames(LAUNCH_ID, PATH, "reason")
      }

    // From the agent receiving a scrape request to its scrape starting: time spent waiting for one of
    // agent.http.maxConcurrentClients. Prometheus's scrapes only.
    private val SCRAPE_QUEUE_WAIT: Histogram =
      histogram {
        name("agent_scrape_queue_wait_seconds")
        help("Agent scrape request wait before its scrape started, in seconds")
        labelNames(LAUNCH_ID)
        classicUpperBounds(*SCRAPE_LATENCY_BUCKETS)
      }

    // By DashboardLoginOutcome, which says what counts as each.
    private val DASHBOARD_LOGIN_COUNT =
      counter {
        name("agent_dashboard_logins_total")
        help("Agent dashboard logins, by outcome")
        labelNames(LAUNCH_ID, OUTCOME)
      }

    private val START_TIME =
      gauge {
        name(START_TIME_NAME)
        labelNames(LAUNCH_ID)
        help("Agent start time in seconds")
      }

    private val BUILD_INFO =
      gauge {
        name("agent_build_info")
        labelNames(LAUNCH_ID, AGENT_NAME, "version")
        help("Agent build information, always 1")
      }

    private val SCRAPE_BACKLOG_SIZE: GaugeWithCallback =
      sampledGauge("agent_scrape_backlog_size", "Agent scrape backlog size", LAUNCH_ID) {
        SHARED.instances().forEach { agent ->
          report(agent.launchId) { agent.scrapeRequestBacklogSize.load().toDouble() }
        }
      }

    private val CLIENT_CACHE_SIZE: GaugeWithCallback =
      sampledGauge("agent_client_cache_size", "Agent client cache size", LAUNCH_ID) {
        SHARED.instances().forEach { agent ->
          report(agent.launchId) { agent.agentHttpService.httpClientCache.currentCacheSize().toDouble() }
        }
      }

    private val CONNECTED: GaugeWithCallback =
      sampledGauge("agent_connected", "Agent connected and registered with a proxy: 1, else 0", LAUNCH_ID) {
        SHARED.instances().forEach { agent ->
          report(agent.launchId) { if (agent.isConnectedToProxy) 1.0 else 0.0 }
        }
      }

    private val PROXY_ENDPOINT_INDEX: GaugeWithCallback =
      sampledGauge(
        "agent_proxy_endpoint_index",
        "Agent proxy endpoint in use, by its place in agent.proxy.endpoints: 0 is the primary",
        LAUNCH_ID,
      ) {
        SHARED.instances().forEach { agent ->
          report(agent.launchId) { agent.grpcService.currentEndpointIndex.toDouble() }
        }
      }

    private val PATHS: GaugeWithCallback =
      sampledGauge("agent_paths", "Agent paths registered with the proxy, by source", LAUNCH_ID, "source") {
        SHARED.instances().forEach { agent ->
          val counts = agent.pathManager.registeredPaths.groupingBy { it.source }.eachCount()
          PathSource.entries.forEach { source ->
            report(agent.launchId, source.metricLabel) { (counts[source] ?: 0).toDouble() }
          }
        }
      }

    // Only an agent with its dashboard on reports one.
    private val DASHBOARD_SESSIONS: GaugeWithCallback =
      sampledGauge("agent_dashboard_sessions", "Agent dashboard pages open", LAUNCH_ID) {
        SHARED.instances().forEach { agent ->
          agent.dashboardService?.also { dashboard -> report(agent.launchId) { dashboard.sessionsOpen.toDouble() } }
        }
      }

    // Each metric whose series carry launch_id first, and the series each agent starts at zero.
    private val PER_LAUNCH: List<PerLaunch> =
      listOf(
        PerLaunch(SCRAPE_REQUEST_COUNT),
        PerLaunch(
          SCRAPE_RESULT_COUNT,
          each(listOf(RESULT_NON_GZIPPED, RESULT_GZIPPED, RESULT_CHUNKED, RESULT_DROPPED)),
        ),
        PerLaunch(CONNECT_COUNT, each(listOf(SUCCESS, FAILURE, REJECTED))),
        PerLaunch(SCRAPE_REQUEST_LATENCY),
        PerLaunch(FILTER_LINES_DROPPED),
        PerLaunch(FILTER_BYTES_SAVED),
        PerLaunch(DASHBOARD_SCRAPE_COUNT),
        PerLaunch(DISCONNECT_COUNT, LAUNCH_ONLY),
        PerLaunch(ENDPOINT_ADVANCE_COUNT, LAUNCH_ONLY),
        PerLaunch(HEARTBEAT_COUNT, each(HeartBeatResult.entries.map { it.metricLabel })),
        PerLaunch(PATH_REJECTION_COUNT, each(REJECTION_CAUSES)),
        PerLaunch(DISCOVERY_RECONCILE_COUNT, each(listOf(SUCCESS, FAILURE))) { it.pathDiscoveryService != null },
        PerLaunch(DISCOVERY_LAST_SUCCESS),
        PerLaunch(SCRAPE_RETRY_COUNT, LAUNCH_ONLY),
        PerLaunch(FILTER_FAIL_OPEN_COUNT),
        PerLaunch(SCRAPE_QUEUE_WAIT),
        PerLaunch(DASHBOARD_LOGIN_COUNT, each(LOGIN_OUTCOMES)) { it.dashboardService != null },
        PerLaunch(START_TIME),
        PerLaunch(BUILD_INFO),
      )

    private val SHARED: SharedCollectors<Agent> =
      SharedCollectors(
        sentinelName = START_TIME_NAME,
        collectors =
          PER_LAUNCH.map { it.metric } +
            listOf(SCRAPE_BACKLOG_SIZE, CLIENT_CACHE_SIZE, CONNECTED, PROXY_ENDPOINT_INDEX, PATHS, DASHBOARD_SESSIONS),
      )
  }
}
