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

import com.google.protobuf.ByteString
import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.client.Counter
import io.prometheus.common.testConfigVals
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.chunkData
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import io.prometheus.grpc.heartBeatRequest
import io.prometheus.grpc.pathMapSizeRequest
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.grpc.registerPathRequest
import io.prometheus.grpc.scrapeResponse
import io.prometheus.grpc.summaryData
import io.prometheus.grpc.unregisterPathRequest
import io.prometheus.proxy.ProxyPathManager.PathRejection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import java.util.zip.CRC32

@Suppress("LargeClass")
class ProxyServiceImplTest : StringSpec() {
  // The chunk-failure counters, stubbed individually so a test can tell which counter and stage label moved.
  private class ChunkFailureCounters {
    val metrics = mockk<ProxyMetrics>(relaxed = true)
    val chunkStage = mockk<Counter.Child>(relaxed = true)
    val summaryStage = mockk<Counter.Child>(relaxed = true)
    val abandoned = mockk<Counter>(relaxed = true)

    init {
      val validationFailures = mockk<Counter>(relaxed = true)
      every { metrics.chunkValidationFailures } returns validationFailures
      every { validationFailures.labels(ProxyMetrics.STAGE_CHUNK) } returns chunkStage
      every { validationFailures.labels(ProxyMetrics.STAGE_SUMMARY) } returns summaryStage
      every { metrics.chunkedTransfersAbandoned } returns abandoned
    }
  }

  private fun createMockProxy(
    transportFilterDisabled: Boolean = false,
    isRunning: Boolean = true,
    metrics: ProxyMetrics = mockk(relaxed = true),
  ): Proxy {
    val mockOptions = mockk<ProxyOptions>(relaxed = true)
    every { mockOptions.transportFilterDisabled } returns transportFilterDisabled

    val mockAgentContextManager = mockk<AgentContextManager>(relaxed = true)
    val mockPathManager = mockk<ProxyPathManager>(relaxed = true)
    val mockScrapeRequestManager = mockk<ScrapeRequestManager>(relaxed = true)
    every { mockScrapeRequestManager.containsScrapeRequest(any()) } returns true

    val configVals = testConfigVals(
      """
      proxy {
        auth = []
        internal {
          maxZippedContentSizeMBytes = 5
          maxUnzippedContentSizeMBytes = 10
        }
      }
      agent {
        pathConfigs = []
        filters = []
      }
      """,
    )

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.options } returns mockOptions
    every { mockProxy.proxyConfigVals } returns configVals.proxy
    every { mockProxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers {
      val block = firstArg<ProxyMetrics.() -> Unit>()
      block(metrics)
    }
    every { mockProxy.agentContextManager } returns mockAgentContextManager
    every { mockProxy.pathManager } returns mockPathManager
    every { mockProxy.scrapeRequestManager } returns mockScrapeRequestManager
    every { mockProxy.isRunning } returns isRunning

    return mockProxy
  }

  init {
    "connectAgent should succeed when transportFilterDisabled matches" {
      val proxy = createMockProxy(transportFilterDisabled = false)
      val service = ProxyServiceImpl(proxy)

      val result = service.connectAgent(EMPTY_INSTANCE)

      result shouldBe EMPTY_INSTANCE
      verify { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
    }

    "connectAgent should throw StatusException with FAILED_PRECONDITION when transportFilterDisabled mismatch" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val service = ProxyServiceImpl(proxy)

      // Before the fix, this threw RequestFailureException (a plain Exception), which gRPC
      // converted to Status.UNKNOWN on the wire. The agent never saw the RequestFailureException
      // type — it received StatusRuntimeException(UNKNOWN), taking the wrong error-handling path.
      // After the fix, a StatusException with FAILED_PRECONDITION is thrown, which gRPC preserves
      // on the wire so the agent receives the correct status code and description.
      val exception = shouldThrow<StatusException> {
        service.connectAgent(EMPTY_INSTANCE)
      }

      exception.status.code shouldBe Status.Code.FAILED_PRECONDITION
      exception.status.description shouldContain "do not have matching transportFilterDisabled config values"
    }

    // Tests the transport filter security mechanism: when transportFilterDisabled=true on both
    // proxy and agent, a direct gRPC connection is established without the transport filter.
    // This creates an AgentContext that tracks the agent's state throughout its connection lifetime.
    "connectAgentWithTransportFilterDisabled should create agent context" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextSlot = slot<AgentContext>()

      every { proxy.agentContextManager.addAgentContext(capture(agentContextSlot)) } returns null

      val service = ProxyServiceImpl(proxy)
      val result = service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)

      result.agentId.isNotEmpty().shouldBeTrue()
      agentContextSlot.isCaptured.shouldBeTrue()
      agentContextSlot.captured.agentId shouldBe result.agentId

      verify { proxy.agentContextManager.addAgentContext(any()) }
      verify { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
    }

    "connectAgentWithTransportFilterDisabled should throw StatusException with FAILED_PRECONDITION when mismatch" {
      val proxy = createMockProxy(transportFilterDisabled = false)
      val service = ProxyServiceImpl(proxy)

      val exception = shouldThrow<StatusException> {
        service.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)
      }

