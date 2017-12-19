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

package io.prometheus.agent

import io.grpc.*
import io.prometheus.Agent
import io.prometheus.Proxy
import org.slf4j.LoggerFactory

class AgentClientInterceptor(private val agent: Agent) : ClientInterceptor {

    override fun <ReqT, RespT> interceptCall(method: MethodDescriptor<ReqT, RespT>,
                                             callOptions: CallOptions,
                                             next: Channel): ClientCall<ReqT, RespT> {
        // final String methodName = method.getFullMethodName();
        // logger.info("Intercepting {}", methodName);
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(this.agent.channel!!.newCall(method, callOptions)) {
            override fun start(responseListener: ClientCall.Listener<RespT>, headers: Metadata) {
                super.start(
                        object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                            override fun onHeaders(headers: Metadata?) {
                                // Grab agent_id from headers if not already assigned
                                if (agent.agentId == null) {
                                    val agentId = headers!!.get(Metadata.Key.of(Proxy.AGENT_ID, Metadata.ASCII_STRING_MARSHALLER))
                                    if (agentId != null) {
                                        agent.agentId = agentId
                                        logger.info("Assigned agentId to $agent")
                                    }
                                    else {
                                        logger.error("Headers missing AGENT_ID key")
                                    }
                                }
                                super.onHeaders(headers)
                            }
                        },
                        headers)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentClientInterceptor::class.java)
    }
}
