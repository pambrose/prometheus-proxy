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
import io.kotest.inspectors.forAll
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.mockProxyForMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.Labels

// Tests for ProxyMetrics which manages Prometheus metrics for the proxy component.
// Metrics include counters for scrape requests, connects, evictions, heartbeats,
// and gauges for various map sizes.
class ProxyMetricsTest : StringSpec() {
  // Every path label value across the per-path histograms' series.
  private fun seriesPaths(metrics: ProxyMetrics): Set<String> =
    listOf(metrics.scrapeRequestLatency, metrics.scrapeResponseBytes)
      .flatMap { it.collect().dataPoints }
      .mapNotNull { it.labels.get("path") }
      .toSet()

  init {
    beforeEach {
      // Clear the default Prometheus registry to avoid "already registered" errors
      PrometheusRegistry.defaultRegistry.clear()
    }

    // ==================== Counter Initialization Tests ====================

    "scrapeRequestCount counter should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestCount.shouldNotBeNull()
    }

    "connectCount counter should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.connectCount.shouldNotBeNull()
    }

    "agentEvictionCount counter should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.agentEvictionCount.shouldNotBeNull()
    }

    "heartbeatCount counter should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.heartbeatCount.shouldNotBeNull()
    }

    // ==================== Histogram Initialization Tests ====================

    "scrapeRequestLatency histogram should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestLatency.shouldNotBeNull()
    }

    "scrapeResponseBytes histogram should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeResponseBytes.shouldNotBeNull()
    }

    // ==================== Per-path Series Removal Tests ====================

    // A retired path's series used to stay in memory and on /metrics forever. Removal must take every series for the
    // path, whatever its outcome or encoding, and leave other paths alone.
    "removePathSeries should drop every series for the path and keep other paths" {
      val metrics = ProxyMetrics(mockProxyForMetrics())
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
      val metrics = ProxyMetrics(mockProxyForMetrics())
      metrics.pathRegistered("retired")
      metrics.observeLatency("retired", "success", 0.1)
      metrics.scrapeRequestLatency.labelValues("retired", "unrecorded").observe(0.1)

      metrics.removePathSeries("retired")

      metrics.scrapeRequestLatency.collect().dataPoints
        .map { listOf(it.labels.get("path"), it.labels.get("outcome")) }
        .toSet() shouldBe setOf(listOf("retired", "unrecorded"))
    }

    // A disconnect removes a path's series before it wakes the scrapes waiting on the agent, and an unregister can
    // land while a scrape is in flight. Either scrape then finished by observing the path again, re-creating the
    // series of a path that was gone, and nothing removed them again.
    "a scrape finishing after its path is removed should not bring the path's series back" {
      val metrics = ProxyMetrics(mockProxyForMetrics())
      metrics.pathRegistered("retired")
      metrics.observeLatency("retired", "success", 0.1)

      metrics.removePathSeries("retired")
      metrics.observeLatency("retired", "agent_disconnected", 0.2)
      metrics.observeResponseBytes("retired", "plain", 1_000.0)

      seriesPaths(metrics) shouldBe emptySet()
    }

    "a path registered again after removal should record its series again" {
      val metrics = ProxyMetrics(mockProxyForMetrics())
      metrics.pathRegistered("returning")
      metrics.removePathSeries("returning")

      metrics.pathRegistered("returning")
      metrics.observeLatency("returning", "success", 0.1)

      seriesPaths(metrics) shouldBe setOf("returning")
    }

    // The agent's default scrape timeout is 15s and the proxy's 90s, so with buckets ending at 10s every timeout
    // landed in +Inf and the slow tail had no resolution.
    "latency buckets should reach the proxy's default scrape request timeout" {
      val metrics = ProxyMetrics(mockProxyForMetrics())
      metrics.pathRegistered("slow")
      metrics.observeLatency("slow", "timed_out", 90.0)

      val buckets = metrics.scrapeRequestLatency.collect().dataPoints.single().classicBuckets
      buckets.map { it.upperBound } shouldBe
        listOf(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0, Double.POSITIVE_INFINITY)
    }

    "scrapeResponseBytes should keep its bucket layout" {
      val metrics = ProxyMetrics(mockProxyForMetrics())
      metrics.pathRegistered("p")
      metrics.observeResponseBytes("p", ProxyMetrics.ENCODING_PLAIN, 1_024.0)

      val buckets = metrics.scrapeResponseBytes.collect().dataPoints.single().classicBuckets
      buckets.map { it.upperBound } shouldBe
        listOf(
          1_024.0,
          10_240.0,
          102_400.0,
          512_000.0,
          1_048_576.0,
          5_242_880.0,
          10_485_760.0,
          Double.POSITIVE_INFINITY,
        )
    }

    // Built through MetricBuilders, which keeps histograms classic-only (see its KDoc for why).
    "per-path histograms should keep classic buckets only" {
      val metrics = ProxyMetrics(mockProxyForMetrics())
      metrics.pathRegistered("p")
      metrics.observeLatency("p", "success", 0.1)
      metrics.observeResponseBytes("p", ProxyMetrics.ENCODING_PLAIN, 1_024.0)

      listOf(metrics.scrapeRequestLatency, metrics.scrapeResponseBytes)
        .flatMap { it.collect().dataPoints }
        .forAll { it.hasNativeHistogramData().shouldBeFalse() }
    }

    // Built through MetricBuilders, which keeps no exemplars (see its KDoc for why).
    "per-path histograms should keep no exemplars" {
      val metrics = ProxyMetrics(mockProxyForMetrics())
      metrics.scrapeRequestLatency.labelValues("p", "success").observeWithExemplar(0.1, Labels.of("trace_id", "t"))
      metrics.scrapeResponseBytes
        .labelValues("p", ProxyMetrics.ENCODING_PLAIN)
        .observeWithExemplar(1_024.0, Labels.of("trace_id", "t"))

      listOf(metrics.scrapeRequestLatency, metrics.scrapeResponseBytes)
        .flatMap { it.collect().dataPoints }
        .forAll { it.exemplars.size() shouldBe 0 }
    }

    // ==================== Gauge Tests ====================

    "proxy start time gauge should hold the start time in Unix seconds" {
      val before = System.currentTimeMillis() / 1_000.0
      ProxyMetrics(mockProxyForMetrics())
      val after = System.currentTimeMillis() / 1_000.0

      val gauge =
        PrometheusRegistry.defaultRegistry.scrape()
          .filterIsInstance<GaugeSnapshot>()
          .single { it.metadata.name == "proxy_start_time_seconds" }
      val value = gauge.dataPoints.single().value
      value shouldBeIn before..after
    }

    // ==================== New Counter Initialization Tests ====================

    "chunkValidationFailures counter should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.chunkValidationFailures.shouldNotBeNull()
    }

    "chunkedTransfersAbandoned counter should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.chunkedTransfersAbandoned.shouldNotBeNull()
    }

    "agentDisplacementCount counter should be initialized" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.agentDisplacementCount.shouldNotBeNull()
    }

    // ==================== Counter Operations Tests ====================

    "scrapeRequestCount should increment with labels" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.scrapeRequestCount.labelValues("test-type").get()
      metrics.scrapeRequestCount.labelValues("test-type").inc()

      metrics.scrapeRequestCount.labelValues("test-type").get() shouldBe initialValue + 1
    }

    "connectCount should increment" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.connectCount.get()
      metrics.connectCount.inc()

      metrics.connectCount.get() shouldBe initialValue + 1
    }

    "agentEvictionCount should increment" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.agentEvictionCount.get()
      metrics.agentEvictionCount.inc()

      metrics.agentEvictionCount.get() shouldBe initialValue + 1
    }

    "heartbeatCount should increment" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.heartbeatCount.get()
      metrics.heartbeatCount.inc()

      metrics.heartbeatCount.get() shouldBe initialValue + 1
    }

    // ==================== Histogram Operations Tests ====================

    "scrapeRequestLatency should record observations with path and outcome labels" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      // The histogram is labeled by (path, outcome) so latency can be broken down by result.
      metrics.scrapeRequestLatency.labelValues("test-path", "success").observe(0.1)
      metrics.scrapeRequestLatency.labelValues("test-path", "success").observe(0.2)
      metrics.scrapeRequestLatency.labelValues("test-path", "timed_out").observe(0.3)

      val latencyMetric =
        PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == "proxy_scrape_request_latency_seconds" }
      latencyMetric.shouldNotBeNull()
      val outcomes = latencyMetric.dataPoints.mapNotNull { it.labels.get("outcome") }.toSet()
      outcomes shouldContain "success"
      outcomes shouldContain "timed_out"
    }

    "scrapeResponseBytes should record observations with labels" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeResponseBytes.labelValues("test-path", "plain").observe(1024.0)
      metrics.scrapeResponseBytes.labelValues("test-path", "gzipped").observe(512.0)

      val bytesMetric =
        PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == "proxy_scrape_response_bytes" }
      bytesMetric.shouldNotBeNull()
    }

    "chunkValidationFailures should increment with stage labels" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.chunkValidationFailures.labelValues(ProxyMetrics.STAGE_CHUNK).inc()
      metrics.chunkValidationFailures.labelValues(ProxyMetrics.STAGE_SUMMARY).inc()
      metrics.chunkValidationFailures.labelValues(ProxyMetrics.STAGE_SUMMARY).inc()

      metrics.chunkValidationFailures.labelValues(ProxyMetrics.STAGE_CHUNK).get() shouldBe 1.0
      metrics.chunkValidationFailures.labelValues(ProxyMetrics.STAGE_SUMMARY).get() shouldBe 2.0
    }

    "chunkedTransfersAbandoned should increment" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.chunkedTransfersAbandoned.get()
      metrics.chunkedTransfersAbandoned.inc()

      metrics.chunkedTransfersAbandoned.get() shouldBe initialValue + 1
    }

    "agentDisplacementCount should increment" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      val initialValue = metrics.agentDisplacementCount.get()
      metrics.agentDisplacementCount.inc()

      metrics.agentDisplacementCount.get() shouldBe initialValue + 1
    }

    // ==================== Label Tests ====================

    "scrapeRequestCount should support different label values" {
      val proxy = mockProxyForMetrics()
      val metrics = ProxyMetrics(proxy)

      metrics.scrapeRequestCount.labelValues("type-a").inc()
      metrics.scrapeRequestCount.labelValues("type-a").inc()
      metrics.scrapeRequestCount.labelValues("type-b").inc()

      // Different labels should be tracked separately
      metrics.scrapeRequestCount.labelValues("type-a").get() shouldBeGreaterThanOrEqual 2.0
      metrics.scrapeRequestCount.labelValues("type-b").get() shouldBeGreaterThanOrEqual 1.0
    }
  }
}
