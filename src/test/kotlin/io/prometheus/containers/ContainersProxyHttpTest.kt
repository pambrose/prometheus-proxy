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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.TestPorts.AGENT_ADMIN_PORT
import io.prometheus.common.TestPorts.AGENT_METRICS_PORT
import io.prometheus.containers.support.ContainerTestSupport.NGINX_METRICS_DEST
import io.prometheus.common.TestPorts.PROXY_ADMIN_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.TestPorts.PROXY_METRICS_PORT
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.statusOf
import org.testcontainers.containers.Network
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the proxy's and agent's HTTP surfaces directly (no Prometheus): registered-path scrapes,
 * 404 / 503 / upstream-status passthrough, the Prometheus self-metrics endpoints, the admin servlets
 * (ping / version / healthcheck / threaddump), and the service-discovery document. Runs against a single
 * shared stack with admin, metrics, and service-discovery all enabled via env vars.
 */
class ContainersProxyHttpTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Proxy and agent expose their HTTP surfaces (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val network = Network.newNetwork()
      val metricsStub =
        metricsStub(
          network,
          files =
            mapOf(
              "containers/metrics.txt" to NGINX_METRICS_DEST,
              "containers/metrics2.txt" to "/usr/share/nginx/html/metrics2",
            ),
        )
      val proxy =
        proxyContainer(
          network,
          env = mapOf("ADMIN_ENABLED" to "true", "METRICS_ENABLED" to "true", "SD_ENABLED" to "true"),
          exposedPorts = listOf(PROXY_HTTP_PORT, PROXY_METRICS_PORT, PROXY_ADMIN_PORT),
        )
      val agent =
        agentContainer(
          network,
          configResource = "containers/agent-http.conf",
          env = mapOf("ADMIN_ENABLED" to "true", "METRICS_ENABLED" to "true"),
          exposedPorts = listOf(AGENT_METRICS_PORT, AGENT_ADMIN_PORT),
        )
      val httpClient = httpClient()

      fun proxyHttp(path: String) = proxy.baseUrl(PROXY_HTTP_PORT) + path

      fun proxyAdmin(path: String) = proxy.baseUrl(PROXY_ADMIN_PORT) + path

      fun proxyMetrics(path: String) = proxy.baseUrl(PROXY_METRICS_PORT) + path

      fun agentAdmin(path: String) = agent.baseUrl(AGENT_ADMIN_PORT) + path

      fun agentMetrics(path: String) = agent.baseUrl(AGENT_METRICS_PORT) + path

      beforeSpec {
        metricsStub.start()
        proxy.start()
        agent.start()
      }

      afterSpec {
        httpClient.close()
        listOf(agent, proxy, metricsStub).forEach { container ->
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

      "proxy serves a registered path and preserves the exposition format" {
        eventually(30.seconds) {
          val body = httpClient.bodyOf(proxyHttp("/test_metrics"))
          body shouldContain "metric_under_test 42"
          body shouldContain "# HELP metric_under_test"
          body shouldContain "# TYPE metric_under_test gauge"
        }
      }

      "proxy serves a second registered path" {
        eventually(30.seconds) {
          httpClient.bodyOf(proxyHttp("/test_metrics2")) shouldContain "metric_two 7"
        }
      }

      "proxy returns 404 for an unregistered path" {
        httpClient.statusOf(proxyHttp("/no_such_path")) shouldBe 404
      }

      "proxy returns 404 for the root path" {
        httpClient.statusOf(proxyHttp("/")) shouldBe 404
      }

      "proxy returns 404 for favicon.ico" {
        httpClient.statusOf(proxyHttp("/favicon.ico")) shouldBe 404
      }

      "proxy passes through an upstream 404" {
        eventually(30.seconds) {
          httpClient.statusOf(proxyHttp("/missing_target")) shouldBe 404
        }
      }

      "proxy returns 503 when the upstream target is unreachable" {
        eventually(30.seconds) {
          httpClient.statusOf(proxyHttp("/bad_target")) shouldBe 503
        }
      }

      "proxy exposes its own Prometheus metrics" {
        eventually(20.seconds) {
          val body = httpClient.bodyOf(proxyMetrics("/metrics"))
          body shouldContain "proxy_scrape_requests"
        }
      }

      "proxy admin ping responds" {
        eventually(20.seconds) {
          httpClient.bodyOf(proxyAdmin("/ping")) shouldContain "pong"
        }
      }

      "proxy admin version responds" {
        eventually(20.seconds) {
          httpClient.statusOf(proxyAdmin("/version")) shouldBe 200
        }
      }

      "proxy admin healthcheck reports healthy" {
        eventually(20.seconds) {
          httpClient.statusOf(proxyAdmin("/healthcheck")) shouldBe 200
        }
      }

      "proxy admin threaddump responds" {
        eventually(20.seconds) {
          val body = httpClient.bodyOf(proxyAdmin("/threaddump"))
          body.length shouldBeGreaterThan 100
        }
      }

      "agent exposes its own Prometheus metrics" {
        eventually(20.seconds) {
          httpClient.bodyOf(agentMetrics("/metrics")) shouldContain "agent_scrape_request_count"
        }
      }

      "agent admin ping responds" {
        eventually(20.seconds) {
          httpClient.bodyOf(agentAdmin("/ping")) shouldContain "pong"
        }
      }

      "service discovery lists the registered path" {
        eventually(20.seconds) {
          val body = httpClient.bodyOf(proxyHttp("/discovery"))
          body shouldContain "__metrics_path__"
          body shouldContain "test_metrics"
        }
      }
    }
  }
}
