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
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.prometheus.Agent
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

// A discovered path the proxy rejects for a cause that can't clear -- here, one the agent's identity may never
// register -- is tried once rather than on every reconcile, so the proxy logs that rejection once. Netty, so the
// agent's token crosses the wire and resolves to its identity.
class AgentDiscoveredPathRejectionTest : StringSpec() {
  init {
    "a discovered path the agent's identity may never register should reach the proxy once" {
      CollectorRegistry.defaultRegistry.clear()

      val discoveryFile = File.createTempFile("discovery", ".conf").apply { deleteOnExit() }
      discoveryFile.writeText(discoveryPathsHocon(DENIED_PATH to TARGET_URL))

      val proxy = startProxy(args = ["--agent_port", "$AGENT_PORT"], proxyPort = HTTP_PORT, configArgs = CONFIG_ARG)
      val agents = mutableListOf<Agent>()
      val proxyWarnings =
        captureLogs<ProxyServiceImpl>(Level.WARN) {
          try {
            val agent =
              startAgent(
                args =
                  [
                    "--proxy",
                    "localhost:$AGENT_PORT",
                    "--agent_token",
                    TOKEN_B,
                    "-Dagent.discovery.enabled=true",
                    "-Dagent.discovery.file.path=${discoveryFile.absolutePath}",
                    "-Dagent.discovery.reconcileIntervalSecs=1",
                  ],
                configArgs = CONFIG_ARG,
              ).also { agents += it }
            agent.awaitInitialConnection(10.seconds).shouldBeTrue()

            // Each rewrite keeps the denied path and adds one team_b may register. Seeing it registered proves
            // another reconcile ran with the denied path still listed.
            for (allowed in ["b_discovered_1", "b_discovered_2", "b_discovered_3"]) {
              discoveryFile.writeText(discoveryPathsHocon(DENIED_PATH to TARGET_URL, allowed to TARGET_URL))
              eventually(10.seconds) { agent.pathManager[allowed].shouldNotBeNull() }
            }
          } finally {
            stopAll(proxy, *agents.toTypedArray())
          }
        }

      val denial = "identity 'team_b' denied registration of path /$DENIED_PATH"
      proxyWarnings.count { denial in it.formattedMessage } shouldBe 1
    }
  }

  companion object {
    private const val TOKEN_B = "team-b-token"

    // Must match path-retry.conf: team_b may register b_* paths, but not a_* ones.
    private const val DENIED_PATH = "a_discovered"
    private const val TARGET_URL = "http://localhost:9100/metrics"

    private val CONFIG_ARG = ["--config", "config/test-configs/path-retry.conf"]

    private const val HTTP_PORT = TestPorts.DISCOVERED_PATH_REJECTION_HTTP_PORT
    private const val AGENT_PORT = TestPorts.DISCOVERED_PATH_REJECTION_AGENT_PORT
  }
}
