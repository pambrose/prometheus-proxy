/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.dsl.PrometheusDsl.counter
import com.github.pambrose.common.dsl.PrometheusDsl.gauge
import com.github.pambrose.common.metrics.SamplerGaugeCollector
import io.prometheus.Proxy
import io.prometheus.client.Histogram

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
      .labelNames("path")
      .buckets(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0)
      .register()

  val scrapeResponseBytes: Histogram =
    Histogram.build()
      .name("proxy_scrape_response_bytes")
      .help("Proxy scrape response size in bytes")
      .labelNames("path", "encoding")
      .buckets(1_024.0, 10_240.0, 102_400.0, 512_000.0, 1_048_576.0, 5_242_880.0, 10_485_760.0)
      .register()

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
      help("Proxy start time in seconds")
    }.setToCurrentTime()

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
    const val STAGE_CHUNK = "chunk"
    const val STAGE_SUMMARY = "summary"
    const val ENCODING_GZIPPED = "gzipped"
    const val ENCODING_PLAIN = "plain"
  }
}
