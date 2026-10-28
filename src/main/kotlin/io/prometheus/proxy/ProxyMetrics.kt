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

package io.prometheus.proxy

import io.prometheus.Proxy
import io.prometheus.common.MetricBuilders.SCRAPE_LATENCY_BUCKETS
import io.prometheus.common.MetricBuilders.counter
import io.prometheus.common.MetricBuilders.gauge
import io.prometheus.common.MetricBuilders.histogram
import io.prometheus.common.MetricBuilders.sampledGauge
import io.prometheus.common.MetricBuilders.setToCurrentTime
import io.prometheus.common.SharedCollectors
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.core.metrics.Histogram
import java.util.concurrent.ConcurrentHashMap

/**
 * A proxy's handle on the proxy metrics, which every proxy in the JVM shares (see [SharedCollectors]). Creating one
 * adds the proxy to them, registering them if it is the first, and [close] removes the proxy, its start time, and the
 * per-path series it recorded. The counters carry no launch_id, so they add up across the proxies, and go when the
 * last proxy closes.
 */
internal class ProxyMetrics(
  proxy: Proxy,
) : AutoCloseable {
  private val launchId = proxy.launchId

  val scrapeRequestCount = SCRAPE_REQUEST_COUNT
  val connectCount = CONNECT_COUNT
  val agentEvictionCount = AGENT_EVICTION_COUNT
  val heartbeatCount = HEARTBEAT_COUNT
  val scrapeRequestLatency: Histogram = SCRAPE_REQUEST_LATENCY
  val scrapeResponseBytes: Histogram = SCRAPE_RESPONSE_BYTES
  val chunkValidationFailures = CHUNK_VALIDATION_FAILURES
  val chunkedTransfersAbandoned = CHUNKED_TRANSFERS_ABANDONED
  val agentDisplacementCount = AGENT_DISPLACEMENT_COUNT

  init {
    SHARED.acquire(launchId, proxy)
    START_TIME.labelValues(launchId).setToCurrentTime()
  }

  override fun close() {
    pathSeries.keys.forEach { removePathSeries(it) }
    START_TIME.remove(launchId)
    SHARED.release(launchId)
  }

  // The per-path histogram series each registered path has recorded, as (histogram, second label value) pairs. A
  // path is a key exactly while it is registered. Observing and removing both run inside the map's per-key compute, so
  // a scrape that finishes after its path's removal finds no key and records nothing, instead of re-creating series
  // that nothing would remove again.
  private val pathSeries = ConcurrentHashMap<String, MutableSet<Pair<Histogram, String>>>()

  /** Starts recording [path]'s per-path series; called when the path registers. A path already recording is kept. */
  fun pathRegistered(path: String) {
    pathSeries.putIfAbsent(path, ConcurrentHashMap.newKeySet())
  }

  /** Records a scrape of [path] with [outcome] taking [seconds], if [path] is still registered. */
  fun observeLatency(
    path: String,
    outcome: String,
    seconds: Double,
  ) = observe(scrapeRequestLatency, path, outcome, seconds)

  /** Records a [bytes]-byte response for [path] in [encoding], if [path] is still registered. */
  fun observeResponseBytes(
    path: String,
    encoding: String,
    bytes: Double,
  ) = observe(scrapeResponseBytes, path, encoding, bytes)

  private fun observe(
    histogram: Histogram,
    path: String,
    label: String,
    value: Double,
  ) {
    pathSeries.computeIfPresent(path) { _, series ->
      series += histogram to label
      histogram.labelValues(path, label).observe(value)
      series
    }
  }

  /**
   * Removes every series labelled with [path] from the per-path histograms, and stops recording new ones.
   *
   * Called when a path's last registration goes away, so a retired path stops holding series in memory and on
   * `/metrics`. It removes only the series [path] recorded, which it tracks as they are created: reading them back
   * with collect() materialized every sample of every path, and this runs inside the path map's lock, which every
   * scrape takes.
   */
  fun removePathSeries(path: String) {
    pathSeries.compute(path) { _, series ->
      series?.forEach { (histogram, label) -> histogram.remove(path, label) }
      null
    }
  }

  companion object {
    private const val LAUNCH_ID = "launch_id"
    private const val START_TIME_NAME = "proxy_start_time_seconds"
    const val STAGE_CHUNK = "chunk"
    const val STAGE_SUMMARY = "summary"
    const val ENCODING_GZIPPED = "gzipped"
    const val ENCODING_PLAIN = "plain"

    private val SCRAPE_REQUEST_COUNT =
      counter {
        name("proxy_scrape_requests_total")
        help("Proxy scrape requests")
        labelNames("type")
      }

    private val CONNECT_COUNT =
      counter {
        name("proxy_connect_count_total")
        help("Proxy connect count")
      }

    private val AGENT_EVICTION_COUNT =
      counter {
        name("proxy_eviction_count_total")
        help("Proxy eviction count")
      }

    private val HEARTBEAT_COUNT =
      counter {
        name("proxy_heartbeat_count_total")
        help("Proxy heartbeat count")
      }

    private val SCRAPE_REQUEST_LATENCY: Histogram =
      histogram {
        name("proxy_scrape_request_latency_seconds")
        help("Proxy scrape request latency in seconds")
        labelNames("path", "outcome")
        classicUpperBounds(*SCRAPE_LATENCY_BUCKETS)
      }

    private val SCRAPE_RESPONSE_BYTES: Histogram =
      histogram {
        name("proxy_scrape_response_bytes")
        help("Proxy scrape response size in bytes")
        labelNames("path", "encoding")
        classicUpperBounds(1_024.0, 10_240.0, 102_400.0, 512_000.0, 1_048_576.0, 5_242_880.0, 10_485_760.0)
      }

    private val CHUNK_VALIDATION_FAILURES =
      counter {
        name("proxy_chunk_validation_failures_total")
        help("Proxy chunk validation failures")
        labelNames("stage")
      }

    private val CHUNKED_TRANSFERS_ABANDONED =
      counter {
        name("proxy_chunked_transfers_abandoned_total")
        help("Proxy chunked transfers abandoned mid-transfer")
      }

    private val AGENT_DISPLACEMENT_COUNT =
      counter {
        name("proxy_agent_displacement_total")
        help("Proxy agent path displacement events")
      }

    private val START_TIME =
      gauge {
        name(START_TIME_NAME)
        labelNames(LAUNCH_ID)
        help("Proxy start time in seconds")
      }

    private val AGENT_MAP_SIZE =
      summedGauge("proxy_agent_map_size", "Proxy connected agents") { it.agentContextManager.agentContextSize }

    private val CHUNK_CONTEXT_MAP_SIZE =
      summedGauge("proxy_chunk_context_map_size", "Proxy chunk context map size") {
        it.agentContextManager.chunkedContextSize
      }

    private val PATH_MAP_SIZE =
      summedGauge("proxy_path_map_size", "Proxy path map size") { it.pathManager.pathMapSize }

    private val SCRAPE_MAP_SIZE =
      summedGauge("proxy_scrape_map_size", "Proxy scrape map size") { it.scrapeRequestManager.scrapeMapSize }

    private val CUMULATIVE_AGENT_BACKLOG_SIZE =
      summedGauge("proxy_cumulative_agent_backlog_size", "Proxy cumulative agent backlog size") {
        it.agentContextManager.totalAgentScrapeRequestBacklogSize
      }

    // A size every proxy keeps, added up across the proxies in the JVM.
    private fun summedGauge(
      name: String,
      help: String,
      size: (Proxy) -> Number,
    ): GaugeWithCallback = sampledGauge(name, help) { report { SHARED.instances().sumOf { size(it).toDouble() } } }

    // The proxies in one JVM, normally just one, share these: the counters add up across them, and so do the
    // sampled gauges.
    private val SHARED: SharedCollectors<Proxy> =
      SharedCollectors(
        sentinelName = START_TIME_NAME,
        collectors =
          listOf(
            SCRAPE_REQUEST_COUNT,
            CONNECT_COUNT,
            AGENT_EVICTION_COUNT,
            HEARTBEAT_COUNT,
            SCRAPE_REQUEST_LATENCY,
            SCRAPE_RESPONSE_BYTES,
            CHUNK_VALIDATION_FAILURES,
            CHUNKED_TRANSFERS_ABANDONED,
            AGENT_DISPLACEMENT_COUNT,
            START_TIME,
            AGENT_MAP_SIZE,
            CHUNK_CONTEXT_MAP_SIZE,
            PATH_MAP_SIZE,
            SCRAPE_MAP_SIZE,
            CUMULATIVE_AGENT_BACKLOG_SIZE,
          ),
      )
  }
}
