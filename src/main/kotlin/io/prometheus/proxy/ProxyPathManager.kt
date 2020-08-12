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

import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.isNull
import com.google.common.collect.Maps.newConcurrentMap
import io.prometheus.Proxy
import io.prometheus.common.GrpcObjects.EMPTY_AGENT_ID
import io.prometheus.common.GrpcObjects.EMPTY_PATH
import io.prometheus.common.GrpcObjects.unregisterPathResponse
import io.prometheus.grpc.UnregisterPathResponse
import mu.KLogging

internal class ProxyPathManager(private val proxy: Proxy, private val isTestMode: Boolean) {

  class AgentContextInfo(var consolidated: Boolean, val agentContexts: MutableList<AgentContext>) {
    override fun toString(): String {
      return "AgentContextInfo(consolidated=$consolidated, agentContexts=$agentContexts)"
    }
  }

  private val pathMap = newConcurrentMap<String, AgentContextInfo>()

  fun getAgentContextInfo(path: String) = pathMap[path]

  val pathMapSize: Int
    get() = pathMap.size

  fun addPath(path: String, agentContext: AgentContext) {
    require(path.isNotEmpty()) { EMPTY_PATH }

    synchronized(pathMap) {
      val agentInfo = pathMap[path]
      if (agentContext.consolidated) {
        if (agentInfo.isNull()) {
          pathMap[path] = AgentContextInfo(true, mutableListOf(agentContext))
        }
        else {
          if (agentContext.consolidated != agentInfo.consolidated)
            logger.warn { "Mismatch of agent context types: ${agentContext.consolidated} and ${agentInfo.consolidated}" }
          else
            agentInfo.agentContexts += agentContext
        }
      }
      else {
        if (agentInfo.isNotNull()) logger.info { "Overwriting path /$path for ${agentInfo.agentContexts.get(0)}" }
        pathMap[path] = AgentContextInfo(false, mutableListOf(agentContext))
      }

      if (!isTestMode) logger.info { "Added path /$path for $agentContext" }
    }
  }

  fun removePath(path: String, agentId: String): UnregisterPathResponse {
    require(path.isNotEmpty()) { EMPTY_PATH }
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }

    synchronized(pathMap) {
      val agentInfo = pathMap[path]
      val results =
        if (agentInfo.isNull()) {
          val msg = "Unable to remove path /$path - path not found"
          logger.error { msg }
          false to msg
        }
        else {
          val agentContext = agentInfo.agentContexts.firstOrNull { it.agentId == agentId }
          if (agentContext.isNull()) {
            val agentIds = agentInfo.agentContexts.map { it.agentId }.joinToString(", ")
            val msg = "Unable to remove path /$path - invalid agentId: $agentId -- [$agentIds]"
            logger.error { msg }
            false to msg
          }
          else {
            if (agentInfo.consolidated && agentInfo.agentContexts.size > 1) {
              agentInfo.agentContexts.remove(agentContext)
              if (!isTestMode)
                logger.info { "Removed element of path /$path for $agentInfo" }
            }
            else {
              pathMap.remove(path)
              if (!isTestMode)
                logger.info { "Removed path /$path for $agentInfo" }
            }
            true to ""
          }
        }
      return unregisterPathResponse { valid = results.first; reason = results.second }
    }
  }

  // This is called on agent disconnects
  fun removePathByAgentId(agentId: String) {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }

    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    if (agentContext.isNull()) {
      logger.warn { "Missing agent context for agentId: $agentId" }
      return
    }

    logger.info { "Removing paths for agentId: $agentId" }

    synchronized(pathMap) {
      pathMap.forEach { (k, v) ->
        if (v.agentContexts.size == 1) {
          if (v.agentContexts.get(0).agentId == agentId)
            pathMap.remove(k)?.also {
              if (!isTestMode)
                logger.info { "Removed path /$k for $it" }
            }
            ?: logger.warn { "Missing ${agentContext.desc}path /$k for agentId: $agentId" }
        }
        else {
          val removed = v.agentContexts.removeIf { it.agentId == agentId }
          if (removed)
            logger.info { "Removed path /$k for $agentContext" }
          else
            logger.warn { "Missing path /$k for agentId: $agentId" }
        }
      }
    }
  }

  fun toPlainText() =
    if (pathMap.isEmpty()) {
      "No agents connected."
    }
    else {
      val maxPath = pathMap.keys.map { it.length }.max() ?: 0
      "Proxy Path Map:\n" + "Path".padEnd(maxPath + 2) + "Agent Context\n" +
      pathMap
        .toSortedMap()
        .map { c -> "/${c.key.padEnd(maxPath)} ${c.value.agentContexts.size} ${c.value}" }
        .joinToString("\n\n")
    }

  companion object : KLogging()
}