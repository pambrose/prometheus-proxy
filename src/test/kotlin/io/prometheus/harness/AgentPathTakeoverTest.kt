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

import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts.PATH_TAKEOVER_HTTP_PORT
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.TestUtils.stopAll
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

// Two agents of different launches registering the same exclusive path, as a redeploy or a config deployed twice does.
// The second takes the path over. The proxy tells the first, which shows the path as taken over rather than registered,
// and takes it back once the second leaves, without reconnecting and without taking it from the second while it stays.
// path-takeover.conf sets a 60s retry, so taking the path back within seconds shows the proxy's notice did it.
class AgentPathTakeoverTest : StringSpec() {
  init {
    "a displaced agent should show the path taken over, and take it back once the other agent leaves" {
      withProxyAndTarget { serverName, client, targetUrl ->
        val agentA = connectedAgent(serverName, "takeover-agent-a")
        val agentB = connectedAgent(serverName, "takeover-agent-b")
        try {
          agentA.pathManager.registerPath(SHARED_PATH, targetUrl)
          eventually(10.seconds) { ownerOf(SHARED_PATH) shouldBe agentA.agentId }

          agentB.pathManager.registerPath(SHARED_PATH, targetUrl)

          eventually(10.seconds) {
            agentA.pathManager.takenOverContexts.map { it.path } shouldBe listOf(SHARED_PATH)
            agentA.pathManager.pathFailures.getValue(SHARED_PATH).reason.message shouldContain "takeover-agent-b"
          }
          agentA.pathManager.registeredPaths.shouldBeEmpty()
          // The first agent waits rather than taking the path back, so the two settle.
          continually(3.seconds) { ownerOf(SHARED_PATH) shouldBe agentB.agentId }

          agentB.stopSync(10.seconds)

          // The same agentId: the first agent took the path back on its connection, without reconnecting. The proxy
          // records the path before it answers, so the agent's side follows a moment later.
          eventually(5.seconds) {
            ownerOf(SHARED_PATH) shouldBe agentA.agentId
            agentA.pathManager.registeredPaths.map { it.path } shouldBe listOf(SHARED_PATH)
            agentA.pathManager.takenOverContexts.shouldBeEmpty()
          }
          client.get("http://localhost:$PATH_TAKEOVER_HTTP_PORT/$SHARED_PATH").status shouldBe HttpStatusCode.OK
        } finally {
          stopAll(agentA, agentB)
        }
      }
    }

    // An agent heartbeats only when idle, so one still scraped for its other paths would never hear a notice sent
    // with a heartbeat. Its own stream reaches it whatever it's doing.
    "a displaced agent busy with its other paths should still be told" {
      withProxyAndTarget { serverName, client, targetUrl ->
        val agentA = connectedAgent(serverName, "takeover-agent-a")
        val agentB = connectedAgent(serverName, "takeover-agent-b")
        try {
          agentA.pathManager.registerPath(SHARED_PATH, targetUrl)
          agentA.pathManager.registerPath(OWN_PATH, targetUrl)
          eventually(10.seconds) { ownerOf(OWN_PATH) shouldBe agentA.agentId }

          coroutineScope {
            val scrapes =
              launch {
                while (isActive) {
                  client.get("http://localhost:$PATH_TAKEOVER_HTTP_PORT/$OWN_PATH")
                  delay(100.milliseconds)
                }
              }
            agentB.pathManager.registerPath(SHARED_PATH, targetUrl)

            eventually(10.seconds) {
              agentA.pathManager.takenOverContexts.map { it.path } shouldBe listOf(SHARED_PATH)
            }
            scrapes.cancel()
          }
          agentA.pathManager.registeredPaths.map { it.path } shouldBe listOf(OWN_PATH)
        } finally {
          stopAll(agentA, agentB)
        }
      }
    }
  }

  private var proxy: Proxy? = null

  private fun ownerOf(path: String): String? =
    proxy?.pathManager?.getAgentContextInfo(path)?.agentContexts?.singleOrNull()?.agentId

  // Runs [block] against a proxy, an HTTP client, and a target that answers every scrape, and stops them all after.
  private suspend fun withProxyAndTarget(block: suspend (String, HttpClient, String) -> Unit) {
    PrometheusRegistry.defaultRegistry.clear()
    val serverName = "path-takeover-${System.nanoTime()}"
    val target =
      embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing { get("/metrics") { call.respondText("up 1\n") } }
      }
    val targetPort = target.startAndAwaitReady()
    val started = startProxy(serverName, proxyPort = PATH_TAKEOVER_HTTP_PORT, configArgs = CONFIG_ARG)
    proxy = started
    val client = HttpClient(CIO)
    try {
      block(serverName, client, "http://localhost:$targetPort/metrics")
    } finally {
      client.close()
      stopAll(started)
      proxy = null
      target.stop(0, 0)
    }
  }

  // An agent of a launch of its own, named [name], connected to the proxy in-process.
  private fun connectedAgent(
    serverName: String,
    name: String,
  ): Agent =
    startAgent(serverName, args = listOf("-Dagent.name=$name"), configArgs = CONFIG_ARG)
      .also { it.awaitInitialConnection(10.seconds).shouldBeTrue() }

  companion object {
    private const val SHARED_PATH = "takeover_shared_metrics"
    private const val OWN_PATH = "takeover_own_metrics"
    private val CONFIG_ARG = listOf("--config", "config/test-configs/path-takeover.conf")
  }
}
