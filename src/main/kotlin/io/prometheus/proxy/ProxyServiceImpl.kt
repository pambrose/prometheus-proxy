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

package io.prometheus.proxy

import com.pambrose.common.util.runCatchingCancellable
import com.google.protobuf.Empty
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.Status
import io.grpc.StatusException
import io.prometheus.Proxy
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.MetricBuilders.rejectionCauseLabel
import io.prometheus.common.ScrapeResults.Companion.toScrapeResults
import io.prometheus.common.ScrapeTracing.Companion.SENT_TO_AGENT
import io.prometheus.common.ScrapeTracing.Companion.TAG_QUEUE_WAIT
import io.prometheus.common.AgentValues.truncate
import io.prometheus.grpc.AgentInfo
import io.prometheus.grpc.ChunkData
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ChunkedScrapeResponse.ChunkOneOfCase
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.PathMapSizeRequest
import io.prometheus.grpc.PathNotice
import io.prometheus.grpc.PathRejectionCause.BINDING_MISMATCH
import io.prometheus.grpc.PathRejectionCause.INVALID_AGENT
import io.prometheus.grpc.PathRejectionCause.NOT_AUTHORIZED
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.RegisterAgentResponse
import io.prometheus.grpc.RegisterPathRequest
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.SummaryData
import io.prometheus.grpc.UnregisterPathRequest
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatResponse
import io.prometheus.grpc.pathMapSizeResponse
import io.prometheus.grpc.registerAgentResponse
import io.prometheus.grpc.scrapeRequest
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.unregisterPathResponse
import io.prometheus.proxy.ProxyPathManager.PathMetadata
import io.prometheus.proxy.ProxyPathManager.PathRejection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Server-side implementation of the `ProxyService` gRPC service.
 *
 * Handles all gRPC RPCs defined in `proxy_service.proto`: agent connection and registration,
 * path registration/unregistration, heartbeat processing, and bidirectional scrape request/response
 * streaming (including chunked transfers for large payloads). Validates agent identity on every
 * call that names an agent, except `pathMapSize`, and delegates state management to
 * [AgentContextManager], [ProxyPathManager], and [ScrapeRequestManager].
 *
 * @param proxy the parent [Proxy] instance
 * @see ProxyGrpcService
 * @see AgentContextManager
 * @see ScrapeRequestManager
 */
