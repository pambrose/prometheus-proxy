/*
 * Copyright © 2026 Paul Ambrose
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

@file:Suppress("TooGenericExceptionCaught")

package io.prometheus.agent

import brave.grpc.GrpcTracing
import com.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.pambrose.common.dsl.GrpcDsl.channel
import com.pambrose.common.util.runCatchingCancellable
import com.pambrose.common.util.simpleClassName
import com.pambrose.common.utils.TlsContext
import com.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.pambrose.common.utils.TlsUtils.buildClientTlsContext
import com.google.protobuf.ByteString.copyFrom
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.prometheus.Agent
import io.prometheus.common.BaseOptions.Companion.HTTPS_PREFIX
import io.prometheus.common.BaseOptions.Companion.HTTP_PREFIX
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Messages.EMPTY_PATH_MSG
import io.prometheus.common.ScrapeResults
import io.prometheus.common.Utils.logStreamFailure
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.concurrent.atomics.plusAssign
import kotlin.concurrent.withLock

/**
 * Outcome of a single [AgentGrpcService.sendHeartBeat] call, used by the agent's heartbeat loop to
 * decide whether the connection is healthy or should be torn down so the run loop reconnects.
 */
internal enum class HeartBeatResult {
  /** The proxy acknowledged the heartbeat; the connection is healthy. */
  SUCCESS,

  /** The proxy reported it no longer knows this agent (evicted its context); reconnect immediately. */
  EVICTED,

  /** The heartbeat RPC failed (deadline/transport/other); tolerated up to a threshold before reconnecting. */
  TRANSIENT_FAILURE,
}

/**
 * gRPC client that manages the agent's connection to the proxy.
 *
 * Handles the full gRPC lifecycle: channel and stub creation, TLS configuration, agent
 * registration, path registration/unregistration, heartbeat sending, and bidirectional
 * scrape request/response streaming (including chunked transfers for large payloads).
 * Supports reconnection by resetting stubs and channels on demand.
 *
 * @param agent the parent [Agent] instance
 * @param options agent configuration options (proxy hostname, keepalive, TLS, etc.)
 * @param inProcessServerName the in-process server name (empty for Netty mode)
 * @see io.prometheus.Agent
 * @see AgentConnectionContext
 * @see AgentHttpService
 */
