/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

internal class AgentContextManager(
  private val isTestMode: Boolean,
) {
  // Map agent_id to AgentContext
  val agentContextMap = ConcurrentHashMap<String, AgentContext>()
  val agentContextSize: Int get() = agentContextMap.size

  // Map scrape_id to ChunkedContext
  val chunkedContextMap = ConcurrentHashMap<Long, ChunkedContext>()
  val chunkedContextSize: Int get() = chunkedContextMap.size

  val totalAgentScrapeRequestBacklogSize: Int get() = agentContextMap.values.sumOf { it.scrapeRequestBacklogSize }

  fun addAgentContext(agentContext: AgentContext): AgentContext? {
    logger.info { "Registering agentId: ${agentContext.agentId}" }
    return agentContextMap.put(agentContext.agentId, agentContext)
  }

  fun getAgentContext(agentId: String) = agentContextMap[agentId]

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
