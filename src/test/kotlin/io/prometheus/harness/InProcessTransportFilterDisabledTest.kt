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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

// The transport-filter-disabled mode is what an agent uses behind an L7 reverse proxy that strips
// transport identity. The unit tests mock each side of it; this runs both sides together. Three things
// only the real pairing can show: the agent takes its id from the connectAgentWithTransportFilterDisabled
// response rather than a header, a scrape flows through that connection, and with no transport filter to
// notice a disconnect the request stream's own termination is what removes the agent and its paths.
class InProcessTransportFilterDisabledTest : StringSpec() {
  init {
    "with the transport filter disabled on both sides a scrape succeeds and a disconnect still cleans up" {
      CollectorRegistry.defaultRegistry.clear()

      val serverName = "tf-disabled-${System.nanoTime()}"

      // The metrics endpoint the agent will scrape.
      val stub =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
          routing {
            get("/metrics") { call.respondText("tf_metric 7\n") }
          }
        }
      val stubPort = stub.startAndAwaitReady()

      val proxy =
        startProxy(
          serverName = serverName,
          proxyPort = HTTP_PORT,
          args = [
            "--tf_disabled",
            // Off in config so startUp() has to force the eviction thread on: with no transport filter it is
            // the only thing that can reclaim a context whose agent died before opening its stream.
            "-Dproxy.internal.staleAgentCheckEnabled=false",
          ],
        )

      val client = HttpClient(CIO)
      try {
        val agent = startAgent(serverName = serverName, args = ["--tf_disabled"])

        try {
          agent.awaitInitialConnection(10.seconds).shouldBeTrue()

          // No client interceptor runs in this mode, so the id can only have come from the RPC response.
          val agentId = agent.agentId
          agentId.shouldNotBeEmpty()
          proxy.agentContextManager.getAgentContext(agentId).shouldNotBeNull()

          agent.pathManager.registerPath("tfpath", "http://localhost:$stubPort/metrics")
          eventually(10.seconds) {
            val response = client.get("http://localhost:$HTTP_PORT/tfpath")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "tf_metric"
          }

          // No transportTerminated() callback exists to run here, so the cleanup in
          // readRequestsFromProxy's finally is the only thing that can drop the context and its path.
          agent.stopSync(15.seconds)
          eventually(10.seconds) {
            proxy.agentContextManager.getAgentContext(agentId).shouldBeNull()
            proxy.pathManager.getAgentContextInfo("tfpath").shouldBeNull()
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

  companion object {
    private const val HTTP_PORT = TestPorts.TF_DISABLED_HTTP_PORT
  }
}
