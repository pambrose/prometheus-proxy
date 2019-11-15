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

package io.prometheus.agent

import com.github.pambrose.common.dsl.PrometheusDsl.counter
import com.github.pambrose.common.dsl.PrometheusDsl.gauge
import com.github.pambrose.common.dsl.PrometheusDsl.summary
import com.github.pambrose.common.metrics.SamplerGaugeCollector
import io.prometheus.Agent
import io.prometheus.client.Counter
import io.prometheus.client.Summary

class AgentMetrics(agent: Agent) {

    val scrapeRequests: Counter =
        counter {
            name("agent_scrape_requests")
            help("Agent scrape requests")
            labelNames("type")
        }

    val connects: Counter =
        counter {
            name("agent_connect_count")
            help("Agent connect counts")
            labelNames("type")
        }

    val scrapeRequestLatency: Summary =
        summary {
            name("agent_scrape_request_latency_seconds")
            help("Agent scrape request latency in seconds")
            labelNames("agent_name")
        }

    init {
        gauge {
            name("agent_start_time_seconds")
            help("Agent start time in seconds")
        }.setToCurrentTime()

        SamplerGaugeCollector("agent_scrape_backlog_size",
                              "Agent scrape backlog size",
                              data = { agent.scrapeRequestBacklogSize.get().toDouble() })
    }
}