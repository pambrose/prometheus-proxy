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

import io.prometheus.common.registeredCounts
import io.prometheus.agent.dashboard.AgentDashboardService
import io.mockk.mockk
import io.kotest.inspectors.forAll
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.BuildConfig
import io.prometheus.common.MetricBuilders
import io.prometheus.common.registeredSnapshot
import io.prometheus.common.registeredMetricNames
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.ranges.shouldBeIn
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.prometheus.common.mockAgentForMetrics
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.Labels
import io.prometheus.metrics.core.metrics.MetricWithFixedMetadata
import io.prometheus.metrics.core.metrics.StatefulMetric
import kotlin.time.Clock

// Tests for AgentMetrics which manages Prometheus metrics for the agent component.
// Metrics include counters for scrape requests and results, connect counts,
// and gauges for backlog and cache sizes.
class AgentMetricsTest : StringSpec() {
  private val openMetrics = mutableListOf<AgentMetrics>()

  private fun opened(metrics: AgentMetrics): AgentMetrics = metrics.also { openMetrics += it }

  // Every agent metric the default registry gets, read from AgentMetrics' private COLLECTORS, a companion property, so
  // a static field of the class.
  @Suppress("UNCHECKED_CAST")
  private fun agentCollectors(): List<MetricWithFixedMetadata> =
    AgentMetrics::class.java.getDeclaredField("COLLECTORS").apply { isAccessible = true }.get(null)
      as List<MetricWithFixedMetadata>

  // [metric]'s label names, in the order its builder declared them, which is the order a series' values take; the
  // library keeps them in a protected field, and its getLabelNames() gives an unordered set.
  private fun declaredLabelNames(metric: MetricWithFixedMetadata): List<String> =
    (
      MetricWithFixedMetadata::class.java.getDeclaredField("labelNames").apply { isAccessible = true }.get(metric)
        as Array<*>
    ).map { it.toString() }

  // A mock agent with [launchId], with discovery and a dashboard, so it has every agent metric's series.
  private fun fullyEquippedAgent(launchId: String) =
    mockAgentForMetrics().also { agent ->
      every { agent.launchId } returns launchId
      every { agent.pathDiscoveryService } returns mockk(relaxed = true)
      every { agent.dashboardService } returns mockk<AgentDashboardService>(relaxed = true)
    }

  init {
    // Every agent's metrics are shared in the JVM; closing each test's AgentMetrics removes the series it recorded.
    afterEach {
      openMetrics.forEach { it.close() }
      openMetrics.clear()
    }

    // ==================== Counter Initialization Tests ====================

    "scrapeRequestCount counter should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      metrics.scrapeRequestCount.shouldNotBeNull()
    }

