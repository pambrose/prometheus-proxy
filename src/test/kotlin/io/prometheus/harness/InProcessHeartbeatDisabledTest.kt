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
import io.prometheus.agent.AgentOptions.Companion.agentOptions
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.HarnessConstants.CONFIG_ARG
import io.prometheus.proxy.ProxyOptions.Companion.proxyOptions
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

// Finding 6: launchConnectionTask closes the shared connectionContext whenever any connection-lifetime
// task completes. With heartbeatEnabled=false the heartbeat task returns immediately, closing the context
// right after connect. The read stream stays suspended (so the agent looks connected), but the first
// scrape request hits the closed channel with a ClosedSendChannelException -- the scrape fails and the
// agent flaps, losing its registered path. The fix keeps the heartbeat task alive for the connection's
// lifetime (polling connectionContext.connected) without sending heartbeats, so scrapes succeed.
class InProcessHeartbeatDisabledTest : StringSpec() {
  init {
    "Finding 6: with heartbeat disabled a scrape through the proxy still succeeds" {
      CollectorRegistry.defaultRegistry.clear()

      val serverName = "hb-disabled-${System.nanoTime()}"
      val httpPort = 9526

      // The metrics endpoint the agent will scrape.
      val stub =
        embeddedServer(ServerCIO, port = 0) {
          routing {
            get("/metrics") { call.respondText("test_metric 42\n") }
          }
        }
      val stubPort = stub.startAndAwaitReady()

      val args = [
        "-Dproxy.admin.enabled=false",
        "-Dproxy.metrics.enabled=false",
        // Fail a lost scrape fast so the buggy path doesn't block the whole scrape timeout.
        "-Dproxy.internal.scrapeRequestTimeoutSecs=5",
      ]
      val proxy =
        Proxy(
          options = proxyOptions(CONFIG_ARG + args),
          proxyPort = httpPort,
          inProcessServerName = serverName,
          testMode = true,
        ) { startSync() }

      val client = HttpClient(CIO)
      try {
        val args = [
          "-Dagent.admin.enabled=false",
          "-Dagent.metrics.enabled=false",
          "-Dagent.internal.heartbeatEnabled=false",
        ]
        val agent =
          Agent(
            options = agentOptions(CONFIG_ARG + args, exitOnMissingConfig = false),
            inProcessServerName = serverName,
            testMode = true,
          ) { startSync() }

        try {
          agent.awaitInitialConnection(10.seconds).shouldBeTrue()
          agent.pathManager.registerPath("hbpath", "http://localhost:$stubPort/metrics")

          // With the bug the first scrape closes the connection and drops the path, so scrapes never
          // succeed; with the fix the connection stays healthy and the scrape returns the metric.
          eventually(10.seconds) {
            val response = client.get("http://localhost:$httpPort/hbpath")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "test_metric"
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
