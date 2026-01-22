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

package io.prometheus

import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.service.GenericService
import com.github.pambrose.common.servlet.LambdaServlet
import com.github.pambrose.common.time.format
import com.github.pambrose.common.util.MetricsUtils.newBacklogHealthCheck
import com.github.pambrose.common.util.Version
import com.github.pambrose.common.util.getBanner
import com.github.pambrose.common.util.hostInfo
import com.github.pambrose.common.util.randomId
import com.github.pambrose.common.util.simpleClassName
import com.google.common.util.concurrent.RateLimiter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.prometheus.agent.AgentConnectionContext
import io.prometheus.agent.AgentGrpcService
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.AgentOptions
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.agent.RequestFailureException
import io.prometheus.client.Summary
import io.prometheus.common.BaseOptions.Companion.DEBUG
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.Utils.exceptionDetails
import io.prometheus.common.Utils.getVersionDesc
import io.prometheus.common.Utils.lambda
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.concurrent.atomics.AtomicInt
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
  versionBlock = lambda { getVersionDesc(true) },
  isTestMode = testMode,
) {
  private val clock = Monotonic
  internal val agentHttpService = AgentHttpService(this)
  private val initialConnectionLatch = CountDownLatch(1)

  // Prime the limiter
  private val reconnectLimiter: RateLimiter
  private var lastMsgSentMark: TimeMark by nonNullableReference(clock.markNow())

  internal val agentName = options.agentName.ifBlank { "Unnamed-${hostInfo.hostName}" }
  internal val scrapeRequestBacklogSize = AtomicInt(0)
  internal val pathManager = AgentPathManager(this)
  internal val grpcService = AgentGrpcService(this, options, inProcessServerName)
  internal var agentId: String by nonNullableReference("")
  internal val launchId = randomId(15)
  internal val metrics by lazy { AgentMetrics(this) }

  val agentConfigVals: ConfigVals.Agent get() = configVals.agent

  init {
    reconnectLimiter = RateLimiter.create(1.0 / agentConfigVals.internal.reconnectPauseSecs).apply { acquire() }

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
      scrapeRequestBacklogSize.store(0)
      lastMsgSentMark = clock.markNow()

      if (grpcService.connectAgent(configVals.agent.transportFilterDisabled)) {
        grpcService.registerAgent(initialConnectionLatch)
        pathManager.registerPaths()

        val connectionContext = AgentConnectionContext()

        coroutineScope {
          launch(Dispatchers.IO) {
            runCatching {
              grpcService.readRequestsFromProxy(agentHttpService, connectionContext)
            }.onFailure { e ->
              if (grpcService.agent.isRunning)
                Status.fromThrowable(e).apply { logger.error(e) { "readRequestsFromProxy(): ${exceptionDetails(e)}" } }
            }
          }

          launch(Dispatchers.IO) {
            runCatching {
              startHeartBeat(connectionContext)
            }.onFailure { e ->
              if (grpcService.agent.isRunning)
                Status.fromThrowable(e).apply { logger.error(e) { "startHeartBeat(): ${exceptionDetails(e)}" } }
            }
          }

          // This exceptionHandler is not necessary
          launch(Dispatchers.IO) {
            runCatching {
              grpcService.writeResponsesToProxyUntilDisconnected(this@Agent, connectionContext)
            }.onFailure { e ->
              if (grpcService.agent.isRunning)
                Status.fromThrowable(e)
                  .apply { logger.error(e) { "writeResponsesToProxyUntilDisconnected(): ${exceptionDetails(e)}" } }
            }
          }

          launch(Dispatchers.IO) {
            runCatching {
              val max = options.maxConcurrentHttpClients
              logger.info { "Starting scrape request processing with maxConcurrentClients: $max" }
              // Limits the number of concurrent scrapes below
              val semaphore = Semaphore(max)

              // This for stmt is terminated by connectionContext.close()
              for (scrapeRequestAction in connectionContext.scrapeRequestsChannel) {
                semaphore.withPermit {
                  // The url fetch occurs here during scrapeRequestAction.invoke()
                  val scrapeResponse = scrapeRequestAction.invoke()
                  connectionContext.scrapeResultsChannel.send(scrapeResponse)
                }
              }
            }.onFailure { e ->
              if (grpcService.agent.isRunning)
                Status.fromThrowable(e)
                  .apply { logger.error(e) { "scrapeResultsChannel.send(): ${exceptionDetails(e)}" } }
            }
          }
        }
      }
    }

    while (isRunning) {
      try {
        runCatching {
          runBlocking {
            connectToProxy()
          }
        }.onFailure { e ->
          when (e) {
            is RequestFailureException -> {
              logger.info { "Disconnected from proxy at $proxyHost after invalid response ${e.message}" }
            }

            is StatusRuntimeException -> {
              logger.info { "Disconnected from proxy at $proxyHost" }
            }

            is StatusException -> {
              logger.warn { "Cannot connect to proxy at $proxyHost ${e.simpleClassName} ${e.message}" }
            }

            // Catch anything else to avoid exiting retry loop
            else -> {
              logger.warn { "Throwable caught ${e.simpleClassName} ${e.message}" }
            }
          }
        }
      } finally {
        logger.info { "Waited ${reconnectLimiter.acquire().roundToInt().seconds} to reconnect" }
      }
    }
  }

  internal val proxyHost get() = "${grpcService.agentHostName}:${grpcService.agentPort}"

  internal fun startTimer(agent: Agent): Summary.Timer? =
    metrics.scrapeRequestLatency.labels(agent.launchId, agentName).startTimer()

  override fun serviceName() = "$simpleClassName $agentName"

  override fun registerHealthChecks() {
    super.registerHealthChecks()
    healthCheckRegistry.register(
      "scrape_request_backlog_check",
      newBacklogHealthCheck(
        backlogSize = scrapeRequestBacklogSize.load(),
        size = agentConfigVals.internal.scrapeRequestBacklogUnhealthySize,
      ),
    )
    runBlocking {
      healthCheckRegistry.register(
        "http_client_cache_size_check",
        newBacklogHealthCheck(
          backlogSize = agentHttpService.httpClientCache.getCacheStats().totalEntries,
          size = options.maxCacheSize + 1,
        ),
      )
    }
  }

  private suspend fun startHeartBeat(connectionContext: AgentConnectionContext) {
    agentConfigVals.internal.apply {
      if (heartbeatEnabled) {
        val heartbeatPauseTime = heartbeatCheckPauseMillis.milliseconds
        val maxInactivityTime = heartbeatMaxInactivitySecs.seconds
        logger.info { "Heartbeat scheduled to fire after $maxInactivityTime of inactivity" }

        while (isRunning && connectionContext.connected) {
          val timeSinceLastWrite = lastMsgSentMark.elapsedNow()
          if (timeSinceLastWrite > maxInactivityTime) {
            logger.debug { "Sending heartbeat" }
            grpcService.sendHeartBeat()
          }
          delay(heartbeatPauseTime)
        }
        logger.info { "Heartbeat completed" }
      } else {
        logger.info { "Heartbeat disabled" }
      }
    }
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
  internal fun awaitInitialConnection(timeout: Duration) =
    initialConnectionLatch.await(timeout.inWholeMilliseconds, MILLISECONDS)

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

  override fun shutDown() {
    grpcService.shutDown()
    super.shutDown()
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
    private val logger = KotlinLogging.logger {}

    @JvmStatic
    fun main(args: Array<String>) {
      startSyncAgent(args, true)
    }

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
      return EmbeddedAgentInfo(agent.launchId, agent.agentName)
    }
  }
}
