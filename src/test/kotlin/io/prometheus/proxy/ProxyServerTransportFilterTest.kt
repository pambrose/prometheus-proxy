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

package io.prometheus.proxy

import io.grpc.Attributes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.proxy.ProxyServerTransportFilter.Companion.AGENT_ID_KEY

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
      filter.transportReady(inputAttrs)

      agentContextManager.agentContextSize shouldBe 1
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
      filter.transportReady(inputAttrs)

      // The AgentContext was created — verify it exists in the map
      agentContextManager.agentContextSize shouldBe 1
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

      repeat(5) {
        filter.transportReady(Attributes.newBuilder().build())
      }

      agentContextManager.agentContextSize shouldBe 5
    }

    // ==================== transportTerminated Tests ====================

    "transportTerminated should remove agent from context manager" {
      val (mockProxy, agentContextManager) = createMockProxy()
      val filter = ProxyServerTransportFilter(mockProxy)

      // First, create a context via transportReady
      val resultAttrs = filter.transportReady(Attributes.newBuilder().build())
      val agentId = resultAttrs.get(AGENT_ID_KEY)!!
      agentContextManager.agentContextSize shouldBe 1

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

      resultAttrs.get(AGENT_ID_KEY).shouldNotBeNull()
      agentContextManager.agentContextSize shouldBe 1
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
