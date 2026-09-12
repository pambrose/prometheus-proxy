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
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.prometheus.Agent
import io.prometheus.common.GrpcConstants.META_AGENT_ID_KEY

internal class AgentClientInterceptor(
  private val agent: Agent,
) : ClientInterceptor {
  override fun <ReqT, RespT> interceptCall(
    method: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions,
    next: Channel,
  ): ClientCall<ReqT, RespT> {
    val delegate = next.newCall(method, callOptions)
    return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(delegate) {
      override fun start(
        responseListener: Listener<RespT>,
        metadata: Metadata,
      ) {
        super.start(
          object : ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
            override fun onHeaders(headers: Metadata) {
              // Grab agent_id from headers if not already assigned
              synchronized(agent) {
                if (agent.agentId.isEmpty()) {
                  // takeIf sends a present-but-empty header down the same cancel path as a missing one.
                  // It used to reach the ?.also branch, which assigned the empty id and only then failed a
                  // check() -- throwing from the callback the cancel path below exists to avoid, and leaving
                  // the agent believing it had registered under an id the proxy rejects on every later RPC.
                  headers.get(META_AGENT_ID_KEY)
                    ?.takeIf { it.isNotEmpty() }
                    ?.also { agentId ->
                      agent.agentId = agentId
                      logger.info { "Assigned agentId: $agentId to $agent" }
                    } ?: run {
                    // Cancel the call instead of throwing from the listener callback.
                    // Throwing from onHeaders violates the gRPC ClientCall.Listener contract
                    // and can cause undefined transport behavior.
                    val msg = "Headers missing or empty AGENT_ID key"
                    logger.error { msg }
                    delegate.cancel(msg, StatusRuntimeException(Status.INTERNAL.withDescription(msg)))
                    return
                  }
                }
              }

              super.onHeaders(headers)
            }
          },
          metadata,
        )
      }
    }
  }

  companion object {
    private val logger = logger {}
  }
}
