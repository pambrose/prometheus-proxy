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

package io.prometheus.agent

import brave.grpc.GrpcTracing
import com.github.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.github.pambrose.common.dsl.GrpcDsl.channel
import com.github.pambrose.common.dsl.GrpcDsl.streamObserver
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.utils.TlsContext
import com.github.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.github.pambrose.common.utils.TlsUtils.buildClientTlsContext
import com.google.protobuf.Empty
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.prometheus.Agent
import io.prometheus.common.BaseOptions.Companion.HTTPS_PREFIX
import io.prometheus.common.BaseOptions.Companion.HTTP_PREFIX
import io.prometheus.common.GrpcObjects
import io.prometheus.common.GrpcObjects.newAgentInfo
import io.prometheus.common.GrpcObjects.newRegisterAgentRequest
import io.prometheus.common.GrpcObjects.newScrapeResponseChunk
import io.prometheus.common.GrpcObjects.newScrapeResponseSummary
import io.prometheus.common.GrpcObjects.toScrapeResponse
import io.prometheus.common.GrpcObjects.toScrapeResponseHeader
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceBlockingStub
import io.prometheus.grpc.ProxyServiceGrpc.ProxyServiceStub
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.CRC32
import kotlin.properties.Delegates.notNull

internal class AgentGrpcService(private val agent: Agent,
                                private val options: AgentOptions,
                                private val inProcessServerName: String) {
  private var grpcStarted by atomicBoolean(false)
  private var blockingStub: ProxyServiceBlockingStub by notNull()
  private var asyncStub: ProxyServiceStub by notNull()
  private val tracing by lazy { agent.zipkinReporterService.newTracing("grpc_client") }
  private val grpcTracing by lazy { GrpcTracing.create(tracing) }

  var channel: ManagedChannel by notNull()

  val hostName: String
  val port: Int
  private val tlsContext: TlsContext

  init {
    val schemeStripped =
        options.proxyHostname
            .run {
              when {
                startsWith(HTTP_PREFIX) -> removePrefix(HTTP_PREFIX)
                startsWith(HTTPS_PREFIX) -> removePrefix(HTTPS_PREFIX)
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

  @Synchronized
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
        agent.metrics { connectCount.labels("success").inc() }
        true
      } catch (e: StatusRuntimeException) {
        agent.metrics { connectCount.labels("failure").inc() }
        logger.info { "Cannot connect to proxy at ${agent.proxyHost} using ${tlsContext.desc()} - ${e.simpleClassName}: ${e.message}" }
        false
      }

  fun registerAgent(initialConnectionLatch: CountDownLatch) {
    val request = newRegisterAgentRequest(agent.agentId, agent.agentName, hostName)
    blockingStub.registerAgent(request)
        .also { response ->
          agent.markMsgSent()
          if (!response.valid)
            throw RequestFailureException("registerAgent() - ${response.reason}")
        }
    initialConnectionLatch.countDown()
  }

  fun pathMapSize(): Int {
    val request = GrpcObjects.newPathMapSizeRequest(agent.agentId)
    return blockingStub.pathMapSize(request)
        .run {
          agent.markMsgSent()
          pathCount
        }
  }

  fun registerPathOnProxy(path: String): Long {
    val request = GrpcObjects.newRegisterPathRequest(agent.agentId, path)
    return blockingStub.registerPath(request)
        .run {
          agent.markMsgSent()
          if (!valid)
            throw RequestFailureException("registerPath() - $reason")
          pathId
        }
  }

  fun unregisterPathOnProxy(path: String) {
    val request = GrpcObjects.newUnregisterPathRequest(agent.agentId, path)
    blockingStub.unregisterPath(request)
        .apply {
          agent.markMsgSent()
          if (!valid)
            throw RequestFailureException("unregisterPath() - $reason")
        }
  }

  fun sendHeartBeat(connectionContext: AgentConnectionContext) {
    if (agent.agentId.isEmpty())
      return

    try {
      val request = GrpcObjects.newHeartBeatRequest(agent.agentId)
      blockingStub
          .sendHeartBeat(request)
          .apply {
            agent.markMsgSent()
            if (!valid) {
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
        newAgentInfo(agent.agentId),
        streamObserver {
          onNext { request ->
            // This will block, but only very briefly for the duration of the send.
            // The actual fetch happens at the other end of the channel, not here.
            runBlocking {
              logger.debug { "readRequestsFromProxy(): \n$request" }
              connectionContext.scrapeRequestsChannel.send { agentHttpService.fetchScrapeUrl(request) }
              agent.scrapeRequestBacklogSize.incrementAndGet()
            }
          }

          onError { throwable ->
            if (agent.isRunning)
              Status.fromThrowable(throwable).apply {
                logger.error { "Error in readRequestsFromProxy(): $code $description" }
              }
            connectionContext.disconnect()
          }

          onCompleted {
            connectionContext.disconnect()
          }
        })
  }

  suspend fun writeResponsesToProxyUntilDisconnected(connectionContext: AgentConnectionContext) {

    val emptyResponseObserver =
        streamObserver<Empty> {
          onNext {
            // Ignore Empty return value
          }

          onError { throwable ->
            if (agent.isRunning)
              Status.fromThrowable(throwable).apply {
                logger.error { "Error in writeResponsesToProxyUntilDisconnected(): $code $description" }
              }
            connectionContext.disconnect()
          }

          onCompleted {
            connectionContext.disconnect()
          }
        }

    val nonchunkedObserver = asyncStub.writeResponsesToProxy(emptyResponseObserver)
    val chunkedObserver = asyncStub.writeChunkedResponsesToProxy(emptyResponseObserver)

    for (scrapeResults: ScrapeResults in connectionContext.scrapeResultsChannel) {
      val scrapedId = scrapeResults.scrapeId

      if (!scrapeResults.zipped) {
        logger.debug { "Writing non-chunked msg scrapeId: $scrapedId length: ${scrapeResults.contentAsText.length}" }
        nonchunkedObserver.onNext(scrapeResults.toScrapeResponse())
        agent.metrics { scrapeResultCount.labels("non-gzipped").inc() }
      }
      else {
        val zipped = scrapeResults.contentAsZipped
        logger.debug { "Comparing ${zipped.size} and ${options.chunkContentSizeKbs}" }

        if (zipped.size < options.chunkContentSizeKbs) {
          logger.debug { "Writing zipped non-chunked msg scrapeId: $scrapedId length: ${zipped.size}" }
          nonchunkedObserver.onNext(scrapeResults.toScrapeResponse())
          agent.metrics { scrapeResultCount.labels("gzipped").inc() }
        }
        else {
          scrapeResults.toScrapeResponseHeader().also {
            logger.debug { "Writing header length: ${zipped.size} for scrapeId: $scrapedId " }
            chunkedObserver.onNext(it)
          }

          var totalByteCount = 0
          var totalChunkCount = 0
          val checksum = CRC32()
          val bais = ByteArrayInputStream(zipped)
          val buffer = ByteArray(options.chunkContentSizeKbs)
          var readByteCount: Int

          while (bais.read(buffer).also { bytesRead -> readByteCount = bytesRead } > 0) {
            totalChunkCount++
            totalByteCount += readByteCount
            checksum.update(buffer, 0, buffer.size)

            newScrapeResponseChunk(scrapeResults.scrapeId, totalChunkCount, readByteCount, checksum, buffer).also {
              logger.debug { "Writing chunk $totalChunkCount for scrapeId: $scrapedId" }
              chunkedObserver.onNext(it)
            }
          }

          newScrapeResponseSummary(scrapeResults.scrapeId, totalChunkCount, totalByteCount, checksum).also {
            logger.debug { "Writing summary totalChunkCount: $totalChunkCount for scrapeID: $scrapedId" }
            chunkedObserver.onNext(it)
            agent.metrics { scrapeResultCount.labels("chunked").inc() }
          }
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