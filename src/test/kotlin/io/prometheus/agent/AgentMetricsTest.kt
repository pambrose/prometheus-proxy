/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.client.CollectorRegistry
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

// Tests for AgentMetrics which manages Prometheus metrics for the agent component.
// Metrics include counters for scrape requests and results, connect counts,
// and gauges for backlog and cache sizes.
@OptIn(ExperimentalAtomicApi::class)
class AgentMetricsTest : FunSpec() {
  private fun createMockAgent(): Agent {
    val mockHttpClientCache = mockk<HttpClientCache>(relaxed = true)
    every { mockHttpClientCache.currentCacheSize() } returns 0

    val mockAgentHttpService = mockk<AgentHttpService>(relaxed = true)
    every { mockAgentHttpService.httpClientCache } returns mockHttpClientCache

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.launchId } returns "test-launch-id"
    every { mockAgent.scrapeRequestBacklogSize } returns AtomicInt(0)
    every { mockAgent.agentHttpService } returns mockAgentHttpService

    return mockAgent
  }

  init {
    beforeEach {
      // Clear the default Prometheus registry to avoid "already registered" errors
      CollectorRegistry.defaultRegistry.clear()
    }

    // ==================== Counter Initialization Tests ====================

    test("scrapeRequestCount counter should be initialized") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      metrics.scrapeRequestCount.shouldNotBeNull()
    }

    test("scrapeResultCount counter should be initialized") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      metrics.scrapeResultCount.shouldNotBeNull()
    }

    test("connectCount counter should be initialized") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      metrics.connectCount.shouldNotBeNull()
    }

    // ==================== Summary Initialization Tests ====================

    test("scrapeRequestLatency summary should be initialized") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      metrics.scrapeRequestLatency.shouldNotBeNull()
    }

    // ==================== Counter Operations Tests ====================

    test("scrapeRequestCount should increment with labels") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "scrape"

      val initialValue = metrics.scrapeRequestCount.labels(launchId, type).get()
      metrics.scrapeRequestCount.labels(launchId, type).inc()

      metrics.scrapeRequestCount.labels(launchId, type).get() shouldBe initialValue + 1
    }

    test("scrapeResultCount should increment with labels") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "success"

      val initialValue = metrics.scrapeResultCount.labels(launchId, type).get()
      metrics.scrapeResultCount.labels(launchId, type).inc()

      metrics.scrapeResultCount.labels(launchId, type).get() shouldBe initialValue + 1
    }

    test("connectCount should increment with labels") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "grpc"

      val initialValue = metrics.connectCount.labels(launchId, type).get()
      metrics.connectCount.labels(launchId, type).inc()

      metrics.connectCount.labels(launchId, type).get() shouldBe initialValue + 1
    }

    // ==================== Summary Operations Tests ====================

    test("scrapeRequestLatency should record observations with labels") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val agentName = "test-agent"

      // Record some latency observations
      metrics.scrapeRequestLatency.labels(launchId, agentName).observe(0.05)
      metrics.scrapeRequestLatency.labels(launchId, agentName).observe(0.10)
      metrics.scrapeRequestLatency.labels(launchId, agentName).observe(0.15)

      // Summary should have recorded the observations
      metrics.scrapeRequestLatency.labels(launchId, agentName).get().count shouldBe 3
    }

    // ==================== Label Differentiation Tests ====================

    test("counters should track different label combinations separately") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"

      // Increment different type labels
      metrics.scrapeRequestCount.labels(launchId, "type-a").inc()
      metrics.scrapeRequestCount.labels(launchId, "type-a").inc()
      metrics.scrapeRequestCount.labels(launchId, "type-b").inc()

      // Different labels should be tracked separately
      metrics.scrapeRequestCount.labels(launchId, "type-a").get() shouldBeGreaterThanOrEqual 2.0
      metrics.scrapeRequestCount.labels(launchId, "type-b").get() shouldBeGreaterThanOrEqual 1.0
    }

    test("multiple counters can be incremented independently") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "test"

      // Increment different counters
      metrics.scrapeRequestCount.labels(launchId, type).inc()
      metrics.scrapeResultCount.labels(launchId, type).inc()
      metrics.scrapeResultCount.labels(launchId, type).inc()
      metrics.connectCount.labels(launchId, type).inc()

      // Each counter should track independently
      metrics.scrapeRequestCount.labels(launchId, type).get() shouldBeGreaterThanOrEqual 1.0
      metrics.scrapeResultCount.labels(launchId, type).get() shouldBeGreaterThanOrEqual 2.0
      metrics.connectCount.labels(launchId, type).get() shouldBeGreaterThanOrEqual 1.0
    }

    // ==================== Gauge Tests ====================

    test("start time gauge should be registered and set") {
      val agent = createMockAgent()
      val metrics = AgentMetrics(agent)

      // The agent_start_time_seconds gauge is created and set in the init block
      // Verify it was registered by checking the default registry
      val samples = CollectorRegistry.defaultRegistry.metricFamilySamples().toList()
      val startTimeMetric = samples.find { it.name == "agent_start_time_seconds" }
      startTimeMetric.shouldNotBeNull()

      // The gauge value should be a positive timestamp
      val sample = startTimeMetric.samples.firstOrNull()
      sample.shouldNotBeNull()
      sample.value shouldBeGreaterThanOrEqual 0.0
    }

    test("SamplerGaugeCollector should be constructable with agent metrics") {
      val agent = createMockAgent()

      // Creating AgentMetrics should register SamplerGaugeCollectors without exception
      val metrics = AgentMetrics(agent)

      // Verify the backlog and cache size gauges are registered
      val samples = CollectorRegistry.defaultRegistry.metricFamilySamples().toList()
      val backlogMetric = samples.find { it.name == "agent_scrape_backlog_size" }
      backlogMetric.shouldNotBeNull()

      val cacheMetric = samples.find { it.name == "agent_client_cache_size" }
      cacheMetric.shouldNotBeNull()
    }
  }
}