internal class AgentGrpcService(
  internal val agent: Agent,
  private val options: AgentOptions,
  private val inProcessServerName: String,
) {
  private val grpcLock = ReentrantLock()
  private var grpcStarted by atomicBoolean(false)

  // Latched by shutDownChannelPermanently() and never cleared: the agent is stopping for good, so no
  // further channel may be created. Read and written only under grpcLock, which is what makes the
  // stop-vs-reconnect ordering decisive rather than racy.
  private var shutDownRequested by atomicBoolean(false)
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
    // Strip either scheme prefix (case-insensitively, matching BaseOptions.isUrlPrefix); the prefixes
    // are mutually exclusive so a chain works, and removePrefixIgnoreCase no-ops when absent (finding 42).
    val schemeStripped =
      options.proxyHostname
        .removePrefixIgnoreCase(HTTPS_PREFIX)
        .removePrefixIgnoreCase(HTTP_PREFIX)

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
  private fun shutDownChannelLocked() {
    if (grpcStarted) {
      channel.shutdownNow()
      // A false return means the channel did not terminate in time; a leftover call's late onHeaders can
      // then re-assign a stale agentId to the next connection (finding 20), so surface it instead of
      // ignoring the boolean.
      if (!channel.awaitTermination(CHANNEL_TERMINATION_TIMEOUT_SECS, SECONDS))
        logger.warn { "gRPC channel did not terminate within ${CHANNEL_TERMINATION_TIMEOUT_SECS}s" }
    }
  }

  // Must be called while holding grpcLock
  private fun shutDownLocked() {
    if (agent.isZipkinEnabled)
      tracing.close()
    shutDownChannelLocked()
  }

  fun shutDown() =
    grpcLock.withLock {
      shutDownLocked()
    }

  // Shuts the gRPC channel down without touching tracing, breaking any in-flight RPCs (e.g. an idle
  // readRequestsFromProxy collect). This is the *transient* teardown used by the heartbeat loop to force
  // a reconnect, so it deliberately leaves the service able to build a new channel.
  fun shutDownChannel() =
    grpcLock.withLock {
      shutDownChannelLocked()
    }

  // Terminal counterpart of shutDownChannel(), called from Agent.triggerShutdown() so stopSync() can
  // unblock the run loop (finding 1). Guava invokes triggerShutdown() exactly once, so tearing the
  // current channel down is not enough on its own: a run-loop iteration that read `while (isRunning)`
  // as true just before stopAsync() flipped it can still reach resetGrpcStubs() and build a fresh live
  // channel that nothing will ever break, parking run() in an idle collect and reinstating the deadlock.
  // Latching shutDownRequested under grpcLock makes the outcome the same either way the race lands:
  // reset-then-stop tears the new channel down, stop-then-reset never creates it.
  //
  // The full shutDown() (tracing included) still runs from the service shutDown() hook, and
  // channel.shutdownNow() is idempotent so that later call is a no-op on the channel.
  fun shutDownChannelPermanently() =
    grpcLock.withLock {
      shutDownRequested = true
      shutDownChannelLocked()
    }

  fun resetGrpcStubs() =
    grpcLock.withLock {
      // A stop was already requested and the channel torn down; handing the run loop a fresh live
      // channel now would leave it parked in a collect nothing can break (see shutDownChannelPermanently).
      // Returning early leaves grpcStub pointing at the terminated channel, so the connectAgent() that
      // follows fails fast and the run loop falls out of its already-false while (isRunning) test.
      if (shutDownRequested) {
        logger.info { "Skipping gRPC stub creation: shutdown already requested" }
        return@withLock
      }

      logger.info { "Creating gRPC stubs" }

      // Only tear down the channel on reconnect, NOT tracing: tracing/grpcTracing are one-shot lazy
      // fields, so closing tracing here would leave every channel built after the first reconnect
      // intercepting over a closed Tracing. tracing is closed once, in the final shutDown() (finding 8).
      if (grpcStarted)
        shutDownChannelLocked()

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
        buildList {
          if (!options.transportFilterDisabled)
            add(AgentClientInterceptor(agent))
          // Attach the pre-shared token (when set) regardless of transportFilterDisabled, so it also works
          // when the Agent connects through an L7 reverse proxy (nginx) that strips transport info.
          if (options.agentToken.isNotEmpty())
            add(AgentTokenClientInterceptor(options.agentToken))
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
      // Re-throw JVM Errors (OutOfMemoryError, StackOverflowError, etc.) so they propagate to
      // handleConnectionFailure() and terminate the agent instead of being collapsed into a
      // routine "couldn't connect, will retry". Only transient connection failures return false.
      if (e is Error)
        throw e
      agent.metrics { connectCount.labels(agent.launchId, "failure").inc() }
      // Pass the throwable so a stack trace is captured for diagnosis.
      logger.error(e) {
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

  // Sends one heartbeat and classifies the outcome for the caller (Agent.startHeartBeat), which uses the
  // result to keep the connection or tear it down. A NOT_FOUND *response* (proxy evicted this agent) maps
  // to EVICTED; any RPC failure -- DEADLINE_EXCEEDED / UNAVAILABLE / non-gRPC -- maps to TRANSIENT_FAILURE
  // so the caller can force a reconnect after N consecutive failures rather than spinning forever on a
  // half-open transport (finding 2). Cancellation propagates (runCatchingCancellable) for clean shutdown.
  suspend fun sendHeartBeat(): HeartBeatResult {
    val anAgentId = agent.agentId
    if (anAgentId.isEmpty())
      return HeartBeatResult.SUCCESS

    return runCatchingCancellable {
      unaryStub().sendHeartBeat(heartBeatRequest { agentId = anAgentId })
        .let { response ->
          agent.markMsgSent()
          if (response.valid) {
            HeartBeatResult.SUCCESS
          } else {
            logger.warn { "Heartbeat: agentId $anAgentId not found on proxy" }
            HeartBeatResult.EVICTED
          }
        }
    }.getOrElse { e ->
      // Log at WARN with the throwable attached so the stack trace is preserved (finding 13).
      logger.warn(e) { "Heartbeat failure: ${e.simpleClassName} - ${e.message}" }
      HeartBeatResult.TRANSIENT_FAILURE
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
        agent.scrapeRequestBacklogSize += 1
        try {
          connectionContext.sendScrapeRequestAction { agentHttpService.fetchScrapeUrl(grpcRequest) }
        } catch (e: Exception) {
          agent.decrementBacklog(1)
          throw e
        }
      }
  }

  private suspend fun processScrapeResults(
    agent: Agent,
    connectionContext: AgentConnectionContext,
    nonChunkedChannel: Channel<ScrapeResponse>,
    chunkedChannel: Channel<ChunkedScrapeResponse>,
  ) {
    for (scrapeResults in connectionContext.scrapeResults()) {
      try {
        forwardScrapeResult(agent, scrapeResults, nonChunkedChannel, chunkedChannel)
      } catch (e: ClosedSendChannelException) {
        // A sibling writer coroutine closed the response channels (e.g. a transient gRPC failure
        // triggered closeAll()). The fully-computed result can no longer be delivered on this
        // connection, so count it as dropped -- mirroring AgentConnectionContext.sendScrapeResults()
        // -- rather than losing it silently, then propagate so the producer stops.
        agent.metrics { scrapeResultCount.labels(agent.launchId, "dropped").inc() }
        throw e
      }
      agent.markMsgSent()
    }
  }

  private suspend fun forwardScrapeResult(
    agent: Agent,
    scrapeResults: ScrapeResults,
    nonChunkedChannel: Channel<ScrapeResponse>,
    chunkedChannel: Channel<ChunkedScrapeResponse>,
  ) {
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

        // bais is an in-memory ByteArrayInputStream, so read() never blocks; no Dispatchers.IO hop
        // is needed (the enclosing coroutine already runs on IO).
        while (bais.read(buffer).also { readByteCount = it } > 0) {
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
  }

  suspend fun writeResponsesToProxyUntilDisconnected(
    agent: Agent,
    connectionContext: AgentConnectionContext,
  ) {
    val nonChunkedChannel = Channel<ScrapeResponse>(UNLIMITED)
    val chunkedChannel = Channel<ChunkedScrapeResponse>(UNLIMITED)

    fun closeAll() {
      // close() drains N buffered scrape actions; decrement the backlog by that count here too (mirroring
      // Agent.launchConnectionTask's invokeOnCompletion) so the gauge isn't left inflated by N until the
      // next connect resets it (finding 18).
      val drained = connectionContext.close()
      if (drained > 0) agent.decrementBacklog(drained)
      nonChunkedChannel.close()
      chunkedChannel.close()
    }

    // Shared per-stream failure policy: run [block], and on a non-cancellation failure log it (tagged
    // with [name]) while the agent is still running, then tear down the shared channels. Cancellation
    // is propagated without closing (normal shutdown). Factored out of the three coroutines below so
    // the failure handling lives in one place.
    suspend fun runStreamTask(
      name: String,
      block: suspend () -> Unit,
    ) {
      runCatchingCancellable { block() }
        .onFailure { e ->
          if (agent.isRunning)
            logger.logStreamFailure(name, e)
          if (e !is CancellationException) closeAll()
        }
    }

    coroutineScope {
      // Ends by connectionContext.close()
      launch(Dispatchers.IO) {
        try {
          runStreamTask("processScrapeResults") {
            processScrapeResults(agent, connectionContext, nonChunkedChannel, chunkedChannel)
          }
        } finally {
          // Close channels when the producer finishes so consumers' consumeAsFlow() will complete.
          // This is the sole owner of channel lifecycle — no outer finally needed.
          nonChunkedChannel.close()
          chunkedChannel.close()
        }
      }

      // Ends by disconnection from server
      launch(Dispatchers.IO) {
        runStreamTask("writeResponsesToProxy") {
          grpcStub.writeResponsesToProxy(nonChunkedChannel.consumeAsFlow())
        }
      }

      launch(Dispatchers.IO) {
        runStreamTask("writeChunkedResponsesToProxy") {
          grpcStub.writeChunkedResponsesToProxy(chunkedChannel.consumeAsFlow())
        }
      }
    }
  }

  companion object {
    private val logger = logger {}
    private const val DEFAULT_GRPC_PORT = 50051

    // Max time to wait for the gRPC channel to terminate after shutdownNow() (finding 28).
    private const val CHANNEL_TERMINATION_TIMEOUT_SECS = 5L

    // Case-insensitive counterpart to String.removePrefix, so the proxy-hostname scheme strip matches
    // BaseOptions.isUrlPrefix's case-insensitivity (finding 42). No-ops when the prefix is absent.
    private fun String.removePrefixIgnoreCase(prefix: String): String =
      if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this
  }
}
