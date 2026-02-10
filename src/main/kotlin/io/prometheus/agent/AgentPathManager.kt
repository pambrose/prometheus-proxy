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

package io.prometheus.agent

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.common.Utils.defaultEmptyJsonObject
import java.util.concurrent.ConcurrentHashMap

internal class AgentPathManager(
  private val agent: Agent,
) {
  private val agentConfigVals = agent.configVals.agent
  private val pathContextMap = ConcurrentHashMap<String, PathContext>()

  operator fun get(path: String): PathContext? = pathContextMap[path]

  fun clear() = pathContextMap.clear()

  suspend fun pathMapSize(): Int = agent.grpcService.pathMapSize()

  private val pathConfigs =
    agentConfigVals.pathConfigs
      .map {
        mapOf(
          NAME to """"${it.name}"""",
          PATH to it.path,
          URL to it.url,
          LABELS to it.labels,
        )
      }
      .onEach {
        logger.info { "Proxy path /${it[PATH]} will be assigned to ${it[URL]} with labels ${it[LABELS]}" }
      }

  suspend fun registerPaths() =
    pathConfigs.forEach {
      val path = it[PATH]
      val url = it[URL]
      val labels = it[LABELS]
      if (path != null && url != null && labels != null)
        registerPath(path, url, labels)
      else
        logger.error { "Null path/url/labels value: $path/$url/$labels" }
    }

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
    if (!agent.isTestMode)
      logger.info { "Registered $url as /$path with labels $labelsJson" }
    pathContextMap[path] = PathContext(pathId, path, url, labelsJson)
  }

  suspend fun unregisterPath(pathVal: String) {
    require(pathVal.isNotEmpty()) { EMPTY_PATH_MSG }

    val path = pathVal.removePrefix("/")
    agent.grpcService.unregisterPathOnProxy(path)
    val pathContext = pathContextMap.remove(path)
    if (pathContext == null) {
      logger.info { "No path value /$path found in pathContextMap when unregistering" }
    } else if (!agent.isTestMode) {
      logger.info { "Unregistered /$path for ${pathContext.url}" }
    }
  }

  fun toPlainText(): String {
    val maxName = pathConfigs.maxOfOrNull { it[NAME].orEmpty().length } ?: 0
    val maxPath = pathConfigs.maxOfOrNull { it[PATH].orEmpty().length } ?: 0
    return "Agent Path Configs:\n" + "Name".padEnd(maxName + 1) + "Path".padEnd(maxPath + 2) + "URL\n" +
      pathConfigs.joinToString("\n") { c -> "${c[NAME]?.padEnd(maxName)} /${c[PATH]?.padEnd(maxPath)} ${c[URL]}" }
  }

  companion object {
    private val logger = logger {}
    private const val NAME = "name"
    private const val PATH = "path"
    private const val URL = "url"
    private const val LABELS = "labels"
  }

  data class PathContext(
    val pathId: Long,
    val path: String,
    val url: String,
    val labels: String,
  )
}
