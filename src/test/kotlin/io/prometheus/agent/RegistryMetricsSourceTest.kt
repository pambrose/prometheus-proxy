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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.prometheus.common.mockAgentForMetrics
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.io.ByteArrayOutputStream

// A registry served in-process, as MetricsSource.of and the builder's source(path, registry) serve it, and the agent's
// own metrics, as agentMetricsAt serves them: each series served once, at one path.
class RegistryMetricsSourceTest : StringSpec() {
  private val defaultRegistry = PrometheusRegistry.defaultRegistry
  private val openMetrics = mutableListOf<AgentMetrics>()

  // An agent's metrics, as an agent that collects them builds them, under launchId
  private fun agentMetrics(launchId: String): AgentMetrics {
    val agent = mockAgentForMetrics()
    every { agent.launchId } returns launchId
    return AgentMetrics(agent).also { openMetrics += it }
  }

  // What a source writes for a scrape: its content type and its body
  private fun MetricsSource.written(
    accept: String = TEXT,
    names: Set<String> = emptySet(),
  ): Pair<String, String> {
    val out = ByteArrayOutputStream()
    val contentType = write(accept = accept, names = names, out = out)
    return contentType to out.toString(Charsets.UTF_8)
  }

  init {
    afterEach {
      openMetrics.forEach { it.close() }
      openMetrics.clear()
    }

    val registry = PrometheusRegistry()
    Counter.builder().name("orders").help("Orders placed").register(registry).inc(3.0)
    Gauge.builder().name("queue_depth").help("Orders waiting").register(registry).set(7.0)
    val source = MetricsSource.of(registry)

    "writes every family in the text format the scrape accepts" {
      val (contentType, body) = source.written()

      contentType shouldStartWith "text/plain; version=0.0.4"
      body shouldContain "orders_total 3.0"
      body shouldContain "queue_depth 7.0"
    }

    "writes the text format when the scrape names no format" {
      source.written(accept = "").first shouldStartWith "text/plain; version=0.0.4"
    }

    "writes OpenMetrics when the scrape prefers it" {
      val (contentType, body) = source.written(accept = OPEN_METRICS)

      contentType shouldStartWith "application/openmetrics-text"
      body shouldContain "orders_total 3.0"
      body shouldEndWith "# EOF\n"
    }

    // Prometheus's name[] parameter, which the agent passes on as the names
    "writes only the families the scrape names" {
      source.written(names = setOf("queue_depth")).second.also {
        it shouldContain "queue_depth 7.0"
        it shouldNotContain "orders"
      }
    }

    "MetricsSource.of, called from Java, gives the same source" {
      RegistrySourceFromJava.of(registry).written() shouldBe source.written()
    }

    // A plain program's own metrics are in the default registry, beside the agent's, which agentMetricsAt serves
    "a source of the default registry leaves out the agent metrics while an agent collects them" {
      val jobs = Counter.builder().name("app_jobs").help("Jobs run").register().apply { inc() }
      try {
        agentMetrics("app-launch")
        val appSource = MetricsSource.of(defaultRegistry)

        appSource.written().second.also {
          it shouldContain "app_jobs_total 1.0"
          it shouldNotContain "agent_"
        }
        // Named, by either of a counter's names, they're left out too
        appSource.written(names = setOf("agent_disconnects_total", "agent_connected", "app_jobs")).second.also {
          it shouldContain "app_jobs_total 1.0"
          it shouldNotContain "agent_"
        }
      } finally {
        defaultRegistry.unregister(jobs)
      }
    }

    "a source of the default registry serves it all while no agent collects the agent metrics" {
      val jobs = Counter.builder().name("app_jobs").help("Jobs run").register().apply { inc() }
      try {
        MetricsSource.of(defaultRegistry).written().second shouldContain "app_jobs_total 1.0"
      } finally {
        defaultRegistry.unregister(jobs)
      }
    }

    // The agent metrics are only ever in the default registry, so another registry's family of the same name is the
    // app's own
    "a source of another registry keeps a family that has an agent metric's name" {
      agentMetrics("app-launch")
      val other = PrometheusRegistry()
      Gauge.builder().name("agent_connected").help("The app's own").register(other).set(1.0)

      MetricsSource.of(other).written().second shouldContain "agent_connected 1.0"
    }

    "the agent's own source serves its agent metrics, with only its own series, and nothing else in the registry" {
      val jobs = Counter.builder().name("app_jobs").help("Jobs run").register().apply { inc() }
      try {
        val first = agentMetrics("first-launch")
        val second = agentMetrics("second-launch")
        first.scrapeQueueWait.labelValues("first-launch").observe(0.1)
        second.scrapeQueueWait.labelValues("second-launch").observe(0.1)

        val (contentType, body) = AgentMetricsSource { "first-launch" }.written()

        contentType shouldStartWith "text/plain; version=0.0.4"
        // A counter, a gauge, and a histogram, each with this agent's series alone
        body shouldContain """agent_disconnects_total{launch_id="first-launch"} 0.0"""
        body shouldContain """agent_connected{launch_id="first-launch"} 0.0"""
        body shouldContain """agent_scrape_queue_wait_seconds_count{launch_id="first-launch"} 1"""
        body shouldNotContain "second-launch"
        body shouldNotContain "app_jobs"
      } finally {
        defaultRegistry.unregister(jobs)
      }
    }

    "the agent's own source writes only the families the scrape names" {
      agentMetrics("first-launch")
      val source = AgentMetricsSource { "first-launch" }

      source.written(names = setOf("agent_connected")).second.also {
        it shouldContain """agent_connected{launch_id="first-launch"} 0.0"""
        it shouldNotContain "agent_disconnects"
      }
      source.written(names = setOf("agent_disconnects_total")).second.also {
        it shouldContain """agent_disconnects_total{launch_id="first-launch"} 0.0"""
        it shouldNotContain "agent_connected"
      }
    }

    "the agent's own source writes nothing once its agent's metrics are gone" {
      agentMetrics("first-launch").close()
      openMetrics.clear()

      AgentMetricsSource { "first-launch" }.written().second shouldBe ""
    }
  }

  private companion object {
    const val TEXT = "text/plain;version=0.0.4;q=0.5,*/*;q=0.1"
    const val OPEN_METRICS = "application/openmetrics-text;version=1.0.0;q=0.9,$TEXT"
  }
}
