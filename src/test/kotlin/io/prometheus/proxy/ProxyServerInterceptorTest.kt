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

import io.grpc.Attributes
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.common.GrpcConstants.META_AGENT_ID_KEY
import io.prometheus.proxy.ProxyServerTransportFilter.Companion.AGENT_ID_KEY

class ProxyServerInterceptorTest : StringSpec() {
  init {
    // ==================== META_AGENT_ID_KEY Tests ====================

    "META_AGENT_ID_KEY should use agent-id name" {
      META_AGENT_ID_KEY.name() shouldBe "agent-id"
    }

    "META_AGENT_ID_KEY should use ASCII marshaller" {
      // Verify we can put and get a value using the key
      val metadata = Metadata()
      metadata.put(META_AGENT_ID_KEY, "test-id-123")

      metadata.get(META_AGENT_ID_KEY) shouldBe "test-id-123"
    }

    // ==================== Interceptor Behavior Tests ====================

    "interceptCall should delegate to handler" {
      val interceptor = ProxyServerInterceptor()

      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
      val requestHeaders = Metadata()
      // Real (empty) attributes: a relaxed Attributes mock returns a bare Object from get(), which
      // no real ServerCall ever does — interceptCall reads AGENT_ID_KEY up front to bind the
      // connection agentId onto the Context.
      every { mockCall.attributes } returns Attributes.EMPTY

      interceptor.interceptCall(mockCall, requestHeaders, mockHandler)

      verify { mockHandler.startCall(any(), eq(requestHeaders)) }
    }

    // With no transport-assigned agentId (transportFilterDisabled, or an in-process server with no
    // transport filter), the Context key stays unset so ProxyServiceImpl skips the binding check.
    "interceptCall should leave the connection agentId unset when the attribute is missing" {
      val interceptor = ProxyServerInterceptor()

      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
      every { mockCall.attributes } returns Attributes.EMPTY
      every { mockHandler.startCall(any(), any()) } answers {
        ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get().shouldBeNull()
        mockk(relaxed = true)
      }

      interceptor.interceptCall(mockCall, Metadata(), mockHandler)

      verify { mockHandler.startCall(any(), any()) }
    }

    "interceptCall should bind the transport-assigned agentId onto the Context" {
      val interceptor = ProxyServerInterceptor()

      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
      every { mockCall.attributes } returns Attributes.newBuilder().set(AGENT_ID_KEY, "agent-42").build()
      every { mockHandler.startCall(any(), any()) } answers {
        // Contexts.interceptCall attaches the Context for the duration of the downstream call.
        ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get() shouldBe "agent-42"
        mockk(relaxed = true)
      }

      interceptor.interceptCall(mockCall, Metadata(), mockHandler)

      verify { mockHandler.startCall(any(), any()) }
    }

    "sendHeaders should inject agent-id from call attributes" {
      val interceptor = ProxyServerInterceptor()

      // Set up attributes with AGENT_ID_KEY
      val attributes = Attributes.newBuilder()
        .set(AGENT_ID_KEY, "agent-42")
        .build()

      val headerSlot = slot<Metadata>()
      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      every { mockCall.attributes } returns attributes

      val mockListener = mockk<ServerCall.Listener<Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>()
      every { mockHandler.startCall(any(), any()) } answers {
        val wrappedCall = firstArg<ServerCall<Any, Any>>()
        // Simulate sending headers through the wrapped call
        val responseHeaders = Metadata()
        wrappedCall.sendHeaders(responseHeaders)
        mockListener
      }

      interceptor.interceptCall(mockCall, Metadata(), mockHandler)

      // The original call's sendHeaders should have been called with the agent-id injected
      verify { mockCall.sendHeaders(capture(headerSlot)) }
      headerSlot.captured.get(META_AGENT_ID_KEY) shouldBe "agent-42"
    }

    "sendHeaders should not inject agent-id when attribute is missing" {
      val interceptor = ProxyServerInterceptor()

      // Empty attributes — no AGENT_ID_KEY
      val attributes = Attributes.newBuilder().build()

      val headerSlot = slot<Metadata>()
      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      every { mockCall.attributes } returns attributes

      val mockListener = mockk<ServerCall.Listener<Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>()
      every { mockHandler.startCall(any(), any()) } answers {
        val wrappedCall = firstArg<ServerCall<Any, Any>>()
        val responseHeaders = Metadata()
        wrappedCall.sendHeaders(responseHeaders)
        mockListener
      }

      interceptor.interceptCall(mockCall, Metadata(), mockHandler)

      verify { mockCall.sendHeaders(capture(headerSlot)) }
      // Should not have the agent-id key
      headerSlot.captured.get(META_AGENT_ID_KEY) shouldBe null
    }
  }
}
