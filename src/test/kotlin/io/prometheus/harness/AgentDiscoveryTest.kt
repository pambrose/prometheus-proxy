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
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.PathSource
import io.prometheus.agent.discovery.DiscoveryTestSupport.discoveryPathsHocon
import io.prometheus.client.CollectorRegistry
import io.prometheus.harness.HarnessConstants.CONFIG_ARG
import io.prometheus.harness.support.TestUtils
import io.prometheus.harness.support.exceptionHandler
import io.prometheus.proxy.ProxyOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.Duration.Companion.seconds

// Dynamic target discovery (Feature 1). The agent polls a discovery file every second and reconciles
// the DISCOVERED path set with no restart. A regression that stopped reconciling would fail the
// add/remove assertions below.
class AgentDiscoveryTest : StringSpec() {
  init {
    "agent adds, updates, and removes discovered paths from a watched file without restart" {
      CollectorRegistry.defaultRegistry.clear()

      val discoveryFile = File.createTempFile("discovery", ".conf").apply { deleteOnExit() }
      discoveryFile.writeText(discoveryPathsHocon("disc_a", "http://localhost:9100/a"))

      val proxy = startProxy(HTTP_PORT, AGENT_PORT)
      val agent =
        TestUtils.startAgent(
          args =
            listOf(
              "--proxy",
              "localhost:$AGENT_PORT",
              "-Dagent.discovery.enabled=true",
              "-Dagent.discovery.file.path=${discoveryFile.absolutePath}",
              "-Dagent.discovery.reconcileIntervalSecs=1",
            ),
        )

      try {
        agent.awaitInitialConnection(10.seconds).shouldBeTrue()

        // disc_a is discovered and registered (tagged DISCOVERED) within a couple reconcile ticks.
        eventually(10.seconds) {
          agent.pathManager["disc_a"].shouldNotBeNull().source shouldBe PathSource.DISCOVERED
        }

        // Rewrite the file: remove disc_a and add disc_b. The reconcile picks up both changes.
        discoveryFile.writeText(discoveryPathsHocon("disc_b", "http://localhost:9100/b"))
        eventually(10.seconds) {
          agent.pathManager["disc_b"].shouldNotBeNull()
          agent.pathManager["disc_a"].shouldBeNull()
        }

        // Emptying the file removes all discovered paths.
        discoveryFile.writeText("paths = []\n")
        eventually(10.seconds) {
          agent.pathManager["disc_b"].shouldBeNull()
        }
      } finally {
        stopAll(proxy, agent)
      }
    }
  }

  private fun startProxy(
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
    return Proxy(options = proxyOptions, proxyPort = httpPort, testMode = true) { startSync() }
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

    // Dedicated ports to avoid clashing with the shared harness ports and the auth tests.
    private const val HTTP_PORT = 9516
    private const val AGENT_PORT = 50464
  }
}
