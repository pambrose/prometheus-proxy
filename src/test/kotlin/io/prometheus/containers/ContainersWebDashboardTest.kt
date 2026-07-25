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
import io.prometheus.common.TestPorts.PROXY_DASHBOARD_PORT
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
 * The operational dashboard (Feature 5) end to end, against the real fat JAR.
 *
 * The point of running this in a container rather than only in the harness is the **fat JAR**: htmx
 * ships as a WebJar and is served from the classpath, so a packaging regression that drops those
 * resources would leave the page loading a script that 404s. Only an assembled-image test catches that.
 */
class ContainersWebDashboardTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "operational dashboard renders connected agents (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      "the dashboard serves its assets and reflects a connected agent" {
        val network = Network.newNetwork()
        val metricsStub = metricsStub(network)
        val proxy =
          proxyContainer(
            network,
            env = mapOf("DASHBOARD_ENABLED" to "true"),
            exposedPorts = [PROXY_DASHBOARD_PORT],
          )
        val agent = agentContainer(network)

        try {
          metricsStub.start()
          proxy.start()
          agent.start()

          val uiUrl = proxy.baseUrl(PROXY_DASHBOARD_PORT)

          // The page renders and names the connected agent, which means the snapshot layer reached
          // real AgentContext state through the whole stack.
          eventually(60.seconds) {
            val body = httpClient.bodyOf("$uiUrl/dashboard")
            body shouldContain "prometheus-proxy"
            body shouldContain "test-agent"
          }

          // htmx must come off the classpath. If the WebJar resources were dropped from the fat JAR,
          // the page would still render but every interaction would be dead -- a failure that is
          // invisible to any test that only checks HTML.
          eventually(30.seconds) {
            httpClient.bodyOf("$uiUrl/dashboard/assets/htmx.min.js") shouldContain "htmx"
          }
          eventually(30.seconds) {
            httpClient.bodyOf("$uiUrl/dashboard/assets/ws.js") shouldContain "ws"
          }

          // The detail fragment is what a row click fetches; it must carry the agent's registered path.
          eventually(30.seconds) {
            val listing = httpClient.bodyOf("$uiUrl/dashboard")
            val agentId = AGENT_ID_PATTERN.find(listing)?.groupValues?.get(1).orEmpty()
            httpClient.bodyOf("$uiUrl/dashboard/agents/$agentId") shouldContain "test_metrics"
          }

          // The path layout against the real image: the target URL and source only reach the proxy via
          // the registration RPC, so this exercises the assembled agent's side of that change too.
          eventually(30.seconds) {
            val table = httpClient.bodyOf("$uiUrl/dashboard/paths")
            table shouldContain "/test_metrics"
            // Registered from the container's config file, so it must report as static.
            table shouldContain "cfg"
          }
        } finally {
          stopQuietly(agent, proxy, metricsStub)
          closeQuietly(network)
        }
      }
    }
  }

  companion object {
    // The proxy assigns agent ids, so the test discovers one from the rendered list rather than
    // assuming a value.
    private val AGENT_ID_PATTERN = """hx-get="/dashboard/agents/([^"]+)"""".toRegex()
  }
}
