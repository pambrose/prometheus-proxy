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

import ch.qos.logback.classic.Level
import io.grpc.Attributes
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.common.GrpcConstants.META_AGENT_TOKEN_KEY
import io.prometheus.common.KeyedThrottle
import io.prometheus.common.captureLogs
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource
import io.prometheus.proxy.AgentAuthManager.AuthEntry

class AgentAuthServerInterceptorTest : StringSpec() {
  private val authManager =
    AgentAuthManager.create(
      authEntries = [AuthEntry("team_a", "s3cret", ["team_a_*"])],
      legacyToken = "",
    )

  private fun headersWithToken(token: String?): Metadata =
    Metadata().apply { token?.also { put(META_AGENT_TOKEN_KEY, it) } }

  // A refused call reads its transport's attributes for the host to warn of, which a relaxed mock answers with a mock
  private fun refusedCall() =
    mockk<ServerCall<Any, Any>>(relaxed = true).also { every { it.attributes } returns Attributes.EMPTY }

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
      val mockCall = refusedCall()
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
      val mockCall = refusedCall()
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
      val mockCall = refusedCall()
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)

      interceptor.interceptCall(mockCall, headersWithToken("abcxyz"), mockHandler)

      verify { mockCall.close(any(), any()) }
      verify(exactly = 0) { mockHandler.startCall(any(), any()) }
    }

    // The proxy counts each in proxy_agent_auth_failures_total, by whether a token was sent; the warning is throttled
    // by host, but the count isn't, and a call with the right token isn't one.
    "every refused call should be reported, saying whether it sent a token, and an accepted one should not" {
      val refused = mutableListOf<Boolean>()
      val interceptor = AgentAuthServerInterceptor(authManager) { missing -> refused += missing }

      repeat(2) { interceptor.interceptCall(refusedCall(), headersWithToken("nope"), mockk(relaxed = true)) }
      interceptor.interceptCall(refusedCall(), headersWithToken(null), mockk(relaxed = true))
      interceptor.interceptCall(refusedCall(), headersWithToken("s3cret"), mockk(relaxed = true))

      refused shouldBe listOf(false, false, true)
    }

    // A wrong token is the usual reason a new agent never appears, and nothing else on the dashboard shows it.
    "a bad token should go in the activity feed, naming the host it came from, a host's repeats counted on its line" {
      val log = ActivityLog(100)
      val interceptor = AgentAuthServerInterceptor(authManager, log)

      fun callFrom(host: String) =
        mockk<ServerCall<Any, Any>>(relaxed = true).also {
          every { it.attributes } returns
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, InetSocketAddress(host, 40000)).build()
        }

      repeat(3) { interceptor.interceptCall(callFrom("10.9.9.9"), headersWithToken("nope"), mockk(relaxed = true)) }
      interceptor.interceptCall(callFrom("10.8.8.8"), headersWithToken(null), mockk(relaxed = true))
      interceptor.interceptCall(callFrom("10.7.7.7"), headersWithToken("s3cret"), mockk(relaxed = true))

      log.entries().map { Triple(it.kind, it.detail, it.count) } shouldBe
        [
          Triple(ActivityKind.BAD_TOKEN, "No agent token, from 10.8.8.8", 1),
          Triple(ActivityKind.BAD_TOKEN, "An unknown agent token, from 10.9.9.9", 3),
        ]
    }

    // Refused without a word in the proxy's log, a wrong token showed only in the agent's, once, as it first failed to
    // connect, while it went on reconnecting every few seconds; and the dashboard, with its activity feed, may be off.
    "a bad token should be logged at WARN, naming the host, at most once a minute for each host" {
      val time = TestTimeSource()
      val interceptor =
        AgentAuthServerInterceptor(authManager, badTokenWarnings = KeyedThrottle(1.minutes, 1_000, time))

      fun callFrom(host: String) =
        mockk<ServerCall<Any, Any>>(relaxed = true).also {
          every { it.attributes } returns
            Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, InetSocketAddress(host, 40000)).build()
        }

      val events =
        captureLogs("io.prometheus.proxy", Level.DEBUG) {
          repeat(3) {
            interceptor.interceptCall(callFrom("10.9.9.9"), headersWithToken("nope"), mockk(relaxed = true))
          }
          interceptor.interceptCall(callFrom("10.8.8.8"), headersWithToken(null), mockk(relaxed = true))
          time += 1.minutes
          interceptor.interceptCall(callFrom("10.9.9.9"), headersWithToken("nope"), mockk(relaxed = true))
        }

      events.filter { it.level == Level.WARN }.map { it.formattedMessage } shouldBe
        [
          "Refused an agent from 10.9.9.9 (UNAUTHENTICATED): it sent an unknown agent token",
          "Refused an agent from 10.8.8.8 (UNAUTHENTICATED): it sent no agent token",
          "Refused an agent from 10.9.9.9 (UNAUTHENTICATED): it sent an unknown agent token",
        ]
    }

    "a bad token with no remote address should still go in the activity feed" {
      val log = ActivityLog(100)

      val call = mockk<ServerCall<Any, Any>>(relaxed = true).also { every { it.attributes } returns Attributes.EMPTY }

      AgentAuthServerInterceptor(authManager, log).interceptCall(call, headersWithToken("nope"), mockk(relaxed = true))

      log.entries().single().detail shouldBe "An unknown agent token, from Unknown"
    }
  }
}
