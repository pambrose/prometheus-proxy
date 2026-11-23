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

import kotlin.concurrent.atomics.AtomicBoolean
import com.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import com.pambrose.common.dsl.GrpcDsl.channel
import com.pambrose.common.util.hostInfo
import com.pambrose.common.util.runCatchingCancellable
import io.prometheus.common.MetricBuilders.metricLabel
import com.pambrose.common.util.simpleClassName
import com.pambrose.common.utils.TlsContext
import com.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.google.protobuf.ByteString.copyFrom
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusException
import io.prometheus.Agent
import io.prometheus.agent.AgentOptions.Companion.DEFAULT_GRPC_PORT
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.DeferredClientInterceptor
import io.prometheus.common.Messages.BLANK_AGENT_ID_MSG
import io.prometheus.common.Messages.BLANK_PATH_MSG
import io.prometheus.common.ScrapeResults
import io.prometheus.common.TlsMaterial
import io.prometheus.common.Utils.failureKey
import io.prometheus.common.Utils.logStreamFailure
import io.prometheus.common.Utils.HostPort
import io.prometheus.common.Utils.parseEndpointList
import io.prometheus.common.receiveCatchingWithin
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.RegisterPathRequestKt
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
import io.prometheus.grpc.scrapeResponse
import io.prometheus.grpc.summaryData
import io.prometheus.grpc.unregisterPathRequest
import kotlin.concurrent.atomics.AtomicInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import kotlin.concurrent.atomics.plusAssign
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

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
 * How agent_scrape_result_count_total counts one result: as its [type] once gRPC takes the result's last message to
 * send, or as dropped once a message of it can't be, the connection having ended first; whichever comes first, once.
 * It holds the label alone: holding the result kept its body in memory as long as a message of it was queued.
 */
internal class ResultCount(
  private val type: String,
) {
  private val counted = AtomicBoolean(false)

  fun sent(agent: Agent) = count(agent, type)

  fun dropped(agent: Agent) = count(agent, AgentMetrics.RESULT_DROPPED)

  private fun count(
    agent: Agent,
    countedAs: String,
  ) {
    if (counted.compareAndSet(expectedValue = false, newValue = true))
      agent.countScrapeResult(countedAs)
  }
}

/**
 * A message for the proxy, with the [count] of the result it belongs to, null for a dashboard's scrape, which the
 * metric leaves out. A chunked result's messages share one; only its [last], the summary, counts it as sent.
 */
