/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.proxy

import com.github.pambrose.common.concurrent.GenericExecutionThreadService
import com.github.pambrose.common.concurrent.genericServiceListener
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.util.sleep
import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals
import mu.KLogging
import kotlin.time.seconds

internal class AgentContextCleanupService(private val proxy: Proxy,
                                          private val configVals: ConfigVals.Proxy2.Internal2,
                                          initBlock: (AgentContextCleanupService.() -> Unit) = {}) :
    GenericExecutionThreadService() {

  init {
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
    initBlock(this)
  }

  override fun run() {
    val maxInactivityTime = configVals.maxAgentInactivitySecs.seconds
    val pauseTime = configVals.staleAgentCheckPauseSecs.seconds
    while (isRunning) {
      proxy.agentContextManager.agentContextMap
        .forEach { (agentId, agentContext) ->
          val inactivityDuration = agentContext.inactivityDuration
          if (inactivityDuration > maxInactivityTime) {
            logger.info { "Evicting agent after $inactivityDuration of inactivity $agentContext" }
            proxy.removeAgentContext(agentId)
            proxy.metrics { agentEvictionCount.inc() }
          }
        }
      sleep(pauseTime)
    }
  }

  override fun toString() =
    toStringElements {
      add("max inactivity secs", configVals.maxAgentInactivitySecs)
      add("pause secs", configVals.staleAgentCheckPauseSecs)
    }

  companion object : KLogging()
}
