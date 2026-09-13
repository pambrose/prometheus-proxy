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
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.TestPorts
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.proxy.AgentContext
import kotlin.time.Duration.Companion.seconds

// Proxy.startUp() decides whether to run the stale-agent eviction thread from two inputs: the
// proxy.internal.staleAgentCheckEnabled switch, and transportFilterDisabled, which forces it on because
// without a transport filter nothing else can reclaim a leaked context. The switch defaults to true, so
// every other spec runs with eviction on and neither of the other two branches is ever taken. No agent is
// needed: a context planted straight into the manager is exactly the leak the thread exists to clean up.
class InProcessStaleAgentCleanupTest : StringSpec() {
  init {
    // Both proxies in one case, because the forced-on proxy's eviction is what proves a full check cycle
    // elapsed. Asserting the other proxy still holds its context at that moment is stronger evidence than
    // any fixed sleep, and costs no wall clock: the two run concurrently rather than one after the other.
    "stale-agent cleanup should run only when enabled, or when the transport filter forces it on" {
      CollectorRegistry.defaultRegistry.clear()

      // Short enough that a running eviction thread fires well inside a test's patience.
      val staleCheckArgs = [
        "-Dproxy.internal.staleAgentCheckEnabled=false",
        "-Dproxy.internal.maxAgentInactivitySecs=1",
        "-Dproxy.internal.staleAgentCheckPauseSecs=1",
      ]
      val checksOff = startProxy(serverName = "stale-off", proxyPort = OFF_HTTP_PORT, args = staleCheckArgs)
      val forcedOn =
        startProxy(serverName = "stale-forced", proxyPort = FORCED_HTTP_PORT, args = staleCheckArgs + "--tf_disabled")

      try {
        val leakedOff = AgentContext("leaked-off").also { checksOff.agentContextManager.addAgentContext(it) }
        val leakedForced = AgentContext("leaked-forced").also { forcedOn.agentContextManager.addAgentContext(it) }

        eventually(10.seconds) {
          forcedOn.agentContextManager.getAgentContext(leakedForced.agentId).shouldBeNull()
        }

        // Same inactivity limit, same elapsed time, eviction thread never started.
        checksOff.agentContextManager.getAgentContext(leakedOff.agentId).shouldNotBeNull()
      } finally {
        runCatching { checksOff.stopSync(10.seconds) }
        runCatching { forcedOn.stopSync(10.seconds) }
      }
    }
  }

  companion object {
    private const val OFF_HTTP_PORT = TestPorts.STALE_CLEANUP_OFF_HTTP_PORT
    private const val FORCED_HTTP_PORT = TestPorts.STALE_CLEANUP_FORCED_HTTP_PORT
  }
}
