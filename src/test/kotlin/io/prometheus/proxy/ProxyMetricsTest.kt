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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
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

    "scrapeRequestLatency should record observations with path label" {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestLatency.labels("test-path").observe(0.1)
      metrics.scrapeRequestLatency.labels("test-path").observe(0.2)
      metrics.scrapeRequestLatency.labels("test-path").observe(0.3)

      val samples = CollectorRegistry.defaultRegistry.metricFamilySamples().toList()
      val latencyMetric = samples.find { it.name == "proxy_scrape_request_latency_seconds" }
      latencyMetric.shouldNotBeNull()
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

      metrics.chunkValidationFailures.labels("chunk").inc()
      metrics.chunkValidationFailures.labels("summary").inc()
      metrics.chunkValidationFailures.labels("summary").inc()

      metrics.chunkValidationFailures.labels("chunk").get() shouldBe 1.0
      metrics.chunkValidationFailures.labels("summary").get() shouldBe 2.0
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
