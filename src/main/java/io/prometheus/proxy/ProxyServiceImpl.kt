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
        this.proxy.metrics?.connects?.inc()
        responseObserver.apply {
            onNext(Empty.getDefaultInstance())
            onCompleted()
        }
    }

    override fun registerAgent(request: RegisterAgentRequest,
                               responseObserver: StreamObserver<RegisterAgentResponse>) {
        val agentId = request.agentId
        val agentContext = this.proxy.getAgentContext(agentId)
        if (agentContext == null)
            logger.info("registerAgent() missing AgentContext agentId: $agentId")
        else
            agentContext.apply {
                agentName = request.agentName
                hostname = request.hostname
                markActivity()
            }

        responseObserver.apply {
            onNext(RegisterAgentResponse.newBuilder()
                           .setValid(agentContext != null)
                           .setReason("Invalid agentId: $agentId")
                           .setAgentId(agentId)
                           .build())
            onCompleted()
        }
    }

    override fun registerPath(request: RegisterPathRequest,
                              responseObserver: StreamObserver<RegisterPathResponse>) {
        val path = request.path
        if (this.proxy.containsPath(path))
            logger.info("Overwriting path /$path")

        val agentId = request.agentId
        val agentContext = this.proxy.getAgentContext(agentId)
        val response =
                RegisterPathResponse.newBuilder()
                        .setValid(agentContext != null)
                        .setReason("Invalid agentId: $agentId")
                        .setPathCount(this.proxy.pathMapSize())
                        .setPathId(if (agentContext != null) PATH_ID_GENERATOR.getAndIncrement() else -1)
                        .build()
        if (agentContext == null) {
            logger.error("Missing AgentContext for agentId: $agentId")
        }
        else {
            this.proxy.addPath(path, agentContext)
            agentContext.markActivity()
        }

        responseObserver.apply {
            onNext(response)
            onCompleted()
        }
    }

    override fun unregisterPath(request: UnregisterPathRequest,
                                responseObserver: StreamObserver<UnregisterPathResponse>) {
        val path = request.path
        val agentId = request.agentId
        val agentContext = this.proxy.getAgentContext(agentId)

        val responseBuilder = UnregisterPathResponse.newBuilder()

        if (agentContext == null) {
            logger.error("Missing AgentContext for agentId: $agentId")
            responseBuilder.setValid(false).reason = "Invalid agentId: $agentId"
        }
        else {
            this.proxy.removePath(path, agentId, responseBuilder)
            agentContext.markActivity()
        }

        responseObserver.apply {
            onNext(responseBuilder.build())
            onCompleted()
        }
    }

    override fun pathMapSize(request: PathMapSizeRequest, responseObserver: StreamObserver<PathMapSizeResponse>) {
        responseObserver.apply {
            onNext(PathMapSizeResponse.newBuilder().setPathCount(proxy.pathMapSize()).build())
            onCompleted()
        }
    }

    override fun sendHeartBeat(request: HeartBeatRequest, responseObserver: StreamObserver<HeartBeatResponse>) {
        this.proxy.metrics?.heartbeats?.inc()
        val agentContext = this.proxy.getAgentContext(request.agentId)
        agentContext?.markActivity() ?: logger.info("sendHeartBeat() missing AgentContext agentId: ${request.agentId}")
        responseObserver.apply {
            onNext(HeartBeatResponse.newBuilder()
                           .setValid(agentContext != null)
                           .setReason("Invalid agentId: ${request.agentId}")
                           .build())
            onCompleted()
        }
    }

    override fun readRequestsFromProxy(agentInfo: AgentInfo, responseObserver: StreamObserver<ScrapeRequest>) {
        val agentId = agentInfo.agentId
        val agentContext = this.proxy.getAgentContext(agentId)
        if (agentContext != null) {
            while (this.proxy.isRunning && agentContext.valid) {
                val scrapeRequest = agentContext.pollScrapeRequestQueue()
                if (scrapeRequest != null) {
                    responseObserver.onNext(scrapeRequest.scrapeRequest)
                }
            }
        }
        responseObserver.onCompleted()
    }

    override fun writeResponsesToProxy(responseObserver: StreamObserver<Empty>): StreamObserver<ScrapeResponse> {
        return object : StreamObserver<ScrapeResponse> {
            override fun onNext(response: ScrapeResponse) {
                val scrapeRequest = proxy.getFromScrapeRequestMap(response.scrapeId)
                if (scrapeRequest == null)
                    logger.error("Missing ScrapeRequestWrapper for scrape_id: ${response.scrapeId}")
                else
                    scrapeRequest.apply {
                        scrapeResponse = response
                        markComplete()
                        agentContext.markActivity()
                    }
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
