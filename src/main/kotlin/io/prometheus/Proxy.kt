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

import com.codahale.metrics.health.HealthCheck
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.dsl.MetricsDsl.healthCheck
import com.github.pambrose.common.service.GenericService
import com.github.pambrose.common.servlet.LambdaServlet
import com.github.pambrose.common.time.format
import com.github.pambrose.common.util.MetricsUtils.newMapHealthCheck
import com.github.pambrose.common.util.Version
import com.github.pambrose.common.util.getBanner
import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.simpleClassName
import com.google.common.collect.EvictingQueue
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.BaseOptions.Companion.DEBUG
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Utils.getVersionDesc
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.AgentContextCleanupService
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyGrpcService
import io.prometheus.proxy.ProxyHttpService
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyOptions
import io.prometheus.proxy.ProxyPathManager
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
 * Multiple Proxy instances can run simultaneously for high availability:
 * - Each proxy can handle the full set of agents
 * - Agents automatically reconnect to available proxies
 * - Load balancers can distribute Prometheus requests across proxies
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

  val proxyConfigVals: ConfigVals.Proxy2 get() = configVals.proxy

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
            listOf(
              toPlainText(),
              pathManager.toPlainText(),
              recentReqsText,
            ).joinToString("\n")
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

    if (proxyConfigVals.internal.staleAgentCheckEnabled)
      agentCleanupService.startSync()
    else
      logger.info { "Agent eviction thread not started" }
  }

  override fun shutDown() {
    grpcService.stopSync()
    httpService.stopSync()
    if (proxyConfigVals.internal.staleAgentCheckEnabled)
      agentCleanupService.stopSync()
    super.shutDown()
  }

  override fun run() {
    runBlocking {
      while (isRunning) {
        delay(500.milliseconds)
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
            agentContextManager.chunkedContextMap,
            proxyConfigVals.internal.chunkContextMapUnhealthySize,
          ),
        )
        register(
          "scrape_response_map_check",
          newMapHealthCheck(
            scrapeRequestManager.scrapeRequestMap,
            proxyConfigVals.internal.scrapeRequestMapUnhealthySize,
          ),
        )
        register(
          "agent_scrape_request_backlog",
          healthCheck {
            agentContextManager.agentContextMap.entries
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

    pathManager.removeFromPathManager(agentId, reason)
    return agentContextManager.removeFromContextManager(agentId, reason)
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

  // val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
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

  /**
   * Checks if a request path corresponds to a Blitz verification request.
   *
   * Blitz is a service that verifies website ownership. This method checks if
   * the requested path matches the configured Blitz verification path.
   *
   * @param path The request path to check
   * @return true if this is a Blitz verification request, false otherwise
   */
  fun isBlitzRequest(path: String) = with(proxyConfigVals.internal) { blitz.enabled && path == blitz.path }

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
  fun buildServiceDiscoveryJson(): JsonArray =
    buildJsonArray {
      pathManager.allPaths.forEach { path ->
        addJsonObject {
          putJsonArray("targets") {
            add(JsonPrimitive(options.sdTargetPrefix))
          }
          putJsonObject("labels") {
            put("__metrics_path__", JsonPrimitive(path))

            val agentContextInfo = pathManager.getAgentContextInfo(path)

            if (agentContextInfo.isNotNull()) {
              val agentContexts = agentContextInfo.agentContexts
              put("agentName", JsonPrimitive(agentContexts.joinToString { it.agentName }))
              put("hostName", JsonPrimitive(agentContexts.joinToString { it.hostName }))

              val labels = agentContextInfo.labels
              runCatching {
                val json = labels.toJsonElement()
                json.jsonObject.forEach { (k, v) -> put(k, v) }
              }.onFailure { e ->
                logger.warn { "Invalid JSON in labels value: $labels - ${e.simpleClassName}: ${e.message}" }
              }
            } else {
              logger.warn { "No agent context info for path: $path" }
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
