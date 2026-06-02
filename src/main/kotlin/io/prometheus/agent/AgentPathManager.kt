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

package io.prometheus.agent

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.common.Utils.defaultEmptyJsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the agent's path registration lifecycle with the proxy.
 *
 * Loads path configurations from the agent's HOCON config, registers each path with
 * the proxy via gRPC, and maintains a local map of path to [PathContext] (URL, labels,
 * path ID). Supports dynamic registration and unregistration at runtime.
 *
 * @param agent the parent [Agent] instance
 * @see AgentGrpcService
 * @see io.prometheus.Agent
 */
internal class AgentPathManager(
  private val agent: Agent,
) {
  private val agentConfigVals = agent.configVals.agent
  private val pathContextMap = ConcurrentHashMap<String, PathContext>()
  private val pathMutex = Mutex()

  operator fun get(path: String): PathContext? = pathContextMap[path]

  fun clear() = pathContextMap.clear()

  suspend fun pathMapSize(): Int = agent.grpcService.pathMapSize()

  private val pathConfigs: List<PathConfig> =
    agentConfigVals.pathConfigs
      .map { PathConfig(name = it.name, path = it.path, url = it.url, labels = it.labels) }
      .onEach {
        logger.info { "Proxy path /${it.path} will be assigned to ${it.url} with labels ${it.labels}" }
      }

  suspend fun registerPaths() = pathConfigs.forEach { registerPath(it.path, it.url, it.labels) }

  suspend fun registerPath(
    pathVal: String,
    url: String,
    labels: String = "{}",
  ) {
    require(pathVal.isNotEmpty()) { EMPTY_PATH_MSG }
    require(url.isNotEmpty()) { "Empty URL" }

    val path = pathVal.removePrefix("/")
    val labelsJson = labels.defaultEmptyJsonObject()
    val pathId = agent.grpcService.registerPathOnProxy(path, labelsJson).pathId
    pathMutex.withLock {
      if (!agent.isTestMode)
        logger.info { "Registered $url as /$path with labels $labelsJson" }
      pathContextMap[path] = PathContext(pathId, path, url, labelsJson)
    }
  }

  suspend fun unregisterPath(pathVal: String) {
    require(pathVal.isNotEmpty()) { EMPTY_PATH_MSG }

    val path = pathVal.removePrefix("/")
    agent.grpcService.unregisterPathOnProxy(path)
    pathMutex.withLock {
      val pathContext = pathContextMap.remove(path)
      if (pathContext == null) {
        logger.info { "No path value /$path found in pathContextMap when unregistering" }
      } else if (!agent.isTestMode) {
        logger.info { "Unregistered /$path for ${pathContext.url}" }
      }
    }
  }

  fun toPlainText(): String {
    val maxName = pathConfigs.maxOfOrNull { it.quotedName.length } ?: 0
    val maxPath = pathConfigs.maxOfOrNull { it.path.length } ?: 0
    return "Agent Path Configs:\n" + "Name".padEnd(maxName + 1) + "Path".padEnd(maxPath + 2) + "URL\n" +
      pathConfigs.joinToString("\n") { c -> "${c.quotedName.padEnd(maxName)} /${c.path.padEnd(maxPath)} ${c.url}" }
  }

  companion object {
    private val logger = logger {}
  }

  // Strongly-typed view of a single `agent.pathConfigs` entry, replacing the prior magic-string map.
  private data class PathConfig(
    val name: String,
    val path: String,
    val url: String,
    val labels: String,
  ) {
    // Name wrapped in double-quotes for the toPlainText() display, preserving the original format.
    val quotedName: String get() = "\"$name\""
  }

  data class PathContext(
    val pathId: Long,
    val path: String,
    val url: String,
    val labels: String,
  )
}
