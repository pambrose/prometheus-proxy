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

package io.prometheus.agent

import brave.grpc.GrpcTracing
import com.github.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.github.pambrose.common.dsl.GrpcDsl.channel
import com.github.pambrose.common.util.runCatchingCancellable
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.utils.TlsContext
import com.github.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.github.pambrose.common.utils.TlsUtils.buildClientTlsContext
import com.google.protobuf.ByteString.copyFrom
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.prometheus.Agent
import io.prometheus.common.BaseOptions.Companion.HTTPS_PREFIX
import io.prometheus.common.BaseOptions.Companion.HTTP_PREFIX
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.common.Utils.exceptionDetails
import io.prometheus.common.Utils.parseHostPort
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.chunkData
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.heartBeatRequest
import io.prometheus.grpc.pathMapSizeRequest
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.grpc.registerPathRequest
import io.prometheus.grpc.summaryData
import io.prometheus.grpc.unregisterPathRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.concurrent.withLock

internal class AgentGrpcService(
  internal val agent: Agent,
  private val options: AgentOptions,
  private val inProcessServerName: String,
) {
  private val grpcLock = ReentrantLock()
  private var grpcStarted by atomicBoolean(false)
  private val tracing by lazy { agent.zipkinReporterService.newTracing("grpc_client") }
  private val grpcTracing by lazy { GrpcTracing.create(tracing) }

  internal lateinit var grpcStub: ProxyServiceGrpcKt.ProxyServiceCoroutineStub

  // Deadline applied to all unary RPCs. Set to 0 to disable (e.g., in tests).
  internal var unaryDeadlineSecs = options.unaryDeadlineSecs.toLong()

  private fun unaryStub() =
    if (unaryDeadlineSecs > 0) grpcStub.withDeadlineAfter(unaryDeadlineSecs, SECONDS) else grpcStub

  lateinit var channel: ManagedChannel

  val agentHostName: String
  val agentPort: Int

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

    val parsed = parseHostPort(schemeStripped, DEFAULT_GRPC_PORT)
    agentHostName = parsed.host
    agentPort = parsed.port

    tlsContext =
      agent.options.run {
        if (isTlsEnabled || trustCertCollectionFilePath.isNotEmpty())
          buildClientTlsContext(
            certChainFilePath = certChainFilePath,
            privateKeyFilePath = privateKeyFilePath,
            trustCertCollectionFilePath = trustCertCollectionFilePath,
          )
        else
          PLAINTEXT_CONTEXT
      }

    resetGrpcStubs()
  }

  // Must be called while holding grpcLock
  private fun shutDownLocked() {
    if (agent.isZipkinEnabled)
      tracing.close()
    if (grpcStarted) {
      channel.shutdownNow()
      channel.awaitTermination(5, SECONDS)
    }
  }

  fun shutDown() =
    grpcLock.withLock {
      shutDownLocked()
    }

  fun resetGrpcStubs() =
    grpcLock.withLock {
      logger.info { "Creating gRPC stubs" }

      if (grpcStarted)
        shutDownLocked()

      channel =
        channel(
          hostName = agentHostName,
          port = agentPort,
          enableRetry = true,
          tlsContext = tlsContext,
          overrideAuthority = agent.options.overrideAuthority,
          inProcessServerName = inProcessServerName,
        ) {
          if (agent.isZipkinEnabled)
            intercept(grpcTracing.newClientInterceptor())

          if (options.keepAliveTimeSecs > -1L)
            keepAliveTime(options.keepAliveTimeSecs, SECONDS)

          if (options.keepAliveTimeoutSecs > -1L)
            keepAliveTimeout(options.keepAliveTimeoutSecs, SECONDS)

          if (options.keepAliveWithoutCalls)
            keepAliveWithoutCalls(options.keepAliveWithoutCalls)
        }

      grpcStarted = true

      val interceptors =
        buildList<ClientInterceptor> {
          if (!options.transportFilterDisabled)
            add(AgentClientInterceptor(agent))
        }
      grpcStub = ProxyServiceGrpcKt.ProxyServiceCoroutineStub(ClientInterceptors.intercept(channel, interceptors))
    }

  // If successful, will create an agentContext on the Proxy and an interceptor will add an agent_id to the headers`
  suspend fun connectAgent(transportFilterDisabled: Boolean) =
    runCatchingCancellable {
      logger.info { "Connecting to proxy at ${agent.proxyHost} using ${tlsContext.desc()}..." }
      if (transportFilterDisabled)
        unaryStub().connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE).also { agent.agentId = it.agentId }
      else
        unaryStub().connectAgent(EMPTY_INSTANCE)

      logger.info { "Connected to proxy at ${agent.proxyHost} using ${tlsContext.desc()}" }
      agent.metrics { connectCount.labels(agent.launchId, "success").inc() }
      true
    }.getOrElse { e ->
      agent.metrics { connectCount.labels(agent.launchId, "failure").inc() }
      logger.error {
        "Cannot connect to proxy at ${agent.proxyHost} using ${tlsContext.desc()} - ${e.simpleClassName}: ${e.message}"
      }
      false
    }

  suspend fun registerAgent(initialConnectionLatch: CountDownLatch) {
    val request =
      registerAgentRequest {
        require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
        agentId = agent.agentId
        launchId = agent.launchId
        agentName = agent.agentName
        hostName = agentHostName
        consolidated = agent.options.consolidated
      }
    unaryStub().registerAgent(request)
      .also { response ->
        agent.markMsgSent()
        if (!response.valid)
          throw RequestFailureException("registerAgent() - ${response.reason}")
      }
    initialConnectionLatch.countDown()
  }

  suspend fun pathMapSize(): Int =
    unaryStub().pathMapSize(
      pathMapSizeRequest {
        require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
        agentId = agent.agentId
      },
    ).run {
      agent.markMsgSent()
      pathCount
    }

  suspend fun registerPathOnProxy(
    pathVal: String,
    labelsJson: String,
  ): RegisterPathResponse =
    unaryStub().registerPath(
      registerPathRequest {
        require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
        require(pathVal.isNotEmpty()) { EMPTY_PATH_MSG }
        agentId = agent.agentId
        path = pathVal
        labels = labelsJson
      },
    ).apply {
      agent.markMsgSent()
      if (!valid)
        throw RequestFailureException("registerPathOnProxy() - $reason")
    }

  suspend fun unregisterPathOnProxy(pathVal: String): UnregisterPathResponse =
    unaryStub().unregisterPath(
      unregisterPathRequest {
        require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
        require(pathVal.isNotEmpty()) { EMPTY_PATH_MSG }
        agentId = agent.agentId
        path = pathVal
      },
    ).apply {
      agent.markMsgSent()
      if (!valid)
        throw RequestFailureException("unregisterPathOnProxy() - $reason")
    }

  suspend fun sendHeartBeat() {
    agent.agentId
      .also { anAgentId ->
        if (anAgentId.isNotEmpty())
          runCatchingCancellable {
            val request =
              heartBeatRequest {
                agentId = anAgentId
              }
            unaryStub().sendHeartBeat(request)
              .apply {
                agent.markMsgSent()
                if (!valid) {
                  logger.error { "AgentId $anAgentId not found on proxy" }
                  throw StatusRuntimeException(Status.NOT_FOUND)
                }
              }
          }.onFailure { e ->
            if (e is StatusRuntimeException)
              logger.error { "sendHeartBeat() failed ${e.status}" }
            else
              logger.error { "sendHeartBeat() failed ${e.message}" }
          }
      }
  }

  suspend fun readRequestsFromProxy(
    agentHttpService: AgentHttpService,
    connectionContext: AgentConnectionContext,
  ) {
    val agentInfo =
      agentInfo {
        require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
        agentId = agent.agentId
      }
    grpcStub.readRequestsFromProxy(agentInfo)
      .collect { grpcRequest: ScrapeRequest ->
        // The actual fetch happens at the other end of the channel, not here.
        logger.debug { "readRequestsFromProxy():\n$grpcRequest" }
        connectionContext.sendScrapeRequestAction { agentHttpService.fetchScrapeUrl(grpcRequest) }
      }
  }

  private suspend fun processScrapeResults(
    agent: Agent,
    connectionContext: AgentConnectionContext,
    nonChunkedChannel: Channel<ScrapeResponse>,
    chunkedChannel: Channel<ChunkedScrapeResponse>,
  ) {
    for (scrapeResults in connectionContext.scrapeResults()) {
      val scrapeId = scrapeResults.srScrapeId

      if (!scrapeResults.srZipped) {
        logger.debug { "Writing non-chunked msg scrapeId: $scrapeId length: ${scrapeResults.srContentAsText.length}" }
        nonChunkedChannel.send(scrapeResults.toScrapeResponse())
        agent.metrics { scrapeResultCount.labels(agent.launchId, "non-gzipped").inc() }
      } else {
        val zipped = scrapeResults.srContentAsZipped
        val chunkContentSize = options.chunkContentSizeBytes

        logger.debug { "Comparing ${zipped.size} and $chunkContentSize" }

        if (zipped.size < chunkContentSize) {
          logger.debug { "Writing zipped non-chunked msg scrapeId: $scrapeId length: ${zipped.size}" }
          nonChunkedChannel.send(scrapeResults.toScrapeResponse())
          agent.metrics { scrapeResultCount.labels(agent.launchId, "gzipped").inc() }
        } else {
          scrapeResults.toScrapeResponseHeader()
            .also {
              logger.debug { "Writing header length: ${zipped.size} for scrapeId: $scrapeId " }
              chunkedChannel.send(it)
            }

          var totalByteCount = 0
          var totalChunkCount = 0
          val checksum = CRC32()
          val bais = ByteArrayInputStream(zipped)
          val buffer = ByteArray(chunkContentSize)
          var readByteCount: Int

          while (withContext(Dispatchers.IO) { bais.read(buffer) }.also { readByteCount = it } > 0) {
            totalChunkCount++
            totalByteCount += readByteCount
            checksum.update(buffer, 0, readByteCount)

            chunkedScrapeResponse {
              chunk = chunkData {
                chunkScrapeId = scrapeId
                chunkCount = totalChunkCount
                chunkByteCount = readByteCount
                chunkChecksum = checksum.value
                chunkBytes = copyFrom(buffer, 0, readByteCount)
              }
            }.also {
              logger.debug { "Writing chunk $totalChunkCount for scrapeId: $scrapeId" }
              chunkedChannel.send(it)
            }
          }

          chunkedScrapeResponse {
            summary = summaryData {
              summaryScrapeId = scrapeId
              summaryChunkCount = totalChunkCount
              summaryByteCount = totalByteCount
              summaryChecksum = checksum.value
            }
          }.also {
            logger.debug { "Writing summary totalChunkCount: $totalChunkCount for scrapeID: $scrapeId" }
            chunkedChannel.send(it)
            agent.metrics { scrapeResultCount.labels(agent.launchId, "chunked").inc() }
          }
        }
      }

      agent.markMsgSent()
    }
  }

  suspend fun writeResponsesToProxyUntilDisconnected(
    agent: Agent,
    connectionContext: AgentConnectionContext,
  ) {
    val nonChunkedChannel = Channel<ScrapeResponse>(UNLIMITED)
    val chunkedChannel = Channel<ChunkedScrapeResponse>(UNLIMITED)
    try {
      coroutineScope {
        // Ends by connectionContext.close()
        launch(Dispatchers.IO) {
          try {
            runCatchingCancellable {
              processScrapeResults(agent, connectionContext, nonChunkedChannel, chunkedChannel)
            }.onFailure { e ->
              if (agent.isRunning)
                Status.fromThrowable(e)
                  .apply { logger.error(e) { "processScrapeResults(): ${exceptionDetails(e)}" } }
            }
          } finally {
            // Close channels when the producer finishes so consumers' consumeAsFlow() will complete
            nonChunkedChannel.close()
            chunkedChannel.close()
          }
        }

        // Ends by disconnection from server
        launch(Dispatchers.IO) {
          runCatchingCancellable {
            grpcStub.writeResponsesToProxy(nonChunkedChannel.consumeAsFlow())
          }.onFailure { e ->
            if (agent.isRunning)
              Status.fromThrowable(e)
                .apply { logger.error(e) { "writeResponsesToProxy(): ${exceptionDetails(e)}" } }
          }
        }

        launch(Dispatchers.IO) {
          runCatchingCancellable {
            grpcStub.writeChunkedResponsesToProxy(chunkedChannel.consumeAsFlow())
          }.onFailure { e ->
            if (agent.isRunning)
              Status.fromThrowable(e)
                .apply { logger.error(e) { "writeChunkedResponsesToProxy(): ${exceptionDetails(e)}" } }
          }
        }
      }
    } finally {
      nonChunkedChannel.close()
      chunkedChannel.close()
    }
  }

  companion object {
    private val logger = logger {}
    private const val DEFAULT_GRPC_PORT = 50051
  }
}
