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

package io.prometheus.harness

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

// Prometheus gives up on a scrape at its own timeout, which is usually well below the proxy's. A target slower than
// Prometheus but faster than the proxy is the most common real failure, so it has to show up where operators look:
// the scrape metrics, /debug, and the dashboard's recent scrapes.
class InProcessClientCancelledScrapeTest : StringSpec() {
  init {
    "a scrape the client abandons should still be recorded, as client_cancelled" {
      PrometheusRegistry.defaultRegistry.clear()

      val serverName = "client-cancelled-${System.nanoTime()}"

      // Slower than the client's timeout below, but far faster than the proxy's.
      val stub =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
          routing {
            get("/metrics") {
              delay(TARGET_DELAY)
              call.respondText("slow_metric 1\n")
            }
          }
        }
      val stubPort = stub.startAndAwaitReady()

      // The assertion reads the dashboard's recent scrapes, which the proxy records only while the dashboard is on.
      val proxy =
        startProxy(
          serverName = serverName,
          proxyPort = HTTP_PORT,
          args = ["--dashboard", "--dashboard_port", "$DASHBOARD_PORT"],
        )
      val client =
        HttpClient(CIO) {
          install(HttpTimeout) { requestTimeoutMillis = CLIENT_TIMEOUT.inWholeMilliseconds }
        }
      try {
        val agent = startAgent(serverName = serverName)
        try {
          agent.awaitInitialConnection(10.seconds).shouldBeTrue()
          agent.pathManager.registerPath(PATH, "http://localhost:$stubPort/metrics")
          eventually(10.seconds) { proxy.pathManager.getAgentContextInfo(PATH).shouldNotBeNull() }

          runCatching { client.get("http://localhost:$HTTP_PORT/$PATH") }.isFailure.shouldBeTrue()

          eventually(10.seconds) {
            proxy.recentScrapes().map { it.path to it.outcome } shouldContain (PATH to "client_cancelled")
          }
        } finally {
          if (agent.isRunning) runCatching { agent.stopSync(10.seconds) }
        }
      } finally {
        client.close()
        runCatching { proxy.stopSync(10.seconds) }
        stub.stop(0, 0)
      }
    }
  }

  companion object {
    private const val HTTP_PORT = TestPorts.CLIENT_CANCELLED_HTTP_PORT
    private const val DASHBOARD_PORT = TestPorts.CLIENT_CANCELLED_DASHBOARD_PORT
    private const val PATH = "slowpath"
    private val TARGET_DELAY = 3.seconds
    private val CLIENT_TIMEOUT = 1.seconds
  }
}
