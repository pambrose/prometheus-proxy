@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Agent
import org.junit.jupiter.api.Test

class AgentClientInterceptorTest {
  private fun createMockAgent(agentId: String = ""): Agent {
    val mockChannel = mockk<ManagedChannel>(relaxed = true)

    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)
    every { mockGrpcService.channel } returns mockChannel

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.agentId } returns agentId
    every { mockAgent.grpcService } returns mockGrpcService

    return mockAgent
  }

  // ==================== Interceptor Call Tests ====================

  @Test
  fun `interceptCall should return a non-null client call`() {
    val mockAgent = createMockAgent()
    val interceptor = AgentClientInterceptor(mockAgent)

    val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
    val mockChannel = mockk<Channel>(relaxed = true)

    val result = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

    result.shouldNotBeNull()
  }

  @Test
  fun `interceptCall should access grpcService channel`() {
    val mockAgent = createMockAgent()
    val interceptor = AgentClientInterceptor(mockAgent)

    val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
    val mockChannel = mockk<Channel>(relaxed = true)

    interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

    // The interceptor should access the agent's grpc service channel
    verify { mockAgent.grpcService }
  }

  @Test
  fun `interceptCall should not modify agent id when already set`() {
    val mockAgent = createMockAgent(agentId = "existing-id")
    val interceptor = AgentClientInterceptor(mockAgent)

    val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
    val mockChannel = mockk<Channel>(relaxed = true)

    interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockChannel)

    // When agentId is already set, the interceptor should not overwrite it during call setup
    verify(exactly = 0) { mockAgent.agentId = any() }
  }

  @Test
  fun `interceptCall with empty agentId should create forwarding call`() {
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

  @Test
  fun `interceptCall with empty agentId should use agent grpcService channel`() {
    val mockChannel = mockk<ManagedChannel>(relaxed = true)
    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)
    every { mockGrpcService.channel } returns mockChannel

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.agentId } returns ""
    every { mockAgent.grpcService } returns mockGrpcService

    val interceptor = AgentClientInterceptor(mockAgent)
    val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
    val mockNextChannel = mockk<Channel>(relaxed = true)

    val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)

    call.shouldNotBeNull()
    // The interceptor should use agent.grpcService.channel, not the 'next' channel parameter
    val grpcService = mockAgent.grpcService
    verify { grpcService.channel }
  }

  @Test
  fun `onHeaders should not overwrite agentId when already set`() {
    val mockChannel = mockk<ManagedChannel>(relaxed = true)
    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)
    every { mockGrpcService.channel } returns mockChannel

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.agentId } returns "existing-id"
    every { mockAgent.grpcService } returns mockGrpcService

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

  @Test
  fun `onHeaders should handle missing agent ID key in headers`() {
    val mockChannel = mockk<ManagedChannel>(relaxed = true)
    val mockGrpcService = mockk<AgentGrpcService>(relaxed = true)
    every { mockGrpcService.channel } returns mockChannel

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.agentId } returns ""
    every { mockAgent.grpcService } returns mockGrpcService

    val interceptor = AgentClientInterceptor(mockAgent)
    val mockMethod = mockk<MethodDescriptor<Any, Any>>(relaxed = true)
    val mockNextChannel = mockk<Channel>(relaxed = true)

    val call = interceptor.interceptCall(mockMethod, CallOptions.DEFAULT, mockNextChannel)

    // Start the call with empty headers (no agent ID key)
    call.start(mockk(relaxed = true), Metadata())

    // With empty headers and empty agentId, no assignment should occur
    // because headers.get(META_AGENT_ID_KEY) returns null
    verify(exactly = 0) { mockAgent.agentId = any() }
  }
}
