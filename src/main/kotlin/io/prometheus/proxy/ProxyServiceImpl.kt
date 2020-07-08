/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import com.google.protobuf.Empty
import io.grpc.Status
import io.prometheus.Proxy
import io.prometheus.common.GrpcObjects.newHeartBeatResponse
import io.prometheus.common.GrpcObjects.newPathMapSizeResponse
import io.prometheus.common.GrpcObjects.newRegisterAgentResponse
import io.prometheus.common.GrpcObjects.newRegisterPathResponse
import io.prometheus.common.GrpcObjects.toScrapeResults
import io.prometheus.common.GrpcObjects.unregisterPathResponse
import io.prometheus.grpc.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import mu.KLogging
import java.util.concurrent.atomic.AtomicLong

internal class ProxyServiceImpl(private val proxy: Proxy) : ProxyServiceGrpcKt.ProxyServiceCoroutineImplBase() {

  override suspend fun connectAgent(request: Empty): Empty {
    proxy.metrics { connectCount.inc() }
    return Empty.getDefaultInstance()
  }

  override suspend fun registerAgent(request: RegisterAgentRequest): RegisterAgentResponse {
    val agentId = request.agentId
    var valid = false
    proxy.agentContextManager.getAgentContext(agentId)
      ?.apply {
        valid = true
        agentName = request.agentName
        hostName = request.hostName
        markActivityTime(false)
        logger.info { "Connected to $this" }
      } ?: logger.info { "registerAgent() missing AgentContext agentId: $agentId" }

    return newRegisterAgentResponse(valid, "Invalid agentId: $agentId", agentId)
  }

  override suspend fun registerPath(request: RegisterPathRequest): RegisterPathResponse {
    val path = request.path
    if (path in proxy.pathManager)
      logger.info { "Overwriting path /$path" }

    val agentId = request.agentId
    var valid = false

    proxy.agentContextManager.getAgentContext(agentId)?.apply {
      valid = true
      proxy.pathManager.addPath(path, this)
      markActivityTime(false)
    } ?: logger.error { "Missing AgentContext for agentId: $agentId" }

    return newRegisterPathResponse(valid,
                                   "Invalid agentId: $agentId",
                                   proxy.pathManager.pathMapSize,
                                   if (valid) PATH_ID_GENERATOR.getAndIncrement() else -1)
  }

  override suspend fun unregisterPath(request: UnregisterPathRequest): UnregisterPathResponse {
    val agentId = request.agentId
    val agentContext = proxy.agentContextManager.getAgentContext(agentId)

    return if (agentContext == null) {
      logger.error { "Missing AgentContext for agentId: $agentId" }
      unregisterPathResponse {
        valid = false
        reason = "Invalid agentId: $agentId"
      }
    }
    else {
      proxy.pathManager.removePath(request.path, agentId).apply { agentContext.markActivityTime(false) }
    }
  }

  override suspend fun pathMapSize(request: PathMapSizeRequest) =
    newPathMapSizeResponse(proxy.pathManager.pathMapSize)

  override suspend fun sendHeartBeat(request: HeartBeatRequest): HeartBeatResponse {
    proxy.metrics { heartbeatCount.inc() }
    val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
    agentContext?.markActivityTime(false)
    ?: logger.info { "sendHeartBeat() missing AgentContext agentId: ${request.agentId}" }
    return newHeartBeatResponse(agentContext != null, "Invalid agentId: ${request.agentId}")
  }

  override fun readRequestsFromProxy(request: AgentInfo): Flow<ScrapeRequest> {
    return flow {
      proxy.agentContextManager.getAgentContext(request.agentId)
        ?.also { agentContext ->
          while (proxy.isRunning && agentContext.isValid())
            agentContext.readScrapeRequest()?.apply { emit(scrapeRequest) }
        }
    }
  }

  override suspend fun writeResponsesToProxy(requests: Flow<ScrapeResponse>): Empty {
    try {
      requests.collect { response ->
        val scrapeResults = response.toScrapeResults()
        proxy.scrapeRequestManager.assignScrapeResults(scrapeResults)
      }
    }
    catch (throwable: Throwable) {
      if (proxy.isRunning)
        Status.fromThrowable(throwable)
          .also { arg ->
            if (arg.code != Status.Code.CANCELLED)
              logger.error(throwable) { "Error in writeResponsesToProxy(): $arg" }
          }
    }
    return Empty.getDefaultInstance()
  }

  override suspend fun writeChunkedResponsesToProxy(requests: Flow<ChunkedScrapeResponse>): Empty {
    try {
      requests.collect { response ->
        val ooc = response.chunkOneOfCase
        val chunkedContextMap = proxy.agentContextManager.chunkedContextMap
        when (ooc.name.toLowerCase()) {
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
                check(context != null) { "Missing chunked context with scrapeId: $chunkScrapeId" }
                context.applyChunk(chunkBytes.toByteArray(), chunkByteCount, chunkCount, chunkChecksum)
              }
          }
          "summary" -> {
            response.summary
              .apply {
                val context = chunkedContextMap.remove(summaryScrapeId)
                check(context != null) { "Missing chunked context with scrapeId: $summaryScrapeId" }
                logger.debug { "Reading summary chunkCount: ${context.totalChunkCount} byteCount: ${context.totalByteCount} for scrapeId: $summaryScrapeId" }
                context.applySummary(summaryChunkCount, summaryByteCount, summaryChecksum)
                proxy.scrapeRequestManager.assignScrapeResults(context.scrapeResults)
              }
          }
          else -> throw IllegalStateException("Invalid field name in writeChunkedResponsesToProxy()")
        }
      }
    }
    catch (throwable: Throwable) {
      if (proxy.isRunning)
        Status.fromThrowable(throwable)
          .also { arg ->
            if (arg.code != Status.Code.CANCELLED)
              logger.error(throwable) { "Error in writeChunkedResponsesToProxy(): $arg" }
          }
    }
    return Empty.getDefaultInstance()
  }

  companion object : KLogging() {
    private val PATH_ID_GENERATOR = AtomicLong(0)
  }
}