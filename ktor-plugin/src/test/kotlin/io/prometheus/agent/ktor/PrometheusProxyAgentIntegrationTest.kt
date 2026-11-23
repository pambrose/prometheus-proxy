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

package io.prometheus.agent.ktor

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.testing.testApplication
import io.micrometer.core.instrument.Counter
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyOptions
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

// A Ktor app with Micrometer and the plugin, served in-process through a proxy in the same JVM: Prometheus's scrape of
// the proxy's path reaches the app's registry, and once the app stops, the proxy no longer has the path.
class PrometheusProxyAgentIntegrationTest : StringSpec() {
  private val http = HttpClient.newHttpClient()
  private lateinit var proxy: Proxy

  init {
    beforeSpec {
      val args = listOf("--port", "$SCRAPE_PORT", "--agent_port", "$AGENT_PORT", "--sd_enabled")
      proxy = Proxy(options = ProxyOptions(args), proxyPort = SCRAPE_PORT)
      proxy.startAsync().awaitRunning()
    }

    afterSpec {
      proxy.stopAsync().awaitTerminated()
    }

    "the proxy serves the app's metrics until the app stops" {
      var app: Application? = null
      testApplication {
        application {
          app = this
          val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
          install(MicrometerMetrics) { registry = meterRegistry }
          Counter.builder("orders").description("Orders placed").register(meterRegistry).increment(4.0)
          install(PrometheusProxyAgent) {
            registry = meterRegistry.prometheusRegistry
            path = "ktor_app_metrics"
            labels = mapOf("team" to "web")
            agent {
              name = "ktor-app"
              proxy = "localhost:$AGENT_PORT"
            }
          }
        }
        startApplication()

        val body = scrapeWhen("/ktor_app_metrics") { it == 200 }
        // The app's own meter and one Micrometer binds for the JVM
        body shouldContain "orders_total 4.0"
        body shouldContain "jvm_threads_live_threads"
        // The path's labels, in the proxy's service discovery
        send("/discovery").body() shouldMatch Regex("""(?s).*"team":\s*"web".*""")
        val agent = app?.prometheusProxyAgent.shouldNotBeNull()
        agent.isConnected shouldBe true
        agent.proxyEndpoint shouldBe "localhost:$AGENT_PORT"
      }

      // The agent stopped with the app and disconnected, so the proxy dropped its path
      scrapeWhen("/ktor_app_metrics") { it == 404 }
    }
  }

  // The proxy answers 404 for the path until the agent has registered it, and again once it has left
  private fun scrapeWhen(
    path: String,
    status: (Int) -> Boolean,
  ): String {
    val deadline = Instant.now().plus(Duration.ofSeconds(30))
    var response = send(path)
    while (!status(response.statusCode()) && Instant.now().isBefore(deadline)) {
      Thread.sleep(250)
      response = send(path)
    }
    status(response.statusCode()) shouldBe true
    return response.body()
  }

  private fun send(path: String): HttpResponse<String> =
    http.send(
      HttpRequest.newBuilder(URI.create("http://localhost:$SCRAPE_PORT$path")).build(),
      HttpResponse.BodyHandlers.ofString(),
    )

  private companion object {
    // Fixed, below the ephemeral range, where a port taken from ServerSocket(0) and bound again later can go to another
    // connection in between; the root project's TestPorts lists them (KTOR_PLUGIN_PROXY_*), so no root spec binds them.
    const val SCRAPE_PORT = 11402
    const val AGENT_PORT = 11403
  }
}