internal class ProxyServiceImpl(
  private val proxy: Proxy,
) : ProxyServiceGrpcKt.ProxyServiceCoroutineImplBase() {
  // How long a stream between the proxy and an agent goes without a message before a keepalive, zero for none (see
  // Proxy.streamKeepalive), and the longest the streams to an agent wait for something to send: forever when off.
  private val streamKeepalive = proxy.streamKeepalive
  private val keepaliveIdle = if (streamKeepalive.isPositive()) streamKeepalive else Duration.INFINITE

  override suspend fun connectAgent(request: Empty): Empty {
    if (proxy.options.transportFilterDisabled) {
      val msg = "Agent (false) and Proxy (true) do not have matching transportFilterDisabled config values"
      logger.error { msg }
      countRefusal(ProxyMetrics.REFUSAL_TRANSPORT_FILTER_MISMATCH)
      throw StatusException(Status.FAILED_PRECONDITION.withDescription(msg))
    }

    proxy.metrics { connectCount.inc() }
    // The connection's context is already announced: ProxyServerInterceptor announces it on every authenticated call,
    // this one included, so a client can't stay unannounced by skipping this call (October #26).
    return EMPTY_INSTANCE
  }

  override suspend fun connectAgentWithTransportFilterDisabled(request: Empty): AgentInfo {
    if (!proxy.options.transportFilterDisabled) {
      val msg = "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
      logger.error { msg }
      countRefusal(ProxyMetrics.REFUSAL_TRANSPORT_FILTER_MISMATCH)
      throw StatusException(Status.FAILED_PRECONDITION.withDescription(msg))
    }

    // With no transport filter there is no connection-assigned agentId to bind later calls to, so bind them
    // to the auth identity this call presented instead (see identityMismatchReason). Empty when auth is off.
    val agentContext = AgentContext(UNKNOWN_ADDRESS, callerIdentityName())
    // Every call creates a context, which lasts until its stream ends or it is evicted, so the contexts one identity
    // may hold are limited (November #17). A connect past the limit is refused rather than evicting another context:
    // after a proxy restart a whole fleet reconnects at once, and evicting would drop agents that just connected. The
    // agent retries a refused connect after its reconnect pause.
    val maxAgents = proxy.maxAgentsPerIdentity
    if (!proxy.agentContextManager.addAgentContextWithinLimit(agentContext, maxAgents)) {
      val msg =
        "The proxy already has $maxAgents agents connected with this agent's identity, its " +
          "proxy.internal.maxAgentsPerIdentity limit; retry once one disconnects"
      countRefusal(ProxyMetrics.REFUSAL_IDENTITY_LIMIT)
      throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription(msg))
    }
    // Counted once the connect is accepted, as connectAgent counts it; a refused one counts as a refusal.
    proxy.metrics { connectCount.inc() }
    return agentInfo { agentId = agentContext.agentId }
  }

  private fun countRefusal(reason: String) = proxy.metrics { agentConnectRefusalCount.labelValues(reason).inc() }

  private fun countBindingMismatch(rpcName: String) = proxy.metrics { bindingMismatchCount.labelValues(rpcName).inc() }

  /**
   * Rejects a request whose `agentId` is not the one the transport assigned to this connection.
   *
   * `request.agentId` is caller-supplied, and agentIds are sequential integers that the proxy echoes
   * back to every client in the `agent-id` response header — so without this check an authenticated
   * agent could enumerate its neighbors and pass a victim's agentId to act on the victim's
   * [AgentContext] while presenting its own valid token.
   *
   * Returns null (allow) when the connection agentId is unset, which happens when no transport
   * filter ran: `transportFilterDisabled` deployments, and tests that call this class with no gRPC
   * server. The in-process server runs the filter too. With no transport-assigned identity to compare
   * against, [identityMismatchReason] binds the calls instead.
   */
  private fun connectionMismatchReason(
    requestAgentId: String,
    rpcName: String,
  ): String? {
    val connectionAgentId = ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get()
    return if (connectionAgentId == null || connectionAgentId == requestAgentId) {
      null
    } else {
      val shown = shownAgentId(requestAgentId)
      logger.warn { "Agent on connection $connectionAgentId sent agentId $shown to $rpcName(); rejecting" }
      countBindingMismatch(rpcName)
      "agentId $shown does not match the connection's agentId ($rpcName)"
    }
  }

  // The name of the auth identity this call presented; empty when per-agent auth is off.
  private fun callerIdentityName(): String = AgentAuthManager.AGENT_IDENTITY_KEY.get()?.name.orEmpty()

  /**
   * Rejects a call acting on [agentContext] when that agent connected as a different auth identity.
   *
   * With `transportFilterDisabled` there is no transport-assigned agentId, so [connectionMismatchReason]
   * can't bind calls to a connection. The auth identity presented at connect is the remaining signal:
   * [connectAgentWithTransportFilterDisabled] records it on the context, and every later call naming that
   * agent must present the same one. Returns null (allow) when the context has no bound identity -- per-agent
   * auth disabled, or a context created by the transport filter. Agents that share one identity, such as the
   * legacy `proxy.agentToken`, remain indistinguishable from each other in this mode.
   */
  private fun identityMismatchReason(
    agentContext: AgentContext,
    rpcName: String,
  ): String? {
    if (isCallerBoundTo(agentContext))
      return null
    val callerIdentityName = AgentAuthManager.AGENT_IDENTITY_KEY.get()?.name
    logger.warn {
      "Identity '$callerIdentityName' sent $rpcName() for agentId ${agentContext.agentId}, " +
        "which connected as identity '${agentContext.authIdentityName}'; rejecting"
    }
    countBindingMismatch(rpcName)
    return "agentId ${agentContext.agentId} is bound to a different agent identity ($rpcName)"
  }

  // Whether this call may act on [agentContext]: it has no bound identity, or the call presented that one. The check
  // identityMismatchReason makes, without its warning.
  private fun isCallerBoundTo(agentContext: AgentContext): Boolean {
    val boundIdentityName = agentContext.authIdentityName
    return boundIdentityName.isEmpty() || AgentAuthManager.AGENT_IDENTITY_KEY.get()?.name == boundIdentityName
  }

  override suspend fun registerAgent(request: RegisterAgentRequest): RegisterAgentResponse {
    val failureReason =
      connectionMismatchReason(request.agentId, "registerAgent") ?: run {
        val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
        if (agentContext == null) {
          val shown = shownAgentId(request.agentId)
          logger.error { "registerAgent() missing AgentContext agentId: $shown" }
          "Invalid agentId: $shown (registerAgent)"
        } else {
          identityMismatchReason(agentContext, "registerAgent") ?: run {
            // The identity is the operator's name for a token, not an agent-supplied value.
            agentContext.assignProperties(boundedRegistration(request, agentContext.agentId), callerIdentityName())
            agentContext.markActivityTime(false)
            logger.info { "Connected to $agentContext" }
            proxy.activityLog?.record(
              ActivityKind.AGENT_CONNECTED,
              agentContext,
              detail = "From ${agentContext.remoteAddr}, host ${agentContext.hostName}",
            )
            // Identity is only populated here; AgentConnected fired at the connection's first authenticated call
            // (connectAgent, for the stock agent), before the agent had told us who it is.
            proxy.eventBus.emit(ProxyEvent.AgentRegistered(request.agentId))
            null
          }
        }
      }

    val isValid = failureReason == null
    return registerAgentResponse {
      valid = isValid
      agentId = request.agentId
      // writeResponsesToProxy and writeChunkedResponsesToProxy skip them.
      streamKeepaliveMillis = streamKeepalive.inWholeMilliseconds
      if (!isValid) {
        // Smart-cast to non-null: isValid == false implies failureReason != null.
        reason = failureReason
      }
    }
  }

  /**
   * [request] with its agent name, host name, launch ID, and each proxy endpoint cut to
   * `proxy.internal.maxAgentFieldLength` characters, and its endpoint list to `maxAgentProxyEndpoints` entries;
   * 0 leaves either whole. Logs one warning naming what was cut, and how long it was, when anything was.
   *
   * The proxy only shows these fields, but it repeats them for every path the agent serves: in logs, service
   * discovery, the dashboard, and `/debug`. Bounded only by gRPC's 4 MiB message limit, one agent's fields times
   * `maxPathsPerAgent` paths could exhaust the heap or the log volume (October #23). They are cut rather than
   * rejected, so an agent with an unusually long host name keeps serving its paths.
   */
  private fun boundedRegistration(
    request: RegisterAgentRequest,
    agentId: String,
  ): RegisterAgentRequest {
    val maxLength = proxy.maxAgentFieldLength
    val maxEndpoints = proxy.maxAgentProxyEndpoints
    // What was cut, for the warning: field names and sizes, never the values.
    val cut = mutableListOf<String>()

    fun isLong(value: String) = maxLength in 1..<value.length

    fun bounded(
      field: String,
      value: String,
    ): String =
      if (isLong(value)) {
        cut += "$field (${value.length} characters)"
        truncate(value, maxLength)
      } else {
        value
      }

    val endpoints =
      request.proxyEndpointsList.let { all ->
        if (maxEndpoints in 1..<all.size) {
          cut += "proxyEndpoints (${all.size} entries)"
          all.take(maxEndpoints)
        } else {
          all
        }
      }
    val longEndpoints = endpoints.count { isLong(it) }
    if (longEndpoints > 0)
      cut += "$longEndpoints proxy endpoints (up to ${endpoints.maxOf { it.length }} characters)"

    val bounded =
      request.toBuilder()
        .setAgentName(bounded("agentName", request.agentName))
        .setHostName(bounded("hostName", request.hostName))
        .setLaunchId(bounded("launchId", request.launchId))
        .clearProxyEndpoints()
        .addAllProxyEndpoints(endpoints.map { if (isLong(it)) truncate(it, maxLength) else it })
        .build()
    if (cut.isNotEmpty())
      logger.warn {
        "Agent $agentId registered with ${cut.joinToString()}, over proxy.internal.maxAgentFieldLength ($maxLength) " +
          "or maxAgentProxyEndpoints ($maxEndpoints); keeping the start of each"
      }
    return bounded
  }

  override suspend fun registerPath(request: RegisterPathRequest): RegisterPathResponse {
    // addPath() and the binding and authorization checks below return null on success or the rejection; rejection
    // is null iff valid.
    val rejection =
      connectionMismatchReason(request.agentId, "registerPath")?.let { PathRejection(it, BINDING_MISMATCH) } ?: run {
        val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
        if (agentContext == null) {
          val shown = shownAgentId(request.agentId)
          logger.error { "Missing AgentContext for agentId: $shown" }
          PathRejection("Invalid agentId: $shown (registerPath)", INVALID_AGENT)
        } else {
          identityMismatchReason(agentContext, "registerPath")?.let { PathRejection(it, BINDING_MISMATCH) } ?: run {
            // AGENT_IDENTITY_KEY is null when per-agent auth is disabled (no interceptor); an identity
            // with no path patterns authorizes everything, so legacy single-token behavior is unchanged.
            val identity = AgentAuthManager.AGENT_IDENTITY_KEY.get()
            // The path's form and size are checked first, so the identity's globs, and the denial's log line, only
            // ever see a path of bounded length (October #22).
            val result =
              proxy.pathManager.formRejection(request.path, request.labels, agentContext)
                ?: authorizationRejection(identity, request.path, agentContext)
                ?: proxy.pathManager.addPath(
                  request.path,
                  request.labels,
                  agentContext,
                  PathMetadata(
                    targetUrl = request.targetUrl,
                    pathSource = request.pathSource,
                    // Only the same identity may take over a path a live agent already serves.
                    identityName = identity?.name.orEmpty(),
                    reclaim = request.reclaim,
                  ),
                )
            result.also { agentContext.markActivityTime(false) }
          }
        }
      }

    return registerPathResponse {
      pathId = if (rejection == null) PATH_ID_GENERATOR.fetchAndIncrement() else -1
      valid = rejection == null
      if (rejection != null) {
        reason = rejection.reason
        rejectionCause = rejection.cause
        proxy.metrics { pathRejectionCount.labelValues(rejectionCauseLabel(rejection.cause)).inc() }
      }
      pathCount = proxy.pathManager.pathMapSize
    }
  }

  // Why [identity] may not register [path], or null when it may, or when per-agent auth is off (no identity). The log
  // line and the reason show the path as ProxyPathManager's rejections do, cut to its first characters, and like them
  // it goes in the activity feed the first time [agentContext] is told it (noteRejection); the WARN is logged every
  // time.
  private fun authorizationRejection(
    identity: AgentIdentity?,
    path: String,
    agentContext: AgentContext,
  ): PathRejection? {
    if (identity == null || identity.isAuthorized(path))
      return null
    val shown = ProxyPathManager.shownPath(path.removePrefix("/"))
    logger.warn { "Agent identity '${identity.name}' denied registration of path /$shown" }
    val reason = "Agent identity '${identity.name}' is not authorized to register path /$shown"
    proxy.pathManager.noteRejection(agentContext, shown, reason, NOT_AUTHORIZED)
    return PathRejection(reason, NOT_AUTHORIZED)
  }

  override suspend fun unregisterPath(request: UnregisterPathRequest): UnregisterPathResponse {
    val agentId = request.agentId
    val mismatchReason = connectionMismatchReason(agentId, "unregisterPath")
    if (mismatchReason != null) {
      return unregisterPathResponse {
        valid = false
        reason = mismatchReason
      }
    }

    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    val identityReason = agentContext?.let { identityMismatchReason(it, "unregisterPath") }
    return if (agentContext == null) {
      val shown = shownAgentId(agentId)
      logger.error { "Missing AgentContext for agentId: $shown" }
      unregisterPathResponse {
        valid = false
        reason = "Invalid agentId: $shown (unregisterPath)"
      }
    } else if (identityReason != null) {
      unregisterPathResponse {
        valid = false
        reason = identityReason
      }
    } else {
      // The activity-time bump is unrelated to the response receiver, so .also (not .apply) -- finding 36.
      proxy.pathManager.removePath(request.path, agentId).also { agentContext.markActivityTime(false) }
    }
  }

  override suspend fun pathMapSize(request: PathMapSizeRequest) =
    pathMapSizeResponse {
      pathCount = proxy.pathManager.pathMapSize
    }

  override suspend fun sendHeartBeat(request: HeartBeatRequest) =
    proxy.agentContextManager.getAgentContext(request.agentId)
      .let { agentContext ->
        // Bound like every other agent RPC: a spoofed heartbeat would otherwise keep another agent's
        // context from ever being evicted.
        val failureReason =
          connectionMismatchReason(request.agentId, "sendHeartBeat")
            ?: if (agentContext == null) {
              val shown = shownAgentId(request.agentId)
              logger.error { "sendHeartBeat() missing AgentContext agentId: $shown" }
              "Invalid agentId: $shown (sendHeartBeat)"
            } else {
              identityMismatchReason(agentContext, "sendHeartBeat")
            }
        // Only an accepted heartbeat is counted: a refused one, usually from an agent the proxy evicted, is in the
        // eviction or binding mismatch counts.
        if (failureReason == null) {
          agentContext?.markActivityTime(false)
          proxy.metrics { heartbeatCount.inc() }
        }
        heartBeatResponse {
          valid = failureReason == null
          if (failureReason != null)
            reason = failureReason
        }
      }

  // The HTTP handler stops tracking a request when Prometheus times out or disconnects, but the request stays
  // queued. Delivering it would have the agent scrape for nobody and grow a slow agent's backlog.
  private fun isStillAwaited(wrapper: ScrapeRequestWrapper): Boolean =
    proxy.scrapeRequestManager.containsScrapeRequest(wrapper.scrapeId).also { awaited ->
      if (!awaited)
        logger.debug { "Skipping scrapeId ${wrapper.scrapeId}: no longer awaited" }
    }

  override fun readRequestsFromProxy(request: AgentInfo): Flow<ScrapeRequest> =
    flow {
      // Looked up, and checked, before the try/finally, so a call that is refused never reaches the cleanup, and the
      // context the cleanup removes is the one checked and streamed.
      val agentContext = readableAgentContext(request.agentId, "readRequestsFromProxy")
      try {
        emitScrapeRequests(agentContext)
      } finally {
        // When transportFilterDisabled is true, there is no ProxyServerTransportFilter to
        // detect agent disconnect and clean up. Handle cleanup here on stream termination.
        if (proxy.options.transportFilterDisabled)
          proxy.removeAgentContextIfCurrent(agentContext, RemovalReason.STREAM_TERMINATED)
      }
    }

  // Sends the agent the notices about paths another agent took from it, until the proxy stops or the context is
  // invalidated; see ProxyPathManager.takeOverPath. Unlike readRequestsFromProxy, its end removes nothing, even with
  // the transport filter disabled: the scrape request stream, which every agent reads, does that. Notices are rare, so
  // it sends a notice with no path, which the agent ignores, after keepaliveIdle with nothing else (see
  // Proxy.streamKeepalive). Every agent that reads the stream ignores one, so no flag is needed.
  override fun readPathNoticesFromProxy(request: AgentInfo): Flow<PathNotice> =
    flow {
      val agentContext = readableAgentContext(request.agentId, "readPathNoticesFromProxy")
      while (proxy.isStartingOrRunning) {
        val notices = agentContext.awaitPathNotices(keepaliveIdle) ?: break
        if (notices.isEmpty()) emit(KEEPALIVE_NOTICE) else notices.forEach { emit(it) }
      }
    }

  /**
   * The context whose scrape requests or path notices this connection may read: [agentId]'s, from one lookup.
   *
   * Throws PERMISSION_DENIED when the transport belongs to another agentId, or the caller's auth identity isn't the one
   * the context is bound to, and NOT_FOUND when there is no context. The identity was checked on one lookup and the
   * stream read from a second, so a caller that named an agentId about to be assigned passed the check while it was
   * free, and then streamed the context another identity connected with under it, or, when the second lookup also
   * found nothing, removed that context as its stream ended (November #14).
   */
  private fun readableAgentContext(
    agentId: String,
    rpcName: String,
  ): AgentContext {
    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    val denial =
      connectionMismatchReason(agentId, rpcName)
        ?: agentContext?.let { identityMismatchReason(it, rpcName) }
    if (denial != null)
      throw StatusException(Status.PERMISSION_DENIED.withDescription(denial))
    return agentContext
      ?: throw StatusException(
        Status.NOT_FOUND.withDescription("No AgentContext found for agentId: ${shownAgentId(agentId)}"),
      )
  }

  // Delivers [agentContext]'s queued scrape requests until the proxy stops or the context is invalidated, skipping
  // the ones no longer awaited. Starting counts: see Proxy.isStartingOrRunning. An agent that takes them gets a
  // keepalive after keepaliveIdle with no request (see Proxy.streamKeepalive), and never one that doesn't, whatever
  // the wait returns.
  private suspend fun FlowCollector<ScrapeRequest>.emitScrapeRequests(agentContext: AgentContext) {
    val idle = if (agentContext.acceptsStreamKeepalives) keepaliveIdle else Duration.INFINITE
    while (proxy.isStartingOrRunning && agentContext.isValid()) {
      val wrapper = agentContext.readScrapeRequest(idle)
      when {
        wrapper == null -> if (idle.isFinite() && agentContext.isValid()) emit(KEEPALIVE_REQUEST)
        isStillAwaited(wrapper) -> sendScrapeRequest(wrapper) { emit(it) }
      }
    }
  }

  // Sends the agent [wrapper]'s request with [send].
  private suspend fun sendScrapeRequest(
    wrapper: ScrapeRequestWrapper,
    send: suspend (ScrapeRequest) -> Unit,
  ) {
    val queueWait = wrapper.ageDuration()
    // A dashboard's scrape-now is left out, as it is from the latency histogram.
    if (!wrapper.scrapeRequest.dashboardScrape)
      proxy.metrics { scrapeQueueWait.observe(queueWait.toDouble(DurationUnit.SECONDS)) }
    // On the forward span, so the time the request waited in the agent's queue shows, and reads as a number. Before the
    // send: it suspends while gRPC's flow control holds an agent that stopped reading, and a scrape that times out
    // meanwhile finishes the span, which drops what is written to it afterwards.
    wrapper.traceSpan?.tag(TAG_QUEUE_WAIT, queueWait.inWholeMilliseconds.toString())
    try {
      send(wrapper.scrapeRequest)
      // Only while the scrape still awaits the result: one that stopped awaiting it finished its span, or is about to.
      wrapper.traceSpan
        ?.takeIf { proxy.scrapeRequestManager.containsScrapeRequest(wrapper.scrapeId) }
        ?.annotate(SENT_TO_AGENT)
    } catch (e: CancellationException) {
      // Cancelled after polling the wrapper out of the queue but before delivering it: fail the
      // wrapper so the waiting HTTP handler gets a prompt error instead of blocking the full
      // scrape-timeout window (finding 14).
      proxy.scrapeRequestManager.failScrapeRequest(
        wrapper.scrapeId,
        "readRequestsFromProxy cancelled",
        ProxyFailure.AGENT_DISCONNECTED,
      )
      throw e
    }
  }

  /**
   * Returns whether this connection may act on [scrapeId]'s result, whose scrape was sent to [owner].
   *
   * Scrape IDs come from one process-wide counter, so without this check any authenticated agent could
   * answer, fail, or disrupt the chunked transfer of a scrape that was sent to a different agent. Compares the
   * connection's transport-assigned agentId when there is one, and otherwise the owner's bound auth identity
   * (see [identityMismatchReason]).
   *
   * [owner] must come from the object the caller then acts on: the tracked wrapper it completes, or the chunked
   * transfer it extends. Checking on a separate lookup first let a scrapeId that was untracked at the check, and
   * tracked for another agent before the act, take this connection's data (October #21).
   */
  private fun isScrapeOwnedByConnection(
    connectionAgentId: String?,
    owner: AgentContext,
    scrapeId: Long,
    rpcName: String,
  ): Boolean =
    if (connectionAgentId == null) {
      // No transport-assigned agentId (transportFilterDisabled): fall back to the owner's bound auth identity.
      identityMismatchReason(owner, rpcName) == null
    } else {
      (owner.agentId == connectionAgentId).also { owned ->
        if (!owned) {
          logger.warn {
            "Agent on connection $connectionAgentId sent $rpcName() data for scrapeId $scrapeId, " +
              "which was sent to agent ${owner.agentId}; ignoring"
          }
          countBindingMismatch(rpcName)
        }
      }
    }

  // The owner check ScrapeRequestManager applies to the wrapper it completes or fails for this connection.
  private fun ownedByConnection(
    connectionAgentId: String?,
    rpcName: String,
  ): (ScrapeRequestWrapper) -> Boolean =
    { wrapper -> isScrapeOwnedByConnection(connectionAgentId, wrapper.agentContext, wrapper.scrapeId, rpcName) }

  /**
   * The agent sending one response stream, which each message the stream delivers marks active.
   *
   * Every message counts, whether or not the scrape it answers is still tracked: a result that arrives after
   * Prometheus's timeout, or a chunk of one, still proves the agent is alive. ScrapeRequestManager marks only a result
   * for a tracked scrape, and the agent heartbeats only once it has sent nothing for `heartbeatMaxInactivitySecs`,
   * so an agent on a link too slow for the scrape timeout, always sending and never in time, was evicted every
   * `maxAgentInactivitySecs` while its data kept arriving. A late result isn't a completed request, so the mark
   * leaves the agent's last request time alone.
   *
   * With the transport filter, the connection names the agent. Without it, a result or a chunked header names one,
   * which is marked only when the call presents the identity that agent connected as, the check [sendHeartBeat]
   * makes, so one identity can't keep another's agents connected. A chunk or a summary names no agent, so it marks
   * the one the stream's last accepted message named. Confined to one coroutine, as [ChunkedResponseReader] is.
   */
  private inner class StreamSender(
    private val connectionAgentId: String?,
  ) {
    // The agent the stream's last accepted naming message named; null until one is accepted.
    private var lastNamed: AgentContext? = null

    // Marks the agent that sent a message naming [namedAgentId], or naming none when it's null. Marks nothing for an
    // agent whose context is gone, or one the call may not act on. The identity is checked quietly: for a tracked
    // scrape the owner check logs the mismatch already, and once is enough.
    fun markActive(namedAgentId: String?) {
      val contextManager = proxy.agentContextManager
      val sender =
        when {
          connectionAgentId != null -> contextManager.getAgentContext(connectionAgentId)
          namedAgentId == null -> lastNamed
          else -> contextManager.getAgentContext(namedAgentId)?.takeIf { isCallerBoundTo(it) }?.also { lastNamed = it }
        }
      sender?.markActivityTime(false)
    }
  }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
    val connectionAgentId = ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get()
    val isOwner = ownedByConnection(connectionAgentId, "writeResponsesToProxy")
    val sender = StreamSender(connectionAgentId)
    runCatchingCancellable {
      requests.collect { response ->
        // The agent keeping the stream open, for a reverse proxy in front of this one, and nothing else.
        if (response.keepalive)
          return@collect
        // Whatever becomes of the result: one whose scrape is no longer tracked still proves the agent is alive.
        sender.markActive(response.agentId)
        try {
          if (!proxy.scrapeRequestManager.assignScrapeResults(boundedResponse(response).toScrapeResults(), isOwner))
            proxy.metrics { lateScrapeResultCount.inc() }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.error(e) { "Error processing scrape response for scrapeId: ${response.scrapeId}" }
          // Fail the in-flight request immediately so the waiting HTTP handler returns a 502 now
          // instead of blocking until scrapeRequestTimeoutSecs and reporting a misleading timeout.
          // Mirrors the failScrapeRequest() handling in writeChunkedResponsesToProxy().
          proxy.scrapeRequestManager.failScrapeRequest(
            response.scrapeId,
            "Error processing scrape response: ${e.message}",
            ProxyFailure.INVALID_RESPONSE,
            isOwner,
          )
        }
      }
    }.onFailure { logResponseStreamFailure("writeResponsesToProxy", it) }
    return EMPTY_INSTANCE
  }

  // A chunked transfer its scrape ended before; see Proxy.releaseRemovedAgent for why each counts once.
  private fun countAbandonedTransfer() = proxy.metrics { chunkedTransfersAbandoned.inc() }

  // Logs why a response stream failed, unless the proxy is stopping or the stream was just cancelled. A starting proxy
  // logs it too: see Proxy.isStartingOrRunning.
  private fun logResponseStreamFailure(
    rpcName: String,
    throwable: Throwable,
  ) {
    if (!proxy.isStartingOrRunning)
      return
    val status = Status.fromThrowable(throwable)
    if (status.code != Status.Code.CANCELLED && status.cause !is CancellationException)
      logger.error(throwable) { "Error in $rpcName(): $status" }
  }

  override suspend fun writeChunkedResponsesToProxy(requests: Flow<ChunkedScrapeResponse>): Empty {
    val reader = ChunkedResponseReader(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get())
    try {
      runCatchingCancellable { requests.collect { reader.read(it) } }
        .onFailure { logResponseStreamFailure("writeChunkedResponsesToProxy", it) }
    } finally {
      // In a finally because runCatchingCancellable rethrows CancellationException, and grpc-kotlin cancels the RPC
      // when the agent disconnects mid-transfer: the most common way a stream ends. The sweep doesn't suspend, so it
      // is safe to run while cancelled.
      reader.abandonUnfinished()
    }
    return EMPTY_INSTANCE
  }

  // One writeChunkedResponsesToProxy stream: reassembles each chunked scrape result from its header, chunks, and
  // summary, and fails the transfers the stream leaves unfinished.
  private inner class ChunkedResponseReader(
    private val connectionAgentId: String?,
  ) {
    // The transfers this stream opened and hasn't finished, by scrapeId. A plain (non-thread-safe) map, which is safe
    // ONLY because grpc-kotlin confines a client-streaming RPC's collect{} and the cleanup in its finally to a single
    // coroutine -- there is no launch/async/flowOn here that would fan the body across threads. The genuinely shared
    // chunkedContextMap is a ConcurrentHashMap. If this RPC is ever refactored to process chunks concurrently, switch
    // this to a thread-safe/synchronized map. A ChunkedContext isn't thread-safe either, so only the stream that opened
    // it may touch it; see owns.
    private val transfers = mutableMapOf<Long, ChunkedContext>()
    private val contextManager get() = proxy.agentContextManager
    private val isOwner = ownedByConnection(connectionAgentId, RPC_NAME)
    private val sender = StreamSender(connectionAgentId)

    // The most transfers this stream may have under way: the cap on one agent's queue of scrape requests. The stock
    // agent sends each transfer whole before the next, so it has one at a time (November #13).
    private val maxTransfers = ScrapeDispatch.maxAgentBacklog(proxy)

    // The scrapeId of the last chunk that found no transfer, so a late transfer's chunks log that once, not each time.
    private var lastMissingChunkScrapeId: Long? = null

    // The scrapeId of the last message for a transfer another stream opened, so its chunks warn once, not each time.
    private var lastForeignScrapeId: Long? = null

    fun read(response: ChunkedScrapeResponse) {
      // The agent keeping the stream open, as on writeResponsesToProxy: none of the three parts, and no warning.
      if (response.keepalive)
        return
      // Whatever becomes of the message: a transfer whose scrape is no longer tracked still proves the agent is alive,
      // and on a slow link one transfer can take longer than maxAgentInactivitySecs. Only a header names the agent.
      sender.markActive(if (response.hasHeader()) response.header.headerAgentId else null)
      when (response.chunkOneOfCase) {
        ChunkOneOfCase.HEADER -> readHeader(response)

        ChunkOneOfCase.CHUNK -> readChunk(response.chunk)

        ChunkOneOfCase.SUMMARY -> readSummary(response.summary)

        ChunkOneOfCase.CHUNKONEOF_NOT_SET, null -> logger.warn {
          "Received chunked response with no field set, skipping"
        }
      }
    }

    // Whether this stream may extend or finish [context], the transfer for [scrapeId]: it opened the transfer, which
    // its header could only once the connection's owner check passed. A connection can carry more than one stream, as
    // can an identity with the transport filter disabled, and a second stream passed that check, so it could extend a
    // transfer the first had opened, and the two updated its checksum, counts, and buffer at once (November 26 #1).
    // Data for a transfer this stream didn't open is refused: another agent's is logged and counted by the owner
    // check, checked on the owner the transfer recorded, and another stream's of this connection is warned of here.
    private fun owns(
      context: ChunkedContext,
      scrapeId: Long,
    ): Boolean {
      if (transfers[scrapeId] === context)
        return true
      if (!isScrapeOwnedByConnection(connectionAgentId, context.owner, scrapeId, RPC_NAME))
        return false
      if (scrapeId != lastForeignScrapeId) {
        lastForeignScrapeId = scrapeId
        logger.warn {
          "Agent sent $RPC_NAME() data for scrapeId $scrapeId, whose transfer another stream opened; ignoring"
        }
      }
      return false
    }

    // The scrape is looked up once: that wrapper is both what the owner check reads and the owner the transfer records,
    // so a scrapeId that isn't tracked yet can't pass the check and then be opened for the agent that tracks it next.
    // A header for a scrape no longer tracked, usually one that hit Prometheus's timeout, is logged at DEBUG: its
    // handler has already recorded the outcome (November #3).
    private fun readHeader(response: ChunkedScrapeResponse) {
      val scrapeId = response.header.headerScrapeId
      val wrapper = proxy.scrapeRequestManager.getScrapeRequest(scrapeId)
      if (wrapper == null) {
        logger.debug { "Received chunked header for unknown scrapeId: $scrapeId" }
        proxy.metrics { lateScrapeResultCount.inc() }
        return
      }
      if (!isOwner(wrapper))
        return
      if (!hasRoomForTransfer()) {
        logger.warn {
          "Agent stream has $maxTransfers chunked transfers under way, the most one stream may have " +
            "(twice proxy.internal.scrapeRequestBacklogUnhealthySize); refusing the header for scrapeId: $scrapeId"
        }
        proxy.scrapeRequestManager.failScrapeRequest(
          scrapeId,
          "Too many chunked transfers under way on one stream",
          ProxyFailure.INVALID_RESPONSE,
          isOwner,
        )
        return
      }
      logger.debug { "Reading header for scrapeId: $scrapeId" }
      val maxZippedSize = proxy.proxyConfigVals.internal.maxZippedContentSizeMBytes * 1024L * 1024L
      val context = ChunkedContext(boundedHeader(response), maxZippedSize, wrapper.agentContext)
      when {
        !contextManager.putChunkedContext(scrapeId, context) -> {
          logger.warn { "Received a second chunked header for scrapeId: $scrapeId, keeping the transfer under way" }
        }

        // The scrape's handler stops tracking it and then removes its transfer, so checking again once the transfer is
        // stored means that if the handler returned in between, one of the two removes it (November #13).
        !proxy.scrapeRequestManager.containsScrapeRequest(scrapeId) -> {
          if (contextManager.removeChunkedContext(scrapeId, context)) {
            logger.debug { "Dropped the chunked header for scrapeId: $scrapeId, whose scrape ended as it arrived" }
            countAbandonedTransfer()
          }
        }

        else -> {
          transfers[scrapeId] = context
        }
      }
    }

    // Whether this stream may open one more transfer: fewer than maxTransfers are under way, once the IDs whose
    // transfer has already gone, removed by its scrape's handler or by removeAgentContext, are let go.
    private fun hasRoomForTransfer(): Boolean {
      if (transfers.size >= maxTransfers)
        transfers.entries.retainAll { (scrapeId, context) -> contextManager.getChunkedContext(scrapeId) === context }
      return transfers.size < maxTransfers
    }

    // Whether [scrapeId]'s scrape is still tracked. When it isn't, its handler has returned and nobody awaits the
    // result, so [context], its transfer, is dropped, unless the handler got to it first (November #13).
    private fun isStillTracked(
      scrapeId: Long,
      context: ChunkedContext,
    ): Boolean =
      proxy.scrapeRequestManager.containsScrapeRequest(scrapeId).also { tracked ->
        if (!tracked) {
          transfers -= scrapeId
          if (contextManager.removeChunkedContext(scrapeId, context)) {
            logger.debug { "Dropped the chunked transfer for scrapeId: $scrapeId, whose scrape has ended" }
            countAbandonedTransfer()
          }
        }
      }

    // with(...) rather than apply { }: this block consumes the chunk, it doesn't configure it (finding 36).
    private fun readChunk(chunk: ChunkData) {
      with(chunk) {
        logger.debug { "Reading chunk $chunkCount for scrapeId: $chunkScrapeId" }
        val context = contextManager.getChunkedContext(chunkScrapeId)
        if (context == null) {
          // Gone with its scrape, or never opened. Logged at DEBUG, and for the first such chunk of a transfer only:
          // a late result of 5 MB at 32 KB a chunk logged 160 WARNs (November #3).
          transfers -= chunkScrapeId
          if (chunkScrapeId != lastMissingChunkScrapeId) {
            lastMissingChunkScrapeId = chunkScrapeId
            logger.debug { "Missing chunked context for chunk with scrapeId: $chunkScrapeId, skipping" }
          }
          return
        }
        if (!owns(context, chunkScrapeId))
          return
        if (!isStillTracked(chunkScrapeId, context)) {
          // The rest of this transfer's chunks will find no transfer; that needn't be logged too.
          lastMissingChunkScrapeId = chunkScrapeId
          return
        }
        try {
          context.applyChunk(chunkBytes.toByteArray(), chunkByteCount, chunkCount, chunkChecksum)
        } catch (e: ChunkValidationException) {
          logger.error(e) { "Chunk validation failed for scrapeId: $chunkScrapeId, discarding context" }
          contextManager.removeChunkedContext(chunkScrapeId, context)
          transfers -= chunkScrapeId
          proxy.scrapeRequestManager.failScrapeRequest(
            chunkScrapeId,
            "Chunk validation failed: ${e.message}",
            ProxyFailure.INVALID_RESPONSE,
          )
          proxy.metrics { chunkValidationFailures.labelValues(ProxyMetrics.STAGE_CHUNK).inc() }
        }
      }
    }

    // with(...) rather than apply { }: this block consumes the summary (finding 36).
    private fun readSummary(summary: SummaryData) {
      with(summary) {
        val context = contextManager.getChunkedContext(summaryScrapeId)
        if (context != null && !owns(context, summaryScrapeId))
          return
        transfers -= summaryScrapeId
        // Removes only the transfer just checked, which a concurrent removeAgentContext may already have reclaimed. A
        // summary for a transfer that went with its scrape is routine, so it's logged at DEBUG (November #3).
        if (context == null || !contextManager.removeChunkedContext(summaryScrapeId, context)) {
          logger.debug { "Missing chunked context for summary with scrapeId: $summaryScrapeId, skipping" }
          return
        }
        // Nobody awaits a scrape no longer tracked, so its transfer, now removed, isn't checked or assigned
        // (November #13).
        if (!proxy.scrapeRequestManager.containsScrapeRequest(summaryScrapeId)) {
          logger.debug { "Dropped the chunked transfer for scrapeId: $summaryScrapeId, whose scrape has ended" }
          countAbandonedTransfer()
          return
        }
        logger.debug {
          val ccnt = context.totalChunkCount
          val bcnt = context.totalByteCount
          "Reading summary chunkCount: $ccnt byteCount: $bcnt for scrapeId: $summaryScrapeId"
        }
        try {
          val scrapeResults = context.applySummary(summaryChunkCount, summaryByteCount, summaryChecksum)
          // The scrape can end between the check above and here; its transfer, already removed, was then abandoned.
          if (!proxy.scrapeRequestManager.assignScrapeResults(scrapeResults, isOwner))
            countAbandonedTransfer()
        } catch (e: ChunkValidationException) {
          logger.error(e) { "Summary validation failed for scrapeId: $summaryScrapeId" }
          proxy.scrapeRequestManager.failScrapeRequest(
            summaryScrapeId,
            "Summary validation failed: ${e.message}",
            ProxyFailure.INVALID_RESPONSE,
          )
          proxy.metrics { chunkValidationFailures.labelValues(ProxyMetrics.STAGE_SUMMARY).inc() }
        }
      }
    }

    // Cleans up the chunked contexts the stream left without a summary (e.g., due to stream cancellation or agent
    // disconnect mid-transfer). Runs in writeChunkedResponsesToProxy's finally, so it must not suspend. It removes only
    // the transfers this stream opened, each if it's still the one for its scrapeId, so it never removes another
    // stream's or another agent's.
    //
    // This sweep can race Proxy.removeAgentContext() (from the transport-terminated or cleanup-service
    // thread), Proxy.removeAgentContextIfCurrent() (as a scrape-request stream ends, with the transport
    // filter disabled), and the scrape's HTTP handler, which removes the transfer as it returns, for the same
    // scrapeId. Double-handling is prevented by two invariants that future edits must preserve:
    // (a) contextManager.removeChunkedContext() is a ConcurrentHashMap.remove(), so only one caller removes
    // the context and the block below (warn + failScrapeRequest + metric) runs at most once,
    // and either removal, which counts the transfers it reclaims in the same metric, never counts one
    // this sweep removed; and (b) failScrapeRequest() -> ScrapeRequestWrapper.complete() is idempotent
    // via an AtomicBoolean compareAndSet, so even a double-fail has no observable effect.
    fun abandonUnfinished() {
      transfers.forEach { (scrapeId, context) ->
        if (contextManager.removeChunkedContext(scrapeId, context)) {
          logger.warn { "Cleaned up orphaned ChunkedContext for scrapeId: $scrapeId" }
          proxy.scrapeRequestManager.failScrapeRequest(
            scrapeId,
            "Chunked transfer abandoned: stream terminated before summary received",
            ProxyFailure.AGENT_DISCONNECTED,
          )
          countAbandonedTransfer()
        }
      }
    }
  }

  companion object {
    private val logger = logger {}
    private val PATH_ID_GENERATOR = AtomicLong(0L)
    private const val RPC_NAME = "writeChunkedResponsesToProxy"

    private val KEEPALIVE_NOTICE: PathNotice = PathNotice.getDefaultInstance()
    private val KEEPALIVE_REQUEST: ScrapeRequest = scrapeRequest { keepalive = true }
    internal const val UNKNOWN_ADDRESS = "Unknown"

    // The most characters the proxy keeps of each string in a scrape result, sent whole or in chunks: its agent ID,
    // URL, failure reason, and content type; see boundedResponse and boundedHeader. A fixed bound, not
    // proxy.internal.maxTargetUrlLength: it caps what a transfer holds while it lasts (November #13), so a limit an
    // operator can turn off mustn't lift it. A result's strings are cut again where they're shown; see ScrapeDispatch.
    internal const val MAX_RESULT_FIELD_LENGTH = 4096

    // Whether any of [fields] is longer than MAX_RESULT_FIELD_LENGTH characters.
    private fun anyTooLong(vararg fields: String) = fields.any { it.length > MAX_RESULT_FIELD_LENGTH }

    private fun cut(value: String) = truncate(value, MAX_RESULT_FIELD_LENGTH)

    // [response] with each of its strings but its body cut to MAX_RESULT_FIELD_LENGTH characters, as a chunked
    // transfer's header is. gRPC's 4 MiB message limit was their only bound, so an agent's content type of megabytes
    // reached the scrape's response header (November 26 #38).
    private fun boundedResponse(response: ScrapeResponse): ScrapeResponse {
      if (!anyTooLong(response.agentId, response.url, response.failureReason, response.contentType))
        return response
      logger.debug {
        "Cut the scrape result for scrapeId: ${response.scrapeId} to $MAX_RESULT_FIELD_LENGTH characters a field"
      }
      return response.toBuilder()
        .setAgentId(cut(response.agentId))
        .setUrl(cut(response.url))
        .setFailureReason(cut(response.failureReason))
        .setContentType(cut(response.contentType))
        .build()
    }

    // [response] with each string in its header cut to MAX_RESULT_FIELD_LENGTH characters. A transfer keeps
    // its header until it ends, and gRPC's 4 MiB message limit was the only other bound on them, so each transfer could
    // hold up to 4 MiB of header besides its chunks (November #13). A real agent ID, URL, failure reason, or content
    // type is far shorter, so they are cut rather than refused.
    private fun boundedHeader(response: ChunkedScrapeResponse): ChunkedScrapeResponse {
      val header = response.header
      if (!anyTooLong(header.headerAgentId, header.headerUrl, header.headerFailureReason, header.headerContentType))
        return response
      logger.debug {
        "Cut the chunked header for scrapeId: ${header.headerScrapeId} to $MAX_RESULT_FIELD_LENGTH characters a field"
      }
      return response.toBuilder()
        .setHeader(
          header.toBuilder()
            .setHeaderAgentId(cut(header.headerAgentId))
            .setHeaderUrl(cut(header.headerUrl))
            .setHeaderFailureReason(cut(header.headerFailureReason))
            .setHeaderContentType(cut(header.headerContentType)),
        ).build()
    }

    // [agentId] as a log line or a reason shows it, cut as a rejected path is. The proxy assigns agentIds, so a real
    // one is short, but request.agentId is caller-supplied, up to gRPC's 4 MiB message limit, until it's matched to a
    // context (October #4).
    private fun shownAgentId(agentId: String): String = ProxyPathManager.shownPath(agentId)
  }
}
