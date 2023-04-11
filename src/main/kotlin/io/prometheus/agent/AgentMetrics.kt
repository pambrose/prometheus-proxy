/*
 * Copyright © 2023 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.dsl.PrometheusDsl.counter
import com.github.pambrose.common.dsl.PrometheusDsl.gauge
import com.github.pambrose.common.dsl.PrometheusDsl.summary
import com.github.pambrose.common.metrics.SamplerGaugeCollector
import io.prometheus.Agent

internal class AgentMetrics(agent: Agent) {

  val scrapeRequestCount =
    counter {
      name("agent_scrape_request_count")
      help("Agent scrape request count")
      labelNames(LAUNCH_ID, TYPE)
    }

  val scrapeResultCount =
    counter {
      name("agent_scrape_result_count")
      help("Agent scrape result count")
      labelNames(LAUNCH_ID, TYPE)
    }

  val connectCount =
    counter {
      name("agent_connect_count")
      help("Agent connect count")
      labelNames(LAUNCH_ID, TYPE)
    }

  val scrapeRequestLatency =
    summary {
      name("agent_scrape_request_latency_seconds")
      help("Agent scrape request latency in seconds")
      labelNames(LAUNCH_ID, AGENT_NAME)
    }

  init {
    gauge {
      name("agent_start_time_seconds")
      labelNames(LAUNCH_ID)
      help("Agent start time in seconds")
    }.labels(agent.launchId).setToCurrentTime()

    SamplerGaugeCollector(
      "agent_scrape_backlog_size",
      "Agent scrape backlog size",
      labelNames = listOf(LAUNCH_ID),
      labelValues = listOf(agent.launchId),
      data = { agent.scrapeRequestBacklogSize.get().toDouble() }
    )
  }

  companion object {
    private const val LAUNCH_ID = "launch_id"
    private const val AGENT_NAME = "agent_name"
    private const val TYPE = "type"
  }
}