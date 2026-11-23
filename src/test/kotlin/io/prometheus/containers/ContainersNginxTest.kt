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
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.TestPorts.PROXY_METRICS_PORT
import io.prometheus.containers.support.ContainerTestSupport.NGINX_IMAGE
import io.prometheus.containers.support.ContainerTestSupport.agentContainer
import io.prometheus.containers.support.ContainerTestSupport.containerTestsEnabled
import io.prometheus.containers.support.ContainerTestSupport.httpClient
import io.prometheus.containers.support.ContainerTestSupport.logConsumer
import io.prometheus.containers.support.ContainerTestSupport.metricsStub
import io.prometheus.containers.support.ContainerTestSupport.proxyContainer
import io.prometheus.containers.support.ContainerTestSupport.transferable
import io.prometheus.containers.support.baseUrl
import io.prometheus.containers.support.bodyOf
import io.prometheus.containers.support.closeQuietly
import io.prometheus.containers.support.stopQuietly
import kotlinx.coroutines.delay
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import kotlin.time.Duration.Companion.seconds

/**
 * The proxy behind nginx, as the website's Nginx Reverse Proxy section sets it up and the nginx demo (`demos/nginx`)
 * runs it: nginx ends the agent's TLS, with the project's `testing/certs` `server1.pem`, and passes its gRPC calls to
 * the proxy's agent port in plaintext (`grpc_pass`), and both sides turn the transport filter off.
 *
 * nginx closes a stream that carries nothing for its `grpc_read_timeout` (the stream of scrape requests) or
 * `client_body_timeout` (the streams of results), and an agent's connection ends when any of its streams closes. The
 * proxy and agent keep each stream open with a keepalive after `proxy.internal.streamKeepAliveSecs` of quiet. Here
 * nginx's timeouts are [NGINX_TIMEOUT_SECS] rather than its default 60, so the spec takes seconds: an idle agent stays
 * connected past them with keepalives on, and, the control that shows the timeouts bite, is cut off with them off.
 * A scrape through the proxy proves nothing about that, since the agent reconnects within a second, so the cases count
 * the proxy's connects and the streams it saw end.
 */
class ContainersNginxTest : StringSpec() {
  init {
    if (!containerTestsEnabled()) {
      "The proxy behind an nginx gRPC reverse proxy (set RUN_CONTAINER_TESTS=true to enable)"
        .config(enabled = false) { }
    } else {
      val httpClient = httpClient()
      afterSpec { httpClient.close() }

      "a scrape reaches the agent's target through nginx, which ends the agent's TLS" {
        withProxyBehindNginx(keepaliveSecs = KEEPALIVE_SECS) { proxy ->
          eventually(30.seconds) {
            httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics") shouldContain METRIC
          }
        }
      }

      "keepalives keep an idle agent connected past nginx's stream timeouts" {
        withProxyBehindNginx(keepaliveSecs = KEEPALIVE_SECS) { proxy ->
          // Nothing scrapes the agent, so its streams carry only keepalives, for more than twice nginx's timeouts.
          delay(IDLE_WAIT)
          val metrics = httpClient.bodyOf(proxy.baseUrl(PROXY_METRICS_PORT) + "/metrics")
          metrics.sampleValue(CONNECTS) shouldBe 1.0
          metrics.sampleValue(STREAMS_ENDED) shouldBe 0.0
          httpClient.bodyOf(proxy.baseUrl(PROXY_HTTP_PORT) + "/test_metrics") shouldContain METRIC
        }
      }

      "without keepalives, nginx's stream timeouts cut an idle agent off" {
        withProxyBehindNginx(keepaliveSecs = 0) { proxy ->
          eventually(IDLE_WAIT) {
            val metrics = httpClient.bodyOf(proxy.baseUrl(PROXY_METRICS_PORT) + "/metrics")
            metrics.sampleValue(STREAMS_ENDED) shouldBeGreaterThanOrEqual 1.0
          }
          // The agent reconnects after each cut, so the proxy has seen it connect again.
          eventually(30.seconds) {
            val metrics = httpClient.bodyOf(proxy.baseUrl(PROXY_METRICS_PORT) + "/metrics")
            metrics.sampleValue(CONNECTS) shouldBeGreaterThanOrEqual 2.0
          }
        }
      }
    }
  }

