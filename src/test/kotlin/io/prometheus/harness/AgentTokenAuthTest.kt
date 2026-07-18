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

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentOptions
import io.prometheus.client.CollectorRegistry
import io.prometheus.harness.HarnessConstants.CONFIG_ARG
import io.prometheus.harness.support.exceptionHandler
import io.prometheus.proxy.ProxyOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

// Pre-shared agent-token authentication (security doc item #1). Both cases use the Netty transport
// (empty serverName) so the token metadata header actually crosses the wire and is validated by the
// proxy's AgentAuthServerInterceptor (the legacy shared token is honored as an allow-all identity).
// A regression that stopped enforcing the token — a security boundary — would let the wrong-token
// agent connect and fail the reject case.
class AgentTokenAuthTest : StringSpec() {
  init {
    "agent presenting the matching token connects to a token-protected proxy" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startTokenProxy(HTTP_PORT_OK, AGENT_PORT_OK, token = TOKEN)
      val agent = startAgent(AGENT_PORT_OK, token = TOKEN)

      try {
        // Matching token => connectAgent passes the interceptor and the agent registers.
        agent.awaitInitialConnection(10.seconds).shouldBeTrue()
      } finally {
        stopAll(proxy, agent)
      }
    }

    "agent presenting the wrong token is rejected by a token-protected proxy" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startTokenProxy(HTTP_PORT_BAD, AGENT_PORT_BAD, token = TOKEN)
      val agent = startAgent(AGENT_PORT_BAD, token = "wrong-token")

      try {
        // Wrong token => every RPC is rejected with UNAUTHENTICATED, so the agent keeps retrying
        // and never registers. awaitInitialConnection returns false once the timeout elapses.
        agent.awaitInitialConnection(5.seconds).shouldBeFalse()
      } finally {
        stopAll(proxy, agent)
      }
    }
  }

  private fun startTokenProxy(
    httpPort: Int,
    agentPort: Int,
    token: String,
  ): Proxy {
    val proxyOptions =
      ProxyOptions(
        buildList {
          addAll(CONFIG_ARG)
          addAll(["--agent_port", agentPort.toString()])
          addAll(["--agent_token", token])
          add("-Dproxy.admin.enabled=false")
          add("-Dproxy.metrics.enabled=false")
        },
      )
    // Empty inProcessServerName => Netty transport => the token header crosses the wire.
    return Proxy(options = proxyOptions, proxyPort = httpPort, testMode = true) { startSync() }
  }

  private fun startAgent(
    agentPort: Int,
    token: String,
  ): Agent {
    val agentOptions =
      AgentOptions(
        args =
          buildList {
            addAll(CONFIG_ARG)
            addAll(["--proxy", "localhost:$agentPort"])
            addAll(["--agent_token", token])
            add("-Dagent.admin.enabled=false")
            add("-Dagent.metrics.enabled=false")
          },
        exitOnMissingConfig = false,
      )
    return Agent(options = agentOptions, testMode = true) { startSync() }
  }

  private suspend fun stopAll(
    proxy: Proxy,
    agent: Agent,
  ) {
    coroutineScope {
      for (service in [proxy, agent]) {
        launch(Dispatchers.IO + exceptionHandler(logger)) { service.stopSync() }
      }
    }
  }

  companion object {
    private val logger = logger {}

    private const val TOKEN = "harness-secret-token"

    // Dedicated ports to avoid clashing with the shared harness ports and the TLS tests.
    private const val HTTP_PORT_OK = 9513
    private const val AGENT_PORT_OK = 50461
    private const val HTTP_PORT_BAD = 9514
    private const val AGENT_PORT_BAD = 50462
  }
}
