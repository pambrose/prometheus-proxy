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
import io.prometheus.BuildConfig
import io.prometheus.common.MetricBuilders.SCRAPE_LATENCY_BUCKETS
import io.prometheus.common.MetricBuilders.counter
import io.prometheus.common.MetricBuilders.gauge
import io.prometheus.common.MetricBuilders.histogram
import io.prometheus.common.MetricBuilders.sampledGauge
import io.prometheus.common.MetricBuilders.setToCurrentTime
import io.prometheus.common.MetricBuilders.LOGIN_OUTCOMES
import io.prometheus.common.MetricBuilders.REJECTION_CAUSES
import io.prometheus.common.MetricBuilders.metricLabel
import io.prometheus.common.SharedCollectors
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.metrics.core.metrics.GaugeWithCallback
import io.prometheus.metrics.core.metrics.Histogram
import java.util.concurrent.ConcurrentHashMap

/**
 * A proxy's handle on the proxy metrics, which every proxy in the JVM shares (see [SharedCollectors]). Creating one
 * adds the proxy to them, registering them if it is the first, and [close] removes the proxy, its start time and build
 * info, and the per-path series it recorded. The counters carry no launch_id, so they add up across the proxies, and go
 * when the last proxy closes.
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
  val agentDisconnectCount = AGENT_DISCONNECT_COUNT
  val agentAuthFailureCount = AGENT_AUTH_FAILURE_COUNT
  val agentConnectRefusalCount = AGENT_CONNECT_REFUSAL_COUNT
  val pathRejectionCount = PATH_REJECTION_COUNT
  val bindingMismatchCount = BINDING_MISMATCH_COUNT
  val lateScrapeResultCount = LATE_SCRAPE_RESULT_COUNT
  val scrapeQueueWait: Histogram = SCRAPE_QUEUE_WAIT
  val dashboardLoginCount = DASHBOARD_LOGIN_COUNT

  init {
    SHARED.acquire(launchId, proxy)
    START_TIME.labelValues(launchId).setToCurrentTime()
    BUILD_INFO.labelValues(launchId, BuildConfig.APP_VERSION).set(1.0)
  }

  override fun close() {
    pathSeries.keys.forEach { removePathSeries(it) }
    START_TIME.remove(launchId)
    BUILD_INFO.remove(launchId, BuildConfig.APP_VERSION)
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
    const val AUTH_MISSING_TOKEN = "missing_token"
    const val AUTH_UNKNOWN_TOKEN = "unknown_token"
    const val REFUSAL_IDENTITY_LIMIT = "identity_limit"
    const val REFUSAL_TRANSPORT_FILTER_MISMATCH = "transport_filter_mismatch"

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
      counter(startingAtZero = listOf(STAGE_CHUNK, STAGE_SUMMARY)) {
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

    // By RemovalReason; see Proxy.releaseRemovedAgent for which removals count.
    private val AGENT_DISCONNECT_COUNT =
      counter(startingAtZero = RemovalReason.entries.map { it.metricLabel }) {
        name("proxy_agent_disconnects_total")
        help("Proxy agent disconnects, by why the proxy removed the agent")
        labelNames("reason")
      }

    private val AGENT_AUTH_FAILURE_COUNT =
      counter(startingAtZero = listOf(AUTH_MISSING_TOKEN, AUTH_UNKNOWN_TOKEN)) {
        name("proxy_agent_auth_failures_total")
        help("Proxy agent calls refused for a missing or unknown agent token")
        labelNames("reason")
      }

    private val AGENT_CONNECT_REFUSAL_COUNT =
      counter(startingAtZero = listOf(REFUSAL_IDENTITY_LIMIT, REFUSAL_TRANSPORT_FILTER_MISMATCH)) {
        name("proxy_agent_connect_refusals_total")
        help("Proxy agent connects refused")
        labelNames("reason")
      }

    // Every rejection, by its PathRejectionCause, not only the first for a path, which the activity feed notes.
    private val PATH_REJECTION_COUNT =
      counter(startingAtZero = REJECTION_CAUSES) {
        name("proxy_path_rejections_total")
        help("Proxy path registrations rejected, by cause")
        labelNames("cause")
      }

    // An agent naming an agentId, or answering a scrape, that isn't its connection's, or calling as another identity
    // than the one its agent connected as. A stock agent never does; the rpc label is one of the service's methods.
    private val BINDING_MISMATCH_COUNT =
      counter(startingAtZero = ProxyServiceGrpc.getServiceDescriptor().methods.mapNotNull { it.bareMethodName }) {
        name("proxy_binding_mismatches_total")
        help("Proxy agent calls refused for naming another agent or identity than their connection's")
        labelNames("rpc")
      }

    // A result, or the header of a chunked one, for a scrape whose handler had already returned, usually at
    // Prometheus's or the proxy's timeout, which the handler counted.
    private val LATE_SCRAPE_RESULT_COUNT =
      counter {
        name("proxy_late_scrape_results_total")
        help("Proxy scrape results that arrived after their scrape had ended")
      }

    // From the scrape's submission to the proxy handing it to the agent's stream: the part of the end-to-end latency
    // spent waiting behind the agent's other scrapes. Prometheus's scrapes only, as in the latency histogram.
    private val SCRAPE_QUEUE_WAIT: Histogram =
      histogram {
        name("proxy_scrape_queue_wait_seconds")
        help("Proxy scrape request wait before the agent's stream took it, in seconds")
        classicUpperBounds(*SCRAPE_LATENCY_BUCKETS)
      }

    // By DashboardLoginOutcome, which says what counts as each.
    private val DASHBOARD_LOGIN_COUNT =
      counter(startingAtZero = LOGIN_OUTCOMES) {
        name("proxy_dashboard_logins_total")
        help("Proxy dashboard logins, by outcome")
        labelNames("outcome")
      }

    private val START_TIME =
      gauge {
        name(START_TIME_NAME)
        labelNames(LAUNCH_ID)
        help("Proxy start time in seconds")
      }

    private val BUILD_INFO =
      gauge {
        name("proxy_build_info")
        labelNames(LAUNCH_ID, "version")
        help("Proxy build information, always 1")
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

    private val DASHBOARD_SESSIONS =
      summedGauge("proxy_dashboard_sessions", "Proxy dashboard pages open") { it.dashboardSessionsOpen }

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
            AGENT_DISCONNECT_COUNT,
            AGENT_AUTH_FAILURE_COUNT,
            AGENT_CONNECT_REFUSAL_COUNT,
            PATH_REJECTION_COUNT,
            BINDING_MISMATCH_COUNT,
            LATE_SCRAPE_RESULT_COUNT,
            SCRAPE_QUEUE_WAIT,
            DASHBOARD_LOGIN_COUNT,
            START_TIME,
            BUILD_INFO,
            AGENT_MAP_SIZE,
            CHUNK_CONTEXT_MAP_SIZE,
            PATH_MAP_SIZE,
            SCRAPE_MAP_SIZE,
            CUMULATIVE_AGENT_BACKLOG_SIZE,
            DASHBOARD_SESSIONS,
          ),
      )
  }
}
