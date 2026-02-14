/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

internal class AgentContextManager(
  private val isTestMode: Boolean,
) {
  // Map agent_id to AgentContext
  private val agentContextMap = ConcurrentHashMap<String, AgentContext>()
  val agentContextSize: Int get() = agentContextMap.size

  // Map scrape_id to ChunkedContext
  private val chunkedContextMap = ConcurrentHashMap<Long, ChunkedContext>()
  val chunkedContextSize: Int get() = chunkedContextMap.size

  val totalAgentScrapeRequestBacklogSize: Int get() = agentContextMap.values.sumOf { it.scrapeRequestBacklogSize }

  val agentContextEntries: Set<Map.Entry<String, AgentContext>> get() = agentContextMap.entries

  val chunkedContextMapView: Map<Long, ChunkedContext> get() = chunkedContextMap

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
    chunkedContextMap[scrapeId] = context
  }

  fun getChunkedContext(scrapeId: Long): ChunkedContext? = chunkedContextMap[scrapeId]

  fun removeChunkedContext(scrapeId: Long): ChunkedContext? = chunkedContextMap.remove(scrapeId)

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
      logger.warn { "Missing AgentContext for agentId: $agentId ($reason)" }
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
