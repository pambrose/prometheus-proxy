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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentOptions
import io.prometheus.agent.RequestFailureException
import io.prometheus.client.CollectorRegistry
import io.prometheus.harness.support.exceptionHandler
import io.prometheus.proxy.ProxyOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.seconds

// Per-agent identity and path authorization (Feature 3). The proxy is configured with two identities
// via a `proxy.auth` override; each may only register paths matching its glob patterns. The Netty
// transport (empty serverName) is used so the token metadata header crosses the wire and is resolved
// to an identity by the proxy's AgentAuthServerInterceptor. A regression that stopped enforcing path
// authorization — a security boundary — would let the "team_a" agent register a "team_b_*" path.
class AgentPathAuthTest : StringSpec() {
  init {
    "an agent may register a path its identity is authorized for and is denied others" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startAuthProxy(HTTP_PORT, AGENT_PORT)
      // The agent presents team_a's token; auto-registration is suppressed so paths are driven below.
      val agent = startAgent(AGENT_PORT, token = TOKEN_A)

      try {
        agent.awaitInitialConnection(10.seconds).shouldBeTrue()

        // team_a_* is authorized for the team_a identity => registration succeeds.
        agent.pathManager.registerPath("team_a_metrics", TARGET_URL)

        // team_b_* is not authorized for the team_a identity => the proxy rejects the registration,
        // which the agent surfaces as a RequestFailureException.
        shouldThrow<RequestFailureException> {
          agent.pathManager.registerPath("team_b_metrics", TARGET_URL)
        }
      } finally {
        stopAll(proxy, agent)
      }
    }
  }

  private fun startAuthProxy(
    httpPort: Int,
    agentPort: Int,
  ): Proxy {
    val proxyOptions =
      ProxyOptions(
        buildList {
          addAll(CONFIG_ARG)
          addAll(listOf("--agent_port", agentPort.toString()))
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
            addAll(listOf("--proxy", "localhost:$agentPort"))
            addAll(listOf("--agent_token", token))
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
      for (service in listOf(proxy, agent)) {
        launch(Dispatchers.IO + exceptionHandler(logger)) { service.stopSync() }
      }
    }
  }

  companion object {
    private val logger = logger {}

    private const val TOKEN_A = "team-a-token"
    private const val TOKEN_B = "team-b-token"
    private const val TARGET_URL = "http://localhost:9100/metrics"

    // The `proxy.auth` identity list is a HOCON list, which the -D property mechanism cannot express,
    // so it lives in a dedicated config file resolved like the shared harness config.
    private const val GH_PREFIX = "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/"
    private const val AUTH_CONFIG_FILE = "config/test-configs/path-auth.conf"

    private fun localOrGitHub(path: String): String = if (File(path).exists()) path else "$GH_PREFIX$path"

    private val CONFIG_ARG = listOf("--config", localOrGitHub(AUTH_CONFIG_FILE))

    // Dedicated ports to avoid clashing with the shared harness ports and the other auth tests.
    private const val HTTP_PORT = 9515
    private const val AGENT_PORT = 50463
  }
}
