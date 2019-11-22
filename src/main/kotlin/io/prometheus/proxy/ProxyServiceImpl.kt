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
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.HeartBeatResponse
import io.prometheus.grpc.PathMapSizeRequest
import io.prometheus.grpc.PathMapSizeResponse
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.RegisterAgentResponse
import io.prometheus.grpc.RegisterPathRequest
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.UnregisterPathRequest
import io.prometheus.grpc.UnregisterPathResponse
import kotlinx.coroutines.runBlocking
import mu.KLogging
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
    } else {
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

  override fun writeResponsesToProxy(responseObserver: StreamObserver<Empty>): StreamObserver<ScrapeResponse> =
    streamObserver {
      onNext { resp ->
        proxy.scrapeRequestManager.getFromScrapeRequestMap(resp.scrapeId)
          ?.apply {
            scrapeResponse = resp
            markComplete()
            agentContext.markActivityTime(true)
          } ?: logger.error { "Missing ScrapeRequestWrapper for scrape_id: ${resp.scrapeId}" }
      }

      onError { throwable ->
        Status.fromThrowable(throwable)
          .also { arg ->
            if (arg !== Status.CANCELLED)
              logger.info { "Error in writeResponsesToProxy(): $arg" }
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
