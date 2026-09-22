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

import com.pambrose.common.dsl.PrometheusDsl.counter
import com.pambrose.common.dsl.PrometheusDsl.gauge
import com.pambrose.common.metrics.SamplerGaugeCollector
import io.prometheus.Proxy
import io.prometheus.client.Histogram
import java.util.concurrent.ConcurrentHashMap

internal class ProxyMetrics(
  proxy: Proxy,
) {
  val scrapeRequestCount =
    counter {
      name("proxy_scrape_requests")
      help("Proxy scrape requests")
      labelNames("type")
    }

  val connectCount =
    counter {
      name("proxy_connect_count")
      help("Proxy connect count")
    }

  val agentEvictionCount =
    counter {
      name("proxy_eviction_count")
      help("Proxy eviction count")
    }

  val heartbeatCount =
    counter {
      name("proxy_heartbeat_count")
      help("Proxy heartbeat count")
    }

  val scrapeRequestLatency: Histogram =
    Histogram.build()
      .name("proxy_scrape_request_latency_seconds")
      .help("Proxy scrape request latency in seconds")
      .labelNames("path", "outcome")
      // Up to the proxy's default scrapeRequestTimeoutSecs (90), past the agent's default scrapeTimeoutSecs (15), so
      // a timeout lands in a bucket rather than +Inf.
      .buckets(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0)
      .register()

  val scrapeResponseBytes: Histogram =
    Histogram.build()
      .name("proxy_scrape_response_bytes")
      .help("Proxy scrape response size in bytes")
      .labelNames("path", "encoding")
      .buckets(1_024.0, 10_240.0, 102_400.0, 512_000.0, 1_048_576.0, 5_242_880.0, 10_485_760.0)
      .register()

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
      histogram.labels(path, label).observe(value)
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

  val chunkValidationFailures =
    counter {
      name("proxy_chunk_validation_failures_total")
      help("Proxy chunk validation failures")
      labelNames("stage")
    }

  val chunkedTransfersAbandoned =
    counter {
      name("proxy_chunked_transfers_abandoned_total")
      help("Proxy chunked transfers abandoned mid-transfer")
    }

  val agentDisplacementCount =
    counter {
      name("proxy_agent_displacement_total")
      help("Proxy agent path displacement events")
    }

  init {
    gauge {
      name("proxy_start_time_seconds")
      labelNames(LAUNCH_ID)
      help("Proxy start time in seconds")
    }.labels(proxy.launchId).setToCurrentTime()

    SamplerGaugeCollector(
      name = "proxy_agent_map_size",
      help = "Proxy connected agents",
      data = { proxy.agentContextManager.agentContextSize.toDouble() },
    )

    SamplerGaugeCollector(
      name = "proxy_chunk_context_map_size",
      help = "Proxy chunk context map size",
      data = { proxy.agentContextManager.chunkedContextSize.toDouble() },
    )

    SamplerGaugeCollector(
      name = "proxy_path_map_size",
      help = "Proxy path map size",
      data = { proxy.pathManager.pathMapSize.toDouble() },
    )

    SamplerGaugeCollector(
      name = "proxy_scrape_map_size",
      help = "Proxy scrape map size",
      data = { proxy.scrapeRequestManager.scrapeMapSize.toDouble() },
    )

    SamplerGaugeCollector(
      name = "proxy_cumulative_agent_backlog_size",
      help = "Proxy cumulative agent backlog size",
      data = { proxy.agentContextManager.totalAgentScrapeRequestBacklogSize.toDouble() },
    )
  }

  companion object {
    private const val LAUNCH_ID = "launch_id"
    const val STAGE_CHUNK = "chunk"
    const val STAGE_SUMMARY = "summary"
    const val ENCODING_GZIPPED = "gzipped"
    const val ENCODING_PLAIN = "plain"
  }
}
