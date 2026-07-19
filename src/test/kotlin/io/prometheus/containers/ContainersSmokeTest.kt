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
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.ContainerTestSupport.prometheusContainer
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import org.testcontainers.containers.Network
import kotlin.time.Duration.Companion.seconds

class ContainersSmokeTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Prometheus scrapes metric_under_test through the proxy and agent (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val network = Network.newNetwork()
      val metricsStub = metricsStub(network)
      val proxy = proxyContainer(network)
      val agent =
        agentContainer(
          network,
          waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
        )
      val prometheus = prometheusContainer(network)
      val httpClient = httpClient()

      beforeSpec {
        metricsStub.start()
        proxy.start()
        agent.start()
        prometheus.start()
      }

      afterSpec {
        httpClient.close()
        [prometheus, agent, proxy, metricsStub].forEach { container ->
          try {
            container.stop()
          } catch (_: Exception) {
            // best-effort teardown
          }
        }
        try {
          network.close()
        } catch (_: Exception) {
          // best-effort teardown
        }
      }

      "Prometheus scrapes metric_under_test through the proxy and agent" {
        val queryUrl = prometheus.baseUrl(PROMETHEUS_PORT) + "/api/v1/query?query=metric_under_test"
        eventually(30.seconds) {
          val body = httpClient.bodyOf(queryUrl)
          body shouldContain "\"metric_under_test\""
          body shouldContain "\"42\""
        }
      }

      "Prometheus reports the proxy scrape target as up" {
        val queryUrl = prometheus.baseUrl(PROMETHEUS_PORT) + "/api/v1/query?query=up%7Bjob%3D%22proxy%22%7D"
        eventually(30.seconds) {
          val body = httpClient.bodyOf(queryUrl)
          // up{job="proxy"} resolves to a value of "1" once the scrape loop succeeds.
          body shouldContain "\"up\""
          body shouldContain "\"1\""
        }
      }

      "Prometheus targets API marks the proxy endpoint healthy" {
        val targetsUrl = prometheus.baseUrl(PROMETHEUS_PORT) + "/api/v1/targets"
        eventually(30.seconds) {
          val body = httpClient.bodyOf(targetsUrl)
          body shouldContain "\"job\":\"proxy\""
          body shouldContain "\"health\":\"up\""
        }
      }
    }
  }
}
