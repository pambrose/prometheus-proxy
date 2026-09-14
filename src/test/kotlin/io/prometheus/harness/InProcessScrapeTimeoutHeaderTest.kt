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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.proxy.ProxyHttpRoutes
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import io.ktor.server.cio.CIO as ServerCIO

// Prometheus tells a target how long it will wait in X-Prometheus-Scrape-Timeout-Seconds. The proxy forwards that to
// the agent, which stops the scrape at the client's deadline instead of finishing one nobody is still waiting for.
class InProcessScrapeTimeoutHeaderTest : StringSpec() {
  init {
    "the agent should stop a scrape at the client's X-Prometheus-Scrape-Timeout-Seconds" {
      CollectorRegistry.defaultRegistry.clear()

      val serverName = "scrape-timeout-header-${System.nanoTime()}"

      // Slower than the client's scrape timeout, but faster than the agent's own scrapeTimeoutSecs.
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

      val proxy = startProxy(serverName = serverName, proxyPort = HTTP_PORT)
      val client =
        HttpClient(CIO) {
          install(HttpTimeout) { requestTimeoutMillis = CLIENT_WAIT.inWholeMilliseconds }
        }
      try {
        val agent = startAgent(serverName = serverName, scrapeTimeoutSecs = AGENT_SCRAPE_TIMEOUT_SECS)
        try {
          agent.awaitInitialConnection(10.seconds).shouldBeTrue()
          agent.pathManager.registerPath(PATH, "http://localhost:$stubPort/metrics")
          eventually(10.seconds) { proxy.pathManager.getAgentContextInfo(PATH).shouldNotBeNull() }

          val (status, elapsed) =
            measureTimedValue {
              client.get("http://localhost:$HTTP_PORT/$PATH") {
                header(ProxyHttpRoutes.SCRAPE_TIMEOUT_HEADER, "$HEADER_TIMEOUT_SECS")
              }.status
            }

          status shouldBe HttpStatusCode.RequestTimeout
          elapsed shouldBeLessThan TARGET_DELAY / 2
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
    private const val HTTP_PORT = TestPorts.SCRAPE_TIMEOUT_HEADER_HTTP_PORT
    private const val PATH = "slowheaderpath"
    private const val HEADER_TIMEOUT_SECS = 1
    private const val AGENT_SCRAPE_TIMEOUT_SECS = 20
    private val TARGET_DELAY = 8.seconds
    private val CLIENT_WAIT = 30.seconds
  }
}
