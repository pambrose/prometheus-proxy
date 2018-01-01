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
import io.prometheus.grpc.*
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

internal class ProxyServiceImpl(private val proxy: Proxy) : ProxyServiceGrpc.ProxyServiceImplBase() {

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
        val agentContext = proxy.getAgentContext(agentId)
        agentContext?.apply {
            agentName = request.agentName
            hostName = request.hostName
            markActivity()
        } ?: logger.info("registerAgent() missing AgentContext agentId: $agentId")

        responseObserver.apply {
            val response =
                    with(RegisterAgentResponse.newBuilder()) {
                        valid = agentContext != null
                        reason = "Invalid agentId: $agentId"
                        this.agentId = agentId
                        build()
                    }
            onNext(response)
            onCompleted()
        }
    }

    override fun registerPath(request: RegisterPathRequest,
                              responseObserver: StreamObserver<RegisterPathResponse>) {
        val path = request.path
        if (proxy.containsPath(path))
            logger.info("Overwriting path /$path")

        val agentId = request.agentId
        val agentContext = proxy.getAgentContext(agentId)
        val response =
                with(RegisterPathResponse.newBuilder()) {
                    valid = agentContext != null
                    reason = "Invalid agentId: $agentId"
                    pathCount = proxy.pathMapSize()
                    pathId = if (agentContext != null) PATH_ID_GENERATOR.getAndIncrement() else -1
                    build()
                }

        agentContext?.apply {
            proxy.addPath(path, this)
            markActivity()

        } ?: logger.error("Missing AgentContext for agentId: $agentId")

        responseObserver.apply {
            onNext(response)
            onCompleted()
        }
    }

    override fun unregisterPath(request: UnregisterPathRequest,
                                responseObserver: StreamObserver<UnregisterPathResponse>) {
        val path = request.path
        val agentId = request.agentId
        val agentContext = proxy.getAgentContext(agentId)

        val responseBuilder = UnregisterPathResponse.newBuilder()

        if (agentContext == null) {
            logger.error("Missing AgentContext for agentId: $agentId")
            responseBuilder.apply {
                valid = false
                reason = "Invalid agentId: $agentId"
            }
        }
        else {
            proxy.removePath(path, agentId, responseBuilder)
            agentContext.markActivity()
        }

        responseObserver.apply {
            onNext(responseBuilder.build())
            onCompleted()
        }
    }

    override fun pathMapSize(request: PathMapSizeRequest, responseObserver: StreamObserver<PathMapSizeResponse>) {
        responseObserver.apply {
            val response = with(PathMapSizeResponse.newBuilder()) {
                pathCount = proxy.pathMapSize()
                build()
            }
            onNext(response)
            onCompleted()
        }
    }

    override fun sendHeartBeat(request: HeartBeatRequest, responseObserver: StreamObserver<HeartBeatResponse>) {
        if (proxy.isZipkinEnabled)
            proxy.metrics.heartbeats.inc()
        val agentContext = proxy.getAgentContext(request.agentId)
        agentContext?.markActivity() ?: logger.info("sendHeartBeat() missing AgentContext agentId: ${request.agentId}")
        responseObserver.apply {
            val response =
                    with(HeartBeatResponse.newBuilder()) {
                        valid = agentContext != null
                        reason = "Invalid agentId: ${request.agentId}"
                        build()
                    }
            onNext(response)
            onCompleted()
        }
    }

    override fun readRequestsFromProxy(agentInfo: AgentInfo, responseObserver: StreamObserver<ScrapeRequest>) {
        val agentId = agentInfo.agentId
        val agentContext = proxy.getAgentContext(agentId)
        if (agentContext != null) {
            while (proxy.isRunning && agentContext.valid) {
                agentContext.pollScrapeRequestQueue()?.apply {
                    responseObserver.onNext(scrapeRequest)
                }
            }
        }
        responseObserver.onCompleted()
    }

    override fun writeResponsesToProxy(responseObserver: StreamObserver<Empty>): StreamObserver<ScrapeResponse> {
        return object : StreamObserver<ScrapeResponse> {
            override fun onNext(response: ScrapeResponse) {
                proxy.getFromScrapeRequestMap(response.scrapeId)?.apply {
                    scrapeResponse = response
                    markComplete()
                    agentContext.markActivity()
                } ?: logger.error("Missing ScrapeRequestWrapper for scrape_id: ${response.scrapeId}")
            }

            override fun onError(t: Throwable) {
                val status = Status.fromThrowable(t)
                if (status !== Status.CANCELLED)
                    logger.info("Error in writeResponsesToProxy(): $status")
                try {
                    responseObserver.apply {
                        onNext(Empty.getDefaultInstance())
                        onCompleted()
                    }
                } catch (e: StatusRuntimeException) {
                    // logger.warn("StatusRuntimeException", e);
                    // Ignore
                }

            }

            override fun onCompleted() {
                responseObserver.apply {
                    onNext(Empty.getDefaultInstance())
                    onCompleted()
                }
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ProxyServiceImpl::class.java)
        private val PATH_ID_GENERATOR = AtomicLong(0)
    }
}
