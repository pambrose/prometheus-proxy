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

package io.prometheus

import com.codahale.metrics.health.HealthCheck
import com.github.pambrose.common.coroutine.delay
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.dsl.MetricsDsl.healthCheck
import com.github.pambrose.common.service.GenericService
import com.github.pambrose.common.servlet.LambdaServlet
import com.github.pambrose.common.time.format
import com.github.pambrose.common.util.MetricsUtils.newMapHealthCheck
import com.github.pambrose.common.util.getBanner
import com.github.pambrose.common.util.isNotNull
import com.github.pambrose.common.util.simpleClassName
import com.google.common.base.Joiner
import com.google.common.collect.EvictingQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import io.prometheus.common.BaseOptions.Companion.DEBUG
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.Messages.EMPTY_AGENT_ID_MSG
import io.prometheus.common.Utils.getVersionDesc
import io.prometheus.common.Utils.lambda
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.common.Version
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.AgentContextCleanupService
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyGrpcService
import io.prometheus.proxy.ProxyHttpService
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyOptions
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ScrapeRequestManager
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

@Version(
  version = BuildConfig.APP_VERSION,
  releaseDate = BuildConfig.APP_RELEASE_DATE,
  buildTime = BuildConfig.BUILD_TIME,
)
class Proxy(
  val options: ProxyOptions,
  proxyHttpPort: Int = options.proxyHttpPort,
  inProcessServerName: String = "",
  testMode: Boolean = false,
  initBlock: (Proxy.() -> Unit)? = null,
) : GenericService<ConfigVals>(
  configVals = options.configVals,
  adminConfig = newAdminConfig(options.adminEnabled, options.adminPort, options.configVals.proxy.admin),
  metricsConfig = newMetricsConfig(options.metricsEnabled, options.metricsPort, options.configVals.proxy.metrics),
  zipkinConfig = newZipkinConfig(options.configVals.proxy.internal.zipkin),
  versionBlock = lambda { getVersionDesc(true) },
  isTestMode = testMode,
) {
  private val httpService = ProxyHttpService(this, proxyHttpPort, isTestMode)
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
            listOf(
              toPlainText(),
              pathManager.toPlainText(),
              if (recentReqs.isNotEmpty()) "\n${recentReqs.size} most recent requests:" else "",
              recentReqs.reversed().joinToString("\n"),
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
                  val s = Joiner.on(", ").join(vals)
                  HealthCheck.Result.unhealthy("Large agent scrape request backlog: $s")
                }
              }
          },
        )
      }
  }

  // This is called on agent disconnects
  internal fun removeAgentContext(
    agentId: String,
    reason: String,
  ): AgentContext? {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID_MSG }

    pathManager.removeFromPathManager(agentId, reason)
    return agentContextManager.removeFromContextManager(agentId, reason)
  }

  internal fun metrics(args: ProxyMetrics.() -> Unit) {
    if (isMetricsEnabled)
      args.invoke(metrics)
  }

  // val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
  private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  internal fun logActivity(desc: String) {
    synchronized(recentReqs) {
      recentReqs.add("${LocalDateTime.now().format(formatter)}: $desc")
    }
  }

  fun isBlitzRequest(path: String) = with(proxyConfigVals.internal) { blitz.enabled && path == blitz.path }

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
    private val logger = KotlinLogging.logger {}

    @JvmStatic
    fun main(argv: Array<String>) {
      logger.apply {
        info { getBanner("banners/proxy.txt", logger) }
        info { getVersionDesc(false) }
      }
      Proxy(options = ProxyOptions(argv)) { startSync() }
    }
  }
}
