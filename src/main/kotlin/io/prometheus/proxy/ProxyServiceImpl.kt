/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.isNull
import com.google.protobuf.Empty
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.Status
import io.prometheus.Proxy
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.ScrapeResults.Companion.toScrapeResults
import io.prometheus.common.Utils.runCatchingCancellable
import io.prometheus.common.Utils.toLowercase
import io.prometheus.grpc.AgentInfo
import io.prometheus.grpc.ChunkedScrapeResponse
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
      "Agent (false) and Proxy (true) do not have matching transportFilterDisabled config values".also { msg ->
        logger.error { msg }
        throw RequestFailureException(msg)
      }
    }

    proxy.metrics { connectCount.inc() }
    return EMPTY_INSTANCE
  }

  override suspend fun connectAgentWithTransportFilterDisabled(request: Empty): AgentInfo {
    if (!proxy.options.transportFilterDisabled) {
      "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
        .also { msg ->
          logger.error { msg }
          throw RequestFailureException(msg)
        }
    }

    proxy.metrics { connectCount.inc() }
    val agentContext = AgentContext(UNKNOWN_ADDRESS)
    proxy.agentContextManager.addAgentContext(agentContext)
    return agentInfo {
      require(agentContext.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
      agentId = agentContext.agentId
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

    proxy.agentContextManager.getAgentContext(request.agentId)
      ?.apply {
        isValid = true
        proxy.pathManager.addPath(request.path, request.labels, this)
        markActivityTime(false)
      } ?: logger.error { "Missing AgentContext for agentId: ${request.agentId}" }

    return registerPathResponse {
      pathId = if (isValid) PATH_ID_GENERATOR.fetchAndIncrement() else -1
      valid = isValid
      if (!isValid) {
        reason = "Invalid agentId: ${request.agentId} (registerPath)"
      }
      pathCount = proxy.pathManager.pathMapSize
    }
  }

  override suspend fun unregisterPath(request: UnregisterPathRequest): UnregisterPathResponse {
    val agentId = request.agentId
    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    return if (agentContext.isNull()) {
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
          valid = agentContext.isNotNull()
          reason = "Invalid agentId: ${request.agentId} (sendHeartBeat)"
        }
      }

  override fun readRequestsFromProxy(request: AgentInfo): Flow<ScrapeRequest> =
    flow {
      proxy.agentContextManager.getAgentContext(request.agentId)
        ?.also { agentContext ->
          while (proxy.isRunning && agentContext.isValid()) {
            agentContext.readScrapeRequest()?.apply { emit(scrapeRequest) }
          }
        }
    }

  override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
    runCatchingCancellable {
      requests.collect { response ->
        val scrapeResults = response.toScrapeResults()
        proxy.scrapeRequestManager.assignScrapeResults(scrapeResults)
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
    runCatchingCancellable {
      requests.collect { response ->
        val ooc = response.chunkOneOfCase
        val chunkedContextMap = proxy.agentContextManager.chunkedContextMap
        when (ooc.name.toLowercase()) {
          "header" -> {
            val scrapeId = response.header.headerScrapeId
            logger.debug { "Reading header for scrapeId: $scrapeId" }
            chunkedContextMap[scrapeId] = ChunkedContext(response)
          }

          "chunk" -> {
            response.chunk
              .apply {
                logger.debug { "Reading chunk $chunkCount for scrapeId: $chunkScrapeId" }
                val context = chunkedContextMap[chunkScrapeId]
                check(context.isNotNull()) { "Missing chunked context with scrapeId: $chunkScrapeId" }
                context.applyChunk(chunkBytes.toByteArray(), chunkByteCount, chunkCount, chunkChecksum)
              }
          }

          "summary" -> {
            response.summary
              .apply {
                val context = chunkedContextMap.remove(summaryScrapeId)
                check(context.isNotNull()) { "Missing chunked context with scrapeId: $summaryScrapeId" }
                logger.debug {
                  val ccnt = context.totalChunkCount
                  val bcnt = context.totalByteCount
                  "Reading summary chunkCount: $ccnt byteCount: $bcnt for scrapeId: $summaryScrapeId"
                }
                context.applySummary(summaryChunkCount, summaryByteCount, summaryChecksum)
                proxy.scrapeRequestManager.assignScrapeResults(context.scrapeResults)
              }
          }

          else -> {
            error("Invalid field name in writeChunkedResponsesToProxy()")
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
    return EMPTY_INSTANCE
  }

  companion object {
    private val logger = logger {}
    private val PATH_ID_GENERATOR = AtomicLong(0L)
    internal const val UNKNOWN_ADDRESS = "Unknown"
  }
}
