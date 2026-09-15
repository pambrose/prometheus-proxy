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
import io.prometheus.agent.filter.MetricFilter
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.common.Utils.defaultEmptyJsonObject
import io.prometheus.common.Utils.sanitizeUrl
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
        logger.info { "Proxy path /${it.path} will be assigned to ${sanitizeUrl(it.url)} with labels ${it.labels}" }
      }

  // Normalized pathConfigs paths. reconcileDiscoveredPaths skips these even while unregistered -- rejected by the
  // proxy and awaiting retryRejectedStaticPaths -- so a discovered entry never takes over a configured static path.
  private val configuredStaticPaths: Set<String> = pathConfigs.mapTo(HashSet()) { it.path.removePrefix("/") }

  // Compiled once at startup, never per scrape. Keyed by normalized path, so a filter applies to
  // that path regardless of whether it was registered statically or by discovery.
  private val filtersByPath: Map<String, MetricFilter> =
    agentConfigVals.filters
      .mapNotNull { cfg ->
        val path = cfg.path.removePrefix("/")
        MetricFilter.createOrNull(cfg.metricNameAllow, cfg.metricNameDeny, path)?.let { path to it }
      }
      .toMap()

  // Static paths whose last registration the proxy rejected as retryable, for retryRejectedStaticPaths to retry.
  private val rejectedStaticPaths = ConcurrentHashMap.newKeySet<PathConfig>()

  // A proxy rejecting one path (valid=false -- e.g. the agent's identity isn't authorized for it) must not
  // abort registration of the others: that used to end the connection, and the agent then reconnected
  // forever with every path down. A proxy that rejects every static path isn't usable, though, so that
  // fails the attempt and lets EndpointFailover move on. Transport failures still propagate: the
  // connection is gone.
  suspend fun registerPaths() {
    rejectedStaticPaths.clear()
    val rejectedCount =
      pathConfigs.count { config ->
        registerStaticPath(config)?.also { e ->
          if (e.retryable)
            logger.warn { "Proxy rejected static path /${config.path.removePrefix("/")}, retrying: ${e.message}" }
        } != null
      }
    if (pathConfigs.isNotEmpty() && rejectedCount == pathConfigs.size)
      throw RequestFailureException("Proxy rejected all ${pathConfigs.size} static paths")
  }

  val hasRejectedStaticPaths: Boolean
    get() = rejectedStaticPaths.isNotEmpty()

  // Registers a static path, keeping rejectedStaticPaths in step, and returns the proxy's rejection, or null once
  // registered. Only a retryable rejection (see RegisterPathResponse.retryable) stays for retrying; any other can't
  // clear before a reconnect, so it is dropped and logged here, once. A transport failure propagates.
  private suspend fun registerStaticPath(config: PathConfig): RequestFailureException? =
    try {
      registerPath(config.path, config.url, config.labels)
      rejectedStaticPaths -= config
      null
    } catch (e: RequestFailureException) {
      if (e.retryable) {
        rejectedStaticPaths += config
      } else {
        rejectedStaticPaths -= config
        logger.warn { "Proxy rejected static path /${config.path.removePrefix("/")}, not retrying: ${e.message}" }
      }
      e
    }

  // Retries each static path the proxy rejected as retryable. Static paths are otherwise registered only at connect,
  // but such a rejection can clear while the agent stays connected: the live agent that held the path disconnects.
  // Each path is isolated, as in reconcileDiscoveredPaths, so no failure ends the connection, whose other tasks
  // already end it when it is really gone.
  suspend fun retryRejectedStaticPaths() {
    for (config in rejectedStaticPaths.toList()) {
      val path = config.path.removePrefix("/")
      runCatchingCancellable { registerStaticPath(config) }
        .onSuccess { rejection ->
          if (rejection == null)
            logger.info { "Registered static path /$path after the proxy had rejected it" }
          else if (rejection.retryable)
            logger.debug { "Proxy still rejects static path /$path: ${rejection.message}" }
        }.onFailure { e -> logger.warn(e) { "Failed to retry static path /$path" } }
    }
  }

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
   * desired path colliding with a [PathSource.STATIC] path, or with a configured static path the proxy
   * rejected, is skipped (static wins). Each per-path operation is isolated, so one failing path does
   * not abort the rest of the reconcile; the failure is retried on the next call (reconcile is idempotent).
   */
  suspend fun reconcileDiscoveredPaths(desired: List<DiscoveredPath>) =
    pathMutex.withLock {
      // Build the desired discovered set keyed by normalized path; drop collisions with STATIC paths.
      val desiredByPath = LinkedHashMap<String, DiscoveredPath>()
      for (entry in desired) {
        val path = entry.path.removePrefix("/")
        if (path in configuredStaticPaths || pathContextMap[path]?.source == PathSource.STATIC) {
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
    val pathId = agent.grpcService.registerPathOnProxy(path, labelsJson, url, source.name).pathId
    // Whether a filter attached is only knowable here, where the path is actually resolved -- which is
    // why it is reported here rather than cross-checked against pathConfigs at construction time, a
    // baseline that by definition cannot see discovered or runtime-registered paths. Absence of the
    // suffix on a path you configured a filter for means the two path strings do not match.
    val filter = filtersByPath[path]
    if (!agent.isTestMode) {
      logger.info {
        "Registered ${sanitizeUrl(url)} as /$path with labels $labelsJson (${source.name.lowercase()})" +
          if (filter != null) " with a metric filter" else ""
      }
    }
    pathContextMap[path] = PathContext(pathId, path, url, labelsJson, source, filter)
  }

  // Lock-free unregistration body; callers MUST hold pathMutex (see doRegisterPath).
  private suspend fun doUnregisterPath(pathVal: String) {
    require(pathVal.isNotEmpty()) { EMPTY_PATH_MSG }

    val path = pathVal.removePrefix("/")
    // The proxy rejects an unregister (valid=false) only when it no longer maps this path to this agent: the path
    // is gone, or another agent now holds it. The local entry is stale either way, so it is removed below. Keeping
    // it made reconcile retry forever and blocked a changed URL from ever applying. Transport failures still
    // propagate and keep the entry, so the next reconcile retries them.
    try {
      agent.grpcService.unregisterPathOnProxy(path)
    } catch (e: RequestFailureException) {
      logger.warn { "Proxy no longer holds /$path for this agent; removing the local entry: ${e.message}" }
    }
    val pathContext = pathContextMap.remove(path)
    if (pathContext == null) {
      logger.info { "No path value /$path found in pathContextMap when unregistering" }
    } else if (!agent.isTestMode) {
      logger.info { "Unregistered /$path for ${sanitizeUrl(pathContext.url)}" }
    }
  }

  fun toPlainText(): String {
    val maxName = pathConfigs.maxOfOrNull { it.quotedName.length } ?: 0
    val maxPath = pathConfigs.maxOfOrNull { it.path.length } ?: 0
    return "Agent Path Configs:\n" + "Name".padEnd(maxName + 1) + "Path".padEnd(maxPath + 2) + "URL\n" +
      pathConfigs.joinToString("\n") { c ->
        "${c.quotedName.padEnd(maxName)} /${c.path.padEnd(maxPath)} ${sanitizeUrl(c.url)}"
      }
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
    val filter: MetricFilter? = null,
  )
}
