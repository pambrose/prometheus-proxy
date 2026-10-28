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

// Service discovery as documented gave Prometheus no targets: the proxy advertised sdTargetPrefix, a URL, as each
// target, and Prometheus refuses a target address that contains "/". The unit tests passed with a bare host:port, and
// no other spec runs service discovery against a real Prometheus. Here Prometheus finds the agent's path only through
// http_sd_configs, with the prefix given as the URL the docs used, and has to scrape it.
class ContainersServiceDiscoveryTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Prometheus scrapes a path it found through http_sd_configs (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val network = Network.newNetwork()
      val metricsStub = metricsStub(network)
      val proxy =
        proxyContainer(
          network,
          env = mapOf("SD_ENABLED" to "true", "SD_TARGET_PREFIX" to "http://proxy-host:8080/"),
        )
      val agent =
        agentContainer(
          network,
          waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
        )
      val prometheus = prometheusContainer(network, ymlResource = "containers/prometheus-sd.yml")
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

      "Prometheus reports the target it discovered through the proxy as up" {
        val queryUrl = prometheus.baseUrl(PROMETHEUS_PORT) + "/api/v1/query?query=up%7Bjob%3D%22proxy-sd%22%7D"
        eventually(60.seconds) {
          val body = httpClient.bodyOf(queryUrl)
          body shouldContain "\"__name__\":\"up\""
          body shouldContain "\"1\""
        }
      }

      "Prometheus scrapes metric_under_test from the discovered path" {
        val query = "metric_under_test%7Bjob%3D%22proxy-sd%22%7D"
        val queryUrl = prometheus.baseUrl(PROMETHEUS_PORT) + "/api/v1/query?query=$query"
        eventually(60.seconds) {
          val body = httpClient.bodyOf(queryUrl)
          body shouldContain "\"metric_under_test\""
          body shouldContain "\"42\""
        }
      }
    }
  }
}
