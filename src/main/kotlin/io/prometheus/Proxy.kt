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
import com.google.common.collect.EvictingQueue
import com.pambrose.common.dsl.GuavaDsl.toStringElements
import com.pambrose.common.dsl.MetricsDsl.healthCheck
import com.pambrose.common.service.GenericService
import com.pambrose.common.servlet.LambdaServlet
import com.pambrose.common.time.format
import com.pambrose.common.util.MetricsUtils.newMapHealthCheck
import com.pambrose.common.util.Version
import com.pambrose.common.util.getBanner
import com.pambrose.common.util.randomId
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.BaseOptions.Companion.DEBUG
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Utils.getVersionDesc
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.proxy.AgentAuthManager
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.AgentContextCleanupService
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyGrpcService
import io.prometheus.proxy.ProxyHttpService
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyOptions
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ScrapeRecord
import io.prometheus.proxy.ScrapeRequestManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Prometheus Proxy that enables metrics scraping across firewalls.
 *
 * The Proxy is the component that runs outside the firewall alongside Prometheus server.
 * It accepts scrape requests from Prometheus, routes them to appropriate Agents behind firewalls,
 * and returns the collected metrics back to Prometheus. This enables the standard Prometheus
 * pull model to work across network boundaries that would normally block direct access.
 *
 * ## Architecture
 *
 * The Proxy serves as a bridge between Prometheus and Agents, handling:
 * - **HTTP Service**: Serves metrics endpoints that Prometheus can scrape
 * - **gRPC Service**: Manages connections from multiple agents
 * - **Request Routing**: Routes scrape requests to appropriate agents based on path mappings
 * - **Service Discovery**: Provides Prometheus service discovery endpoints
 * - **Agent Management**: Tracks agent connections and handles disconnections
 * - **Health Monitoring**: Provides comprehensive health checks and metrics
 *
 * ## Configuration
 *
 * Proxies are configured via HOCON configuration files that specify:
 * - HTTP port for Prometheus scrape requests
 * - gRPC port for agent connections
 * - Admin endpoints for monitoring and debugging
 * - TLS settings for secure communication
 * - Service discovery configuration
 * - Agent cleanup and timeout settings
 *
 * ## Usage Examples
 *
 * ### Basic Usage
 * ```kotlin
 * val proxy = Proxy(ProxyOptions(args))
 * proxy.startSync()
 * ```
 *
 * ### With Custom Initialization
 * ```kotlin
 * val proxy = Proxy(options) {
 *     // Custom initialization logic
 *     logger.info { "Proxy initialized with custom settings" }
 * }
 * proxy.startSync()
 * ```
 *
 * ### In-Process for Testing
 * ```kotlin
 * val proxy = Proxy(options, inProcessServerName = "test-server")
 * // Agents can connect using the same in-process server name
 * ```
 *
 * ## Request Flow
 *
 * 1. **Prometheus Request**: Prometheus sends scrape request to proxy HTTP endpoint
 * 2. **Path Resolution**: Proxy determines which agent(s) can handle the requested path
 * 3. **Agent Communication**: Proxy forwards request to appropriate agent(s) via gRPC
 * 4. **Metric Collection**: Agent scrapes actual endpoint and returns metrics
 * 5. **Response Aggregation**: Proxy aggregates responses from multiple agents if needed
 * 6. **Prometheus Response**: Proxy returns collected metrics to Prometheus
 *
 * ## Service Discovery
 *
 * The Proxy can provide Prometheus-compatible service discovery by exposing an endpoint
 * that returns JSON describing all available scrape targets. This allows Prometheus to
 * automatically discover new metrics endpoints as agents register them.
 *
 * ## High Availability
 *
 * Multiple Proxy instances can run simultaneously for high availability. Each proxy is fully
 * independent — there is no shared state, clustering, or peer awareness between them:
 * - Each proxy can handle the full set of agents
 * - An agent configured with an ordered endpoint list (`agent.proxy.endpoints`, or a comma-separated
 *   `--proxy` / `PROXY_HOSTNAME`) connects to the first endpoint that answers and moves to the next on
 *   a failed connect, returning to the head of the list once a working connection drops
 * - An agent holds **one** connection at a time and registers its paths only on that proxy, so a
 *   standby proxy serves nothing until an agent fails over to it
 * - Prometheus should scrape every proxy with identical `static_config` target lists. Do not use
 *   `http_sd_config` for an HA pair: a standby returns an empty target list, which Prometheus treats
 *   as target deletion rather than as a failed scrape
 *
 * @param options Configuration options for the proxy, typically loaded from command line or config files
 * @param proxyPort Port for the HTTP service that Prometheus scrapes. Defaults to value in options.
 * @param inProcessServerName Optional in-process server name for testing scenarios. When specified,
 *                           the proxy will create an in-process gRPC server instead of a network server.
 * @param testMode Whether to run in test mode. Test mode may disable certain features or use
 *                 different defaults suitable for testing environments.
 * @param initBlock Optional initialization block executed after construction but before startup.
 *                  Useful for custom configuration or testing setup.
 *
 * @since 1.0.0
 * @see ProxyOptions for configuration details
 * @see Agent for the corresponding agent component
 */
