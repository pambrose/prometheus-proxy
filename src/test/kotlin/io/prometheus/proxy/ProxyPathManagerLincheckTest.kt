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
import io.prometheus.grpc.PathNotice
import io.prometheus.grpc.PathRejectionCause.HELD_BY_ANOTHER_AGENT
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.proxy.ProxyPathManager.AgentContextInfo
import io.prometheus.proxy.ProxyPathManager.PathMetadata
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration

// Lincheck races ProxyPathManager's path registration, takeovers, and reclaims against unregistering and against agent
// removal, on the real code the TLA+ spec (specs/tla/ProxyRegistry.tla) models. Registration does what
// ProxyServiceImpl.registerPath does -- look the agent's context up outside the path-map lock, then addPath -- and a
// reclaim does the same with the reclaim flag, which an agent sends for a path it was told another agent took.
// Removal does what Proxy.removeAgentContext does: remove and invalidate the context, then sweep its paths. Seven
// agents share three paths, each serving at most two. Four are exclusive agents of team-a, which may take over each
// other's paths: two launches that read path notices, a context of the first's launch (the same agent reconnected while
// the proxy still holds its old context, which a takeover of its own launch invalidates once it has no paths), and an
// agent predating path notices, which is never told and never waits. An exclusive agent of team-b may not take team-a's
// paths, and a consolidated agent of each team may not share a path with an exclusive one.
//
// Each reclaim checks, as addPath decides it, that it takes no path from a live agent of another launch (the TLA+
// spec's ReclaimNeverDisplaces), and that it is refused as held by another agent only while such an agent serves the
// path. Once every operation has finished, validate checks the spec's invariants:
//  - NoStrandedPath: every agent a path lists is still valid (finding 7: none left on a removed agent);
//  - PathCountsAgree: pathCountFor, which enforces the limit and decides when a displaced agent is orphaned, matches
//    the path map, and never exceeds the limit;
//  - an exclusive path has one agent, consolidated and exclusive agents never share a path, and every valid agent on a
//    path has the path's identity. Only a consolidated join can break that last one: a refused exclusive takeover
//    leaves no trace in the path map, as an allowed one replaces the path's only agent;
//  - InvalidatedHasNoNotices: an invalidated agent, removed or orphaned, holds no unread path notices, though
//    queuePathNotice races invalidate();
//  - the waiting lists, which the TLA+ spec leaves out: each agent waiting on a path reads notices, waits once, is of
//    another launch than the agent serving it, is still connected (a removed agent is taken off every list), and, while
//    valid, has an unread notice about the path; and no agent has an unread notice about a path it serves.
//
// A waiting agent may be invalid: one orphaned by its own launch's takeover of another path stays on the list until its
// connection is removed, which is harmless, since queuePathNotice refuses it and the path's next change drops it.
//
// One real Proxy, never started, serves every run: a MockK stand-in retained every call it recorded, thousands of runs'
// worth, and ran the test JVM out of heap. Stress only, since a Proxy is far more than model checking can replay.
class ProxyPathManagerLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "path registration, takeovers, and reclaims keep the path map consistent under stress" {
      // Rejections, displacements, and refused reclaims log at WARN and INFO, on every one of thousands of runs.
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

@Param(name = "agent", gen = IntGen::class, conf = "0:6")
@Param(name = "path", gen = IntGen::class, conf = "0:2")
class ProxyPathManagerOps {
  private val contextManager = AgentContextManager(isTestMode = true)
  private val pathManager = ProxyPathManager(PROXY, isTestMode = true)

  // Held around every path-map change, so a reclaim sees who serves the path as addPath decides; see reclaim. It
  // serializes nothing the path map's own lock doesn't: those calls do all their shared work under it.
  private val pathLock = Any()
  private val violations = ConcurrentLinkedQueue<String>()

