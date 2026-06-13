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

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.prometheus.common.GrpcConstants.META_AGENT_TOKEN_KEY
import java.security.MessageDigest

/**
 * Rejects agent RPCs that do not present the configured pre-shared token.
 *
 * Installed only when `proxy.agentToken` is non-empty. Every RPC must carry the token in the
 * [META_AGENT_TOKEN_KEY] metadata header; a missing or mismatched token is closed with
 * [Status.UNAUTHENTICATED] before the handler runs. When no token is configured this interceptor
 * is not installed and the agent port preserves its historical open behavior.
 *
 * @param expectedToken the non-empty token agents must present
 * @see io.prometheus.agent.AgentTokenClientInterceptor
 */
internal class AgentTokenServerInterceptor(
  private val expectedToken: String,
) : ServerInterceptor {
  // Compared as raw header bytes so the constant-time check never depends on the secret's encoding.
  private val expectedTokenBytes: ByteArray = expectedToken.toByteArray()

  override fun <ReqT, RespT> interceptCall(
    call: ServerCall<ReqT, RespT>,
    requestHeaders: Metadata,
    handler: ServerCallHandler<ReqT, RespT>,
  ): ServerCall.Listener<ReqT> {
    val provided = requestHeaders.get(META_AGENT_TOKEN_KEY)
    if (provided == null || !constantTimeEquals(provided)) {
      call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid agent token"), Metadata())
      // Return a no-op listener: the call is already closed, so no messages must be delivered.
      return object : ServerCall.Listener<ReqT>() {}
    }
    return handler.startCall(call, requestHeaders)
  }

  // Constant-time comparison avoids leaking the token length/content via response timing.
  // MessageDigest.isEqual is constant-time for equal-length inputs and short-circuits only on length.
  private fun constantTimeEquals(provided: String): Boolean =
    MessageDigest.isEqual(provided.toByteArray(), expectedTokenBytes)
}
