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
import io.prometheus.containers.support.ContainerTestSupport.LARGE_PAYLOAD_SERIES
import io.prometheus.containers.support.ContainerTestSupport.METRICS_STUB_ALIAS
import io.prometheus.common.TestPorts.NGINX_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.containers.support.ContainerTestSupport.SENTINEL_METRIC
import io.prometheus.containers.support.ContainerTestSupport.SENTINEL_VALUE
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.largeMetricsText
import io.prometheus.containers.support.ContainerTestSupport.logConsumer
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.ContainerTestSupport.transferable
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.stopQuietly
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.time.Duration.Companion.seconds

/**
 * Forces the agent's chunked + gzipped scrape path with a large (~150 KB) synthetic exposition and verifies
 * the proxy reassembles it intact. The agent's `CHUNK_CONTENT_SIZE_KBS=1` makes even modest payloads chunk,
 * and a low `MIN_GZIP_SIZE_BYTES` forces gzip. Chunking is otherwise only unit-tested.
 */
class ContainersLargePayloadTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "Large chunked, gzipped payloads survive the proxy (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val network = Network.newNetwork()
      val largePayload = largeMetricsText().first
      val metricsStub =
        GenericContainer<Nothing>("nginx:alpine").apply {
          withNetwork(network)
          withNetworkAliases(METRICS_STUB_ALIAS)
          withCopyToContainer(transferable(largePayload), "/usr/share/nginx/html/large")
          withExposedPorts(NGINX_PORT)
          withLogConsumer(logConsumer(METRICS_STUB_ALIAS))
          waitingFor(Wait.forHttp("/large").forPort(NGINX_PORT))
        }
      val proxy = proxyContainer(network)
      val agent =
        agentContainer(
          network,
          configResource = "containers/agent-large.conf",
          env = mapOf("CHUNK_CONTENT_SIZE_KBS" to "1", "MIN_GZIP_SIZE_BYTES" to "256"),
          waitLogRegex = ".*Registered http://metrics-stub/large as /large_metrics.*",
        )
      val httpClient = httpClient()

      beforeSpec {
        metricsStub.start()
        proxy.start()
        agent.start()
      }

      afterSpec {
        httpClient.close()
        stopQuietly(agent, proxy, metricsStub)
        closeQuietly(network)
      }

      "a large payload is reassembled intact through the proxy" {
        eventually(60.seconds) {
          val body = httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/large_metrics")
          // Sentinel proves the tail of the payload survived chunk reassembly.
          body shouldContain "$SENTINEL_METRIC $SENTINEL_VALUE"
          // The final synthetic series (index seriesCount - 1) must also be present.
          body shouldContain "synthetic_metric{series=\"${LARGE_PAYLOAD_SERIES - 1}\"}"
        }
      }
    }
  }
}