  private val agents =
    listOf(
      newAgent(TEAM_A, LAUNCH_A1),
      newAgent(TEAM_A, LAUNCH_A2),
      newAgent(TEAM_A, LAUNCH_A1),
      newAgent(TEAM_A, LAUNCH_A3, pathNotices = false),
      newAgent(TEAM_B, LAUNCH_B),
      newAgent(TEAM_A, LAUNCH_CA, consolidated = true),
      newAgent(TEAM_B, LAUNCH_CB, consolidated = true),
    ).onEach { contextManager.addAgentContext(it) }

  private fun newAgent(
    identity: String,
    launch: String,
    consolidated: Boolean = false,
    pathNotices: Boolean = true,
  ) = AgentContext("lincheck", authIdentityName = identity).apply {
    assignProperties(
      registerAgentRequest {
        launchId = launch
        this.consolidated = consolidated
        this.pathNotices = pathNotices
      },
    )
  }

  // ProxyServiceImpl.registerPath: an agent whose context is gone is refused before addPath.
  @Operation
  fun register(
    @Param(name = "agent") agent: Int,
    @Param(name = "path") path: Int,
  ) {
    val context = contextManager.getAgentContext(agents[agent].agentId) ?: return
    synchronized(pathLock) {
      pathManager.addPath(PATHS[path], "{}", context, PathMetadata(identityName = context.authIdentityName))
    }
  }

  // registerPath with the reclaim flag. Any agent may send it, as the proxy can't tell an agent predating notices from
  // one that ignores them.
  @Operation
  fun reclaim(
    @Param(name = "agent") agent: Int,
    @Param(name = "path") path: Int,
  ) {
    val context = contextManager.getAgentContext(agents[agent].agentId) ?: return
    val key = PATHS[path]
    synchronized(pathLock) {
      // No other path-map change comes between this look and addPath's decision. Validity can change, as disconnect
      // invalidates outside the lock, but a context never becomes valid again: one valid after the call was valid as it
      // decided, and one invalid before the call was invalid then too. The call invalidates only agents of the
      // reclaimer's own launch.
      val otherLaunches =
        pathManager.getAgentContextInfo(key)?.agentContexts.orEmpty().filter { it.launchId != context.launchId }
      val liveBefore = otherLaunches.filter { it.isValid() }
      val metadata = PathMetadata(identityName = context.authIdentityName, reclaim = true)
      val rejection = pathManager.addPath(key, "{}", context, metadata)
      val liveAfter = otherLaunches.filter { it.isValid() }
      val id = context.agentId
      if (rejection == null && !context.consolidated && liveAfter.isNotEmpty())
        violations += "Agent $id's reclaim took /$key from ${liveAfter.ids()}, live and of another launch"
      if (rejection?.cause == HELD_BY_ANOTHER_AGENT && liveBefore.isEmpty())
        violations += "Agent $id's reclaim of /$key was refused as held, with no live agent of another launch on it"
    }
  }

  @Operation
  fun unregister(
    @Param(name = "agent") agent: Int,
    @Param(name = "path") path: Int,
  ) {
    synchronized(pathLock) {
      pathManager.removePath(PATHS[path], agents[agent].agentId)
    }
  }

  // Proxy.removeAgentContext, for a transport that closed or an agent evicted as stale.
  @Operation
  fun disconnect(
    @Param(name = "agent") agent: Int,
  ) {
    val agentId = agents[agent].agentId
    contextManager.removeFromContextManager(agentId, RemovalReason.TERMINATION)
    synchronized(pathLock) {
      pathManager.removeFromPathManager(agentId, RemovalReason.TERMINATION)
    }
  }

