/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.proxy

import com.google.common.util.concurrent.AbstractExecutionThreadService
import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.Proxy
import io.prometheus.common.GenericServiceListener
import io.prometheus.common.sleepForSecs
import io.prometheus.dsl.GuavaDsl.toStringElements
import org.slf4j.LoggerFactory

class AgentContextCleanupService(private val proxy: Proxy) : AbstractExecutionThreadService() {

    init {
        addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    @Throws(Exception::class)
    override fun run() {
        val maxInactivitySecs = proxy.configVals.internal.maxAgentInactivitySecs.toLong()
        val threadPauseSecs = proxy.configVals.internal.staleAgentCheckPauseSecs.toLong()
        while (isRunning) {
            proxy.agentContextMap
                    .forEach { agentId, agentContext ->
                        val inactivitySecs = agentContext.inactivitySecs
                        if (inactivitySecs > maxInactivitySecs) {
                            logger.info("Evicting agent after $inactivitySecs secs of inactivty $agentContext")
                            proxy.removeAgentContext(agentId)
                            if (proxy.isMetricsEnabled)
                                proxy.metrics.agentEvictions.inc()
                        }
                    }
            sleepForSecs(threadPauseSecs)
        }
    }

    override fun toString() =
            toStringElements {
                add("max inactivity secs", proxy.configVals.internal.maxAgentInactivitySecs)
                add("pause secs", proxy.configVals.internal.staleAgentCheckPauseSecs)
            }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentContextCleanupService::class.java)
    }
}
