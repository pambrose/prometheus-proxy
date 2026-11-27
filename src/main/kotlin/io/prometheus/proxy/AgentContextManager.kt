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
import io.prometheus.common.KeyedThrottle
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.time.TimeSource.Monotonic

/**
 * Registry for connected agents and in-flight chunked scrape contexts.
 *
 * Manages two concurrent maps: one mapping agent IDs to their [AgentContext] instances,
 * and another mapping scrape IDs to [ChunkedContext] instances for chunked transfers.
 * Provides lookup, addition, removal, stale-agent detection, and bulk invalidation of
 * agent contexts.
 *
 * @param isTestMode when true, suppresses verbose logging during tests
 * @param clock times the WARN for a connect the agents-per-identity limit refuses; injectable for tests
 * @see AgentContext
 * @see ChunkedContext
 * @see AgentContextCleanupService
 */
internal class AgentContextManager(
  private val isTestMode: Boolean,
  // Defaulted so tests that do not care about topology events construct a manager with its own private
  // bus. Emitting into a bus nobody subscribes to is a no-op, so an unshared one changes no behavior.
  // Proxy passes its shared instance explicitly.
  private val eventBus: ProxyEventBus = ProxyEventBus(),
  clock: TimeSource = Monotonic,
  // The dashboard's activity feed, where a refused connect goes; null with the dashboard off.
  private val activityLog: ActivityLog? = null,
) {
  // Map agent_id to AgentContext
  private val agentContextMap = ConcurrentHashMap<String, AgentContext>()

  // The agentIds of the contexts addAgentContextWithinLimit added, by the auth identity each is bound to: what
  // proxy.internal.maxAgentsPerIdentity counts. A set is read and changed only inside a compute on its identity, and an
  // identity's entry goes with its last context.
  private val agentIdsByIdentity = ConcurrentHashMap<String, MutableSet<String>>()

  // When to warn that an identity nears maxAgentsPerIdentity, as ProxyPathManager does for its path limits: told of
  // each change to an identity's set inside the compute that makes it, so the warnings change in the same order.
  private val identitiesNearLimit = NearLimitWarnings<String>()

  // Which identities' refused connects may be logged at WARN now; see logRefusal. The identities are the ones the proxy
  // is configured with, plus the empty one that agents share with agent auth off, far fewer than its cap.
  private val refusalWarnings = KeyedThrottle<String>(REFUSAL_WARNING_INTERVAL, MAX_REFUSAL_IDENTITIES, clock)

  // Connected agents only: a pending context (see addAgentContext) isn't one yet.
  val agentContextSize: Int get() = agentContextMap.values.count { it.announced }

  val chunkedContextSize: Int get() = chunkedContextMapView.size

  val totalAgentScrapeRequestBacklogSize: Int get() = agentContextMap.values.sumOf { it.scrapeRequestBacklogSize }

  // The connected agents the dashboard and health checks read; pending contexts are left out, as in agentContextSize.
  val agentContextEntries: Set<Map.Entry<String, AgentContext>>
    get() = agentContextMap.entries.filterTo(LinkedHashSet()) { it.value.announced }

  // Map scrape_id to ChunkedContext.
  //
  // The View suffix names the *external* contract: callers outside this class get a read-only Map.
  // Inside the class the same name resolves to the ConcurrentHashMap backing field, so the put/remove
  // calls below really are mutating the map itself -- not a copy, and not a violation of the read-only
  // exposure the name promises everyone else.
  val chunkedContextMapView: Map<Long, ChunkedContext>
    field = ConcurrentHashMap<Long, ChunkedContext>()

  /**
   * Adds [agentContext], announcing it as a connected agent unless [announce] is false.
   *
   * The transport filter adds every connection's context before any call on it is authenticated, so it passes false:
   * the context is findable by its agentId, but isn't logged at INFO, counted, listed, or emitted as AgentConnected
   * until [announceAgentContext]. Otherwise anyone who could reach the agent port showed up as a connected agent.
   */
  fun addAgentContext(
    agentContext: AgentContext,
    announce: Boolean = true,
  ): AgentContext? =
    agentContextMap.put(agentContext.agentId, agentContext).also {
      if (announce)
        announce(agentContext)
      else
        logger.debug { "Pending agentId: ${agentContext.agentId} until its first authenticated call" }
    }

  /**
   * Adds and announces [agentContext], as [addAgentContext] does, unless [maxPerIdentity] contexts bound to its auth
   * identity are already registered; 0 means no limit. Returns whether it was added.
   *
   * With the transport filter disabled, every connect creates a context, which lasts until its stream ends or the
   * cleanup service evicts it, so with no limit one caller could add contexts as fast as it could call, each announced
   * and each with its own path quota (November #17). Agents that share an identity share its limit, and with agent auth
   * off, or on the legacy agentToken, every agent shares one. The slot is claimed before the context is added and freed
   * after it is removed, so concurrent connects can't overshoot the limit.
   */
  fun addAgentContextWithinLimit(
    agentContext: AgentContext,
    maxPerIdentity: Int,
  ): Boolean {
    val identity = agentContext.authIdentityName
    // The identity's count with this context, or 0 when it was refused, and whether that count is one to warn of.
    var count = 0
    var nearLimit = false
    agentIdsByIdentity.compute(identity) { _, agentIds ->
      (agentIds ?: HashSet()).apply {
        if (maxPerIdentity !in 1..size) {
          add(agentContext.agentId)
          count = size
          nearLimit = identitiesNearLimit.rose(identity, size, maxPerIdentity)
        }
      }.ifEmpty { null }
    }
    if (count == 0) {
      logRefusal(identity, maxPerIdentity)
      return false
    }
    addAgentContext(agentContext)
    // Warns as the identity reaches 80% of its limit, so an operator hears about it before connects are refused; once,
    // until its count falls below 70%.
    if (nearLimit)
      logger.warn {
        "$count agents are connected ${identityDesc(identity)}, of the $maxPerIdentity " +
          "proxy.internal.maxAgentsPerIdentity allows"
      }
    return true
  }

  // A refused connect is logged at WARN at most once a minute for each identity, and at DEBUG otherwise: a client that
  // calls in a loop is refused on every call, and a WARN each time would flood the log. The activity feed gets the
  // same once a minute.
  private fun logRefusal(
    identity: String,
    maxPerIdentity: Int,
  ) {
    val message =
      "Refused a connect: $maxPerIdentity agents are already connected ${identityDesc(identity)}, the " +
        "proxy.internal.maxAgentsPerIdentity limit, until one disconnects or is evicted"
    if (refusalWarnings.tryAcquire(identity)) {
      logger.warn { message }
      activityLog?.record(
        ActivityKind.AGENT_REFUSED,
        identity = identity,
        detail = "Already at its proxy.internal.maxAgentsPerIdentity limit ($maxPerIdentity)",
      )
    } else {
      logger.debug { message }
    }
  }

  /**
   * Announces a pending context as a connected agent. ProxyServerInterceptor calls it on every authenticated call; only
   * the first announces.
   */
  fun announceAgentContext(agentId: String) {
    agentContextMap[agentId]?.also { announce(it) }
  }

  // Skips a context already invalidated: ProxyServerInterceptor can announce in the moment after transportTerminated
  // removed it, which would report a connection that is already gone. markAnnounced refuses an invalidated context in
  // the same step that marks it, so a removal racing this one either finds it announced, and reports it leaving, or
  // leaves it never announced.
  private fun announce(agentContext: AgentContext) {
    if (agentContext.markAnnounced()) {
      logger.info { "Registering agentId: ${agentContext.agentId}" }
      eventBus.emit(ProxyEvent.AgentConnected(agentContext.agentId))
    }
  }

  fun getAgentContext(agentId: String) = agentContextMap[agentId]

  // Opens a transfer for [scrapeId] unless one is already under way, which a second header must not replace. Returns
  // whether [context] was stored.
  fun putChunkedContext(
    scrapeId: Long,
    context: ChunkedContext,
  ): Boolean = chunkedContextMapView.putIfAbsent(scrapeId, context) == null

  fun getChunkedContext(scrapeId: Long): ChunkedContext? = chunkedContextMapView[scrapeId]

  fun removeChunkedContext(scrapeId: Long): ChunkedContext? = chunkedContextMapView.remove(scrapeId)

  // Removes [context] only if it is still the transfer for [scrapeId], so a caller that checked a context acts on that
  // one. Returns whether it was removed.
  fun removeChunkedContext(
    scrapeId: Long,
    context: ChunkedContext,
  ): Boolean = chunkedContextMapView.remove(scrapeId, context)

  /**
   * Removes every in-flight [ChunkedContext] owned by [agentId] (e.g. when the agent disconnects or is
   * evicted), reclaiming their buffered output streams immediately instead of waiting for each chunked
   * stream's own orphan sweep. The corresponding scrape requests are failed separately by
   * [ScrapeRequestManager.failAllScrapeRequests]; this only frees the chunk buffers. A context's owner is
   * the agent its scrape was sent to ([ChunkedContext.owner]), not the agentId its header claims.
   *
   * @return the scrape IDs that were removed
   */
  fun removeChunkedContextsForAgent(agentId: String): List<Long> =
    // Return only the IDs this call actually removed. A concurrent writeChunkedResponsesToProxy can
    // complete (and remove) a matching context between the filter and the remove, so returning the
    // pre-removal snapshot would over-count what was reclaimed in the caller's log.
    chunkedContextMapView.entries
      .filter { (_, context) -> context.owner.agentId == agentId }
      .mapNotNull { (scrapeId, context) -> if (chunkedContextMapView.remove(scrapeId, context)) scrapeId else null }

  fun findStaleAgents(maxInactivity: Duration): List<Pair<String, AgentContext>> =
    agentContextMap
      .filter { (_, agentContext) -> agentContext.inactivityDuration > maxInactivity }
      .map { (agentId, agentContext) -> agentId to agentContext }

  fun invalidateAllAgentContexts() {
    agentContextMap.values.forEach { agentContext ->
      agentContext.invalidate()
    }
  }

  fun removeFromContextManager(
    agentId: String,
    reason: RemovalReason,
  ): AgentContext? {
    val agentContext = agentContextMap.remove(agentId)
    if (agentContext == null)
      logger.debug { "AgentContext already removed for agentId: $agentId (${reason.logText})" }
    else
      onRemoved(agentContext, reason)
    return agentContext
  }

  /**
   * Removes [agentContext], as [removeFromContextManager] does, only if it is still the context registered under its
   * agentId, and returns whether it was. A caller that looked a context up and acted on it removes that context, never
   * one registered under the same agentId after its lookup (November #14).
   */
  fun removeFromContextManagerIfCurrent(
    agentContext: AgentContext,
    reason: RemovalReason,
  ): Boolean =
    agentContextMap.remove(agentContext.agentId, agentContext).also { removed ->
      if (removed)
        onRemoved(agentContext, reason)
      else
        logger.debug { "AgentContext already removed for agentId: ${agentContext.agentId} (${reason.logText})" }
    }

  // Logs a context just taken out of the map, invalidates it, and frees its slot under maxAgentsPerIdentity.
  private fun onRemoved(
    agentContext: AgentContext,
    reason: RemovalReason,
  ) {
    val agentId = agentContext.agentId
    // Invalidated first, which fixes whether it was announced, so the log agrees with what the removal reports.
    agentContext.invalidate()
    // A connection that never authenticated -- a bad token, a port scanner, a grpc_health_probe -- isn't an agent,
    // so its closing is logged at DEBUG (October #4).
    if (!agentContext.announced)
      logger.debug { "Removed $agentContext for agentId: $agentId (${reason.logText})" }
    else if (!isTestMode)
      logger.info { "Removed $agentContext for agentId: $agentId (${reason.logText})" }
    agentIdsByIdentity.computeIfPresent(agentContext.authIdentityName) { identity, agentIds ->
      agentIds.apply {
        remove(agentId)
        identitiesNearLimit.fell(identity, size)
      }.ifEmpty { null }
    }
  }

  companion object {
    private val logger = logger {}

    // How often a refused connect may be logged at WARN for one identity.
    private val REFUSAL_WARNING_INTERVAL = 1.minutes

    // The most identities whose last WARN is remembered. Only a configured identity, or the empty one agents share with
    // agent auth off, can be refused, so in practice the cap forgets none; a proxy with more identities than this all
    // refused within a minute would only warn of the one refused least recently again early.
    private const val MAX_REFUSAL_IDENTITIES = 1_000

    // An identity as the log names it, after "connected". Agents with agent auth off share the empty one.
    private fun identityDesc(identity: String) =
      if (identity.isEmpty()) "with no auth identity" else "as identity '$identity'"
  }
}
