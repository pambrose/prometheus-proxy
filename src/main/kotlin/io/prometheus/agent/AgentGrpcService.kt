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

package io.prometheus.agent

import brave.Tracing
import brave.grpc.GrpcTracing
import com.github.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.github.pambrose.common.dsl.GrpcDsl
import com.github.pambrose.common.dsl.GrpcDsl.channel
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.zip
import com.github.pambrose.common.utils.TlsContext
import com.github.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.github.pambrose.common.utils.TlsUtils.buildClientTlsContext
import com.google.protobuf.ByteString
import com.google.protobuf.Empty
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.prometheus.Agent
import io.prometheus.common.GrpcObjects
import io.prometheus.common.GrpcObjects.MAX_MSG_SIZE
import io.prometheus.common.GrpcObjects.newRegisterAgentRequest
import io.prometheus.grpc.ChunkData
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.HeaderData
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceBlockingStub
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceStub
import io.prometheus.grpc.SummaryData
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.CRC32
import kotlin.properties.Delegates.notNull

class AgentGrpcService(private val agent: Agent,
                       options: AgentOptions,
                       private val inProcessServerName: String) {
  private var grpcStarted by atomicBoolean(false)
  private var blockingStub: ProxyServiceBlockingStub by notNull()
  private var asyncStub: ProxyServiceStub by notNull()

  private lateinit var tracing: Tracing
  private lateinit var grpcTracing: GrpcTracing

  var channel: ManagedChannel by notNull()

  val hostName: String
  val port: Int
  private val tlsContext: TlsContext

  init {
    val schemeStripped =
        options.proxyHostname
            .run {
              when {
                startsWith("http://") -> removePrefix("http://")
                startsWith("https://") -> removePrefix("https://")
                else -> this
              }
            }

    if (":" in schemeStripped) {
      val vals = schemeStripped.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
      hostName = vals[0]
      port = Integer.valueOf(vals[1])
    }
    else {
      hostName = schemeStripped
      port = 50051
    }

    if (agent.isZipkinEnabled) {
      tracing = agent.zipkinReporterService.newTracing("grpc_client")
      grpcTracing = GrpcTracing.create(tracing)
    }

    tlsContext =
        agent.options.run {
          if (certChainFilePath.isNotEmpty()
              || privateKeyFilePath.isNotEmpty()
              || trustCertCollectionFilePath.isNotEmpty())
            buildClientTlsContext(certChainFilePath = certChainFilePath,
                                  privateKeyFilePath = privateKeyFilePath,
                                  trustCertCollectionFilePath = trustCertCollectionFilePath)
          else
            PLAINTEXT_CONTEXT
        }

    resetGrpcStubs()
  }

  fun shutDown() {
    if (agent.isZipkinEnabled)
      tracing.close()
    if (grpcStarted)
      channel.shutdownNow()
  }

  @Synchronized
  fun resetGrpcStubs() {
    logger.info { "Creating gRPC stubs" }

    if (grpcStarted)
      shutDown()
    else
      grpcStarted = true


    channel =
        channel(hostName = hostName,
                port = port,
                tlsContext = tlsContext,
                overrideAuthority = agent.options.overrideAuthority,
                inProcessServerName = inProcessServerName) {
          if (agent.isZipkinEnabled)
            intercept(grpcTracing.newClientInterceptor())
        }

    val interceptors = listOf(AgentClientInterceptor(agent))

    blockingStub = ProxyServiceGrpc.newBlockingStub(ClientInterceptors.intercept(channel, interceptors))
    asyncStub = ProxyServiceGrpc.newStub(ClientInterceptors.intercept(channel, interceptors))
  }

  // If successful, this will create an agentContext on the Proxy and an interceptor will add an agent_id to the headers`
  fun connectAgent() =
      try {
        logger.info { "Connecting to proxy at ${agent.proxyHost} using ${tlsContext.desc()}..." }
        blockingStub.connectAgent(Empty.getDefaultInstance())
        logger.info { "Connected to proxy at ${agent.proxyHost} using ${tlsContext.desc()}" }
        if (agent.isMetricsEnabled)
          agent.metrics.connects.labels("success")?.inc()
        true
      } catch (e: StatusRuntimeException) {
        if (agent.isMetricsEnabled)
          agent.metrics.connects.labels("failure")?.inc()
        logger.info { "Cannot connect to proxy at ${agent.proxyHost} using ${tlsContext.desc()} - ${e.simpleClassName}: ${e.message}" }
        false
      }

  fun registerAgent(initialConnectionLatch: CountDownLatch) {
    val request = newRegisterAgentRequest(agent.agentId, agent.agentName, hostName)
    blockingStub.registerAgent(request)
        .also { resp ->
          agent.markMsgSent()
          if (!resp.valid)
            throw RequestFailureException("registerAgent() - ${resp.reason}")
        }
    initialConnectionLatch.countDown()
  }

  fun pathMapSize(): Int {
    val request = GrpcObjects.newPathMapSizeRequest(agent.agentId)
    return blockingStub.pathMapSize(request)
        .let { resp ->
          agent.markMsgSent()
          resp.pathCount
        }
  }

  fun registerPathOnProxy(path: String): Long {
    val request = GrpcObjects.newRegisterPathRequest(agent.agentId, path)
    return blockingStub.registerPath(request)
        .let { resp ->
          agent.markMsgSent()
          if (!resp.valid)
            throw RequestFailureException("registerPath() - ${resp.reason}")
          resp.pathId
        }
  }

  fun unregisterPathOnProxy(path: String) {
    val request = GrpcObjects.newUnregisterPathRequest(agent.agentId, path)
    blockingStub.unregisterPath(request)
        .also { resp ->
          agent.markMsgSent()
          if (!resp.valid)
            throw RequestFailureException("unregisterPath() - ${resp.reason}")
        }
  }

  fun sendHeartBeat(connectionContext: AgentConnectionContext) {
    if (agent.agentId.isEmpty())
      return

    try {
      val request = GrpcObjects.newHeartBeatRequest(agent.agentId)
      blockingStub
          .sendHeartBeat(request)
          .also { resp ->
            agent.markMsgSent()
            if (!resp.valid) {
              logger.error { "AgentId ${agent.agentId} not found on proxy" }
              throw StatusRuntimeException(Status.NOT_FOUND)
            }
          }
    } catch (e: StatusRuntimeException) {
      logger.error { "Hearbeat failed ${e.status}" }
      connectionContext.disconnect()
    }
  }

  fun readRequestsFromProxy(agentHttpService: AgentHttpService, connectionContext: AgentConnectionContext) {
    asyncStub.readRequestsFromProxy(
        GrpcObjects.newAgentInfo(agent.agentId),
        GrpcDsl.streamObserver {
          onNext { req ->
            // This will block, but only for the duration of the send.
            // The actual fetch happens at the other end of the channel
            runBlocking {
              logger.debug { "readRequestsFromProxy(): \n$req" }
              connectionContext.scrapeRequestChannel.send { agentHttpService.fetchScrapeUrl(req) }
              agent.scrapeRequestBacklogSize.incrementAndGet()
            }
          }

          onError { throwable ->
            val s = Status.fromThrowable(throwable)
            logger.error { "Error in readRequestsFromProxy(): ${s.code} ${s.description}" }
            connectionContext.disconnect()
          }

          onCompleted {
            connectionContext.disconnect()
          }
        })
  }

  suspend fun writeResponsesToProxyUntilDisconnected(connectionContext: AgentConnectionContext) {
    val nonchunkedObserver =
        asyncStub.writeResponsesToProxy(
            GrpcDsl.streamObserver<Empty> {
              onNext {
                // Ignore Empty return value
              }

              onError { throwable ->
                val s = Status.fromThrowable(throwable)
                logger.error { "Error in writeResponsesToProxyUntilDisconnected(): ${s.code} ${s.description}" }
                connectionContext.disconnect()
              }

              onCompleted {
                connectionContext.disconnect()
              }
            })

    val chunkedObserver =
        asyncStub.writeChunkedResponsesToProxy(
            GrpcDsl.streamObserver<Empty> {
              onNext {
                // Ignore Empty return value
              }

              onError { throwable ->
                val s = Status.fromThrowable(throwable)
                logger.error { "Error in writeResponsesToProxyUntilDisconnected(): ${s.code} ${s.description}" }
                connectionContext.disconnect()
              }

              onCompleted {
                connectionContext.disconnect()
              }
            })

    for (scrapeResponse in connectionContext.scrapeResultChannel) {
      if (scrapeResponse.contentText.length < MAX_MSG_SIZE) {
        logger.debug { "writeResponsesToProxyUntilDisconnected(): \n$scrapeResponse" }
        nonchunkedObserver.onNext(scrapeResponse)
      }
      else {
        logger.info { "Writing chunked message with length: ${scrapeResponse.contentText.length}" }

        ChunkedScrapeResponse.newBuilder()
            .let { builder ->
              builder.header =
                  HeaderData.newBuilder()
                      .run {
                        scrapeResponse.apply {
                          headerValidResponse = validResponse
                          headerAgentId = agentId
                          headerScrapeId = scrapeId
                          headerStatusCode = statusCode
                          headerFailureReason = failureReason
                          headerUrl = url
                          headerContentType = contentType
                        }
                        build()
                      }
              builder.build()
            }.also {
              logger.info { "Writing header data" }
              chunkedObserver.onNext(it)
            }

        var totalByteCount = 0
        var totalChunkCount = 0
        val checksum = CRC32()
        val zippedData = scrapeResponse.contentText.zip()
        val bais = ByteArrayInputStream(zippedData)
        val buffer = ByteArray(MAX_MSG_SIZE)
        var byteCount: Int

        while (bais.read(buffer).also { bytesRead -> byteCount = bytesRead } > 0) {
          totalChunkCount++
          totalByteCount += byteCount
          checksum.update(buffer, 0, buffer.size);
          val byteString: ByteString = ByteString.copyFrom(buffer)

          ChunkedScrapeResponse.newBuilder()
              .let { builder ->
                builder.chunk =
                    ChunkData.newBuilder()
                        .run {
                          chunkCount = totalChunkCount
                          chunkByteCount = byteCount
                          chunkChecksum = checksum.value
                          chunkBytes = byteString
                          build()
                        }
                builder.build()
              }.also {
                logger.info { "Writing chunk data" }
                chunkedObserver.onNext(it)
              }
        }

        ChunkedScrapeResponse.newBuilder()
            .let { builder ->
              builder.summary =
                  SummaryData.newBuilder()
                      .run {
                        summaryChunkCount = totalChunkCount
                        summaryByteCount = totalByteCount
                        summaryChecksum = checksum.value
                        build()
                      }
              builder.build()
            }.also {
              logger.info { "Writing summary data" }
              chunkedObserver.onNext(it)
            }
      }

      agent.markMsgSent()
      agent.scrapeRequestBacklogSize.decrementAndGet()
    }

    logger.info { "Disconnected from proxy at ${agent.proxyHost}" }

    nonchunkedObserver.onCompleted()
    chunkedObserver.onCompleted()
  }

  companion object : KLogging()
}