  @Validate
  fun validate() {
    check(violations.isEmpty()) { violations.joinToString("; ") }
    val paths = pathManager.allPathContextInfos()
    // Read once, since reading them takes them.
    val unread = agents.associate { it.agentId to unreadNotices(it) }

    paths.forEach { (path, info) ->
      info.agentContexts.forEach { context ->
        check(context.isValid()) { "Path /$path still lists agent ${context.agentId}, which is no longer valid" }
        check(context.consolidated == info.isConsolidated) {
          "Path /$path mixes consolidated and exclusive agents: ${info.agentContexts.ids()}"
        }
        check(context.authIdentityName == info.identityName) {
          "Path /$path of identity '${info.identityName}' is served by agent ${context.agentId} of " +
            "'${context.authIdentityName}'"
        }
      }
      if (!info.isConsolidated)
        check(info.agentContexts.size == 1) { "Exclusive path /$path has ${info.agentContexts.size} agents" }
      validateWaiting(path, info, unread)
    }

    agents.forEach { agent ->
      val served = paths.filterValues { it.serves(agent) }.keys
      val counted = pathManager.pathCountFor(agent.agentId)
      check(counted == served.size) {
        "Agent ${agent.agentId} is counted as serving $counted paths but serves ${served.size}"
      }
      check(served.size <= MAX_PATHS_PER_AGENT) {
        "Agent ${agent.agentId} serves ${served.size} paths, over $MAX_PATHS_PER_AGENT"
      }
      validateNotices(agent, served, unread.getValue(agent.agentId))
    }
  }

  // The agents waiting on [path], whose entry is [info], to be told once no agent serves it. [unread] holds each
  // agent's unread notices.
  private fun validateWaiting(
    path: String,
    info: AgentContextInfo,
    unread: Map<String, List<PathNotice>>,
  ) {
    val waiting = info.waiting
    check(waiting.distinctBy { it.agentId }.size == waiting.size) {
      "Path /$path lists an agent waiting on it twice: ${waiting.ids()}"
    }
    waiting.forEach { waiter ->
      val id = waiter.agentId
      check(waiter.acceptsPathNotices) { "Agent $id, which doesn't read path notices, waits on /$path" }
      check(info.agentContexts.none { it.launchId == waiter.launchId }) {
        "Agent $id waits on /$path, which an agent of its own launch serves: ${info.agentContexts.ids()}"
      }
      check(contextManager.getAgentContext(id) != null) { "Agent $id waits on /$path after it was removed" }
      check(!waiter.isValid() || unread.getValue(id).any { it.path == path }) {
        "Agent $id waits on /$path with no unread notice about it"
      }
    }
  }

  // [agent]'s [notices], unread, given the paths it [served].
  private fun validateNotices(
    agent: AgentContext,
    served: Set<String>,
    notices: List<PathNotice>,
  ) {
    val id = agent.agentId
    val noticePaths = notices.map { it.path }
    check(agent.isValid() || notices.isEmpty()) { "Agent $id, invalidated, holds notices about $noticePaths" }
    check(agent.acceptsPathNotices || notices.isEmpty()) {
      "Agent $id, which doesn't read path notices, was sent notices about $noticePaths"
    }
    check(noticePaths.none { it in served }) { "Agent $id serves $served but has unread notices about $noticePaths" }
  }

  // The notices [agent] holds unread, which this takes: at once, as an empty list when there are none, and as null,
  // here empty, once the context is invalidated with none left.
  private fun unreadNotices(agent: AgentContext): List<PathNotice> =
    runBlocking { agent.awaitPathNotices(Duration.ZERO) }.orEmpty()

  private fun List<AgentContext>.ids() = map { it.agentId }

  companion object {
    private const val MAX_PATHS_PER_AGENT = 2
    private const val TEAM_A = "team-a"
    private const val TEAM_B = "team-b"
    private const val LAUNCH_A1 = "launch-a1"
    private const val LAUNCH_A2 = "launch-a2"
    private const val LAUNCH_A3 = "launch-a3"
    private const val LAUNCH_B = "launch-b"
    private const val LAUNCH_CA = "launch-ca"
    private const val LAUNCH_CB = "launch-cb"
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
