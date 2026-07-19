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

package io.prometheus.proxy

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ForwardingServerCall
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.prometheus.common.GrpcConstants.AGENT_ID
import io.prometheus.common.GrpcConstants.META_AGENT_ID_KEY
import io.prometheus.proxy.ProxyServerTransportFilter.Companion.AGENT_ID_KEY

internal class ProxyServerInterceptor : ServerInterceptor {
  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    requestHeaders: Metadata,
    handler: ServerCallHandler<ReqT, RespT>,
  ): ServerCall.Listener<ReqT> {
    val forwardingCall =
      object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
        override fun sendHeaders(headers: Metadata) {
          // ATTRIB_AGENT_ID was assigned in ServerTransportFilter
          call.attributes.get(AGENT_ID_KEY)?.also { headers.put(META_AGENT_ID_KEY, it) }
          super.sendHeaders(headers)
        }
      }

    // Publish the transport-assigned agentId on the gRPC Context so ProxyServiceImpl can compare it
    // against the caller-supplied request.agentId. Absent when transportFilterDisabled is set (no
    // transport filter ran), in which case the call proceeds with the key unset.
    val connectionAgentId =
      call.attributes.get(AGENT_ID_KEY)
        ?: return handler.startCall(forwardingCall, requestHeaders)

    val context = Context.current().withValue(CONNECTION_AGENT_ID_KEY, connectionAgentId)
    return Contexts.interceptCall(context, forwardingCall, requestHeaders, handler)
  }

  companion object {
    /**
     * The agentId the transport assigned to *this connection*, as opposed to the caller-supplied
     * `request.agentId` on the wire. [ProxyServiceImpl] compares the two so one authenticated agent
     * cannot act on another agent's [AgentContext]. Null when no transport filter ran (in-process
     * tests, or `transportFilterDisabled`), in which case the comparison is skipped.
     */
    internal val CONNECTION_AGENT_ID_KEY: Context.Key<String> = Context.key(AGENT_ID)
  }
}
