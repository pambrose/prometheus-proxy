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

package io.prometheus.proxy

import io.kotest.core.spec.style.FunSpec
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
class ProxyMetricsTest : FunSpec() {
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

    test("scrapeRequestCount counter should be initialized") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestCount.shouldNotBeNull()
    }

    test("connectCount counter should be initialized") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.connectCount.shouldNotBeNull()
    }

    test("agentEvictionCount counter should be initialized") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.agentEvictionCount.shouldNotBeNull()
    }

    test("heartbeatCount counter should be initialized") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.heartbeatCount.shouldNotBeNull()
    }

    // ==================== Summary Initialization Tests ====================

    test("scrapeRequestLatency summary should be initialized") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestLatency.shouldNotBeNull()
    }

    // ==================== Counter Operations Tests ====================

    test("scrapeRequestCount should increment with labels") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.scrapeRequestCount.labels("test-type").get()
      metrics.scrapeRequestCount.labels("test-type").inc()

      metrics.scrapeRequestCount.labels("test-type").get() shouldBe initialValue + 1
    }

    test("connectCount should increment") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.connectCount.get()
      metrics.connectCount.inc()

      metrics.connectCount.get() shouldBe initialValue + 1
    }

    test("agentEvictionCount should increment") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.agentEvictionCount.get()
      metrics.agentEvictionCount.inc()

      metrics.agentEvictionCount.get() shouldBe initialValue + 1
    }

    test("heartbeatCount should increment") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.heartbeatCount.get()
      metrics.heartbeatCount.inc()

      metrics.heartbeatCount.get() shouldBe initialValue + 1
    }

    // ==================== Summary Operations Tests ====================

    test("scrapeRequestLatency should record observations") {
      val proxy = createMockProxy()
      val metrics = ProxyMetrics(proxy)

      // Record some latency observations
      metrics.scrapeRequestLatency.observe(0.1)
      metrics.scrapeRequestLatency.observe(0.2)
      metrics.scrapeRequestLatency.observe(0.3)

      // Summary should have recorded the observations
      metrics.scrapeRequestLatency.get().count shouldBe 3
    }

    // ==================== Label Tests ====================

    test("scrapeRequestCount should support different label values") {
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
