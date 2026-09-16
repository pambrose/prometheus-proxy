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
import io.prometheus.common.Messages.BLANK_AGENT_ID_MSG
import io.prometheus.common.Messages.BLANK_PATH_MSG
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.PathRejectionCause.CONSOLIDATION_MISMATCH
import io.prometheus.grpc.PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
import io.prometheus.grpc.PathRejectionCause.INVALID_AGENT
import io.prometheus.grpc.PathRejectionCause.INVALID_PATH
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
    // Reported by the agent at registration. On a consolidated path these describe the FIRST
    // registrant only -- a later agent joining the path contributes its contexts but not its metadata,
    // exactly as labels has always behaved. Empty when the agent predates the fields.
    val targetUrl: String = "",
    val pathSource: String = "",
    // The auth identity the path's first registrant connected as, empty when agent auth is disabled. On a
    // consolidated path it is the identity every later agent must also present to join.
    val identityName: String = "",
  ) {
    fun isNotValid() = agentContexts.all { it.isNotValid() }
  }

  // Why addPath refused a path: the reason, for people, and the cause, which the agent decides from whether to retry.
  data class PathRejection(
    val reason: String,
    val cause: PathRejectionCause,
  )

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
   * @return null on success, or the [PathRejection] on failure.
   */
  fun addPath(
    path: String,
    labels: String,
    agentContext: AgentContext,
    targetUrl: String = "",
    pathSource: String = "",
    identityName: String = "",
  ): PathRejection? {
    require(path.isNotBlank()) { BLANK_PATH_MSG }
    // Redacted on the way in so the dashboard and /debug never show credentials, even from an agent that
    // predates agent-side redaction.
    return multiSegmentPathError(path)?.let { PathRejection(it, INVALID_PATH) }
      ?: addValidatedPath(path, labels, agentContext, sanitizeUrl(targetUrl), pathSource, identityName)
  }

  // Logs why a path was refused and returns the rejection. The first time this connection is told a cause for a
  // path it is logged at WARN; an identical repeat -- an agent retrying a rejection that can clear -- at DEBUG, so
  // a lasting conflict is reported once rather than on every retry. Mirrors AgentPathManager.logRejection.
  private fun rejectPath(
    agentContext: AgentContext,
    path: String,
    reason: String,
    cause: PathRejectionCause,
  ): PathRejection {
    if (agentContext.recordRejection(path, cause)) logger.warn { reason } else logger.debug { reason }
    return PathRejection(reason, cause)
  }

  @Suppress("ReturnCount")
  private fun addValidatedPath(
    path: String,
    labels: String,
    agentContext: AgentContext,
    targetUrl: String,
    pathSource: String,
    identityName: String,
  ): PathRejection? {
    synchronized(pathMap) {
      // Re-check validity inside the lock: agent removal (transportTerminated / cleanup eviction) can
      // interleave between the caller's out-of-lock getAgentContext() check and here, invalidating the
      // context. Inserting a path for an invalidated context creates a permanently-dead path that no
      // cleanup sweeps, so reject it and let the agent re-register on reconnect (finding 7).
      if (agentContext.isNotValid()) {
        val reason = "Agent context ${agentContext.agentId} was invalidated during registration of /$path"
        return rejectPath(agentContext, path, reason, INVALID_AGENT)
      }

      val agentInfo = pathMap[path]
      if (agentInfo != null && agentInfo.isConsolidated != agentContext.consolidated) {
        val reason =
          if (agentContext.consolidated)
            "Consolidated agent rejected for non-consolidated path /$path"
          else
            "Non-consolidated agent rejected for consolidated path /$path"
        return rejectPath(agentContext, path, reason, CONSOLIDATION_MISMATCH)
      }

      // The path's agents other than this one. An agent re-registering a path it already backs neither conflicts with
      // nor displaces itself.
      val others = agentInfo?.agentContexts.orEmpty().filterNot { it.agentId == agentContext.agentId }
      // While a live agent serves a path, only an agent of the same auth identity may take it over or, on a
      // consolidated path, join it: a redeploy reclaims its paths at once, but one identity can neither replace
      // another's metrics nor merge its own into them. With no agent auth, or only the legacy shared token, every agent
      // has the same identity, so nothing changes there. A path whose agents are no longer valid is nobody's, which is
      // how such a conflict clears.
      if (agentInfo != null && agentInfo.identityName != identityName && others.any { it.isValid() }) {
        val action = if (agentContext.consolidated) "join it" else "take it over"
        val reason =
          "Path /$path is served by identity '${agentInfo.identityName}'; identity '$identityName' cannot $action"
        return rejectPath(agentContext, path, reason, HELD_BY_ANOTHER_IDENTITY)
      }

      if (agentContext.consolidated) {
        if (agentInfo == null) {
          pathMap[path] = AgentContextInfo(true, labels, [agentContext], targetUrl, pathSource, identityName)
        } else {
          // An agent re-registering a path it already backs (a path listed twice in its config) replaces its own
          // entry: a second copy would send it two requests per scrape, and Prometheus rejects the duplicate samples.
          val contexts = agentInfo.agentContexts
          val updated =
            if (contexts.any { it.agentId == agentContext.agentId })
              contexts.map { if (it.agentId == agentContext.agentId) agentContext else it }
            else
              contexts + agentContext
          // The path takes this agent's identity, which differs only when every earlier agent is gone.
          pathMap[path] = agentInfo.copy(agentContexts = updated, identityName = identityName)
        }
      } else {
        // Every other agent on the path is displaced; re-registering its own path is neither logged nor counted.
        val displacedContexts = others
        if (displacedContexts.isNotEmpty()) {
          logger.info { "Overwriting path /$path for ${displacedContexts.first()}" }
          proxy.metrics { agentDisplacementCount.inc() }
        }
        pathMap[path] = AgentContextInfo(false, labels, [agentContext], targetUrl, pathSource, identityName)

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

      agentContext.forgetRejection(path)
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
    require(path.isNotBlank()) { BLANK_PATH_MSG }
    require(agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }

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
        // The path's last registration is gone, so its per-path metric series go with it.
        proxy.metrics { removePathSeries(path) }
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
    require(agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }

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
        // the dashboard would never be woken for it, and unlike the removal case below there is no later
        // event to self-correct from.
        proxy.eventBus.emit(ProxyEvent.PathUnregistered(k, agentId))
      }

      keysToRemove.forEach { k ->
        pathMap.remove(k)
          ?.also {
            removedPathCount++
            proxy.metrics { removePathSeries(k) }
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
