/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.proxy

import com.github.pambrose.common.concurrent.GenericExecutionThreadService
import com.github.pambrose.common.concurrent.genericServiceListener
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.google.common.util.concurrent.MoreExecutors
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Background service that periodically evicts stale agent contexts.
 *
 * Runs a loop that checks for agents whose last activity exceeds
 * `maxAgentInactivitySecs`. Stale agents are removed from the path map and context
 * manager, and their in-flight scrape requests are failed. A TOCTOU re-check guards
 * against evicting agents that sent a heartbeat between the scan and the eviction.
 *
 * @param proxy the parent [Proxy] instance
 * @param configVals internal proxy configuration (inactivity timeout, check interval)
 * @param initBlock optional initialization block run after listener registration
 * @see AgentContextManager
 * @see Proxy
 */
internal class AgentContextCleanupService(
  private val proxy: Proxy,
  private val configVals: ConfigVals.Proxy2.Internal2,
  initBlock: (AgentContextCleanupService.() -> Unit) = {},
) : GenericExecutionThreadService() {
  private val shutdownLatch = CountDownLatch(1)

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
    initBlock(this)
  }

  override fun run() {
    val maxAgentInactivityTime = configVals.maxAgentInactivitySecs.seconds
    val pauseTimeSecs = configVals.staleAgentCheckPauseSecs.seconds
    while (isRunning) {
      val agentsToEvict = proxy.agentContextManager.findStaleAgents(maxAgentInactivityTime)

      agentsToEvict.forEach { (agentId, agentContext) ->
        // Re-check inactivity to avoid a TOCTOU race: the agent may have sent a
        // heartbeat between the findStaleAgents snapshot and this eviction attempt.
        val inactiveTime = agentContext.inactivityDuration
        if (inactiveTime > maxAgentInactivityTime) {
          logger.info {
            "Evicting agentId $agentId after $inactiveTime (max $maxAgentInactivityTime) of inactivity: $agentContext"
          }
          proxy.removeAgentContext(agentId, "Eviction")
          proxy.metrics { agentEvictionCount.inc() }
        } else {
          logger.debug {
            "Skipping eviction for agentId $agentId: activity detected since stale check ($inactiveTime)"
          }
        }
      }
      shutdownLatch.await(pauseTimeSecs.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }
  }

  override fun triggerShutdown() {
    shutdownLatch.countDown()
  }

  override fun toString() =
    toStringElements {
      add("max inactivity secs", configVals.maxAgentInactivitySecs)
      add("pause secs", configVals.staleAgentCheckPauseSecs)
    }

  companion object {
    private val logger = logger {}
  }
}
