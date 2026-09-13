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

package io.prometheus.harness

import io.grpc.Metadata
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.mockk.coEvery
import io.mockk.spyk
import io.prometheus.common.TestPorts
import io.prometheus.grpc.heartBeatResponse
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import kotlin.time.Duration.Companion.seconds

// The heartbeat loop's teardown (findings 1 & 2): when a heartbeat reports the agent evicted, the loop
// shuts the channel down so the idle request stream errors out and the run loop reconnects, instead of
// the agent lingering as a zombie. InProcessReconnectTest cannot reach that branch: dropping the
// proxy-side context ends the request stream, which reconnects the agent before a heartbeat gets a say.
// Here the stream stays healthy and only the heartbeat's answer changes, so the teardown is the sole
// route back to the run loop.
class InProcessHeartbeatEvictionTest : StringSpec() {
  companion object : HarnessSetup() {
    private const val SERVER_NAME = "hb-eviction"
    private const val HTTP_PORT = TestPorts.HEARTBEAT_EVICTION_HTTP_PORT
  }

  init {
    beforeSpec {
      setupProxyAndAgent(
        proxyPort = HTTP_PORT,
        proxySetup = { startProxy(SERVER_NAME, proxyPort = HTTP_PORT) },
        agentSetup = {
          startAgent(
            serverName = SERVER_NAME,
            args = [
              "-Dagent.internal.heartbeatMaxInactivitySecs=1",
              "-Dagent.internal.heartbeatCheckPauseMillis=200",
              "-Dagent.internal.reconnectPauseSecs=1",
            ],
          )
        },
      )
    }

    afterSpec {
      takeDownProxyAndAgent()
    }

    "an evicted heartbeat should tear the connection down so the agent reconnects with a fresh id" {
      val originalAgentId = agent.agentId
      originalAgentId.shouldNotBeEmpty()

      // Only the heartbeat is intercepted; every other RPC still reaches the real proxy.
      val grpcService = agent.grpcService
      val evictingStub = spyk(grpcService.grpcStub)
      coEvery { evictingStub.sendHeartBeat(any(), any<Metadata>()) } returns
        heartBeatResponse {
          valid = false
          reason = "test: evicted"
        }
      // With a deadline set, unaryStub() derives a fresh stub per call and the spy would never be consulted.
      grpcService.unaryDeadlineSecs = 0
      grpcService.grpcStub = evictingStub

      eventually(30.seconds) {
        agent.agentId.shouldNotBeEmpty()
        agent.agentId shouldNotBe originalAgentId
        proxy.agentContextManager.getAgentContext(agent.agentId).shouldNotBeNull()
      }

      // The reconnect rebuilt the stub, so the spy is gone and later heartbeats are real again.
      (agent.grpcService.grpcStub !== evictingStub).shouldBeTrue()
      eventually(10.seconds) {
        proxy.pathManager.getAgentContextInfo("agent1_metrics").shouldNotBeNull()
      }
    }
  }
}
