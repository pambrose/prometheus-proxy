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

import com.pambrose.common.metrics.SystemMetrics
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.TestUtils.stopAll
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO

// A standalone agent's own metrics, served through the proxy at agent.metrics.proxyPath, read in-process, with its
// metrics port off: its agent_* series, and the JVM metric groups its agent.metrics settings turn on, which only its
// port served before. The port's service registers those groups only while the port is on, so the agent registers them
// itself. RegistryMetricsSourceTest covers what the path serves; this covers the whole way, from a config file.
// EmbeddedAgentConfigTest shows that an embedded agent, whose JVM metrics are its host's, registers none of the groups.
class AgentMetricsProxyPathTest : StringSpec() {
  init {
    "a standalone agent serves its own and its JVM's metrics at its proxy path, with no metrics port" {
      PrometheusRegistry.defaultRegistry.clear()
      SystemMetrics.forget(PrometheusRegistry.defaultRegistry)
      val configFile =
        createTempFile("metrics-proxy-path", ".conf").toFile().apply {
          writeText(
            """
            agent {
              name = "standalone-metrics"
              pathConfigs = []
              metrics {
                proxyPath = "/$PROXY_PATH"
                standardExportsEnabled = true
                threadExportsEnabled = true
              }
              internal.reconnectPauseSecs = 1
            }
            """.trimIndent(),
          )
          deleteOnExit()
        }

      var proxy: Proxy? = null
      var agent: Agent? = null
      try {
        // Inside the try, so a proxy that started is stopped when the agent fails to
        proxy = startProxy(args = ["--agent_port", "$GRPC_PORT"], proxyPort = HTTP_PORT)
        agent =
          startAgent(
            configArgs = ["--config", configFile.absolutePath],
            args = ["--proxy", "$LOOPBACK_HOST:$GRPC_PORT"],
          )
        agent.isMetricsEnabled.shouldBeFalse()
        agent.collectsMetrics.shouldBeTrue()
        agent.toString() shouldContain "metricsProxyPath=$PROXY_PATH"

        HttpClient(ClientCIO).use { client ->
          eventually(20.seconds) {
            val response = client.get("http://$LOOPBACK_HOST:$HTTP_PORT/$PROXY_PATH")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText().apply {
              this shouldContain """agent_connected{launch_id="${agent.launchId}"} 1.0"""
              // standardExportsEnabled and threadExportsEnabled
              this shouldContain "process_cpu_seconds_total"
              this shouldContain "jvm_threads_current"
            }
          }
        }
      } finally {
        stopAll(*listOfNotNull(agent, proxy).toTypedArray())
        // The JVM metric groups stay registered once the agent stops, as they do for the metrics port's
        PrometheusRegistry.defaultRegistry.clear()
        SystemMetrics.forget(PrometheusRegistry.defaultRegistry)
      }
    }
  }

  companion object {
    private const val HTTP_PORT = TestPorts.METRICS_PROXY_PATH_HTTP_PORT
    private const val GRPC_PORT = TestPorts.METRICS_PROXY_PATH_GRPC_PORT
    private const val PROXY_PATH = "standalone_agent_metrics"
  }
}
