/*
 * Copyright © 2023 Paul Ambrose (pambrose@mac.com)
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
import io.grpc.Status
import io.prometheus.Proxy
import io.prometheus.agent.RequestFailureException
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.GrpcObjects.toScrapeResults
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
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
import io.prometheus.grpc.krotodc.HeartBeatResponse
import io.prometheus.grpc.krotodc.PathMapSizeResponse
import io.prometheus.grpc.krotodc.agentinfo.toProto
import io.prometheus.grpc.krotodc.heartbeatresponse.toProto
import io.prometheus.grpc.krotodc.pathmapsizeresponse.toProto
import io.prometheus.grpc.krotodc.registeragentresponse.toProto
import io.prometheus.grpc.krotodc.registerpathresponse.toProto
import io.prometheus.grpc.krotodc.scraperesponse.toDataClass
import io.prometheus.grpc.krotodc.unregisterpathresponse.toProto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.two.KLogging
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

internal class ProxyServiceImpl(private val proxy: Proxy) : ProxyServiceGrpcKt.ProxyServiceCoroutineImplBase() {
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
    return io.prometheus.grpc.krotodc.AgentInfo(agentContext.agentId)
      .apply { require(agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG } }
      .toProto()
  }

  override suspend fun registerAgent(request: RegisterAgentRequest): RegisterAgentResponse {
    var valid = false

    proxy.agentContextManager.getAgentContext(request.agentId)
      ?.apply {
        valid = true
        assignProperties(request)
        markActivityTime(false)
        logger.info { "Connected to $this" }
      } ?: logger.info { "registerAgent() missing AgentContext agentId: ${request.agentId}" }

    return io.prometheus.grpc.krotodc.RegisterAgentResponse(
      valid = valid,
      reason = request.agentId,
      agentId = "Invalid agentId: ${request.agentId} (registerAgent)",
    ).apply { require(this.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG } }
      .toProto()
  }

  override suspend fun registerPath(request: RegisterPathRequest): RegisterPathResponse {
    var valid = false

    proxy.agentContextManager.getAgentContext(request.agentId)
      ?.apply {
        valid = true
        proxy.pathManager.addPath(request.path, this)
        markActivityTime(false)
      } ?: logger.error { "Missing AgentContext for agentId: ${request.agentId}" }

    return io.prometheus.grpc.krotodc.RegisterPathResponse(
      pathId = if (valid) PATH_ID_GENERATOR.getAndIncrement() else -1,
      valid = valid,
      reason = "Invalid agentId: ${request.agentId} (registerPath)",
      pathCount = proxy.pathManager.pathMapSize,
    ).toProto()
  }

  override suspend fun unregisterPath(request: UnregisterPathRequest): UnregisterPathResponse {
    val agentId = request.agentId
    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    return if (agentContext.isNull()) {
      logger.error { "Missing AgentContext for agentId: $agentId" }
      io.prometheus.grpc.krotodc.UnregisterPathResponse(
        valid = false,
        reason = "Invalid agentId: $agentId (unregisterPath)",
      )
    } else {
      proxy.pathManager.removePath(request.path, agentId).apply { agentContext.markActivityTime(false) }
    }.toProto()
  }

  override suspend fun pathMapSize(request: PathMapSizeRequest) =
    PathMapSizeResponse(proxy.pathManager.pathMapSize).toProto()

  override suspend fun sendHeartBeat(request: HeartBeatRequest) =
    proxy.agentContextManager.getAgentContext(request.agentId)
      .let { agentContext ->
        proxy.metrics { heartbeatCount.inc() }
        agentContext?.markActivityTime(false)
          ?: logger.info { "sendHeartBeat() missing AgentContext agentId: ${request.agentId}" }
        HeartBeatResponse(
          valid = agentContext.isNotNull(),
          reason = "Invalid agentId: ${request.agentId} (sendHeartBeat)",
        ).toProto()
      }

  override fun readRequestsFromProxy(request: AgentInfo): Flow<ScrapeRequest> =
    flow {
      proxy.agentContextManager.getAgentContext(request.agentId)
        ?.also { agentContext ->
          while (proxy.isRunning && agentContext.isValid())
            agentContext.readScrapeRequest()?.apply { emit(scrapeRequest) }
        }
    }

  override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
    runCatching {
      requests.collect { response ->
        val scrapeResults = response.toDataClass().toScrapeResults()
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
    runCatching {
      requests.collect { response ->
        val ooc = response.chunkOneOfCase
        val chunkedContextMap = proxy.agentContextManager.chunkedContextMap
        when (ooc.name.lowercase(Locale.getDefault())) {
          "header" -> {
            val scrapeId = response.header.headerScrapeId
            logger.debug { "Reading header for scrapeId: $scrapeId}" }
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

          else -> throw IllegalStateException("Invalid field name in writeChunkedResponsesToProxy()")
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

  companion object : KLogging() {
    private val PATH_ID_GENERATOR = AtomicLong(0L)
    internal const val UNKNOWN_ADDRESS = "Unknown"
  }
}
