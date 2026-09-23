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

package io.prometheus.harness.support

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.Agent
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.TestPorts.HARNESS_AGENT_ADMIN_PORT
import io.prometheus.common.TestPorts.HARNESS_AGENT_METRICS_PORT
import io.prometheus.common.TestPorts.HARNESS_HELPERS_HTTP_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_ADMIN_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_METRICS_PORT
import io.prometheus.harness.HarnessConstants
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.TestUtils.stopAll
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The harness helpers used to warn and carry on: a port still held after the wait logged a warning and the spec went
// on to fail at bind time, and a missing test config was silently fetched from GitHub master. Both now fail at once,
// naming the port or file.
class HarnessHelpersTest : StringSpec() {
  init {
    "awaitPortFree should return once the port is free" {
      val port = ServerSocket(0).use { it.localPort }
      awaitPortFree(port, maxAttempts = 3, delayMs = 10)
    }

    "awaitPortFree should fail when the port stays taken" {
      ServerSocket(0).use { taken ->
        val e = shouldThrow<IllegalStateException> { awaitPortFree(taken.localPort, maxAttempts = 2, delayMs = 10) }
        e.message shouldContain "${taken.localPort}"
      }
    }

    // awaitPortReady only logged a warning when a server never came up, so the test went on to fail later on a
    // symptom -- a refused scrape -- far from the cause. It now fails at once, like awaitPortFree, naming the port.
    "awaitPortReady should return once the port accepts connections" {
      ServerSocket(0).use { listening -> HarnessTests.awaitPortReady(listening.localPort, 2.seconds) }
    }

    "awaitPortReady should fail when nothing listens on the port" {
      val port = ServerSocket(0).use { it.localPort }
      val e = shouldThrow<IllegalStateException> { HarnessTests.awaitPortReady(port, 300.milliseconds) }
      e.message shouldContain "$port"
    }

    // The launchers left the proxy's gRPC port and both sides' admin and metrics servers on the product defaults, so
    // a proxy or agent already running on the machine -- or another project's server on 8092 -- failed every spec
    // that started one, at bind time.
    "startProxy and startAgent should bind the harness ports, not the product defaults" {
      CollectorRegistry.defaultRegistry.clear()
      val proxy = startProxy(adminEnabled = true, metricsEnabled = true, proxyPort = HARNESS_HELPERS_HTTP_PORT)
      val agents = mutableListOf<Agent>()
      try {
        val agent = startAgent(adminEnabled = true, metricsEnabled = true).also { agents += it }

        proxy.options.proxyAgentPort shouldBe HARNESS_PROXY_AGENT_PORT
        proxy.options.adminPort shouldBe HARNESS_PROXY_ADMIN_PORT
        proxy.options.metricsPort shouldBe HARNESS_PROXY_METRICS_PORT
        agent.options.adminPort shouldBe HARNESS_AGENT_ADMIN_PORT
        agent.options.metricsPort shouldBe HARNESS_AGENT_METRICS_PORT
        // Over Netty, so connecting shows the agent dials the proxy's harness gRPC port.
        agent.awaitInitialConnection(10.seconds).shouldBeTrue()
      } finally {
        stopAll(proxy, *agents.toTypedArray())
      }
    }

    // Setup fails at once when the agent never connects, naming the cause, and stops what it started.
    "setupProxyAndAgent should fail, and stop both services, when the agent never connects" {
      val unusedPort = ServerSocket(0).use { it.localPort }
      val harness =
        object : HarnessSetup() {
          fun setUp() =
            setupProxyAndAgent(
              proxyPort = HARNESS_HELPERS_HTTP_PORT,
              proxySetup = { startProxy(proxyPort = HARNESS_HELPERS_HTTP_PORT) },
              // Nothing listens there, so the agent never connects. A 1s reconnect pause lets it stop without waiting
              // out the 3s default.
              agentSetup = {
                startAgent(args = ["--proxy", "localhost:$unusedPort", "-Dagent.internal.reconnectPauseSecs=1"])
              },
              startupTimeout = 1.seconds,
            )

          fun tearDown() = takeDownProxyAndAgent()
        }
      try {
        shouldThrow<IllegalStateException> { harness.setUp() }.message shouldContain "did not connect"
        // Both were stopped, so their ports are free again.
        awaitPortFree(HARNESS_HELPERS_HTTP_PORT, maxAttempts = 10, delayMs = 100)
        awaitPortFree(HARNESS_PROXY_AGENT_PORT, maxAttempts = 10, delayMs = 100)
      } finally {
        runCatching { harness.tearDown() }
      }
    }

    "localConfigFile should return a test config that exists" {
      HarnessConstants.localConfigFile(EXISTING_CONFIG) shouldBe EXISTING_CONFIG
    }

    "localConfigFile should fail for a missing test config rather than fetch one from GitHub" {
      val e = shouldThrow<IllegalArgumentException> { HarnessConstants.localConfigFile(MISSING_CONFIG) }
      e.message shouldContain MISSING_CONFIG
    }
  }

  companion object {
    private const val EXISTING_CONFIG = "config/test-configs/harness.conf"
    private const val MISSING_CONFIG = "config/test-configs/no-such-config.conf"
  }
}
