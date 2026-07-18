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

package io.prometheus.containers

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.prometheus.agent.discovery.DiscoveryTestSupport.discoveryPathsHocon
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.ContainerTestSupport.transferable
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.containers.Network
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies dynamic target discovery end-to-end in the packaged JARs: the agent reconciles a path from a
 * watched discovery file (with no static `pathConfigs`) and serves it through the proxy.
 *
 * Runtime add/update/remove reconciliation is covered in-process by
 * `io.prometheus.harness.AgentDiscoveryTest`; this spec proves the file → reconcile → register → scrape
 * path works over real Netty transport in the built images.
 */
class ContainersDiscoveryTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Dynamic target discovery reconciles paths from a file (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      "a discovered path scrapes end-to-end through the proxy" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxy = proxyContainer(network)
        val agent =
          agentContainer(
            network,
            configText = discoveryAgentConfig(),
            // start() blocks until the discovered path is registered.
            waitLogRegex = ".*Registered $METRICS_STUB_URL as /disc_a.*",
          ).apply {
            withCopyToContainer(transferable(discoveryPathsHocon("disc_a", METRICS_STUB_URL)), DISCOVERY_DEST)
          }
        try {
          metricsStub.start()
          proxy.start()
          agent.start()

          eventually(30.seconds) {
            httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/disc_a") shouldContain "metric_under_test 42"
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }
    }
  }

  private fun discoveryAgentConfig(): String =
    """
    agent {
      name = "test-agent"
      proxy {
        hostname = "proxy-host"
        port = $PROXY_AGENT_PORT
      }
      pathConfigs = []
      discovery {
        enabled = true
        file.path = "$DISCOVERY_DEST"
        reconcileIntervalSecs = 1
      }
      scrapeTimeoutSecs = 5
      internal {
        reconnectPauseSecs = 1
      }
    }
    """.trimIndent()

  companion object {
    private const val METRICS_STUB_URL = "http://metrics-stub/metrics"
    private const val DISCOVERY_DEST = "/config/discovery.conf"
  }
}
