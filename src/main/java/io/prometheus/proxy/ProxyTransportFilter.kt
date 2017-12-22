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

import io.grpc.Attributes
import io.grpc.ServerTransportFilter
import io.prometheus.Proxy
import org.slf4j.LoggerFactory

class ProxyTransportFilter(private val proxy: Proxy) : ServerTransportFilter() {

    private fun getRemoteAddr(attributes: Attributes): String {
        val key = attributes.keys().first { "remote-addr" == it.toString() }
        return if (key == null) "Unknown" else attributes.get(key)?.toString() ?: "Unknown"
    }

    override fun transportReady(attributes: Attributes): Attributes {
        val remoteAddr = this.getRemoteAddr(attributes)
        val agentContext = AgentContext(this.proxy, remoteAddr)
        this.proxy.addAgentContext(agentContext)
        logger.info("Connected to $agentContext")
        return Attributes.newBuilder()
                .set(Proxy.ATTRIB_AGENT_ID, agentContext.agentId)
                .setAll<Any>(attributes)
                .build()
    }

    override fun transportTerminated(attributes: Attributes?) {
        val agentId = attributes!!.get(Proxy.ATTRIB_AGENT_ID)
        this.proxy.removePathByAgentId(agentId)
        val agentContext = this.proxy.removeAgentContext(agentId)
        if (agentContext != null)
            logger.info("Disconnected from $agentContext")
        else
            logger.info("Disconnected with invalid agentId: $agentId")
        super.transportTerminated(attributes)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProxyTransportFilter::class.java)
    }
}
