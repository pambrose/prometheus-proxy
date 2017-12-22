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

import io.grpc.*
import io.prometheus.Proxy

class ProxyInterceptor : ServerInterceptor {

    override fun <ReqT, RespT> interceptCall(call: ServerCall<ReqT, RespT>,
                                             requestHeaders: Metadata,
                                             handler: ServerCallHandler<ReqT, RespT>): ServerCall.Listener<ReqT> {
        val attributes = call.attributes
        //val methodDescriptor = call.methodDescriptor
        // final String methodName = methodDescriptor.getFullMethodName();
        // logger.info("Intercepting {}", methodName);

        return handler.startCall(
                object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                    override fun sendHeaders(headers: Metadata) {
                        // agent_id was assigned in ServerTransportFilter
                        val agentId = attributes.get(Proxy.ATTRIB_AGENT_ID)
                        if (agentId != null)
                            headers.put(META_AGENT_ID, agentId)
                        super.sendHeaders(headers)
                    }
                },
                requestHeaders)
    }

    companion object {
        private val META_AGENT_ID = Metadata.Key.of(Proxy.AGENT_ID, Metadata.ASCII_STRING_MARSHALLER)
    }
}
