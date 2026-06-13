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

package io.prometheus.agent

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.prometheus.common.GrpcConstants.META_AGENT_TOKEN_KEY

/**
 * Attaches the configured pre-shared token to every outgoing gRPC call to the Proxy.
 *
 * Installed only when `agent.agentToken` is non-empty. The Proxy's [io.prometheus.proxy.AgentTokenServerInterceptor]
 * validates this header and rejects calls that omit or mismatch it.
 *
 * @param token the non-empty token to present to the Proxy
 */
internal class AgentTokenClientInterceptor(
  private val token: String,
) : ClientInterceptor {
  override fun <ReqT, RespT> interceptCall(
    method: MethodDescriptor<ReqT, RespT>,
    callOptions: CallOptions,
    next: Channel,
  ): ClientCall<ReqT, RespT> =
    object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
      override fun start(
        responseListener: Listener<RespT>,
        metadata: Metadata,
      ) {
        metadata.put(META_AGENT_TOKEN_KEY, token)
        super.start(responseListener, metadata)
      }
    }
}
