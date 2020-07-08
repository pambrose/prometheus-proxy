/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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
import com.github.pambrose.common.dsl.PrometheusDsl.summary
import com.github.pambrose.common.metrics.SamplerGaugeCollector
import io.prometheus.Proxy

internal class ProxyMetrics(proxy: Proxy) {

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

  val scrapeRequestLatency =
    summary {
      name("proxy_scrape_request_latency_seconds")
      help("Proxy scrape request latency in seconds")
    }

  init {
    gauge {
      name("proxy_start_time_seconds")
      help("Proxy start time in seconds")
    }.setToCurrentTime()

    SamplerGaugeCollector(name = "proxy_agent_map_size",
        help = "Proxy connected agents",
        data = { proxy.agentContextManager.agentContextSize.toDouble() })

    SamplerGaugeCollector(name = "proxy_chunk_context_map_size",
        help = "Proxy chunk context map size",
        data = { proxy.agentContextManager.chunkedContextSize.toDouble() })

    SamplerGaugeCollector(name = "proxy_path_map_size",
        help = "Proxy path map size",
        data = { proxy.pathManager.pathMapSize.toDouble() })

    SamplerGaugeCollector(name = "proxy_scrape_map_size",
        help = "Proxy scrape map size",
        data = { proxy.scrapeRequestManager.scrapeMapSize.toDouble() })

    SamplerGaugeCollector(name = "proxy_cumulative_agent_backlog_size",
        help = "Proxy cumulative agent backlog size",
        data = { proxy.agentContextManager.totalAgentScrapeRequestBacklogSize.toDouble() })
  }
}
