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
import io.kotest.matchers.shouldNotBe
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
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.agentOptions
import io.prometheus.common.proxyOptions
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

/**
 * Agent-side proxy failover (Feature 2, Phase 1) over the Netty transport.
 *
 * Two proxies run simultaneously on distinct gRPC ports. The agent is pointed at both, connects to the
 * first, and must land on the second once the first is stopped.
 *
 * This spec cannot be written in-process. `GrpcDsl.channel` ignores hostName/port entirely when an
 * in-process server name is supplied, so an in-process failover test would build the same channel
 * regardless of which endpoint the cursor selected and would pass without any rotation happening.
 *
 * Distinct ports are load-bearing for the same reason: they are what makes this a test of endpoint
 * *selection* rather than of a name resolving to a new address, which is what `ContainersReconnectTest`
 * already covers by replacing a proxy at one address.
 */
class AgentProxyFailoverTest : StringSpec() {
  init {
    "an agent fails over to the second proxy endpoint when the first stops" {
      CollectorRegistry.defaultRegistry.clear()

      val stub =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = STUB_PORT) {
          routing {
            get("/metrics") { call.respondText(STUB_BODY) }
          }
        }
      stub.start(wait = false)

      val client = HttpClient(CIO)
      val proxyA = startProxyOn(httpPort = PROXY_A_HTTP_PORT, grpcPort = PROXY_A_GRPC_PORT)
      var proxyB: Proxy? = null
      var agent: Agent? = null

      try {
        proxyB = startProxyOn(httpPort = PROXY_B_HTTP_PORT, grpcPort = PROXY_B_GRPC_PORT)

        agent =
          Agent(
            options =
              agentOptions(
                [
                  "--config", FAILOVER_CONFIG_FILE,
                  "--proxy", "$LOOPBACK_HOST:$PROXY_A_GRPC_PORT,$LOOPBACK_HOST:$PROXY_B_GRPC_PORT",
                  "-Dagent.admin.enabled=false",
                  "-Dagent.metrics.enabled=false",
                ],
                exitOnMissingConfig = false,
              ),
            // Empty: Netty transport, so the endpoint the cursor selects is the one actually dialed.
            inProcessServerName = "",
            testMode = true,
          ) { startSync() }

        agent.awaitInitialConnection(20.seconds).shouldBeTrue()

        // The list is a priority order, so the agent must start on A, not merely on "some proxy".
        agent.proxyHost shouldBe "$LOOPBACK_HOST:$PROXY_A_GRPC_PORT"

        eventually(20.seconds) {
          val response = client.get("http://$LOOPBACK_HOST:$PROXY_A_HTTP_PORT/failover_metrics")
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain METRIC_LINE
        }

        val agentIdOnA = agent.agentId
        agentIdOnA shouldNotBe ""

        // Take the primary down. The agent should re-probe A first (the deliberate failback), fail, and
        // then advance to B.
        proxyA.stopSync(10.seconds)

        eventually(60.seconds) {
          // Serving through B's OWN http port is the assertion that matters: it can only succeed if the
          // agent registered its path on B, which requires having connected to B specifically.
          val response = client.get("http://$LOOPBACK_HOST:$PROXY_B_HTTP_PORT/failover_metrics")
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain METRIC_LINE
        }

        // The cursor really moved, rather than the body arriving via some other route.
        agent.proxyHost shouldBe "$LOOPBACK_HOST:$PROXY_B_GRPC_PORT"

        // A fresh connection means a fresh proxy-assigned identity; B's id space is its own.
        agent.agentId shouldNotBe ""
        agent.agentId shouldNotBe agentIdOnA
      } finally {
        agent?.also { if (it.isRunning) runCatching { it.stopSync(10.seconds) } }
        client.close()
        proxyB?.also { runCatching { it.stopSync(10.seconds) } }
        runCatching { proxyA.stopSync(10.seconds) }
        stub.stop(0, 0)
      }
    }
  }

  private fun startProxyOn(
    httpPort: Int,
    grpcPort: Int,
  ): Proxy =
    Proxy(
      options =
        proxyOptions(
          [
            "--config", FAILOVER_CONFIG_FILE,
            "--agent_port", "$grpcPort",
            "-Dproxy.admin.enabled=false",
            "-Dproxy.metrics.enabled=false",
          ],
        ),
      proxyPort = httpPort,
      inProcessServerName = "",
      testMode = true,
    ) { startSync() }

  companion object {
    private const val FAILOVER_CONFIG_FILE = "config/test-configs/proxy-failover.conf"

    // Dedicated ports, following the one-off convention used by the other standalone harness specs
    // (9512, 9525, 9526, 9527) rather than TestPorts.kt, which mirrors real default config values.
    private const val PROXY_A_HTTP_PORT = 9530
    private const val PROXY_A_GRPC_PORT = 9531
    private const val PROXY_B_HTTP_PORT = 9532
    private const val PROXY_B_GRPC_PORT = 9533

    // Must match the pathConfigs url in proxy-failover.conf, which cannot see a dynamically bound port.
    private const val STUB_PORT = 9534

    private const val METRIC_LINE = "failover_metric_under_test 42"
    private val STUB_BODY =
      """
      # HELP failover_metric_under_test A metric served through whichever proxy the agent is on
      # TYPE failover_metric_under_test gauge
      $METRIC_LINE
      """.trimIndent() + "\n"
  }
}
