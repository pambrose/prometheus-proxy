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

package io.prometheus.agent

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.common.GrpcObjects.EMPTY_AGENTID
import mu.KLogging

class AgentClientInterceptor(private val agent: Agent) : ClientInterceptor {

  override fun <ReqT, RespT> interceptCall(method: MethodDescriptor<ReqT, RespT>,
                                           callOptions: CallOptions,
                                           next: Channel): ClientCall<ReqT, RespT> =
  // final String methodName = method.getFullMethodName();
      // logger.info {"Intercepting {}", methodName);
      object :
          ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
              agent.grpcService.channel.newCall(method, callOptions)) {
        override fun start(responseListener: Listener<RespT>, metadata: Metadata) {
          super.start(
              object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                override fun onHeaders(headers: Metadata?) {
                  if (headers == null) {
                    logger.error { "Missing headers" }
                  }
                  else {
                    // Grab agent_id from headers if not already assigned
                    if (agent.agentId.isEmpty()) {
                      headers.get(Metadata.Key.of(Proxy.AGENT_ID, Metadata.ASCII_STRING_MARSHALLER))?.also {
                        agent.agentId = it
                        check(agent.agentId.isNotEmpty()) { EMPTY_AGENTID }
                        logger.debug { "Assigned agentId to $agent" }
                      } ?: logger.error { "Headers missing AGENT_ID key" }
                    }
                  }
                  super.onHeaders(headers)
                }
              },
              metadata
          )
        }
      }

  companion object : KLogging()
}
