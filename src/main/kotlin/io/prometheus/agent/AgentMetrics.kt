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

  init {
    SHARED.acquire(agent.launchId, agent)
    START_TIME.labelValues(agent.launchId).setToCurrentTime()
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

    private val START_TIME =
      gauge {
        name(START_TIME_NAME)
        labelNames(LAUNCH_ID)
        help("Agent start time in seconds")
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

    // The metrics with series per launch_id, and their label names in order, launch_id first.
    private val LABELED: List<Pair<StatefulMetric<*, *>, List<String>>> =
      listOf(
        SCRAPE_REQUEST_COUNT to listOf(LAUNCH_ID, TYPE),
        SCRAPE_RESULT_COUNT to listOf(LAUNCH_ID, TYPE),
        CONNECT_COUNT to listOf(LAUNCH_ID, TYPE),
        SCRAPE_REQUEST_LATENCY to listOf(LAUNCH_ID, AGENT_NAME),
        FILTER_LINES_DROPPED to listOf(LAUNCH_ID, PATH),
        FILTER_BYTES_SAVED to listOf(LAUNCH_ID, PATH),
        START_TIME to listOf(LAUNCH_ID),
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
            START_TIME,
            SCRAPE_BACKLOG_SIZE,
            CLIENT_CACHE_SIZE,
          ),
      )
  }
}
