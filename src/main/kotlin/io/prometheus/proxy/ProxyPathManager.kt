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
import io.prometheus.common.AgentValues.pathCharacterProblem
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.common.AgentValues.truncate
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.PathRejectionCause.CONSOLIDATION_MISMATCH
import io.prometheus.grpc.PathRejectionCause.HELD_BY_ANOTHER_AGENT
import io.prometheus.grpc.PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
import io.prometheus.grpc.PathRejectionCause.INVALID_AGENT
import io.prometheus.grpc.PathRejectionCause.INVALID_PATH
import io.prometheus.grpc.PathRejectionCause.PATH_LIMIT_REACHED
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.grpc.pathNotice
import io.prometheus.grpc.unregisterPathResponse
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.proxy.ProxyConstants.FAVICON_FILENAME
import kotlinx.serialization.json.JsonObject

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
    // As the agent sent them, for the dashboard and /debug.
    val labels: String,
    // Immutable: mutations replace the pathMap entry with a copy() carrying a new list, so a
    // data-class copy() can never share a still-mutating list with the stored entry.
    val agentContexts: List<AgentContext>,
    // Reported by the agent at registration: the target and source of the path's first agent, the one the dashboard
    // names, which follow it when it leaves (see agentTargets). Empty when the agent predates the fields.
    val targetUrl: String = "",
    val pathSource: String = "",
    // The auth identity the path's first registrant connected as, empty when agent auth is disabled. On a
    // consolidated path it is the identity every later agent must also present to join.
    val identityName: String = "",
    // [labels] as service discovery serves them, parsed and filtered once, when the path registered, which each
    // service-discovery request only copies (November #15). See Proxy.serviceDiscoveryLabels.
    val sdLabels: JsonObject = JsonObject(emptyMap()),
    // Each agent's own target and source, by agentId, one per agent on the path: on a consolidated path every agent
    // scrapes its own target. Kept so targetUrl and pathSource can follow the first agent when another becomes it,
    // which they once didn't, leaving a departed agent's URL beside the agent still serving the path. Labels, which
    // service discovery serves, stay the first registrant's.
    val agentTargets: Map<String, PathTarget> = emptyMap(),
    // The agents of other launches this path was taken from, or whose reclaim it refused, which are told once no agent
    // serves it, so each can take it back; see takeOverPath. Only agents that read path notices, at most
    // MAX_WAITERS_PER_PATH of them.
    val waiting: List<AgentContext> = emptyList(),
  ) {
    fun isNotValid() = agentContexts.all { it.isNotValid() }

    // Whether [agentContext] is among the path's agents.
    fun serves(agentContext: AgentContext) = agentContexts.any { it.agentId == agentContext.agentId }

    // This entry without [agentId] among the agents waiting on it, or this entry when it isn't.
    fun withoutWaiter(agentId: String): AgentContextInfo {
      if (waiting.none { it.agentId == agentId })
        return this
      return copy(waiting = waiting.filterNot { it.agentId == agentId })
    }

    // The data-class form without agentTargets or waiting. /debug prints every entry inside the pathMap lock, and every
    // agent's target, each up to maxTargetUrlLength, would multiply what it prints; the first agent's is targetUrl
    // already.
    override fun toString() =
      "AgentContextInfo(isConsolidated=$isConsolidated, labels=$labels, agentContexts=$agentContexts, " +
        "targetUrl=$targetUrl, pathSource=$pathSource, identityName=$identityName, sdLabels=$sdLabels)"

    // This entry with targetUrl and pathSource taken from its first agent. Unchanged when that agent's target isn't
    // known, as for an entry built without agentTargets.
    fun showingFirstAgent(): AgentContextInfo {
      val target = agentContexts.firstOrNull()?.let { agentTargets[it.agentId] } ?: return this
      return copy(targetUrl = target.targetUrl, pathSource = target.pathSource)
    }
  }

  // One agent's target URL and path source for a path, as its registration reported them, after bounding.
  data class PathTarget(
    val targetUrl: String,
    val pathSource: String,
  )

  // Why addPath refused a path: the reason, for people, and the cause, which the agent decides from whether to retry.
  data class PathRejection(
    val reason: String,
    val cause: PathRejectionCause,
  )

  // What a registration says about a path besides its labels; see the AgentContextInfo fields of the same names.
  data class PathMetadata(
    val targetUrl: String = "",
    val pathSource: String = "",
    val identityName: String = "",
    // Register the path only if no live agent of another launch serves it; see reclaimRejection.
    val reclaim: Boolean = false,
  )

  private val pathMap = HashMap<String, AgentContextInfo>()

  // How many paths each agent serves, by agentId; guarded by pathMap. Kept in step with every change to pathMap, so
  // enforcing proxy.internal.maxPathsPerAgent -- and finding an agent left with no paths -- needs no scan of the map.
  private val pathCounts = HashMap<String, Int>()

  // Callers MUST hold pathMap.
  private fun countPaths(
    agentId: String,
    delta: Int,
  ) {
    val count = (pathCounts[agentId] ?: 0) + delta
    if (count > 0) pathCounts[agentId] = count else pathCounts.remove(agentId)
  }

  // The number of paths [agentId] serves. Callers that already hold pathMap take it again, which the JVM allows.
  internal fun pathCountFor(agentId: String): Int = synchronized(pathMap) { pathCounts[agentId] ?: 0 }

  // AgentContextInfo is deeply immutable and mutations replace the pathMap entry (never mutate in
  // place), so returning the stored instance is already an effective snapshot — no defensive copy.
  // Paths are keyed without a leading slash, the form the scrape route looks them up in; see pathKey.
  fun getAgentContextInfo(path: String): AgentContextInfo? = synchronized(pathMap) { pathMap[pathKey(path)] }

  val pathMapSize: Int
    get() = synchronized(pathMap) { pathMap.size }

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
    metadata: PathMetadata = PathMetadata(),
  ): PathRejection? {
    require(path.isNotBlank()) { BLANK_PATH_MSG }
    formRejection(path, labels, agentContext)?.let { return it }
    val key = pathKey(path)
    // What boundedMetadata cut, for the warning: field names and sizes, never the values.
    val cut = mutableListOf<String>()
    val bounded = boundedMetadata(metadata, cut)
    val sdLabels = Proxy.serviceDiscoveryLabels(labels, proxy.reserveJobAndInstanceLabels)
    val entry =
      AgentContextInfo(
        isConsolidated = agentContext.consolidated,
        labels = labels,
        agentContexts = [agentContext],
        targetUrl = bounded.targetUrl,
        pathSource = bounded.pathSource,
        identityName = bounded.identityName,
        sdLabels = sdLabels.served,
        agentTargets = mapOf(agentContext.agentId to PathTarget(bounded.targetUrl, bounded.pathSource)),
      )
    return addValidatedPath(key, agentContext, entry, bounded.reclaim).also { rejection ->
      // Warned only once the path is stored, so an agent retrying a refused registration isn't warned about each time.
      if (rejection == null)
        warnAboutRegistration(key, agentContext, sdLabels, cut)
    }
  }

  // Says once, as [path] registers, what of its registration won't be served as sent: the labels service discovery
  // leaves out, which each service-discovery request no longer repeats (November #15), and the metadata [cut] names.
  private fun warnAboutRegistration(
    path: String,
    agentContext: AgentContext,
    sdLabels: Proxy.SdLabels,
    cut: List<String>,
  ) {
    if (sdLabels.reserved.isNotEmpty()) {
      val reserved = sdLabels.reserved.joinToString(prefix = "[", postfix = "]") { shownValue(it) }
      logger.warn { "Agent labels $reserved for path /$path are reserved and won't appear in service discovery" }
    }
    // Left out of service discovery rather than refused: labels matter only there, and the path still serves scrapes.
    sdLabels.problem?.also {
      logger.warn { "Agent labels for path /$path won't all appear in service discovery: $it" }
    }
    if (cut.isNotEmpty())
      logger.warn {
        "Agent ${agentContext.agentId} registered path /$path with ${cut.joinToString()}, over " +
          "proxy.internal.maxTargetUrlLength (${proxy.maxTargetUrlLength}) or maxAgentFieldLength " +
          "(${proxy.maxAgentFieldLength}); keeping the start of each"
      }
  }

  // [metadata] as a path's entry keeps it. The target URL is redacted, so the dashboard and /debug never show
  // credentials, even from an agent that predates agent-side redaction, and then cut to
  // proxy.internal.maxTargetUrlLength characters; the path source is cut to maxAgentFieldLength; 0 leaves either whole.
  // Each was bounded only by gRPC's 4 MiB message limit and is shown on every dashboard push and /debug page, so about
  // 100 registrations exhausted the heap (November #12). The URL is redacted first: cut first, a URL whose userinfo ran
  // past the cut lost the "@" the redaction looks for, and kept the start of its password. Adds what it cut, and how
  // long it was, to [cut].
  private fun boundedMetadata(
    metadata: PathMetadata,
    cut: MutableList<String>,
  ): PathMetadata {
    fun bounded(
      field: String,
      value: String,
      max: Int,
    ): String = truncate(value, max).also { if (it.length < value.length) cut += "$field (${value.length} characters)" }

    return metadata.copy(
      targetUrl = bounded("targetUrl", sanitizeUrl(metadata.targetUrl), proxy.maxTargetUrlLength),
      pathSource = bounded("pathSource", metadata.pathSource, proxy.maxAgentFieldLength),
    )
  }

  /**
   * Returns why [path] can't be registered as given, or null when its form and size are fine: it names no path
   * segment, it is longer than `proxy.internal.maxPathLength`, its [labels] are larger than `maxLabelsSizeBytes`, it
   * has a character no scrape's request path can carry, or it has more than one segment.
   *
   * Each check is linear in the input and takes no lock, so `registerPath` runs them before it matches the path
   * against the caller's identity globs, which must never see a path of unbounded length (October #22). [addPath]
   * runs them as well. Every reason shows at most [SHOWN_PATH_CHARS] characters of the path, escaped.
   */
  fun formRejection(
    path: String,
    labels: String,
    agentContext: AgentContext,
  ): PathRejection? {
    val key = pathKey(path)
    val shown = shownPath(key)
    // A blank path, or "/", names no path the scrape route can reach.
    if (key.isBlank())
      return rejectPath(agentContext, shown, "Invalid path: /$shown (a path segment is required)", INVALID_PATH)
    return sizeRejection(key, shown, labels, agentContext)
      ?: characterRejection(key, shown, agentContext)
      ?: multiSegmentPathError(key, shown)?.let { reason ->
        noteRejection(agentContext, shown, reason, INVALID_PATH)
        PathRejection(reason, INVALID_PATH)
      }
      ?: reservedPathRejection(key, shown, agentContext)
  }

  // Rejects a path with a control character, whitespace, '?', or '#' (see AgentValues.pathCharacterProblem). No scrape
  // could reach one, though it was advertised in service discovery, and the path is logged at INFO as it registers,
  // where a line break in it let an agent forge log lines: a glob's '*' matches line terminators, so the globs let it
  // through (November #16). The agent refuses such a pathConfigs entry at startup.
  private fun characterRejection(
    key: String,
    shown: String,
    agentContext: AgentContext,
  ): PathRejection? =
    pathCharacterProblem(key)?.let { problem ->
      rejectPath(agentContext, shown, "Invalid path: /$shown ($problem)", INVALID_PATH)
    }

  // Rejects a path the scrape port answers itself: registered, it would be advertised in service discovery but could
  // never be scraped. The SD path returned the SD JSON, favicon.ico a 404, and the blitz path "42" (October #6).
  private fun reservedPathRejection(
    key: String,
    shown: String,
    agentContext: AgentContext,
  ): PathRejection? {
    val reserved =
      when {
        key == FAVICON_FILENAME -> "the favicon path"
        proxy.options.sdEnabled && key == pathKey(proxy.options.sdPath) -> "the service discovery path"
        proxy.isBlitzRequest(key) -> "the blitz path"
        else -> return null
      }
    return rejectPath(
      agentContext,
      shown,
      "Invalid path: /$shown is $reserved, which the proxy serves itself",
      INVALID_PATH,
    )
  }

  // Rejects a path longer than proxy.internal.maxPathLength, or labels larger than maxLabelsSizeBytes; 0 turns either
  // check off. Without them a path and its labels were bounded only by gRPC's 4 MiB message limit. A long path is
  // truncated to [shown] in the reason, and in the rejection this connection remembers, so it isn't kept whole.
  private fun sizeRejection(
    key: String,
    shown: String,
    labels: String,
    agentContext: AgentContext,
  ): PathRejection? {
    val maxLength = proxy.maxPathLength
    if (maxLength > 0 && key.length > maxLength) {
      val reason = "Path /$shown is ${key.length} characters, over proxy.internal.maxPathLength ($maxLength)"
      return rejectPath(agentContext, shown, reason, INVALID_PATH)
    }
    val maxLabels = proxy.maxLabelsSizeBytes
    val labelsSize = labels.encodeToByteArray().size
    if (maxLabels in 1..<labelsSize) {
      val reason = "Labels for path /$shown are $labelsSize bytes, over proxy.internal.maxLabelsSizeBytes ($maxLabels)"
      return rejectPath(agentContext, shown, reason, INVALID_PATH)
    }
    return null
  }

  // Rejects a path the agent doesn't serve yet once it serves proxy.internal.maxPathsPerAgent (0 = unlimited): an
  // identity limited to team-a-* could otherwise register unlimited matching paths, each adding a path-map entry, a
  // service-discovery target, and per-path metric series. Callers MUST hold pathMap.
  private fun pathLimitRejection(
    agentContext: AgentContext,
    path: String,
  ): PathRejection? {
    val max = proxy.maxPathsPerAgent
    val count = pathCountFor(agentContext.agentId)
    if (max !in 1..count)
      return null
    val reason =
      "Agent ${agentContext.agentId} already serves $count paths, the proxy.internal.maxPathsPerAgent limit ($max); " +
        "path /$path refused"
    return rejectPath(agentContext, path, reason, PATH_LIMIT_REACHED)
  }

  // Warns once as an agent's path count reaches 80% of maxPathsPerAgent, so an operator hears about it before its
  // registrations start failing. Callers MUST hold pathMap.
  private fun warnNearPathLimit(agentContext: AgentContext) {
    val max = proxy.maxPathsPerAgent
    val count = pathCountFor(agentContext.agentId)
    if (max > 0 && count == (max * 4 + 4) / 5)
      logger.warn {
        "Agent ${agentContext.agentId} serves $count of $max paths allowed by proxy.internal.maxPathsPerAgent"
      }
  }

  // Logs why a path was refused and returns the rejection. The first time this connection is told a cause for a
  // path it is logged at WARN; an identical repeat -- an agent retrying a rejection that can clear -- at DEBUG, so a
  // lasting conflict is reported once rather than on every retry. Mirrors AgentPathManager.logRejection.
  private fun rejectPath(
    agentContext: AgentContext,
    path: String,
    reason: String,
    cause: PathRejectionCause,
  ): PathRejection {
    if (noteRejection(agentContext, path, reason, cause)) logger.warn { reason } else logger.debug { reason }
    return PathRejection(reason, cause)
  }

  /**
   * Notes that [agentContext] is told [cause] for [path], and returns whether that is news: the first time, or a cause
   * other than last time (see [AgentContext.recordRejection]). News goes in the activity feed, so the feed shows each
   * rejection once, however often the agent retries. Every rejection of a path passes through here, those made outside
   * this class included, though each logs as it always has.
   */
  internal fun noteRejection(
    agentContext: AgentContext,
    path: String,
    reason: String,
    cause: PathRejectionCause,
  ): Boolean =
    agentContext.recordRejection(path, cause).also { news ->
      if (news) proxy.activityLog?.record(ActivityKind.PATH_REJECTED, agentContext, path, reason, group = cause.name)
    }

  // Registers [path] for [agentContext] unless a check made under the lock refuses it. [entry] is what addPath built
  // from the registration: the path's entry when the agent is its only one. [reclaim] is the registration's flag.
  private fun addValidatedPath(
    path: String,
    agentContext: AgentContext,
    entry: AgentContextInfo,
    reclaim: Boolean,
  ): PathRejection? {
    synchronized(pathMap) {
      val agentInfo = pathMap[path]
      // A path the agent already serves is a re-registration, which neither counts against its limit nor adds to it.
      val alreadyServes = agentInfo?.serves(agentContext) == true
      val rejection = lockedRejection(path, agentContext, entry, reclaim)
      if (rejection == null) {
        val displaced =
          if (entry.isConsolidated) {
            pathMap[path] = agentInfo?.joinedBy(agentContext, entry) ?: entry
            null
          } else {
            takeOverPath(path, agentContext, agentInfo, entry)
          }
        recordRegistration(path, agentContext, alreadyServes, displaced)
      }
      return rejection
    }
  }

  // Why a check that must hold the lock refuses [agentContext]'s registration of [path] as [entry], a [reclaim] or not,
  // or null. Callers MUST hold pathMap.
  private fun lockedRejection(
    path: String,
    agentContext: AgentContext,
    entry: AgentContextInfo,
    reclaim: Boolean,
  ): PathRejection? {
    val agentInfo = pathMap[path]
    return invalidatedRejection(agentContext, path)
      ?: agentInfo?.let {
        conflictRejection(path, agentContext, it, entry)
          ?: if (reclaim) reclaimRejection(path, agentContext, it, entry) else null
      }
      ?: if (agentInfo?.serves(agentContext) == true) null else pathLimitRejection(agentContext, path)
  }

  // Re-checks validity inside the lock: agent removal (transportTerminated / cleanup eviction) can
  // interleave between the caller's out-of-lock getAgentContext() check and here, invalidating the
  // context. Inserting a path for an invalidated context creates a permanently-dead path that no
  // cleanup sweeps, so reject it and let the agent re-register on reconnect (finding 7). Callers MUST hold pathMap.
  private fun invalidatedRejection(
    agentContext: AgentContext,
    path: String,
  ): PathRejection? {
    if (agentContext.isNotValid()) {
      val reason = "Agent context ${agentContext.agentId} was invalidated during registration of /$path"
      return rejectPath(agentContext, path, reason, INVALID_AGENT)
    }
    return null
  }

  // Rejects [entry]'s registration of [path], which [agentInfo] already holds, when the two disagree on consolidation,
  // or when another identity's live agent serves the path. Callers MUST hold pathMap.
  private fun conflictRejection(
    path: String,
    agentContext: AgentContext,
    agentInfo: AgentContextInfo,
    entry: AgentContextInfo,
  ): PathRejection? {
    if (agentInfo.isConsolidated != entry.isConsolidated) {
      val reason =
        if (entry.isConsolidated)
          "Consolidated agent rejected for non-consolidated path /$path"
        else
          "Non-consolidated agent rejected for consolidated path /$path"
      return rejectPath(agentContext, path, reason, CONSOLIDATION_MISMATCH)
    }

    // While a live agent serves a path, only an agent of the same auth identity may take it over or, on a
    // consolidated path, join it: a redeploy reclaims its paths at once, but one identity can neither replace
    // another's metrics nor merge its own into them. With no agent auth, or only the legacy shared token, every agent
    // has the same identity, so nothing changes there. A path whose agents are no longer valid is nobody's, which is
    // how such a conflict clears. An agent re-registering a path it already backs never conflicts with itself.
    val otherAgentLive = agentInfo.agentContexts.any { it.agentId != agentContext.agentId && it.isValid() }
    if (agentInfo.identityName == entry.identityName || !otherAgentLive)
      return null
    val action = if (entry.isConsolidated) "join it" else "take it over"
    val reason =
      "Path /$path is served by identity '${agentInfo.identityName}'; identity '${entry.identityName}' cannot $action"
    return rejectPath(agentContext, path, reason, HELD_BY_ANOTHER_IDENTITY)
  }

  // Refuses [agentContext]'s reclaim of the exclusive [path] while a live agent of another launch serves it, so a
  // displaced agent takes the path back only once that agent leaves: taking it back at once is what made two agents
  // with the same exclusive path swap it every few seconds. A stale context of the agent's own launch doesn't count, so
  // takeOverPath still invalidates it. A waiting state rather than a fault, so it is logged at INFO, once, and left out
  // of the activity feed, which already has the takeover. The refused agent waits on the path's current holder, which
  // may not be the agent that took it: once freed, the path can go to a third agent before this one takes it back.
  // Callers MUST hold pathMap.
  private fun reclaimRejection(
    path: String,
    agentContext: AgentContext,
    agentInfo: AgentContextInfo,
    entry: AgentContextInfo,
  ): PathRejection? {
    if (entry.isConsolidated)
      return null
    val holder =
      agentInfo.agentContexts.firstOrNull {
        it.isValid() && it.launchId != agentContext.launchId
      } ?: return null
    val reason =
      "Path /$path is served by agentId ${holder.agentId} (${shownValue(holder.agentName)}), of another launch; " +
        "agentId ${agentContext.agentId} takes it back once that agent leaves"
    if (agentContext.recordRejection(path, HELD_BY_ANOTHER_AGENT)) logger.info { reason } else logger.debug { reason }
    // The holder the agent was told of may have left since, and the path gone to this one.
    tellHolder(path, holder, [agentContext])
    if (agentInfo.waiting.none { it.agentId == agentContext.agentId })
      pathMap[path] = agentInfo.copy(waiting = waitersOf(agentInfo.waiting + agentContext, agentInfo.agentContexts))
    return PathRejection(reason, HELD_BY_ANOTHER_AGENT)
  }

  // Of [candidates], the agents to tell once no agent serves a path [holders] serve: valid ones that read path notices
  // and aren't among the holders, once each, the last MAX_WAITERS_PER_PATH. Callers MUST hold pathMap.
  private fun waitersOf(
    candidates: List<AgentContext>,
    holders: List<AgentContext>,
  ): List<AgentContext> {
    val holderIds = holders.mapTo(HashSet()) { it.agentId }
    return candidates
      .filter { it.isValid() && it.acceptsPathNotices && it.agentId !in holderIds }
      .distinctBy { it.agentId }
      .takeLast(MAX_WAITERS_PER_PATH)
  }

  // This consolidated path's entry with [agentContext] among its agents, as [entry] registers it. The path takes the
  // agent's identity, which differs only when every earlier agent is gone, and the agent's target joins the others. An
  // agent re-registering a path it already backs (a path listed twice in its config) replaces its own context and
  // target: a second copy would send it two requests per scrape, and Prometheus rejects the duplicate samples.
  private fun AgentContextInfo.joinedBy(
    agentContext: AgentContext,
    entry: AgentContextInfo,
  ): AgentContextInfo {
    val updated =
      if (serves(agentContext))
        agentContexts.map { if (it.agentId == agentContext.agentId) agentContext else it }
      else
        agentContexts + agentContext
    return copy(
      agentContexts = updated,
      identityName = entry.identityName,
      agentTargets = agentTargets + entry.agentTargets,
    ).showingFirstAgent()
  }

  // Gives the exclusive [path], which [agentInfo] held, to [agentContext] alone as [entry], and returns the agent it
  // displaced, or null. Every other agent on the path is displaced; re-registering its own path is neither logged nor
  // counted. A displaced agent of another launch that reads path notices is told who holds the path, and waits on it,
  // with the agents already waiting, to be told once no agent serves it. Callers MUST hold pathMap.
  private fun takeOverPath(
    path: String,
    agentContext: AgentContext,
    agentInfo: AgentContextInfo?,
    entry: AgentContextInfo,
  ): AgentContext? {
    val displacedContexts = agentInfo?.agentContexts.orEmpty().filterNot { it.agentId == agentContext.agentId }
    val displaced = displacedContexts.firstOrNull()
    if (displaced != null) {
      logger.info { "Overwriting path /$path for agentId ${displaced.agentId}" }
      proxy.metrics { agentDisplacementCount.inc() }
    }
    // The agents already waiting on the path are told too, so none names an agent that no longer holds it. Oldest
    // first, so the waiting list keeps the agents displaced last.
    val told = tellHolder(path, agentContext, agentInfo?.waiting.orEmpty() + displacedContexts)
    // tellHolder kept only agents that can wait: valid, of another launch, and reading notices.
    pathMap[path] = if (told.isEmpty()) entry else entry.copy(waiting = told.takeLast(MAX_WAITERS_PER_PATH))
    displacedContexts.forEach { settleDisplaced(path, agentContext, it, told = it in told) }
    return displaced
  }

  // Tells each valid one of [contexts] of another launch that reads path notices that [agentContext] holds [path], and
  // returns them. A context of the holder's own launch is a stale copy of it, which needs no telling. Callers MUST hold
  // pathMap.
  private fun tellHolder(
    path: String,
    agentContext: AgentContext,
    contexts: List<AgentContext>,
  ): List<AgentContext> {
    val told =
      contexts.filter { it.isValid() && it.launchId != agentContext.launchId && it.acceptsPathNotices }
        .distinctBy { it.agentId }
    if (told.isEmpty())
      return told
    val notice =
      pathNotice {
        this.path = path
        heldByAgentId = agentContext.agentId
        heldByAgentName = agentContext.agentName
      }
    told.forEach { it.queuePathNotice(notice) }
    return told
  }

  // Takes [path], which [agentContext] took, off [displacedContext]'s count. A displaced context left with no paths is
  // invalidated only when it is a stale copy of the agent that took the path: an agent keeps its launch id across
  // reconnects, so a context of the same launch is one the agent has already left. An agent of another launch stays
  // connected without paths, [told] or not. Invalidating it freed nothing, since it reconnected within seconds,
  // registered its paths again, and displaced this agent in turn: two agents configured with the same exclusive path
  // displaced each other every few seconds without end. A dead context of another launch is evicted for inactivity.
  // Callers MUST hold pathMap.
  private fun settleDisplaced(
    path: String,
    agentContext: AgentContext,
    displacedContext: AgentContext,
    told: Boolean,
  ) {
    countPaths(displacedContext.agentId, -1)
    if (pathCountFor(displacedContext.agentId) > 0)
      return
    if (displacedContext.launchId == agentContext.launchId) {
      logger.info { "Invalidating orphaned agentId ${displacedContext.agentId} after path /$path was overwritten" }
      displacedContext.invalidate()
      return
    }
    val toldHint = if (told) ". It was told, and takes the path back once no agent serves it" else ""
    logger.info {
      "agentId ${displacedContext.agentId} keeps its connection without paths after agentId " +
        "${agentContext.agentId}, of another launch, took over /$path; to serve a path from both agents, " +
        "set agent.consolidated = true on each$toldHint"
    }
  }

  // Counts, reports, and announces [agentContext]'s registration of [path], once pathMap holds it. The activity feed
  // has it as a take-over when it [displaced] another agent, and as a registration when it is new. Callers MUST hold
  // pathMap.
  private fun recordRegistration(
    path: String,
    agentContext: AgentContext,
    alreadyServes: Boolean,
    displaced: AgentContext?,
  ) {
    if (!alreadyServes) {
      countPaths(agentContext.agentId, 1)
      warnNearPathLimit(agentContext)
    }
    proxy.activityLog?.also { log ->
      when {
        displaced != null -> log.record(ActivityKind.PATH_TAKEN_OVER, agentContext, path, "From ${displaced.agentName}")
        !alreadyServes -> log.record(ActivityKind.PATH_REGISTERED, agentContext, path)
      }
    }
    agentContext.forgetRejection(path)
    // The agent holds the path again, so a notice it hasn't read about it is stale.
    agentContext.forgetPathNotice(path)
    // A path that comes back is live again, no longer one the dashboard shows as gone.
    proxy.pathHistory?.forgetDeparture(path)
    proxy.metrics { pathRegistered(path) }
    // The agentId, not the context, whose name and host name the agent reports: this is logged for every path.
    if (!isTestMode) logger.info { "Added path /$path for agentId ${agentContext.agentId}" }
    // Inside synchronized(pathMap) on purpose: tryEmit never suspends or blocks, so publishing here
    // cannot stall a registration, and the event is emitted only once the map actually reflects it.
    proxy.eventBus.emit(ProxyEvent.PathRegistered(path, agentContext.agentId))
  }

  // The scrape route is registered as get("/*"), which matches exactly one path segment. A path with
  // an embedded slash (e.g. "app/metrics") would be advertised in service discovery yet 404 at scrape
  // time, so reject it at registration. A single leading slash is tolerated because the agent may or
  // may not have stripped it. Returns a failure reason, or null when the path is a single segment.
  // [key] is already stripped of its leading slash by pathKey, so any slash left -- including a second leading one, as
  // in "//foo" -- makes it a path the scrape route can't reach. Stripping again here let "//foo" through as "foo" and
  // stored it under "/foo". The reason shows [shown], the key truncated as in sizeRejection.
  private fun multiSegmentPathError(
    key: String,
    shown: String,
  ): String? {
    if ('/' !in key) return null
    return "Multi-segment path not supported (use a single path segment): /$shown"
      .also { logger.error { it } }
  }

  fun removePath(
    path: String,
    agentId: String,
  ): UnregisterPathResponse {
    require(path.isNotBlank()) { BLANK_PATH_MSG }
    require(agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }
    val key = pathKey(path)
    // unregisterPath's path is unchecked, so the failure shows it cut (October #4).
    val shown = shownPath(key)

    // Null once the path is removed, otherwise why it couldn't be.
    val failure =
      synchronized(pathMap) {
        val agentInfo = pathMap[key]
        val agentContext = agentInfo?.agentContexts?.firstOrNull { it.agentId == agentId }
        when {
          agentInfo == null -> {
            "Unable to remove path /$shown - path not found"
          }

          agentContext == null -> {
            val agentIds = agentInfo.agentContexts.joinToString(", ") { it.agentId }
            "Unable to remove path /$shown - invalid agentId: $agentId -- [$agentIds]"
          }

          else -> {
            countPaths(agentId, -1)
            dropAgentFromPath(key, agentInfo, agentId)
            proxy.activityLog?.record(ActivityKind.PATH_UNREGISTERED, agentContext, key)
            null
          }
        }
      }

    failure?.let { msg -> logger.error { msg } }
    return unregisterPathResponse {
      valid = failure == null
      reason = failure.orEmpty()
    }
  }

  // This is called on agent disconnects. Returns how many paths the agent served, which the activity feed reports.
  fun removeFromPathManager(
    agentId: String,
    reason: RemovalReason,
  ): Int {
    require(agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }

    // Always sweep the pathMap by agentId, even when the context is already gone from the manager: a
    // registerPath that raced this removal can strand a path pointing at an invalidated context, and
    // skipping the sweep would leave that path 404ing forever (finding 7). The caller invalidates the
    // context before calling this, so the context is normally already absent from the manager here —
    // report on what the sweep actually removed rather than on a manager lookup that no longer
    // distinguishes a live disconnect from a repeat call.
    // The paths the agent served, and those no agent is left on.
    val (servedPathCount, removedPathCount) =
      synchronized(pathMap) {
        // Snapshot the agent's entries first, since dropping it from a path changes pathMap.
        val served = pathMap.filterValues { info -> info.agentContexts.any { it.agentId == agentId } }
        served.forEach { dropAgentFromPath(it.key, it.value, agentId) }
        // Nor does it wait on any path now: a waiting list kept it, and all it holds, until the path's entry changed.
        pathMap.replaceAll { _, info -> info.withoutWaiter(agentId) }
        // The sweep took every path the agent served.
        pathCounts.remove(agentId)
        served.size to served.keys.count { it !in pathMap }
      }

    if (removedPathCount == 0)
      logger.debug { "No paths registered for agentId: $agentId (${reason.logText})" }
    else
      logger.info { "Removed $removedPathCount path(s) for agentId: $agentId (${reason.logText})" }
    return servedPathCount
  }

  // Takes [agentId] off [path], whose entry is [agentInfo], removing the path once no agent is left on it. Leaves
  // pathCounts to the caller. Callers MUST hold pathMap.
  private fun dropAgentFromPath(
    path: String,
    agentInfo: AgentContextInfo,
    agentId: String,
  ) {
    val remaining = agentInfo.agentContexts.filterNot { it.agentId == agentId }
    if (remaining.isEmpty()) {
      pathMap.remove(path)
      // No agent serves the path now, so the agents waiting on it are told, and each tries to take it back. A waiter
      // that has since disconnected refuses the notice.
      val free = pathNotice { this.path = path }
      agentInfo.waiting.forEach { it.queuePathNotice(free) }
      // The dashboard keeps showing the path, marked gone, with what this entry showed: from its scrapes alone, a path
      // never scraped had no row, and none had a target. Recorded under the lock, as the activity feed is, since the
      // history's monitor is a leaf, so the departure is ordered with the path's next registration, which forgets it.
      proxy.pathHistory?.recordDeparture(path, agentInfo)
      // The path's last registration is gone, so its per-path metric series go with it.
      proxy.metrics { removePathSeries(path) }
      if (!isTestMode)
        logger.info { "Removed path /$path for agentId $agentId" }
    } else {
      // The departing agent's target goes with it, and the path shows the target of whichever agent is now first.
      pathMap[path] =
        agentInfo.copy(agentContexts = remaining, agentTargets = agentInfo.agentTargets - agentId).showingFirstAgent()
      if (!isTestMode)
        logger.info { "Removed agentId $agentId from consolidated path /$path" }
    }
    // A consolidated path surviving the loss of one agent is still a topology change: without the event the dashboard
    // would never be woken for it.
    proxy.eventBus.emit(ProxyEvent.PathUnregistered(path, agentId))
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

    // How much of an over-long path a rejection shows.
    private const val SHOWN_PATH_CHARS = 64

    // How many displaced agents one path keeps waiting to be told it's free; see AgentContextInfo.waiting.
    internal const val MAX_WAITERS_PER_PATH = 16

    // [key] as a rejection or a log line shows it: whole up to SHOWN_PATH_CHARS characters, and otherwise cut there and
    // marked "...", with its control characters escaped (November #16). See AgentValues.shownValue.
    internal fun shownPath(key: String): String = shownValue(key, SHOWN_PATH_CHARS)

    // The key a path is stored and looked up under: without its leading slash, as the scrape route (get("/*") with its
    // leading slashes dropped) looks it up. Stored with the slash, a path was advertised in service discovery yet 404ed
    // on every scrape, and "/foo" and "foo" were separate paths that the identity and consolidation checks never
    // compared. PathHistory keys its rings with it too, so its join to the registered paths holds. Only one slash is
    // stripped, unlike the route: "//foo" must reach multiSegmentPathError with a slash left, to be refused.
    internal fun pathKey(path: String) = path.removePrefix("/")
  }
}
