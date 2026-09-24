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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.kotest.core.spec.style.StringSpec
import io.prometheus.Proxy
import io.prometheus.common.Lincheck
import io.prometheus.grpc.registerAgentRequest
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import org.slf4j.LoggerFactory

// Lincheck races ProxyPathManager's path registration against unregistering and against agent removal, on the real
// code the TLA+ spec (specs/tla/ProxyRegistry.tla) models. Registration does what ProxyServiceImpl.registerPath does
// -- look the agent's context up outside the path-map lock, then addPath -- and removal does what
// Proxy.removeAgentContext does: remove and invalidate the context, then sweep its paths. Five agents share three
// paths, each serving at most two: two exclusive agents of team-a, which may take over each other's paths, an
// exclusive agent of team-b, which may not, and a consolidated agent of each team, which may not share a path. Once
// every operation has finished, validate checks the TLA+ spec's invariants:
//  - NoStrandedPath: every agent a path lists is still valid (finding 7: none left on a removed agent);
//  - PathCountsAgree: pathCountFor, which enforces the limit and decides when a displaced agent is orphaned, matches
//    the path map, and never exceeds the limit;
//  - an exclusive path has one agent, consolidated and exclusive agents never share a path, and every valid agent on a
//    path has the path's identity. Only a consolidated join can break that last one: a refused exclusive takeover
//    leaves no trace in the path map, as an allowed one replaces the path's only agent.
//
// One real Proxy, never started, serves every run: a MockK stand-in retained every call it recorded, thousands of runs'
// worth, and ran the test JVM out of heap. Stress only, since a Proxy is far more than model checking can replay.
class ProxyPathManagerLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "path registration keeps the path map consistent under stress" {
      // Rejections and displacements log at WARN and INFO, on every one of thousands of runs.
      withLoggingOff<ProxyPathManager> {
        StressOptions()
          .iterations(ITERATIONS)
          .invocationsPerIteration(INVOCATIONS)
          .threads(THREADS)
          .actorsPerThread(ACTORS_PER_THREAD)
          .check(ProxyPathManagerOps::class)
      }
    }
  }

  companion object {
    private inline fun <reified T : Any> withLoggingOff(block: () -> Unit) {
      val logger = LoggerFactory.getLogger(T::class.java) as Logger
      val level = logger.level
      logger.level = Level.OFF
      try {
        block()
      } finally {
        logger.level = level
      }
    }

    private const val ITERATIONS = 30
    private const val INVOCATIONS = 300
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 4
  }
}

@Param(name = "agent", gen = IntGen::class, conf = "0:4")
@Param(name = "path", gen = IntGen::class, conf = "0:2")
class ProxyPathManagerOps {
  private val contextManager = AgentContextManager(isTestMode = true)
  private val pathManager = ProxyPathManager(PROXY, isTestMode = true)

  private val agents =
    listOf(
      newAgent(TEAM_A, consolidated = false),
      newAgent(TEAM_A, consolidated = false),
      newAgent(TEAM_B, consolidated = false),
      newAgent(TEAM_A, consolidated = true),
      newAgent(TEAM_B, consolidated = true),
    ).onEach { contextManager.addAgentContext(it) }

  private fun newAgent(
    identity: String,
    consolidated: Boolean,
  ) = AgentContext("lincheck", authIdentityName = identity).apply {
    assignProperties(registerAgentRequest { this.consolidated = consolidated })
  }

  // ProxyServiceImpl.registerPath: an agent whose context is gone is refused before addPath.
  @Operation
  fun register(
    @Param(name = "agent") agent: Int,
    @Param(name = "path") path: Int,
  ) {
    val context = contextManager.getAgentContext(agents[agent].agentId) ?: return
    pathManager.addPath(PATHS[path], "{}", context, identityName = context.authIdentityName)
  }

  @Operation
  fun unregister(
    @Param(name = "agent") agent: Int,
    @Param(name = "path") path: Int,
  ) {
    pathManager.removePath(PATHS[path], agents[agent].agentId)
  }

  // Proxy.removeAgentContext, for a transport that closed or an agent evicted as stale.
  @Operation
  fun disconnect(
    @Param(name = "agent") agent: Int,
  ) {
    val agentId = agents[agent].agentId
    contextManager.removeFromContextManager(agentId, "disconnected")
    pathManager.removeFromPathManager(agentId, "disconnected")
  }

  @Validate
  fun validate() {
    val paths = pathManager.allPathContextInfos()

    paths.forEach { (path, info) ->
      info.agentContexts.forEach { context ->
        check(context.isValid()) { "Path /$path still lists agent ${context.agentId}, which is no longer valid" }
        check(context.consolidated == info.isConsolidated) {
          "Path /$path mixes consolidated and exclusive agents: ${info.agentContexts.map { it.agentId }}"
        }
        check(context.authIdentityName == info.identityName) {
          "Path /$path of identity '${info.identityName}' is served by agent ${context.agentId} of " +
            "'${context.authIdentityName}'"
        }
      }
      if (!info.isConsolidated)
        check(info.agentContexts.size == 1) { "Exclusive path /$path has ${info.agentContexts.size} agents" }
    }

    agents.forEach { agent ->
      val served = paths.count { (_, info) -> info.agentContexts.any { it.agentId == agent.agentId } }
      val counted = pathManager.pathCountFor(agent.agentId)
      check(counted == served) { "Agent ${agent.agentId} is counted as serving $counted paths but serves $served" }
      check(served <= MAX_PATHS_PER_AGENT) { "Agent ${agent.agentId} serves $served paths, over $MAX_PATHS_PER_AGENT" }
    }
  }

  companion object {
    private const val MAX_PATHS_PER_AGENT = 2
    private const val TEAM_A = "team-a"
    private const val TEAM_B = "team-b"
    private val PATHS = listOf("p0", "p1", "p2")

    private val PROXY by lazy {
      Proxy(
        options = ProxyOptions(listOf("-Dproxy.internal.maxPathsPerAgent=$MAX_PATHS_PER_AGENT")),
        inProcessServerName = "proxy-path-manager-lincheck",
        testMode = true,
      )
    }
  }
}
