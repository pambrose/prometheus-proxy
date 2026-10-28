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

package io.prometheus.harness

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.prometheus.Agent
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds

// A host that runs more than one agent with metrics on -- two at once, or one started again after shutdown() -- got
// an agent that never connected. Each agent registered its own collectors in the default registry, which rejects a
// second metric with the same name and labels, and the rejection escaped connectAgent on every retry. The agents in a
// JVM now share one set of collectors, so each connects and counts its own connects under its launch_id.
class EmbeddedAgentMetricsTest : StringSpec() {
  private fun configFile(
    name: String,
    metricsPort: Int,
  ): File =
    createTempFile("embedded-metrics-agent", ".conf").toFile().apply {
      writeText(
        """
        agent {
          name = "$name"
          proxy {
            hostname = "$LOOPBACK_HOST"
            port = $GRPC_PORT
          }
          pathConfigs = []
          metrics {
            enabled = true
            port = $metricsPort
          }
          internal.reconnectPauseSecs = 1
        }
        """.trimIndent(),
      )
      deleteOnExit()
    }

  private fun startAgent(
    name: String,
    metricsPort: Int,
  ) = Agent.startAsyncAgent(configFile(name, metricsPort).absolutePath, exitOnMissingConfig = false)

  // The agent's agent_connect_count{type="success"} value in the default registry, or null with no such series.
  private fun successfulConnects(launchId: String): Double? =
    PrometheusRegistry.defaultRegistry
      .scrape { it == "agent_connect_count" }
      .flatMap { (it as CounterSnapshot).dataPoints }
      .firstOrNull { it.labels.get("launch_id") == launchId && it.labels.get("type") == "success" }
      ?.value

  init {
    "an agent started again after shutdown() should connect and count its own connect" {
      PrometheusRegistry.defaultRegistry.clear()
      val proxy = startProxy(args = ["--agent_port", "$GRPC_PORT"], proxyPort = HTTP_PORT)
      try {
        val first = startAgent("metrics-agent-first", AGENT_1_METRICS_PORT)
        try {
          eventually(15.seconds) { successfulConnects(first.launchId) shouldBe 1.0 }
        } finally {
          first.shutdown()
        }
        successfulConnects(first.launchId).shouldBeNull()

        // No registry clear in between: the agent's own lifecycle has to leave the registry fit for the next one.
        val second = startAgent("metrics-agent-second", AGENT_1_METRICS_PORT)
        try {
          eventually(15.seconds) { successfulConnects(second.launchId) shouldBe 1.0 }
        } finally {
          second.shutdown()
        }
      } finally {
        proxy.stopSync(10.seconds)
      }
    }

    // The agent builds its metrics as it starts, so a registry that rejects them -- here for a host metric with one of
    // their names -- fails startAsyncAgent, instead of every connect, forever, with nothing reported to the host.
    "a host metric with an agent metric's name should fail the agent's startup" {
      val hostMetric = Gauge.builder().name("agent_connect_count").register()
      try {
        shouldThrow<IllegalStateException> { startAgent("metrics-agent-clash", AGENT_1_METRICS_PORT) }
      } finally {
        PrometheusRegistry.defaultRegistry.unregister(hostMetric)
      }
    }

    "two agents with metrics on should both connect in one JVM" {
      PrometheusRegistry.defaultRegistry.clear()
      val proxy = startProxy(args = ["--agent_port", "$GRPC_PORT"], proxyPort = HTTP_PORT)
      try {
        val first = startAgent("metrics-agent-one", AGENT_1_METRICS_PORT)
        val second = startAgent("metrics-agent-two", AGENT_2_METRICS_PORT)
        try {
          eventually(15.seconds) {
            successfulConnects(first.launchId) shouldBe 1.0
            successfulConnects(second.launchId) shouldBe 1.0
          }
        } finally {
          first.shutdown()
          second.shutdown()
        }
      } finally {
        proxy.stopSync(10.seconds)
      }
    }
  }

  companion object {
    private const val HTTP_PORT = TestPorts.EMBEDDED_METRICS_HTTP_PORT
    private const val GRPC_PORT = TestPorts.EMBEDDED_METRICS_GRPC_PORT
    private const val AGENT_1_METRICS_PORT = TestPorts.EMBEDDED_METRICS_AGENT_1_PORT
    private const val AGENT_2_METRICS_PORT = TestPorts.EMBEDDED_METRICS_AGENT_2_PORT
  }
}
