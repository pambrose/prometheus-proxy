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

package io.prometheus.common

import io.prometheus.metrics.model.snapshots.MetricSnapshot
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.HttpClientCache
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Histogram
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ScrapeRequestManager
import kotlin.concurrent.atomics.AtomicInt

// Mocks that stub just what ProxyMetrics and AgentMetrics read when they are built and scraped: the members behind
// their sampled gauges, and the agent's launch ID.

internal fun mockProxyForMetrics(): Proxy {
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

internal fun mockAgentForMetrics(): Agent {
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

/**
 * A relaxed [ProxyMetrics] whose counters and queue-wait histogram are real collectors, never registered, so a spec
 * reads what a call counted: `connects.get()`, or `pathRejections.labelValues("invalid_path").get()`. [installOn]
 * routes a mocked or spied proxy's `metrics { }` blocks to them.
 */
internal class CountingProxyMetrics {
  val metrics: ProxyMetrics = mockk(relaxed = true)
  val scrapeRequests = stub({ scrapeRequestCount }, counter("scrape_requests", "type"))
  val connects = stub({ connectCount }, counter("connects"))
  val heartbeats = stub({ heartbeatCount }, counter("heartbeats"))
  val evictions = stub({ agentEvictionCount }, counter("evictions"))
  val abandonedTransfers = stub({ chunkedTransfersAbandoned }, counter("abandoned_transfers"))
  val chunkValidationFailures = stub({ chunkValidationFailures }, counter("chunk_validation_failures", "stage"))
  val agentDisconnects = stub({ agentDisconnectCount }, counter("agent_disconnects", "reason"))
  val authFailures = stub({ agentAuthFailureCount }, counter("auth_failures", "reason"))
  val connectRefusals = stub({ agentConnectRefusalCount }, counter("connect_refusals", "reason"))
  val pathRejections = stub({ pathRejectionCount }, counter("path_rejections", "cause"))
  val bindingMismatches = stub({ bindingMismatchCount }, counter("binding_mismatches", "rpc"))
  val lateResults = stub({ lateScrapeResultCount }, counter("late_results"))
  val dashboardLogins = stub({ dashboardLoginCount }, counter("dashboard_logins", "outcome"))
  val queueWait: Histogram = stub({ scrapeQueueWait }, Histogram.builder().name("queue_wait").build())

  /** How many observations the queue-wait histogram holds. */
  val queueWaitCount: Long get() = queueWait.collect().dataPoints.sumOf { it.count }

  /** Routes [proxy]'s `metrics { }` blocks, a mock's or a spy's, to these collectors; returns [proxy]. */
  fun installOn(proxy: Proxy): Proxy =
    proxy.also {
      every { it.metrics(any<ProxyMetrics.() -> Unit>()) } answers { firstArg<ProxyMetrics.() -> Unit>()(metrics) }
    }

  // [collector], which [metrics] answers for the member [member] reads.
  private fun <T : Any> stub(
    member: ProxyMetrics.() -> T,
    collector: T,
  ): T = collector.also { every { metrics.member() } returns it }

  private fun counter(
    name: String,
    vararg labelNames: String,
  ): Counter = Counter.builder().name(name).labelNames(*labelNames).build()
}

/** The names of the metric families registered in the default registry. */
internal fun registeredMetricNames(): List<String> =
  PrometheusRegistry.defaultRegistry.scrape().map {
  it.metadata.name
}

/** The default registry's snapshot of the [T] family named [name], which must be registered. */
internal inline fun <reified T : MetricSnapshot> registeredSnapshot(name: String): T =
  PrometheusRegistry.defaultRegistry.scrape().filterIsInstance<T>().single { it.metadata.name == name }
