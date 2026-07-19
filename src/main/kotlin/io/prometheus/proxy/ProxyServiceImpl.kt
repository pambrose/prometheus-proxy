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
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ChunkedScrapeResponse.ChunkOneOfCase
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.PathMapSizeRequest
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.RegisterAgentResponse
import io.prometheus.grpc.RegisterPathRequest
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.UnregisterPathRequest
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatResponse
import io.prometheus.grpc.pathMapSizeResponse
import io.prometheus.grpc.registerAgentResponse
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.unregisterPathResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
    return EMPTY_INSTANCE
  }

  override suspend fun connectAgentWithTransportFilterDisabled(request: Empty): AgentInfo {
    if (!proxy.options.transportFilterDisabled) {
      val msg = "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
      logger.error { msg }
      throw StatusException(Status.FAILED_PRECONDITION.withDescription(msg))
    }

    proxy.metrics { connectCount.inc() }
    val agentContext = AgentContext(UNKNOWN_ADDRESS)
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
   * transport-assigned identity to compare against, so behavior there is unchanged.
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

  override suspend fun registerAgent(request: RegisterAgentRequest): RegisterAgentResponse {
    val failureReason =
      connectionMismatchReason(request.agentId, "registerAgent") ?: run {
        val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
        if (agentContext == null) {
          logger.error { "registerAgent() missing AgentContext agentId: ${request.agentId}" }
          "Invalid agentId: ${request.agentId} (registerAgent)"
        } else {
          agentContext.assignProperties(request)
          agentContext.markActivityTime(false)
          logger.info { "Connected to $agentContext" }
          null
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
    // addPath() (and the binding/authorization checks below) return null on success or a failure
    // message; failureReason is null iff valid.
    val failureReason =
      connectionMismatchReason(request.agentId, "registerPath") ?: run {
        val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
        if (agentContext == null) {
          logger.error { "Missing AgentContext for agentId: ${request.agentId}" }
          "Invalid agentId: ${request.agentId} (registerPath)"
        } else {
          // AGENT_IDENTITY_KEY is null when per-agent auth is disabled (no interceptor); an identity
          // with no path patterns authorizes everything, so legacy single-token behavior is unchanged.
          val identity = AgentAuthManager.AGENT_IDENTITY_KEY.get()
          val reason =
            if (identity != null && !identity.isAuthorized(request.path)) {
              val normalizedPath = request.path.removePrefix("/")
              logger.warn { "Agent identity '${identity.name}' denied registration of path /$normalizedPath" }
              "Agent identity '${identity.name}' is not authorized to register path /$normalizedPath"
            } else {
              proxy.pathManager.addPath(request.path, request.labels, agentContext)
            }
          reason.also { agentContext.markActivityTime(false) }
        }
      }

    val isValid = failureReason == null
    return registerPathResponse {
      pathId = if (isValid) PATH_ID_GENERATOR.fetchAndIncrement() else -1
      valid = isValid
      if (!isValid) {
        // Smart-cast to non-null: isValid == false implies failureReason != null.
        reason = failureReason
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
    return if (agentContext == null) {
      logger.error { "Missing AgentContext for agentId: $agentId" }
      unregisterPathResponse {
        valid = false
        reason = "Invalid agentId: $agentId (unregisterPath)"
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
        agentContext?.markActivityTime(false)
          ?: logger.error { "sendHeartBeat() missing AgentContext agentId: ${request.agentId}" }
        heartBeatResponse {
          valid = agentContext != null
          if (!valid)
            reason = "Invalid agentId: ${request.agentId} (sendHeartBeat)"
        }
      }

  override fun readRequestsFromProxy(request: AgentInfo): Flow<ScrapeRequest> =
    flow {
      val agentId = request.agentId
      connectionMismatchReason(agentId, "readRequestsFromProxy")?.also { reason ->
        // Thrown before the try/finally so the mismatched agentId never reaches the cleanup branch —
        // a spoofed id must not be able to evict another agent's context.
        throw StatusException(Status.PERMISSION_DENIED.withDescription(reason))
      }
      try {
        val agentContext = proxy.agentContextManager.getAgentContext(agentId)
          ?: throw StatusException(
            Status.NOT_FOUND.withDescription(
              "No AgentContext found for agentId: $agentId",
            ),
          )
        while (proxy.isRunning && agentContext.isValid()) {
          val wrapper = agentContext.readScrapeRequest() ?: continue
          try {
            emit(wrapper.scrapeRequest)
          } catch (e: CancellationException) {
            // Cancelled after polling the wrapper out of the queue but before delivering it: fail the
            // wrapper so the waiting HTTP handler gets a prompt error instead of blocking the full
            // scrape-timeout window (finding 14).
            proxy.scrapeRequestManager.failScrapeRequest(wrapper.scrapeId, "readRequestsFromProxy cancelled")
            throw e
          }
        }
      } finally {
        // When transportFilterDisabled is true, there is no ProxyServerTransportFilter to
        // detect agent disconnect and clean up. Handle cleanup here on stream termination.
        if (proxy.options.transportFilterDisabled) {
          proxy.removeAgentContext(agentId, "Stream terminated (transport filter disabled)")
        }
      }
    }

  @Suppress("TooGenericExceptionCaught")
  override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
    runCatchingCancellable {
      requests.collect { response ->
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
          )
        }
      }
    }.onFailure { throwable ->
      if (proxy.isRunning)
        Status.fromThrowable(throwable)
          .also { arg ->
            if (arg.code != Status.Code.CANCELLED && arg.cause !is CancellationException)
              logger.error(throwable) { "Error in writeResponsesToProxy(): $arg" }
          }
    }
    return EMPTY_INSTANCE
  }

  override suspend fun writeChunkedResponsesToProxy(requests: Flow<ChunkedScrapeResponse>): Empty {
    // activeScrapeIds is a plain (non-thread-safe) set, which is safe ONLY because grpc-kotlin
    // confines a client-streaming RPC's collect{} and the post-collect cleanup to a single
    // coroutine — there is no launch/async/flowOn here that would fan the body across threads.
    // The genuinely shared chunkedContextMap is a ConcurrentHashMap. If this RPC is ever
    // refactored to process chunks concurrently, switch this to a thread-safe/synchronized set.
    val activeScrapeIds = mutableSetOf<Long>()
    runCatchingCancellable {
      requests.collect { response ->
        val contextManager = proxy.agentContextManager
        when (response.chunkOneOfCase) {
          ChunkOneOfCase.HEADER -> {
            val scrapeId = response.header.headerScrapeId
            if (proxy.scrapeRequestManager.containsScrapeRequest(scrapeId)) {
              logger.debug { "Reading header for scrapeId: $scrapeId" }
              val maxZippedSize = proxy.proxyConfigVals.internal.maxZippedContentSizeMBytes * 1024L * 1024L
              contextManager.putChunkedContext(scrapeId, ChunkedContext(response, maxZippedSize))
              activeScrapeIds += scrapeId
            } else {
              logger.warn { "Received chunked header for unknown scrapeId: $scrapeId" }
            }
          }

          ChunkOneOfCase.CHUNK -> {
            // with(...) rather than apply { }: this block consumes the chunk, it doesn't configure it (finding 36).
            with(response.chunk) {
              logger.debug { "Reading chunk $chunkCount for scrapeId: $chunkScrapeId" }
                val context = contextManager.getChunkedContext(chunkScrapeId)
                if (context == null) {
                  logger.warn { "Missing chunked context for chunk with scrapeId: $chunkScrapeId, skipping" }
                } else {
                  try {
                    context.applyChunk(chunkBytes.toByteArray(), chunkByteCount, chunkCount, chunkChecksum)
                  } catch (e: ChunkValidationException) {
                    logger.error(e) { "Chunk validation failed for scrapeId: $chunkScrapeId, discarding context" }
                    contextManager.removeChunkedContext(chunkScrapeId)
                    activeScrapeIds -= chunkScrapeId
                    proxy.scrapeRequestManager.failScrapeRequest(
                      chunkScrapeId,
                      "Chunk validation failed: ${e.message}",
                    )
                    proxy.metrics { chunkValidationFailures.labels(ProxyMetrics.STAGE_CHUNK).inc() }
                  }
                }
              }
          }

          ChunkOneOfCase.SUMMARY -> {
            // with(...) rather than apply { }: this block consumes the summary (finding 36).
            with(response.summary) {
              val context = contextManager.removeChunkedContext(summaryScrapeId)
                activeScrapeIds -= summaryScrapeId
                if (context == null) {
                  logger.warn { "Missing chunked context for summary with scrapeId: $summaryScrapeId, skipping" }
                } else {
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
                    )
                    proxy.metrics { chunkValidationFailures.labels(ProxyMetrics.STAGE_SUMMARY).inc() }
                  }
                }
              }
          }

          ChunkOneOfCase.CHUNKONEOF_NOT_SET, null -> {
            logger.warn { "Received chunked response with no field set, skipping" }
          }
        }
      }
    }.onFailure { throwable ->
      if (proxy.isRunning)
        Status.fromThrowable(throwable)
          .also { arg ->
            if (arg.code != Status.Code.CANCELLED && arg.cause !is CancellationException)
              logger.error(throwable) { "Error in writeChunkedResponsesToProxy(): $arg" }
          }
    }

    // Clean up any in-progress chunked contexts that were not completed with a summary
    // (e.g., due to stream cancellation or agent disconnect mid-transfer).
    //
    // This sweep can race Proxy.removeAgentContext() (from the transport-terminated or cleanup-service
    // thread) for the same scrapeId. Double-handling is prevented by two invariants that future edits
    // must preserve: (a) contextManager.removeChunkedContext() is a ConcurrentHashMap.remove(), so only
    // one caller gets the non-null context and the ?.also block (warn + failScrapeRequest + metric)
    // runs at most once; and (b) failScrapeRequest() -> ScrapeRequestWrapper.complete() is idempotent
    // via an AtomicBoolean compareAndSet, so even a double-fail has no observable effect.
    if (activeScrapeIds.isNotEmpty()) {
      val contextManager = proxy.agentContextManager
      activeScrapeIds.forEach { scrapeId ->
        contextManager.removeChunkedContext(scrapeId)
          ?.also {
            logger.warn { "Cleaned up orphaned ChunkedContext for scrapeId: $scrapeId" }
            proxy.scrapeRequestManager.failScrapeRequest(
              scrapeId,
              "Chunked transfer abandoned: stream terminated before summary received",
            )
            proxy.metrics { chunkedTransfersAbandoned.inc() }
          }
      }
    }

    return EMPTY_INSTANCE
  }

  companion object {
    private val logger = logger {}
    private val PATH_ID_GENERATOR = AtomicLong(0L)
    internal const val UNKNOWN_ADDRESS = "Unknown"
  }
}
