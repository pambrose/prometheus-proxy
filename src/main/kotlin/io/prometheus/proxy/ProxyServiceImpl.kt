/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import com.github.pambrose.common.dsl.GrpcDsl.streamObserver
import com.google.common.collect.Maps.newConcurrentMap
import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.prometheus.Proxy
import io.prometheus.common.GrpcObjects.newHeartBeatResponse
import io.prometheus.common.GrpcObjects.newPathMapSizeResponse
import io.prometheus.common.GrpcObjects.newRegisterAgentResponse
import io.prometheus.common.GrpcObjects.newRegisterPathResponse
import io.prometheus.common.GrpcObjects.newUnregisterPathResponseBuilder
import io.prometheus.grpc.AgentInfo
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.HeartBeatResponse
import io.prometheus.grpc.NonChunkedScrapeResponse
import io.prometheus.grpc.PathMapSizeRequest
import io.prometheus.grpc.PathMapSizeResponse
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.RegisterAgentResponse
import io.prometheus.grpc.RegisterPathRequest
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.UnregisterPathRequest
import io.prometheus.grpc.UnregisterPathResponse
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong

class ProxyServiceImpl(private val proxy: Proxy) : ProxyServiceGrpc.ProxyServiceImplBase() {

  override fun connectAgent(request: Empty, responseObserver: StreamObserver<Empty>) {
    if (proxy.isMetricsEnabled)
      proxy.metrics.connects.inc()

    responseObserver.apply {
      onNext(Empty.getDefaultInstance())
      onCompleted()
    }
  }

  override fun registerAgent(request: RegisterAgentRequest,
                             responseObserver: StreamObserver<RegisterAgentResponse>) {
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

    responseObserver.apply {
      onNext(newRegisterAgentResponse(valid, "Invalid agentId: $agentId", agentId))
      onCompleted()
    }
  }

  override fun registerPath(request: RegisterPathRequest,
                            responseObserver: StreamObserver<RegisterPathResponse>) {
    val path = request.path
    if (path in proxy.pathManager)
      logger.info { "Overwriting path /$path" }

    val agentId = request.agentId
    var valid = false

    proxy.agentContextManager.getAgentContext(agentId)
        ?.apply {
          valid = true
          proxy.pathManager.addPath(path, this)
          markActivityTime(false)
        } ?: logger.error { "Missing AgentContext for agentId: $agentId" }

    responseObserver.apply {
      onNext(
          newRegisterPathResponse(valid,
                                  "Invalid agentId: $agentId",
                                  proxy.pathManager.pathMapSize,
                                  if (valid) PATH_ID_GENERATOR.getAndIncrement() else -1)
      )
      onCompleted()
    }
  }

  override fun unregisterPath(request: UnregisterPathRequest,
                              responseObserver: StreamObserver<UnregisterPathResponse>) {
    val agentId = request.agentId
    val agentContext = proxy.agentContextManager.getAgentContext(agentId)
    val responseBuilder = newUnregisterPathResponseBuilder()

    if (agentContext == null) {
      logger.error { "Missing AgentContext for agentId: $agentId" }
      responseBuilder
          .apply {
            this.valid = false
            this.reason = "Invalid agentId: $agentId"
          }
    }
    else {
      proxy.pathManager.removePath(request.path, agentId, responseBuilder)
      agentContext.markActivityTime(false)
    }

    responseObserver.apply {
      onNext(responseBuilder.build())
      onCompleted()
    }
  }

  override fun pathMapSize(request: PathMapSizeRequest, responseObserver: StreamObserver<PathMapSizeResponse>) {
    responseObserver.apply {
      onNext(newPathMapSizeResponse(proxy.pathManager.pathMapSize))
      onCompleted()
    }
  }

  override fun sendHeartBeat(request: HeartBeatRequest, responseObserver: StreamObserver<HeartBeatResponse>) {
    if (proxy.isMetricsEnabled)
      proxy.metrics.heartbeats.inc()
    val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
    agentContext?.markActivityTime(false)
        ?: logger.info { "sendHeartBeat() missing AgentContext agentId: ${request.agentId}" }
    responseObserver.apply {
      onNext(newHeartBeatResponse(agentContext != null, "Invalid agentId: ${request.agentId}"))
      onCompleted()
    }
  }

