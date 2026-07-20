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

package io.prometheus.harness

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.agentOptions
import io.prometheus.common.proxyOptions
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.HarnessConstants.CONFIG_ARG
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

// Per-path metric filtering (Feature 4) end to end. The agent scrapes a target exposing two metric
// families, and harness.conf denies "go_.*" on this path, so only the allowed family should reach
// the proxy. A regression that stopped applying the filter would leak go_goroutines into the body.
class AgentMetricFilterTest : StringSpec() {
  init {
    "a filtered path returns a reduced payload end to end" {
      CollectorRegistry.defaultRegistry.clear()

      val serverName = "metric-filter-${System.nanoTime()}"
      // Dedicated port, not TestPorts.kt: avoids clashing with the shared harness port (9505) and the
      // other standalone in-process specs' own dedicated ports (9512, 9525, 9526 -- see
      // TlsMutualAuthRejectionTest/InProcessIdleShutdownTest/InProcessHeartbeatDisabledTest for the
      // same pattern). TestPorts.kt holds ports that mirror real default config values shared broadly
      // across the unit/container suites, not one-off ports owned by a single harness spec.
      val httpPort = 9527

      // Two families: go_goroutines is denied by harness.conf, app_requests is not.
      val body =
        """
        # HELP go_goroutines Number of goroutines
        # TYPE go_goroutines gauge
        go_goroutines 12
        # HELP app_requests Total requests
        # TYPE app_requests counter
        app_requests 42
        """.trimIndent() + "\n"

      val stub =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
          routing {
            get("/metrics") { call.respondText(body) }
          }
        }
      val stubPort = stub.startAndAwaitReady()

      val proxyArgs = ["-Dproxy.admin.enabled=false", "-Dproxy.metrics.enabled=false"]
      val proxy =
        Proxy(
          options = proxyOptions(CONFIG_ARG + proxyArgs),
          proxyPort = httpPort,
          inProcessServerName = serverName,
          testMode = true,
        ) { startSync() }

      val client = HttpClient(CIO)
      try {
        val agentArgs = ["-Dagent.admin.enabled=false", "-Dagent.metrics.enabled=false"]
        val agent =
          Agent(
            options = agentOptions(CONFIG_ARG + agentArgs, exitOnMissingConfig = false),
            inProcessServerName = serverName,
            testMode = true,
          ) { startSync() }

        try {
          agent.awaitInitialConnection(10.seconds).shouldBeTrue()
          // Must match the filters[].path in harness.conf exactly, with no leading slash.
          agent.pathManager.registerPath("filtered_metrics", "http://localhost:$stubPort/metrics")

          eventually(10.seconds) {
            val response = client.get("http://localhost:$httpPort/filtered_metrics")
            response.status shouldBe HttpStatusCode.OK
            val text = response.bodyAsText()

            // The allowed family survives in full.
            text shouldContain "app_requests 42"
            text shouldContain "# TYPE app_requests counter"

            // The denied family is gone, HELP and TYPE lines included.
            text shouldNotContain "go_goroutines"
            text shouldNotContain "Number of goroutines"
          }
        } finally {
          if (agent.isRunning) runCatching { agent.stopSync(5.seconds) }
        }
      } finally {
        client.close()
        runCatching { proxy.stopSync(10.seconds) }
        stub.stop(0, 0)
      }
    }
  }
}
