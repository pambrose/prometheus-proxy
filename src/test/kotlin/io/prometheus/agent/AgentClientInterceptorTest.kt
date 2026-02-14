/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.common.GrpcConstants

class AgentClientInterceptorTest : StringSpec() {
  private fun createMockAgent(agentId: String = ""): Agent {
    val mockChannel = mockk<ManagedChannel>(relaxed = true)

    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)
    every { mockGrpcService.channel } returns mockChannel

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.agentId } returns agentId
    every { mockAgent.grpcService } returns mockGrpcService

    return mockAgent
  }

  init {
    // ==================== Interceptor Call Tests ====================

    "interceptCall should return a non-null client call" {
      val mockAgent = createMockAgent()
      val interceptor = AgentClientInterceptor(mockAgent)

      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockChannel = mockk<Channel>(relaxed = true)

      val result = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

      result.shouldNotBeNull()
    }

    "interceptCall should use next channel parameter" {
      val mockAgent = createMockAgent()
      val interceptor = AgentClientInterceptor(mockAgent)

      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockChannel = mockk<Channel>(relaxed = true)

      interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

      // The interceptor should delegate to the next channel, not agent.grpcService.channel
      verify { mockChannel.newCall(mockMethod, CallOptions.DEFAULT) }
      verify(exactly = 0) { mockAgent.grpcService }
    }

    "interceptCall should not modify agent id when already set" {
      val mockAgent = createMockAgent(agentId = "existing-id")
      val interceptor = AgentClientInterceptor(mockAgent)

      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockChannel = mockk<Channel>(relaxed = true)

      interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

      // When agentId is already set, the interceptor should not overwrite it during call setup
      verify(exactly = 0) { mockAgent.agentId = any() }
    }

    "interceptCall with empty agentId should create forwarding call" {
      val mockAgent = createMockAgent(agentId = "")
      val interceptor = AgentClientInterceptor(mockAgent)

      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockChannel = mockk<Channel>(relaxed = true)

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

      // Interceptor should create a forwarding call successfully even with empty agentId
      // The agentId extraction happens in onHeaders callback, not during interceptCall
      call.shouldNotBeNull()
    }

    // ==================== onHeaders Tests ====================

    "interceptCall with empty agentId should use next channel parameter" {
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.agentId } returns ""

      val interceptor = AgentClientInterceptor(mockAgent)
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockNextChannel = mockk<Channel>(relaxed = true)

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)

      call.shouldNotBeNull()
      // The interceptor should delegate to the next channel in the chain
      verify { mockNextChannel.newCall(mockMethod, CallOptions.DEFAULT) }
      verify(exactly = 0) { mockAgent.grpcService }
    }

    "onHeaders should not overwrite agentId when already set" {
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.agentId } returns "existing-id"

      val interceptor = AgentClientInterceptor(mockAgent)
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
      val mockNextChannel = mockk<Channel>(relaxed = true)

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)

      // Start the call
      call.start(mockk(relaxed = true), Metadata())

      // Since agentId is already set ("existing-id"), the onHeaders callback
      // should not attempt to assign a new agentId
      verify(exactly = 0) { mockAgent.agentId = any() }
    }

    "onHeaders should assign agentId when header is present" {
      // Use a backing variable so the mock tracks agentId state across get/set
      var currentAgentId = ""
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.agentId } answers { currentAgentId }
      every { mockAgent.agentId = any() } answers { currentAgentId = firstArg() }

      val interceptor = AgentClientInterceptor(mockAgent)
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)

      // Capture the wrapped listener when start is called on the underlying call
      val listenerSlot = slot<ClientCall.Listener<Any>>()
      val mockUnderlyingCall = mockk<ClientCall<Any, Any>>(relaxed = true)
      every { mockUnderlyingCall.start(capture(listenerSlot), any()) } answers {}

      val mockNextChannel = mockk<Channel>(relaxed = true)
      every { mockNextChannel.newCall(any<MethodDescriptor<Any, Any>>(), any()) } returns mockUnderlyingCall

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)
      call.start(mockk(relaxed = true), Metadata())

      // Trigger onHeaders with a valid AGENT_ID header
      val headers = Metadata()
      headers.put(GrpcConstants.META_AGENT_ID_KEY, "test-agent-42")
      listenerSlot.captured.onHeaders(headers)

      // The agentId should have been assigned
      currentAgentId shouldBe "test-agent-42"
      verify { mockAgent.agentId = "test-agent-42" }
    }

    "onHeaders should throw StatusRuntimeException when agent ID key is missing from headers" {
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.agentId } returns ""

      val interceptor = AgentClientInterceptor(mockAgent)
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)

      // Capture the wrapped listener when start is called on the underlying call
      val listenerSlot = slot<ClientCall.Listener<Any>>()
      val mockUnderlyingCall = mockk<ClientCall<Any, Any>>(relaxed = true)
      every { mockUnderlyingCall.start(capture(listenerSlot), any()) } answers {}

      val mockNextChannel = mockk<Channel>(relaxed = true)
      every { mockNextChannel.newCall(any<MethodDescriptor<Any, Any>>(), any()) } returns mockUnderlyingCall

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)
      call.start(mockk(relaxed = true), Metadata())

      // Trigger onHeaders with empty headers (no AGENT_ID key) — should throw StatusRuntimeException
      shouldThrow<StatusRuntimeException> {
        listenerSlot.captured.onHeaders(Metadata())
      }

      verify(exactly = 0) { mockAgent.agentId = any() }
    }

    "onHeaders missing agent ID should throw INTERNAL status with descriptive message" {
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.agentId } returns ""

      val interceptor = AgentClientInterceptor(mockAgent)
      val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)

      val listenerSlot = slot<ClientCall.Listener<Any>>()
      val mockUnderlyingCall = mockk<ClientCall<Any, Any>>(relaxed = true)
      every { mockUnderlyingCall.start(capture(listenerSlot), any()) } answers {}

      val mockNextChannel = mockk<Channel>(relaxed = true)
      every { mockNextChannel.newCall(any<MethodDescriptor<Any, Any>>(), any()) } returns mockUnderlyingCall

      val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)
      call.start(mockk(relaxed = true), Metadata())

      val exception = shouldThrow<StatusRuntimeException> {
        listenerSlot.captured.onHeaders(Metadata())
      }

      exception.status.code shouldBe Status.Code.INTERNAL
      exception.status.description.shouldContain("AGENT_ID")
    }
  }
}
