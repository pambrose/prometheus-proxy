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
import io.prometheus.common.Messages.BLANK_PATH_MSG
import io.prometheus.common.Utils.defaultEmptyJsonObject
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.grpc.PathRejectionCause.CONSOLIDATION_MISMATCH
import io.prometheus.grpc.PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
  // Where retry backoffs are measured; injectable so tests advance time instead of sleeping, as HttpClientCache does.
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val agentConfigVals = agent.configVals.agent

  // The loop that retries each kind of rejection: Agent.connectToProxy's retry task for static paths, the discovery
  // reconcile for discovered ones. A backoff is measured from its own loop's tick, so the first retry still lands one
  // tick after the rejection and only a repeat is paced out.
  private val staticRetryInterval = agentConfigVals.internal.rejectedPathRetrySecs.seconds
  private val discoveredRetryInterval = agentConfigVals.discovery.reconcileIntervalSecs.seconds
  private val pathContextMap = ConcurrentHashMap<String, PathContext>()
  private val pathMutex = Mutex()

  // The proxy's latest rejection of each discovered path, by normalized path; see registerDiscoveredPath. Guarded
  // by pathMutex.
  private val discoveredRejections = HashMap<String, DiscoveredRejection>()

  operator fun get(path: String): PathContext? = pathContextMap[path]

  // Routed through pathMutex so a dynamic registerPath racing a reconcile can't insert a stale
  // PathContext into the freshly-cleared map (finding 19). A reconnect clears first, which forgets every
  // rejection, so each static and discovered path is tried again.
  suspend fun clear() =
    pathMutex.withLock {
      pathContextMap.clear()
      rejectedStaticPaths.clear()
      discoveredRejections.clear()
    }

  suspend fun pathMapSize(): Int = agent.grpcService.pathMapSize()

  // A blank path or url could never register, and registerPaths does not catch the require() that would reject it,
  // so one config typo would fail every connect attempt. Dropped here instead, the way discovery drops its own.
  private val pathConfigs: List<PathConfig> =
    agentConfigVals.pathConfigs
      .map { PathConfig(name = it.name, path = it.path, url = it.url, labels = it.labels) }
      .filter { config ->
        config.usable.also {
          if (!it)
            logger.warn {
              "Skipping pathConfigs entry ${config.quotedName}: a path and a url are required " +
                "(path='${config.path}', url='${sanitizeUrl(config.url)}')"
            }
        }
      }
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

  // Static paths whose last registration the proxy rejected as retryable, for retryRejectedStaticPaths to retry, each
  // with the backoff that says when its next retry is due.
  private val rejectedStaticPaths = ConcurrentHashMap<PathConfig, RetryBackoff>()

  // A proxy rejecting one path (valid=false -- e.g. the agent's identity isn't authorized for it) must not
  // abort registration of the others: that used to end the connection, and the agent then reconnected
  // forever with every path down. A proxy that rejects every static path isn't usable, though, so that
  // fails the attempt and lets EndpointFailover move on. Transport failures still propagate: the
  // connection is gone.
  suspend fun registerPaths() {
    val rejectedCount = pathConfigs.count { registerStaticPath(it, repeat = false) != null }
    if (pathConfigs.isNotEmpty() && rejectedCount == pathConfigs.size)
      throw RequestFailureException("Proxy rejected all ${pathConfigs.size} static paths")
  }

  val hasRejectedStaticPaths: Boolean
    get() = rejectedStaticPaths.isNotEmpty()

  // Registers a static path, keeping rejectedStaticPaths in step, and returns the proxy's rejection, or null once
  // registered. Only a rejection whose cause is in RETRYABLE_CAUSES stays for retrying; any other can't clear before a
  // reconnect. repeat says whether a retryable rejection was already logged. A transport failure propagates.
  private suspend fun registerStaticPath(
    config: PathConfig,
    repeat: Boolean,
  ): RequestFailureException? =
    try {
      registerPath(config.path, config.url, config.labels)
      rejectedStaticPaths -= config
      null
    } catch (e: RequestFailureException) {
      val backoff =
        if (e.retryable) {
          nextBackoff(rejectedStaticPaths[config], staticRetryInterval).also { rejectedStaticPaths[config] = it }
        } else {
          rejectedStaticPaths -= config
          null
        }
      logRejection(PathSource.STATIC, config.path.removePrefix("/"), e, repeat, backoff?.wait)
      e
    }

  // Retries each static path the proxy rejected as retryable. Static paths are otherwise registered only at connect,
  // but such a rejection can clear while the agent stays connected: the live agent that held the path disconnects.
  // Each path is isolated, as in reconcileDiscoveredPaths, so no failure ends the connection, whose other tasks
  // already end it when it is really gone.
  suspend fun retryRejectedStaticPaths() {
    for ((config, backoff) in rejectedStaticPaths.toList()) {
      // Paced by the path's own backoff rather than retried on every tick: a conflict that lasts would otherwise cost
      // the proxy a round trip per rejected path per interval for the life of the connection.
      if (!backoff.isDue)
        continue
      val path = config.path.removePrefix("/")
      runCatchingCancellable { registerStaticPath(config, repeat = true) }
        .onSuccess { rejection ->
          if (rejection == null)
            logger.info { "Registered static path /$path after the proxy had rejected it" }
        }.onFailure { e -> logger.warn(e) { "Failed to retry static path /$path" } }
    }
  }

  // Logs the proxy's rejection of a path. A rejection is the proxy's answer, not a fault, so it carries no stack
  // trace: one that can't clear is logged at WARN, and a retryable one at WARN the first time and at DEBUG on a
  // repeat. [retryIn] is how long the next retry waits, so a lasting conflict's log says when it will be tried again.
  private fun logRejection(
    source: PathSource,
    path: String,
    e: RequestFailureException,
    repeat: Boolean,
    retryIn: Duration?,
  ) {
    val kind = source.name.lowercase()
    // A zero wait means the next tick of the retry loop, which is what "retrying" has always meant here.
    val retrying = if (retryIn == null || retryIn == Duration.ZERO) "retrying" else "retrying in $retryIn"
    when {
      !e.retryable -> logger.warn { "Proxy rejected $kind path /$path, not retrying: ${e.message}" }
      !repeat -> logger.warn { "Proxy rejected $kind path /$path, $retrying: ${e.message}" }
      else -> logger.debug { "Proxy still rejects $kind path /$path, $retrying: ${e.message}" }
    }
  }

  // The backoff for a path the proxy rejected for a cause that can clear, after [prior] (null when this is its first
  // rejection). The first retry still comes one [base] later -- the retry loop's own tick -- and each further
  // rejection doubles the wait, capped at MAX_RETRY_BACKOFF. A conflict that lasts then costs the proxy a handful of
  // round trips an hour instead of one every tick; the price is that a cleared conflict takes up to the current wait
  // to be noticed.
  private fun nextBackoff(
    prior: RetryBackoff?,
    base: Duration,
  ): RetryBackoff {
    val attempts = (prior?.attempts ?: 0) + 1
    val wait =
      if (attempts <= 1)
        Duration.ZERO
      else
        minOf(base * (1 shl (attempts - 2).coerceAtMost(MAX_BACKOFF_DOUBLINGS)), MAX_RETRY_BACKOFF)
    return RetryBackoff(attempts, wait, timeSource.markNow() + wait)
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
   * not abort the rest of the reconcile. A failure is retried on the next call (reconcile is idempotent),
   * except a rejection for a cause that can't clear: see [registerDiscoveredPath].
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

      // Forget the rejections of paths no longer desired.
      discoveredRejections.keys.retainAll(desiredByPath.keys)

      // Register new discovered paths and re-register changed ones (unregister-then-register keeps the
      // local mapping and the proxy's stored labels in agreement).
      for ((path, entry) in desiredByPath) {
        val current = pathContextMap[path]
        val labels = entry.labels.defaultEmptyJsonObject()
        if (current != null && current.url == entry.url && current.labels == labels)
          continue // Unchanged discovered path.
        registerDiscoveredPath(path, entry, labels, current)
      }
    }

  // Registers, or re-registers, one discovered path; callers MUST hold pathMutex. The failure is recorded in
  // discoveredRejections, and a rejection whose cause can't clear (see RETRYABLE_CAUSES) isn't tried again until the
  // entry's URL or labels change, it leaves the desired set and returns, or the agent reconnects. Any other failure
  // is still retried on the next reconcile, and its stack trace is logged once rather than on every one.
  private suspend fun registerDiscoveredPath(
    path: String,
    entry: DiscoveredPath,
    labels: String,
    current: PathContext?,
  ) {
    val prior = discoveredRejections[path]?.takeIf { it.url == entry.url && it.labels == labels }
    if (prior?.retryable == false)
      return
    // A rejection that can clear is retried on a backoff, so a lasting conflict costs the proxy a round trip every
    // few minutes rather than one on every poll. A changed URL or labels is a different registration, and leaves
    // prior null, so an edit to the entry is always tried at once.
    if (prior?.backoff?.isDue == false)
      return
    runCatchingCancellable {
      if (current != null)
        doUnregisterPath(path)
      doRegisterPath(path, entry.url, entry.labels, PathSource.DISCOVERED)
    }.onSuccess {
      discoveredRejections -= path
    }.onFailure { e ->
      val failure = "${e::class.simpleName}: ${e.message}"
      val repeat = prior?.failure == failure
      // Only a rejection is backed off: it is the proxy's answer, and every retry costs it a round trip. Any other
      // failure never reached the proxy -- a transport failure means the connection is already going -- so it keeps
      // the reconcile's own pace.
      val rejection = e as? RequestFailureException
      val backoff = if (rejection?.retryable == true) nextBackoff(prior?.backoff, discoveredRetryInterval) else null
      discoveredRejections[path] =
        DiscoveredRejection(entry.url, labels, rejection == null || rejection.retryable, failure, backoff)
      if (rejection != null)
        logRejection(PathSource.DISCOVERED, path, rejection, repeat, backoff?.wait)
      else if (repeat)
        logger.debug { "Still failing to register discovered path /$path: $failure" }
      else
        logger.warn(e) { "Failed to register discovered path /$path" }
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
    require(pathVal.isNotBlank()) { BLANK_PATH_MSG }
    require(url.isNotBlank()) { "Blank URL" }

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
    require(pathVal.isNotBlank()) { BLANK_PATH_MSG }

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

  // The paths discovery registered, for the agent's debug page: toPlainText lists only the static config, so a
  // discovery-only agent showed nothing at all.
  fun discoveredToPlainText(): String {
    val discovered = pathContextMap.values.filter { it.source == PathSource.DISCOVERED }.sortedBy { it.path }
    if (discovered.isEmpty())
      return "Discovered Paths: none"
    val maxPath = discovered.maxOf { it.path.length }
    return "Discovered Paths:\n" +
      discovered.joinToString("\n") { "/${it.path.padEnd(maxPath)} ${sanitizeUrl(it.url)}" }
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

    // The rejection causes that clear while the agent stays connected: a live agent holds the path, and it leaves
    // eventually. No other cause does, including none (a proxy predating the field) and one this agent doesn't know.
    private val RETRYABLE_CAUSES = setOf(HELD_BY_ANOTHER_IDENTITY, CONSOLIDATION_MISMATCH)

    // The longest a retry of a rejection that can clear ever waits. It bounds both the cost of a lasting conflict --
    // a dozen round trips an hour per path rather than one per retry interval -- and how late a cleared one is picked
    // up.
    private val MAX_RETRY_BACKOFF = 5.minutes

    // Bounds the shift in nextBackoff so it cannot overflow; MAX_RETRY_BACKOFF is the cap that governs in practice.
    private const val MAX_BACKOFF_DOUBLINGS = 16

    private val RequestFailureException.retryable: Boolean
      get() = rejectionCause in RETRYABLE_CAUSES
  }

  // The last failure to register a discovered entry. [retryable] says whether the entry stays in the desired set;
  // [failure] identifies the failure, so an identical repeat is logged at DEBUG instead of a fresh stack trace.
  private data class DiscoveredRejection(
    val url: String,
    val labels: String,
    val retryable: Boolean,
    val failure: String,
    // Null when the failure was not a rejection, which is not backed off; see registerDiscoveredPath.
    val backoff: RetryBackoff? = null,
  )

  // When a path the proxy rejected for a cause that can clear may be retried: [attempts] counts the consecutive
  // rejections behind the current [wait], and [dueAt] is when that wait is up.
  private data class RetryBackoff(
    val attempts: Int,
    val wait: Duration,
    val dueAt: TimeMark,
  ) {
    val isDue: Boolean get() = dueAt.hasPassedNow()
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

    // Mirrors DiscoveredPath.usable: doRegisterPath requires both, so an entry missing either can never register.
    val usable: Boolean get() = path.isNotBlank() && url.isNotBlank()
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
