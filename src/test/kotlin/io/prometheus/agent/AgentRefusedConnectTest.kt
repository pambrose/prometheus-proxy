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

import com.google.protobuf.Empty
import com.pambrose.common.dsl.GrpcDsl.server
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import io.prometheus.Agent
import io.prometheus.agent.AgentActivityKind.CONNECTED
import io.prometheus.agent.AgentActivityKind.CONNECT_FAILED
import io.prometheus.agent.AgentActivityKind.PATH_REFUSED
import io.prometheus.agent.AgentActivityKind.PATH_REGISTERED
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts.AGENT_REFUSED_CONNECT_DASHBOARD_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.agentOptionsFromConfig
import io.prometheus.grpc.AgentInfo
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.HeartBeatResponse
import io.prometheus.grpc.PathNotice
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.RegisterAgentResponse
import io.prometheus.grpc.RegisterPathRequest
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatResponse
import io.prometheus.grpc.registerAgentResponse
import io.prometheus.grpc.registerPathResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.seconds

/**
 * An agent whose proxy takes its connects and then refuses it, its registration or every one of its paths, tries again
 * every `reconnectPauseSecs`. Its activity feed says so once, as its log does: when it starts failing, when the reason
 * changes, and when it recovers, not on every attempt, each of which added about three lines (a connect, the refused
 * paths, and a disconnect). The proxy is a stand-in served in-process, so nothing but the agent's dashboard binds a
 * port.
 */
class AgentRefusedConnectTest : StringSpec() {
  // What the stand-in proxy refuses: the agent's registration, every path the agent registers, or nothing.
  private enum class Refusal { AGENT, PATHS, NONE }

  // A proxy that takes every connect and refuses as [refusal] says. Its streams stay open, so a connection it takes
  // lasts until the agent stops.
  private class RefusingProxy : ProxyServiceGrpcKt.ProxyServiceCoroutineImplBase() {
    @Volatile
    var refusal = Refusal.AGENT

    private val ids = AtomicInt(0)

    // The registrations the agent has asked for: one per attempt that got past its connect.
    val registrations = AtomicInt(0)

    override suspend fun connectAgentWithTransportFilterDisabled(request: Empty): AgentInfo =
      agentInfo { agentId = "agent-${ids.incrementAndFetch()}" }

    override suspend fun registerAgent(request: RegisterAgentRequest): RegisterAgentResponse {
      registrations.incrementAndFetch()
      val refused = refusal == Refusal.AGENT
      return registerAgentResponse {
        valid = !refused
        if (refused) reason = "Invalid agentId: ${request.agentId} (registerAgent)"
      }
    }

    override suspend fun registerPath(request: RegisterPathRequest): RegisterPathResponse {
      val refused = refusal == Refusal.PATHS
      return registerPathResponse {
        valid = !refused
        if (refused) {
          reason = "Agent is not authorized to register path /${request.path}"
          rejectionCause = PathRejectionCause.NOT_AUTHORIZED
        } else {
          pathId = 1L
        }
      }
    }

    override fun readRequestsFromProxy(request: AgentInfo): Flow<ScrapeRequest> = flow { awaitCancellation() }

    override fun readPathNoticesFromProxy(request: AgentInfo): Flow<PathNotice> = flow { awaitCancellation() }

    override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
      requests.collect {}
      return EMPTY_INSTANCE
    }

    override suspend fun writeChunkedResponsesToProxy(requests: Flow<ChunkedScrapeResponse>): Empty {
      requests.collect {}
      return EMPTY_INSTANCE
    }

    override suspend fun sendHeartBeat(request: HeartBeatRequest): HeartBeatResponse =
      heartBeatResponse {
        valid = true
      }
  }

  // An agent with its dashboard on, so it keeps a feed, and one static path, reconnecting every second.
  private fun refusedAgent(serverName: String): Agent =
    Agent(
      options =
        agentOptionsFromConfig(
          """agent.pathConfigs = [{ name = app, path = app_metrics, url = "$APP_URL" }]""",
          [
            "--proxy",
            "localhost:$PROXY_AGENT_PORT",
            "--tf-disabled",
            "--dashboard",
            "--dashboard_host",
            LOOPBACK_HOST,
            "--dashboard_port",
            "$AGENT_REFUSED_CONNECT_DASHBOARD_PORT",
            "-Dagent.internal.reconnectPauseSecs=1",
          ],
        ),
      inProcessServerName = serverName,
      testMode = true,
    )

  companion object {
    // The static path's target, which the agent never scrapes: the stand-in proxy sends no scrapes.
    private const val APP_URL = "http://$LOOPBACK_HOST:$PROXY_HTTP_PORT/metrics"
  }

  init {
    "a proxy that keeps refusing the agent should be in the feed once per reason, and the recovery once" {
      val serverName = "agent-refused-connect-${System.nanoTime()}"
      val stub = RefusingProxy()
      val proxy = server(inProcessServerName = serverName) { addService(stub) }.start()
      val agent = refusedAgent(serverName)

      // Waits until [more] attempts past the connect have begun since [from], so all but the last have ended.
      suspend fun attempts(
        from: Int,
        more: Int,
      ) = eventually(20.seconds) { stub.registrations.load() shouldBeGreaterThanOrEqual from + more }

      fun feed() = agent.activityLog.shouldNotBeNull().snapshot().entries

      try {
        agent.startSync()

        // The proxy refuses the agent's registration: the connect it took, and why it failed, once.
        attempts(from = 0, more = 4)
        feed().map { it.kind } shouldContainExactly [CONNECT_FAILED, CONNECTED]
        feed().first().detail shouldContain "Invalid agentId"

        // It refuses every path instead: a new reason, so the connect, the refusal, and the failure, once more.
        stub.refusal = Refusal.PATHS
        attempts(from = stub.registrations.load(), more = 4)
        feed().map { it.kind } shouldContainExactly
          [CONNECT_FAILED, PATH_REFUSED, CONNECTED, CONNECT_FAILED, CONNECTED]
        feed().first().detail shouldContain "rejected all 1 static paths"

        // It takes them: the agent connects and registers its path, and the feed says so.
        stub.refusal = Refusal.NONE
        eventually(20.seconds) {
          agent.isConnectedToProxy.shouldBeTrue()
          feed().map { it.kind } shouldContainExactly
            [PATH_REGISTERED, CONNECTED, CONNECT_FAILED, PATH_REFUSED, CONNECTED, CONNECT_FAILED, CONNECTED]
        }
      } finally {
        agent.stopSync()
        proxy.shutdownNow().awaitTermination()
      }
    }
  }
}
