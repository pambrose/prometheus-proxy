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

import ch.qos.logback.classic.Level
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.TestPorts
import io.prometheus.common.captureLogs
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.TestUtils.stopAll
import io.prometheus.proxy.ProxyServiceImpl
import kotlin.time.Duration.Companion.seconds

// A static path the proxy rejects at connect because a live agent of another identity still serves it is retried for
// the connection's lifetime, so it registers once that agent is gone, with no reconnect. A path the agent's identity
// may never register is rejected as not retryable, so the agent tries it once rather than on every retry. Netty, so
// each agent's token crosses the wire and resolves to its own identity.
class AgentRejectedPathRetryTest : StringSpec() {
  init {
    "a rejected static path should register once the agent of another identity that held it disconnects" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startProxy(args = ["--agent_port", "$AGENT_PORT"], proxyPort = HTTP_PORT, configArgs = CONFIG_ARG)
      val agents = mutableListOf<Agent>()
      val proxyWarnings =
        captureLogs<ProxyServiceImpl>(Level.WARN) {
          try {
            val agentA = startAgentWithToken(TOKEN_A).also { agents += it }
            agentA.awaitInitialConnection(10.seconds).shouldBeTrue()
            eventually(10.seconds) { ownerOf(proxy, SHARED_PATH) shouldBe agentA.agentId }

            val agentB = startAgentWithToken(TOKEN_B).also { agents += it }
            agentB.awaitInitialConnection(10.seconds).shouldBeTrue()
            eventually(10.seconds) { ownerOf(proxy, B_PATH) shouldBe agentB.agentId }
            // team_b may register shared_metrics, but team_a's live agent serves it, so agent B's registration of it
            // was rejected.
            ownerOf(proxy, SHARED_PATH) shouldBe agentA.agentId

            agentA.stopSync(10.seconds)

            eventually(15.seconds) { ownerOf(proxy, SHARED_PATH) shouldBe agentB.agentId }
            // a_metrics, which team_b may never register, was never kept for a retry. Eventually, since the proxy
            // records shared_metrics just before agent B drops it from its retries.
            eventually(5.seconds) { agentB.pathManager.hasRejectedStaticPaths.shouldBeFalse() }
          } finally {
            stopAll(proxy, *agents.toTypedArray())
          }
        }

      // team_b may never register a_metrics, so agent B's attempt at connect is its only one.
      proxyWarnings.count { "identity 'team_b' denied registration of path /$A_PATH" in it.formattedMessage } shouldBe 1
    }
  }

  private fun ownerOf(
    proxy: Proxy,
    path: String,
  ): String? = proxy.pathManager.getAgentContextInfo(path)?.agentContexts?.singleOrNull()?.agentId

  private fun startAgentWithToken(token: String): Agent =
    startAgent(args = ["--proxy", "localhost:$AGENT_PORT", "--agent_token", token], configArgs = CONFIG_ARG)

  companion object {
    private const val TOKEN_A = "team-a-token"
    private const val TOKEN_B = "team-b-token"

    // Must match path-retry.conf: shared_metrics is registrable by both identities, a_metrics only by team_a, and
    // b_metrics only by team_b.
    private const val SHARED_PATH = "shared_metrics"
    private const val A_PATH = "a_metrics"
    private const val B_PATH = "b_metrics"

    private val CONFIG_ARG = ["--config", "config/test-configs/path-retry.conf"]

    private const val HTTP_PORT = TestPorts.REJECTED_PATH_RETRY_HTTP_PORT
    private const val AGENT_PORT = TestPorts.REJECTED_PATH_RETRY_AGENT_PORT
  }
}
