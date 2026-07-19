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
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.harness.HarnessConstants.DEFAULT_CHUNK_SIZE_BYTES
import io.prometheus.harness.HarnessConstants.DEFAULT_SCRAPE_TIMEOUT_SECS
import io.prometheus.harness.HarnessConstants.HARNESS_CONFIG
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import kotlin.time.Duration.Companion.seconds

// Exercises the agent's full disconnect -> reconnect -> re-register cycle entirely in-process (no
// Docker), which the equivalent ContainersReconnectTest can only cover behind RUN_CONTAINER_TESTS.
// The agent's config-driven path (agent1_metrics, from harness.conf) must be re-registered after the
// agent loses its proxy-side context, guarding against regressions like clear() without a following
// registerPaths(). Heartbeat/reconnect intervals are shortened via -D so the cycle completes quickly.
class InProcessReconnectTest : StringSpec() {
  companion object : HarnessSetup()

  init {
    beforeSpec {
      setupProxyAndAgent(
        proxyPort = PROXY_PORT,
        proxySetup = { startProxy("reconnect") },
        agentSetup = {
          startAgent(
            serverName = "reconnect",
            scrapeTimeoutSecs = DEFAULT_SCRAPE_TIMEOUT_SECS,
            chunkContentSizeBytes = DEFAULT_CHUNK_SIZE_BYTES,
            maxConcurrentClients = HARNESS_CONFIG.concurrentClients,
            args = [
              "-Dagent.internal.heartbeatMaxInactivitySecs=1",
              "-Dagent.internal.heartbeatCheckPauseMillis=200",
              "-Dagent.internal.reconnectPauseSecs=1",
            ],
          )
        },
      )
    }

    afterSpec {
      takeDownProxyAndAgent()
    }

    "agent should reconnect and re-register its config paths after losing its proxy context" {
      val path = "agent1_metrics"

      // The config-driven path is registered after the initial connect.
      eventually(10.seconds) {
        proxy.pathManager.getAgentContextInfo(path).shouldNotBeNull()
      }
      val originalAgentId = agent.agentId
      originalAgentId.shouldNotBeEmpty()

      // Drop this agent's context on the proxy. The agent's next heartbeat returns valid=false, which
      // it surfaces as NOT_FOUND, closing the connection and forcing the run loop to reconnect.
      proxy.removeAgentContext(originalAgentId, "test: forced reconnect")

      // After reconnecting, the agent registers a fresh agentId and re-registers agent1_metrics.
      eventually(30.seconds) {
        agent.agentId.shouldNotBeEmpty()
        agent.agentId shouldNotBe originalAgentId
        proxy.pathManager.getAgentContextInfo(path).shouldNotBeNull()
      }
    }
  }
}
