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

import com.google.common.collect.Maps.newConcurrentMap
import io.prometheus.common.GrpcObjects.EMPTY_AGENTID
import io.prometheus.common.GrpcObjects.EMPTY_PATH
import io.prometheus.common.GrpcObjects.unregisterPathResponse
import io.prometheus.grpc.UnregisterPathResponse
import mu.KLogging
import java.util.concurrent.ConcurrentMap

internal class ProxyPathManager(private val isTestMode: Boolean) {

  private val pathMap: ConcurrentMap<String, AgentContext> = newConcurrentMap() // Map path to AgentContext

  operator fun get(path: String) = pathMap[path]

  operator fun contains(path: String) = pathMap.containsKey(path)

  val pathMapSize: Int
    get() = pathMap.size

  fun addPath(path: String, agentContext: AgentContext) {
    require(path.isNotEmpty()) { EMPTY_PATH }
    synchronized(pathMap) {
      pathMap[path] = agentContext
      if (!isTestMode)
        logger.info { "Added path /$path for $agentContext" }
    }
  }

  fun removePath(path: String, agentId: String): UnregisterPathResponse {
    require(path.isNotEmpty()) { EMPTY_PATH }
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    synchronized(pathMap) {
      val agentContext = pathMap[path]
      return when {
        agentContext == null -> {
          val msg = "Unable to remove path /$path - path not found"
          logger.error { msg }
          unregisterPathResponse {
            valid = false
            reason = msg
          }
        }
        agentContext.agentId != agentId -> {
          val msg = "Unable to remove path /$path - invalid agentId: $agentId (owner is ${agentContext.agentId})"
          logger.error { msg }
          unregisterPathResponse {
            valid = false
            reason = msg
          }
        }
        else -> {
          pathMap.remove(path)
          if (!isTestMode)
            logger.info { "Removed path /$path for $agentContext" }
          unregisterPathResponse {
            valid = true
            reason = ""
          }
        }
      }
    }
  }

  fun removePathByAgentId(agentId: String) {
    require(agentId.isNotEmpty()) { EMPTY_AGENTID }
    synchronized(pathMap) {
      pathMap.forEach { (k, v) ->
        if (v.agentId == agentId)
          pathMap.remove(k)?.also { if (!isTestMode) logger.info { "Removed path /$k for context: $it" } }
              ?: logger.error { "Missing path /$k for agentId: $agentId" }
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
                .map { c -> "/${c.key.padEnd(maxPath)} ${c.value}" }
                .joinToString("\n")
      }

  companion object : KLogging()
}