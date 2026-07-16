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

package io.prometheus

import com.codahale.metrics.health.HealthCheck
import com.google.common.util.concurrent.RateLimiter
import com.pambrose.common.concurrent.await
import com.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.pambrose.common.dsl.GuavaDsl.toStringElements
import com.pambrose.common.dsl.MetricsDsl.healthCheck
import com.pambrose.common.service.GenericService
import com.pambrose.common.servlet.LambdaServlet
import com.pambrose.common.time.format
import com.pambrose.common.util.Version
import com.pambrose.common.util.getBanner
import com.pambrose.common.util.hostInfo
import com.pambrose.common.util.randomId
import com.pambrose.common.util.runCatchingCancellable
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.prometheus.agent.AgentConnectionContext
import io.prometheus.agent.AgentGrpcService
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.AgentOptions
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.agent.HeartBeatResult
import io.prometheus.agent.RequestFailureException
import io.prometheus.client.Histogram
import io.prometheus.common.BaseOptions.Companion.DEBUG
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.Utils.getVersionDesc
import io.prometheus.common.Utils.logStreamFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.update
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

/**
 * Prometheus Agent that connects to a Proxy to enable metrics scraping across firewalls.
 *
 * The Agent is the component that runs inside the firewall alongside the services being monitored.
 * It establishes an outbound connection to the Proxy, registers the metrics endpoints it can scrape,
 * and responds to scrape requests by fetching metrics from the actual endpoints and returning them
 * to the Proxy.
 *
 * ## Architecture
 *
 * The Agent maintains a persistent gRPC connection to the Proxy and handles:
 * - **Connection Management**: Establishes and maintains connection to proxy with automatic reconnection
 * - **Path Registration**: Registers configured metrics endpoints with the proxy
 * - **Scrape Processing**: Responds to scrape requests by fetching metrics from local endpoints
 * - **Concurrent Scraping**: Handles multiple concurrent scrape requests with configurable limits
 * - **Heartbeat**: Sends periodic heartbeat messages to maintain connection health
 * - **Metrics Collection**: Exposes operational metrics about the agent itself
 *
 * ## Configuration
 *
 * Agents are configured via HOCON configuration files that specify:
 * - Proxy connection details (hostname, port, TLS settings)
 * - Path configurations (endpoints to scrape and their metadata)
 * - Operational parameters (timeouts, concurrency limits, retry policies)
 * - Admin and metrics endpoints for monitoring the agent itself
 *
 * ## Usage Examples
 *
 * ### Basic Usage
 * ```kotlin
 * val agent = Agent(AgentOptions(args))
 * agent.startSync()
 * ```
 *
 * ### Embedded Usage
 * ```kotlin
 * val agentInfo = Agent.startAsyncAgent("config.conf", true)
 * println("Started agent: ${agentInfo.agentName}")
 * ```
 *
 * ### With Custom Initialization
 * ```kotlin
 * val agent = Agent(options) {
 *     // Custom initialization logic
 *     logger.info { "Agent initialized with custom settings" }
 * }
 * agent.startSync()
 * ```
 *
 * ## Connection Lifecycle
 *
 * 1. **Startup**: Agent reads configuration and initializes services
 * 2. **Connection**: Establishes gRPC connection to proxy
 * 3. **Registration**: Registers configured paths with proxy
 * 4. **Operation**: Processes scrape requests and sends heartbeats
 * 5. **Reconnection**: Automatically reconnects on connection failures
 * 6. **Shutdown**: Gracefully closes connections and cleans up resources
 *
 * ## Error Handling
 *
 * The Agent includes robust error handling for:
 * - Network connectivity issues with exponential backoff
 * - Individual scrape failures without affecting other scrapes
 * - Configuration errors with detailed error messages
 * - Resource exhaustion with proper cleanup
 *
 * @param options Configuration options for the agent, typically loaded from command line or config files
 * @param inProcessServerName Optional in-process server name for testing scenarios. When specified,
 *                           the agent will connect to an in-process gRPC server instead of a network server.
 * @param testMode Whether to run in test mode. Test mode may disable certain features or use
 *                 different defaults suitable for testing environments.
 * @param initBlock Optional initialization block executed after construction but before startup.
 *                  Useful for custom configuration or testing setup.
 *
 * @since 1.0.0
 * @see AgentOptions for configuration details
 * @see Proxy for the corresponding proxy component
 * @see EmbeddedAgentInfo for information about embedded agent instances
 */