  override fun readRequestsFromProxy(agentInfo: AgentInfo, responseObserver: StreamObserver<ScrapeRequest>) {
    responseObserver.also { observer ->
      proxy.agentContextManager.getAgentContext(agentInfo.agentId)
          ?.also { agentContext ->
            runBlocking {
              while (proxy.isRunning && agentContext.isValid())
                agentContext.readScrapeRequest()
                    ?.apply {
                      observer.onNext(scrapeRequest)
                    }
            }
          }
      observer.onCompleted()
    }
  }

  override fun writeNonChunkedResponsesToProxy(responseObserver: StreamObserver<Empty>): StreamObserver<NonChunkedScrapeResponse> =
      streamObserver {
        onNext { response ->
          proxy.scrapeRequestManager.getFromScrapeRequestMap(response.scrapeId)
              ?.apply {
                scrapeResponse = response
                markComplete()
                agentContext.markActivityTime(true)
              } ?: logger.error { "Missing ScrapeRequestWrapper for scrape_id: ${response.scrapeId}" }
        }

        onError { throwable ->
          Status.fromThrowable(throwable)
              .also { arg ->
                if (arg.code != Status.Code.CANCELLED)
                  logger.error(throwable) { "Error in writeNonChunkedResponsesToProxy(): $arg" }
              }

          try {
            responseObserver.apply {
              onNext(Empty.getDefaultInstance())
              onCompleted()
            }
          } catch (e: StatusRuntimeException) {
            // logger.warn(e) {"StatusRuntimeException"};
            // Ignore
          }
        }

        onCompleted {
          responseObserver.apply {
            onNext(Empty.getDefaultInstance())
            onCompleted()
          }
        }
      }

  override fun writeChunkedResponsesToProxy(responseObserver: StreamObserver<Empty>): StreamObserver<ChunkedScrapeResponse> =
      streamObserver {

        val chunkedContextMap: ConcurrentMap<Long, ChunkedContext> = newConcurrentMap()

        onNext { response ->
          val ooc = response.testOneofCase
          when (ooc.name.toLowerCase()) {
            "header" -> {
              val scrapeId = response.header.headerScrapeId
              logger.debug { "Reading header for scrapeId: $scrapeId}" }
              chunkedContextMap[scrapeId] = ChunkedContext(response)
            }
            "chunk" -> {
              response.chunk.apply {
                logger.debug { "Reading chunk $chunkCount for scrapeId: $chunkScrapeId" }
                val context = chunkedContextMap[chunkScrapeId]
                check(context != null) { "Missing chunked context with scrapeId: $chunkScrapeId" }
                context.addChunk(chunkBytes.toByteArray(), chunkByteCount, chunkCount, chunkChecksum)
              }
            }
            "summary" -> {
              response.summary.apply {
                val context = chunkedContextMap.remove(summaryScrapeId)
                check(context != null) { "Missing chunked context with scrapeId: $summaryScrapeId" }

                logger.debug { "Reading summary chunkCount: ${context.totalChunkCount} byteCount: ${context.totalByteCount} for scrapeId: ${response.summary.summaryScrapeId}" }

                val nonChunkedResponse = context.summary(summaryChunkCount, summaryByteCount, summaryChecksum)
                val scrapeId = nonChunkedResponse.scrapeId
                proxy.scrapeRequestManager.getFromScrapeRequestMap(scrapeId)
                    ?.apply {
                      scrapeResponse = nonChunkedResponse
                      markComplete()
                      agentContext.markActivityTime(true)
                    } ?: logger.error { "Missing ScrapeRequestWrapper for scrape_id: $scrapeId" }
              }
            }
            else -> throw IllegalStateException("Invalid field name in writeChunkedResponsesToProxy()")
          }
        }

        onError { throwable ->
          Status.fromThrowable(throwable)
              .also { arg ->
                if (arg.code != Status.Code.CANCELLED)
                  logger.error(throwable) { "Error in writeChunkedResponsesToProxy(): $arg" }
              }

          try {
            responseObserver.apply {
              onNext(Empty.getDefaultInstance())
              onCompleted()
            }
          } catch (e: StatusRuntimeException) {
            // logger.warn(e) {"StatusRuntimeException"};
            // Ignore
          }
        }

        onCompleted {
          responseObserver.apply {
            onNext(Empty.getDefaultInstance())
            onCompleted()
          }
        }
      }

  companion object : KLogging() {
    private val PATH_ID_GENERATOR = AtomicLong(0)
  }
}
