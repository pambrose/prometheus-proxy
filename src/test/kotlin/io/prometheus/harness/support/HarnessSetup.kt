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

package io.prometheus.harness.support

import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.properties.Delegates.notNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

open class HarnessSetup {
  private val logger = logger {}
  protected var proxy: Proxy by notNull()
  protected var agent: Agent by notNull()

  protected fun setupProxyAndAgent(
    proxyPort: Int,
    proxySetup: () -> Proxy,
    agentSetup: () -> Agent,
    actions: () -> Unit = {},
    // How long the agent gets to connect, and then to register its initial paths.
    startupTimeout: Duration = 10.seconds,
  ) {
    CollectorRegistry.defaultRegistry.clear()

    // Wait for the proxy port to be available (previous test may not have fully released it)
    awaitPortFree(proxyPort)

    // Start the proxy first and then allow the agent to connect. If any step fails, stop whatever started, so the next
    // spec's ports are free.
    proxy = proxySetup.invoke()
    var startedAgent: Agent? = null
    try {
      agent = agentSetup.invoke().also { startedAgent = it }
      // Fail fast, naming the cause -- a TLS or auth misconfiguration, say. Waiting for the config-driven paths (from
      // harness.conf etc.) as well keeps their registration from racing the test's pathMapSize() on slow CI.
      check(agent.awaitInitialConnection(startupTimeout)) {
        "${agent.simpleClassName} did not connect to the proxy within $startupTimeout"
      }
      check(agent.awaitInitialPathsRegistered(startupTimeout)) {
        "${agent.simpleClassName} did not register its initial paths within $startupTimeout"
      }
      actions.invoke()
    } catch (e: Throwable) {
      runBlocking { TestUtils.stopAll(proxy, *listOfNotNull(startedAgent).toTypedArray()) }
      throw e
    }

    logger.info { "Started ${proxy.simpleClassName} and ${agent.simpleClassName}" }
  }

  protected fun takeDownProxyAndAgent() {
    runBlocking { TestUtils.stopAll(proxy, agent) }

    logger.info { "Stopped ${proxy.simpleClassName} and ${agent.simpleClassName}" }
  }
}

/**
 * Waits for [port] to be free, and fails if it is still taken after [maxAttempts] tries.
 *
 * A previous spec may not have released the port yet, so a short wait is normal. A port that stays taken means
 * something else holds it, and carrying on would only fail later, at bind time, with a less useful error.
 */
internal fun awaitPortFree(
  port: Int,
  maxAttempts: Int = 50,
  delayMs: Long = 200,
) {
  repeat(maxAttempts) {
    try {
      ServerSocket(port).use { return }
    } catch (_: Exception) {
      Thread.sleep(delayMs)
    }
  }
  error("Port $port is still in use after ${maxAttempts * delayMs}ms")
}
