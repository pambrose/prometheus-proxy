/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.proxy

import com.google.protobuf.Empty
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.prometheus.Proxy
import io.prometheus.dsl.GrpcDsl.streamObserver
import io.prometheus.grpc.*
import mu.KLogging
import java.util.concurrent.atomic.AtomicLong

internal class ProxyServiceImpl(private val proxy: Proxy) : ProxyServiceGrpc.ProxyServiceImplBase() {

    override fun connectAgent(request: Empty, responseObserver: StreamObserver<Empty>) {
        if (proxy.isMetricsEnabled)
            proxy.metrics.connects.inc()
        responseObserver
                .apply {
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
                    markActivity()
                } ?: logger.info { "registerAgent() missing AgentContext agentId: $agentId" }

        responseObserver
                .apply {
                    onNext(
                            RegisterAgentResponse.newBuilder()
                                    .run {
                                        reason = "Invalid agentId: $agentId"
                                        this.valid = valid
                                        this.agentId = agentId
                                        build()
                                    })
                    onCompleted()
                }
    }

    override fun registerPath(request: RegisterPathRequest,
                              responseObserver: StreamObserver<RegisterPathResponse>) {
        val path = request.path
        if (proxy.pathManager.containsPath(path))
            logger.info { "Overwriting path /$path" }

        val agentId = request.agentId
        var valid = false

        proxy.agentContextManager.getAgentContext(agentId)
                ?.apply {
                    valid = true
                    proxy.pathManager.addPath(path, this)
                    markActivity()
                } ?: logger.error { "Missing AgentContext for agentId: $agentId" }

        responseObserver
                .apply {
                    onNext(
                            RegisterPathResponse.newBuilder()
                                    .run {
                                        this.valid = valid
                                        reason = "Invalid agentId: $agentId"
                                        pathCount = proxy.pathManager.pathMapSize()
                                        pathId = if (valid) PATH_ID_GENERATOR.getAndIncrement() else -1
                                        build()
                                    })
                    onCompleted()
                }
    }

    override fun unregisterPath(request: UnregisterPathRequest,
                                responseObserver: StreamObserver<UnregisterPathResponse>) {
        val path = request.path
        val agentId = request.agentId
        val agentContext = proxy.agentContextManager.getAgentContext(agentId)
        val responseBuilder = UnregisterPathResponse.newBuilder()

        if (agentContext == null) {
            logger.error { "Missing AgentContext for agentId: $agentId" }
            responseBuilder
                    .apply {
                        valid = false
                        reason = "Invalid agentId: $agentId"
                    }
        }
        else {
            proxy.pathManager.removePath(path, agentId, responseBuilder)
            agentContext.markActivity()
        }

        responseObserver
                .apply {
                    onNext(responseBuilder.build())
                    onCompleted()
                }
    }

    override fun pathMapSize(request: PathMapSizeRequest, responseObserver: StreamObserver<PathMapSizeResponse>) {
        responseObserver
                .apply {
                    onNext(
                            PathMapSizeResponse.newBuilder()
                                    .run {
                                        pathCount = proxy.pathManager.pathMapSize()
                                        build()
                                    })
                    onCompleted()
                }
    }

    override fun sendHeartBeat(request: HeartBeatRequest, responseObserver: StreamObserver<HeartBeatResponse>) {
        if (proxy.isZipkinEnabled)
            proxy.metrics.heartbeats.inc()
        val agentContext = proxy.agentContextManager.getAgentContext(request.agentId)
        agentContext?.markActivity() ?: logger.info { "sendHeartBeat() missing AgentContext agentId: ${request.agentId}" }
        responseObserver
                .apply {
                    onNext(
                            HeartBeatResponse.newBuilder()
                                    .run {
                                        valid = agentContext != null
                                        reason = "Invalid agentId: ${request.agentId}"
                                        build()
                                    })
                    onCompleted()
                }
    }

    override fun readRequestsFromProxy(agentInfo: AgentInfo, responseObserver: StreamObserver<ScrapeRequest>) {
        responseObserver
                .run {
                    proxy.agentContextManager.getAgentContext(agentInfo.agentId)
                            ?.let {
                                while (proxy.isRunning && it.isValid.get())
                                    it.pollScrapeRequestQueue()?.apply { onNext(scrapeRequest) }
                            }
                    onCompleted()
                }
    }

    override fun writeResponsesToProxy(responseObserver: StreamObserver<Empty>): StreamObserver<ScrapeResponse> {
        return streamObserver {
            onNext { response ->
                proxy.scrapeRequestManager.getFromScrapeRequestMap(response.scrapeId)
                        ?.apply {
                            scrapeResponse = response
                            markComplete()
                            agentContext.markActivity()
                        } ?: logger.error { "Missing ScrapeRequestWrapper for scrape_id: ${response.scrapeId}" }
            }

            onError { t ->
                Status.fromThrowable(t)
                        .let {
                            if (it !== Status.CANCELLED)
                                logger.info { "Error in writeResponsesToProxy(): $it" }
                        }

                try {
                    responseObserver
                            .apply {
                                onNext(Empty.getDefaultInstance())
                                onCompleted()
                            }
                } catch (e: StatusRuntimeException) {
                    // logger.warn(e) {"StatusRuntimeException"};
                    // Ignore
                }
            }

            onCompleted {
                responseObserver
                        .apply {
                            onNext(Empty.getDefaultInstance())
                            onCompleted()
                        }
            }
        }
    }

    companion object : KLogging() {
        private val PATH_ID_GENERATOR = AtomicLong(0)
    }
}
