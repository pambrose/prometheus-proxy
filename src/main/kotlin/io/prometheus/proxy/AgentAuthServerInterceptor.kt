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

package io.prometheus.proxy

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.prometheus.common.GrpcConstants.META_AGENT_TOKEN_KEY
import io.prometheus.proxy.AgentAuthManager.Companion.AGENT_IDENTITY_KEY

/**
 * Authenticates agent RPCs against the configured identities and attaches the resolved identity.
 *
 * Installed only when [AgentAuthManager.isEnabled]. Every RPC must carry a token in the
 * [META_AGENT_TOKEN_KEY] metadata header; a missing or unrecognized token is closed with
 * [Status.UNAUTHENTICATED] before the handler runs. On success the resolved [AgentIdentity] is
 * stored in the gRPC [Context] under [AGENT_IDENTITY_KEY] so [ProxyServiceImpl.registerPath] can
 * enforce per-agent path authorization. When no identities are configured this interceptor is not
 * installed and the agent port preserves its historical open behavior.
 *
 * @param authManager resolves presented tokens to identities
 * @see io.prometheus.agent.AgentTokenClientInterceptor
 */
internal class AgentAuthServerInterceptor(
  private val authManager: AgentAuthManager,
) : ServerInterceptor {
  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    requestHeaders: Metadata,
    handler: ServerCallHandler<ReqT, RespT>,
  ): ServerCall.Listener<ReqT> {
    val provided = requestHeaders.get(META_AGENT_TOKEN_KEY)
    val identity = if (provided == null) null else authManager.resolveToken(provided)
    if (identity == null) {
      call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid agent token"), Metadata())
      // Return a no-op listener: the call is already closed, so no messages must be delivered.
      return object : ServerCall.Listener<ReqT>() {}
    }
    val context = Context.current().withValue(AGENT_IDENTITY_KEY, identity)
    return Contexts.interceptCall(context, call, requestHeaders, handler)
  }
}
