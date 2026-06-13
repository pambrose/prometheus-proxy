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
 * Stretch / higher-variance: verifies the agent reconnects after the proxy is replaced.
 *
 * The first proxy is stopped and a fresh proxy is started on the same `proxy-host` network alias; the agent
 * re-resolves the alias, reconnects, and re-registers its path. Generous timeouts absorb the reconnect pause.
 */
class ContainersReconnectTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Agent reconnects after the proxy is replaced (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      "agent reconnects to a replacement proxy and scrapes resume" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val firstProxy = proxyContainer(network)
        val agent =
          agentContainer(
            network,
            waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
          )
        // Created up front (not started) so the finally block can always tear it down.
        val secondProxy = proxyContainer(network)
        try {
          metricsStub.start()
          firstProxy.start()
          agent.start()

          eventually(30.seconds) {
            val body = httpClient.bodyOf(firstProxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics")
            body shouldContain "metric_under_test 42"
          }

          // Replace the proxy: stop the original and stand a fresh one up on the same network alias.
          firstProxy.stop()
          secondProxy.start()

          // The agent should re-resolve proxy-host, reconnect, re-register, and serve scrapes again.
          eventually(90.seconds) {
            val body = httpClient.bodyOf(secondProxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics")
            body shouldContain "metric_under_test 42"
          }
        } finally {
          stopQuietly(agent, secondProxy, firstProxy, metricsStub)
          closeQuietly(network)
        }
      }
    }
  }
}
