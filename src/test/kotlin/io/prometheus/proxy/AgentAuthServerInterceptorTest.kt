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

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.common.GrpcConstants.META_AGENT_TOKEN_KEY
import io.prometheus.proxy.AgentAuthManager.AuthEntry

class AgentAuthServerInterceptorTest : StringSpec() {
  private val authManager =
    AgentAuthManager.create(
      authEntries = [AuthEntry("team_a", "s3cret", ["team_a_*"])],
      legacyToken = "",
    )

  private fun headersWithToken(token: String?): Metadata =
    Metadata().apply { token?.also { put(META_AGENT_TOKEN_KEY, it) } }

  init {
    "valid token should delegate to handler and expose the resolved identity in the context" {
      val interceptor = AgentAuthServerInterceptor(authManager)
      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val headers = headersWithToken("s3cret")

      // A real handler that captures the identity attached to the gRPC context at startCall time.
      var startCalled = false
      var capturedIdentity: AgentIdentity? = null
      val handler =
        ServerCallHandler<Any, Any> { _, _ ->
          startCalled = true
          capturedIdentity = AgentAuthManager.AGENT_IDENTITY_KEY.get()
          mockk(relaxed = true)
        }

      interceptor.interceptCall(mockCall, headers, handler)

      startCalled shouldBe true
      capturedIdentity?.name shouldBe "team_a"
      verify(exactly = 0) { mockCall.close(any(), any()) }
    }

    "missing token should close with UNAUTHENTICATED and not start the handler" {
      val interceptor = AgentAuthServerInterceptor(authManager)
      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
      val statusSlot = slot<Status>()

      val listener = interceptor.interceptCall(mockCall, headersWithToken(null), mockHandler)

      verify { mockCall.close(capture(statusSlot), any()) }
      statusSlot.captured.code shouldBe Status.Code.UNAUTHENTICATED
      verify(exactly = 0) { mockHandler.startCall(any(), any()) }
      // A no-op listener is returned so no messages are delivered to the (already closed) call.
      listener.shouldNotBeNull()
    }

    "unknown token should close with UNAUTHENTICATED and not start the handler" {
      val interceptor = AgentAuthServerInterceptor(authManager)
      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
      val statusSlot = slot<Status>()

      interceptor.interceptCall(mockCall, headersWithToken("nope"), mockHandler)

      verify { mockCall.close(capture(statusSlot), any()) }
      statusSlot.captured.code shouldBe Status.Code.UNAUTHENTICATED
      verify(exactly = 0) { mockHandler.startCall(any(), any()) }
    }

    "token of the same length but different value should be rejected (constant-time path)" {
      val interceptor =
        AgentAuthServerInterceptor(
          AgentAuthManager.create([AuthEntry("team_a", "abcdef", ["*"])], ""),
        )
      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)

      interceptor.interceptCall(mockCall, headersWithToken("abcxyz"), mockHandler)

      verify { mockCall.close(any(), any()) }
      verify(exactly = 0) { mockHandler.startCall(any(), any()) }
    }
  }
}
