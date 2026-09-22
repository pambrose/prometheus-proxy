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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.collections.shouldContainAll
import io.prometheus.Proxy
import io.prometheus.client.CollectorRegistry

// Tests for ProxyMetrics which manages Prometheus metrics for the proxy component.
// Metrics include counters for scrape requests, connects, evictions, heartbeats,
// and gauges for various map sizes.
class ProxyMetricsTest : StringSpec() {
  private fun createMockProxy(): Proxy {
    val mockAgentContextManager = AgentContextManager(isTestMode = true)
    val mockPathManager = mockk<ProxyPathManager>(relaxed = true)
    val mockScrapeRequestManager = ScrapeRequestManager()

    every { mockPathManager.pathMapSize } returns 0

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.agentContextManager } returns mockAgentContextManager
    every { mockProxy.pathManager } returns mockPathManager
    every { mockProxy.scrapeRequestManager } returns mockScrapeRequestManager

    return mockProxy
  }

  // Every path label value across the per-path histograms' series.
  private fun seriesPaths(metrics: ProxyMetrics): Set<String> =
    listOf(metrics.scrapeRequestLatency, metrics.scrapeResponseBytes)
      .flatMap { it.collect() }
      .flatMap { it.samples }
      .map { it.labelValues.first() }
      .toSet()

  init {
    beforeEach {
      // Clear the default Prometheus registry to avoid "already registered" errors
      CollectorRegistry.defaultRegistry.clear()
    }

    // ==================== Counter Initialization Tests ====================

    "scrapeRequestCount counter should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestCount.shouldNotBeNull()
    }

    "connectCount counter should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.connectCount.shouldNotBeNull()
    }

    "agentEvictionCount counter should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.agentEvictionCount.shouldNotBeNull()
    }

    "heartbeatCount counter should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.heartbeatCount.shouldNotBeNull()
    }

    // ==================== Histogram Initialization Tests ====================

    "scrapeRequestLatency histogram should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestLatency.shouldNotBeNull()
    }

    "scrapeResponseBytes histogram should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeResponseBytes.shouldNotBeNull()
    }

    // ==================== Per-path Series Removal Tests ====================

    // A retired path's series used to stay in memory and on /metrics forever. Removal must take every series for the
    // path, whatever its outcome or encoding, and leave other paths alone.
    "removePathSeries should drop every series for the path and keep other paths" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("retired")
      metrics.pathRegistered("kept")
      metrics.observeLatency("retired", "success", 0.1)
      metrics.observeLatency("retired", "timed_out", 0.2)
      metrics.observeLatency("kept", "success", 0.1)
      metrics.observeResponseBytes("retired", "gzipped", 1_000.0)
      metrics.observeResponseBytes("kept", "plain", 1_000.0)

      metrics.removePathSeries("retired")

      seriesPaths(metrics) shouldBe setOf("kept")
    }

    // Removing a path's series ran collect() on both histograms, materializing every sample of every path, once per
    // removed path and inside the path map's lock that every scrape takes. Removal now works from the series the path
    // recorded, so a series created some other way -- which a scan of the histogram would have found -- is untouched.
    "removePathSeries should remove only the series the path recorded, without scanning the histograms" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("retired")
      metrics.observeLatency("retired", "success", 0.1)
      metrics.scrapeRequestLatency.labels("retired", "unrecorded").observe(0.1)

      metrics.removePathSeries("retired")

      metrics.scrapeRequestLatency.collect()
        .flatMap { it.samples }
        .map { it.labelValues.take(2) }
        .toSet() shouldBe setOf(listOf("retired", "unrecorded"))
    }

    // A disconnect removes a path's series before it wakes the scrapes waiting on the agent, and an unregister can
    // land while a scrape is in flight. Either scrape then finished by observing the path again, re-creating the
    // series of a path that was gone, and nothing removed them again.
    "a scrape finishing after its path is removed should not bring the path's series back" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("retired")
      metrics.observeLatency("retired", "success", 0.1)

      metrics.removePathSeries("retired")
      metrics.observeLatency("retired", "agent_disconnected", 0.2)
      metrics.observeResponseBytes("retired", "plain", 1_000.0)

      seriesPaths(metrics) shouldBe emptySet()
    }

    "a path registered again after removal should record its series again" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("returning")
      metrics.removePathSeries("returning")

      metrics.pathRegistered("returning")
      metrics.observeLatency("returning", "success", 0.1)

      seriesPaths(metrics) shouldBe setOf("returning")
    }

    // The agent's default scrape timeout is 15s and the proxy's 90s, so with buckets ending at 10s every timeout
    // landed in +Inf and the slow tail had no resolution.
    "latency buckets should reach the proxy's default scrape request timeout" {
      val metrics = ProxyMetrics(createMockProxy())
      metrics.pathRegistered("slow")
      metrics.observeLatency("slow", "timed_out", 90.0)

      val bounds =
        metrics.scrapeRequestLatency.collect()
          .flatMap { it.samples }
          .filter { it.name.endsWith("_bucket") }
          .map { it.labelValues.last() }
          .toSet()
      bounds shouldContainAll setOf("10.0", "15.0", "30.0", "60.0", "90.0")
    }

    // ==================== New Counter Initialization Tests ====================

    "chunkValidationFailures counter should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.chunkValidationFailures.shouldNotBeNull()
    }

    "chunkedTransfersAbandoned counter should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.chunkedTransfersAbandoned.shouldNotBeNull()
    }

    "agentDisplacementCount counter should be initialized" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.agentDisplacementCount.shouldNotBeNull()
    }

    // ==================== Counter Operations Tests ====================

    "scrapeRequestCount should increment with labels" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.scrapeRequestCount.labels("test-type").get()
      metrics.scrapeRequestCount.labels("test-type").inc()

      metrics.scrapeRequestCount.labels("test-type").get() shouldBe initialValue + 1
    }

    "connectCount should increment" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.connectCount.get()
      metrics.connectCount.inc()

      metrics.connectCount.get() shouldBe initialValue + 1
    }

    "agentEvictionCount should increment" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.agentEvictionCount.get()
      metrics.agentEvictionCount.inc()

      metrics.agentEvictionCount.get() shouldBe initialValue + 1
    }

    "heartbeatCount should increment" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.heartbeatCount.get()
      metrics.heartbeatCount.inc()

      metrics.heartbeatCount.get() shouldBe initialValue + 1
    }

    // ==================== Histogram Operations Tests ====================

    "scrapeRequestLatency should record observations with path and outcome labels" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      // The histogram is labeled by (path, outcome) so latency can be broken down by result.
      metrics.scrapeRequestLatency.labels("test-path", "success").observe(0.1)
      metrics.scrapeRequestLatency.labels("test-path", "success").observe(0.2)
      metrics.scrapeRequestLatency.labels("test-path", "timed_out").observe(0.3)

      val samples = CollectorRegistry.defaultRegistry.metricFamilySamples().toList()
      val latencyMetric = samples.find { it.name == "proxy_scrape_request_latency_seconds" }
      latencyMetric.shouldNotBeNull()
      val outcomes = latencyMetric.samples.mapNotNull { it.labelValues.getOrNull(1) }.toSet()
      outcomes shouldContain "success"
      outcomes shouldContain "timed_out"
    }

    "scrapeResponseBytes should record observations with labels" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeResponseBytes.labels("test-path", "plain").observe(1024.0)
      metrics.scrapeResponseBytes.labels("test-path", "gzipped").observe(512.0)

      val samples = CollectorRegistry.defaultRegistry.metricFamilySamples().toList()
      val bytesMetric = samples.find { it.name == "proxy_scrape_response_bytes" }
      bytesMetric.shouldNotBeNull()
    }

    "chunkValidationFailures should increment with stage labels" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.chunkValidationFailures.labels(ProxyMetrics.STAGE_CHUNK).inc()
      metrics.chunkValidationFailures.labels(ProxyMetrics.STAGE_SUMMARY).inc()
      metrics.chunkValidationFailures.labels(ProxyMetrics.STAGE_SUMMARY).inc()

      metrics.chunkValidationFailures.labels(ProxyMetrics.STAGE_CHUNK).get() shouldBe 1.0
      metrics.chunkValidationFailures.labels(ProxyMetrics.STAGE_SUMMARY).get() shouldBe 2.0
    }

    "chunkedTransfersAbandoned should increment" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.chunkedTransfersAbandoned.get()
      metrics.chunkedTransfersAbandoned.inc()

      metrics.chunkedTransfersAbandoned.get() shouldBe initialValue + 1
    }

    "agentDisplacementCount should increment" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.agentDisplacementCount.get()
      metrics.agentDisplacementCount.inc()

      metrics.agentDisplacementCount.get() shouldBe initialValue + 1
    }

    // ==================== Label Tests ====================

    "scrapeRequestCount should support different label values" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestCount.labels("type-a").inc()
      metrics.scrapeRequestCount.labels("type-a").inc()
      metrics.scrapeRequestCount.labels("type-b").inc()

      // Different labels should be tracked separately
      metrics.scrapeRequestCount.labels("type-a").get() shouldBeGreaterThanOrEqual 2.0
      metrics.scrapeRequestCount.labels("type-b").get() shouldBeGreaterThanOrEqual 1.0
    }
  }
}