    "scrapeResultCount counter should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      metrics.scrapeResultCount.shouldNotBeNull()
    }

    "connectCount counter should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      metrics.connectCount.shouldNotBeNull()
    }

    // ==================== Histogram Initialization Tests ====================

    "scrapeRequestLatency histogram should be initialized" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      metrics.scrapeRequestLatency.shouldNotBeNull()
    }

    // ==================== Counter Operations Tests ====================

    "scrapeRequestCount should increment with labels" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      val launchId = "test-launch-id"
      val type = "scrape"

      val initialValue = metrics.scrapeRequestCount.labelValues(launchId, type).get()
      metrics.scrapeRequestCount.labelValues(launchId, type).inc()

      metrics.scrapeRequestCount.labelValues(launchId, type).get() shouldBe initialValue + 1
    }

    "scrapeResultCount should increment with labels" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      val launchId = "test-launch-id"
      val type = "success"

      val initialValue = metrics.scrapeResultCount.labelValues(launchId, type).get()
      metrics.scrapeResultCount.labelValues(launchId, type).inc()

      metrics.scrapeResultCount.labelValues(launchId, type).get() shouldBe initialValue + 1
    }

    "connectCount should increment with labels" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      val launchId = "test-launch-id"
      val type = "grpc"

      val initialValue = metrics.connectCount.labelValues(launchId, type).get()
      metrics.connectCount.labelValues(launchId, type).inc()

      metrics.connectCount.labelValues(launchId, type).get() shouldBe initialValue + 1
    }

    // ==================== Histogram Operations Tests ====================

    "scrapeRequestLatency should record observations with labels" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

      val launchId = "test-launch-id"
      val agentName = "test-agent"

      metrics.scrapeRequestLatency.labelValues(launchId, agentName).observe(0.05)
      metrics.scrapeRequestLatency.labelValues(launchId, agentName).observe(0.10)
      metrics.scrapeRequestLatency.labelValues(launchId, agentName).observe(0.15)

      val latencyMetric =
        PrometheusRegistry.defaultRegistry.scrape().find { it.metadata.name == "agent_scrape_request_latency_seconds" }
      latencyMetric.shouldNotBeNull()
    }

    // The agent's default scrape timeout is 15s, so with buckets ending at 10s every agent-side timeout and slow scrape
    // landed in +Inf. The agent's histogram now has the proxy's buckets, which reach the proxy's 90s timeout.
    "scrapeRequestLatency buckets should reach past the agent's scrape timeout" {
      val metrics = opened(AgentMetrics(mockAgentForMetrics()))
      metrics.scrapeRequestLatency.labelValues("test-launch-id", "test-agent").observe(15.0)

      val buckets = metrics.scrapeRequestLatency.collect().dataPoints.single().classicBuckets
      buckets.map { it.upperBound } shouldBe
        listOf(.005, .01, .025, .05, .1, .25, .5, 1.0, 2.5, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0, Double.POSITIVE_INFINITY)
    }

    // ==================== Label Differentiation Tests ====================

    "counters should track different label combinations separately" {
      val agent = mockAgentForMetrics()
      val metrics = opened(AgentMetrics(agent))

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
      val metrics = opened(AgentMetrics(agent))

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
      val before = Clock.System.now().toEpochMilliseconds() / 1_000.0
      opened(AgentMetrics(mockAgentForMetrics()))
      val after = Clock.System.now().toEpochMilliseconds() / 1_000.0

      val gauge =
        PrometheusRegistry.defaultRegistry.scrape()
          .filterIsInstance<GaugeSnapshot>()
          .single { it.metadata.name == "agent_start_time_seconds" }
      val value = gauge.dataPoints.single().value
      value shouldBeIn before..after
    }

    "SamplerGaugeCollector should be constructable with agent metrics" {
      // Creating AgentMetrics should register SamplerGaugeCollectors without exception
      opened(AgentMetrics(mockAgentForMetrics()))

      // Verify the backlog and cache size gauges are registered
      val names = PrometheusRegistry.defaultRegistry.scrape().map { it.metadata.name }
      names shouldContainAll listOf("agent_scrape_backlog_size", "agent_client_cache_size")
    }

    // Built through MetricBuilders, which keeps histograms classic-only (see its KDoc for why).
    // The bucket layout is checked by the "should reach past the agent's scrape timeout" test above.
    "scrapeRequestLatency should keep classic buckets only" {
      val metrics = opened(AgentMetrics(mockAgentForMetrics()))
      metrics.scrapeRequestLatency.labelValues("test-launch-id", "test-agent").observe(0.1)

      metrics.scrapeRequestLatency.collect().dataPoints.single().hasNativeHistogramData().shouldBeFalse()
    }

    // Built through MetricBuilders, which keeps no exemplars (see its KDoc for why).
    "scrapeRequestLatency should keep no exemplars" {
      val metrics = opened(AgentMetrics(mockAgentForMetrics()))
      metrics.scrapeRequestLatency
        .labelValues("test-launch-id", "test-agent")
        .observeWithExemplar(0.1, Labels.of("trace_id", "t"))

      metrics.scrapeRequestLatency.collect().dataPoints.single().exemplars.size() shouldBe 0
    }

    // A scrape the proxy's dashboard asked for is counted here rather than in the agent_scrape_* metrics, under the
    // agent's launch_id alone. Its series goes when that agent's metrics close, while another agent's stays.
    "dashboardScrapeCount should count under launch_id alone and go when its agent's metrics close" {
      val first = opened(AgentMetrics(mockAgentForMetrics()))
      val second = opened(AgentMetrics(mockAgentForMetrics().also { every { it.launchId } returns "other-launch-id" }))
      first.dashboardScrapeCount.labelValues("test-launch-id").inc()
      second.dashboardScrapeCount.labelValues("other-launch-id").inc(2.0)

      fun series() = first.dashboardScrapeCount.collect().dataPoints.associate { it.labels to it.value }

      PrometheusRegistry.defaultRegistry.scrape().map { it.metadata.name } shouldContain "agent_dashboard_scrapes"
      series() shouldBe
        mapOf(Labels.of("launch_id", "test-launch-id") to 1.0, Labels.of("launch_id", "other-launch-id") to 2.0)
      first.close()
      series() shouldBe mapOf(Labels.of("launch_id", "other-launch-id") to 2.0)
    }

    "filter counters should be registered with launch_id and path labels" {
      val metrics = opened(AgentMetrics(mockAgentForMetrics()))

      metrics.filterLinesDropped.labelValues("test-launch-id", "metrics").inc(3.0)
      metrics.filterBytesSaved.labelValues("test-launch-id", "metrics").inc(128.0)

      metrics.filterLinesDropped.labelValues("test-launch-id", "metrics").get() shouldBe 3.0
      metrics.filterBytesSaved.labelValues("test-launch-id", "metrics").get() shouldBe 128.0
    }
    // ==================== Added series ====================

    "the counters, histogram, and gauges added for connections, paths, discovery, and the dashboard should register" {
      opened(AgentMetrics(mockAgentForMetrics()))

      registeredMetricNames() shouldContainAll
        listOf(
          "agent_disconnects",
          "agent_endpoint_advances",
          "agent_heartbeats",
          "agent_path_rejections",
          "agent_discovery_reconciles",
          "agent_discovery_last_success_timestamp_seconds",
          "agent_scrape_retries",
          "agent_filter_fail_open",
          "agent_scrape_queue_wait_seconds",
          "agent_dashboard_logins",
          "agent_build_info",
          "agent_connected",
          "agent_proxy_endpoint_index",
          "agent_paths",
          "agent_dashboard_sessions",
        )
    }

    "agent_build_info should carry the agent's launch ID, name, and version, and go when its metrics close" {
      val leaving = opened(AgentMetrics(mockAgentForMetrics()))
      val staying = mockAgentForMetrics().also { every { it.launchId } returns "staying-launch-id" }
      opened(AgentMetrics(staying))

      fun info() = registeredSnapshot<GaugeSnapshot>("agent_build_info").dataPoints.map { it.labels }

      info().map { it.get("launch_id") }.toSet() shouldBe setOf("test-launch-id", "staying-launch-id")
      info().forAll {
        it.get("agent_name") shouldBe "test-agent"
        it.get("version") shouldBe BuildConfig.APP_VERSION
      }
      leaving.close()
      info().map { it.get("launch_id") } shouldBe listOf("staying-launch-id")
    }

    // A series created by its first increment starts at 1, so increase() and rate() saw nothing of a single
    // disconnect or refused path. Each agent's series of the labels with a known set of values start at zero.
    "an agent's counters with a known set of labels should start at zero, and go when its metrics close" {
      val metrics = opened(AgentMetrics(mockAgentForMetrics()))
      // Another agent keeps the collectors registered once this one's close.
      opened(AgentMetrics(mockAgentForMetrics().also { every { it.launchId } returns "other-launch-id" }))

      fun values(
        name: String,
        label: String? = null,
      ) = registeredCounts(name, label, launchId = "test-launch-id")

      values("agent_connect_count", "type") shouldBe mapOf("success" to 0.0, "failure" to 0.0, "rejected" to 0.0)
      values("agent_scrape_result_count", "type") shouldBe
        mapOf("non-gzipped" to 0.0, "gzipped" to 0.0, "chunked" to 0.0, "dropped" to 0.0)
      values("agent_heartbeats", "outcome") shouldBe
        mapOf("success" to 0.0, "evicted" to 0.0, "transient_failure" to 0.0)
      values("agent_path_rejections", "cause").keys shouldBe MetricBuilders.REJECTION_CAUSES.toSet()
      listOf("agent_disconnects", "agent_endpoint_advances", "agent_scrape_retries").forAll {
        values(it) shouldBe mapOf("" to 0.0)
      }
      // Discovery's and the dashboard's only for an agent that has them.
      values("agent_discovery_reconciles", "outcome") shouldBe emptyMap()
      values("agent_dashboard_logins", "outcome") shouldBe emptyMap()

      metrics.close()
      values("agent_heartbeats", "outcome") shouldBe emptyMap()
    }

    "an agent with discovery and a dashboard should start their counters at zero too" {
      val agent = mockAgentForMetrics()
      every { agent.pathDiscoveryService } returns mockk(relaxed = true)
      every { agent.dashboardService } returns mockk(relaxed = true)
      opened(AgentMetrics(agent))

      registeredCounts("agent_discovery_reconciles", "outcome").keys shouldBe setOf("success", "failure")
      registeredCounts("agent_dashboard_logins", "outcome").keys shouldBe setOf("success", "failure", "blocked")
    }

    // AGENTS.md's rule for an agent metric: launch_id is its first label, and one that keeps series (any but a gauge
    // sampled on each scrape, which reports only the agents still in the JVM) is in PER_LAUNCH, whose series of the
    // closing agent close() removes by their first label's value. A metric that broke it, put among the sampled gauges
    // or with another label first, would still be registered and exposed, and keep a stopped agent's series for as long
    // as another agent in the JVM holds the shared collectors, as when an embedded host starts its agent again. So
    // every collector gets a series for each of two agents, its launch_id where its builder put it, and closing one
    // agent's metrics must take its series from every family and leave the other's.
    "every agent metric should take launch_id first, and lose a closing agent's series while keeping another's" {
      val leaving = opened(AgentMetrics(fullyEquippedAgent(LEAVING)))
      opened(AgentMetrics(fullyEquippedAgent(STAYING)))
      val collectors = agentCollectors()

      // Read at all, so the checks below can't pass on an empty list
      collectors.map { it.prometheusName } shouldContain "agent_scrape_queue_wait_seconds"
      // By family name, so a failure names the family
      collectors.associate { it.prometheusName to declaredLabelNames(it) }.entries.forAll { (_, labelNames) ->
        labelNames.first() shouldBe AgentMetrics.LAUNCH_ID
      }
      collectors.filterIsInstance<StatefulMetric<*, *>>().forEach { metric ->
        val labelNames = declaredLabelNames(metric)
        listOf(LEAVING, STAYING).forEach { launchId ->
          metric.labelValues(
            *labelNames.map { if (it == AgentMetrics.LAUNCH_ID) launchId else "a-$it" }.toTypedArray(),
          )
        }
      }

      // Each family's launch IDs, by its name, so a failure names the family
      fun launchIds() =
        collectors
          .associate { collector ->
            collector.prometheusName to collector.collect().dataPoints.map { it.labels.get(AgentMetrics.LAUNCH_ID) }
          }.entries

      launchIds().forAll { (_, launchIds) -> launchIds shouldContainAll listOf(LEAVING, STAYING) }
      leaving.close()
      launchIds().forAll { (_, launchIds) ->
        launchIds shouldContain STAYING
        launchIds shouldNotContain LEAVING
      }
    }

    // What agent_connect_count_total counts an attempt as, from how registering the agent ended.
    "a connect attempt should count as a success once registered, rejected when refused, and a failure otherwise" {
      connectOutcome(null) shouldBe "success"
      connectOutcome(RequestFailureException.fromProxy("registerAgent", "refused")) shouldBe "rejected"
      connectOutcome(IllegalStateException("UNAVAILABLE")) shouldBe "failure"
    }

    "the sampled gauges should report the agent's connection, endpoint, paths by source, and dashboard pages" {
      val agent = mockAgentForMetrics()
      every { agent.isConnectedToProxy } returns true
      every { agent.grpcService.currentEndpointIndex } returns 2
      every { agent.pathManager.registeredPaths } returns
        listOf(
          AgentPathManager.PathContext(1, "a", "http://a", "{}", PathSource.STATIC),
          AgentPathManager.PathContext(2, "b", "http://b", "{}", PathSource.DISCOVERED),
          AgentPathManager.PathContext(3, "c", "http://c", "{}", PathSource.DISCOVERED),
        )
      val dashboard = mockk<AgentDashboardService>(relaxed = true)
      every { dashboard.sessionsOpen } returns 4
      every { agent.dashboardService } returns dashboard
      opened(AgentMetrics(agent))

      fun gauge(name: String) = registeredSnapshot<GaugeSnapshot>(name).dataPoints

      gauge("agent_connected").single().value shouldBe 1.0
      gauge("agent_proxy_endpoint_index").single().value shouldBe 2.0
      gauge("agent_paths").associate { it.labels.get("source") to it.value } shouldBe
        mapOf("static" to 1.0, "discovered" to 2.0)
      gauge("agent_dashboard_sessions").single().value shouldBe 4.0
    }

    "an agent without a dashboard should report no dashboard pages" {
      opened(AgentMetrics(mockAgentForMetrics()))

      registeredSnapshot<GaugeSnapshot>("agent_dashboard_sessions").dataPoints shouldBe emptyList()
    }
  }

  private companion object {
    const val LEAVING = "leaving-launch-id"
    const val STAYING = "staying-launch-id"
  }
}
