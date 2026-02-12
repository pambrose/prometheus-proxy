@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.grpc.Attributes
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.common.GrpcConstants.META_AGENT_ID_KEY
import io.prometheus.proxy.ProxyServerTransportFilter.Companion.AGENT_ID_KEY

class ProxyServerInterceptorTest : FunSpec() {
  init {
    // ==================== META_AGENT_ID_KEY Tests ====================

    test("META_AGENT_ID_KEY should use agent-id name") {
      META_AGENT_ID_KEY.name() shouldBe "agent-id"
    }

    test("META_AGENT_ID_KEY should use ASCII marshaller") {
      // Verify we can put and get a value using the key
      val metadata = Metadata()
      metadata.put(META_AGENT_ID_KEY, "test-id-123")

      metadata.get(META_AGENT_ID_KEY) shouldBe "test-id-123"
    }

    // ==================== Interceptor Behavior Tests ====================

    test("interceptCall should delegate to handler") {
      val interceptor = ProxyServerInterceptor()

      val mockCall = mockk<ServerCall<Any, Any>>(relaxed = true)
      val mockHandler = mockk<ServerCallHandler<Any, Any>>(relaxed = true)
      val requestHeaders = Metadata()

      interceptor.interceptCall(mockCall, requestHeaders, mockHandler)

      verify { mockHandler.startCall(any(), eq(requestHeaders)) }
    }

    test("sendHeaders should inject agent-id from call attributes") {
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

    test("sendHeaders should not inject agent-id when attribute is missing") {
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
