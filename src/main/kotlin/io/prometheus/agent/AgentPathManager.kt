/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import com.google.common.collect.Maps.newConcurrentMap
import io.prometheus.Agent
import mu.KLogging
import java.util.concurrent.ConcurrentMap

class AgentPathManager(private val agent: Agent) {

  private val agentConfigVals = agent.configVals.agent
  private val pathContextMap: ConcurrentMap<String, PathContext> = newConcurrentMap()

  operator fun get(path: String): PathContext? = pathContextMap[path]

  fun clear() = pathContextMap.clear()

  fun pathMapSize(): Int = agent.grpcService.pathMapSize()

  private val pathConfigs =
    agentConfigVals.pathConfigs
      .map {
        mapOf(NAME to "\"" + it.name + "\"",
              PATH to it.path,
              URL to it.url)
      }
      .onEach { logger.info { "Proxy path /${it["path"]} will be assigned to ${it["url"]}" } }

  fun registerPaths() =
    pathConfigs.forEach {
      val path = it["path"]
      val url = it["url"]
      if (path != null && url != null) {
        registerPath(path, url)
      } else {
        logger.error { "Null path/url values: $path/$url" }
      }
    }

  fun registerPath(pathVal: String, url: String) {
    val path = if (pathVal.startsWith("/")) pathVal.substring(1) else pathVal
    val pathId = agent.grpcService.registerPathOnProxy(path)
    if (!agent.isTestMode)
      logger.info { "Registered $url as /$path" }
    pathContextMap[path] = PathContext(pathId, path, url)
  }

  fun unregisterPath(pathVal: String) {
    val path = if (pathVal.startsWith("/")) pathVal.substring(1) else pathVal
    agent.grpcService.unregisterPathOnProxy(path)
    val pathContext = pathContextMap.remove(path)
    when {
      pathContext == null -> logger.info { "No path value /$path found in pathContextMap" }
      !agent.isTestMode -> logger.info { "Unregistered /$path for ${pathContext.url}" }
    }
  }

  override fun toString(): String {
    val maxName = pathConfigs.map { it[NAME]?.length ?: 0 }.max() ?: 0
    val maxPath = pathConfigs.map { it[PATH]?.length ?: 0 }.max() ?: 0

    return "Agent Path Configs\n" + "Name".padEnd(maxName + 1) + "Path".padEnd(maxPath + 3) + "URL\n" +
        pathConfigs.map { c ->
          "${c[NAME]?.padEnd(maxName)} /${c[PATH]?.padEnd(maxPath)} ${c[URL]}"
        }.joinToString("\n")
  }

  companion object : KLogging() {
    private const val NAME = "name"
    private const val PATH = "path"
    private const val URL = "url"
  }

  data class PathContext(val pathId: Long, val path: String, val url: String)
}