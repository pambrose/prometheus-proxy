/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.Proxy
import io.prometheus.common.delay
import kotlinx.coroutines.runBlocking
import mu.KLogging
import kotlin.time.seconds

class AgentContextCleanupService(private val proxy: Proxy,
                                 initBlock: (AgentContextCleanupService.() -> Unit) = {}) :
    GenericExecutionThreadService() {

    init {
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
        initBlock(this)
    }

    @Throws(Exception::class)
    override fun run() {
        val maxInactivityTime = proxy.configVals.maxAgentInactivitySecs.seconds
        val pauseTime = proxy.configVals.staleAgentCheckPauseSecs.seconds
        while (isRunning) {
            proxy.agentContextManager.agentContextMap
                .forEach { (agentId, agentContext) ->
                    val inactivityTime = agentContext.inactivityTime
                    if (inactivityTime > maxInactivityTime) {
                        logger.info { "Evicting agent after $inactivityTime secs of inactivty $agentContext" }
                        proxy.removeAgentContext(agentId)
                        if (proxy.isMetricsEnabled)
                            proxy.metrics.agentEvictions.inc()
                    }
                }
            runBlocking {
                delay(pauseTime)
            }
        }
    }

    override fun toString() =
        toStringElements {
            add("max inactivity secs", proxy.configVals.maxAgentInactivitySecs)
            add("pause secs", proxy.configVals.staleAgentCheckPauseSecs)
        }

    companion object : KLogging()
}
