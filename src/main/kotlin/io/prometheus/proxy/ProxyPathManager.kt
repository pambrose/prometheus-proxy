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

package io.prometheus.proxy

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Proxy
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.grpc.unregisterPathResponse

/**
 * Maps scrape URL paths to their registered [AgentContext] instances.
 *
 * Maintains a synchronized path map that supports both exclusive (one agent per path) and
 * consolidated (multiple agents per path) registration modes. Handles path addition, removal,
 * agent displacement, and cleanup on agent disconnect. Provides the service discovery path list
 * and plain-text diagnostics.
 *
 * @param proxy the parent [Proxy] instance
 * @param isTestMode when true, suppresses verbose logging during tests
 * @see AgentContext
 * @see AgentContextManager
 */
internal class ProxyPathManager(
  private val proxy: Proxy,
  private val isTestMode: Boolean,
) {
  data class AgentContextInfo(
    val isConsolidated: Boolean,
    val labels: String,
    // Immutable: mutations replace the pathMap entry with a copy() carrying a new list, so a
    // data-class copy() can never share a still-mutating list with the stored entry.
    val agentContexts: List<AgentContext>,
  ) {
    fun isNotValid() = agentContexts.all { it.isNotValid() }
  }

  private val pathMap = HashMap<String, AgentContextInfo>()

  // AgentContextInfo is deeply immutable and mutations replace the pathMap entry (never mutate in
  // place), so returning the stored instance is already an effective snapshot — no defensive copy.
  fun getAgentContextInfo(path: String): AgentContextInfo? = synchronized(pathMap) { pathMap[path] }

  val pathMapSize: Int
    get() = synchronized(pathMap) { pathMap.size }

  val allPaths: List<String>
    get() = synchronized(pathMap) { pathMap.keys.toList() }

  // Copy only the map to snapshot the key set under the lock; the immutable values can be shared.
  fun allPathContextInfos(): Map<String, AgentContextInfo> = synchronized(pathMap) { pathMap.toMap() }

  /**
   * Adds a path to the path map for the given agent context.
   *
   * @return null on success, or a failure reason string on failure.
   */
  fun addPath(
    path: String,
    labels: String,
    agentContext: AgentContext,
  ): String? {
    require(path.isNotEmpty()) { EMPTY_PATH_MSG }
    return multiSegmentPathError(path) ?: addValidatedPath(path, labels, agentContext)
  }

  @Suppress("ReturnCount")
  private fun addValidatedPath(
    path: String,
    labels: String,
    agentContext: AgentContext,
  ): String? {
    synchronized(pathMap) {
      // Re-check validity inside the lock: agent removal (transportTerminated / cleanup eviction) can
      // interleave between the caller's out-of-lock getAgentContext() check and here, invalidating the
      // context. Inserting a path for an invalidated context creates a permanently-dead path that no
      // cleanup sweeps, so reject it and let the agent re-register on reconnect (finding 7).
      if (agentContext.isNotValid()) {
        val reason = "Agent context ${agentContext.agentId} was invalidated during registration of /$path"
        logger.warn { reason }
        return reason
      }

      val agentInfo = pathMap[path]
      if (agentContext.consolidated) {
        if (agentInfo == null) {
          pathMap[path] = AgentContextInfo(true, labels, [agentContext])
        } else {
          if (agentContext.consolidated != agentInfo.isConsolidated) {
            val reason = "Consolidated agent rejected for non-consolidated path /$path"
            logger.error { reason }
            return reason
          }
          pathMap[path] = agentInfo.copy(agentContexts = agentInfo.agentContexts + agentContext)
        }
      } else {
        if (agentInfo != null && agentInfo.isConsolidated) {
          val reason = "Non-consolidated agent rejected for consolidated path /$path"
          logger.error { reason }
          return reason
        }
        val displacedContexts = agentInfo?.agentContexts ?: emptyList()
        if (agentInfo != null) {
          logger.info { "Overwriting path /$path for ${agentInfo.agentContexts.firstOrNull()}" }
          proxy.metrics { agentDisplacementCount.inc() }
        }
        pathMap[path] = AgentContextInfo(false, labels, [agentContext])

        // Invalidate displaced agent contexts that have no other registered paths.
        // Even live agents are invalidated here — a displaced agent with zero paths
        // would otherwise stay alive indefinitely via heartbeats, consuming resources.
        // The agent will reconnect and re-register its paths if needed.
        displacedContexts.forEach { displacedContext ->
          val hasOtherPaths = pathMap.any { (_, v) ->
            v.agentContexts.any { it.agentId == displacedContext.agentId }
          }
          if (!hasOtherPaths) {
            logger.info { "Invalidating orphaned $displacedContext after path /$path was overwritten" }
            displacedContext.invalidate()
          }
        }
      }

      if (!isTestMode) logger.info { "Added path /$path for $agentContext" }
      // Inside synchronized(pathMap) on purpose: tryEmit never suspends or blocks, so publishing here
      // cannot stall a registration, and the event is emitted only once the map actually reflects it.
      proxy.eventBus.emit(ProxyEvent.PathRegistered(path, agentContext.agentId))
    }
    return null
  }

  // The scrape route is registered as get("/*"), which matches exactly one path segment. A path with
  // an embedded slash (e.g. "app/metrics") would be advertised in service discovery yet 404 at scrape
  // time, so reject it at registration. A single leading slash is tolerated because the agent may or
  // may not have stripped it. Returns a failure reason, or null when the path is a single segment.
  private fun multiSegmentPathError(path: String): String? {
    val normalized = path.removePrefix("/")
    if ('/' !in normalized) return null
    return "Multi-segment path not supported (use a single path segment): /$normalized"
      .also { logger.error { it } }
  }

  fun removePath(
    path: String,
    agentId: String,
  ): UnregisterPathResponse {
    require(path.isNotEmpty()) { EMPTY_PATH_MSG }
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }

    synchronized(pathMap) {
      val agentInfo = pathMap[path]
      if (agentInfo == null) {
        val msg = "Unable to remove path /$path - path not found"
        logger.error { msg }
        return unregisterPathResponse {
          valid = false
          reason = msg
        }
      }

      val agentContext = agentInfo.agentContexts.firstOrNull { it.agentId == agentId }
      if (agentContext == null) {
        val agentIds = agentInfo.agentContexts.joinToString(", ") { it.agentId }
        val msg = "Unable to remove path /$path - invalid agentId: $agentId -- [$agentIds]"
        logger.error { msg }
        return unregisterPathResponse {
          valid = false
          reason = msg
        }
      }

      if (agentInfo.isConsolidated && agentInfo.agentContexts.size > 1) {
        val updated = agentInfo.copy(agentContexts = agentInfo.agentContexts.filterNot { it.agentId == agentId })
        pathMap[path] = updated
        if (!isTestMode)
          logger.info { "Removed element of path /$path for $updated" }
      } else {
        pathMap.remove(path)
        if (!isTestMode)
          logger.info { "Removed path /$path for $agentInfo" }
      }
      proxy.eventBus.emit(ProxyEvent.PathUnregistered(path, agentId))
      return unregisterPathResponse {
        valid = true
        reason = ""
      }
    }
  }

  // This is called on agent disconnects
  fun removeFromPathManager(
    agentId: String,
    reason: String,
  ) {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }

    // Always sweep the pathMap by agentId, even when the context is already gone from the manager: a
    // registerPath that raced this removal can strand a path pointing at an invalidated context, and
    // skipping the sweep would leave that path 404ing forever (finding 7). The caller invalidates the
    // context before calling this, so the context is normally already absent from the manager here —
    // report on what the sweep actually removed rather than on a manager lookup that no longer
    // distinguishes a live disconnect from a repeat call.
    var removedPathCount = 0
    synchronized(pathMap) {
      // Collect map mutations in a first pass to avoid modifying the map during iteration.
      val keysToRemove: MutableList<String> = []
      val keysToUpdate: MutableMap<String, AgentContextInfo> = mutableMapOf()
      pathMap.forEach { (k, v) ->
        if (v.agentContexts.size == 1) {
          if (v.agentContexts[0].agentId == agentId)
            keysToRemove += k
        } else {
          val filtered = v.agentContexts.filterNot { it.agentId == agentId }
          if (filtered.size != v.agentContexts.size) {
            logger.info { "Removed agentId $agentId from consolidated path /$k" }
            if (filtered.isEmpty())
              keysToRemove += k
            else
              keysToUpdate[k] = v.copy(agentContexts = filtered)
          }
        }
      }

      keysToUpdate.forEach { (k, v) ->
        pathMap[k] = v
        // A consolidated path surviving the loss of one agent is still a topology change. Without this
        // the UI would never be woken for it, and unlike the removal case below there is no later
        // event to self-correct from.
        proxy.eventBus.emit(ProxyEvent.PathUnregistered(k, agentId))
      }

      keysToRemove.forEach { k ->
        pathMap.remove(k)
          ?.also {
            removedPathCount++
            if (!isTestMode)
              logger.info { "Removed path /$k for $it" }
            proxy.eventBus.emit(ProxyEvent.PathUnregistered(k, agentId))
          } ?: logger.warn { "Missing path /$k for agentId: $agentId" }
      }
    }

    if (removedPathCount == 0)
      logger.debug { "No paths registered for agentId: $agentId ($reason)" }
    else
      logger.info { "Removed $removedPathCount path(s) for agentId: $agentId ($reason)" }
  }

  fun toPlainText(): String =
    synchronized(pathMap) {
      if (pathMap.isEmpty()) {
        "No agents connected."
      } else {
        val maxPath = pathMap.keys.maxOfOrNull { it.length } ?: 0
        "Proxy Path Map:\n" + "Path".padEnd(maxPath + 2) + "Agent Context\n" +
          pathMap
            .toSortedMap()
            .map { c -> "/${c.key.padEnd(maxPath)} ${c.value.agentContexts.size} ${c.value}" }
            .joinToString("\n\n")
      }
    }

  companion object {
    private val logger = logger {}
  }
}
