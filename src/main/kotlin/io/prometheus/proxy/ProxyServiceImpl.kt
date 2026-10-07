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
    // An agent's first call, which the auth interceptor has already let through, so this is where the connection's
    // context becomes a connected agent (see AgentContextManager.addAgentContext).
    ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get()?.also { proxy.agentContextManager.announceAgentContext(it) }
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
    return agentInfo {
      agentId = agentContext.agentId
    }.also {
      proxy.agentContextManager.addAgentContext(agentContext)
    }
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
      logger.warn {
        "Agent on connection $connectionAgentId sent agentId $requestAgentId to $rpcName(); rejecting"
      }
      "agentId $requestAgentId does not match the connection's agentId ($rpcName)"
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
          logger.error { "registerAgent() missing AgentContext agentId: ${request.agentId}" }
          "Invalid agentId: ${request.agentId} (registerAgent)"
        } else {
          identityMismatchReason(agentContext, "registerAgent") ?: run {
            agentContext.assignProperties(request)
            agentContext.markActivityTime(false)
            logger.info { "Connected to $agentContext" }
            // Identity is only populated here; AgentConnected fired at connectAgent, before the agent had told us
            // who it is.
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

  override suspend fun registerPath(request: RegisterPathRequest): RegisterPathResponse {
    // addPath() and the binding and authorization checks below return null on success or the rejection; rejection
    // is null iff valid.
    val rejection =
      connectionMismatchReason(request.agentId, "registerPath")?.let { PathRejection(it, BINDING_MISMATCH) } ?: run {
        val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
        if (agentContext == null) {
          logger.error { "Missing AgentContext for agentId: ${request.agentId}" }
          PathRejection("Invalid agentId: ${request.agentId} (registerPath)", INVALID_AGENT)
        } else {
          identityMismatchReason(agentContext, "registerPath")?.let { PathRejection(it, BINDING_MISMATCH) } ?: run {
            // AGENT_IDENTITY_KEY is null when per-agent auth is disabled (no interceptor); an identity
            // with no path patterns authorizes everything, so legacy single-token behavior is unchanged.
            val identity = AgentAuthManager.AGENT_IDENTITY_KEY.get()
            val result =
              if (identity != null && !identity.isAuthorized(request.path)) {
                val normalizedPath = request.path.removePrefix("/")
                logger.warn { "Agent identity '${identity.name}' denied registration of path /$normalizedPath" }
                PathRejection(
                  "Agent identity '${identity.name}' is not authorized to register path /$normalizedPath",
                  NOT_AUTHORIZED,
                )
              } else {
                proxy.pathManager.addPath(
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
              }
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
      logger.error { "Missing AgentContext for agentId: $agentId" }
      unregisterPathResponse {
        valid = false
        reason = "Invalid agentId: $agentId (unregisterPath)"
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
              logger.error { "sendHeartBeat() missing AgentContext agentId: ${request.agentId}" }
              "Invalid agentId: ${request.agentId} (sendHeartBeat)"
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
      val agentId = request.agentId
      readRequestsDenial(agentId)?.also { reason ->
        // Thrown before the try/finally so the mismatched agentId never reaches the cleanup branch —
        // a spoofed id must not be able to evict another agent's context.
        throw StatusException(Status.PERMISSION_DENIED.withDescription(reason))
      }
      try {
        val agentContext = proxy.agentContextManager.getAgentContext(agentId)
          ?: throw StatusException(Status.NOT_FOUND.withDescription("No AgentContext found for agentId: $agentId"))
        emitScrapeRequests(agentContext)
      } finally {
        // When transportFilterDisabled is true, there is no ProxyServerTransportFilter to
        // detect agent disconnect and clean up. Handle cleanup here on stream termination.
        if (proxy.options.transportFilterDisabled) {
          proxy.removeAgentContext(agentId, "Stream terminated (transport filter disabled)")
        }
      }
    }

  // Why this connection may not read [agentId]'s scrape requests: the transport belongs to another agentId, or the
  // caller's auth identity isn't the one the agent's context is bound to. Null when it may.
  private fun readRequestsDenial(agentId: String): String? =
    connectionMismatchReason(agentId, "readRequestsFromProxy")
      ?: proxy.agentContextManager.getAgentContext(agentId)?.let { identityMismatchReason(it, "readRequestsFromProxy") }

  // Delivers [agentContext]'s queued scrape requests until the proxy stops or the context is invalidated, skipping
  // the ones no longer awaited.
  private suspend fun FlowCollector<ScrapeRequest>.emitScrapeRequests(agentContext: AgentContext) {
    while (proxy.isRunning && agentContext.isValid()) {
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
   * Returns whether this connection may act on [scrapeId]'s result.
   *
   * Scrape IDs come from one process-wide counter, so without this check any authenticated agent could
   * answer, fail, or disrupt the chunked transfer of a scrape that was sent to a different agent. Compares the
   * connection's transport-assigned agentId when there is one, and otherwise the owner's bound auth identity
   * (see [identityMismatchReason]). Allows the call when the scrape is no longer tracked, where the existing
   * missing-request handling applies.
   */
  private fun isScrapeOwnedByConnection(
    connectionAgentId: String?,
    scrapeId: Long,
    rpcName: String,
  ): Boolean {
    val ownerAgentId = proxy.scrapeRequestManager.ownerAgentId(scrapeId) ?: return true
    return if (connectionAgentId == null) {
      // No transport-assigned agentId (transportFilterDisabled): fall back to the owner's bound auth identity.
      proxy.agentContextManager.getAgentContext(ownerAgentId)
        ?.let { owner -> identityMismatchReason(owner, rpcName) == null }
        ?: true
    } else {
      (ownerAgentId == connectionAgentId).also { owned ->
        if (!owned)
          logger.warn {
            "Agent on connection $connectionAgentId sent $rpcName() data for scrapeId $scrapeId, " +
              "which was sent to agent $ownerAgentId; ignoring"
          }
      }
    }
  }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
    val connectionAgentId = ProxyServerInterceptor.CONNECTION_AGENT_ID_KEY.get()
    runCatchingCancellable {
      requests.collect { response ->
        if (!isScrapeOwnedByConnection(connectionAgentId, response.scrapeId, "writeResponsesToProxy"))
          return@collect
        try {
          val scrapeResults = response.toScrapeResults()
          proxy.scrapeRequestManager.assignScrapeResults(scrapeResults)
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
    runCatchingCancellable { requests.collect { reader.read(it) } }
      .onFailure { logResponseStreamFailure("writeChunkedResponsesToProxy", it) }
    reader.abandonUnfinished()
    return EMPTY_INSTANCE
  }

  // One writeChunkedResponsesToProxy stream: reassembles each chunked scrape result from its header, chunks, and
  // summary, and fails the transfers the stream leaves unfinished.
  private inner class ChunkedResponseReader(
    private val connectionAgentId: String?,
  ) {
    // The scrapes with a transfer under way on this stream. A plain (non-thread-safe) set, which is safe ONLY because
    // grpc-kotlin confines a client-streaming RPC's collect{} and the post-collect cleanup to a single coroutine --
    // there is no launch/async/flowOn here that would fan the body across threads. The genuinely shared
    // chunkedContextMap is a ConcurrentHashMap. If this RPC is ever refactored to process chunks concurrently, switch
    // this to a thread-safe/synchronized set.
    private val activeScrapeIds = mutableSetOf<Long>()
    private val contextManager get() = proxy.agentContextManager

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

    private fun isOwned(scrapeId: Long) =
      isScrapeOwnedByConnection(connectionAgentId, scrapeId, "writeChunkedResponsesToProxy")

    private fun readHeader(response: ChunkedScrapeResponse) {
      val scrapeId = response.header.headerScrapeId
      if (!isOwned(scrapeId))
        return
      if (proxy.scrapeRequestManager.containsScrapeRequest(scrapeId)) {
        logger.debug { "Reading header for scrapeId: $scrapeId" }
        val maxZippedSize = proxy.proxyConfigVals.internal.maxZippedContentSizeMBytes * 1024L * 1024L
        contextManager.putChunkedContext(scrapeId, ChunkedContext(response, maxZippedSize))
        activeScrapeIds += scrapeId
      } else {
        logger.warn { "Received chunked header for unknown scrapeId: $scrapeId" }
      }
    }

    // with(...) rather than apply { }: this block consumes the chunk, it doesn't configure it (finding 36).
    private fun readChunk(chunk: ChunkData) {
      with(chunk) {
        logger.debug { "Reading chunk $chunkCount for scrapeId: $chunkScrapeId" }
        if (!isOwned(chunkScrapeId))
          return
        val context = contextManager.getChunkedContext(chunkScrapeId)
        if (context == null) {
          logger.warn { "Missing chunked context for chunk with scrapeId: $chunkScrapeId, skipping" }
          return
        }
        try {
          context.applyChunk(chunkBytes.toByteArray(), chunkByteCount, chunkCount, chunkChecksum)
        } catch (e: ChunkValidationException) {
          logger.error(e) { "Chunk validation failed for scrapeId: $chunkScrapeId, discarding context" }
          contextManager.removeChunkedContext(chunkScrapeId)
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
        if (!isOwned(summaryScrapeId))
          return
        val context = contextManager.removeChunkedContext(summaryScrapeId)
        activeScrapeIds -= summaryScrapeId
        if (context == null) {
          logger.warn { "Missing chunked context for summary with scrapeId: $summaryScrapeId, skipping" }
          return
        }
        logger.debug {
          val ccnt = context.totalChunkCount
          val bcnt = context.totalByteCount
          "Reading summary chunkCount: $ccnt byteCount: $bcnt for scrapeId: $summaryScrapeId"
        }
        try {
          val scrapeResults = context.applySummary(summaryChunkCount, summaryByteCount, summaryChecksum)
          proxy.scrapeRequestManager.assignScrapeResults(scrapeResults)
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
    // disconnect mid-transfer).
    //
    // This sweep can race Proxy.removeAgentContext() (from the transport-terminated or cleanup-service
    // thread) for the same scrapeId. Double-handling is prevented by two invariants that future edits
    // must preserve: (a) contextManager.removeChunkedContext() is a ConcurrentHashMap.remove(), so only
    // one caller gets the non-null context and the ?.also block (warn + failScrapeRequest + metric)
    // runs at most once; and (b) failScrapeRequest() -> ScrapeRequestWrapper.complete() is idempotent
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
    internal const val UNKNOWN_ADDRESS = "Unknown"
  }
}
