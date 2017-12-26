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

package io.prometheus.agent

import io.prometheus.Agent
import io.prometheus.client.Collector
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.Summary
import io.prometheus.common.SamplerGauge
import io.prometheus.common.SamplerGaugeData

class AgentMetrics(agent: Agent) {

    val scrapeRequests: Counter =
            Counter.build()
                    .name("agent_scrape_requests")
                    .help("Agent scrape requests")
                    .labelNames("type")
                    .register()

    val connects: Counter =
            Counter.build()
                    .name("agent_connect_count")
                    .help("Agent connect counts")
                    .labelNames("type")
                    .register()

    val scrapeRequestLatency: Summary =
            Summary.build()
                    .name("agent_scrape_request_latency_seconds")
                    .help("Agent scrape request latency in seconds")
                    .labelNames("agent_name")
                    .register()

    init {
        Gauge.build()
                .name("agent_start_time_seconds")
                .help("Agent start time in seconds")
                .register()
                .setToCurrentTime()

        SamplerGauge("agent_scrape_queue_size",
                     "Agent scrape response queue size",
                     SamplerGaugeData { agent.scrapeResponseQueueSize.toDouble() }).register<Collector>()
    }
}
