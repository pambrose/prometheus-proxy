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
import io.prometheus.common.ScrapeResults.Companion.toScrapeResults
import io.prometheus.common.AgentValues.truncate
import io.prometheus.grpc.AgentInfo
import io.prometheus.grpc.ChunkData
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ChunkedScrapeResponse.ChunkOneOfCase
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.PathMapSizeRequest
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

/**
 * Server-side implementation of the `ProxyService` gRPC service.
 *
 * Handles all gRPC RPCs defined in `proxy_service.proto`: agent connection and registration,
 * path registration/unregistration, heartbeat processing, and bidirectional scrape request/response
 * streaming (including chunked transfers for large payloads). Validates agent identity on every
 * call and delegates state management to [AgentContextManager], [ProxyPathManager], and
 * [ScrapeRequestManager].
 *
 * @param proxy the parent [Proxy] instance
 * @see ProxyGrpcService
 * @see AgentContextManager
 * @see ScrapeRequestManager
 */
internal class ProxyServiceImpl(
  private val proxy: Proxy,
) : ProxyServiceGrpcKt.ProxyServiceCoroutineImplBase() {
  override suspend fun connectAgent(request: Empty): Empty {
    if (proxy.options.transportFilterDisabled) {
      val msg = "Agent (false) and Proxy (true) do not have matching transportFilterDisabled config values"
      logger.error { msg }
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
      throw StatusException(Status.FAILED_PRECONDITION.withDescription(msg))
    }

    proxy.metrics { connectCount.inc() }
    // With no transport filter there is no connection-assigned agentId to bind later calls to, so bind them
    // to the auth identity this call presented instead (see identityMismatchReason). Empty when auth is off.
    val identityName = AgentAuthManager.AGENT_IDENTITY_KEY.get()?.name.orEmpty()
    val agentContext = AgentContext(UNKNOWN_ADDRESS, identityName)
    // Every call creates a context, which lasts until its stream ends or it is evicted, so the contexts one identity
    // may hold are limited (November #17). A connect past the limit is refused rather than evicting another context:
    // after a proxy restart a whole fleet reconnects at once, and evicting would drop agents that just connected. The
    // agent retries a refused connect after its reconnect pause.
    val maxAgents = proxy.maxAgentsPerIdentity
    if (!proxy.agentContextManager.addAgentContextWithinLimit(agentContext, maxAgents)) {
      val msg =
        "The proxy already has $maxAgents agents connected with this agent's identity, its " +
          "proxy.internal.maxAgentsPerIdentity limit; retry once one disconnects"
      throw StatusException(Status.RESOURCE_EXHAUSTED.withDescription(msg))
    }
    return agentInfo { agentId = agentContext.agentId }
  }

  /**
   * Rejects a request whose `agentId` is not the one the transport assigned to this connection.
   *
   * `request.agentId` is caller-supplied, and agentIds are sequential integers that the proxy echoes
   * back to every client in the `agent-id` response header — so without this check an authenticated
   * agent could enumerate its neighbors and pass a victim's agentId to act on the victim's
   * [AgentContext] while presenting its own valid token.
   *
   * Returns null (allow) when the connection agentId is unset, which happens when no transport
   * filter ran: `transportFilterDisabled` deployments and in-process tests. Those paths have no
   * transport-assigned identity to compare against; [identityMismatchReason] binds them instead.
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
      "agentId $shown does not match the connection's agentId ($rpcName)"
    }
  }

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
    val boundIdentityName = agentContext.authIdentityName
    if (boundIdentityName.isEmpty())
      return null
    val callerIdentityName = AgentAuthManager.AGENT_IDENTITY_KEY.get()?.name
    return if (callerIdentityName == boundIdentityName) {
      null
    } else {
      logger.warn {
        "Identity '$callerIdentityName' sent $rpcName() for agentId ${agentContext.agentId}, " +
          "which connected as identity '$boundIdentityName'; rejecting"
      }
      "agentId ${agentContext.agentId} is bound to a different agent identity ($rpcName)"
    }
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
            agentContext.assignProperties(boundedRegistration(request, agentContext.agentId))
            agentContext.markActivityTime(false)
            logger.info { "Connected to $agentContext" }
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
                ?: authorizationRejection(identity, request.path)
                ?: proxy.pathManager.addPath(
                  request.path,
                  request.labels,
                  agentContext,
                  PathMetadata(
                    targetUrl = request.targetUrl,
                    pathSource = request.pathSource,
                    // Only the same identity may take over a path a live agent already serves.
                    identityName = identity?.name.orEmpty(),
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
      }
      pathCount = proxy.pathManager.pathMapSize
    }
  }

  // Why [identity] may not register [path], or null when it may, or when per-agent auth is off (no identity). The log
  // line and the reason show the path as ProxyPathManager's rejections do, cut to its first characters.
  private fun authorizationRejection(
    identity: AgentIdentity?,
    path: String,
  ): PathRejection? {
    if (identity == null || identity.isAuthorized(path))
      return null
    val shown = ProxyPathManager.shownPath(path.removePrefix("/"))
    logger.warn { "Agent identity '${identity.name}' denied registration of path /$shown" }
    return PathRejection("Agent identity '${identity.name}' is not authorized to register path /$shown", NOT_AUTHORIZED)
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
        proxy.metrics { heartbeatCount.inc() }
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
        if (failureReason == null)
          agentContext?.markActivityTime(false)
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
      val agentContext = readableAgentContext(request.agentId)
      try {
        emitScrapeRequests(agentContext)
      } finally {
        // When transportFilterDisabled is true, there is no ProxyServerTransportFilter to
        // detect agent disconnect and clean up. Handle cleanup here on stream termination.
        if (proxy.options.transportFilterDisabled)
          proxy.removeAgentContextIfCurrent(agentContext, "Stream terminated (transport filter disabled)")
      }
    }

  /**
   * The context whose scrape requests this connection may read: [agentId]'s, from one lookup.
   *
   * Throws PERMISSION_DENIED when the transport belongs to another agentId, or the caller's auth identity isn't the one
   * the context is bound to, and NOT_FOUND when there is no context. The identity was checked on one lookup and the
   * stream read from a second, so a caller that named an agentId about to be assigned passed the check while it was
   * free, and then streamed the context another identity connected with under it, or, when the second lookup also
   * found nothing, removed that context as its stream ended (November #14).
   */
  private fun readableAgentContext(agentId: String): AgentContext {
    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    val denial =
      connectionMismatchReason(agentId, "readRequestsFromProxy")
        ?: agentContext?.let { identityMismatchReason(it, "readRequestsFromProxy") }
    if (denial != null)
      throw StatusException(Status.PERMISSION_DENIED.withDescription(denial))
    return agentContext
      ?: throw StatusException(
        Status.NOT_FOUND.withDescription("No AgentContext found for agentId: ${shownAgentId(agentId)}"),
      )
  }

  // Delivers [agentContext]'s queued scrape requests until the proxy stops or the context is invalidated, skipping
  // the ones no longer awaited. Starting counts: see Proxy.isStartingOrRunning.
  private suspend fun FlowCollector<ScrapeRequest>.emitScrapeRequests(agentContext: AgentContext) {
    while (proxy.isStartingOrRunning && agentContext.isValid()) {
      val wrapper = agentContext.readScrapeRequest()?.takeIf { isStillAwaited(it) } ?: continue
      try {
        emit(wrapper.scrapeRequest)
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
        if (!owned)
          logger.warn {
            "Agent on connection $connectionAgentId sent $rpcName() data for scrapeId $scrapeId, " +
              "which was sent to agent ${owner.agentId}; ignoring"
          }
      }
    }

  // The owner check ScrapeRequestManager applies to the wrapper it completes or fails for this connection.
  private fun ownedByConnection(
    connectionAgentId: String?,
    rpcName: String,
  ): (ScrapeRequestWrapper) -> Boolean =
    { wrapper -> isScrapeOwnedByConnection(connectionAgentId, wrapper.agentContext, wrapper.scrapeId, rpcName) }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
    val isOwner = ownedByConnection(ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get(), "writeResponsesToProxy")
    runCatchingCancellable {
      requests.collect { response ->
        try {
          proxy.scrapeRequestManager.assignScrapeResults(response.toScrapeResults(), isOwner)
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

  // Logs why a response stream failed, unless the proxy is stopping or the stream was just cancelled.
  private fun logResponseStreamFailure(
    rpcName: String,
    throwable: Throwable,
  ) {
    if (!proxy.isRunning)
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
    // The scrapes with a transfer this stream opened and hasn't finished. A plain (non-thread-safe) set, which is safe
    // ONLY because grpc-kotlin confines a client-streaming RPC's collect{} and the cleanup in its finally to a single
    // coroutine -- there is no launch/async/flowOn here that would fan the body across threads. The genuinely shared
    // chunkedContextMap is a ConcurrentHashMap. If this RPC is ever refactored to process chunks concurrently, switch
    // this to a thread-safe/synchronized set.
    private val activeScrapeIds = mutableSetOf<Long>()
    private val contextManager get() = proxy.agentContextManager
    private val isOwner = ownedByConnection(connectionAgentId, RPC_NAME)

    // The most transfers this stream may have under way: the cap on one agent's queue of scrape requests. The stock
    // agent sends each transfer whole before the next, so it has one at a time (November #13).
    private val maxTransfers = ProxyHttpRoutes.maxAgentBacklog(proxy)

    // The scrapeId of the last chunk that found no transfer, so a late transfer's chunks log that once, not each time.
    private var lastMissingChunkScrapeId: Long? = null

    fun read(response: ChunkedScrapeResponse) {
      when (response.chunkOneOfCase) {
        ChunkOneOfCase.HEADER -> readHeader(response)

        ChunkOneOfCase.CHUNK -> readChunk(response.chunk)

        ChunkOneOfCase.SUMMARY -> readSummary(response.summary)

        ChunkOneOfCase.CHUNKONEOF_NOT_SET, null -> logger.warn {
          "Received chunked response with no field set, skipping"
        }
      }
    }

    // Whether this connection may extend [context], the transfer for [scrapeId]: checked on the owner the transfer
    // recorded when it was opened, not on a fresh lookup of the scrape.
    private fun owns(
      context: ChunkedContext,
      scrapeId: Long,
    ) = isScrapeOwnedByConnection(connectionAgentId, context.owner, scrapeId, RPC_NAME)

    // The scrape is looked up once: that wrapper is both what the owner check reads and the owner the transfer records,
    // so a scrapeId that isn't tracked yet can't pass the check and then be opened for the agent that tracks it next.
    // A header for a scrape no longer tracked, usually one that hit Prometheus's timeout, is logged at DEBUG: its
    // handler has already recorded the outcome (November #3).
    private fun readHeader(response: ChunkedScrapeResponse) {
      val scrapeId = response.header.headerScrapeId
      val wrapper = proxy.scrapeRequestManager.getScrapeRequest(scrapeId)
      if (wrapper == null) {
        logger.debug { "Received chunked header for unknown scrapeId: $scrapeId" }
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
          contextManager.removeChunkedContext(scrapeId, context)
          logger.debug { "Dropped the chunked header for scrapeId: $scrapeId, whose scrape ended as it arrived" }
        }

        else -> {
          activeScrapeIds += scrapeId
        }
      }
    }

    // Whether this stream may open one more transfer: fewer than maxTransfers are under way, once the IDs whose
    // transfer has already gone, removed by its scrape's handler or by removeAgentContext, are let go.
    private fun hasRoomForTransfer(): Boolean {
      if (activeScrapeIds.size >= maxTransfers)
        activeScrapeIds.retainAll { contextManager.getChunkedContext(it) != null }
      return activeScrapeIds.size < maxTransfers
    }

    // Whether [scrapeId]'s scrape is still tracked. When it isn't, its handler has returned and nobody awaits the
    // result, so [context], its transfer, is dropped, unless the handler got to it first (November #13).
    private fun isStillTracked(
      scrapeId: Long,
      context: ChunkedContext,
    ): Boolean =
      proxy.scrapeRequestManager.containsScrapeRequest(scrapeId).also { tracked ->
        if (!tracked) {
          activeScrapeIds -= scrapeId
          if (contextManager.removeChunkedContext(scrapeId, context))
            logger.debug { "Dropped the chunked transfer for scrapeId: $scrapeId, whose scrape has ended" }
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
          activeScrapeIds -= chunkScrapeId
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
          activeScrapeIds -= chunkScrapeId
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
        activeScrapeIds -= summaryScrapeId
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
          return
        }
        logger.debug {
          val ccnt = context.totalChunkCount
          val bcnt = context.totalByteCount
          "Reading summary chunkCount: $ccnt byteCount: $bcnt for scrapeId: $summaryScrapeId"
        }
        try {
          val scrapeResults = context.applySummary(summaryChunkCount, summaryByteCount, summaryChecksum)
          proxy.scrapeRequestManager.assignScrapeResults(scrapeResults, isOwner)
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
    // disconnect mid-transfer). Runs in writeChunkedResponsesToProxy's finally, so it must not suspend. A transfer is
    // opened only by the agent its scrape was sent to, and scrapeIds are never reused, so removing by scrapeId never
    // removes another agent's transfer.
    //
    // This sweep can race Proxy.removeAgentContext() (from the transport-terminated or cleanup-service
    // thread), and the scrape's HTTP handler, which removes the transfer as it returns, for the same
    // scrapeId. Double-handling is prevented by two invariants that future edits must preserve:
    // (a) contextManager.removeChunkedContext() is a ConcurrentHashMap.remove(), so only one caller gets
    // the non-null context and the ?.also block (warn + failScrapeRequest + metric) runs at most once,
    // and removeAgentContext, which counts the transfers it reclaims in the same metric, never counts one
    // this sweep removed; and (b) failScrapeRequest() -> ScrapeRequestWrapper.complete() is idempotent
    // via an AtomicBoolean compareAndSet, so even a double-fail has no observable effect.
    fun abandonUnfinished() {
      activeScrapeIds.forEach { scrapeId ->
        contextManager.removeChunkedContext(scrapeId)
          ?.also {
            logger.warn { "Cleaned up orphaned ChunkedContext for scrapeId: $scrapeId" }
            proxy.scrapeRequestManager.failScrapeRequest(
              scrapeId,
              "Chunked transfer abandoned: stream terminated before summary received",
              ProxyFailure.AGENT_DISCONNECTED,
            )
            proxy.metrics { chunkedTransfersAbandoned.inc() }
          }
      }
    }
  }

  companion object {
    private val logger = logger {}
    private val PATH_ID_GENERATOR = AtomicLong(0L)
    private const val RPC_NAME = "writeChunkedResponsesToProxy"
    internal const val UNKNOWN_ADDRESS = "Unknown"

    // The most characters a chunked transfer keeps of each string in its header; see boundedHeader. A fixed bound, not
    // proxy.internal.maxTargetUrlLength: it caps what a transfer holds while it lasts (November #13), so a limit an
    // operator can turn off mustn't lift it. A result's strings are cut again where they're shown; see ProxyHttpRoutes.
    internal const val MAX_CHUNKED_HEADER_FIELD_LENGTH = 4096

    // [response] with each string in its header cut to MAX_CHUNKED_HEADER_FIELD_LENGTH characters. A transfer keeps
    // its header until it ends, and gRPC's 4 MiB message limit was the only other bound on them, so each transfer could
    // hold up to 4 MiB of header besides its chunks (November #13). A real agent ID, URL, failure reason, or content
    // type is far shorter, so they are cut rather than refused.
    private fun boundedHeader(response: ChunkedScrapeResponse): ChunkedScrapeResponse {
      val header = response.header
      val max = MAX_CHUNKED_HEADER_FIELD_LENGTH
      val fields = listOf(header.headerAgentId, header.headerUrl, header.headerFailureReason, header.headerContentType)
      if (fields.none { it.length > max })
        return response

      fun cut(value: String) = truncate(value, max)

      logger.debug { "Cut the chunked header for scrapeId: ${header.headerScrapeId} to $max characters a field" }
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
