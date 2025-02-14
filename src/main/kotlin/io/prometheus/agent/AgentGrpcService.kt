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
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.utils.TlsContext
import com.github.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.github.pambrose.common.utils.TlsUtils.buildClientTlsContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.prometheus.Agent
import io.prometheus.common.BaseOptions.Companion.HTTPS_PREFIX
import io.prometheus.common.BaseOptions.Companion.HTTP_PREFIX
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.GrpcObjects.newScrapeResponseChunk
import io.prometheus.common.GrpcObjects.newScrapeResponseSummary
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.common.ScrapeResults
import io.prometheus.grpc.AgentInfo
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.HeartBeatRequest
import io.prometheus.grpc.PathMapSizeRequest
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.RegisterPathRequest
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.UnregisterPathRequest
import io.prometheus.grpc.UnregisterPathResponse
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.zip.CRC32
import kotlin.properties.Delegates.notNull

internal class AgentGrpcService(
  internal val agent: Agent,
  private val options: AgentOptions,
  private val inProcessServerName: String,
) {
  private var grpcStarted by atomicBoolean(false)
  private var stub: ProxyServiceGrpcKt.ProxyServiceCoroutineStub by notNull()
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
    } else {
      hostName = schemeStripped
      port = 50051
    }

    tlsContext =
      agent.options.run {
        if (certChainFilePath.isNotEmpty() ||
          privateKeyFilePath.isNotEmpty() ||
          trustCertCollectionFilePath.isNotEmpty()
        )
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
      channel(
        hostName = hostName,
        port = port,
        enableRetry = true,
        tlsContext = tlsContext,
        overrideAuthority = agent.options.overrideAuthority,
        inProcessServerName = inProcessServerName,
      ) {
        if (agent.isZipkinEnabled)
          intercept(grpcTracing.newClientInterceptor())

        keepAliveTime(options.keepAliveTimeSecs, SECONDS)
        keepAliveTimeout(options.keepAliveTimeoutSecs, SECONDS)
        keepAliveWithoutCalls(options.keepAliveWithoutCalls)
      }

    val interceptors =
      buildList<ClientInterceptor> {
        if (!options.transportFilterDisabled)
          add(AgentClientInterceptor(agent))
      }
    stub = ProxyServiceGrpcKt.ProxyServiceCoroutineStub(ClientInterceptors.intercept(channel, interceptors))
  }

  // If successful, will create an agentContext on the Proxy and an interceptor will add an agent_id to the headers`
  suspend fun connectAgent(transportFilterDisabled: Boolean) =
    runCatching {
      logger.info { "Connecting to proxy at ${agent.proxyHost} using ${tlsContext.desc()}..." }
      if (transportFilterDisabled)
        stub.connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE).also { agent.agentId = it.agentId }
      else
        stub.connectAgent(EMPTY_INSTANCE)

      logger.info { "Connected to proxy at ${agent.proxyHost} using ${tlsContext.desc()}" }
      agent.metrics { connectCount.labels(agent.launchId, "success").inc() }
      true
    }.getOrElse { e ->
      agent.metrics { connectCount.labels(agent.launchId, "failure").inc() }
      logger.info {
        "Cannot connect to proxy at ${agent.proxyHost} using ${tlsContext.desc()} - ${e.simpleClassName}: ${e.message}"
      }
      false
    }

  suspend fun registerAgent(initialConnectionLatch: CountDownLatch) {
    val request =
      RegisterAgentRequest
        .newBuilder()
        .also {
          require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
          it.agentId = agent.agentId
          it.launchId = agent.launchId
          it.agentName = agent.agentName
          it.hostName = hostName
          it.consolidated = agent.options.consolidated
        }
        .build()
    stub.registerAgent(request)
      .also { response ->
        agent.markMsgSent()
        if (!response.valid)
          throw RequestFailureException("registerAgent() - ${response.reason}")
      }
    initialConnectionLatch.countDown()
  }

  fun pathMapSize() =
    runBlocking {
      val request =
        PathMapSizeRequest
          .newBuilder()
          .also {
            require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
            it.agentId = agent.agentId
          }
          .build()
      stub.pathMapSize(request)
        .run {
          agent.markMsgSent()
          pathCount
        }
    }

  suspend fun registerPathOnProxy(
    path: String,
    labelsJson: String,
  ): RegisterPathResponse {
    val request =
      RegisterPathRequest
        .newBuilder()
        .also {
          require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
          require(path.isNotEmpty()) { EMPTY_PATH_MSG }
          it.agentId = agent.agentId
          it.path = path
          it.labels = labelsJson
        }
        .build()
    return stub.registerPath(request)
      .apply {
        agent.markMsgSent()
        if (!valid)
          throw RequestFailureException("registerPathOnProxy() - $reason")
      }
  }

  suspend fun unregisterPathOnProxy(path: String): UnregisterPathResponse {
    val request =
      UnregisterPathRequest
        .newBuilder()
        .also {
          require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
          require(path.isNotEmpty()) { EMPTY_PATH_MSG }
          it.agentId = agent.agentId
          it.path = path
        }.build()
    return stub.unregisterPath(request)
      .apply {
        agent.markMsgSent()
        if (!valid)
          throw RequestFailureException("unregisterPathOnProxy() - $reason")
      }
  }

  suspend fun sendHeartBeat() {
    agent.agentId
      .also { agentId ->
        if (agentId.isNotEmpty())
          runCatching {
            val request = HeartBeatRequest.newBuilder().also { it.agentId = agentId }.build()
            stub.sendHeartBeat(request)
              .apply {
                agent.markMsgSent()
                if (!valid) {
                  logger.error { "AgentId $agentId not found on proxy" }
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
    connectionContext
      .use {
        val agentInfo =
          AgentInfo
            .newBuilder()
            .also {
              require(agent.agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }
              it.agentId = agent.agentId
            }.build()
        stub
          .readRequestsFromProxy(agentInfo)
          .collect { grpcRequest: ScrapeRequest ->
            // The actual fetch happens at the other end of the channel, not here.
            logger.debug { "readRequestsFromProxy():\n$grpcRequest" }
            connectionContext.scrapeRequestsChannel.send { agentHttpService.fetchScrapeUrl(grpcRequest) }
            agent.scrapeRequestBacklogSize.incrementAndGet()
          }
      }
  }

  private suspend fun processScrapeResults(
    agent: Agent,
    scrapeResultsChannel: Channel<ScrapeResults>,
    nonChunkedChannel: Channel<ScrapeResponse>,
    chunkedChannel: Channel<ChunkedScrapeResponse>,
  ) = try {
    for (scrapeResults: ScrapeResults in scrapeResultsChannel) {
      val scrapeId = scrapeResults.scrapeId

      if (!scrapeResults.zipped) {
        logger.debug { "Writing non-chunked msg scrapeId: $scrapeId length: ${scrapeResults.contentAsText.length}" }
        nonChunkedChannel.send(scrapeResults.toScrapeResponse())
        agent.metrics { scrapeResultCount.labels(agent.launchId, "non-gzipped").inc() }
      } else {
        val zipped = scrapeResults.contentAsZipped
        val chunkContentSize = options.chunkContentSizeKbs

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

          while (bais.read(buffer).also { bytesRead -> readByteCount = bytesRead } > 0) {
            totalChunkCount++
            totalByteCount += readByteCount
            checksum.update(buffer, 0, buffer.size)

            newScrapeResponseChunk(scrapeId, totalChunkCount, readByteCount, checksum, buffer)
              .also {
                logger.debug { "Writing chunk $totalChunkCount for scrapeId: $scrapeId" }
                chunkedChannel.send(it)
              }
          }

          newScrapeResponseSummary(scrapeId, totalChunkCount, totalByteCount, checksum)
            .also {
              logger.debug { "Writing summary totalChunkCount: $totalChunkCount for scrapeID: $scrapeId" }
              chunkedChannel.send(it)
              agent.metrics { scrapeResultCount.labels(agent.launchId, "chunked").inc() }
            }
        }
      }

      agent.markMsgSent()
      agent.scrapeRequestBacklogSize.decrementAndGet()
    }
  } finally {
    nonChunkedChannel.close()
    chunkedChannel.close()
  }

  suspend fun writeResponsesToProxyUntilDisconnected(
    agent: Agent,
    connectionContext: AgentConnectionContext,
  ) {
    fun exceptionHandler() =
      CoroutineExceptionHandler { _, e ->
        if (agent.isRunning)
          Status.fromThrowable(e)
            .apply { logger.error { "Error in writeResponsesToProxyUntilDisconnected(): $code $description" } }
      }

    coroutineScope {
      val nonChunkedChannel = Channel<ScrapeResponse>(Channel.UNLIMITED)
      val chunkedChannel = Channel<ChunkedScrapeResponse>(Channel.UNLIMITED)

      launch(Dispatchers.Default + exceptionHandler()) {
        processScrapeResults(agent, connectionContext.scrapeResultsChannel, nonChunkedChannel, chunkedChannel)
      }

      connectionContext
        .use {
          coroutineScope {
            launch(Dispatchers.Default + exceptionHandler()) {
              stub.writeResponsesToProxy(nonChunkedChannel.consumeAsFlow())
            }

            launch(Dispatchers.Default + exceptionHandler()) {
              stub.writeChunkedResponsesToProxy(chunkedChannel.consumeAsFlow())
            }
          }

          logger.info { "Disconnected from proxy at ${agent.proxyHost}" }
        }
    }
  }

  companion object {
    private val logger = KotlinLogging.logger {}
  }
}
