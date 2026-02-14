/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.proxy

import com.github.pambrose.common.util.runCatchingCancellable
import com.google.protobuf.Empty
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.Status
import io.prometheus.Proxy
import io.prometheus.agent.RequestFailureException
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.CancellationException
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.fetchAndIncrement

internal class ProxyServiceImpl(
  private val proxy: Proxy,
) : ProxyServiceGrpcKt.ProxyServiceCoroutineImplBase() {
  override suspend fun connectAgent(request: Empty): Empty {
    if (proxy.options.transportFilterDisabled) {
      val msg = "Agent (false) and Proxy (true) do not have matching transportFilterDisabled config values"
      logger.error { msg }
      throw RequestFailureException(msg)
    }

    proxy.metrics { connectCount.inc() }
    return EMPTY_INSTANCE
  }

  override suspend fun connectAgentWithTransportFilterDisabled(request: Empty): AgentInfo {
    if (!proxy.options.transportFilterDisabled) {
      val msg = "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
      logger.error { msg }
      throw RequestFailureException(msg)
    }

    proxy.metrics { connectCount.inc() }
    val agentContext = AgentContext(UNKNOWN_ADDRESS)
    return agentInfo {
      agentId = agentContext.agentId
    }.also {
      proxy.agentContextManager.addAgentContext(agentContext)
    }
  }

  override suspend fun registerAgent(request: RegisterAgentRequest): RegisterAgentResponse {
    var isValid = false

    proxy.agentContextManager.getAgentContext(request.agentId)
      ?.apply {
        isValid = true
        assignProperties(request)
        markActivityTime(false)
        logger.info { "Connected to $this" }
      } ?: logger.error { "registerAgent() missing AgentContext agentId: ${request.agentId}" }

    return registerAgentResponse {
      valid = isValid
      agentId = request.agentId
      if (!isValid) {
        reason = "Invalid agentId: ${request.agentId} (registerAgent)"
      }
    }
  }

  override suspend fun registerPath(request: RegisterPathRequest): RegisterPathResponse {
    var isValid = false
    var failureReason = ""

    proxy.agentContextManager.getAgentContext(request.agentId)
      ?.apply {
        val addPathResult = proxy.pathManager.addPath(request.path, request.labels, this)
        isValid = addPathResult == null
        if (!isValid) failureReason = addPathResult!!
        markActivityTime(false)
      } ?: run {
      failureReason = "Invalid agentId: ${request.agentId} (registerPath)"
      logger.error { "Missing AgentContext for agentId: ${request.agentId}" }
    }

    return registerPathResponse {
      pathId = if (isValid) PATH_ID_GENERATOR.fetchAndIncrement() else -1
      valid = isValid
      if (!isValid) {
        reason = failureReason
      }
      pathCount = proxy.pathManager.pathMapSize
    }
  }

  override suspend fun unregisterPath(request: UnregisterPathRequest): UnregisterPathResponse {
    val agentId = request.agentId
    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    return if (agentContext == null) {
      logger.error { "Missing AgentContext for agentId: $agentId" }
      unregisterPathResponse {
        valid = false
        reason = "Invalid agentId: $agentId (unregisterPath)"
      }
    } else {
      proxy.pathManager.removePath(request.path, agentId).apply { agentContext.markActivityTime(false) }
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
      try {
        proxy.agentContextManager.getAgentContext(agentId)
          ?.also { agentContext ->
            while (proxy.isRunning && agentContext.isValid()) {
              agentContext.readScrapeRequest()?.apply { emit(scrapeRequest) }
            }
          }
          ?: logger.warn { "readRequestsFromProxy(): No AgentContext found for agentId: $agentId" }
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
    val activeScrapeIds = mutableSetOf<Long>()
    runCatchingCancellable {
      requests.collect { response ->
        val contextManager = proxy.agentContextManager
        when (response.chunkOneOfCase) {
          ChunkOneOfCase.HEADER -> {
            val scrapeId = response.header.headerScrapeId
            if (proxy.scrapeRequestManager.containsScrapeRequest(scrapeId)) {
              logger.debug { "Reading header for scrapeId: $scrapeId" }
              val maxZippedSize = proxy.proxyConfigVals.internal.maxZippedContentSizeMBytes * 1024 * 1024
              contextManager.putChunkedContext(scrapeId, ChunkedContext(response, maxZippedSize))
              activeScrapeIds += scrapeId
            } else {
              logger.warn { "Received chunked header for unknown scrapeId: $scrapeId" }
            }
          }

          ChunkOneOfCase.CHUNK -> {
            response.chunk
              .apply {
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
                  }
                }
              }
          }

          ChunkOneOfCase.SUMMARY -> {
            response.summary
              .apply {
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
    // (e.g., due to stream cancellation or agent disconnect mid-transfer)
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
