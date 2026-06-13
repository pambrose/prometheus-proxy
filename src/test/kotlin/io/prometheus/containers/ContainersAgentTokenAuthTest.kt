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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.containers.support.ContainerTestSupport.PROXY_HTTP_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.statusOf
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the pre-shared agent-token feature end-to-end over real Netty transport in the packaged JARs.
 *
 * Each case stands up its own proxy + agent + metrics-stub stack (the proxy/agent images are content-hash
 * cached, so only container startup is paid per case) and tears it down in a `finally`.
 */
class ContainersAgentTokenAuthTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Agent-token authentication is enforced over gRPC (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      "agent with the matching token connects and scrapes flow" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxy = proxyContainer(network, env = mapOf("AGENT_TOKEN" to "harness-secret-token"))
        val agent =
          agentContainer(
            network,
            env = mapOf("AGENT_TOKEN" to "harness-secret-token"),
            waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
          )
        try {
          metricsStub.start()
          proxy.start()
          // start() blocks on the "Registered …" log, which only appears once the token is accepted.
          agent.start()
          eventually(30.seconds) {
            httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics") shouldContain "metric_under_test 42"
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }

      "agent with a wrong token is rejected and never registers" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxy = proxyContainer(network, env = mapOf("AGENT_TOKEN" to "harness-secret-token"))
        val agent =
          agentContainer(
            network,
            env = mapOf("AGENT_TOKEN" to "wrong-token"),
            // The wrong token never registers; wait on the connect-failure log instead.
            wait = Wait.forLogMessage(".*Cannot connect to proxy.*", 1),
          )
        try {
          metricsStub.start()
          proxy.start()
          agent.start()

          // The proxy rejected the agent's RPCs with UNAUTHENTICATED.
          agent.logs shouldContain "UNAUTHENTICATED"

          // The path was never registered, so the proxy serves it as an unknown path.
          httpClient.statusOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics") shouldBe 404
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }
    }
  }
}