@Version(
  version = BuildConfig.APP_VERSION,
  releaseDate = BuildConfig.APP_RELEASE_DATE,
  buildTime = BuildConfig.BUILD_TIME,
)
class Proxy(
  val options: ProxyOptions,
  proxyPort: Int = options.proxyPort,
  inProcessServerName: String = "",
  testMode: Boolean = false,
  initBlock: (Proxy.() -> Unit)? = null,
) : GenericService<ConfigVals>(
  configVals = options.configVals,
  adminConfig = newAdminConfig(options.adminEnabled, options.adminPort, options.configVals.proxy.admin),
  metricsConfig = newMetricsConfig(options.metricsEnabled, options.metricsPort, options.configVals.proxy.metrics),
  zipkinConfig = newZipkinConfig(options.configVals.proxy.internal.zipkin),
  versionBlock = { getVersionDesc(true) },
  isTestMode = testMode,
) {
  private val httpService = ProxyHttpService(this, proxyPort, isTestMode)
  private val recentReqs: EvictingQueue<String> = EvictingQueue.create(proxyConfigVals.admin.recentRequestsQueueSize)

  // Structured counterpart to recentReqs, for the operational web UI. Separate queue rather than a
  // replacement: the /debug servlet's text format is a stable operator-facing surface, and this one is
  // sized independently because the UI shows more history than a debug dump needs. EvictingQueue is not
  // thread-safe, so every access takes its own monitor -- see recordScrape / recentScrapes.
  private val recentScrapes: EvictingQueue<ScrapeRecord> =
    EvictingQueue.create(proxyConfigVals.ui.recentScrapesQueueSize)

  // Declared before grpcService: createGrpcServer() reads isEnabled to decide whether to install the auth
  // interceptor. Eager construction fail-fasts on invalid proxy.auth config at startup.
  internal val agentAuthManager = AgentAuthManager(this)

  private val grpcService =
    if (inProcessServerName.isEmpty())
      ProxyGrpcService(proxy = this, port = options.proxyAgentPort)
    else
      ProxyGrpcService(proxy = this, inProcessName = inProcessServerName)

  private val agentCleanupService by lazy {
    AgentContextCleanupService(this, proxyConfigVals.internal) { addServices(this) }
  }

  internal val metrics by lazy { ProxyMetrics(this) }
  internal val pathManager by lazy { ProxyPathManager(this, isTestMode) }
  internal val agentContextManager = AgentContextManager(isTestMode)
  internal val scrapeRequestManager = ScrapeRequestManager()

  // Per-process id (mirrors Agent.launchId). Intentionally used to label only proxy_start_time_seconds,
  // which is enough to detect a restart on a Prometheus target; the counters/histograms are left
  // unlabeled since PromQL rate()/increase() already tolerate counter resets across restarts.
  internal val launchId = randomId(15)

  // internal: the generated ConfigVals type is not part of the documented public API (finding 26).
  internal val proxyConfigVals: ConfigVals.Proxy2 get() = configVals.proxy

  // The stale-agent cleanup service runs when explicitly enabled, or is forced on when the transport
  // filter is disabled (there's then no per-connection disconnect detection, so it's the only cleanup
  // mechanism). Computed once so startUp() and shutDown() can't drift (finding 32).
  private val agentCleanupServiceEnabled: Boolean
    get() = proxyConfigVals.internal.staleAgentCheckEnabled || options.transportFilterDisabled

  init {
    fun toPlainText() =
      """
        Prometheus Proxy Info [${getVersionDesc(false)}]

        Uptime:     ${upTime.format(true)}
        Proxy port: ${httpService.httpPort}

        Admin Service:
        ${if (isAdminEnabled) servletService.toString() else "Disabled"}

        Metrics Service:
        ${if (isMetricsEnabled) metricsService.toString() else "Disabled"}

      """.trimIndent()

    addServices(grpcService, httpService)

    initServletService {
      if (options.debugEnabled) {
        logger.info { "Adding /$DEBUG endpoint" }
        addServlet(
          DEBUG,
          LambdaServlet {
            val recentReqsText =
              synchronized(recentReqs) {
                if (recentReqs.isNotEmpty())
                  "\n${recentReqs.size} most recent requests:\n" + recentReqs.reversed().joinToString("\n")
                else
                  ""
              }
            [
              toPlainText(),
              pathManager.toPlainText(),
              recentReqsText,
            ].joinToString("\n")
          },
        )
      } else {
        logger.info { "Debug servlet disabled" }
      }
    }

    initBlock?.invoke(this)
  }

  override fun startUp() {
    super.startUp()

    grpcService.startSync()
    httpService.startSync()

    // When transportFilterDisabled is true, there is no ProxyServerTransportFilter to detect
    // agent disconnects. The stale agent cleanup service is the only mechanism to clean up
    // leaked AgentContexts (e.g., agents that called connectAgentWithTransportFilterDisabled
    // but crashed before opening a readRequestsFromProxy stream). Force-enable it.
    if (agentCleanupServiceEnabled) {
      if (!proxyConfigVals.internal.staleAgentCheckEnabled)
        logger.warn { "Forcing agent eviction thread on: transportFilterDisabled requires stale agent cleanup" }
      agentCleanupService.startSync()
    } else {
      logger.info { "Agent eviction thread not started" }
    }
  }

  override fun shutDown() {
    // Fail in-flight scrape requests BEFORE invalidating agent contexts so that
    // HTTP handlers waiting on awaitCompleted() receive the informative "Proxy is shutting down"
    // error message rather than a generic "missing_results" from the agent context drain.
    scrapeRequestManager.failAllInFlightScrapeRequests("Proxy is shutting down")
    agentContextManager.invalidateAllAgentContexts()
    grpcService.stopSync()
    httpService.stopSync()
    if (agentCleanupServiceEnabled)
      agentCleanupService.stopSync()
    super.shutDown()
  }

  override fun run() {
    runBlocking {
      while (isRunning) {
        delay(RUN_LOOP_PAUSE)
      }
    }
  }

  override fun registerHealthChecks() {
    super.registerHealthChecks()
    healthCheckRegistry
      .apply {
        register("grpc_service", grpcService.healthCheck)
        register(
          "chunking_map_check",
          newMapHealthCheck(
            agentContextManager.chunkedContextMapView,
            proxyConfigVals.internal.chunkContextMapUnhealthySize,
          ),
        )
        register(
          "scrape_response_map_check",
          newMapHealthCheck(
            scrapeRequestManager.scrapeRequestMapView,
            proxyConfigVals.internal.scrapeRequestMapUnhealthySize,
          ),
        )
        register(
          "agent_scrape_request_backlog",
          healthCheck {
            agentContextManager.agentContextEntries
              .filter {
                it.value.scrapeRequestBacklogSize >= proxyConfigVals.internal.scrapeRequestBacklogUnhealthySize
              }
              .map {
                "${it.value} ${it.value.scrapeRequestBacklogSize}"
              }
              .let { vals ->
                if (vals.isEmpty()) {
                  HealthCheck.Result.healthy()
                } else {
                  val s = vals.joinToString(", ")
                  HealthCheck.Result.unhealthy("Large agent scrape request backlog: $s")
                }
              }
          },
        )
      }
  }

  /**
   * Removes an agent context when an agent disconnects.
   *
   * This method is called when an agent disconnects from the proxy, either gracefully
   * or due to network issues. It performs cleanup by removing the agent from both
   * the path manager (so requests are no longer routed to it) and the context manager
   * (to free up resources).
   *
   * @param agentId Unique identifier of the agent that disconnected. Must not be empty.
   * @param reason Human-readable description of why the agent was removed (for logging)
   * @return The removed AgentContext, or null if the agent was not found
   * @throws IllegalArgumentException if agentId is empty
   */
  internal fun removeAgentContext(
    agentId: String,
    reason: String,
  ): AgentContext? {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }

    // Drop the context first: removeFromContextManager invalidates it, and registerPath re-checks
    // validity inside the pathMap lock. Invalidating before the sweep below means a registration
    // racing this teardown is either rejected by that check (it arrives after invalidation) or
    // undone by the sweep (it arrived before). Sweeping first leaves a window where a registration
    // blocked on the pathMap monitor is released after the sweep but before invalidation, stranding
    // a path pointing at a dead context that nothing later removes (finding 7).
    val agentContext = agentContextManager.removeFromContextManager(agentId, reason)
    pathManager.removeFromPathManager(agentId, reason)
    scrapeRequestManager.failAllScrapeRequests(agentId, "Agent disconnected: $reason")
    // Proactively reclaim any in-flight chunked-transfer buffers for this agent (the requests
    // themselves were just failed above) rather than waiting for each stream's orphan sweep.
    agentContextManager.removeChunkedContextsForAgent(agentId)
      .also { reclaimed ->
        if (reclaimed.isNotEmpty())
          logger.info { "Reclaimed ${reclaimed.size} in-flight chunked context(s) for agentId: $agentId ($reason)" }
      }
    return agentContext
  }

  /**
   * Executes metrics operations if metrics collection is enabled.
   *
   * This method provides a safe way to perform metrics operations that will only
   * execute if metrics collection is enabled in the proxy configuration.
   *
   * @param args Lambda function containing metrics operations to execute
   */
  internal fun metrics(args: ProxyMetrics.() -> Unit) {
    if (isMetricsEnabled)
      args.invoke(metrics)
  }

  private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  /**
   * Logs an activity description for debugging and monitoring.
   *
   * Activities are stored in a rolling queue with timestamps for debugging purposes.
   * The queue automatically evicts old entries when it reaches capacity. This information
   * is available through the debug servlet when enabled.
   *
   * @param desc Description of the activity to log
   */
  internal fun logActivity(desc: String) {
    synchronized(recentReqs) {
      recentReqs.add("${LocalDateTime.now().format(formatter)}: $desc")
    }
  }

  /** Records one completed scrape for the web UI. Cheap and non-blocking; safe from any thread. */
  internal fun recordScrape(record: ScrapeRecord) {
    synchronized(recentScrapes) {
      recentScrapes.add(record)
    }
  }

  /**
   * A point-in-time copy of the recent scrapes, newest first.
   *
   * Returns a copy rather than a view: [EvictingQueue] is not thread-safe, so a caller iterating the
   * live queue while a scrape completes would race. Bounded by `proxy.ui.recentScrapesQueueSize`.
   */
  internal fun recentScrapes(): List<ScrapeRecord> = synchronized(recentScrapes) { recentScrapes.reversed() }

  /**
   * Checks if a request path corresponds to a Blitz verification request.
   *
   * Blitz is a service that verifies website ownership. This method checks if
   * the requested path matches the configured Blitz verification path.
   *
   * @param path The request path to check
   * @return true if this is a Blitz verification request, false otherwise
   */
  internal fun isBlitzRequest(path: String) = with(proxyConfigVals.internal) { blitz.enabled && path == blitz.path }

  /**
   * Builds a Prometheus-compatible service discovery JSON response.
   *
   * This method generates a JSON array in the format expected by Prometheus HTTP service
   * discovery. Each registered path is represented as a target with associated labels
   * that Prometheus can use for scraping configuration.
   *
   * The generated JSON includes:
   * - **targets**: Array containing the proxy endpoint for each path
   * - **labels**: Metadata about each target including:
   *   - `__metrics_path__`: The path to scrape
   *   - `agentName`: Names of agents serving this path
   *   - `hostName`: Hostnames of agents serving this path
   *   - Custom labels from agent path configuration
   *
   * ## Example Output
   * ```json
   * [
   *   {
   *     "targets": ["proxy.example.com:8080"],
   *     "labels": {
   *       "__metrics_path__": "/app1_metrics",
   *       "agentName": "agent-01",
   *       "hostName": "internal.host.com",
   *       "environment": "production",
   *       "service": "web"
   *     }
   *   }
   * ]
   * ```
   *
   * @return JsonArray containing service discovery information for all registered paths
   * @see <a href="https://prometheus.io/docs/prometheus/latest/http_sd/">Prometheus HTTP Service Discovery</a>
   */
  internal fun buildServiceDiscoveryJson(): JsonArray =
    buildJsonArray {
      pathManager.allPathContextInfos().forEach { (path, agentContextInfo) ->
        addJsonObject {
          putJsonArray("targets") {
            add(JsonPrimitive(options.sdTargetPrefix))
          }
          putJsonObject("labels") {
            val pathWithSlash = if (path.startsWith("/")) path else "/$path"
            put("__metrics_path__", JsonPrimitive(pathWithSlash))

            val agentContexts = agentContextInfo.agentContexts
            put("agentName", JsonPrimitive(agentContexts.joinToString { it.agentName }))
            put("hostName", JsonPrimitive(agentContexts.joinToString { it.hostName }))

            val labels = agentContextInfo.labels
            runCatching {
              val json = labels.toJsonElement()
              // Apply agent-supplied labels, but never let them clobber the proxy-computed reserved
              // keys above (a colliding key could redirect the scrape target or spoof identity).
              json.jsonObject.forEach { (k, v) ->
                if (k in RESERVED_SD_LABEL_KEYS)
                  logger.warn { "Ignoring agent label '$k' that collides with a reserved key for path $pathWithSlash" }
                else
                  put(k, v)
              }
            }.onFailure { e ->
              logger.warn { "Invalid JSON in labels value: $labels - ${e.simpleClassName}: ${e.message}" }
            }
          }
        }
      }
    }

  override fun toString() =
    toStringElements {
      add("proxyPort", httpService.httpPort)
      add("adminService", if (isAdminEnabled) servletService else "Disabled")
      add("metricsService", if (isMetricsEnabled) metricsService else "Disabled")
    }

  companion object {
    private val logger = logger {}

    // Idle pause of the proxy's run loop, which just parks the service thread until shutdown (finding 28).
    private val RUN_LOOP_PAUSE = 500.milliseconds

    // Service-discovery label keys computed by the proxy; agent-supplied labels must not overwrite them.
    private val RESERVED_SD_LABEL_KEYS: Set<String> = ["__metrics_path__", "agentName", "hostName"]

    /**
     * JVM entry point for the standalone Proxy process.
     *
     * Logs the Proxy ASCII banner and version, parses [args] into a [ProxyOptions], constructs a [Proxy],
     * and immediately calls `startSync()` from the constructor's `initBlock`. Control returns to the JVM only
     * after the Proxy terminates (e.g. process signal, fatal error, or explicit shutdown).
     *
     * Used as the `Main-Class` of `prometheus-proxy.jar`. Unlike [Agent.main], the Proxy always loads its
     * built-in reference config when no `--config`/`PROXY_CONFIG` is supplied, so there is no
     * `exitOnMissingConfig` flag to plumb through here.
     *
     * For embedded use inside another JVM (mirroring [Agent.startAsyncAgent]), construct a [Proxy] directly
     * and call `startAsync()` rather than using this method.
     *
     * @param args Raw command-line arguments forwarded to [ProxyOptions] for parsing.
     */
    @JvmStatic
    fun main(args: Array<String>) {
      logger.apply {
        info { getBanner("banners/proxy.txt", logger) }
        info { getVersionDesc(false) }
      }
      Proxy(options = ProxyOptions(args)) { startSync() }
    }
  }
}
