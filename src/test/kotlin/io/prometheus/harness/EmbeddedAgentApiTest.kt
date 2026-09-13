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
import io.kotest.matchers.string.shouldHaveLength
import io.prometheus.Agent
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.harness.support.TestUtils.startProxy
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.seconds

// The public embedded entry point, end to end. AgentTest pins the failure contract (a missing config
// surfaces as ConfigLoadException instead of exiting the host JVM); this pins the success contract: an
// agent built from a config file connects to a real proxy over Netty, the returned handle reports the
// configured name, and shutdown() tears the connection down so the proxy forgets the agent. The banner
// is left on (the default) because that is what an embedding host gets unless it opts out.
class EmbeddedAgentApiTest : StringSpec() {
  init {
    "startAsyncAgent should connect the agent from a config file and shutdown() should disconnect it" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startProxy(args = ["--agent_port", "$GRPC_PORT"], proxyPort = HTTP_PORT)
      val configFile =
        createTempFile("embedded-agent", ".conf").toFile().apply {
          writeText(
            """
            agent {
              name = "$AGENT_NAME"
              proxy {
                hostname = "$LOOPBACK_HOST"
                port = $GRPC_PORT
              }
              pathConfigs = []
              internal.reconnectPauseSecs = 1
            }
            """.trimIndent(),
          )
        }

      var info: EmbeddedAgentInfo? = null
      try {
        // exitOnMissingConfig = false is the embedded mode: startup failures throw rather than exit.
        info = Agent.startAsyncAgent(configFile.absolutePath, exitOnMissingConfig = false)
        info.agentName shouldBe AGENT_NAME
        info.launchId shouldHaveLength 15

        // The handle hides the Agent, so the proxy is the only witness that it actually connected.
        eventually(30.seconds) {
          proxy.agentContextManager.agentContextEntries
            .any { it.value.agentName == AGENT_NAME }
            .shouldBeTrue()
        }

        info.shutdown()

        eventually(30.seconds) {
          proxy.agentContextManager.agentContextSize shouldBe 0
        }
      } finally {
        runCatching { info?.shutdown() }
        runCatching { proxy.stopSync(10.seconds) }
        configFile.delete()
      }
    }
  }

  companion object {
    private const val AGENT_NAME = "embedded-api-agent"

    // Dedicated ports, following the one-off convention the other standalone harness specs use.
    private const val HTTP_PORT = TestPorts.EMBEDDED_API_HTTP_PORT
    private const val GRPC_PORT = TestPorts.EMBEDDED_API_GRPC_PORT
  }
}
