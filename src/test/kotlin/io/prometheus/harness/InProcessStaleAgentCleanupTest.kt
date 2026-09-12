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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.prometheus.Proxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.proxyOptions
import io.prometheus.harness.HarnessConstants.CONFIG_ARG
import io.prometheus.proxy.AgentContext
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

// Proxy.startUp() decides whether to run the stale-agent eviction thread from two inputs: the
// proxy.internal.staleAgentCheckEnabled switch, and transportFilterDisabled, which forces it on because
// without a transport filter nothing else can reclaim a leaked context. The switch defaults to true, so
// every other spec runs with eviction on and neither of the other two branches is ever taken. No agent is
// needed: a context planted straight into the manager is exactly the leak the thread exists to clean up.
class InProcessStaleAgentCleanupTest : StringSpec() {
  private fun startProxyWithStaleCheckOff(
    proxyPort: Int,
    vararg extraArgs: String,
  ): Proxy {
    val args = [
      "-Dproxy.admin.enabled=false",
      "-Dproxy.metrics.enabled=false",
      "-Dproxy.internal.staleAgentCheckEnabled=false",
      // Short enough that a running eviction thread fires well inside a test's patience.
      "-Dproxy.internal.maxAgentInactivitySecs=1",
      "-Dproxy.internal.staleAgentCheckPauseSecs=1",
    ] + extraArgs
    return Proxy(
      options = proxyOptions(CONFIG_ARG + args),
      proxyPort = proxyPort,
      inProcessServerName = "stale-check-${System.nanoTime()}",
      testMode = true,
    ) { startSync() }
  }

  init {
    "with stale-agent checks disabled a leaked context is left alone" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startProxyWithStaleCheckOff(OFF_HTTP_PORT)
      try {
        val leaked = AgentContext("leaked").also { proxy.agentContextManager.addAgentContext(it) }

        // Several check intervals past the inactivity limit: an eviction thread, had one been started,
        // would have removed the context by now.
        delay(3.seconds)

        proxy.agentContextManager.getAgentContext(leaked.agentId).shouldNotBeNull()
      } finally {
        runCatching { proxy.stopSync(10.seconds) }
      }
    }

    "disabling the transport filter forces stale-agent cleanup on even when it is off in config" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy = startProxyWithStaleCheckOff(FORCED_HTTP_PORT, "--tf_disabled")
      try {
        val leaked = AgentContext("leaked").also { proxy.agentContextManager.addAgentContext(it) }

        eventually(10.seconds) {
          proxy.agentContextManager.getAgentContext(leaked.agentId).shouldBeNull()
        }
      } finally {
        runCatching { proxy.stopSync(10.seconds) }
      }
    }
  }

  companion object {
    private const val OFF_HTTP_PORT = 9563
    private const val FORCED_HTTP_PORT = 9564
  }
}
