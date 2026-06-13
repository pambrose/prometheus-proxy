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
import io.prometheus.containers.support.ContainerTestSupport.NGINX_METRICS_DEST
import io.prometheus.containers.support.ContainerTestSupport.PROXY_HTTP_PORT
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
 * Two consolidated agents register the **same** proxy path, each scraping a different backend. The proxy
 * merges both responses, so a single scrape of `/consolidated` returns metrics from both agents. Consolidation
 * and response merging are otherwise only proven by the in-process harness; this exercises them end-to-end.
 */
class ContainersConsolidatedTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Consolidated agents merge into one path (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val network = Network.newNetwork()
      val stubA =
        metricsStub(network, alias = "metrics-stub-a", files = mapOf("containers/metrics-a.txt" to NGINX_METRICS_DEST))
      val stubB =
        metricsStub(network, alias = "metrics-stub-b", files = mapOf("containers/metrics-b.txt" to NGINX_METRICS_DEST))
      val proxy = proxyContainer(network)
      val agentA =
        agentContainer(
          network,
          alias = "agent-a",
          configResource = "containers/agent-consolidated-a.conf",
          waitLogRegex = ".*Registered http://metrics-stub-a/metrics as /consolidated.*",
        )
      val agentB =
        agentContainer(
          network,
          alias = "agent-b",
          configResource = "containers/agent-consolidated-b.conf",
          waitLogRegex = ".*Registered http://metrics-stub-b/metrics as /consolidated.*",
        )
      val httpClient = httpClient()

      beforeSpec {
        stubA.start()
        stubB.start()
        proxy.start()
        agentA.start()
        agentB.start()
      }

      afterSpec {
        httpClient.close()
        stopQuietly(agentB, agentA, proxy, stubB, stubA)
        closeQuietly(network)
      }

      "a single consolidated path returns metrics merged from both agents" {
        eventually(30.seconds) {
          val body = httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/consolidated")
          body shouldContain "metric_a 1"
          body shouldContain "metric_b 2"
        }
      }
    }
  }
}
