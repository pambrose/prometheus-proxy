/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.ForwardingClientCallListener
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.prometheus.Agent
import io.prometheus.common.GrpcConstants.META_AGENT_ID_KEY
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG

internal class AgentClientInterceptor(
  private val agent: Agent,
) : ClientInterceptor {
  override fun <ReqT, RespT> interceptCall(
    method: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions,
    next: Channel,
  ): ClientCall<ReqT, RespT> =
    object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
      agent.grpcService.channel.newCall(method, callOptions),
    ) {
      override fun start(
        responseListener: Listener<RespT>,
        metadata: Metadata,
      ) {
        super.start(
          object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
            override fun onHeaders(headers: Metadata) {
              // Grab agent_id from headers if not already assigned
              if (agent.agentId.isEmpty()) {
                headers.get(META_AGENT_ID_KEY)
                  ?.also { agentId ->
                    agent.agentId = agentId
                    check(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
                    logger.info { "Assigned agentId: $agentId to $agent" }
                  } ?: logger.error { "Headers missing AGENT_ID key" }
              }

              super.onHeaders(headers)
            }
          },
          metadata,
        )
      }
    }

  companion object {
    private val logger = logger {}
  }
}
