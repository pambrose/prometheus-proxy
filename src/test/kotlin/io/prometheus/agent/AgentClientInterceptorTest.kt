@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
}
