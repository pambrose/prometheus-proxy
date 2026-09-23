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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyServerTransportFilter.Companion.AGENT_ID_KEY
import io.prometheus.proxy.ProxyServiceImpl.Companion.UNKNOWN_ADDRESS

class ProxyServerTransportFilterTest : StringSpec() {
  private fun createMockProxy(): Pair<Proxy, AgentContextManager> {
    val agentContextManager = AgentContextManager(isTestMode = true)
    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.agentContextManager } returns agentContextManager
    return mockProxy to agentContextManager
  }

  init {
    // ==================== transportReady Tests ====================

    "transportReady should create agent context and add to manager" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      val inputAttrs = Attributes.newBuilder().build()
      val agentId = filter.transportReady(inputAttrs).get(AGENT_ID_KEY)

      agentContextManager.getAgentContext(agentId.shouldNotBeNull()).shouldNotBeNull()
    }

    // No call on this connection has been authenticated yet, so it isn't announced as a connected agent: that waits
    // for connectAgent.
    "transportReady should not count or announce the context before the agent connects" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      val agentId = filter.transportReady(Attributes.newBuilder().build()).get(AGENT_ID_KEY).shouldNotBeNull()

      agentContextManager.agentContextSize shouldBe 0
      agentContextManager.getAgentContext(agentId).shouldNotBeNull().announced shouldBe false
    }

    "transportReady should add AGENT_ID_KEY to returned attributes" {
      val (mockProxy, _) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      val inputAttrs = Attributes.newBuilder().build()
      val resultAttrs = filter.transportReady(inputAttrs)

      resultAttrs.get(AGENT_ID_KEY).shouldNotBeNull()
    }

    "transportReady should use UNKNOWN_ADDRESS when remote addr is missing" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      val inputAttrs = Attributes.newBuilder().build()
      val agentId = filter.transportReady(inputAttrs).get(AGENT_ID_KEY).shouldNotBeNull()

      // The AgentContext was created with the placeholder address
      agentContextManager.getAgentContext(agentId).shouldNotBeNull().remoteAddr shouldBe UNKNOWN_ADDRESS
    }

    "transportReady should preserve original attributes" {
      val (mockProxy, _) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      val customKey = Attributes.Key.create<String>("custom-key")
      val inputAttrs = Attributes.newBuilder()
        .set(customKey, "custom-value")
        .build()

      val resultAttrs = filter.transportReady(inputAttrs)

      resultAttrs.get(customKey) shouldBe "custom-value"
      resultAttrs.get(AGENT_ID_KEY).shouldNotBeNull()
    }

    "transportReady should handle multiple connections" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      val agentIds =
        List(5) { filter.transportReady(Attributes.newBuilder().build()).get(AGENT_ID_KEY).shouldNotBeNull() }

      agentIds.toSet().size shouldBe 5
      agentIds.forEach { agentContextManager.getAgentContext(it).shouldNotBeNull() }
    }

    // ==================== transportTerminated Tests ====================

    "transportTerminated should remove agent from context manager" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      // First, create a context via transportReady
      val resultAttrs = filter.transportReady(Attributes.newBuilder().build())
      val agentId = resultAttrs.get(AGENT_ID_KEY)!!
      agentContextManager.getAgentContext(agentId).shouldNotBeNull()

      // Set up proxy.removeAgentContext to delegate to the manager
      every { mockProxy.removeAgentContext(any(), any()) } answers {
        agentContextManager.removeFromContextManager(firstArg(), secondArg())
      }

      // Terminate transport
      filter.transportTerminated(resultAttrs)

      verify { mockProxy.removeAgentContext(agentId, "Termination") }
    }

    "transportTerminated should handle missing agent-id gracefully" {
      val (mockProxy, _) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      // Attributes without AGENT_ID_KEY — should not throw
      val emptyAttrs = Attributes.newBuilder().build()
      filter.transportTerminated(emptyAttrs)
    }

    // ==================== Remote Address Tests ====================

    "transportReady should use remote addr from REMOTE_ADDR_KEY when available" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      // Create attributes with a remote address
      val remoteAddrKey = Attributes.Key.create<java.net.SocketAddress>("remote-addr")
      val socketAddr = java.net.InetSocketAddress("192.168.1.100", 50000)
      val inputAttrs = Attributes.newBuilder()
        .set(remoteAddrKey, socketAddr)
        .build()

      val resultAttrs = filter.transportReady(inputAttrs)

      val agentId = resultAttrs.get(AGENT_ID_KEY).shouldNotBeNull()
      agentContextManager.getAgentContext(agentId).shouldNotBeNull()
    }

    // Bug #9: When the cleanup service already removed the agent, transport
    // termination should handle the null return gracefully without errors.
    "Bug #9: transportTerminated should handle already-removed agent gracefully" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      // Create a context via transportReady
      val resultAttrs = filter.transportReady(Attributes.newBuilder().build())
      val agentId = resultAttrs.get(AGENT_ID_KEY)!!
      agentContextManager.getAgentContext(agentId).shouldNotBeNull()

      // Simulate cleanup service removing the agent first
      agentContextManager.removeFromContextManager(agentId, "Eviction")
      agentContextManager.getAgentContext(agentId).shouldBeNull()

      // removeAgentContext returns null for already-removed agents
      every { mockProxy.removeAgentContext(any(), any()) } returns null

      // transportTerminated should not throw
      filter.transportTerminated(resultAttrs)

      // Verify removeAgentContext was still called
      verify { mockProxy.removeAgentContext(agentId, "Termination") }
    }

    // ==================== Transport Filter Lifecycle ====================

    "transportTerminated should call removeAgentContext with correct reason" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      val resultAttrs = filter.transportReady(Attributes.newBuilder().build())
      val agentId = resultAttrs.get(AGENT_ID_KEY)!!

      every { mockProxy.removeAgentContext(any(), any()) } answers {
        agentContextManager.removeFromContextManager(firstArg(), secondArg())
      }

      filter.transportTerminated(resultAttrs)

      verify { mockProxy.removeAgentContext(agentId, "Termination") }
      agentContextManager.agentContextSize shouldBe 0
    }
  }
}
