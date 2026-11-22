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
import io.prometheus.common.dashboard.DashboardLoginOutcome
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
    startAtZero()
  }

  // This agent's series of the counters whose labels have a known set of values, at zero, so increase() and rate() see
  // the first event rather than only a second one. Discovery's and the dashboard's only when the agent has them.
  private fun startAtZero() {
    val launchId = agent.launchId

    fun Counter.atZero(values: List<String> = listOf()) =
      if (values.isEmpty()) labelValues(launchId) else values.forEach { labelValues(launchId, it) }

    CONNECT_COUNT.atZero(listOf(CONNECT_SUCCESS, CONNECT_FAILURE, CONNECT_REJECTED))
    DISCONNECT_COUNT.atZero()
    ENDPOINT_ADVANCE_COUNT.atZero()
    HEARTBEAT_COUNT.atZero(HeartBeatResult.entries.map { it.metricLabel })
    PATH_REJECTION_COUNT.atZero(REJECTION_CAUSES)
    SCRAPE_RETRY_COUNT.atZero()
    if (agent.pathDiscoveryService != null)
      DISCOVERY_RECONCILE_COUNT.atZero(listOf(OUTCOME_SUCCESS, OUTCOME_FAILURE))
    if (agent.dashboardService != null)
      DASHBOARD_LOGIN_COUNT.atZero(DashboardLoginOutcome.entries.map { it.metricLabel })
  }

  override fun close() {
    LABELED.forEach { (metric, labelNames) -> metric.removeSeriesOf(labelNames, agent.launchId) }
    SHARED.release(agent.launchId)
  }

  companion object {
    private const val LAUNCH_ID = "launch_id"
    private const val AGENT_NAME = "agent_name"
    private const val TYPE = "type"
    private const val PATH = "path"
    private const val START_TIME_NAME = "agent_start_time_seconds"
    private const val OUTCOME = "outcome"
    const val CONNECT_SUCCESS = "success"
    const val CONNECT_FAILURE = "failure"
    const val CONNECT_REJECTED = "rejected"
    const val OUTCOME_SUCCESS = "success"
    const val OUTCOME_FAILURE = "failure"
    const val FAIL_OPEN_NON_TEXT = "non_text"
    const val FAIL_OPEN_INVALID_UTF8 = "invalid_utf8"
    const val RESULT_DROPPED = "dropped"

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
    // maxConcurrentHttpClients. Prometheus's scrapes only.
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
          val paths = agent.pathManager.registeredPaths
          PathSource.entries.forEach { source ->
            report(agent.launchId, source.metricLabel) { paths.count { it.source == source }.toDouble() }
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

    // The metrics with series per launch_id, and their label names in order, launch_id first.
    private val LABELED: List<Pair<StatefulMetric<*, *>, List<String>>> =
      listOf(
        SCRAPE_REQUEST_COUNT to listOf(LAUNCH_ID, TYPE),
        SCRAPE_RESULT_COUNT to listOf(LAUNCH_ID, TYPE),
        CONNECT_COUNT to listOf(LAUNCH_ID, TYPE),
        SCRAPE_REQUEST_LATENCY to listOf(LAUNCH_ID, AGENT_NAME),
        FILTER_LINES_DROPPED to listOf(LAUNCH_ID, PATH),
        FILTER_BYTES_SAVED to listOf(LAUNCH_ID, PATH),
        DASHBOARD_SCRAPE_COUNT to listOf(LAUNCH_ID),
        DISCONNECT_COUNT to listOf(LAUNCH_ID),
        ENDPOINT_ADVANCE_COUNT to listOf(LAUNCH_ID),
        HEARTBEAT_COUNT to listOf(LAUNCH_ID, OUTCOME),
        PATH_REJECTION_COUNT to listOf(LAUNCH_ID, "cause"),
        DISCOVERY_RECONCILE_COUNT to listOf(LAUNCH_ID, OUTCOME),
        DISCOVERY_LAST_SUCCESS to listOf(LAUNCH_ID),
        SCRAPE_RETRY_COUNT to listOf(LAUNCH_ID),
        FILTER_FAIL_OPEN_COUNT to listOf(LAUNCH_ID, PATH, "reason"),
        SCRAPE_QUEUE_WAIT to listOf(LAUNCH_ID),
        DASHBOARD_LOGIN_COUNT to listOf(LAUNCH_ID, OUTCOME),
        START_TIME to listOf(LAUNCH_ID),
        BUILD_INFO to listOf(LAUNCH_ID, AGENT_NAME, "version"),
      )

    // Reads the series back with collect(), which is fine at a shutdown: an agent has a few dozen at most.
    private fun StatefulMetric<*, *>.removeSeriesOf(
      labelNames: List<String>,
      launchId: String,
    ) = collect().dataPoints
      .map { point -> labelNames.map { point.labels.get(it).orEmpty() } }
      .filter { it.first() == launchId }
      .forEach { remove(*it.toTypedArray()) }

    private val SHARED: SharedCollectors<Agent> =
      SharedCollectors(
        sentinelName = START_TIME_NAME,
        collectors =
          listOf(
            SCRAPE_REQUEST_COUNT,
            SCRAPE_RESULT_COUNT,
            CONNECT_COUNT,
            SCRAPE_REQUEST_LATENCY,
            FILTER_LINES_DROPPED,
            FILTER_BYTES_SAVED,
            DASHBOARD_SCRAPE_COUNT,
            DISCONNECT_COUNT,
            ENDPOINT_ADVANCE_COUNT,
            HEARTBEAT_COUNT,
            PATH_REJECTION_COUNT,
            DISCOVERY_RECONCILE_COUNT,
            DISCOVERY_LAST_SUCCESS,
            SCRAPE_RETRY_COUNT,
            FILTER_FAIL_OPEN_COUNT,
            SCRAPE_QUEUE_WAIT,
            DASHBOARD_LOGIN_COUNT,
            START_TIME,
            BUILD_INFO,
            SCRAPE_BACKLOG_SIZE,
            CLIENT_CACHE_SIZE,
            CONNECTED,
            PROXY_ENDPOINT_INDEX,
            PATHS,
            DASHBOARD_SESSIONS,
          ),
      )
  }
}
