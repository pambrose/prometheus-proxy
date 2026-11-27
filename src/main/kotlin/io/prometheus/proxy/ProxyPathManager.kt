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
import io.prometheus.common.AgentValues.segmentProblem
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
    // The labels the path registered with, as Proxy.pathLabels keeps them, parsed once: what the dashboard shows, its
    // links read, and /debug prints. The entry kept them as sent too, beside the parsed copy, and the dashboard parsed
    // them again on each collect for its links.
    val labels: JsonObject = Proxy.NO_LABELS,
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
    // [labels] as service discovery serves them, filtered once, when the path registered, which each service-discovery
    // request only copies (November #15): the same object as labels unless service discovery leaves some out. See
    // Proxy.pathLabels.
    val sdLabels: JsonObject = Proxy.NO_LABELS,
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

    // The data-class form without agentTargets or waiting, and with sdLabels only when it isn't labels. /debug prints
    // every entry inside the pathMap lock, and every agent's target, each up to maxTargetUrlLength, would multiply what
    // it prints; the first agent's is targetUrl already.
    override fun toString() =
      "AgentContextInfo(isConsolidated=$isConsolidated, labels=$labels, agentContexts=$agentContexts, " +
        "targetUrl=$targetUrl, pathSource=$pathSource, identityName=$identityName" +
        (if (sdLabels == labels) ")" else ", sdLabels=$sdLabels)")

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

  // The path map, and the paths each agent waits on, which it indexes; also the lock that guards it and the counts.
  // Changed only through its store, which keeps the index in step.
  private val pathMap = PathEntries()

  // How many paths each agent, and the agents of each identity, serve; guarded by pathMap, and changed only in
  // countPaths, with every change to pathMap.
  private val pathCounts = PathCounts(proxy, logger)

  // Adds [delta] to the paths [agentId] serves, and to its identity's: [identity], the identity of the registration,
  // when the agent serves none yet. Callers MUST hold pathMap.
  private fun countPaths(
    agentId: String,
    delta: Int,
    identity: String = "",
  ) = pathCounts.add(agentId, delta, identity)

  // The number of paths [agentId] serves. Callers that already hold pathMap take it again, which the JVM allows.
  internal fun pathCountFor(agentId: String): Int = synchronized(pathMap) { pathCounts.of(agentId) }

  // The number of paths the agents of [identity] serve together, empty for agents with no auth identity.
  internal fun identityPathCountFor(identity: String): Int = synchronized(pathMap) { pathCounts.ofIdentity(identity) }

  // AgentContextInfo is deeply immutable and mutations replace the pathMap entry (never mutate in
  // place), so returning the stored instance is already an effective snapshot — no defensive copy.
  // Paths are keyed without a leading slash, the form the scrape route looks them up in; see pathKey.
  fun getAgentContextInfo(path: String): AgentContextInfo? = synchronized(pathMap) { pathMap[pathKey(path)] }

  val pathMapSize: Int
    get() = synchronized(pathMap) { pathMap.size }

  // The paths [agentId] waits on, as the index has them.
  internal fun waitedPathsOf(agentId: String): Set<String> = synchronized(pathMap) { pathMap.waitedOnBy(agentId) }

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
    val pathLabels = Proxy.pathLabels(labels, proxy.reserveJobAndInstanceLabels)
    labelCountRejection(key, pathLabels.count, agentContext)?.let { return it }
    // What boundedMetadata cut, for the warning: field names and sizes, never the values.
    val cut = mutableListOf<String>()
    val bounded = boundedMetadata(metadata, cut)
    val entry =
      AgentContextInfo(
        isConsolidated = agentContext.consolidated,
        labels = pathLabels.kept,
        agentContexts = [agentContext],
        targetUrl = bounded.targetUrl,
        pathSource = bounded.pathSource,
        identityName = bounded.identityName,
        sdLabels = pathLabels.served,
        agentTargets = mapOf(agentContext.agentId to PathTarget(bounded.targetUrl, bounded.pathSource)),
      )
    val orphans = mutableListOf<AgentContext>()
    return addValidatedPath(key, agentContext, entry, bounded.reclaim, orphans).also { rejection ->
      // Warned only once the path is stored, so an agent retrying a refused registration isn't warned about each time.
      if (rejection == null)
        warnAboutRegistration(key, agentContext, pathLabels, cut)
      // Removed only once pathMap is let go of, since a removal sweeps the context's paths under it (settleDisplaced).
      orphans.forEach { proxy.removeAgentContextIfCurrent(it, RemovalReason.REPLACED) }
    }
  }

  // Says once, as [path] registers, what of its registration won't be served as sent: the labels service discovery
  // leaves out, which each service-discovery request no longer repeats (November #15), and the metadata [cut] names.
  private fun warnAboutRegistration(
    path: String,
    agentContext: AgentContext,
    labels: Proxy.PathLabels,
    cut: List<String>,
  ) {
    if (labels.reserved.isNotEmpty()) {
      val reserved = labels.reserved.joinToString(prefix = "[", postfix = "]") { shownValue(it) }
      logger.warn { "Agent labels $reserved for path /$path are reserved and won't appear in service discovery" }
    }
    // Left out of service discovery rather than refused: labels matter only there, and the path still serves scrapes.
    labels.problem?.also {
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
   * has a character no scrape's request path can carry, it has more than one segment, or it is a path the proxy
   * serves itself (the favicon, service discovery, or blitz path).
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
  // never be scraped. The SD path returned the SD JSON, favicon.ico a 404, and the blitz path "42" (October #6). The SD
  // path is compared by its key, which ProxyOptions makes sure names a path with no leading slash left.
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

  // Rejects labels that hold more than proxy.internal.maxLabelsPerPath labels, of [count]; 0 turns the check off. Each
  // label the entry keeps costs over 100 bytes besides its text, so within maxLabelsSizeBytes, 8 KiB of one-character
  // labels kept over 130 KiB a path, and a few thousand such paths, far fewer than maxPathsPerAgent, exhausted a small
  // heap. Checked once the labels are parsed, after the identity's globs, as the path limits are.
  private fun labelCountRejection(
    key: String,
    count: Int,
    agentContext: AgentContext,
  ): PathRejection? {
    val max = proxy.maxLabelsPerPath
    if (max !in 1..<count)
      return null
    val shown = shownPath(key)
    val reason = "Path /$shown has $count labels, over proxy.internal.maxLabelsPerPath ($max)"
    return rejectPath(agentContext, shown, reason, INVALID_PATH)
  }

  // Rejects a path the agent doesn't serve yet once it serves proxy.internal.maxPathsPerAgent (0 = unlimited), each
  // path adding a path-map entry, a service-discovery target, and per-path metric series. The limit is one
  // connection's, and an identity may open as many as it likes, so identityPathLimitRejection bounds the identity.
  // Callers MUST hold pathMap.
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

  // Rejects a path [agentContext] doesn't serve yet once the agents of [identity], the identity its paths count
  // against, serve proxy.internal.maxPathsPerIdentity paths together (0 = unlimited). maxPathsPerAgent counts one
  // connection, and an identity may open any number, each with a quota of its own. With agent auth off, or only the
  // legacy agentToken, every agent has one identity, so the limit is a total. Callers MUST hold pathMap.
  private fun identityPathLimitRejection(
    agentContext: AgentContext,
    path: String,
    identity: String,
  ): PathRejection? {
    val max = proxy.maxPathsPerIdentity
    val count = pathCounts.ofIdentity(identity)
    if (max !in 1..count)
      return null
    val agents = "The agents ${PathCounts.identityDesc(identity)}"
    val reason =
      "$agents already serve $count paths, the proxy.internal.maxPathsPerIdentity limit ($max); path /$path refused"
    return rejectPath(agentContext, path, reason, PATH_LIMIT_REACHED)
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
   * rejection once, however often the agent retries. Every rejection the feed shows passes through here, the
   * authorization one ProxyServiceImpl makes included, though each logs as it always has. A refused reclaim doesn't:
   * reclaimRejection keeps that waiting state out of the feed.
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
  // from the registration: the path's entry when the agent is its only one. [reclaim] is the registration's flag. Adds
  // to [orphans] each context the registration leaves orphaned, for addPath to remove once it lets go of the lock.
  private fun addValidatedPath(
    path: String,
    agentContext: AgentContext,
    entry: AgentContextInfo,
    reclaim: Boolean,
    orphans: MutableList<AgentContext>,
  ): PathRejection? {
    synchronized(pathMap) {
      val agentInfo = pathMap[path]
      // A path the agent already serves is a re-registration, which neither counts against its limit nor adds to it.
      val alreadyServes = agentInfo?.serves(agentContext) == true
      val rejection = lockedRejection(path, agentContext, entry, reclaim)
      if (rejection == null) {
        val displaced =
          if (entry.isConsolidated) {
            pathMap.store(path, agentInfo?.let { withoutGoneAgents(it) }?.joinedBy(agentContext, entry) ?: entry)
            null
          } else {
            takeOverPath(path, agentContext, agentInfo, entry, orphans)
          }
        recordRegistration(path, agentContext, entry.identityName, alreadyServes, displaced)
      }
      return rejection
    }
  }

  // [agentInfo], a consolidated path's entry, without the agents that are gone, which are taken off their counts, or
  // null when none is left. removeAgentContext invalidates an agent before it sweeps its paths, so a registration can
  // come in between: joined to the entry as it was, the gone agent stayed first, so scrapes still went to it and the
  // path showed its target, and the path kept its labels, another identity's perhaps, after its sweep. With none left,
  // the registration's own entry is the path's, labels and all. Its sweep then finds the path no longer its own.
  // Callers MUST hold pathMap.
  private fun withoutGoneAgents(agentInfo: AgentContextInfo): AgentContextInfo? {
    val (gone, live) = agentInfo.agentContexts.partition { it.isNotValid() }
    if (gone.isEmpty())
      return agentInfo
    gone.forEach { countPaths(it.agentId, -1) }
    if (live.isEmpty())
      return null
    val goneIds = gone.mapTo(HashSet()) { it.agentId }
    return agentInfo.copy(agentContexts = live, agentTargets = agentInfo.agentTargets - goneIds).showingFirstAgent()
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
      ?: if (agentInfo?.serves(agentContext) == true) null else quotaRejection(path, agentContext, agentInfo, entry)
  }

  // Why [agentContext]'s registration of [path], which it doesn't serve yet and [agentInfo] holds, as [entry], would
  // take it past maxPathsPerAgent, or the agents of its identity past maxPathsPerIdentity, or null. Taking an exclusive
  // path from an agent whose paths count against the same identity leaves that identity's count as it was, so an
  // identity at its limit can still redeploy. Callers MUST hold pathMap.
  private fun quotaRejection(
    path: String,
    agentContext: AgentContext,
    agentInfo: AgentContextInfo?,
    entry: AgentContextInfo,
  ): PathRejection? {
    pathLimitRejection(agentContext, path)?.let { return it }
    // The identity the agent's paths count against: its first path's.
    val identity = pathCounts.identityOf(agentContext.agentId) ?: entry.identityName
    val replacesOwn =
      !entry.isConsolidated &&
        agentInfo?.agentContexts.orEmpty().any { pathCounts.identityOf(it.agentId) == identity }
    return if (replacesOwn) null else identityPathLimitRejection(agentContext, path, identity)
  }

  // Re-checks validity inside the lock: agent removal (transportTerminated / cleanup eviction / the scrape-request
  // stream ending, with the transport filter disabled) can interleave between the caller's out-of-lock
  // getAgentContext() check and here, invalidating the context. Inserting a path for an invalidated context creates a
  // permanently-dead path that no cleanup sweeps, so reject it and let the agent re-register on reconnect (finding 7).
  // Callers MUST hold pathMap.
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
    if (agentInfo.waiting.none { it.agentId == agentContext.agentId }) {
      val waiting = waitersOf(agentInfo.waiting + agentContext, agentInfo.agentContexts)
      pathMap.store(path, agentInfo.copy(waiting = waiting))
    }
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
  // with the agents already waiting, to be told once no agent serves it. Adds each displaced context left orphaned to
  // [orphans]. Callers MUST hold pathMap.
  private fun takeOverPath(
    path: String,
    agentContext: AgentContext,
    agentInfo: AgentContextInfo?,
    entry: AgentContextInfo,
    orphans: MutableList<AgentContext>,
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
    pathMap.store(path, if (told.isEmpty()) entry else entry.copy(waiting = told.takeLast(MAX_WAITERS_PER_PATH)))
    displacedContexts.forEach { displacedContext ->
      if (settleDisplaced(path, agentContext, displacedContext, told = displacedContext in told))
        orphans += displacedContext
    }
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

  // Takes [path], which [agentContext] took, off [displacedContext]'s count, and returns whether that left it orphaned.
  // A displaced context left with no paths is orphaned only when it is a stale copy of the agent that took the path:
  // an agent keeps its launch id across reconnects, so a context of the same launch is one the agent has already left.
  // It is invalidated here, under the lock, and removed once the registration lets go of it, as a disconnect removes a
  // context: invalidated alone, it stayed in the context manager, and a scrape it had already been sent, or a chunked
  // transfer it had begun, waited for its eviction or its timeout. An agent of another launch stays connected without
  // paths, [told] or not. Invalidating it freed nothing, since it reconnected within seconds, registered its paths
  // again, and displaced this agent in turn: two agents configured with the same exclusive path displaced each other
  // every few seconds without end. A dead context of another launch is evicted for inactivity. Callers MUST hold
  // pathMap.
  private fun settleDisplaced(
    path: String,
    agentContext: AgentContext,
    displacedContext: AgentContext,
    told: Boolean,
  ): Boolean {
    countPaths(displacedContext.agentId, -1)
    if (pathCountFor(displacedContext.agentId) > 0)
      return false
    if (displacedContext.launchId == agentContext.launchId) {
      logger.info { "Removing orphaned agentId ${displacedContext.agentId} after path /$path was overwritten" }
      displacedContext.invalidate()
      return true
    }
    val toldHint = if (told) ". It was told, and takes the path back once no agent serves it" else ""
    logger.info {
      "agentId ${displacedContext.agentId} keeps its connection without paths after agentId " +
        "${agentContext.agentId}, of another launch, took over /$path; to serve a path from both agents, " +
        "set agent.consolidated = true on each$toldHint"
    }
    return false
  }

  // Counts, reports, and announces [agentContext]'s registration of [path], as [identity], once pathMap holds it. The
  // activity feed has it as a take-over when it [displaced] another agent, and as a registration when it is new.
  // Callers MUST hold pathMap.
  private fun recordRegistration(
    path: String,
    agentContext: AgentContext,
    identity: String,
    alreadyServes: Boolean,
    displaced: AgentContext?,
  ) {
    if (!alreadyServes)
      countPaths(agentContext.agentId, 1, identity)
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
    if (segmentProblem(key) == null) return null
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
            // An agent waiting on the path gives it up, so it isn't told the path is free.
            pathMap.store(key, agentInfo.withoutWaiter(agentId))
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

    // The agent's view is stale, not the proxy's: a displaced agent unregisters a path another agent took, or one that
    // agent has since dropped.
    failure?.let { msg -> logger.info { msg } }
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
    // distinguishes a live disconnect from a repeat call. The sweep scans the map only for an agent that
    // serves a path, which its count, kept in step with the map, tells, and visits only the paths it waits
    // on, which pathMap indexes: most connections that close, a port scanner's or a bad token's among
    // them, serve none and wait on none, and each scanned every entry twice under the lock.
    // The paths the agent served, and those no agent is left on.
    val (servedPathCount, removedPathCount) =
      synchronized(pathMap) {
        val count = pathCounts.of(agentId)
        // Snapshot the agent's entries first, since dropping it from a path changes pathMap.
        val served = if (count == 0) emptyMap() else pathMap.servedBy(agentId)
        served.forEach { dropAgentFromPath(it.key, it.value, agentId) }
        // Nor does it wait on any path now: a waiting list kept it, and all it holds, until the path's entry changed.
        pathMap.forgetWaiter(agentId)
        // The sweep took every path the agent served, off its count and its identity's.
        if (count > 0)
          countPaths(agentId, -count)
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
      pathMap.store(path, null)
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
      pathMap.store(
        path,
        agentInfo.copy(agentContexts = remaining, agentTargets = agentInfo.agentTargets - agentId).showingFirstAgent(),
      )
      if (!isTestMode)
        logger.info { "Removed agentId $agentId from consolidated path /$path" }
    }
    // A consolidated path surviving the loss of one agent is still a topology change: without the event the dashboard
    // would never be woken for it.
    proxy.eventBus.emit(ProxyEvent.PathUnregistered(path, agentId))
  }

  fun toPlainText(): String =
    synchronized(pathMap) {
      if (pathMap.size == 0) {
        "No agents connected."
      } else {
        val maxPath = pathMap.keys.maxOfOrNull { it.length } ?: 0
        "Proxy Path Map:\n" + "Path".padEnd(maxPath + 2) + "Agent Context\n" +
          pathMap
            .toMap()
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
