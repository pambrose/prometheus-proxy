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

package io.prometheus.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.common.mockAgentForMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.Labels
import kotlin.concurrent.atomics.AtomicInt

// Tests for AgentMetrics which manages Prometheus metrics for the agent component.
// Metrics include counters for scrape requests and results, connect counts,
// and gauges for backlog and cache sizes.
class AgentMetricsTest : StringSpec() {
  init {
    beforeEach {
      // Clear the default Prometheus registry to avoid "already registered" errors
      PrometheusRegistry.defaultRegistry.clear()
    }

    // ==================== Counter Initialization Tests ====================

    "scrapeRequestCount counter should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      metrics.scrapeRequestCount.shouldNotBeNull()
    }

    "scrapeResultCount counter should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      metrics.scrapeResultCount.shouldNotBeNull()
    }

    "connectCount counter should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      metrics.connectCount.shouldNotBeNull()
    }

    // ==================== Histogram Initialization Tests ====================

    "scrapeRequestLatency histogram should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      metrics.scrapeRequestLatency.shouldNotBeNull()
    }

    // ==================== Counter Operations Tests ====================

    "scrapeRequestCount should increment with labels" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "scrape"

      val initialValue = metrics.scrapeRequestCount.labelValues(launchId, type).get()
      metrics.scrapeRequestCount.labelValues(launchId, type).inc()

      metrics.scrapeRequestCount.labelValues(launchId, type).get() shouldBe initialValue + 1
    }

    "scrapeResultCount should increment with labels" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "success"

      val initialValue = metrics.scrapeResultCount.labelValues(launchId, type).get()
      metrics.scrapeResultCount.labelValues(launchId, type).inc()

      metrics.scrapeResultCount.labelValues(launchId, type).get() shouldBe initialValue + 1
    }

    "connectCount should increment with labels" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "grpc"

      val initialValue = metrics.connectCount.labelValues(launchId, type).get()
      metrics.connectCount.labelValues(launchId, type).inc()

      metrics.connectCount.labelValues(launchId, type).get() shouldBe initialValue + 1
    }

    // ==================== Histogram Operations Tests ====================

    "scrapeRequestLatency should record observations with labels" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val agentName = "test-agent"

      metrics.scrapeRequestLatency.labelValues(launchId, agentName).observe(0.05)
      metrics.scrapeRequestLatency.labelValues(launchId, agentName).observe(0.10)
      metrics.scrapeRequestLatency.labelValues(launchId, agentName).observe(0.15)

      val latencyMetric =
        PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == "agent_scrape_request_latency_seconds" }
      latencyMetric.shouldNotBeNull()
    }

    // ==================== Label Differentiation Tests ====================

    "counters should track different label combinations separately" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"

      // Increment different type labels
      metrics.scrapeRequestCount.labelValues(launchId, "type-a").inc()
      metrics.scrapeRequestCount.labelValues(launchId, "type-a").inc()
      metrics.scrapeRequestCount.labelValues(launchId, "type-b").inc()

      // Different labels should be tracked separately
      metrics.scrapeRequestCount.labelValues(launchId, "type-a").get() shouldBeGreaterThanOrEqual 2.0
      metrics.scrapeRequestCount.labelValues(launchId, "type-b").get() shouldBeGreaterThanOrEqual 1.0
    }

    "multiple counters can be incremented independently" {
      val agent = mockAgentForMetrics()
      val metrics = AgentMetrics(agent)

      val launchId = "test-launch-id"
      val type = "test"

      // Increment different counters
      metrics.scrapeRequestCount.labelValues(launchId, type).inc()
      metrics.scrapeResultCount.labelValues(launchId, type).inc()
      metrics.scrapeResultCount.labelValues(launchId, type).inc()
      metrics.connectCount.labelValues(launchId, type).inc()

      // Each counter should track independently
      metrics.scrapeRequestCount.labelValues(launchId, type).get() shouldBeGreaterThanOrEqual 1.0
      metrics.scrapeResultCount.labelValues(launchId, type).get() shouldBeGreaterThanOrEqual 2.0
      metrics.connectCount.labelValues(launchId, type).get() shouldBeGreaterThanOrEqual 1.0
    }

    // ==================== Gauge Tests ====================

    "start time gauge should hold the start time in Unix seconds" {
      val before = System.currentTimeMillis() / 1_000.0
      AgentMetrics(mockAgentForMetrics())
      val after = System.currentTimeMillis() / 1_000.0

      val gauge =
        PrometheusRegistry.defaultRegistry.scrape()
          .filterIsInstance<GaugeSnapshot>()
          .single { it.metadata.name == "agent_start_time_seconds" }
      val value = gauge.dataPoints.single().value
      value shouldBeIn before..after
    }

    "SamplerGaugeCollector should be constructable with agent metrics" {
      // Creating AgentMetrics should register SamplerGaugeCollectors without exception
      AgentMetrics(mockAgentForMetrics())

      // Verify the backlog and cache size gauges are registered
      val names = PrometheusRegistry.defaultRegistry.scrape().map { it.metadata.name }
      names shouldContainAll listOf("agent_scrape_backlog_size", "agent_client_cache_size")
    }

    // Built through MetricBuilders, which keeps histograms classic-only (see its KDoc for why).
    "scrapeRequestLatency should keep classic buckets only, with its bucket layout" {
      val metrics = AgentMetrics(mockAgentForMetrics())
      metrics.scrapeRequestLatency.labelValues("test-launch-id", "test-agent").observe(0.1)

      val point = metrics.scrapeRequestLatency.collect().dataPoints.single()
      point.hasNativeHistogramData().shouldBeFalse()
      point.classicBuckets.map { it.upperBound } shouldBe
        listOf(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, Double.POSITIVE_INFINITY)
    }

    // Built through MetricBuilders, which keeps no exemplars (see its KDoc for why).
    "scrapeRequestLatency should keep no exemplars" {
      val metrics = AgentMetrics(mockAgentForMetrics())
      metrics.scrapeRequestLatency
        .labelValues("test-launch-id", "test-agent")
        .observeWithExemplar(0.1, Labels.of("trace_id", "t"))

      metrics.scrapeRequestLatency.collect().dataPoints.single().exemplars.size() shouldBe 0
    }

    "filter counters should be registered with launch_id and path labels" {
      val metrics = AgentMetrics(mockAgentForMetrics())

      metrics.filterLinesDropped.labelValues("test-launch-id", "metrics").inc(3.0)
      metrics.filterBytesSaved.labelValues("test-launch-id", "metrics").inc(128.0)

      metrics.filterLinesDropped.labelValues("test-launch-id", "metrics").get() shouldBe 3.0
      metrics.filterBytesSaved.labelValues("test-launch-id", "metrics").get() shouldBe 128.0
    }
  }
}
