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
import ch.qos.logback.classic.spi.ILoggingEvent
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.discovery.DiscoveryTestSupport.discoveryPathsHocon
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.TestPorts
import io.prometheus.common.captureLogs
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.TestUtils.stopAll
import io.prometheus.proxy.ProxyServiceImpl
import java.io.File
import kotlin.time.Duration.Companion.seconds

// How an agent handles the proxy rejecting its paths, with two per-agent identities. A static path rejected because a
// live agent of another identity serves it is retried for the connection's lifetime, so it registers once that agent
// is gone, with no reconnect. A path the agent's identity may never register can't clear, so the agent tries it once,
// whether it is static or discovered. Netty, so each agent's token crosses the wire and resolves to its own identity.
class AgentRejectedPathRetryTest : StringSpec() {
  init {
    "a rejected static path should register once the agent of another identity that held it disconnects" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startProxy(args = ["--agent_port", "$AGENT_PORT"], proxyPort = HTTP_PORT, configArgs = CONFIG_ARG)
      val agents = mutableListOf<Agent>()
      // One capture of the proxy's loggers: the denial comes from ProxyServiceImpl and the takeover rejection
      // from ProxyPathManager.
      val proxyWarnings =
        captureLogs(PROXY_LOGGERS, Level.WARN) {
          try {
            val agentA = startAgentWithToken(TOKEN_A).also { agents += it }
            agentA.awaitInitialConnection(10.seconds).shouldBeTrue()
            eventually(10.seconds) { ownerOf(proxy, SHARED_PATH) shouldBe agentA.agentId }

            val agentB = startAgentWithToken(TOKEN_B).also { agents += it }
            agentB.awaitInitialConnection(10.seconds).shouldBeTrue()
            val agentBId = agentB.agentId
            eventually(10.seconds) { ownerOf(proxy, B_PATH) shouldBe agentBId }
            // team_b may register shared_metrics, but team_a's live agent serves it, so agent B's registration of
            // it was rejected.
            ownerOf(proxy, SHARED_PATH) shouldBe agentA.agentId

            agentA.stopSync(10.seconds)

            // Patient enough for the retry backoff: each rejection doubles the wait from rejectedPathRetrySecs (1s
            // here), so by the time agent A stops, agent B's next retry can be tens of seconds out.
            eventually(45.seconds) { ownerOf(proxy, SHARED_PATH) shouldBe agentBId }
            // a_metrics, which team_b may never register, was never kept for a retry. Eventually, since the proxy
            // records shared_metrics just before agent B drops it from its retries.
            eventually(5.seconds) { agentB.pathManager.hasRejectedStaticPaths.shouldBeFalse() }
            // With nothing left to retry, the retry task must idle rather than end: a connection task ending ends the
            // connection, which would drop the path and re-register it under a new agentId.
            continually(3.seconds) { ownerOf(proxy, SHARED_PATH) shouldBe agentBId }
          } finally {
            stopAll(proxy, *agents.toTypedArray())
          }
        }

      // team_b may never register a_metrics, so agent B's attempt at connect is its only one.
      proxyWarnings.deniedCount("team_b", A_PATH) shouldBe 1
      // However often agent B retried shared_metrics, the proxy reported that conflict once.
      proxyWarnings.count { "cannot take it over" in it.formattedMessage } shouldBe 1
    }

    "a discovered path the agent's identity may never register should reach the proxy once" {
      CollectorRegistry.defaultRegistry.clear()

      val discoveryFile = File.createTempFile("discovery", ".conf").apply { deleteOnExit() }
      discoveryFile.writeText(discoveryPathsHocon(DISCOVERED_DENIED_PATH to TARGET_URL))

      val proxy = startProxy(args = ["--agent_port", "$AGENT_PORT"], proxyPort = HTTP_PORT, configArgs = CONFIG_ARG)
      val agents = mutableListOf<Agent>()
      val proxyWarnings =
        captureLogs<ProxyServiceImpl>(Level.WARN) {
          try {
            val agent =
              startAgentWithToken(
                TOKEN_B,
                "-Dagent.discovery.enabled=true",
                "-Dagent.discovery.file.path=${discoveryFile.absolutePath}",
                "-Dagent.discovery.reconcileIntervalSecs=1",
              ).also { agents += it }
            agent.awaitInitialConnection(10.seconds).shouldBeTrue()

            // Each rewrite keeps the denied path and adds one team_b may register. Seeing it registered proves
            // another reconcile ran with the denied path still listed.
            for (allowed in ["b_discovered_1", "b_discovered_2", "b_discovered_3"]) {
              discoveryFile.writeText(discoveryPathsHocon(DISCOVERED_DENIED_PATH to TARGET_URL, allowed to TARGET_URL))
              eventually(10.seconds) { agent.pathManager[allowed].shouldNotBeNull() }
            }
          } finally {
            stopAll(proxy, *agents.toTypedArray())
          }
        }

      proxyWarnings.deniedCount("team_b", DISCOVERED_DENIED_PATH) shouldBe 1
    }
  }

  private fun ownerOf(
    proxy: Proxy,
    path: String,
  ): String? = proxy.pathManager.getAgentContextInfo(path)?.agentContexts?.singleOrNull()?.agentId

  // How many times the proxy logged denying identity's registration of path.
  private fun List<ILoggingEvent>.deniedCount(
    identity: String,
    path: String,
  ): Int = count { "identity '$identity' denied registration of path /$path" in it.formattedMessage }

  private fun startAgentWithToken(
    token: String,
    vararg extraArgs: String,
  ): Agent =
    startAgent(args = ["--proxy", "localhost:$AGENT_PORT", "--agent_token", token] + extraArgs, configArgs = CONFIG_ARG)

  companion object {
    private const val TOKEN_A = "team-a-token"
    private const val TOKEN_B = "team-b-token"

    // Must match path-retry.conf: shared_metrics is registrable by both identities, a_* paths only by team_a, and b_*
    // paths only by team_b.
    private const val SHARED_PATH = "shared_metrics"
    private const val A_PATH = "a_metrics"
    private const val B_PATH = "b_metrics"
    private const val DISCOVERED_DENIED_PATH = "a_discovered"
    private const val TARGET_URL = "http://localhost:9100/metrics"

    private const val PROXY_LOGGERS = "io.prometheus.proxy"

    private val CONFIG_ARG = ["--config", "config/test-configs/path-retry.conf"]

    private const val HTTP_PORT = TestPorts.REJECTED_PATH_RETRY_HTTP_PORT
    private const val AGENT_PORT = TestPorts.REJECTED_PATH_RETRY_AGENT_PORT
  }
}
