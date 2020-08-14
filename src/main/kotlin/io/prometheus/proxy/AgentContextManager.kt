/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.util.isNull
import com.google.common.collect.Maps.newConcurrentMap
import mu.KLogging
import java.util.concurrent.ConcurrentMap

internal class AgentContextManager {
  // Map agent_id to AgentContext
  val agentContextMap: ConcurrentMap<String, AgentContext> = newConcurrentMap()
  val agentContextSize: Int get() = agentContextMap.size

  // Map scrape_id to ChunkedContext
  val chunkedContextMap: ConcurrentMap<Long, ChunkedContext> = newConcurrentMap()
  val chunkedContextSize: Int get() = chunkedContextMap.size

  val totalAgentScrapeRequestBacklogSize: Int get() = agentContextMap.values.map { it.scrapeRequestBacklogSize }.sum()

  fun addAgentContext(agentContext: AgentContext): AgentContext? {
    logger.debug { "Registering agentId: ${agentContext.agentId}" }
    return agentContextMap.put(agentContext.agentId, agentContext)
  }

  fun getAgentContext(agentId: String) = agentContextMap[agentId]

  fun removeFromContextManager(agentId: String): AgentContext? =
    agentContextMap.remove(agentId)
      .let { agentContext ->
        if (agentContext.isNull())
          logger.error { "Missing AgentContext for agentId: $agentId" }
        else {
          logger.debug { "Removed $agentContext" }
          agentContext.invalidate()
        }
        agentContext
      }

  companion object : KLogging()
}