internal class Outgoing<T>(
  private val message: T,
  private val count: ResultCount?,
  private val last: Boolean = true,
) {
  /** The message, as gRPC takes it to send. */
  fun sent(agent: Agent): T = message.also { if (last) count?.sent(agent) }

  /** The connection ended with the message unsent. */
  fun dropped(agent: Agent) = count?.dropped(agent)
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
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val grpcLock = ReentrantLock()
  private var grpcStarted by atomicBoolean(false)

  // How long the response streams go without a result before they send a keepalive, as the proxy this connection
  // registered with says in its answer to registerAgent. INFINITE when it gives 0: keepalives off, or a proxy predating
  // them, which would take one for a result.
  @Volatile
  private var responseKeepalive = Duration.INFINITE

  // Latched by shutDownChannelPermanently() and never cleared: the agent is stopping for good, so no
  // further channel may be created. Read and written only under grpcLock, which is what makes the
  // stop-vs-reconnect ordering decisive rather than racy.
  private var shutDownRequested by atomicBoolean(false)

  // Installed on every channel a reconnect builds, but built once, on the first call: see Agent.tracing.
  private val tracingInterceptor =
    DeferredClientInterceptor { checkNotNull(agent.tracing).grpcTracing.newClientInterceptor() }

  internal lateinit var grpcStub: ProxyServiceGrpcKt.ProxyServiceCoroutineStub

  // Deadline applied to all unary RPCs. Set to 0 to disable (e.g., in tests).
  internal var unaryDeadlineSecs = options.unaryDeadlineSecs.toLong()

  private fun unaryStub() = stubWithDeadline(unaryDeadlineSecs)

  private fun stubWithDeadline(deadlineSecs: Long) =
    if (deadlineSecs > 0) grpcStub.withDeadlineAfter(deadlineSecs, SECONDS) else grpcStub

  lateinit var channel: ManagedChannel

  // Ordered failover endpoints, parsed once. Never empty -- parseEndpointList throws on a spec that
  // yields none, and AgentOptions already validated the same string at startup. A single-endpoint list
  // (the overwhelmingly common case) makes every rotation call below a no-op, which is what keeps the
  // pre-failover behavior byte-for-byte intact.
  private val endpoints: List<HostPort> = parseEndpointList(options.proxyHostname, DEFAULT_GRPC_PORT)

  // Cursor into [endpoints]. Written only under grpcLock, but read without it from Agent.proxyHost on
  // the run loop (inside log lambdas), hence @Volatile. The list itself is immutable, so publishing the
  // index safely is enough to publish the selection.
  @Volatile
  private var endpointIndex = 0

  // Read the volatile index ONCE. Two independent reads (one for host, one for port) let a rotation
  // land between them, so a concurrent reader -- the /debug servlet runs on a Jetty thread -- could
  // render a host:port pair that was never configured.
  val currentEndpoint: HostPort get() = endpoints[endpointIndex]

  // Each proxy endpoint's connect failure, until a connect to it succeeds: the failure logged at WARN last, as
  // Utils.failureKey gives it, and when, and the latest one, for the agent's dashboard, which shows an agent that can't
  // reach its proxy, as the proxy's never can. A proxy that stays down, or a dead primary in a failover list, logs its
  // failure once and then at DEBUG (October #17), with a WARN again every CONNECT_FAILURE_REMINDER_INTERVAL while it
  // lasts, since a failure that won't clear by itself, such as a refused token, otherwise sat in the log's first lines.
  // The endpoints are the configured list, so this stays as small.
  private val connectFailures = ConcurrentHashMap<String, LoggedFailure>()

  // [connects] is how many connects had succeeded when the activity feed was last told of the failure.
  private class LoggedFailure(
    val key: String,
    val warnedAt: TimeMark,
    val latest: ShownFailure,
    val connects: Int,
  )

  /** The latest failure to connect to each proxy endpoint, by its `host:port`, until a connect to it succeeds. */
  val connectFailuresByEndpoint: Map<String, ShownFailure> get() = connectFailures.mapValues { it.value.latest }

  // Whether the agent's latest connect attempt failed, for the agent's dashboard: an endpoint's failure stays until
  // that endpoint connects, so after a failover the failures left behind don't say the agent is failing now.
  @Volatile
  var lastConnectFailed = false
    private set

  // The connects that succeeded, for the agent's dashboard.
  private val successfulConnects = AtomicInt(0)

  /** How many times the agent has connected to a proxy. */
  val connects: Int get() = successfulConnects.load()

  /** How the agent reaches its proxy: plaintext, TLS, or mutual TLS, as its connect log lines say it. */
  val tlsDescription: String get() = tlsContext.desc()

  val agentHostName: String get() = currentEndpoint.host
  val agentPort: Int get() = currentEndpoint.port

  /** All configured endpoints, in priority order, as `host:port` specs. Reported to the proxy at registration. */
  val endpointSpecs: List<String> get() = endpoints.map { it.spec }

  /** Index into [endpointSpecs] of the endpoint currently in use. */
  val currentEndpointIndex: Int get() = endpointIndex

  /** Endpoints in priority order for startup logging, or null when failover is not configured. */
  val failoverEndpointsDesc: String?
    get() = if (endpoints.size > 1) endpoints.joinToString(", ") { it.spec } else null

  /**
   * Advances to the next endpoint, wrapping at the end, and reports whether the selection changed.
   *
   * Called after a *failed connect*. A `false` return (a single-endpoint list) tells the caller not to
   * rebuild the channel, so a non-failover agent does exactly what it always did.
   */
  fun advanceEndpoint(): Boolean =
    grpcLock.withLock {
      if (endpoints.size <= 1)
        return@withLock false
      val from = currentEndpoint.spec
      val leavingPrimary = endpointIndex == 0
      endpointIndex = (endpointIndex + 1) % endpoints.size
      logger.info { "Trying next proxy endpoint: ${currentEndpoint.spec}" }
      agent.metrics { endpointAdvanceCount.labelValues(agent.launchId).inc() }
      // Leaving the primary is the news for the activity feed; cycling on through the backups in the same outage isn't.
      if (leavingPrimary)
        agent.activityLog?.record(
          AgentActivityKind.FAILED_OVER,
          detail = "from $from to ${currentEndpoint.spec}",
          group = from,
        )
      true
    }

  /**
   * Returns to the first endpoint.
   *
   * Called after a connection that had succeeded and then dropped, which is what makes the list a
   * priority order rather than a ring: the next cycle re-probes the primary, so recovery of a preferred
   * proxy is picked up without a health prober, a timer, or any per-endpoint state.
   */
  fun resetEndpoint() =
    grpcLock.withLock {
      if (endpointIndex != 0) {
        endpointIndex = 0
        logger.info { "Returning to primary proxy endpoint: ${currentEndpoint.spec}" }
        agent.activityLog?.record(AgentActivityKind.FAILED_BACK, detail = "to ${currentEndpoint.spec}")
      }
    }

  private val tlsContext: TlsContext

  init {
    tlsContext =
      agent.options.run {
        if (isTlsEnabled || hasTrustCertCollection)
          TlsMaterial.clientContext(
            certChain = certChainSource,
            privateKey = privateKeySource,
            trust = trustCertCollectionSource,
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
    shutDownChannelLocked()
  }

  fun shutDown() =
    grpcLock.withLock {
      shutDownLocked()
    }

  // Shuts the gRPC channel down for good, without touching tracing, breaking any in-flight RPCs (e.g. an idle
  // readRequestsFromProxy collect). Called from Agent.triggerShutdown() so stopSync() can unblock the run loop
  // (finding 1). Guava invokes triggerShutdown() exactly once, so tearing the current channel down is not enough on
  // its own: a run-loop iteration that read `while (isRunning)` as true just before stopAsync() flipped it can still
  // reach resetGrpcStubs() and build a fresh live channel that nothing will ever break, parking run() in an idle
  // collect and reinstating the deadlock. Latching shutDownRequested under grpcLock makes the outcome the same either
  // way the race lands: reset-then-stop tears the new channel down, stop-then-reset never creates it.
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

      // Only tear down the channel on reconnect, NOT tracing: Agent.tracing is one-shot, so closing it here
      // would leave every channel built after the first reconnect intercepting over a closed Tracing. The
      // agent closes it once, as it stops (finding 8).
      if (grpcStarted)
        shutDownChannelLocked()

      val endpoint = currentEndpoint
      channel =
        channel(
          hostName = endpoint.host,
          port = endpoint.port,
          // Retry, which with no retry policy gives only transparent retries, makes gRPC buffer each message a call
          // sends until the call commits. A client stream commits once that buffer passes its per-RPC limit, counted
          // from the message sizes the transport reports, and the in-process transport reports none, so in-process
          // the agent's result streams kept every message they sent for the whole connection. In-process has no
          // network for a transparent retry to help with, so retry is on only for a real transport (November #43).
          enableRetry = inProcessServerName.isEmpty(),
          tlsContext = tlsContext,
          overrideAuthority = agent.options.overrideAuthority,
          inProcessServerName = inProcessServerName,
        ) {
          if (agent.isZipkinEnabled)
            intercept(tracingInterceptor)

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
  //
  // Reads options.transportFilterDisabled directly rather than taking it as a parameter. The same field
  // already decides whether resetGrpcStubs() installs AgentClientInterceptor, and when the caller passed the
  // value in, the two reads could disagree: Agent.run() sourced it from configVals, which carries only the
  // config-file value, so an agent given --tf_disabled on the command line sent this RPC in transport-filter
  // mode while omitting the interceptor, and the proxy rejected it as a config mismatch. One read cannot
  // disagree with itself.
  suspend fun connectAgent(): Boolean {
    val proxyHost = agent.proxyHost
    return runCatchingCancellable {
      if (connectFailures.containsKey(proxyHost))
        logger.debug { "Connecting to proxy at $proxyHost using ${tlsContext.desc()} again..." }
      else
        logger.info { "Connecting to proxy at $proxyHost using ${tlsContext.desc()}..." }
      if (options.transportFilterDisabled)
        unaryStub().connectAgentWithTransportFilterDisabled(EMPTY_INSTANCE).also { agent.agentId = it.agentId }
      else
        unaryStub().connectAgent(EMPTY_INSTANCE)

      connectFailures.remove(proxyHost)
      lastConnectFailed = false
      successfulConnects += 1
      logger.info { "Connected to proxy at $proxyHost using ${tlsContext.desc()}" }
      agent.activityLog?.record(AgentActivityKind.CONNECTED, detail = proxyHost, group = proxyHost)
      true
    }.getOrElse { e ->
      // Re-throw JVM Errors (OutOfMemoryError, StackOverflowError, etc.) so they propagate to
      // handleConnectionFailure() and terminate the agent instead of being collapsed into a
      // routine "couldn't connect, will retry". Only transient connection failures return false.
      if (e is Error)
        throw e
      // Compared by gRPC status code, or by class without one, since a deadline's description changes every attempt
      // (November #9).
      val failure = failureKey(e)
      val message =
        "Cannot connect to proxy at $proxyHost using ${tlsContext.desc()} - ${e.simpleClassName}: ${e.message}"
      val latest = ShownFailure.of(e)
      lastConnectFailed = true
      // The first failure, and a change, at WARN with the stack trace for diagnosis; a repeat at DEBUG, and at WARN
      // again, without the stack trace, once the last WARN is CONNECT_FAILURE_REMINDER_INTERVAL old.
      val last = connectFailures[proxyHost]
      // The activity feed is told of a failure when it is new or changes, as the log's WARN is, and again once a
      // connect has succeeded since, elsewhere, as when the agent fails back to a primary still down; its repeats would
      // fill the feed.
      val connects = successfulConnects.load()
      if (last?.key != failure || last.connects != connects)
        agent.activityLog?.record(
          AgentActivityKind.CONNECT_FAILED,
          detail = "$proxyHost: ${latest.message}",
          group = "$proxyHost $failure",
        )
      when {
        last?.key != failure -> {
          connectFailures[proxyHost] = LoggedFailure(failure, timeSource.markNow(), latest, connects)
          logger.warn(e) { message }
        }

        last.warnedAt.elapsedNow() >= CONNECT_FAILURE_REMINDER_INTERVAL -> {
          connectFailures[proxyHost] = LoggedFailure(failure, timeSource.markNow(), latest, connects)
          logger.warn { "$message (still failing, retrying every few seconds)" }
        }

        else -> {
          connectFailures[proxyHost] = LoggedFailure(failure, last.warnedAt, latest, connects)
          logger.debug { message }
        }
      }
      false
    }
  }

  suspend fun registerAgent(initialConnectionLatch: CountDownLatch) {
    val request =
      registerAgentRequest {
        require(agent.agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }
        agentId = agent.agentId
        launchId = agent.launchId
        agentName = agent.agentName
        // This agent's OWN host, not agentHostName -- which is the proxy endpoint being dialed. The
        // proxy stores this on AgentContext and publishes it as the reserved `hostName` service-discovery
        // label, documented as "hostnames of agents serving this path", so reporting the proxy's host
        // made the label identical across every target and useless for identifying anything. It matters
        // more with failover: hostName participates in Prometheus target identity, so a value that
        // tracked the current endpoint would change targets on every failover and churn the series.
        hostName = hostInfo.hostName
        consolidated = agent.options.consolidated
        // Reported here rather than on a heartbeat because registration is exactly when this changes:
        // a failover is a reconnect, so the proxy learns the index at the moment it becomes true.
        proxyEndpoints.addAll(this@AgentGrpcService.endpointSpecs)
        currentEndpointIndex = this@AgentGrpcService.currentEndpointIndex
        // Every connection reads them; see readPathNoticesFromProxy.
        pathNotices = true
        // readRequestsFromProxy skips them.
        streamKeepalives = true
      }
    unaryStub().registerAgent(request)
      .also { response ->
        agent.markMsgSent()
        if (!response.valid)
          throw RequestFailureException.fromProxy("registerAgent", response.reason)
        responseKeepalive =
          if (response.streamKeepaliveMillis > 0) response.streamKeepaliveMillis.milliseconds else Duration.INFINITE
      }
    initialConnectionLatch.countDown()
  }

  suspend fun pathMapSize(): Int =
    unaryStub().pathMapSize(
      pathMapSizeRequest {
        require(agent.agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }
        agentId = agent.agentId
      },
    ).run {
      agent.markMsgSent()
      pathCount
    }

  suspend fun registerPathOnProxy(
    pathVal: String,
    labelsJson: String,
    urlVal: String,
    sourceVal: String,
  ): RegisterPathResponse =
    sendRegisterPath(pathVal) {
      labels = labelsJson
      // The target and its origin are known only here. Reported at registration because that is the
      // one moment they are established -- a path is re-registered rather than mutated in place.
      // Redacted here: the proxy shows the target on its dashboard and /debug page.
      targetUrl = sanitizeUrl(urlVal)
      pathSource = sourceVal
    }

  // A registration of a path the proxy said another agent took over, which it refuses while a live agent of another
  // launch serves it; see AgentPathManager.notePathNotice. Otherwise as registerPathOnProxy.
  suspend fun reclaimPathOnProxy(
    pathVal: String,
    labelsJson: String,
    urlVal: String,
    sourceVal: String,
  ): RegisterPathResponse =
    sendRegisterPath(pathVal) {
      labels = labelsJson
      targetUrl = sanitizeUrl(urlVal)
      pathSource = sourceVal
      reclaim = true
    }

  // Registers [pathVal] for this agent, with what [fill] sets, and throws the proxy's refusal.
  private suspend fun sendRegisterPath(
    pathVal: String,
    fill: RegisterPathRequestKt.Dsl.() -> Unit,
  ): RegisterPathResponse =
    unaryStub().registerPath(
      registerPathRequest {
        require(agent.agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }
        require(pathVal.isNotBlank()) { BLANK_PATH_MSG }
        agentId = agent.agentId
        path = pathVal
        fill()
      },
    ).apply {
      agent.markMsgSent()
      if (!valid)
        throw RequestFailureException.fromProxy("registerPathOnProxy", reason, rejectionCause)
    }

  suspend fun unregisterPathOnProxy(pathVal: String): UnregisterPathResponse =
    unaryStub().unregisterPath(
      unregisterPathRequest {
        require(agent.agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }
        require(pathVal.isNotBlank()) { BLANK_PATH_MSG }
        agentId = agent.agentId
        path = pathVal
      },
    ).apply {
      agent.markMsgSent()
      if (!valid)
        throw RequestFailureException.fromProxy("unregisterPathOnProxy", reason)
    }

  // Sends one heartbeat and classifies the outcome for the caller (Agent.startHeartBeat), which uses the
  // result to keep the connection or tear it down. A NOT_FOUND *response* (proxy evicted this agent) maps
  // to EVICTED; any RPC failure -- DEADLINE_EXCEEDED / UNAVAILABLE / non-gRPC -- maps to TRANSIENT_FAILURE
  // so the caller can force a reconnect after N consecutive failures rather than spinning forever on a
  // half-open transport (finding 2). Cancellation propagates (runCatchingCancellable) for clean shutdown.
  // Takes its own deadline (see Agent.heartbeatDeadlineSecs) so a half-open connection is detected within a few
  // heartbeat intervals rather than several unary deadlines.
  suspend fun sendHeartBeat(deadlineSecs: Long = unaryDeadlineSecs): HeartBeatResult {
    val anAgentId = agent.agentId
    if (anAgentId.isEmpty())
      return HeartBeatResult.SUCCESS

    return runCatchingCancellable {
      stubWithDeadline(deadlineSecs).sendHeartBeat(heartBeatRequest { agentId = anAgentId })
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
    }.also { result -> agent.metrics { heartbeatCount.labelValues(agent.launchId, result.metricLabel).inc() } }
  }

  // Hands the path manager each notice the proxy sends about a path another agent took from this one, for the
  // connection's lifetime. A proxy predating notices answers UNIMPLEMENTED: this then waits out the connection, since a
  // task that ends ends the connection, and the agent would reconnect to that proxy without end. A path taken over
  // there stays shown as registered, as before. Any other end ends the connection, as the scrape request stream's does.
  suspend fun readPathNoticesFromProxy() {
    val agentInfo =
      agentInfo {
        require(agent.agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }
        agentId = agent.agentId
      }
    try {
      grpcStub.readPathNoticesFromProxy(agentInfo).collect { notice -> agent.pathManager.notePathNotice(notice) }
    } catch (e: StatusException) {
      if (e.status.code != Status.Code.UNIMPLEMENTED)
        throw e
      logger.info { "Proxy $agentHostName predates path notices; a path another agent takes over stays shown here" }
      awaitCancellation()
    }
  }

  suspend fun readRequestsFromProxy(
    agentHttpService: AgentHttpService,
    connectionContext: AgentConnectionContext,
  ) {
    val agentInfo =
      agentInfo {
        require(agent.agentId.isNotBlank()) { BLANK_AGENT_ID_MSG }
        agentId = agent.agentId
      }
    grpcStub.readRequestsFromProxy(agentInfo)
      .collect { grpcRequest: ScrapeRequest ->
        // The proxy keeping the stream open: nothing to scrape.
        if (grpcRequest.keepalive)
          return@collect
        // The actual fetch happens at the other end of the channel, not here.
        // Not the whole message: a scrape request carries the Authorization header Prometheus sent.
        logger.debug { "readRequestsFromProxy(): scrapeId=${grpcRequest.scrapeId} path=${grpcRequest.path}" }
        agent.scrapeRequestBacklogSize += 1
        val received = TimeSource.Monotonic.markNow()
        try {
          connectionContext.sendScrapeRequestAction {
            // How long it waited for one of maxConcurrentHttpClients; a dashboard's scrape is left out, as from the
            // agent's other scrape metrics.
            if (!grpcRequest.dashboardScrape) {
              val waited = received.elapsedNow().toDouble(DurationUnit.SECONDS)
              agent.metrics { scrapeQueueWait.labelValues(agent.launchId).observe(waited) }
            }
            agentHttpService.fetchScrapeUrl(grpcRequest)
          }
        } catch (e: Exception) {
          agent.decrementBacklog(1)
          throw e
        }
      }
  }

  private suspend fun processScrapeResults(
    agent: Agent,
    connectionContext: AgentConnectionContext,
    nonChunkedChannel: Channel<Outgoing<ScrapeResponse>>,
    chunkedChannel: Channel<Outgoing<ChunkedScrapeResponse>>,
  ) {
    for (scrapeResults in connectionContext.scrapeResults()) {
      // Throws ClosedSendChannelException once a sibling writer coroutine has closed the response channels (e.g. a
      // transient gRPC failure triggered closeAll()), which stops the producer. The result can no longer be delivered
      // on this connection; the channel counts it as dropped (outgoingChannel).
      forwardScrapeResult(scrapeResults, nonChunkedChannel, chunkedChannel)
      agent.markMsgSent()
    }
  }

  private suspend fun forwardScrapeResult(
    scrapeResults: ScrapeResults,
    nonChunkedChannel: Channel<Outgoing<ScrapeResponse>>,
    chunkedChannel: Channel<Outgoing<ChunkedScrapeResponse>>,
  ) {
    val scrapeId = scrapeResults.srScrapeId

    fun countAs(type: String) = if (scrapeResults.dashboardScrape) null else ResultCount(type)

    if (!scrapeResults.srZipped) {
      logger.debug { "Writing non-chunked msg scrapeId: $scrapeId length: ${scrapeResults.srContentAsText.length}" }
      nonChunkedChannel.send(Outgoing(scrapeResults.toScrapeResponse(), countAs(AgentMetrics.RESULT_NON_GZIPPED)))
    } else {
      val zipped = scrapeResults.srContentAsZipped
      val chunkContentSize = options.chunkContentSizeBytes

      logger.debug { "Comparing ${zipped.size} and $chunkContentSize" }

      if (!scrapeResults.isChunked(chunkContentSize)) {
        logger.debug { "Writing zipped non-chunked msg scrapeId: $scrapeId length: ${zipped.size}" }
        nonChunkedChannel.send(Outgoing(scrapeResults.toScrapeResponse(), countAs(AgentMetrics.RESULT_GZIPPED)))
      } else {
        val count = countAs(AgentMetrics.RESULT_CHUNKED)
        scrapeResults.toScrapeResponseHeader()
          .also {
            logger.debug { "Writing header length: ${zipped.size} for scrapeId: $scrapeId " }
            chunkedChannel.send(Outgoing(it, count, last = false))
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
            chunkedChannel.send(Outgoing(it, count, last = false))
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
          chunkedChannel.send(Outgoing(it, count))
        }
      }
    }
  }

  // The messages of [channel], for one of the response streams, and [keepalive] after responseKeepalive with none.
  // Consumes [channel] as consumeAsFlow does: once the flow ends, by its end or a failure, what's left in it is
  // dropped. A message that comes as a wait ends isn't lost (see receiveCatchingWithin).
  private fun <T> streamOf(
    channel: ReceiveChannel<Outgoing<T>>,
    agent: Agent,
    keepalive: T,
  ): Flow<T> =
    flow {
      channel.consume {
        while (true) {
          val next = channel.receiveCatchingWithin(responseKeepalive)
          when {
            next == null -> emit(keepalive)
            next.isClosed -> next.exceptionOrNull()?.let { throw it } ?: break
            else -> emit(next.getOrThrow().sent(agent))
          }
        }
      }
    }

  suspend fun writeResponsesToProxyUntilDisconnected(
    agent: Agent,
    connectionContext: AgentConnectionContext,
  ) {
    val nonChunkedChannel = outgoingChannel<ScrapeResponse>(agent)
    val chunkedChannel = outgoingChannel<ChunkedScrapeResponse>(agent)

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
          grpcStub.writeResponsesToProxy(streamOf(nonChunkedChannel, agent, KEEPALIVE_RESPONSE))
        }
      }

      launch(Dispatchers.IO) {
        runStreamTask("writeChunkedResponsesToProxy") {
          grpcStub.writeChunkedResponsesToProxy(streamOf(chunkedChannel, agent, KEEPALIVE_CHUNKED_RESPONSE))
        }
      }
    }
  }

  companion object {
    private val logger = logger {}

    private val KEEPALIVE_RESPONSE: ScrapeResponse = scrapeResponse { keepalive = true }
    private val KEEPALIVE_CHUNKED_RESPONSE: ChunkedScrapeResponse = chunkedScrapeResponse { keepalive = true }

    /**
     * A channel of messages for the proxy that counts what it can't deliver as dropped: what's queued when a writer's
     * stream fails, which gRPC cancels, discarding it, and a message sent after the channel closed.
     */
    internal fun <T> outgoingChannel(agent: Agent): Channel<Outgoing<T>> =
      Channel(UNLIMITED, onUndeliveredElement = { it.dropped(agent) })

    // Max time to wait for the gRPC channel to terminate after shutdownNow() (finding 28).
    private const val CHANNEL_TERMINATION_TIMEOUT_SECS = 5L

    // How often a connect failure that persists is logged at WARN again.
    private val CONNECT_FAILURE_REMINDER_INTERVAL = 10.minutes
  }
}
