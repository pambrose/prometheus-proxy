/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Proxy
import io.prometheus.client.Counter
import io.prometheus.client.Summary
import io.prometheus.common.SamplerGaugeCollector
import io.prometheus.dsl.PrometheusDsl.counter
import io.prometheus.dsl.PrometheusDsl.gauge
import io.prometheus.dsl.PrometheusDsl.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
class ProxyMetrics(proxy: Proxy) {

    val scrapeRequests: Counter =
        counter {
            name("proxy_scrape_requests")
            help("Proxy scrape requests")
            labelNames("type")
        }

    val connects: Counter =
        counter {
            name("proxy_connect_count")
            help("Proxy connect count")
        }

    val agentEvictions: Counter =
        counter {
            name("proxy_eviction_count")
            help("Proxy eviction count")
        }

    val heartbeats: Counter =
        counter {
            name("proxy_heartbeat_count")
            help("Proxy heartbeat count")
        }

    val scrapeRequestLatency: Summary =
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

        SamplerGaugeCollector(name = "proxy_path_map_size",
            help = "Proxy path map size",
            data = { proxy.pathManager.pathMapSize.toDouble() })

        SamplerGaugeCollector(name = "proxy_scrape_map_size",
            help = "Proxy scrape map size",
            data = { proxy.scrapeRequestManager.scrapeMapSize.toDouble() })

        SamplerGaugeCollector(
            name = "proxy_cummulative_agent_backlog_size",
            help = "Proxy cummulative agent backlog size",
            data = { proxy.agentContextManager.totalAgentScrapeRequestBacklogSize.toDouble() })
    }
}