@Version(
  version = BuildConfig.APP_VERSION,
  releaseDate = BuildConfig.APP_RELEASE_DATE,
  buildTime = BuildConfig.BUILD_TIME,
)
class Agent(
  val options: AgentOptions,
  inProcessServerName: String = "",
  testMode: Boolean = false,
  initBlock: (Agent.() -> Unit)? = null,
) : GenericService<ConfigVals>(
  configVals = options.configVals,
  adminConfig = newAdminConfig(options.adminEnabled, options.adminPort, options.configVals.agent.admin),
  metricsConfig = newMetricsConfig(options.metricsEnabled, options.metricsPort, options.configVals.agent.metrics),
  zipkinConfig = newZipkinConfig(options.configVals.agent.internal.zipkin),
  versionBlock = { getVersionDesc(true) },
  isTestMode = testMode,
) {
  private val clock = Monotonic
  internal val agentHttpService = AgentHttpService(this)
  private val initialConnectionLatch = CountDownLatch(1)
  private val initialPathsRegisteredLatch = CountDownLatch(1)

  // Primed (one acquire) at construction. internal + var so tests can substitute a mock to verify
  // reconnect pacing (finding 3). reconnectPauseSecs is validated > 0 in AgentOptions (finding 11).
  internal var reconnectLimiter: RateLimiter =
    RateLimiter.create(1.0 / agentConfigVals.internal.reconnectPauseSecs).apply { acquire() }
  private var lastMsgSentMark: TimeMark by nonNullableReference(clock.markNow())

  internal val agentName = options.agentName.ifBlank { "Unnamed-${hostInfo.hostName}" }
  internal val scrapeRequestBacklogSize = AtomicInt(0)

  /**
   * Atomically decrements scrapeRequestBacklogSize by [delta], clamping at zero.
   *
   * During disconnect, both connectionContext.close() (which drains buffered channel items)
   * and individual scrape coroutine finally blocks can race to decrement the counter for
   * overlapping items. This CAS loop prevents the counter from going negative.
   */
  internal fun decrementBacklog(delta: Int) {
    scrapeRequestBacklogSize.update { maxOf(it - delta, 0) }
  }

  internal val pathManager = AgentPathManager(this)
  internal val grpcService = AgentGrpcService(this, options, inProcessServerName)
  internal var agentId: String by nonNullableReference("")
  internal val launchId = randomId(15)
  internal val metrics by lazy { AgentMetrics(this) }

  internal val agentConfigVals: ConfigVals.Agent get() = configVals.agent

  init {
    fun toPlainText() =
      """
        Prometheus Agent Info [${getVersionDesc(false)}]

        Uptime:    ${upTime.format(true)}
        AgentId:   $agentId
        AgentName: $agentName
        ProxyHost: $proxyHost

        Admin Service:
        ${if (isAdminEnabled) servletService.toString() else "Disabled"}

        Metrics Service:
        ${if (isMetricsEnabled) metricsService.toString() else "Disabled"}

      """.trimIndent()

    logger.info { "Agent name: $agentName" }
    logger.info { "Proxy reconnect pause time: ${agentConfigVals.internal.reconnectPauseSecs.seconds}" }
    logger.info { "Scrape timeout time: ${options.scrapeTimeoutSecs.seconds}" }

    initServletService {
      if (options.debugEnabled) {
        logger.info { "Adding /$DEBUG endpoint" }
        addServlet(
          path = DEBUG,
          servlet = LambdaServlet { listOf(toPlainText(), pathManager.toPlainText()).joinToString("\n") },
        )
      }
    }

    initBlock?.invoke(this)
  }

  override fun run() {
    suspend fun connectToProxy() {
      // Reset gRPC stubs if the previous iteration had a successful connection, i.e., the agentId != ""
      if (agentId.isNotEmpty()) {
        grpcService.resetGrpcStubs()
        logger.info { "Resetting agentId" }
        agentId = ""
      }

      // Reset values for each connection attempt
      pathManager.clear()
      // Note: scrapeRequestBacklogSize is reset here to ensure a clean state for the new connection.
      // It is safe because the coroutineScope below (and in previous calls) guarantees that all
      // child scrape coroutines from any previous connection have completed before we reach this point.
      scrapeRequestBacklogSize.store(0)
      lastMsgSentMark = clock.markNow()

      if (grpcService.connectAgent(configVals.agent.transportFilterDisabled)) {
        grpcService.registerAgent(initialConnectionLatch)
        pathManager.registerPaths()
        // Signal that any config-driven paths have been registered. CountDownLatch(1) ignores
        // subsequent countDowns, so only the first successful connect cycle matters.
        initialPathsRegisteredLatch.countDown()

        // Close connectionContext when disconnected from the server or the service is shutdown and isRunning is false
        val connectionContext =
          AgentConnectionContext(
            agentConfigVals.internal.scrapeRequestBacklogUnhealthySize * BACKLOG_CAPACITY_MULTIPLIER,
          )

        coroutineScope {
          // Each task runs for the connection's lifetime; on completion launchConnectionTask closes
          // the shared connectionContext and adjusts the backlog. Ends on disconnect/shutdown.
          launchConnectionTask(connectionContext, "readRequestsFromProxy") {
            grpcService.readRequestsFromProxy(agentHttpService, connectionContext)
          }

          launchConnectionTask(connectionContext, "startHeartBeat") {
            startHeartBeat(connectionContext)
          }

          launchConnectionTask(connectionContext, "writeResponsesToProxyUntilDisconnected") {
            grpcService.writeResponsesToProxyUntilDisconnected(this@Agent, connectionContext)
            logger.info { "writeResponsesToProxyUntilDisconnected() completed" }
          }

          launchConnectionTask(connectionContext, "scrapeResultsChannel.send") {
            val max = options.maxConcurrentHttpClients
            logger.info { "Starting scrape request processing with maxConcurrentClients: $max" }
            // Limits the number of concurrent scrapes below
            val semaphore = Semaphore(max)

            for (scrapeRequestAction in connectionContext.scrapeRequestActions()) {
              // Acquisition must happen before launch to provide backpressure to the channel
              // and avoid creating unbounded waiting coroutines (Bug #1).
              semaphore.acquire()
              launch {
                try {
                  // The url fetch occurs here during scrapeRequestAction.invoke()
                  val scrapeResponse = scrapeRequestAction.invoke()
                  // A false return means the result was dropped because the connection closed
                  // mid-scrape; surface it as a metric so the loss is observable, not silent.
                  if (!connectionContext.sendScrapeResults(scrapeResponse))
                    metrics { scrapeResultCount.labels(launchId, "dropped").inc() }
                } finally {
                  semaphore.release()
                  decrementBacklog(1)
                }
              }
            }
          }
        }
        logger.info { "connectToProxy() completed" }
      }
    }

    while (isRunning) {
      try {
        runCatchingCancellable {
          runBlocking {
            connectToProxy()
            logger.info { "Disconnected from proxy at $proxyHost" }
          }
        }.onFailure { e -> handleConnectionFailure(e) }
      } finally {
        // Skip the rate-limiter wait once shutdown has flipped isRunning to false. acquire() is not
        // interruptible, so blocking here would stall Agent.shutDown()/awaitTerminated() for up to
        // reconnectPauseSecs; there is also no reconnect to pace when the agent is stopping.
        if (isRunning)
          reconnectPause()
      }
    }
  }

  // Blocks on the reconnect rate limiter to pace reconnect attempts, then logs the wait.
  // acquire() MUST stay outside the logger.info { } lambda: kotlin-logging skips lambda bodies when the
  // level is disabled, so an acquire() inside the lambda silently stops pacing reconnects at log levels
  // above INFO -- a hot reconnect loop that hammers the proxy (finding 3).
  internal fun reconnectPause() {
    val waited = reconnectLimiter.acquire()
    logger.info { "Waited ${waited.roundToInt().seconds} to reconnect" }
  }

  /**
   * Launches one connection-lifetime task on [Dispatchers.IO]: runs [block], logs any
   * non-cancellation failure (only while the agent is still running) tagged with [name], and on
   * completion closes the shared [connectionContext], decrementing the backlog by whatever it
   * drained. [block] is a [CoroutineScope] extension so a task (e.g. scrape processing) can launch
   * its own child coroutines against the task's scope.
   */
  private fun CoroutineScope.launchConnectionTask(
    connectionContext: AgentConnectionContext,
    name: String,
    block: suspend CoroutineScope.() -> Unit,
  ): Job =
    launch(Dispatchers.IO) {
      runCatchingCancellable { block() }
        .onFailure { e ->
          if (isRunning)
            logger.logStreamFailure(name, e)
        }
    }.apply {
      invokeOnCompletion {
        val drained = connectionContext.close()
        if (drained > 0) decrementBacklog(drained)
      }
    }

  internal fun handleConnectionFailure(e: Throwable) {
    when (e) {
      is RequestFailureException -> {
        logger.info { "Disconnected from proxy at $proxyHost after invalid response ${e.message}" }
      }

      is StatusRuntimeException -> {
        // Include the gRPC status so UNAVAILABLE / UNAUTHENTICATED / RESOURCE_EXHAUSTED reconnect loops
        // are distinguishable in the logs (finding 22).
        logger.info { "Disconnected from proxy at $proxyHost - ${e.status}" }
      }

      is StatusException -> {
        logger.warn { "Cannot connect to proxy at $proxyHost ${e.simpleClassName} ${e.message}" }
      }

      // Re-throw JVM Errors (OutOfMemoryError, StackOverflowError, etc.)
      // so the agent terminates instead of running in a corrupted state.
      is Error -> {
        logger.error(e) { "Fatal JVM error ${e.simpleClassName}: ${e.message}" }
        throw e
      }

      // Catch anything else to avoid exiting the retry loop
      else -> {
        logger.warn(e) { "Throwable caught ${e.simpleClassName} ${e.message}" }
      }
    }
  }

  internal val proxyHost get() = "${grpcService.agentHostName}:${grpcService.agentPort}"

  internal fun startTimer(): Histogram.Timer? = metrics.scrapeRequestLatency.labels(launchId, agentName).startTimer()

  override fun serviceName() = "$simpleClassName $agentName"

  override fun registerHealthChecks() {
    super.registerHealthChecks()
    healthCheckRegistry.register(
      "scrape_request_backlog_check",
      healthCheck {
        val currentBacklog = scrapeRequestBacklogSize.load()
        val threshold = agentConfigVals.internal.scrapeRequestBacklogUnhealthySize
        if (currentBacklog >= threshold)
          HealthCheck.Result.unhealthy("Scrape request backlog size $currentBacklog >= threshold $threshold")
        else
          HealthCheck.Result.healthy()
      },
    )
    // The http_client_cache_size_check health check was removed (finding 24): its threshold
    // (maxCacheSize + 1) was unreachable because the cache evicts before insert, so cache.size never
    // exceeds maxCacheSize and the unhealthy branch could never fire. The cache size is still exported
    // as a metric (AgentMetrics).
  }

  private suspend fun startHeartBeat(connectionContext: AgentConnectionContext) {
    val cfg = agentConfigVals.internal
    val heartbeatPauseTime = cfg.heartbeatCheckPauseMillis.milliseconds

    if (!cfg.heartbeatEnabled) {
      logger.info { "Heartbeat disabled" }
      // Stay alive for the connection's lifetime instead of returning: launchConnectionTask treats any
      // task's completion as a disconnect and closes the shared connectionContext, so returning here would
      // close the context right after connect -- the first scrape then hits a ClosedSendChannelException
      // and the agent flaps, dropping its paths (finding 6). Poll connected (rather than awaitCancellation,
      // which nothing cancels here) so the task still ends promptly once the connection actually closes.
      while (isRunning && connectionContext.connected) {
        delay(heartbeatPauseTime)
      }
      logger.info { "Heartbeat (disabled) completed" }
      return
    }

    val maxInactivityTime = cfg.heartbeatMaxInactivitySecs.seconds
    logger.info { "Heartbeat scheduled to fire after $maxInactivityTime of inactivity" }

    var consecutiveFailures = 0
    while (isRunning && connectionContext.connected) {
      if (lastMsgSentMark.elapsedNow() > maxInactivityTime) {
        logger.debug { "Sending heartbeat" }
        val result = grpcService.sendHeartBeat()
        val nextCount = nextHeartbeatFailureCount(result, consecutiveFailures, MAX_HEARTBEAT_FAILURES)
        if (nextCount == null) {
          // EVICTED, or MAX_HEARTBEAT_FAILURES consecutive failures on a half-open transport. Tear the
          // channel down so the idle readRequestsFromProxy collect errors out and the run loop reconnects
          // (findings 1 & 2); closing the connection context alone cannot unblock that collect, so the
          // agent would otherwise linger as a zombie until proxy eviction.
          logger.warn { "Heartbeat signalled disconnect ($result); tearing down connection to reconnect" }
          grpcService.shutDownChannel()
          break
        }
        consecutiveFailures = nextCount
      }
      delay(heartbeatPauseTime)
    }
    logger.info { "Heartbeat completed" }
  }

  // Given the latest heartbeat [result] and the running [consecutiveFailures] count, returns the updated
  // count, or null to signal the connection should be torn down: immediately on EVICTED, or once transient
  // failures reach [maxFailures] in a row (a half-open transport fails every heartbeat -- finding 2). A
  // SUCCESS resets the count to 0. Pure and side-effect-free so the threshold logic is unit-testable.
  internal fun nextHeartbeatFailureCount(
    result: HeartBeatResult,
    consecutiveFailures: Int,
    maxFailures: Int,
  ): Int? =
    when (result) {
      HeartBeatResult.SUCCESS -> 0
      HeartBeatResult.EVICTED -> null
      HeartBeatResult.TRANSIENT_FAILURE -> (consecutiveFailures + 1).takeIf { it < maxFailures }
    }

  /**
   * Updates the scrape counter metrics for the specified operation type.
   *
   * This method increments the counter for scrape operations, categorized by type
   * (e.g., "success", "failure", "timeout"). The metrics are labeled with the
   * agent's launch ID for tracking across agent restarts.
   *
   * @param type The type/category of scrape operation. Empty strings are ignored.
   */
  internal fun updateScrapeCounter(type: String) {
    if (type.isNotEmpty())
      metrics { scrapeRequestCount.labels(launchId, type).inc() }
  }

  /**
   * Marks the current time as when the last message was sent to the proxy.
   *
   * This timestamp is used by the heartbeat mechanism to determine when to send
   * keep-alive messages to maintain the connection with the proxy.
   */
  internal fun markMsgSent() {
    lastMsgSentMark = clock.markNow()
  }

  /**
   * Waits for the initial connection to the proxy to be established.
   *
   * This method blocks until either the agent successfully connects to the proxy
   * and completes registration, or the specified timeout is reached.
   *
   * @param timeout Maximum time to wait for initial connection
   * @return true if connection was established within the timeout, false otherwise
   */
  internal fun awaitInitialConnection(timeout: Duration) = initialConnectionLatch.await(timeout)

  // Test hook: releases the initial-connection latch so tests can drive awaitInitialConnection()
  // without reflecting into the private field.
  internal fun countDownInitialConnectionLatch() = initialConnectionLatch.countDown()

  internal fun awaitInitialPathsRegistered(timeout: Duration) = initialPathsRegisteredLatch.await(timeout)

  /**
   * Executes metrics operations if metrics collection is enabled.
   *
   * This method provides a safe way to perform metrics operations that will only
   * execute if metrics collection is enabled in the agent configuration.
   *
   * @param args Lambda function containing metrics operations to execute
   */
  internal fun metrics(args: AgentMetrics.() -> Unit) {
    if (isMetricsEnabled)
      args.invoke(metrics)
  }

  // Guava's AbstractExecutionThreadService invokes the shutDown() hook only AFTER run() returns, but
  // run() can be parked in an idle readRequestsFromProxy collect that nothing else cancels. Shut the
  // gRPC channel down here -- triggerShutdown() runs on the stopping thread, concurrently with the
  // blocked run() -- so that collect errors out, the connection coroutineScope completes, runBlocking
  // returns, and the while(isRunning) loop exits. Without this, stopSync()/awaitTerminated() deadlocks
  // until a scrape arrives, the proxy evicts the agent, or the OS TCP timeout fires (finding 1).
  // Mirrors AgentContextCleanupService.triggerShutdown().
  override fun triggerShutdown() {
    runCatchingCancellable { grpcService.shutDownChannel() }
      .onFailure { e -> logger.warn(e) { "triggerShutdown() failed to shut down the gRPC channel: ${e.message}" } }
  }

  override fun shutDown() {
    grpcService.shutDown()
    runBlocking { agentHttpService.close() }
    super.shutDown()
  }

  // Called from EmbeddedAgentInfo to let external callers shut the agent down without access to the
  // full Agent instance. Must drive the Guava service lifecycle (stopSync) rather than invoking the
  // shutDown() hook directly: stopSync() transitions the service to STOPPING (flipping isRunning to
  // false) so the run() loop exits, then Guava invokes the shutDown() hook exactly once for cleanup,
  // and blocks until the service reaches TERMINATED. Calling shutDown() directly would tear down the
  // channel/servlets but leave isRunning true, so the run loop would reconnect forever.
  fun stop() {
    stopSync()
  }

  override fun toString() =
    toStringElements {
      add("agentId", agentId)
      add("agentName", agentName)
      add("proxyHost", proxyHost)
      add("adminService", if (isAdminEnabled) servletService else "Disabled")
      add("metricsService", if (isMetricsEnabled) metricsService else "Disabled")
    }

  companion object {
    private val logger = logger {}

    // Consecutive heartbeat failures tolerated before forcing a reconnect. On a half-open transport
    // every heartbeat fails; a small threshold keeps a transient blip from reconnecting while still
    // detecting a truly dead connection within a few heartbeat cycles (finding 2).
    private const val MAX_HEARTBEAT_FAILURES = 3

    // The connection's scrape-request channel is sized at 2x the backlog-unhealthy threshold so the
    // health check can fire (backlog >= threshold) before the channel actually blocks senders (finding 28).
    private const val BACKLOG_CAPACITY_MULTIPLIER = 2

    /**
     * JVM entry point for the standalone Agent process.
     *
     * Equivalent to calling [startSyncAgent] with `exitOnMissingConfig = true`, meaning the process
     * terminates with a non-zero exit code if no config file or URL is provided via `--config`/`-c` or
     * the `AGENT_CONFIG` environment variable. Used as the `Main-Class` of `prometheus-agent.jar`.
     *
     * @param args Raw command-line arguments forwarded to [AgentOptions] for parsing.
     */
    @JvmStatic
    fun main(args: Array<String>) {
      startSyncAgent(args, exitOnMissingConfig = true)
    }

    /**
     * Starts an Agent in the calling thread and blocks until shutdown.
     *
     * Logs the agent banner and version, parses [args] into an [AgentOptions], constructs an [Agent], and
     * immediately calls `startSync()` from the constructor's `initBlock`. Control returns to the caller only
     * after the Agent terminates (e.g. process signal, fatal error, or explicit [stop]).
     *
     * Use this from `main()` or any code that wants to run the Agent as the foreground task. For embedded
     * usage inside another running JVM, use [startAsyncAgent] instead.
     *
     * @param args Raw command-line arguments forwarded to [AgentOptions] for parsing.
     * @param exitOnMissingConfig When `true`, the process exits with a non-zero status if no config file/URL
     *   is supplied. When `false`, the Agent falls back to the built-in reference config (mostly useful in
     *   tests where defaults are sufficient).
     */
    @JvmStatic
    fun startSyncAgent(
      args: Array<String>,
      exitOnMissingConfig: Boolean,
    ) {
      logger.apply {
        info { getBanner("banners/agent.txt", this) }
        info { getVersionDesc() }
      }
      Agent(options = AgentOptions(args, exitOnMissingConfig)) { startSync() }
    }

    /**
     * Starts an Agent on a background thread and returns immediately, suitable for embedding inside another
     * JVM application.
     *
     * Unlike [startSyncAgent], this does not block — the Agent's lifecycle is owned by the caller via the
     * returned [EmbeddedAgentInfo], which exposes the agent's `launchId` and `agentName` and a `stop()` method
     * for graceful shutdown.
     *
     * @param configFilename Path or URL to the HOCON config file. Forwarded to the
     *   [AgentOptions] config-filename constructor so it follows the same resolution rules as the
     *   `--config` CLI flag.
     * @param exitOnMissingConfig When `true`, terminates the calling process if the config cannot be located.
     *   For embedded usage where the host application should handle config errors itself, pass `false`.
     * @param logBanner When `true` (default), logs the Agent ASCII banner and version on startup. Pass `false`
     *   to suppress banner output when embedding inside an app that owns its own logging surface.
     * @return An [EmbeddedAgentInfo] handle for inspecting and shutting down the launched Agent.
     */
    @Suppress("unused")
    @JvmStatic
    fun startAsyncAgent(
      configFilename: String,
      exitOnMissingConfig: Boolean,
      logBanner: Boolean = true,
    ): EmbeddedAgentInfo {
      if (logBanner)
        logger.apply {
          info { getBanner("banners/agent.txt", this) }
          info { getVersionDesc() }
        }
      val agent = Agent(options = AgentOptions(configFilename, exitOnMissingConfig)) { startAsync() }
      return EmbeddedAgentInfo(agent)
    }
  }
}
