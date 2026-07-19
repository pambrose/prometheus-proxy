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
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * Registry for connected agents and in-flight chunked scrape contexts.
 *
 * Manages two concurrent maps: one mapping agent IDs to their [AgentContext] instances,
 * and another mapping scrape IDs to [ChunkedContext] instances for chunked transfers.
 * Provides lookup, addition, removal, stale-agent detection, and bulk invalidation of
 * agent contexts.
 *
 * @param isTestMode when true, suppresses verbose logging during tests
 * @see AgentContext
 * @see ChunkedContext
 * @see AgentContextCleanupService
 */
internal class AgentContextManager(
  private val isTestMode: Boolean,
) {
  // Map agent_id to AgentContext
  private val agentContextMap = ConcurrentHashMap<String, AgentContext>()
  val agentContextSize: Int get() = agentContextMap.size

  val chunkedContextSize: Int get() = chunkedContextMapView.size

  val totalAgentScrapeRequestBacklogSize: Int get() = agentContextMap.values.sumOf { it.scrapeRequestBacklogSize }

  val agentContextEntries: Set<Map.Entry<String, AgentContext>> get() = agentContextMap.entries

  // Map scrape_id to ChunkedContext.
  //
  // The View suffix names the *external* contract: callers outside this class get a read-only Map.
  // Inside the class the same name resolves to the ConcurrentHashMap backing field, so the put/remove
  // calls below really are mutating the map itself -- not a copy, and not a violation of the read-only
  // exposure the name promises everyone else.
  val chunkedContextMapView: Map<Long, ChunkedContext>
    field = ConcurrentHashMap<Long, ChunkedContext>()

  fun addAgentContext(agentContext: AgentContext): AgentContext? {
    logger.info { "Registering agentId: ${agentContext.agentId}" }
    return agentContextMap.put(agentContext.agentId, agentContext)
  }

  fun getAgentContext(agentId: String) = agentContextMap[agentId]

  internal fun putAgentContext(
    agentId: String,
    context: AgentContext,
  ) {
    agentContextMap[agentId] = context
  }

  fun putChunkedContext(
    scrapeId: Long,
    context: ChunkedContext,
  ) {
    chunkedContextMapView[scrapeId] = context
  }

  fun getChunkedContext(scrapeId: Long): ChunkedContext? = chunkedContextMapView[scrapeId]

  fun removeChunkedContext(scrapeId: Long): ChunkedContext? = chunkedContextMapView.remove(scrapeId)

  /**
   * Removes every in-flight [ChunkedContext] owned by [agentId] (e.g. when the agent disconnects or is
   * evicted), reclaiming their buffered output streams immediately instead of waiting for each chunked
   * stream's own orphan sweep. The corresponding scrape requests are failed separately by
   * [ScrapeRequestManager.failAllScrapeRequests]; this only frees the chunk buffers.
   *
   * @return the scrape IDs that were removed
   */
  fun removeChunkedContextsForAgent(agentId: String): List<Long> =
    // Return only the IDs this call actually removed. A concurrent writeChunkedResponsesToProxy can
    // complete (and remove) a matching context between the filter and the remove, so returning the
    // pre-removal snapshot would over-count what was reclaimed in the caller's log.
    chunkedContextMapView.entries
      .filter { (_, context) -> context.agentId == agentId }
      .mapNotNull { (scrapeId, _) -> if (chunkedContextMapView.remove(scrapeId) != null) scrapeId else null }

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
    reason: String,
  ): AgentContext? {
    val agentContext = agentContextMap.remove(agentId)
    if (agentContext == null) {
      logger.debug { "AgentContext already removed for agentId: $agentId ($reason)" }
    } else {
      if (!isTestMode)
        logger.info { "Removed $agentContext for agentId: $agentId ($reason)" }
      agentContext.invalidate()
    }
    return agentContext
  }

  companion object {
    private val logger = logger {}
  }
}
