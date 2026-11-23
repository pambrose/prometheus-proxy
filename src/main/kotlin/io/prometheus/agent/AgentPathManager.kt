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

import io.prometheus.common.MetricBuilders.rejectionCauseLabel
import com.pambrose.common.util.runCatchingCancellable
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.agent.discovery.DiscoveredPath
import io.prometheus.agent.filter.MetricFilter
import io.prometheus.common.Messages.BLANK_PATH_MSG
import io.prometheus.common.Utils.defaultEmptyJsonObject
import io.prometheus.common.Utils.failureKey
import io.prometheus.common.AgentValues.labelsProblem
import io.prometheus.common.AgentValues.pathCharacterProblem
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.grpc.PathNotice
import io.prometheus.grpc.PathRejectionCause.CONSOLIDATION_MISMATCH
import io.prometheus.grpc.PathRejectionCause.HELD_BY_ANOTHER_AGENT
import io.prometheus.grpc.PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
import io.prometheus.grpc.PathRejectionCause.INVALID_AGENT
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import io.prometheus.grpc.PathRejectionCause

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

  // The retry backoff (see nextBackoff): each kind of path's base is the tick of the loop that retries it --
  // Agent.retryRejectedStaticPathsForConnection for static paths, the discovery reconcile for discovered ones -- and
  // one cap bounds both. Both loops wait a full interval after each pass, so a cap at or below a loop's interval makes
  // every tick due, which turns that loop's backoff off.
  private val staticRetryInterval = agentConfigVals.internal.rejectedPathRetrySecs.seconds
  private val discoveredRetryInterval = agentConfigVals.discovery.reconcileIntervalSecs.seconds
  private val maxRetryBackoff = agentConfigVals.internal.rejectedPathRetryMaxSecs.seconds

  // Each path this agent serves, by normalized path: what it points at, and whether the proxy said another agent took
  // it over (see notePathNotice), until this agent takes it back or unregisters it; a reconnect keeps a taken-over path
  // (see clear). A taken-over path stays meanwhile: a scrape sent before the takeover still finds it, the discovery
  // reconcile sees an entry it has and leaves it alone, and a static path still counts as served. One map, so a path's
  // takeover goes with it, and a notice that marks a path it no longer has finds nothing to mark. Notices update it
  // without pathMutex, so a notice never waits on a reconcile; the wake rouses reclaimTakenOverPathsWhile.
  private val paths = ConcurrentHashMap<String, PathEntry>()
  private val reclaimWake = Channel<Unit>(CONFLATED)
  private val pathMutex = Mutex()

  // The proxy's latest rejection of each discovered path, by normalized path; see registerDiscoveredPath. Guarded
  // by pathMutex.
  private val discoveredRejections = HashMap<String, DiscoveredRejection>()

  // The failure that last kept each stale discovered path registered, by normalized path, as Utils.failureKey gives
  // it; see unregisterStaleDiscoveredPaths. Guarded by pathMutex.
  private val staleUnregisterFailures = HashMap<String, String>()

  // The discovered paths last reported for each kind of conflict; see reportConflict. Guarded by pathMutex.
  private val reportedConflicts = EnumMap<DiscoveryConflict, Set<String>>(DiscoveryConflict::class.java)

  // Each path's last failure to register, by normalized path, until it registers, is unregistered, leaves the
  // discovered set, or the agent reconnects, for the agent's dashboard: the proxy shows none of a path it refused.
  private val registrationFailures = ConcurrentHashMap<String, RegistrationFailure>()

  // The path doRegisterPath is registering, with the latest notice about it that came while the proxy answered, or
  // NO_NOTICE. The notice and the answer come on different streams, so the notice can come first: it applies once the
  // path is recorded, before the registration's caller sees the answer, and goes with a refusal. Written under
  // pathMutex, and by a notice only through computeIfPresent, so it never outlives the registration.
  private val registering = ConcurrentHashMap<String, PathNotice>()

  // Every scrape's lookup of the path it asks for comes through here, which looks it up by its key (see
  // requestedPathKey), so an agent behind a proxy older than #416 serves a path asked for with extra leading slashes.
  // Registration, unregistration, and the discovery reconcile read the map directly, by the keys they normalize.
  operator fun get(path: String): PathContext? = paths[requestedPathKey(path)]?.context

  // Routed through pathMutex so a dynamic registerPath racing a reconcile can't insert a stale
  // PathContext into the freshly-cleared map (finding 19). A reconnect clears first, which forgets every
  // rejection, so each static and discovered path is tried again. A path another agent took over is kept, entry and
  // all, for the reclaim task to try at once on the new connection: the proxy may have freed it meanwhile, and its
  // refusal puts the new connection on the proxy's waiting list. Its wait is kept, for a refusal to double, and it
  // shows as taken over again in place of a refusal for another cause, which the connection that ended gave.
  suspend fun clear() =
    pathMutex.withLock {
      paths.values.removeIf { it.takenOver == null }
      rejectedStaticPaths.clear()
      discoveredRejections.clear()
      staleUnregisterFailures.clear()
      reportedConflicts.clear()
      discoveryConflicts = DiscoveryConflicts(emptyList(), emptyList())
      registrationFailures.clear()
      val now = timeSource.markNow()
      paths.replaceAll { _, entry ->
        entry.copy(takenOver = entry.takenOver?.let { TakenOver(it.holder, it.backoff.copy(dueAt = now)) })
      }
    }

  suspend fun pathMapSize(): Int = agent.grpcService.pathMapSize()

  // The path an embedded agent serves its own metrics at (EmbeddedAgentConfig.Builder.agentMetricsAt), or null. Its
  // source reads the agent's launch ID at each scrape, as it isn't assigned yet when the paths are read.
  private fun agentMetricsPathConfig(): PathConfig? =
    agent.options.agentMetricsPath.takeIf { it.isNotEmpty() }?.let { path ->
      PathConfig(
        name = path,
        path = path,
        url = IN_PROCESS_TARGET,
        labels = agent.options.agentMetricsLabels,
        metricsSource = AgentMetricsSource { agent.launchId },
        addedBy = "agentMetricsAt()",
      )
    }

  // A blank path or url could never register, and registerPaths does not catch the require() that would reject it,
  // so one config typo would fail every connect attempt. Dropped here instead, the way discovery drops its own. The
  // paths an embedded host serves in-process (EmbeddedAgentConfig.Builder.source), and the one it serves the agent's
  // own metrics at (agentMetricsAt), come after the config's, and are checked with them, so one can't take a configured
  // path or use a character the proxy refuses.
  val pathConfigs: List<PathConfig> =
    agentConfigVals.pathConfigs
      .map { PathConfig(name = it.name, path = it.path, url = it.url, labels = it.labels) }
      .plus(agent.options.metricsSources)
      .plus(listOfNotNull(agentMetricsPathConfig()))
      .filter { config ->
        config.usable.also {
          if (!it)
            logger.warn {
              "Skipping pathConfigs entry ${config.quotedName}: a path and a url are required " +
                "(path='${config.path}', url='${sanitizeUrl(config.url)}')"
            }
        }
      }
      .also { configs ->
        // Keyed by normalized path, like agent.filters below, so a second entry would silently replace the first's URL.
        val duplicates = configs.groupBy { it.path.removePrefix("/") }.filterValues { it.size > 1 }
        require(duplicates.isEmpty()) {
          duplicates.entries.joinToString("; ", postfix = "; keep one") { (path, given) ->
            "Path /${shownValue(path)} is given by ${given.joinToString(" and ") { it.origin }}"
          }
        }
        // The proxy refuses a path no scrape can reach for a character in it (November #16), so such an entry fails
        // here, at startup, where it can be fixed, rather than at every connect.
        configs.forEach { c ->
          val problem = pathCharacterProblem(c.path.removePrefix("/"))
          require(problem == null) {
            "Path '${shownValue(c.path)}', given by ${c.origin}, is one the proxy refuses: $problem"
          }
        }
        // The proxy leaves labels it can't serve out of service discovery, so say so at startup, where it can be fixed.
        // A warning, not a failure: labels matter only to service discovery, and the path still serves scrapes.
        configs.forEach { c ->
          labelsProblem(c.labels)?.also {
            logger.warn {
              "Path /${shownValue(c.path.removePrefix("/"))}, given by ${c.origin}, has labels service discovery " +
                "will leave out: $it"
            }
          }
        }
      }
      .onEach {
        logger.info { "Proxy path /${it.path} will be assigned to ${sanitizeUrl(it.url)} with labels ${it.labels}" }
      }

  // Normalized pathConfigs paths. reconcileDiscoveredPaths skips these even while unregistered -- rejected by the
  // proxy and awaiting retryRejectedStaticPaths -- so a discovered entry never takes over a configured static path.
  private val configuredStaticPaths: Set<String> = pathConfigs.mapTo(HashSet()) { it.path.removePrefix("/") }

  // A cap at or below a loop's interval makes every tick due (see nextBackoff), which turns that loop's backoff off.
  // Nothing else would show it, so say so once, at startup. Only pathConfigs entries go through the static loop.
  init {
    if (pathConfigs.isNotEmpty() && maxRetryBackoff <= staticRetryInterval)
      logger.info { "Retry backoff is off for static paths: rejectedPathRetryMaxSecs <= rejectedPathRetrySecs" }
    if (agentConfigVals.discovery.enabled && maxRetryBackoff <= discoveredRetryInterval)
      logger.info { "Retry backoff is off for discovered paths: rejectedPathRetryMaxSecs <= reconcileIntervalSecs" }
  }

  // Compiled once at startup, never per scrape. Keyed by normalized path, so a filter applies to
  // that path regardless of whether it was registered statically or by discovery.
  val filtersByPath: Map<String, MetricFilter> =
    agentConfigVals.filters
      .also { filters ->
        // Keyed by normalized path, so a second entry for a path would silently replace the first -- and with it any
        // allow or deny rules an operator split across the two.
        val duplicates = filters.groupingBy { it.path.removePrefix("/") }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) {
          "agent.filters has more than one entry for path ${duplicates.joinToString { "/$it" }}; combine them into one"
        }
      }
      .mapNotNull { cfg ->
        val path = cfg.path.removePrefix("/")
        MetricFilter.createOrNull(cfg.metricNameAllow, cfg.metricNameDeny, path) { reason ->
          agent.activityLog?.record(AgentActivityKind.FILTER_FAILED_OPEN, path, reason, reason)
        }?.let { path to it }
      }
      .toMap()

  // Static paths whose last registration the proxy rejected as retryable, for retryRejectedStaticPaths to retry, each
  // with the backoff that says when its next retry is due, and the transport failure its last retry met, if any.
  private val rejectedStaticPaths = ConcurrentHashMap<PathConfig, StaticRetry>()

  // A proxy rejecting one path (valid=false -- e.g. the agent's identity isn't authorized for it) must not
  // abort registration of the others: that used to end the connection, and the agent then reconnected
  // forever with every path down. A proxy that rejects every static path for a cause that can't clear isn't usable,
  // though, so that fails the attempt and lets EndpointFailover move on. One rejection that can clear keeps the
  // connection, for the retry task to register that path once it does: failing the attempt would reconnect every
  // reconnectPauseSecs, and each reconnect clears the backoff. Transport failures still propagate: the connection
  // is gone. A path another agent took over is left to the reclaim task (see clear), and counts as not rejected.
  suspend fun registerPaths() {
    val rejectedCount =
      pathConfigs.count {
        paths[it.path.removePrefix("/")]?.takenOver == null && registerStaticPath(it, repeat = false) != null
      }
    val allRejected = pathConfigs.isNotEmpty() && rejectedCount == pathConfigs.size
    if (allRejected && !hasRejectedStaticPaths)
      throw RequestFailureException("Proxy rejected all ${pathConfigs.size} static paths")
  }

  val hasRejectedStaticPaths: Boolean
    get() = rejectedStaticPaths.isNotEmpty()

  // True when static paths are configured and none is registered: every one was rejected, whether at connect or on a
  // later retry that found its rejection could no longer clear. Agent ends such a connection rather than idle on it.
  fun servesNoStaticPath(): Boolean =
    pathConfigs.isNotEmpty() &&
      pathConfigs.none { paths[it.path.removePrefix("/")]?.context?.source == PathSource.STATIC }

  // Registers a static path, keeping rejectedStaticPaths in step, and returns the proxy's rejection, or null once
  // registered. Only a rejection whose cause is in RETRYABLE_CAUSES stays for retrying; any other can't clear before a
  // reconnect. repeat says whether a retryable rejection was already logged. A transport failure propagates.
  private suspend fun registerStaticPath(
    config: PathConfig,
    repeat: Boolean,
  ): RequestFailureException? =
    try {
      registerPath(config.path, config.url, config.labels, config.metricsSource)
      rejectedStaticPaths -= config
      null
    } catch (e: RequestFailureException) {
      // A null from compute removes the entry, so a rejection that can't clear drops the path from retrying. The proxy
      // answered, so the record starts with no transport failure: one that comes back is news.
      val retry =
        rejectedStaticPaths.compute(config) { _, prior ->
          if (e.retryable) StaticRetry(nextBackoff(prior?.backoff, staticRetryInterval)) else null
        }
      val path = config.path.removePrefix("/")
      recordFailure(path, e, retrying = e.retryable, retryAt = retry?.backoff?.dueAt)
      logRejection(PathSource.STATIC, path, e, repeat, retry?.backoff?.wait)
      e
    }

  // Retries each static path the proxy rejected as retryable. Static paths are otherwise registered only at connect,
  // but such a rejection can clear while the agent stays connected: the live agent that held the path disconnects.
  // Each path is isolated, as in reconcileDiscoveredPaths, so no failure ends the connection, whose other tasks
  // already end it when it is really gone. A transport failure keeps the path for retrying (see staticRetryFailed).
  suspend fun retryRejectedStaticPaths() {
    for ((config, retry) in rejectedStaticPaths.toList()) {
      // Each path waits out its own backoff; see nextBackoff.
      if (!retry.backoff.isDue)
        continue
      val path = config.path.removePrefix("/")
      runCatchingCancellable { registerStaticPath(config, repeat = true) }
        .onSuccess { rejection ->
          if (rejection == null)
            logger.info { "Registered static path /$path after the proxy had rejected it" }
        }.onFailure { e -> staticRetryFailed(config, retry, e) }
    }
  }

  // Records [e], a failure that kept the proxy from answering a retry of [config], whose record was [prior] (November
  // #42). A failure on one call needn't mean the connection is gone, so the path stays for retrying: leaving it to the
  // reconnect could leave it unregistered for the rest of a connection that survives. Its backoff advances, as on a
  // rejection, since retrying on every tick cost a round trip per path per tick while the failure lasted, and each
  // time-out held the retry loop for a whole unary deadline. As in registerDiscoveredPath, the stack trace is logged
  // when a failure first appears and a repeat at DEBUG. The proxy's answer, whose message is its reason, never comes
  // here, so as in connectAgent a failure is keyed by its gRPC status code, or its class without one, not its message.
  private fun staticRetryFailed(
    config: PathConfig,
    prior: StaticRetry,
    e: Throwable,
  ) {
    val failure = failureKey(e)
    val next = StaticRetry(nextBackoff(prior.backoff, staticRetryInterval), failure)
    // replace() changes only a path still waiting to be retried, so a concurrent clear() isn't undone.
    val path = config.path.removePrefix("/")
    if (rejectedStaticPaths.replace(config, next) != null)
      recordFailure(path, e, retrying = true, retryAt = next.backoff.dueAt)
    val retrying = retryingIn(next.backoff.wait)
    if (prior.failure == failure)
      logger.debug { "Still failing to retry static path /$path, $retrying: ${e.simpleClassName}: ${e.message}" }
    else
      logger.warn(e) { "Failed to retry static path /$path, $retrying" }
  }

  // Runs retryRejectedStaticPaths every [interval] while [active] holds -- Agent.retryRejectedStaticPathsForConnection,
  // the connection's retry task -- and returns once none is left to retry: only registerPaths adds one, and it runs
  // only at connect.
  suspend fun retryRejectedStaticPathsWhile(
    interval: Duration,
    active: () -> Boolean,
  ) {
    while (active() && hasRejectedStaticPaths) {
      delay(interval)
      retryRejectedStaticPaths()
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
    // Every refusal, the retries' repeats too, which the log and the activity feed leave out.
    agent.metrics { pathRejectionCount.labelValues(agent.launchId, rejectionCauseLabel(e.rejectionCause)).inc() }
    val kind = source.name.lowercase()
    val retrying = retryingIn(retryIn)
    if (e.retryable && repeat) {
      logger.debug { "Proxy still rejects $kind path /$path, $retrying: ${e.message}" }
      return
    }
    recordRefusal(path, e)
    if (e.retryable)
      logger.warn { "Proxy rejected $kind path /$path, $retrying: ${e.message}" }
    else
      logger.warn { "Proxy rejected $kind path /$path, not retrying: ${e.message}" }
  }

  // The proxy's refusal of [path], in the activity feed, as at WARN in the log: a refusal, and not each retry's repeat
  // of it. Grouped by the proxy's cause, as the proxy's own feed groups them, since each of its reasons names its path,
  // so a burst of paths refused for one cause is one line. A proxy that sends no cause is grouped by its reason.
  private fun recordRefusal(
    path: String,
    e: RequestFailureException,
  ) {
    val log = agent.activityLog ?: return
    val reason = ShownFailure.of(e.reason).message
    val cause = e.rejectionCause.takeUnless { it == PathRejectionCause.REJECTION_CAUSE_UNSPECIFIED }?.name ?: reason
    log.record(AgentActivityKind.PATH_REFUSED, path, reason, cause)
  }

  // How a log line says when a path is retried, given its backoff's [wait]. A zero wait means the next tick of the
  // retry loop, which is what "retrying" has always meant here.
  private fun retryingIn(wait: Duration?): String =
    if (wait == null || wait == Duration.ZERO) "retrying" else "retrying in $wait"

  // The backoff for a path the proxy rejected for a cause that can clear, given its [prior] backoff (null on the first
  // rejection). The first retry waits only for the next tick of the loop that retries the path; after that the wait
  // starts at [base] and doubles with each rejection, and with each retry of a static path that fails in transport
  // (see staticRetryFailed), up to maxRetryBackoff. Retrying on every tick cost the proxy a round trip per rejected
  // path per interval for as long as a conflict lasted; the price of pacing is that a cleared conflict is noticed up to
  // the current wait later.
  private fun nextBackoff(
    prior: RetryBackoff?,
    base: Duration,
  ): RetryBackoff {
    val wait = if (prior == null) Duration.ZERO else minOf(maxOf(prior.wait * 2, base), maxRetryBackoff)
    return RetryBackoff.after(wait, timeSource.markNow())
  }

  /**
   * Notes the proxy's [notice] about a path it took from this agent: that another agent holds it now, or that no agent
   * serves it any more. A path this agent doesn't serve is ignored, and one it is registering waits (see
   * [registering]). Taken over, the path shows as retrying, with who holds it, until [reclaimTakenOverPaths] takes it
   * back; freed, its next try is due at once.
   */
  fun notePathNotice(notice: PathNotice) {
    val path = notice.path.removePrefix("/")
    // A notice with no path is the proxy keeping the stream open.
    if (path.isEmpty() || registering.computeIfPresent(path) { _, _ -> notice } != null)
      return
    val holder = holderOf(notice)
    var prior: TakenOver? = null
    // Marks the path's own entry, so a path dropped meanwhile has nothing to mark.
    val marked =
      paths.computeIfPresent(path) { _, entry ->
        prior = entry.takenOver
        entry.copy(takenOver = takenOverBy(holder, entry.takenOver))
      }
    if (marked == null) {
      logger.debug { "Ignoring a notice about /${shownValue(path)}, a path this agent doesn't serve" }
      return
    }
    when {
      holder !is Holder.Agent -> logger.info { "No agent serves path /$path any more; taking it back" }
      prior?.holder == holder -> logger.debug { "Path /$path is still held by ${shownValue(holder.name)}" }
      else -> noteTakeover(path, holder.name)
    }
    reclaimWake.trySend(Unit)
  }

  // The agent [notice] names as the path's holder, or Nobody when it says no agent serves the path.
  private fun holderOf(notice: PathNotice): Holder =
    if (notice.heldByAgentId.isEmpty() && notice.heldByAgentName.isEmpty())
      Holder.Nobody
    else
      Holder.Agent(notice.heldByAgentName.ifEmpty { "agentId ${notice.heldByAgentId}" })

  // The takeover a notice naming [holder] makes of [current]. With Nobody holding it, the path's next try is due at
  // once. Otherwise the backoff carries on: the proxy names the holder again after each refused try, and starting the
  // backoff over would retry at its first wait for as long as the other agent holds the path. A notice naming the
  // holder [current] already names changes nothing: it can land before the refusal it came with, which advances the
  // backoff of the takeover it tried, the same object.
  private fun takenOverBy(
    holder: Holder,
    current: TakenOver?,
  ): TakenOver {
    val now = timeSource.markNow()
    if (holder == Holder.Nobody)
      return TakenOver(holder, current?.backoff?.copy(dueAt = now) ?: RetryBackoff(staticRetryInterval, now))
    if (current != null && current.holder == holder)
      return current
    return TakenOver(holder, current?.backoff ?: RetryBackoff.after(staticRetryInterval, now))
  }

  // Reports that the proxy gave [path] to [holder]: news, so at WARN and in the activity feed, as the proxy's own feed
  // has the takeover. The holder is another agent's own words, so it is escaped.
  private fun noteTakeover(
    path: String,
    holder: String,
  ) {
    val shown = shownValue(holder)
    logger.warn {
      "Proxy gave path /$path to $shown, an agent of another launch; taking it back once no agent serves it. " +
        "To serve the path from both agents, set agent.consolidated = true on each"
    }
    agent.activityLog?.record(AgentActivityKind.PATH_TAKEN_OVER, path, "By $holder", holder)
  }

  // Tries to take back each taken-over path whose retry is due within RECLAIM_WINDOW, as a reclaim, which the proxy
  // refuses while a live agent of another launch holds it. Each path's refusal sets its own next try, so the tries of
  // many taken-over paths spread out, and waking for each alone made every round scan them all once per path. Each
  // path is isolated, as in retryRejectedStaticPaths.
  suspend fun reclaimTakenOverPaths() {
    for ((path, entry) in paths) {
      val due = entry.takenOver?.backoff?.remaining?.let { it <= RECLAIM_WINDOW } ?: false
      if (!due)
        continue
      pathMutex.withLock {
        // Taken back, unregistered, or told again meanwhile: the latest entry decides.
        val current = paths[path] ?: return@withLock
        val tried = current.takenOver ?: return@withLock
        val context = current.context
        runCatchingCancellable { doRegisterPath(path, context.target, context.labels, context.source) }
          .onSuccess { logger.info { "Took back path /$path from the agent that had taken it over" } }
          .onFailure { e -> reclaimFailed(path, context.source, tried, e) }
      }
    }
  }

  // Records [e], the failure of a reclaim of [path] tried as [tried]. A refusal that can't clear drops the path, which
  // then shows as refused, as at connect, except INVALID_AGENT: the proxy has already dropped this connection's
  // context, so the connection is ending, and the takeover stays for the next connection to reclaim, as
  // ProxyRegistry.tla's told does. Dropping it would have the reconnect register the path without reclaim, taking it
  // back from the agent serving it. Anything else keeps it taken over and waits longer before the next try, unless a
  // notice came meanwhile, whose word is newer. The proxy's refusal while another agent holds the path is what
  // the takeover already shows, so it is logged at DEBUG; one for another cause, such as an agent of another identity
  // that took the path once it was free, shows as the proxy gave it, and is logged at WARN once. Callers MUST hold
  // pathMutex.
  private fun reclaimFailed(
    path: String,
    source: PathSource,
    tried: TakenOver,
    e: Throwable,
  ) {
    val rejection = e as? RequestFailureException
    if (rejection?.dropsTakeover == true) {
      paths -= path
      recordFailure(path, e, retrying = false, retryAt = null)
      logRejection(source, path, rejection, repeat = false, retryIn = null)
      return
    }
    val cause = rejection?.rejectionCause
    val updated =
      paths.computeIfPresent(path) { _, entry ->
        if (entry.takenOver === tried)
          entry.copy(takenOver = tried.refusedFor(rejection, nextBackoff(tried.backoff, staticRetryInterval)))
        else
          entry
      }?.takenOver ?: return
    val retryIn = updated.backoff.wait
    when {
      rejection == null -> logger.debug {
        "Failed to take back path /$path, ${retryingIn(retryIn)}: ${e.simpleClassName}"
      }

      cause == HELD_BY_ANOTHER_AGENT -> logRejection(source, path, rejection, repeat = true, retryIn = retryIn)

      else -> logRejection(source, path, rejection, repeat = tried.refusal == cause, retryIn = retryIn)
    }
  }

  // Runs reclaimTakenOverPaths while [active] holds -- the connection's reclaim task, which Agent.launchConnectionTasks
  // starts -- sleeping until the next path's try is due within RECLAIM_WINDOW, or until a notice, with none taken over.
  suspend fun reclaimTakenOverPathsWhile(active: () -> Boolean) {
    while (active()) {
      val wait = paths.values.minOfOrNull { it.takenOver?.backoff?.remaining ?: Duration.INFINITE }
        ?.takeIf { it.isFinite() }
        ?.minus(RECLAIM_WINDOW)
      when {
        wait == null -> reclaimWake.receive()
        wait > Duration.ZERO -> withTimeoutOrNull(wait) { reclaimWake.receive() }
      }
      reclaimTakenOverPaths()
    }
  }

  suspend fun registerPath(
    pathVal: String,
    url: String,
    labels: String = "{}",
    metricsSource: MetricsSource? = null,
  ) = pathMutex.withLock { doRegisterPath(pathVal, ScrapeTarget(url, metricsSource), labels, PathSource.STATIC) }

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
   * except a rejection: one that can clear waits out its backoff, and one that can't isn't retried as it stands;
   * see [registerDiscoveredPath].
   */
  suspend fun reconcileDiscoveredPaths(desired: List<DiscoveredPath>) =
    pathMutex.withLock {
      val desiredByPath = desiredDiscoveredPaths(desired)
      unregisterStaleDiscoveredPaths(desiredByPath.keys)

      // Forget the rejections of paths no longer desired.
      discoveredRejections.keys.retainAll(desiredByPath.keys)
      // And their registration failures: every recorded failure is a configured path's or a desired discovered one's.
      registrationFailures.keys.retainAll { it in configuredStaticPaths || it in desiredByPath }

      // Register new discovered paths and re-register changed ones (see registerDiscoveredPath).
      for ((path, entry) in desiredByPath) {
        reconcileDiscoveredEntry(path, entry)
      }
    }

  // Registers the desired [entry] for [path] unless the path already points at it. A taken-over path's edit is only
  // kept, for its reclaim to register: registered the usual way, the path would be taken back from the agent holding
  // it. Callers MUST hold pathMutex.
  private suspend fun reconcileDiscoveredEntry(
    path: String,
    entry: DiscoveredPath,
  ) {
    val current = paths[path]
    val labels = entry.labels.defaultEmptyJsonObject()
    // Unchanged discovered path.
    if (current?.context?.matches(entry.url, labels) == true)
      return
    if (current?.takenOver != null)
      paths.computeIfPresent(path) { _, taken ->
        taken.copy(context = taken.context.copy(url = entry.url, labels = labels))
      }
    else
      registerDiscoveredPath(path, entry, labels, current?.context)
  }

  // The desired discovered entries keyed by normalized path, the last one winning for a path listed twice. An entry
  // colliding with a static path is dropped (static wins). Callers MUST hold pathMutex.
  private fun desiredDiscoveredPaths(desired: List<DiscoveredPath>): Map<String, DiscoveredPath> {
    val desiredByPath = LinkedHashMap<String, DiscoveredPath>()
    val collisions = LinkedHashSet<String>()
    val duplicates = LinkedHashSet<String>()
    for (entry in desired) {
      val path = entry.path.removePrefix("/")
      if (isStaticPath(path)) {
        collisions += path
        continue
      }
      if (path in desiredByPath)
        duplicates += path
      desiredByPath[path] = entry
    }
    reportConflict(DiscoveryConflict.COLLISION, collisions)
    reportConflict(DiscoveryConflict.DUPLICATE, duplicates)
    return desiredByPath
  }

  // Whether [path] belongs to the static baseline: configured in pathConfigs, registered or not (see
  // configuredStaticPaths), or registered at runtime. Callers MUST hold pathMutex.
  private fun isStaticPath(path: String): Boolean =
    path in configuredStaticPaths || paths[path]?.context?.source == PathSource.STATIC

  // Unregisters the DISCOVERED paths no longer [desired]. Callers MUST hold pathMutex. A failure keeps its path for the
  // next reconcile to retry, so one that lasts repeats on every reconcile: its stack trace is logged when it first
  // appears for the path, and a repeat at DEBUG, as registerDiscoveredPath does (November #42). doUnregisterPath
  // handles the proxy's answer itself, so only a failure that kept the proxy from answering comes here, keyed, as in
  // connectAgent, by its gRPC status code, or its class without one, not by a message that can change every attempt.
  private suspend fun unregisterStaleDiscoveredPaths(desired: Set<String>) {
    val stale =
      paths.mapNotNullTo(HashSet()) { (path, entry) ->
        path.takeIf { entry.context.source == PathSource.DISCOVERED && path !in desired }
      }
    // Forget the failures of paths no longer stale: unregistered, desired again, or no longer discovered.
    staleUnregisterFailures.keys.retainAll(stale)
    for (path in stale) {
      runCatchingCancellable { doUnregisterPath(path) }
        .onSuccess { staleUnregisterFailures -= path }
        .onFailure { e ->
          val failure = failureKey(e)
          if (staleUnregisterFailures.put(path, failure) == failure)
            logger.debug { "Still failing to unregister discovered path /$path: ${e.simpleClassName}: ${e.message}" }
          else
            logger.warn(e) { "Failed to unregister discovered path /$path" }
        }
    }
  }

  // Reports the discovered [paths] in [conflict]. The discovery file is re-read every reconcile, so the set is logged
  // at WARN only when it changes -- as FileDiscoverySource.reportUnusable does for unusable entries -- and at DEBUG
  // otherwise. Callers MUST hold pathMutex.
  private fun reportConflict(
    conflict: DiscoveryConflict,
    paths: Set<String>,
  ) {
    val reported = reportedConflicts.put(conflict, paths).orEmpty()
    if (paths != reported)
      discoveryConflicts =
        DiscoveryConflicts(
          collisions = reportedConflicts[DiscoveryConflict.COLLISION].orEmpty().sorted(),
          duplicates = reportedConflicts[DiscoveryConflict.DUPLICATE].orEmpty().sorted(),
        )
    val shown = paths.map { "/$it" }
    when {
      paths == reported -> if (paths.isNotEmpty()) logger.debug { conflict.unchanged(shown) }
      paths.isEmpty() -> logger.info { conflict.cleared }
      else -> logger.warn { conflict.changed(shown) }
    }
  }

  // Registers, or re-registers, one discovered path; callers MUST hold pathMutex. The failure is recorded in
  // discoveredRejections, and a rejection whose cause can't clear (see RETRYABLE_CAUSES) isn't tried again until the
  // entry's URL or labels change, it leaves the desired set and returns, or the agent reconnects. One that can clear
  // is retried once its backoff is up (see nextBackoff). Any other failure is still retried on the next reconcile, and
  // its stack trace is logged once rather than on every one.
  private suspend fun registerDiscoveredPath(
    path: String,
    entry: DiscoveredPath,
    labels: String,
    current: PathContext?,
  ) {
    // A changed URL or labels leaves prior null, so an edited entry is tried at once.
    val prior = discoveredRejections[path]?.takeIf { it.matches(entry.url, labels) }
    if (prior?.mayRetryNow == false)
      return
    // An edit is registered again as a reclaim, so it never takes the path from an agent that took it meanwhile. An
    // exclusive path is replaced in place, as every proxy replaces an agent's own registration of one: unregistering it
    // first let an agent waiting on it take it in between. A proxy keeps a consolidated path's labels, so that one is
    // unregistered first, and no agent takes a consolidated path over.
    runCatchingCancellable {
      if (current != null && agent.options.consolidated)
        doUnregisterPath(path)
      doRegisterPath(path, ScrapeTarget(entry.url), entry.labels, PathSource.DISCOVERED, reclaim = current != null)
    }.onSuccess {
      discoveredRejections -= path
    }.onFailure { e ->
      val record = discoveredRejection(entry.url, labels, e, prior?.backoff)
      discoveredRejections[path] = record
      recordFailure(path, e, retrying = record.retryable, retryAt = record.backoff?.dueAt)
      val repeat = prior?.failure == record.failure
      if (e is RequestFailureException)
        logRejection(PathSource.DISCOVERED, path, e, repeat, record.backoff?.wait)
      else if (repeat)
        logger.debug { "Still failing to register discovered path /$path: ${e.simpleClassName}: ${e.message}" }
      else
        logger.warn(e) { "Failed to register discovered path /$path" }
    }
  }

  // The record of [e], the latest failure to register a discovered entry with [url] and [labels], given the backoff
  // of the failure before it. Only a rejection is backed off: any other failure never reached the proxy, so it keeps
  // the reconcile's pace. The failure is recorded by gRPC status code, or by class and message without one: a
  // deadline's description carries its elapsed time, so recording the message made every timeout a new failure, and
  // each logged a WARN with its stack trace (November #39). A rejection keeps its message, the proxy's reason, so one
  // for another reason is still news.
  private fun discoveredRejection(
    url: String,
    labels: String,
    e: Throwable,
    priorBackoff: RetryBackoff?,
  ): DiscoveredRejection {
    val rejection = e as? RequestFailureException
    // Every failure but a rejection that can't clear is retried.
    val retryable = rejection?.retryable != false
    val backoff = if (rejection?.retryable == true) nextBackoff(priorBackoff, discoveredRetryInterval) else null
    return DiscoveredRejection(url, labels, retryable, failureKey(e, withMessage = true), backoff)
  }

  // Lock-free registration body; callers MUST hold pathMutex. Kotlin's Mutex is not reentrant, so
  // reconcileDiscoveredPaths (which holds the lock across the whole diff) calls this directly rather
  // than the locking registerPath wrapper. A notice that comes while the proxy answers waits (see registering).
  private suspend fun doRegisterPath(
    pathVal: String,
    target: ScrapeTarget,
    labels: String,
    source: PathSource,
    reclaim: Boolean = false,
  ) {
    val url = target.url
    require(pathVal.isNotBlank()) { BLANK_PATH_MSG }
    require(url.isNotBlank()) { "Blank URL" }

    val path = pathVal.removePrefix("/")
    val labelsJson = labels.defaultEmptyJsonObject()
    val context = PathContext(0L, path, url, labelsJson, source, filtersByPath[path], target.source)
    registering[path] = NO_NOTICE
    try {
      register(context, reclaim)
    } finally {
      registering.remove(path)?.let(::notePathNotice)
    }
  }

  // Registers [context] with the proxy and records it. A taken-over path is registered as a reclaim, by whichever route
  // registers it, and the proxy accepting it ends its takeover. Another path is a reclaim when [reclaim] asks, and
  // waits as taken over if the proxy refuses it because another agent holds the path (see waitForPath).
  private suspend fun register(
    context: PathContext,
    reclaim: Boolean,
  ) {
    val path = context.path
    val isTakenOver = paths[path]?.takenOver != null
    val grpc = agent.grpcService
    val source = context.source.name
    if (!isTakenOver && !reclaim) {
      recordRegistered(context, grpc.registerPathOnProxy(path, context.labels, context.url, source).pathId)
      return
    }
    val pathId =
      try {
        grpc.reclaimPathOnProxy(path, context.labels, context.url, source).pathId
      } catch (e: RequestFailureException) {
        if (isTakenOver || e.rejectionCause != HELD_BY_ANOTHER_AGENT)
          throw e
        waitForPath(context, e)
        return
      }
    recordRegistered(context, pathId)
  }

  // Records [context], which the proxy has just registered as [pathId].
  private fun recordRegistered(
    context: PathContext,
    pathId: Long,
  ) {
    val path = context.path
    // Whether a filter attached is only knowable here, where the path is actually resolved -- which is
    // why it is reported here rather than cross-checked against pathConfigs at construction time, a
    // baseline that by definition cannot see discovered or runtime-registered paths. Absence of the
    // suffix on a path you configured a filter for means the two path strings do not match.
    val sourceName = context.source.name
    if (!agent.isTestMode) {
      logger.info {
        "Registered ${sanitizeUrl(context.url)} as /$path with labels ${context.labels} (${sourceName.lowercase()})" +
          if (context.filter != null) " with a metric filter" else ""
      }
    }
    paths[path] = PathEntry(context.copy(pathId = pathId))
    registrationFailures -= path
    agent.activityLog?.record(AgentActivityKind.PATH_REGISTERED, path, sourceName.lowercase(), sourceName)
  }

  // Keeps [context] as a path another agent took over, as the proxy's refusal [e] of its reclaim says, for the reclaim
  // task to take back once no agent serves it. The proxy's notice naming the holder follows, and reports the takeover,
  // so the refusal is logged as a repeat. Callers MUST hold pathMutex.
  private fun waitForPath(
    context: PathContext,
    e: RequestFailureException,
  ) {
    val path = context.path
    val takenOver = TakenOver.unnamed(e, RetryBackoff.after(staticRetryInterval, timeSource.markNow()))
    paths[path] = PathEntry(context, takenOver)
    reclaimWake.trySend(Unit)
    logRejection(context.source, path, e, repeat = true, retryIn = takenOver.backoff.wait)
  }

  // Lock-free unregistration body; callers MUST hold pathMutex (see doRegisterPath).
  private suspend fun doUnregisterPath(pathVal: String) {
    require(pathVal.isNotBlank()) { BLANK_PATH_MSG }

    val path = pathVal.removePrefix("/")
    // The proxy rejects an unregister (valid=false) only when it no longer maps this path to this agent: the path
    // is gone, or another agent now holds it. The local entry is stale either way, so it is removed below. Keeping
    // it made reconcile retry forever and blocked a changed URL from ever applying. Transport failures still
    // propagate and keep the entry, so the next reconcile retries them. A taken-over path is asked about too, since
    // the notice that marked it can be stale; the proxy's refusal is expected there, so it isn't logged at WARN.
    try {
      agent.grpcService.unregisterPathOnProxy(path)
    } catch (e: RequestFailureException) {
      if (paths[path]?.takenOver != null)
        logger.debug { "Proxy no longer holds taken-over path /$path for this agent: ${e.message}" }
      else
        logger.warn { "Proxy no longer holds /$path for this agent; removing the local entry: ${e.message}" }
    }
    dropLocally(path)
  }

  // Forgets [path], with its takeover, and its last failure, as an unregister ends. Callers MUST hold pathMutex.
  private fun dropLocally(path: String) {
    val pathContext = paths.remove(path)?.context
    registrationFailures -= path
    if (pathContext == null) {
      logger.info { "No path value /$path found when unregistering" }
      return
    }
    if (!agent.isTestMode)
      logger.info { "Unregistered /$path for ${sanitizeUrl(pathContext.url)}" }
    val source = pathContext.source.name
    agent.activityLog?.record(AgentActivityKind.PATH_UNREGISTERED, path, source.lowercase(), source)
  }

  // Records [e], the latest failure to register [path], for the agent's dashboard. The proxy's answer is a refusal,
  // whose reason is its own words; anything else kept the proxy from answering. [retryAt] is when the next try is due,
  // null for the next tick of the loop that retries it, or for no retry.
  private fun recordFailure(
    path: String,
    e: Throwable,
    retrying: Boolean,
    retryAt: TimeMark?,
  ) {
    val refused = e is RequestFailureException
    val failure = if (refused) ShownFailure.of(e.reason) else ShownFailure.of(e)
    registrationFailures[path] = RegistrationFailure(failure, refused, retrying, retryAt)
  }

  /** Every path registered with the proxy now: not one another agent took over. */
  val registeredPaths: List<PathContext>
    get() = paths.values.mapNotNull { entry -> entry.context.takeIf { entry.takenOver == null } }

  /** The paths the proxy said another agent took over, which this agent takes back once no agent serves them. */
  val takenOverContexts: List<PathContext>
    get() = paths.values.mapNotNull { entry -> entry.context.takeIf { entry.takenOver != null } }

  /**
   * Each path's last failure to register, by normalized path, until it registers (see [RegistrationFailure]), and each
   * taken-over path's takeover, or the proxy's refusal of its last try for another cause.
   */
  val pathFailures: Map<String, RegistrationFailure>
    get() = registrationFailures + paths.mapNotNull { (path, entry) -> entry.takenOver?.let { path to it.shown } }

  /**
   * The discovered paths last found colliding with a static path, and those the discovery file lists twice. A plain
   * read, kept by reportConflict as it reports them, so the agent's dashboard never waits on [pathMutex], which a
   * reconcile holds across its calls to the proxy.
   */
  @Volatile
  var discoveryConflicts = DiscoveryConflicts(emptyList(), emptyList())
    private set

  /**
   * A path's last failure to register, for the agent's dashboard.
   *
   * @param reason the proxy's reason, for a refusal, or what kept the proxy from answering, and when
   * @param refused whether the proxy answered, refusing the path
   * @param retrying whether the path is tried again before the agent reconnects
   * @param retryAt when the next try is due, or null for the next tick of the loop that retries it, or for no retry
   * @param takenOver whether the proxy gave the path to another agent, which this one takes it back from once it leaves
   */
  data class RegistrationFailure(
    val reason: ShownFailure,
    val refused: Boolean,
    val retrying: Boolean,
    val retryAt: TimeMark?,
    val takenOver: Boolean = false,
  )

  /** The discovered paths in conflict, by normalized path: [collisions] with static paths, and [duplicates]. */
  data class DiscoveryConflicts(
    val collisions: List<String>,
    val duplicates: List<String>,
  )

  // The paths discovery registered, for the agent's debug page: toPlainText lists only the static config, so a
  // discovery-only agent showed nothing at all.
  fun discoveredToPlainText(): String {
    val discovered = paths.values.map { it.context }.filter { it.source == PathSource.DISCOVERED }.sortedBy { it.path }
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
    private val RETRYABLE_CAUSES = setOf(HELD_BY_ANOTHER_IDENTITY, CONSOLIDATION_MISMATCH, HELD_BY_ANOTHER_AGENT)

    // How close together reclaims are tried in one pass; see reclaimTakenOverPaths.
    private val RECLAIM_WINDOW = 1.seconds

    // A path's place in registering while no notice about it has come.
    private val NO_NOTICE: PathNotice = PathNotice.getDefaultInstance()

    // Whether a refused reclaim drops the takeover: a refusal that can't clear, but not INVALID_AGENT, which says the
    // proxy has already dropped this connection's context; see reclaimFailed.
    private val RequestFailureException.dropsTakeover: Boolean
      get() = !retryable && rejectionCause != INVALID_AGENT

    private val RequestFailureException.retryable: Boolean
      get() = rejectionCause in RETRYABLE_CAUSES

    /**
     * The key [path], a path a scrape asks for, is registered under: [path] without its leading slashes. A current
     * proxy sends the key itself, since its scrape route drops every leading slash (#416). One older than that dropped
     * only the first, so asked for `//orders_metrics` it sends `/orders_metrics`. No key starts with a slash:
     * doRegisterPath drops one, and the proxy refuses a path with a slash left in it, so stripping them all loses no
     * path a lookup as given would have found.
     */
    internal fun requestedPathKey(path: String): String = path.trimStart('/')
  }

  // The last failure to register a discovered entry. [retryable] says whether the entry is tried again as it stands;
  // [failure] identifies the failure, as Utils.failureKey gives it, so a repeat is logged at DEBUG instead of a fresh
  // stack trace. Only that comparison reads it: the log lines take the full message from the exception itself.
  private data class DiscoveredRejection(
    val url: String,
    val labels: String,
    val retryable: Boolean,
    val failure: String,
    // Null when the failure was not a rejection, which is not backed off; see discoveredRejection.
    val backoff: RetryBackoff?,
  ) {
    // Whether this failure was for an entry with [url] and [labels]; an edited entry starts over.
    fun matches(
      url: String,
      labels: String,
    ) = this.url == url && this.labels == labels

    // Whether the entry may be tried again now: never after a rejection that can't clear, and after one that can only
    // once its backoff is up (see nextBackoff). A failure that wasn't a rejection has no backoff to wait out.
    val mayRetryNow: Boolean get() = retryable && backoff?.isDue != false
  }

  // The two ways a discovered entry can conflict, each with how reportConflict words it.
  private enum class DiscoveryConflict(
    val changed: (List<String>) -> String,
    val unchanged: (List<String>) -> String,
    val cleared: String,
  ) {
    // An entry for a static path, which keeps it.
    COLLISION(
      changed = { "Discovered paths $it collide with static paths; keeping the static entries" },
      unchanged = { "Discovered paths still collide with static paths: $it" },
      cleared = "No discovered path collides with a static path any more",
    ),

    // A path the discovery file lists more than once.
    DUPLICATE(
      changed = { "Duplicate discovered paths $it; using the last entry for each" },
      unchanged = { "Discovered paths still duplicated: $it" },
      cleared = "No discovered path is duplicated any more",
    ),
  }

  // When a path the proxy rejected for a cause that can clear may be retried: [wait] is the current backoff, which
  // nextBackoff doubles on the next rejection, or a static path's next failed retry, and [dueAt] is when it is up.
  private data class RetryBackoff(
    val wait: Duration,
    val dueAt: TimeMark,
  ) {
    val isDue: Boolean get() = dueAt.hasPassedNow()

    // How long until it is up, zero once it is.
    val remaining: Duration get() = (-dueAt.elapsedNow()).coerceAtLeast(Duration.ZERO)

    companion object {
      // A backoff of [wait], up [wait] after [now].
      fun after(
        wait: Duration,
        now: TimeMark,
      ) = RetryBackoff(wait, now + wait)
    }
  }

  // A path this agent serves: what it points at, and its takeover while the proxy says another agent holds it.
  private data class PathEntry(
    val context: PathContext,
    val takenOver: TakenOver? = null,
  )

  // Who holds a path another agent took from this one, as the proxy said.
  private sealed interface Holder {
    // An agent, by the name it gave, or its agentId without one.
    data class Agent(
      val name: String,
    ) : Holder

    // No agent: the one that took the path left, so it's free.
    data object Nobody : Holder

    // An agent the proxy hasn't named yet: it refused a registration because one holds the path (see waitForPath).
    data object Unnamed : Holder
  }

  // A path the proxy gave to [holder], when this agent next tries to take it back, and the cause of the proxy's last
  // refusal of a try, if any. A plain class: a refused reclaim waits longer only if no notice replaced the takeover it
  // tried, by identity.
  private class TakenOver(
    val holder: Holder,
    val backoff: RetryBackoff,
    val refusal: PathRejectionCause? = null,
    // How the agent's dashboard shows the path meanwhile: the takeover, or the proxy's refusal.
    val shown: RegistrationFailure = takeoverFailure(holder, backoff),
  ) {
    // This takeover once [rejection], or a failure that kept the proxy from answering, refused the try made with it,
    // to be tried again after [next]. A refusal while another agent holds the path is what the takeover shows, unless
    // the proxy hasn't named that agent yet: its refusal does.
    fun refusedFor(
      rejection: RequestFailureException?,
      next: RetryBackoff,
    ): TakenOver {
      if (rejection == null)
        return TakenOver(holder, next)
      val cause = rejection.rejectionCause
      val held = cause == HELD_BY_ANOTHER_AGENT
      if (held && holder != Holder.Unnamed)
        return TakenOver(holder, next, cause)
      return TakenOver(holder, next, cause, shownRefusal(rejection.reason, next, takenOver = held))
    }

    companion object {
      // A path the proxy refused, for [rejection], because another agent holds it, before a notice names that agent:
      // shown with the proxy's reason, which names it, until one does (see waitForPath).
      fun unnamed(
        rejection: RequestFailureException,
        backoff: RetryBackoff,
      ): TakenOver =
        TakenOver(Holder.Unnamed, backoff, HELD_BY_ANOTHER_AGENT, shownRefusal(rejection.reason, backoff, true))

      private fun takeoverFailure(
        holder: Holder,
        backoff: RetryBackoff,
      ): RegistrationFailure {
        val reason =
          when (holder) {
            is Holder.Agent -> "Taken over by ${holder.name}"
            Holder.Nobody -> "Taken over by another agent, which has left"
            Holder.Unnamed -> "Taken over by another agent"
          }
        return shownRefusal(reason, backoff, takenOver = true)
      }

      // How the agent's dashboard shows a path refused for [reason], to be tried again as [backoff] says.
      private fun shownRefusal(
        reason: String,
        backoff: RetryBackoff,
        takenOver: Boolean,
      ): RegistrationFailure =
        RegistrationFailure(
          reason = ShownFailure.of(reason),
          refused = true,
          retrying = true,
          retryAt = backoff.dueAt,
          takenOver = takenOver,
        )
    }
  }

  // A static path waiting for retryRejectedStaticPaths: [backoff] says when it is next due, and [failure] is the
  // transport failure its last retry met, as Utils.failureKey gives it, or null when the proxy answered, so a repeat
  // is logged at DEBUG instead of a fresh stack trace (see staticRetryFailed).
  private data class StaticRetry(
    val backoff: RetryBackoff,
    val failure: String? = null,
  )

  // Strongly-typed view of a single `agent.pathConfigs` entry, replacing the prior magic-string map, or of a path an
  // embedded host serves in-process, whose [metricsSource] the agent reads in place of its [url], IN_PROCESS_TARGET.
  data class PathConfig(
    val name: String,
    val path: String,
    val url: String,
    val labels: String,
    val metricsSource: MetricsSource? = null,
    // The builder method that added a path an embedded host serves in-process.
    val addedBy: String = "source()",
  ) {
    // What a scrape of this path reads.
    val target: ScrapeTarget get() = ScrapeTarget(url, metricsSource)

    // Where a startup message says this path came from: a pathConfigs entry, by its name, or, for a path an embedded
    // host serves in-process, the builder method that added it, since no pathConfigs entry holds it.
    val origin: String
      get() = if (metricsSource != null) addedBy else "agent.pathConfigs entry ${shownValue(quotedName)}"

    // Name wrapped in double-quotes for the toPlainText() display, preserving the original format.
    val quotedName: String get() = "\"$name\""

    // Mirrors DiscoveredPath.usable: doRegisterPath requires both, so an entry missing either can never register. A
    // path of just "/" normalizes to "", so it is as unusable as a blank one.
    val usable: Boolean get() = path.removePrefix("/").isNotBlank() && url.isNotBlank()
  }

  data class PathContext(
    val pathId: Long,
    val path: String,
    val url: String,
    val labels: String,
    val source: PathSource,
    val filter: MetricFilter? = null,
    // What the agent reads in place of [url], for a path an embedded host serves in-process.
    val metricsSource: MetricsSource? = null,
  ) {
    // What a scrape of this path reads.
    val target: ScrapeTarget get() = ScrapeTarget(url, metricsSource)

    // Whether this path still points at [url] with [labels]; reconcileDiscoveredPaths re-registers it when not.
    fun matches(
      url: String,
      labels: String,
    ) = this.url == url && this.labels == labels

    // Logged at DEBUG on every scrape, so the target URL's userinfo and query values are redacted here rather than
    // at each log site.
    override fun toString() =
      "PathContext(pathId=$pathId, path=$path, url=${sanitizeUrl(url)}, labels=$labels, source=$source, " +
        "filter=$filter)"
  }
}
