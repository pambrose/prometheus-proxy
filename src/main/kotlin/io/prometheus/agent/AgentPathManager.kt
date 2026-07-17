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

import com.pambrose.common.util.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.agent.discovery.DiscoveredPath
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.common.Utils.defaultEmptyJsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Ownership of a registered path. [STATIC] means "not managed by discovery" — config-driven
 * `pathConfigs` entries and any manual runtime registrations — and is never touched by
 * [AgentPathManager.reconcileDiscoveredPaths]; [DISCOVERED] paths are owned (added, updated,
 * and removed) by the reconciler.
 */
internal enum class PathSource {
  STATIC,
  DISCOVERED,
}

/**
 * Manages the agent's path registration lifecycle with the proxy.
 *
 * Loads static path configurations from the agent's HOCON config, registers each path with the
 * proxy via gRPC, and maintains a local map of path to [PathContext] (URL, labels, path ID, source).
 * Supports dynamic registration/unregistration at runtime and, via [reconcileDiscoveredPaths],
 * reconciling the [PathSource.DISCOVERED] subset against a desired set without touching the
 * [PathSource.STATIC] baseline.
 *
 * @param agent the parent [Agent] instance
 * @see AgentGrpcService
 * @see io.prometheus.agent.discovery.PathDiscoveryService
 */
internal class AgentPathManager(
  private val agent: Agent,
) {
  private val agentConfigVals = agent.configVals.agent
  private val pathContextMap = ConcurrentHashMap<String, PathContext>()
  private val pathMutex = Mutex()

  operator fun get(path: String): PathContext? = pathContextMap[path]

  // Routed through pathMutex so a dynamic registerPath racing a reconcile can't insert a stale
  // PathContext into the freshly-cleared map (finding 19).
  suspend fun clear() = pathMutex.withLock { pathContextMap.clear() }

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
  ) = pathMutex.withLock { doRegisterPath(pathVal, url, labels, PathSource.STATIC) }

  suspend fun unregisterPath(pathVal: String) = pathMutex.withLock { doUnregisterPath(pathVal) }

  /**
   * Reconciles the [PathSource.DISCOVERED] paths so the registered set matches [desired].
   *
   * Runs the whole diff-and-apply under [pathMutex] so it is atomic with respect to any other
   * register/unregister/clear call. Registers desired paths not yet present, unregisters discovered
   * paths no longer desired, and re-registers a discovered path whose URL or labels changed. A
   * desired path colliding with a [PathSource.STATIC] path is skipped (static wins). Each per-path
   * operation is isolated, so one failing path does not abort the rest of the reconcile; the failure
   * is retried on the next call (reconcile is idempotent).
   */
  suspend fun reconcileDiscoveredPaths(desired: List<DiscoveredPath>) =
    pathMutex.withLock {
      // Build the desired discovered set keyed by normalized path; drop collisions with STATIC paths.
      val desiredByPath = LinkedHashMap<String, DiscoveredPath>()
      for (entry in desired) {
        val path = entry.path.removePrefix("/")
        if (pathContextMap[path]?.source == PathSource.STATIC) {
          logger.warn { "Discovered path /$path collides with a static path; keeping the static entry" }
          continue
        }
        if (path in desiredByPath)
          logger.warn { "Duplicate discovered path /$path; using the last entry" }
        desiredByPath[path] = entry
      }

      // Unregister DISCOVERED paths that are no longer desired.
      val stale =
        pathContextMap.mapNotNull { (path, ctx) ->
          path.takeIf { ctx.source == PathSource.DISCOVERED && path !in desiredByPath }
        }
      for (path in stale) {
        runCatchingCancellable { doUnregisterPath(path) }
          .onFailure { logger.warn(it) { "Failed to unregister discovered path /$path" } }
      }

      // Register new discovered paths and re-register changed ones (unregister-then-register keeps the
      // local mapping and the proxy's stored labels in agreement).
      for ((path, entry) in desiredByPath) {
        val current = pathContextMap[path]
        if (current != null && current.url == entry.url && current.labels == entry.labels.defaultEmptyJsonObject())
          continue // Unchanged discovered path.
        runCatchingCancellable {
          if (current != null)
            doUnregisterPath(path)
          doRegisterPath(path, entry.url, entry.labels, PathSource.DISCOVERED)
        }.onFailure { logger.warn(it) { "Failed to register discovered path /$path" } }
      }
    }

  // Lock-free registration body; callers MUST hold pathMutex. Kotlin's Mutex is not reentrant, so
  // reconcileDiscoveredPaths (which holds the lock across the whole diff) calls this directly rather
  // than the locking registerPath wrapper.
  private suspend fun doRegisterPath(
    pathVal: String,
    url: String,
    labels: String,
    source: PathSource,
  ) {
    require(pathVal.isNotEmpty()) { EMPTY_PATH_MSG }
    require(url.isNotEmpty()) { "Empty URL" }

    val path = pathVal.removePrefix("/")
    val labelsJson = labels.defaultEmptyJsonObject()
    val pathId = agent.grpcService.registerPathOnProxy(path, labelsJson).pathId
    if (!agent.isTestMode)
      logger.info { "Registered $url as /$path with labels $labelsJson (${source.name.lowercase()})" }
    pathContextMap[path] = PathContext(pathId, path, url, labelsJson, source)
  }

  // Lock-free unregistration body; callers MUST hold pathMutex (see doRegisterPath).
  private suspend fun doUnregisterPath(pathVal: String) {
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
    val source: PathSource,
  )
}
