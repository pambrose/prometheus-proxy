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

// Item 28: negative-path coverage for mutual TLS. The existing TlsWithMutualAuthTest /
// TlsNoMutualAuthTest run over the in-process transport (a non-empty serverName selects in-process,
// which ignores the TLS context), so they only verify TLS *config* parsing, never a real handshake.
//
// This test uses an empty serverName, which selects the Netty transport and performs an actual TLS
// handshake. The proxy requires a client certificate (--trust enables mutual auth); the agent is
// started WITHOUT --cert/--key, so the handshake is rejected and the agent never registers. A
// regression that silently stopped enforcing client-cert verification — a security boundary — would
// let the agent connect and fail this test.
class TlsMutualAuthRejectionTest : StringSpec() {
  init {
    "agent without a client certificate is rejected by a mutual-auth proxy" {
      CollectorRegistry.defaultRegistry.clear()

      val proxyOptions =
        ProxyOptions(
          buildList {
            addAll(CONFIG_ARG)
            addAll(listOf("--agent_port", AGENT_PORT.toString()))
            addAll(listOf("--cert", "testing/certs/server1.pem"))
            addAll(listOf("--key", "testing/certs/server1.key"))
            addAll(listOf("--trust", "testing/certs/ca.pem"))
            add("-Dproxy.admin.enabled=false")
            add("-Dproxy.metrics.enabled=false")
          },
        )
      // Empty inProcessServerName => Netty transport => a real TLS handshake is performed.
      val proxy = Proxy(options = proxyOptions, proxyPort = HTTP_PORT, testMode = true) { startSync() }

      val agentOptions =
        AgentOptions(
          args =
            buildList {
              addAll(CONFIG_ARG)
              addAll(listOf("--proxy", "localhost:$AGENT_PORT"))
              // Trust the proxy's server cert, but present NO client certificate (no --cert/--key).
              addAll(listOf("--trust", "testing/certs/ca.pem"))
              addAll(listOf("--override", "foo.test.google.fr"))
              add("-Dagent.admin.enabled=false")
              add("-Dagent.metrics.enabled=false")
            },
          exitOnMissingConfig = false,
        )
      val agent = Agent(options = agentOptions, testMode = true) { startSync() }

      try {
        // The handshake fails (no client cert), so the agent keeps retrying and never registers.
        // awaitInitialConnection returns false once the timeout elapses.
        agent.awaitInitialConnection(5.seconds).shouldBeFalse()
      } finally {
        // The test block is itself a suspend function, so suspend via coroutineScope rather than
        // blocking the thread with runBlocking (mirrors the teardown in HarnessTests).
        coroutineScope {
          for (service in listOf(proxy, agent)) {
            launch(Dispatchers.IO + exceptionHandler(logger)) { service.stopSync() }
          }
        }
      }
    }
  }

  companion object {
    private val logger = logger {}

    // Dedicated ports to avoid clashing with the shared harness ports (9505 / 50051 / 50440).
    private const val HTTP_PORT = 9512
    private const val AGENT_PORT = 50460
  }
}