  /**
   * Starts a metrics stub, a proxy with this stream keepalive interval, nginx in front of the proxy's agent port, and
   * an agent that reaches the proxy only through nginx, runs [block] with the proxy once the agent has registered
   * its path, and stops them all.
   */
  private suspend fun withProxyBehindNginx(
    keepaliveSecs: Int,
    block: suspend (proxy: GenericContainer<*>) -> Unit,
  ) {
    val network = Network.newNetwork()
    val metricsStub = metricsStub(network)
    val proxy =
      proxyContainer(
        network,
        alias = PROXY_ALIAS,
        env = mapOf("TRANSPORT_FILTER_DISABLED" to "true", "METRICS_ENABLED" to "true"),
        exposedPorts = listOf(PROXY_HTTP_PORT, PROXY_METRICS_PORT),
      ).apply {
        // The interval has no environment variable; the image passes its arguments to the proxy.
        withCommand("-Dproxy.internal.streamKeepAliveSecs=$keepaliveSecs")
      }
    val nginx = nginxContainer(network)
    val agent =
      agentContainer(
        network,
        configText = AGENT_CONFIG,
        hostFiles = mapOf("testing/certs/ca.pem" to "/config/ca.pem"),
        waitLogRegex = ".*Registered http://metrics-stub/metrics as /test_metrics.*",
      )
    try {
      metricsStub.start()
      // nginx resolves the proxy's alias as it loads its config, so the proxy starts first.
      proxy.start()
      nginx.start()
      agent.start()
      block(proxy)
    } finally {
      stopQuietly(agent, nginx, proxy, metricsStub)
      closeQuietly(network)
    }
  }

  private fun nginxContainer(network: Network): GenericContainer<*> =
    GenericContainer<Nothing>(NGINX_IMAGE).apply {
      withNetwork(network)
      // The test certificate names *.test.google.fr, so the agent dials nginx by a name it covers.
      withNetworkAliases(NGINX_ALIAS)
      // In place of the image's default.conf: generated here, not a classpath resource, since ConfigFileKeysTest
      // reads every .conf among the container suite's resources as HOCON.
      withCopyToContainer(transferable(NGINX_CONFIG), "/etc/nginx/conf.d/default.conf")
      withCopyFileToContainer(MountableFile.forHostPath("testing/certs/server1.pem"), "/etc/nginx/certs/server.pem")
      withCopyFileToContainer(MountableFile.forHostPath("testing/certs/server1.key"), "/etc/nginx/certs/server.key")
      withExposedPorts(NGINX_TLS_PORT)
      withLogConsumer(logConsumer(NGINX_ALIAS))
      waitingFor(Wait.forListeningPort())
    }

  companion object {
    private const val PROXY_ALIAS = "proxy"
    private const val NGINX_ALIAS = "nginx.test.google.fr"
    private const val NGINX_TLS_PORT = 443

    // nginx's stream timeouts, cut from its default 60 seconds so the spec takes seconds, and the keepalive interval
    // under them.
    private const val NGINX_TIMEOUT_SECS = 10
    private const val KEEPALIVE_SECS = 3

    // Long enough for nginx's timeouts to fire twice on a stream nothing keeps open.
    private val IDLE_WAIT = (NGINX_TIMEOUT_SECS * 2 + 5).seconds

    private const val METRIC = "metric_under_test 42"
    private const val CONNECTS = "proxy_connect_count_total"
    private const val STREAMS_ENDED = """proxy_agent_disconnects_total{reason="stream_terminated"}"""

    private val NGINX_CONFIG =
      """
      server {
        listen $NGINX_TLS_PORT ssl;
        http2 on;
        ssl_certificate     /etc/nginx/certs/server.pem;
        ssl_certificate_key /etc/nginx/certs/server.key;
        client_body_timeout ${NGINX_TIMEOUT_SECS}s;

        location / {
          grpc_pass grpc://$PROXY_ALIAS:$PROXY_AGENT_PORT;
          grpc_read_timeout ${NGINX_TIMEOUT_SECS}s;
        }
      }
      """.trimIndent()

    private val AGENT_CONFIG =
      """
      agent {
        name = "nginx-agent"
        transportFilterDisabled = true
        proxy {
          hostname = "$NGINX_ALIAS"
          port = $NGINX_TLS_PORT
        }
        tls.trustCertCollectionFilePath = "/config/ca.pem"
        pathConfigs: [
          {
            name: "Test metrics"
            path: test_metrics
            url: "http://metrics-stub/metrics"
          }
        ]
        internal {
          reconnectPauseSecs = 1
        }
      }
      """.trimIndent()

    // A sample's value in exposition text, by its name and labels as written; NaN when it's missing.
    private fun String.sampleValue(sample: String): Double =
      lineSequence()
        .firstOrNull { it.startsWith("$sample ") }
        ?.substringAfter(' ')
        ?.trim()
        ?.toDoubleOrNull() ?: Double.NaN
  }
}
