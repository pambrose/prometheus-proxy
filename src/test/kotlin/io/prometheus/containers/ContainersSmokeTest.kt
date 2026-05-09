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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ContainersSmokeTest : StringSpec() {
  init {
    if (System.getenv("RUN_CONTAINER_TESTS") != "true") {
      "Prometheus scrapes metric_under_test through the proxy and agent (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val network = Network.newNetwork()

      val proxyImage =
        ImageFromDockerfile()
          .withFileFromPath("Dockerfile", Path.of("etc/docker/proxy.df"))
          .withFileFromPath(
            "build/libs/prometheus-proxy.jar",
            Path.of("build/libs/prometheus-proxy.jar"),
          )

      val agentImage =
        ImageFromDockerfile()
          .withFileFromPath("Dockerfile", Path.of("etc/docker/agent.df"))
          .withFileFromPath(
            "build/libs/prometheus-agent.jar",
            Path.of("build/libs/prometheus-agent.jar"),
          )

      fun logger(name: String) = Slf4jLogConsumer(LoggerFactory.getLogger("container.$name")).withPrefix(name)

      val metricsStub =
        GenericContainer<Nothing>("nginx:alpine").apply {
          withNetwork(network)
          withNetworkAliases("metrics-stub")
          withClasspathResourceMapping(
            "containers/metrics.txt",
            "/usr/share/nginx/html/metrics",
            BindMode.READ_ONLY,
          )
          withExposedPorts(80)
          withLogConsumer(logger("metrics-stub"))
          waitingFor(Wait.forHttp("/metrics").forPort(80))
        }

      val proxy =
        GenericContainer<Nothing>(proxyImage).apply {
          withNetwork(network)
          withNetworkAliases("proxy-host")
          withExposedPorts(8080, 50051)
          withLogConsumer(logger("proxy"))
          waitingFor(Wait.forListeningPort())
        }

      val agent =
        GenericContainer<Nothing>(agentImage).apply {
          withNetwork(network)
          withNetworkAliases("agent")
          withEnv("AGENT_NAME", "test-agent")
          withEnv("AGENT_CONFIG", "/config/agent.conf")
          withClasspathResourceMapping(
            "containers/agent.conf",
            "/config/agent.conf",
            BindMode.READ_ONLY,
          )
          withLogConsumer(logger("agent"))
          waitingFor(Wait.forLogMessage(".*Registered http://metrics-stub/metrics as /test_metrics.*", 1))
        }

      val prometheus =
        GenericContainer<Nothing>("prom/prometheus:latest").apply {
          withNetwork(network)
          withNetworkAliases("prometheus")
          withClasspathResourceMapping(
            "containers/prometheus.yml",
            "/etc/prometheus/prometheus.yml",
            BindMode.READ_ONLY,
          )
          withExposedPorts(9090)
          withLogConsumer(logger("prometheus"))
          waitingFor(Wait.forHttp("/-/ready").forPort(9090))
        }

      val httpClient = HttpClient(CIO)

      beforeSpec {
        metricsStub.start()
        proxy.start()
        agent.start()
        prometheus.start()
      }

      afterSpec {
        httpClient.close()
        listOf(prometheus, agent, proxy, metricsStub).forEach { container ->
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
        val mappedPort = prometheus.getMappedPort(9090)
        val queryUrl =
          "http://localhost:$mappedPort/api/v1/query?query=metric_under_test"

        eventually(30.seconds) {
          val body = httpClient.get(queryUrl).bodyAsText()
          body shouldContain "\"metric_under_test\""
          body shouldContain "\"42\""
        }
      }
    }
  }
}
