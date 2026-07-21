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
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.containers.Network
import kotlin.time.Duration.Companion.seconds

/**
 * Agent-side proxy failover (Feature 2, Phase 1) end to end.
 *
 * Two proxies run simultaneously on **distinct network aliases**, and the agent is configured with an
 * ordered endpoint list naming both. When the active proxy is stopped, the agent must land on the
 * standby and resume serving.
 *
 * The distinct aliases are what make this a failover test rather than a reconnect test.
 * [ContainersReconnectTest] replaces a proxy at one alias and so proves DNS re-resolution; a spec
 * copied from it would pass even if endpoint rotation were never implemented. Here proxy B is only
 * reachable at its own alias, so serving through B is only possible if the agent selected B's endpoint.
 */
class ContainersProxyFailoverTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Agent fails over to a second proxy endpoint (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      "agent fails over to the standby proxy endpoint and scrapes resume" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxyA = proxyContainer(network, alias = PROXY_A_ALIAS)
        val proxyB = proxyContainer(network, alias = PROXY_B_ALIAS)

        // The endpoint list is config-file-only in this fixture rather than a -D override: dynamic -D
        // params are parsed with PROPERTIES syntax and cannot carry a HOCON list, and the comma form
        // reads more clearly here anyway.
        val agentConfig =
          """
          agent {
            name = "failover-agent"
            proxy {
              endpoints = [ "$PROXY_A_ALIAS:$PROXY_AGENT_PORT", "$PROXY_B_ALIAS:$PROXY_AGENT_PORT" ]
            }
            pathConfigs: [
              {
                name: "Test metrics"
                path: test_metrics
                url: "http://metrics-stub/metrics"
              }
            ]
            scrapeTimeoutSecs = 5
            internal {
              reconnectPauseSecs = 1
            }
          }
          """.trimIndent()

        val agent =
          agentContainer(
            network,
            configText = agentConfig,
            waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
          )

        try {
          metricsStub.start()
          proxyA.start()
          proxyB.start()
          agent.start()

          // The list is a priority order, so the agent must come up on A while both are healthy.
          eventually(30.seconds) {
            httpClient.bodyOf(proxyA.baseUrl(PROXY_HTTP_PORT) + "/test_metrics") shouldContain METRIC
          }

          // Stop the active proxy. The agent re-probes A (the deliberate failback), fails, then advances.
          proxyA.stop()

          // Serving through B's own alias is the assertion: B only knows this path if the agent
          // connected to B and re-registered there.
          eventually(90.seconds) {
            httpClient.bodyOf(proxyB.baseUrl(PROXY_HTTP_PORT) + "/test_metrics") shouldContain METRIC
          }
        } finally {
          stopQuietly(agent, proxyB, proxyA, metricsStub)
          closeQuietly(network)
        }
      }
    }
  }

  companion object {
    // Deliberately not the default proxy alias -- neither proxy may answer to it, so nothing can
    // accidentally resolve to "whichever proxy is up". The alias doubles as the container log prefix.
    private const val PROXY_A_ALIAS = "proxy-a"
    private const val PROXY_B_ALIAS = "proxy-b"

    private const val METRIC = "metric_under_test 42"
  }
}