      exception.status.code shouldBe Status.Code.FAILED_PRECONDITION
      exception.status.description shouldContain "do not have matching transportFilterDisabled config values"
    }

    "registerAgent should succeed with valid agent context" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = registerAgentRequest {
        agentId = testAgentId
        agentName = "test-agent"
        hostName = "test-host"
        launchId = "launch-123"
        consolidated = false
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerAgent(request)

      response.valid.shouldBeTrue()
      response.agentId shouldBe testAgentId

      verify { mockAgentContext.assignProperties(request) }
      verify { mockAgentContext.markActivityTime(false) }
    }

    "registerAgent should fail with missing agent context" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = registerAgentRequest {
        agentId = testAgentId
        agentName = "test-agent"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerAgent(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
    }

    "registerPath should succeed with valid agent context" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"
      val testPath = "/metrics"
      val testLabels = "job=\"test\""

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { proxy.pathManager.addPath(testPath, testLabels, mockAgentContext) } returns null
      every { proxy.pathManager.pathMapSize } returns 5

      val request = registerPathRequest {
        agentId = testAgentId
        path = testPath
        labels = testLabels
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeTrue()
      response.pathId shouldNotBe -1
      response.pathCount shouldBe 5

      verify { proxy.pathManager.addPath(testPath, testLabels, mockAgentContext) }
      verify { mockAgentContext.markActivityTime(false) }
    }

    // Bug #11: registerPath should propagate the actual failure reason from addPath,
    // not always say "Invalid agentId" which is misleading for consolidated mismatch.
    "registerPath should include addPath failure reason when path registration rejected" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-consolidated"
      val testPath = "/metrics"
      val rejectionReason = "Consolidated agent rejected for non-consolidated path /metrics"

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { proxy.pathManager.addPath(testPath, any(), mockAgentContext) } returns
        PathRejection(rejectionReason, cause = PathRejectionCause.CONSOLIDATION_MISMATCH)
      every { proxy.pathManager.pathMapSize } returns 0

      val request = registerPathRequest {
        agentId = testAgentId
        path = testPath
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeFalse()
      response.pathId shouldBe -1
      // Before the fix, this was "Invalid agentId: test-agent-consolidated (registerPath)"
      response.reason shouldBe rejectionReason
      response.reason shouldContain "Consolidated"
      // The agent decides from the cause whether to retry, so addPath's cause must reach the response.
      response.rejectionCause shouldBe PathRejectionCause.CONSOLIDATION_MISMATCH
    }

    "registerPath should say Invalid agentId when agent context is missing" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-456"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null
      every { proxy.pathManager.pathMapSize } returns 0

      val request = registerPathRequest {
        agentId = testAgentId
        path = "/metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
      response.reason shouldContain testAgentId
      response.rejectionCause shouldBe PathRejectionCause.INVALID_AGENT
    }

    "registerPath should fail with missing agent context" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null
      every { proxy.pathManager.pathMapSize } returns 0

      val request = registerPathRequest {
        agentId = testAgentId
        path = "/metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.registerPath(request)

      response.valid.shouldBeFalse()
      response.pathId shouldBe -1
      response.reason shouldContain "Invalid agentId"
    }

    // Feature 3: when an identity is attached to the gRPC context, registerPath enforces its path
    // patterns. A path outside the identity's patterns is rejected before addPath is ever called.
    "registerPath should deny a path the agent identity is not authorized for" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "team-a-agent"
      val deniedPath = "team_b_metrics"

      // Capture the manager in a local so the exactly=0 verify checks addPath only, not the
      // pathManager getter (which is legitimately called for pathMapSize in the response).
      val pathManager = proxy.pathManager
      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { pathManager.pathMapSize } returns 0

      val identity = AgentIdentity("team_a", ByteArray(0), [AgentAuthManager.globToRegex("team_a_*")])
      val request = registerPathRequest {
        agentId = testAgentId
        path = deniedPath
      }

      val service = ProxyServiceImpl(proxy)
      val context = Context.current().withValue(AgentAuthManager.AGENT_IDENTITY_KEY, identity)
      val previous = context.attach()
      val response =
        try {
          service.registerPath(request)
        } finally {
          context.detach(previous)
        }

      response.valid.shouldBeFalse()
      response.pathId shouldBe -1
      response.reason shouldContain "not authorized"
      response.reason shouldContain "team_a"
      response.rejectionCause shouldBe PathRejectionCause.NOT_AUTHORIZED
      // addPath must not be called when authorization fails.
      verify(exactly = 0) { pathManager.addPath(any(), any(), any()) }
      verify { mockAgentContext.markActivityTime(false) }
    }

    "registerPath should allow a path the agent identity is authorized for" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "team-a-agent"
      val allowedPath = "team_a_metrics"

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { proxy.pathManager.addPath(allowedPath, any(), mockAgentContext, any(), any(), "team_a") } returns null
      every { proxy.pathManager.pathMapSize } returns 1

      val identity = AgentIdentity("team_a", ByteArray(0), [AgentAuthManager.globToRegex("team_a_*")])
      val request = registerPathRequest {
        agentId = testAgentId
        path = allowedPath
      }

      val service = ProxyServiceImpl(proxy)
      val context = Context.current().withValue(AgentAuthManager.AGENT_IDENTITY_KEY, identity)
      val previous = context.attach()
      val response =
        try {
          service.registerPath(request)
        } finally {
          context.detach(previous)
        }

      response.valid.shouldBeTrue()
      // The caller's identity reaches addPath, which lets only the same identity take over a live agent's path.
      verify { proxy.pathManager.addPath(allowedPath, any(), mockAgentContext, any(), any(), "team_a") }
    }

    // ==================== agentId / connection binding ====================
    //
    // Path authorization checks the identity against request.path, but the AgentContext being acted
    // on came from the caller-supplied request.agentId. agentIds are sequential integers that the
    // proxy echoes back to each client, so an authenticated agent could enumerate its neighbors and
    // pass a *victim's* agentId while presenting its own valid token — registering paths against the
    // victim's context, unregistering the victim's paths, or draining the victim's scrape queue.
    // The connection's true agentId now rides the gRPC Context and every RPC rejects a mismatch.

    "registerPath should reject a request whose agentId is not the connection's" {
      val proxy = createMockProxy()
      val victimContext = mockk<AgentContext>(relaxed = true)
      val attackerAgentId = "attacker-agent"
      val victimAgentId = "victim-agent"

      val pathManager = proxy.pathManager
      every { victimContext.agentId } returns victimAgentId
      every { proxy.agentContextManager.getAgentContext(victimAgentId) } returns victimContext
      every { pathManager.pathMapSize } returns 0

      // The attacker is authorized for team_a_* and asks for a team_a_* path, so the identity check
      // passes — only the agentId binding stands between it and the victim's context.
      val identity = AgentIdentity("team_a", ByteArray(0), [AgentAuthManager.globToRegex("team_a_*")])
      val request = registerPathRequest {
        agentId = victimAgentId
        path = "team_a_metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val context =
        Context.current()
          .withValue(AgentAuthManager.AGENT_IDENTITY_KEY, identity)
          .withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, attackerAgentId)
      val previous = context.attach()
      val response =
        try {
          service.registerPath(request)
        } finally {
          context.detach(previous)
        }

      response.valid.shouldBeFalse()
      response.rejectionCause shouldBe PathRejectionCause.BINDING_MISMATCH
      response.pathId shouldBe -1
      response.reason shouldContain "does not match"
      // The victim's context must never be handed to addPath.
      verify(exactly = 0) { pathManager.addPath(any(), any(), any()) }
    }

    "registerPath should allow a request whose agentId is the connection's" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "own-agent"
      val allowedPath = "team_a_metrics"

      every { mockAgentContext.agentId } returns testAgentId
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      every { proxy.pathManager.addPath(allowedPath, any(), mockAgentContext) } returns null
      every { proxy.pathManager.pathMapSize } returns 1

      val request = registerPathRequest {
        agentId = testAgentId
        path = allowedPath
      }

      val service = ProxyServiceImpl(proxy)
      val context = Context.current().withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, testAgentId)
      val previous = context.attach()
      val response =
        try {
          service.registerPath(request)
        } finally {
          context.detach(previous)
        }

      response.valid.shouldBeTrue()
      verify { proxy.pathManager.addPath(allowedPath, any(), mockAgentContext) }
    }

    "unregisterPath should reject a request whose agentId is not the connection's" {
      val proxy = createMockProxy()
      val victimContext = mockk<AgentContext>(relaxed = true)
      val pathManager = proxy.pathManager
      val victimAgentId = "victim-agent"

      every { proxy.agentContextManager.getAgentContext(victimAgentId) } returns victimContext

      val request = unregisterPathRequest {
        agentId = victimAgentId
        path = "team_b_metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val context = Context.current().withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, "attacker-agent")
      val previous = context.attach()
      val response =
        try {
          service.unregisterPath(request)
        } finally {
          context.detach(previous)
        }

      response.valid.shouldBeFalse()
      response.reason shouldContain "does not match"
      // unregisterPath has no identity gate of its own, so the binding check is the only guard.
      coVerify(exactly = 0) { pathManager.removePath(any(), any()) }
    }

    "registerAgent should reject a request whose agentId is not the connection's" {
      val proxy = createMockProxy()
      val victimContext = mockk<AgentContext>(relaxed = true)
      val victimAgentId = "victim-agent"

      every { proxy.agentContextManager.getAgentContext(victimAgentId) } returns victimContext

      val request = registerAgentRequest {
        agentId = victimAgentId
        agentName = "spoofed-name"
        hostName = "spoofed-host"
      }

      val service = ProxyServiceImpl(proxy)
      val context = Context.current().withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, "attacker-agent")
      val previous = context.attach()
      val response =
        try {
          service.registerAgent(request)
        } finally {
          context.detach(previous)
        }

      response.valid.shouldBeFalse()
      response.reason shouldContain "does not match"
      // The victim's identifying metadata must not be overwritten.
      verify(exactly = 0) { victimContext.assignProperties(any()) }
    }

    "readRequestsFromProxy should reject a request whose agentId is not the connection's" {
      val proxy = createMockProxy()
      val victimContext = mockk<AgentContext>(relaxed = true)
      val victimAgentId = "victim-agent"

      every { victimContext.agentId } returns victimAgentId
      // Bounded so an unfixed build exits the stream loop instead of draining the queue forever.
      every { victimContext.isValid() } returnsMany [true, false]
      every { proxy.agentContextManager.getAgentContext(victimAgentId) } returns victimContext

      val request = agentInfo { agentId = victimAgentId }

      val service = ProxyServiceImpl(proxy)
      val context = Context.current().withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, "attacker-agent")
      val previous = context.attach()
      val exception =
        try {
          shouldThrow<StatusException> {
            service.readRequestsFromProxy(request).collect {}
          }
        } finally {
          context.detach(previous)
        }

      exception.status.code shouldBe Status.PERMISSION_DENIED.code
      // Draining the victim's scrape queue must not happen.
      coVerify(exactly = 0) { victimContext.readScrapeRequest() }
    }

    "unregisterPath should succeed with valid agent context" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"
      val testPath = "/metrics"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext
      coEvery { proxy.pathManager.removePath(testPath, testAgentId) } returns mockk(relaxed = true) {
        every { valid } returns true
      }

      val request = unregisterPathRequest {
        agentId = testAgentId
        path = testPath
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.unregisterPath(request)

      response.valid.shouldBeTrue()

      coVerify { proxy.pathManager.removePath(testPath, testAgentId) }
      verify { mockAgentContext.markActivityTime(false) }
    }

    "unregisterPath should fail with missing agent context" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = unregisterPathRequest {
        agentId = testAgentId
        path = "/metrics"
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.unregisterPath(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
    }

    "pathMapSize should return path count" {
      val proxy = createMockProxy()
      every { proxy.pathManager.pathMapSize } returns 42

      val request = pathMapSizeRequest {}

      val service = ProxyServiceImpl(proxy)
      val response = service.pathMapSize(request)

      response.pathCount shouldBe 42
    }

    "sendHeartBeat should succeed with valid agent context" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeTrue()

      verify { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
      verify { mockAgentContext.markActivityTime(false) }
    }

    // Bug #5: reason was unconditionally set to the error message even on valid heartbeats
    "sendHeartBeat should not set reason when valid" {
      val proxy = createMockProxy()
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-456"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeTrue()
      // Before the fix, this was "Invalid agentId: test-agent-456 (sendHeartBeat)"
      response.reason shouldBe ""
    }

    "sendHeartBeat should fail with missing agent context" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain "Invalid agentId"
    }

    "sendHeartBeat should set reason only when invalid" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-789"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = heartBeatRequest {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val response = service.sendHeartBeat(request)

      response.valid.shouldBeFalse()
      response.reason shouldContain testAgentId
      response.reason shouldContain "sendHeartBeat"
    }

    // Tests the gRPC streaming flow for scrape requests from proxy to agent.
    // The proxy continuously streams scrape requests to the agent while:
    // 1. The proxy is running
    // 2. The agent context is valid
    // This test simulates the agent becoming invalid after processing one request,
    // which terminates the stream. The isValid() mock returns [true, false] to
    // simulate one iteration of the loop before the agent disconnects.
    "readRequestsFromProxy should emit scrape requests when agent is valid" {
      val proxy = createMockProxy(isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val mockScrapeRequestWrapper = mockk<ScrapeRequestWrapper>(relaxed = true)
      val mockScrapeRequest = mockk<ScrapeRequest>(relaxed = true)
      val testAgentId = "test-agent-123"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returnsMany [true, false]
      coEvery { mockAgentContext.readScrapeRequest() } returns mockScrapeRequestWrapper andThen null
      every { mockScrapeRequestWrapper.scrapeRequest } returns mockScrapeRequest
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val emittedRequests: MutableList<ScrapeRequest> = []
      flow.collect { emittedRequests.add(it) }

      emittedRequests.size shouldBe 1
      emittedRequests[0] shouldBe mockScrapeRequest
    }

    // Prometheus times out or disconnects, and the proxy stops tracking the request -- but the request is still
    // queued for the agent. Delivering it would make the agent scrape the target for nobody and grow a slow
    // agent's backlog, so a request the proxy no longer tracks is skipped.
    "readRequestsFromProxy should skip a queued request the proxy no longer tracks" {
      val proxy = createMockProxy(isRunning = true)
      val scrapeRequestManager = proxy.scrapeRequestManager
      val agentContextManager = proxy.agentContextManager
      val agentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-123"

      val abandoned = mockk<ScrapeRequestWrapper>(relaxed = true)
      val abandonedRequest = mockk<ScrapeRequest>(relaxed = true)
      every { abandoned.scrapeId } returns 41L
      every { abandoned.scrapeRequest } returns abandonedRequest

      val awaited = mockk<ScrapeRequestWrapper>(relaxed = true)
      val awaitedRequest = mockk<ScrapeRequest>(relaxed = true)
      every { awaited.scrapeId } returns 42L
      every { awaited.scrapeRequest } returns awaitedRequest

      every { agentContext.agentId } returns testAgentId
      every { agentContext.isValid() } returnsMany [true, true, false]
      coEvery { agentContext.readScrapeRequest() } returns abandoned andThen awaited
      every { agentContextManager.getAgentContext(testAgentId) } returns agentContext
      every { scrapeRequestManager.containsScrapeRequest(41L) } returns false
      every { scrapeRequestManager.containsScrapeRequest(42L) } returns true

      val emitted: MutableList<ScrapeRequest> = []
      ProxyServiceImpl(proxy).readRequestsFromProxy(agentInfo { agentId = testAgentId }).collect { emitted.add(it) }

      emitted shouldBe [awaitedRequest]
    }

    "readRequestsFromProxy should throw NOT_FOUND when agent context is missing" {
      val proxy = createMockProxy()
      val testAgentId = "missing-agent-123"

      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns null

      val request = agentInfo {
        agentId = testAgentId
      }

      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val exception = shouldThrow<StatusException> {
        flow.collect {}
      }

      exception.status.code shouldBe Status.NOT_FOUND.code
      exception.status.description shouldContain testAgentId
    }

    // ==================== Bug #11: CancellationException Import Tests ====================

    "writeResponsesToProxy should rethrow kotlinx CancellationException" {
      val proxy = createMockProxy()
      val service = ProxyServiceImpl(proxy)

      val failingFlow = flow<ScrapeResponse> {
        throw kotlinx.coroutines.CancellationException("coroutine cancelled")
      }

      shouldThrow<kotlinx.coroutines.CancellationException> {
        service.writeResponsesToProxy(failingFlow)
      }
    }

    // ==================== writeResponsesToProxy Tests ====================

    "writeResponsesToProxy should process non-chunked scrape responses" {
      val proxy = createMockProxy()

      val response = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 100L
        validResponse = true
        statusCode = 200
        contentType = "text/plain"
        zipped = false
        contentAsText = "metrics data"
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf(response))

      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should handle empty flow" {
      val proxy = createMockProxy()

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf())

      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should process multiple responses" {
      val proxy = createMockProxy()

      val response1 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 101L
        validResponse = true
        statusCode = 200
      }
      val response2 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 102L
        validResponse = true
        statusCode = 200
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf(response1, response2))

      result shouldBe EMPTY_INSTANCE
    }

    // ==================== writeChunkedResponsesToProxy Tests ====================

    "writeChunkedResponsesToProxy should handle empty flow" {
      val proxy = createMockProxy()

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf())

      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should process header-chunk-summary sequence" {
      val proxy = createMockProxy()
      val scrapeId = 200L
      val data = "test chunk data".toByteArray()
      val crc = CRC32()
      crc.update(data)

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }

      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data)
        }
      }

      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = crc.value
        }
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk, summary))

      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should process multi-chunk sequence" {
      val proxy = createMockProxy()
      val scrapeId = 300L
      val data1 = "chunk-one-data".toByteArray()
      val data2 = "chunk-two-data".toByteArray()
      val crc = CRC32()

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }

      crc.update(data1)
      val chunk1 = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data1.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data1)
        }
      }

      crc.update(data2)
      val chunk2 = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 2
          chunkByteCount = data2.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data2)
        }
      }

      val totalSize = data1.size + data2.size
      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 2
          summaryByteCount = totalSize
          summaryChecksum = crc.value
        }
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk1, chunk2, summary))

      result shouldBe EMPTY_INSTANCE
    }

    // ==================== readRequestsFromProxy Edge Case Tests ====================

    "readRequestsFromProxy should stop when proxy stops running" {
      val proxy = createMockProxy(isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-proxy-stop"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returns true
      // Simulate proxy stopping after first check
      every { proxy.isRunning } returnsMany [true, false]
      coEvery { mockAgentContext.readScrapeRequest() } returns null
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val emittedRequests: MutableList<ScrapeRequest> = []
      flow.collect { emittedRequests.add(it) }

      emittedRequests.size shouldBe 0
    }

    "readRequestsFromProxy should skip null readScrapeRequest results" {
      val proxy = createMockProxy(isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-null-read"

      every { mockAgentContext.agentId } returns testAgentId
      // Valid for two iterations then invalid
      every { mockAgentContext.isValid() } returnsMany [true, true, false]
      // Return null both times (channel drained)
      coEvery { mockAgentContext.readScrapeRequest() } returns null
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      val emittedRequests: MutableList<ScrapeRequest> = []
      flow.collect { emittedRequests.add(it) }

      // readScrapeRequest returned null, so nothing emitted
      emittedRequests.size shouldBe 0
    }

    // ==================== readRequestsFromProxy Cleanup Tests ====================

    "readRequestsFromProxy should clean up agent context when transportFilterDisabled" {
      val proxy = createMockProxy(transportFilterDisabled = true, isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-cleanup"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returns false
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      flow.collect {}

      // Agent context should be cleaned up since transportFilterDisabled is true
      verify { proxy.removeAgentContext(testAgentId, any()) }
    }

    "readRequestsFromProxy should not clean up agent context when transportFilter enabled" {
      val proxy = createMockProxy(transportFilterDisabled = false, isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val testAgentId = "test-agent-no-cleanup"

      every { mockAgentContext.agentId } returns testAgentId
      every { mockAgentContext.isValid() } returns false
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      flow.collect {}

      // Agent context should NOT be cleaned up — ProxyServerTransportFilter handles it
      verify(exactly = 0) { proxy.removeAgentContext(any(), any()) }
    }

    "readRequestsFromProxy should clean up on stream cancellation when transportFilterDisabled" {
      val proxy = createMockProxy(transportFilterDisabled = true, isRunning = true)
      val mockAgentContext = mockk<AgentContext>(relaxed = true)
      val mockScrapeRequestWrapper = mockk<ScrapeRequestWrapper>(relaxed = true)
      val mockScrapeRequest = mockk<ScrapeRequest>(relaxed = true)
      val testAgentId = "test-agent-cancel-cleanup"

      every { mockAgentContext.agentId } returns testAgentId
      // Valid for one iteration, then stream will be cancelled by the test
      every { mockAgentContext.isValid() } returnsMany [true, false]
      coEvery { mockAgentContext.readScrapeRequest() } returns mockScrapeRequestWrapper andThen null
      every { mockScrapeRequestWrapper.scrapeRequest } returns mockScrapeRequest
      every { proxy.agentContextManager.getAgentContext(testAgentId) } returns mockAgentContext

      val request = agentInfo { agentId = testAgentId }
      val service = ProxyServiceImpl(proxy)
      val flow = service.readRequestsFromProxy(request)

      flow.collect {}

      verify { proxy.removeAgentContext(testAgentId, any()) }
    }

    // ==================== writeResponsesToProxy Error Handling Tests ====================

    "writeResponsesToProxy should handle flow error gracefully when proxy running" {
      val proxy = createMockProxy(isRunning = true)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ScrapeResponse> = flow {
        throw IllegalStateException("Simulated flow error")
      }

      // Should not throw — error is caught in onFailure
      val result = service.writeResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should suppress error when proxy not running" {
      val proxy = createMockProxy(isRunning = false)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ScrapeResponse> = flow {
        throw IllegalStateException("Simulated flow error")
      }

      val result = service.writeResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeResponsesToProxy should continue processing after single message failure" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager

      val goodResponse1 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 601L
        validResponse = true
        statusCode = 200
      }
      val goodResponse2 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 602L
        validResponse = true
        statusCode = 200
      }

      // Make assignScrapeResults throw on the first call, succeed on the second
      var callCount = 0
      every { scrapeRequestManager.assignScrapeResults(any()) } answers {
        callCount++
        if (callCount == 1) error("Simulated processing failure")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf(goodResponse1, goodResponse2))

      result shouldBe EMPTY_INSTANCE
      // Both messages should have been attempted (stream not killed by first failure)
      verify(exactly = 2) { scrapeRequestManager.assignScrapeResults(any()) }
    }

    // M2: Previously, writeChunkedResponsesToProxy used string-based dispatch on
    // ooc.name.lowercase() which would throw IllegalStateException on CHUNKONEOF_NOT_SET,
    // crashing the entire chunked stream. Now it uses enum constants and handles NOT_SET gracefully.
    "writeChunkedResponsesToProxy should skip message with no oneOf field set" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val service = ProxyServiceImpl(proxy)

      // A default ChunkedScrapeResponse has CHUNKONEOF_NOT_SET
      val emptyResponse = chunkedScrapeResponse {}

      val result = service.writeChunkedResponsesToProxy(flowOf(emptyResponse))

      result shouldBe EMPTY_INSTANCE
      // No chunked context should have been created
      verify(exactly = 0) { contextManager.putChunkedContext(any(), any()) }
    }

    "writeChunkedResponsesToProxy should continue processing after NOT_SET message" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val service = ProxyServiceImpl(proxy)

      val scrapeId = 300L
      val data = "test data".toByteArray()
      val crc = CRC32()
      crc.update(data)
      val checksum = crc.value

      val emptyResponse = chunkedScrapeResponse {}
      val header = chunkedScrapeResponse {
        header = headerData {
          headerScrapeId = scrapeId
          headerValidResponse = true
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }
      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkBytes = ByteString.copyFrom(data)
          chunkByteCount = data.size
          chunkCount = 1
          chunkChecksum = checksum
        }
      }

      crc.reset()
      crc.update(data)
      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = checksum
        }
      }

      every { contextManager.putChunkedContext(scrapeId, any()) } returns Unit
      every { contextManager.getChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000)
      every { contextManager.removeChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000).apply {
        applyChunk(data, data.size, 1, checksum)
      }
      every { scrapeRequestManager.assignScrapeResults(any()) } returns Unit

      // NOT_SET message first, then a valid header-chunk-summary sequence
      val result = service.writeChunkedResponsesToProxy(flowOf(emptyResponse, header, chunk, summary))

      result shouldBe EMPTY_INSTANCE
      // The valid sequence should still be processed despite the NOT_SET message
      verify(exactly = 1) { contextManager.putChunkedContext(scrapeId, any()) }
      verify(exactly = 1) { scrapeRequestManager.assignScrapeResults(any()) }
    }

    // ==================== writeChunkedResponsesToProxy Error Handling Tests ====================

    "writeChunkedResponsesToProxy should handle flow error gracefully" {
      val proxy = createMockProxy(isRunning = true)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ChunkedScrapeResponse> = flow {
        throw IllegalStateException("Simulated chunked flow error")
      }

      val result = service.writeChunkedResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should suppress error when proxy not running" {
      val proxy = createMockProxy(isRunning = false)
      val service = ProxyServiceImpl(proxy)

      val errorFlow: Flow<ChunkedScrapeResponse> = flow {
        throw IllegalStateException("Simulated chunked flow error")
      }

      val result = service.writeChunkedResponsesToProxy(errorFlow)
      result shouldBe EMPTY_INSTANCE
    }

    "writeChunkedResponsesToProxy should clean up orphaned contexts on stream failure" {
      val counters = ChunkFailureCounters()
      val proxy = createMockProxy(isRunning = true, metrics = counters.metrics)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId = 400L

      val chunkedContext = mockk<ChunkedContext>(relaxed = true)
      every { contextManager.removeChunkedContext(scrapeId) } returns chunkedContext

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }

      // Flow emits a header then fails before sending a summary
      val failingFlow: Flow<ChunkedScrapeResponse> = flow {
        emit(header)
        error("Simulated agent disconnect")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(failingFlow)

      result shouldBe EMPTY_INSTANCE

      // Verify the header was stored
      verify { contextManager.putChunkedContext(scrapeId, any()) }
      // Verify the orphaned context was cleaned up
      verify { contextManager.removeChunkedContext(scrapeId) }
      // Bug #4: Verify the waiting HTTP handler is notified via failScrapeRequest
      verify {
        scrapeRequestManager.failScrapeRequest(
          scrapeId,
          match {
          it.contains("abandoned")
        },
          ProxyFailure.AGENT_DISCONNECTED,
        )
      }
      // Counted as an abandoned transfer, not as a validation failure at either stage.
      verify(exactly = 1) { counters.abandoned.inc() }
      verify(exactly = 0) { counters.chunkStage.inc() }
      verify(exactly = 0) { counters.summaryStage.inc() }
    }

    "writeChunkedResponsesToProxy should clean up multiple orphaned contexts on stream failure" {
      val proxy = createMockProxy(isRunning = true)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId1 = 401L
      val scrapeId2 = 402L

      every { contextManager.removeChunkedContext(scrapeId1) } returns mockk(relaxed = true)
      every { contextManager.removeChunkedContext(scrapeId2) } returns mockk(relaxed = true)

      val header1 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId1
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics1"
        }
      }
      val header2 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId2
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics2"
        }
      }

      val failingFlow: Flow<ChunkedScrapeResponse> = flow {
        emit(header1)
        emit(header2)
        error("Simulated agent disconnect")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(failingFlow)

      result shouldBe EMPTY_INSTANCE

      verify { contextManager.putChunkedContext(scrapeId1, any()) }
      verify { contextManager.putChunkedContext(scrapeId2, any()) }
      // Both orphaned contexts should be cleaned up
      verify { contextManager.removeChunkedContext(scrapeId1) }
      verify { contextManager.removeChunkedContext(scrapeId2) }
      // Bug #4: Both orphaned scrape requests should be failed
      verify {
        scrapeRequestManager.failScrapeRequest(
          scrapeId1,
          match {
          it.contains("abandoned")
        },
          ProxyFailure.AGENT_DISCONNECTED,
        )
      }
      verify {
        scrapeRequestManager.failScrapeRequest(
          scrapeId2,
          match {
          it.contains("abandoned")
        },
          ProxyFailure.AGENT_DISCONNECTED,
        )
      }
    }

    "writeChunkedResponsesToProxy should not clean up completed contexts on stream failure" {
      val proxy = createMockProxy(isRunning = true)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val completedScrapeId = 403L
      val orphanedScrapeId = 404L

      val data = "test data".toByteArray()
      val crc = CRC32()
      crc.update(data)

      // Set up the completed context's chunked context for the summary removal
      val completedChunkedContext = ChunkedContext(
        chunkedScrapeResponse {
          header = headerData {
            headerValidResponse = true
            headerScrapeId = completedScrapeId
            headerAgentId = "agent-1"
            headerStatusCode = 200
            headerContentType = "text/plain"
            headerUrl = "http://test/metrics"
          }
        },
        1000000,
      )
      completedChunkedContext.applyChunk(data, data.size, 1, crc.value)

      // Return the real chunked context when summary removes it
      every { contextManager.removeChunkedContext(completedScrapeId) } returns completedChunkedContext
      every { contextManager.removeChunkedContext(orphanedScrapeId) } returns mockk(relaxed = true)

      val header1 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = completedScrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics"
        }
      }
      val chunk1 = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = completedScrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = crc.value
          chunkBytes = ByteString.copyFrom(data)
        }
      }
      val summary1 = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = completedScrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = crc.value
        }
      }
      val header2 = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = orphanedScrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
          headerUrl = "http://test/metrics2"
        }
      }

      val failingFlow: Flow<ChunkedScrapeResponse> = flow {
        // First transfer completes normally
        emit(header1)
        emit(chunk1)
        emit(summary1)
        // Second transfer starts but fails before summary
        emit(header2)
        error("Simulated agent disconnect")
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(failingFlow)

      result shouldBe EMPTY_INSTANCE

      // Completed context was removed by summary processing (assignScrapeResults called)
      verify(exactly = 1) { contextManager.removeChunkedContext(completedScrapeId) }
      verify(exactly = 1) { scrapeRequestManager.assignScrapeResults(any()) }
      // Orphaned context should be cleaned up and failed during cleanup phase
      verify { contextManager.removeChunkedContext(orphanedScrapeId) }
      // Bug #4: Only the orphaned scrape should be failed, not the completed one
      verify {
        scrapeRequestManager.failScrapeRequest(
          orphanedScrapeId,
          match {
          it.contains("abandoned")
        },
          ProxyFailure.AGENT_DISCONNECTED,
        )
      }
      verify(exactly = 0) { scrapeRequestManager.failScrapeRequest(eq(completedScrapeId), any(), any()) }
    }

    // Bug #2: Chunk validation failure left the HTTP handler waiting until timeout.
    // The fix calls failScrapeRequest() to notify the handler immediately.

    "writeChunkedResponsesToProxy should notify handler on chunk validation failure" {
      val counters = ChunkFailureCounters()
      val proxy = createMockProxy(metrics = counters.metrics)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId = 500L

      // Create a ChunkedContext from a real header, then set up the mock
      // to return it. Send a chunk with a bad checksum to trigger validation failure.
      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }

      val data = "test chunk data".toByteArray()
      val badChecksum = 12345L // Wrong checksum to trigger ChunkValidationException

      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = badChecksum
          chunkBytes = ByteString.copyFrom(data)
        }
      }

      // Use a real ChunkedContext so applyChunk() actually validates
      every { contextManager.getChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000)

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk))

      result shouldBe EMPTY_INSTANCE

      // The waiting HTTP handler should have been notified via failScrapeRequest
      verify {
        scrapeRequestManager.failScrapeRequest(scrapeId, match { it.contains("Chunk") }, ProxyFailure.INVALID_RESPONSE)
      }
      // Context should have been cleaned up
      verify { contextManager.removeChunkedContext(scrapeId) }
      // Counted at the chunk stage only: the failed transfer is not also counted as abandoned at stream end.
      verify(exactly = 1) { counters.chunkStage.inc() }
      verify(exactly = 0) { counters.summaryStage.inc() }
      verify(exactly = 0) { counters.abandoned.inc() }
    }

    "writeChunkedResponsesToProxy should notify handler on summary validation failure" {
      val counters = ChunkFailureCounters()
      val proxy = createMockProxy(metrics = counters.metrics)
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val scrapeId = 501L

      val data = "test chunk data".toByteArray()
      val crc = CRC32()
      crc.update(data)
      val correctChecksum = crc.value

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }

      val chunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = scrapeId
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = correctChecksum
          chunkBytes = ByteString.copyFrom(data)
        }
      }

      // Summary with wrong checksum to trigger ChunkValidationException
      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = scrapeId
          summaryChunkCount = 1
          summaryByteCount = data.size
          summaryChecksum = 99999L // Wrong checksum
        }
      }

      // Use a real ChunkedContext that has had the chunk applied, so applySummary() validates
      val realContext = ChunkedContext(header, 1000000).apply {
        applyChunk(data, data.size, 1, correctChecksum)
      }
      every { contextManager.getChunkedContext(scrapeId) } returns ChunkedContext(header, 1000000)
      every { contextManager.removeChunkedContext(scrapeId) } returns realContext

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header, chunk, summary))

      result shouldBe EMPTY_INSTANCE

      // The waiting HTTP handler should have been notified via failScrapeRequest
      verify {
        scrapeRequestManager.failScrapeRequest(
          scrapeId,
          match {
          it.contains("Summary")
        },
          ProxyFailure.INVALID_RESPONSE,
        )
      }
      // Counted at the summary stage only.
      verify(exactly = 1) { counters.summaryStage.inc() }
      verify(exactly = 0) { counters.chunkStage.inc() }
      verify(exactly = 0) { counters.abandoned.inc() }
    }

    // ==================== Bug #20: transportFilterDisabled cleanup Tests ====================

    // Bug #20: When transportFilterDisabled is true and readRequestsFromProxy is called,
    // the finally block should clean up the AgentContext via removeAgentContext.
    "Bug #20: readRequestsFromProxy should cleanup on stream termination when transportFilterDisabled" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContext = AgentContext("test-addr")
      val agentId = agentContext.agentId

      every { proxy.agentContextManager.getAgentContext(agentId) } returns agentContext
      coEvery { proxy.removeAgentContext(agentId, any()) } returns agentContext

      val service = ProxyServiceImpl(proxy)
      val request = agentInfo { this.agentId = agentId }

      // Invalidate the agent context immediately so the loop exits
      agentContext.invalidate()

      val results: MutableList<ScrapeRequest> = []
      service.readRequestsFromProxy(request).collect { results.add(it) }

      // Verify cleanup was called because transportFilterDisabled is true
      coVerify { proxy.removeAgentContext(agentId, match { it.contains("transport filter disabled") }) }
    }

    // Bug #20: When transportFilterDisabled is false, readRequestsFromProxy should NOT
    // call removeAgentContext (transport filter handles cleanup instead).
    "Bug #20: readRequestsFromProxy should not cleanup when transportFilterDisabled is false" {
      val proxy = createMockProxy(transportFilterDisabled = false)
      val agentContext = AgentContext("test-addr")
      val agentId = agentContext.agentId

      every { proxy.agentContextManager.getAgentContext(agentId) } returns agentContext

      val service = ProxyServiceImpl(proxy)
      val request = agentInfo { this.agentId = agentId }

      // Invalidate the agent context immediately so the loop exits
      agentContext.invalidate()

      val results: MutableList<ScrapeRequest> = []
      service.readRequestsFromProxy(request).collect { results.add(it) }

      // Verify cleanup was NOT called because transportFilterDisabled is false
      coVerify(exactly = 0) { proxy.removeAgentContext(any(), any()) }
    }

    "writeResponsesToProxy should call assignScrapeResults for each response" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager
      val service = ProxyServiceImpl(proxy)

      val response1 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 501L
        validResponse = true
        statusCode = 200
      }
      val response2 = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 502L
        validResponse = true
        statusCode = 200
      }

      service.writeResponsesToProxy(flowOf(response1, response2))

      verify(exactly = 2) { scrapeRequestManager.assignScrapeResults(any()) }
    }

    // Item 7: a per-response processing error must fail the in-flight request immediately so the
    // waiting HTTP handler returns a 502 now, rather than blocking until scrapeRequestTimeoutSecs
    // and reporting a misleading timeout. Mirrors writeChunkedResponsesToProxy's failScrapeRequest.
    "Item 7: writeResponsesToProxy should fail the scrape request when processing throws" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.assignScrapeResults(any()) } answers { error("Simulated processing failure") }

      val response = scrapeResponse {
        agentId = "agent-1"
        scrapeId = 700L
        validResponse = true
        statusCode = 200
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeResponsesToProxy(flowOf(response))

      result shouldBe EMPTY_INSTANCE
      // The waiting HTTP handler is notified via failScrapeRequest for the failed scrapeId.
      verify {
        scrapeRequestManager.failScrapeRequest(
          700L,
          match {
          it.contains("Error processing")
        },
          ProxyFailure.INVALID_RESPONSE,
        )
      }
    }

    // Item 30: a chunked HEADER for an unknown/stale scrapeId must be dropped, not turned into a
    // ChunkedContext (which would leak the entry). The shared createMockProxy() stubs
    // containsScrapeRequest=true, so override it to false to exercise the drop branch.
    "Item 30: writeChunkedResponsesToProxy should drop header for unknown scrapeId" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeId = 800L
      every { proxy.scrapeRequestManager.containsScrapeRequest(scrapeId) } returns false

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = scrapeId
          headerAgentId = "agent-1"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }

      val service = ProxyServiceImpl(proxy)
      val result = service.writeChunkedResponsesToProxy(flowOf(header))

      result shouldBe EMPTY_INSTANCE
      // No ChunkedContext should be created for an unknown scrapeId.
      verify(exactly = 0) { contextManager.putChunkedContext(any(), any()) }
    }

    // ==================== scrape results bound to the requesting agent ====================
    //
    // Scrape IDs come from one process-wide counter, and the response RPCs look the waiting request up
    // by scrapeId alone. Without an ownership check, an authenticated agent could answer, fail, or hijack
    // the chunked transfer of a scrape that was sent to a different agent -- defeating per-agent path
    // authorization. Results are now accepted only from the agent the scrape was sent to.

    suspend fun <T> onConnection(
      connectionAgentId: String,
      block: suspend () -> T,
    ): T {
      val context = Context.current().withValue(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY, connectionAgentId)
      val previous = context.attach()
      return try {
        block()
      } finally {
        context.detach(previous)
      }
    }

    "writeResponsesToProxy should ignore a result for a scrape sent to another agent" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.ownerAgentId(900L) } returns "victim-agent"

      val forged = scrapeResponse {
        agentId = "attacker-agent"
        scrapeId = 900L
        validResponse = true
        statusCode = 200
        contentAsText = "forged_metric 1"
      }

      onConnection("attacker-agent") {
        ProxyServiceImpl(proxy).writeResponsesToProxy(flowOf(forged))
      }

      verify(exactly = 0) { scrapeRequestManager.assignScrapeResults(any()) }
    }

    "writeResponsesToProxy should accept a result for a scrape sent to the connection's agent" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.ownerAgentId(901L) } returns "own-agent"

      val response = scrapeResponse {
        agentId = "own-agent"
        scrapeId = 901L
        validResponse = true
        statusCode = 200
      }

      onConnection("own-agent") {
        ProxyServiceImpl(proxy).writeResponsesToProxy(flowOf(response))
      }

      verify(exactly = 1) { scrapeRequestManager.assignScrapeResults(match { it.srScrapeId == 901L }) }
    }

    "writeResponsesToProxy should not fail a scrape sent to another agent when processing its result throws" {
      val proxy = createMockProxy()
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.ownerAgentId(902L) } returns "victim-agent"
      every { scrapeRequestManager.assignScrapeResults(any()) } answers { error("Simulated processing failure") }

      val malformed = scrapeResponse {
        agentId = "attacker-agent"
        scrapeId = 902L
        validResponse = true
        statusCode = 200
      }

      onConnection("attacker-agent") {
        ProxyServiceImpl(proxy).writeResponsesToProxy(flowOf(malformed))
      }

      verify(exactly = 0) { scrapeRequestManager.failScrapeRequest(902L, any(), any()) }
    }

    "writeChunkedResponsesToProxy should not open a transfer for a scrape sent to another agent" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      // Stub through a captured reference: a chained `proxy.scrapeRequestManager.x()` stub makes MockK swap
      // in a child mock, which would drop createMockProxy()'s containsScrapeRequest=true stub.
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.ownerAgentId(903L) } returns "victim-agent"

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = 903L
          headerAgentId = "attacker-agent"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }

      onConnection("attacker-agent") {
        ProxyServiceImpl(proxy).writeChunkedResponsesToProxy(flowOf(header))
      }

      verify(exactly = 0) { contextManager.putChunkedContext(any(), any()) }
    }

    "writeChunkedResponsesToProxy should open a transfer for a scrape sent to the connection's agent" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.ownerAgentId(906L) } returns "own-agent"

      val header = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = 906L
          headerAgentId = "own-agent"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }

      onConnection("own-agent") {
        ProxyServiceImpl(proxy).writeChunkedResponsesToProxy(flowOf(header))
      }

      verify(exactly = 1) { contextManager.putChunkedContext(906L, any()) }
    }

    "writeChunkedResponsesToProxy should not apply a chunk to a transfer for another agent's scrape" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.ownerAgentId(904L) } returns "victim-agent"

      // The victim's in-progress transfer. A chunk with a bad checksum would fail validation, discard this
      // context, and fail the victim's scrape if it were ever applied.
      val victimHeader = chunkedScrapeResponse {
        header = headerData {
          headerValidResponse = true
          headerScrapeId = 904L
          headerAgentId = "victim-agent"
          headerStatusCode = 200
          headerContentType = "text/plain"
        }
      }
      every { contextManager.getChunkedContext(904L) } returns ChunkedContext(victimHeader, 1000000)

      val data = "forged chunk".toByteArray()
      val badChunk = chunkedScrapeResponse {
        chunk = chunkData {
          chunkScrapeId = 904L
          chunkCount = 1
          chunkByteCount = data.size
          chunkChecksum = 12345L
          chunkBytes = ByteString.copyFrom(data)
        }
      }

      onConnection("attacker-agent") {
        ProxyServiceImpl(proxy).writeChunkedResponsesToProxy(flowOf(badChunk))
      }

      verify(exactly = 0) { contextManager.removeChunkedContext(904L) }
      verify(exactly = 0) { scrapeRequestManager.failScrapeRequest(904L, any(), any()) }
    }

    "writeChunkedResponsesToProxy should not apply a summary to a transfer for another agent's scrape" {
      val proxy = createMockProxy()
      val contextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      every { scrapeRequestManager.ownerAgentId(905L) } returns "victim-agent"

      val summary = chunkedScrapeResponse {
        summary = summaryData {
          summaryScrapeId = 905L
          summaryChunkCount = 1
          summaryByteCount = 10
          summaryChecksum = 12345L
        }
      }

      onConnection("attacker-agent") {
        ProxyServiceImpl(proxy).writeChunkedResponsesToProxy(flowOf(summary))
      }

      // Removing the context is what a summary does first; the victim's transfer must be left intact.
      verify(exactly = 0) { contextManager.removeChunkedContext(905L) }
    }

    // ==================== heartbeats bound to the connection ====================

    "sendHeartBeat should reject a request whose agentId is not the connection's" {
      val proxy = createMockProxy()
      val agentContextManager = proxy.agentContextManager
      val victimContext = mockk<AgentContext>(relaxed = true)
      every { victimContext.agentId } returns "victim-agent"
      every { agentContextManager.getAgentContext("victim-agent") } returns victimContext

      val response =
        onConnection("attacker-agent") {
          ProxyServiceImpl(proxy).sendHeartBeat(heartBeatRequest { agentId = "victim-agent" })
        }

      response.valid.shouldBeFalse()
      response.reason shouldContain "does not match"
      // A spoofed heartbeat must not keep the victim's context from being evicted.
      verify(exactly = 0) { victimContext.markActivityTime(any()) }
    }

    "sendHeartBeat should accept a request whose agentId is the connection's" {
      val proxy = createMockProxy()
      val agentContextManager = proxy.agentContextManager
      val ownContext = mockk<AgentContext>(relaxed = true)
      every { ownContext.agentId } returns "own-agent"
      every { agentContextManager.getAgentContext("own-agent") } returns ownContext

      val response =
        onConnection("own-agent") {
          ProxyServiceImpl(proxy).sendHeartBeat(heartBeatRequest { agentId = "own-agent" })
        }

      response.valid.shouldBeTrue()
      verify { ownContext.markActivityTime(false) }
    }

    // ==================== identity binding with the transport filter disabled ====================
    //
    // With transportFilterDisabled (the nginx deployment) there is no transport-assigned agentId, so the
    // connection check above can't run. The only per-call signal is the auth identity the token resolved
    // to. connectAgentWithTransportFilterDisabled records it on the new AgentContext, and every later call
    // naming that agent must present the same identity -- otherwise an agent authenticated as one identity
    // could act on an agent that connected as another.

    suspend fun <T> asIdentity(
      identityName: String,
      block: suspend () -> T,
    ): T {
      val identity = AgentIdentity(identityName, ByteArray(0), emptyList())
      val context = Context.current().withValue(AgentAuthManager.AGENT_IDENTITY_KEY, identity)
      val previous = context.attach()
      return try {
        block()
      } finally {
        context.detach(previous)
      }
    }

    "connectAgentWithTransportFilterDisabled should record the caller's identity on the new agent context" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager

      asIdentity("team-a") {
        ProxyServiceImpl(proxy).connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE)
      }

      val added = slot<AgentContext>()
      verify { agentContextManager.addAgentContext(capture(added)) }
      added.captured.authIdentityName shouldBe "team-a"
    }

    "registerPath should reject an agentId whose context is bound to a different identity" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager
      val pathManager = proxy.pathManager
      val victimContext = AgentContext("victim-host", authIdentityName = "team-a")
      every { agentContextManager.getAgentContext(victimContext.agentId) } returns victimContext
      // Stubbed to succeed so a rejection can only come from the identity check.
      every { pathManager.addPath(any(), any(), any(), any(), any()) } returns null

      val response =
        asIdentity("team-b") {
          ProxyServiceImpl(proxy).registerPath(
            registerPathRequest {
              agentId = victimContext.agentId
              path = "any_metrics"
            },
          )
        }

      response.valid.shouldBeFalse()
      response.reason shouldContain "identity"
      response.rejectionCause shouldBe PathRejectionCause.BINDING_MISMATCH
      verify(exactly = 0) { pathManager.addPath(any(), any(), any(), any(), any()) }
    }

    "registerPath should allow an agentId whose context is bound to the caller's identity" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager
      val pathManager = proxy.pathManager
      val ownContext = AgentContext("own-host", authIdentityName = "team-a")
      every { agentContextManager.getAgentContext(ownContext.agentId) } returns ownContext
      every { pathManager.addPath(any(), any(), any(), any(), any(), "team-a") } returns null

      val response =
        asIdentity("team-a") {
          ProxyServiceImpl(proxy).registerPath(
            registerPathRequest {
              agentId = ownContext.agentId
              path = "any_metrics"
            },
          )
        }

      response.valid.shouldBeTrue()
      verify { pathManager.addPath("any_metrics", any(), ownContext, any(), any(), "team-a") }
    }

    // Contexts the transport filter creates, and contexts created without auth, record no identity. Binding
    // must not reject those, or enabling auth would break every filter-enabled deployment.
    "registerPath should allow a caller identity when the agent context has no bound identity" {
      val proxy = createMockProxy()
      val agentContextManager = proxy.agentContextManager
      val pathManager = proxy.pathManager
      val unboundContext = AgentContext("some-host")
      every { agentContextManager.getAgentContext(unboundContext.agentId) } returns unboundContext
      every { pathManager.addPath(any(), any(), any(), any(), any(), "team-a") } returns null

      val response =
        asIdentity("team-a") {
          ProxyServiceImpl(proxy).registerPath(
            registerPathRequest {
              agentId = unboundContext.agentId
              path = "any_metrics"
            },
          )
        }

      response.valid.shouldBeTrue()
    }

    "unregisterPath should reject an agentId whose context is bound to a different identity" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager
      val pathManager = proxy.pathManager
      val victimContext = AgentContext("victim-host", authIdentityName = "team-a")
      every { agentContextManager.getAgentContext(victimContext.agentId) } returns victimContext

      val response =
        asIdentity("team-b") {
          ProxyServiceImpl(proxy).unregisterPath(
            unregisterPathRequest {
              agentId = victimContext.agentId
              path = "team_a_metrics"
            },
          )
        }

      response.valid.shouldBeFalse()
      coVerify(exactly = 0) { pathManager.removePath(any(), any()) }
    }

    "registerAgent should reject an agentId whose context is bound to a different identity" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager
      val victimContext = AgentContext("victim-host", authIdentityName = "team-a")
      every { agentContextManager.getAgentContext(victimContext.agentId) } returns victimContext

      val response =
        asIdentity("team-b") {
          ProxyServiceImpl(proxy).registerAgent(
            registerAgentRequest {
              agentId = victimContext.agentId
              agentName = "spoofed-name"
            },
          )
        }

      response.valid.shouldBeFalse()
      victimContext.agentName shouldBe "Unassigned"
    }

    "readRequestsFromProxy should reject an agentId whose context is bound to a different identity" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager
      val victimContext = AgentContext("victim-host", authIdentityName = "team-a")
      // Invalidated so an unfixed build exits the read loop at once instead of waiting on the queue.
      victimContext.invalidate()
      every { agentContextManager.getAgentContext(victimContext.agentId) } returns victimContext

      val exception =
        asIdentity("team-b") {
          shouldThrow<StatusException> {
            ProxyServiceImpl(proxy).readRequestsFromProxy(agentInfo { agentId = victimContext.agentId }).collect {}
          }
        }

      exception.status.code shouldBe Status.PERMISSION_DENIED.code
      // With the transport filter disabled, a closing stream removes the agent it named.
      verify(exactly = 0) { proxy.removeAgentContext(any(), any()) }
    }

    "sendHeartBeat should reject an agentId whose context is bound to a different identity" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager
      val victimContext = AgentContext("victim-host", authIdentityName = "team-a")
      every { agentContextManager.getAgentContext(victimContext.agentId) } returns victimContext

      val response =
        asIdentity("team-b") {
          ProxyServiceImpl(proxy).sendHeartBeat(heartBeatRequest { agentId = victimContext.agentId })
        }

      response.valid.shouldBeFalse()
    }

    "writeResponsesToProxy should ignore a result from a different identity when the transport filter is disabled" {
      val proxy = createMockProxy(transportFilterDisabled = true)
      val agentContextManager = proxy.agentContextManager
      val scrapeRequestManager = proxy.scrapeRequestManager
      val victimContext = AgentContext("victim-host", authIdentityName = "team-a")
      every { scrapeRequestManager.ownerAgentId(910L) } returns victimContext.agentId
      every { agentContextManager.getAgentContext(victimContext.agentId) } returns victimContext

      val forged = scrapeResponse {
        agentId = victimContext.agentId
        scrapeId = 910L
        validResponse = true
        statusCode = 200
      }

      asIdentity("team-b") {
        ProxyServiceImpl(proxy).writeResponsesToProxy(flowOf(forged))
      }

      verify(exactly = 0) { scrapeRequestManager.assignScrapeResults(any()) }
    }
  }
}
