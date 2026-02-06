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

import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.isNull
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import java.util.concurrent.ConcurrentHashMap
import io.prometheus.Proxy
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.grpc.unregisterPathResponse

internal class ProxyPathManager(
  private val proxy: Proxy,
  private val isTestMode: Boolean,
) {
  class AgentContextInfo(
    val isConsolidated: Boolean,
    val labels: String,
    val agentContexts: MutableList<AgentContext>,
  ) {
    fun isNotValid() = agentContexts.all { it.isNotValid() }

    override fun toString(): String =
      "AgentContextInfo(consolidated=$isConsolidated, labels=$labels,agentContexts=$agentContexts)"
  }

  private val pathMap = ConcurrentHashMap<String, AgentContextInfo>()

  fun getAgentContextInfo(path: String): AgentContextInfo? =
    synchronized(pathMap) {
      pathMap[path]?.let { info ->
        AgentContextInfo(info.isConsolidated, info.labels, info.agentContexts.toMutableList())
      }
    }

  val pathMapSize: Int
    get() = pathMap.size

  val allPaths: List<String>
    get() = synchronized(pathMap) { pathMap.keys.toList() }

  fun addPath(
    path: String,
    labels: String,
    agentContext: AgentContext,
  ) {
    require(path.isNotEmpty()) { EMPTY_PATH_MSG }

    synchronized(pathMap) {
      val agentInfo = pathMap[path]
      if (agentContext.consolidated) {
        if (agentInfo.isNull()) {
          pathMap[path] = AgentContextInfo(true, labels, mutableListOf(agentContext))
        } else {
          if (agentContext.consolidated != agentInfo.isConsolidated)
            logger.warn {
              "Mismatch of agent context types: ${agentContext.consolidated} and ${agentInfo.isConsolidated}"
            }
          else
            agentInfo.agentContexts += agentContext
        }
      } else {
        if (agentInfo.isNotNull()) {
          if (agentInfo.isConsolidated) {
            logger.warn { "Non-consolidated agent overwriting consolidated path /$path" }
          }
          logger.info { "Overwriting path /$path for ${agentInfo.agentContexts[0]}" }
        }
        pathMap[path] = AgentContextInfo(false, labels, mutableListOf(agentContext))
      }

      if (!isTestMode) logger.info { "Added path /$path for $agentContext" }
    }
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
        agentInfo.agentContexts.remove(agentContext)
        if (!isTestMode)
          logger.info { "Removed element of path /$path for $agentInfo" }
      } else {
        pathMap.remove(path)
        if (!isTestMode)
          logger.info { "Removed path /$path for $agentInfo" }
      }
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

    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    if (agentContext.isNull()) {
      logger.warn { "Missing agent context for agentId: $agentId ($reason)" }
    } else {
      logger.info { "Removing paths for agentId: $agentId ($reason)" }

      synchronized(pathMap) {
        // Collect keys to remove in a first pass to avoid modifying the map during iteration
        val keysToRemove = mutableListOf<String>()
        pathMap.forEach { (k, v) ->
          if (v.agentContexts.size == 1) {
            if (v.agentContexts[0].agentId == agentId)
              keysToRemove += k
          } else {
            val removed = v.agentContexts.removeIf { it.agentId == agentId }
            if (removed)
              logger.info { "Removed path /$k for $agentContext" }
          }
        }

        keysToRemove.forEach { k ->
          pathMap.remove(k)
            ?.also {
              if (!isTestMode)
                logger.info { "Removed path /$k for $it" }
            } ?: logger.warn { "Missing ${agentContext.desc}path /$k for agentId: $agentId" }
        }
      }
    }
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
