/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.proxy

import io.prometheus.Proxy
import io.prometheus.client.Collector
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary
import io.prometheus.common.SamplerGauge
import io.prometheus.common.SamplerGaugeData

class ProxyMetrics(proxy: Proxy) {

    val scrapeRequests: Counter =
            Counter.build()
                    .name("proxy_scrape_requests")
                    .help("Proxy scrape requests")
                    .labelNames("type")
                    .register()

    val connects: Counter =
            Counter.build()
                    .name("proxy_connect_count")
                    .help("Proxy connect count")
                    .register()

    val agentEvictions: Counter =
            Counter.build()
                    .name("proxy_eviction_count")
                    .help("Proxy eviction count")
                    .register()

    val heartbeats: Counter =
            Counter.build()
                    .name("proxy_heartbeat_count")
                    .help("Proxy heartbeat count")
                    .register()

    val scrapeRequestLatency: Summary =
            Summary.build()
                    .name("proxy_scrape_request_latency_seconds")
                    .help("Proxy scrape request latency in seconds")
                    .register()

    init {
        Gauge.build()
                .name("proxy_start_time_seconds")
                .help("Proxy start time in seconds")
                .register()
                .setToCurrentTime()

        SamplerGauge("proxy_agent_map_size",
                     "Proxy connected agents",
                     SamplerGaugeData { proxy.agentContextSize.toDouble() }).register<Collector>()

        SamplerGauge("proxy_path_map_size",
                     "Proxy path map size",
                     SamplerGaugeData { proxy.pathMapSize.toDouble() }).register<Collector>()

        SamplerGauge("proxy_scrape_map_size",
                     "Proxy scrape map size",
                     SamplerGaugeData { proxy.scrapeMapSize.toDouble() }).register<Collector>()

        SamplerGauge("proxy_cummulative_agent_queue_size",
                     "Proxy cummulative agent queue size",
                     SamplerGaugeData { proxy.totalAgentRequestQueueSize.toDouble() }).register<Collector>()
    }
}
