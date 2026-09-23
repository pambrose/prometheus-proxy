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

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.agentOptions
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.scrapeRequest
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.flowOf

// AgentGrpcService.readRequestsFromProxy counts each scrape request it forwards in the agent's backlog, and must give
// the slot back when forwarding fails -- otherwise every request that arrives as the connection closes leaves the
// backlog permanently higher, and the agent eventually reports itself unhealthy. These drive the real method on a real
// Agent, so they count with the real scrapeRequestBacklogSize and decrementBacklog; only the gRPC stub is replaced.
class AgentBacklogDriftTest : StringSpec() {
  private val agents = mutableListOf<Agent>()

  // A real Agent whose proxy stream delivers [requests].
  private fun agentStreaming(vararg requests: ScrapeRequest): Agent =
    Agent(
      options = agentOptions(["--proxy", "localhost:$PROXY_AGENT_PORT"], exitOnMissingConfig = false),
      inProcessServerName = "backlog-drift-test",
      testMode = true,
    ).also { agent ->
      agents += agent
      agent.agentId = "backlog-agent"
      agent.grpcService.grpcStub =
        mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true).also { stub ->
          every { stub.readRequestsFromProxy(any(), any()) } returns flowOf(*requests)
        }
    }

  private fun request(id: Long) =
    scrapeRequest {
      agentId = "backlog-agent"
      scrapeId = id
      path = "metrics"
    }

  init {
    afterTest {
      agents.forEach { it.grpcService.shutDown() }
      agents.clear()
    }

    "readRequestsFromProxy should count each forwarded request in the backlog" {
      val agent = agentStreaming(request(1), request(2))

      agent.grpcService.readRequestsFromProxy(mockk(relaxed = true), AgentConnectionContext(2))

      agent.scrapeRequestBacklogSize.load() shouldBe 2
    }

    // The connection is closing, so the request can't be handed on: the failure propagates, ending the stream, and
    // the backlog returns to where it was.
    "readRequestsFromProxy should give back the backlog slot of a request it can't forward" {
      val agent = agentStreaming(request(1))
      val closed = AgentConnectionContext(1).also { it.close() }

      shouldThrow<ClosedSendChannelException> {
        agent.grpcService.readRequestsFromProxy(mockk(relaxed = true), closed)
      }

      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }
  }
}
