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

package io.prometheus

import com.codahale.metrics.health.HealthCheck
import com.github.pambrose.common.coroutine.delay
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.dsl.MetricsDsl.healthCheck
import com.github.pambrose.common.service.GenericService
import com.github.pambrose.common.servlet.LambdaServlet
import com.github.pambrose.common.time.format
import com.github.pambrose.common.util.MetricsUtils.newMapHealthCheck
import com.github.pambrose.common.util.Version
import com.github.pambrose.common.util.getBanner
import com.google.common.base.Joiner
import com.google.common.collect.EvictingQueue
import io.grpc.Attributes
import io.prometheus.common.BaseOptions.Companion.DEBUG
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import io.prometheus.common.GrpcObjects.EMPTY_AGENT_ID
import io.prometheus.common.getVersionDesc
import io.prometheus.proxy.*
import kotlinx.coroutines.runBlocking
import mu.KLogging
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.milliseconds

@Version(version = "1.8.3", date = "9/1/20")
class Proxy(val options: ProxyOptions,
            proxyHttpPort: Int = options.proxyHttpPort,
            inProcessServerName: String = "",
            testMode: Boolean = false,
            initBlock: (Proxy.() -> Unit)? = null) :
    GenericService<ConfigVals>(options.configVals,
                               newAdminConfig(options.adminEnabled,
                                              options.adminPort,
                                              options.configVals.proxy.admin),
                               newMetricsConfig(options.metricsEnabled,
                                                options.metricsPort,
                                                options.configVals.proxy.metrics),
                               newZipkinConfig(options.configVals.proxy.internal.zipkin),
                               { getVersionDesc(true) },
                               isTestMode = testMode) {

  private val proxyConfigVals: ConfigVals.Proxy2.Internal2 = configVals.proxy.internal
  private val httpService = ProxyHttpService(this, proxyHttpPort, isTestMode)
  private val recentActions: EvictingQueue<String> =
    EvictingQueue.create(configVals.proxy.admin.recentRequestsQueueSize)
  private val grpcService =
    if (inProcessServerName.isEmpty())
      ProxyGrpcService(this, port = options.proxyAgentPort)
    else
      ProxyGrpcService(this, inProcessName = inProcessServerName)

  private val agentCleanupService by lazy { AgentContextCleanupService(this, proxyConfigVals) { addServices(this) } }

  internal val metrics by lazy { ProxyMetrics(this) }
  internal val pathManager by lazy { ProxyPathManager(this, isTestMode) }
  internal val agentContextManager = AgentContextManager()
  internal val scrapeRequestManager = ScrapeRequestManager()

  init {
    fun toPlainText() = """
      Prometheus Proxy Info [${getVersionDesc(false)}]
      
      Uptime:     ${upTime.format(true)}
      Proxy port: ${httpService.httpPort}
      
      Admin Service:
      ${if (isAdminEnabled) adminService.toString() else "Disabled"}
      
      Metrics Service:
      ${if (isMetricsEnabled) metricsService.toString() else "Disabled"}
      
    """.trimIndent()

    addServices(grpcService, httpService)

    initService {
      if (options.debugEnabled) {
        logger.info { "Adding /$DEBUG endpoint" }
        addServlet(DEBUG,
                   LambdaServlet {
                     listOf(toPlainText(),
                            pathManager.toPlainText(),
                            if (recentActions.size > 0) "\n${recentActions.size} most recent requests:" else "",
                            recentActions.reversed().joinToString("\n"))
                       .joinToString("\n")
                   })
      }
    }

    initBlock?.invoke(this)
  }

  override fun startUp() {
    super.startUp()

    grpcService.startSync()
    httpService.startSync()

    if (proxyConfigVals.staleAgentCheckEnabled)
      agentCleanupService.startSync()
    else
      logger.info { "Agent eviction thread not started" }
  }

  override fun shutDown() {
    grpcService.stopSync()
    httpService.stopSync()
    if (proxyConfigVals.staleAgentCheckEnabled)
      agentCleanupService.stopSync()
    super.shutDown()
  }

  override fun run() {
    runBlocking {
      while (isRunning)
        delay(500.milliseconds)
    }
  }

  override fun registerHealthChecks() {
    super.registerHealthChecks()
    healthCheckRegistry
      .apply {
        register("grpc_service", grpcService.healthCheck)
        register("chunking_map_check",
                 newMapHealthCheck(agentContextManager.chunkedContextMap,
                                   proxyConfigVals.chunkContextMapUnhealthySize))
        register("scrape_response_map_check",
                 newMapHealthCheck(scrapeRequestManager.scrapeRequestMap,
                                   proxyConfigVals.scrapeRequestMapUnhealthySize))
        register("agent_scrape_request_backlog",
                 healthCheck {
                   val unhealthySize = proxyConfigVals.scrapeRequestBacklogUnhealthySize
                   val vals =
                     agentContextManager.agentContextMap.entries
                       .filter { it.value.scrapeRequestBacklogSize >= unhealthySize }
                       .map { "${it.value} ${it.value.scrapeRequestBacklogSize}" }
                   if (vals.isEmpty()) {
                     HealthCheck.Result.healthy()
                   }
                   else {
                     val s = Joiner.on(", ").join(vals)
                     HealthCheck.Result.unhealthy("Large agent scrape request backlog: $s")
                   }
                 })
      }
  }

  // This is called on agent disconnects
  internal fun removeAgentContext(agentId: String): AgentContext? {
    require(agentId.isNotEmpty()) { EMPTY_AGENT_ID }

    pathManager.removeFromPathManager(agentId)
    return agentContextManager.removeFromContextManager(agentId)
  }

  internal fun metrics(args: ProxyMetrics.() -> Unit) {
    if (isMetricsEnabled)
      args.invoke(metrics)
  }

  //val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
  private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

  internal fun logActivity(desc: String) {
    synchronized(recentActions) {
      recentActions.add("${LocalDateTime.now().format(formatter)}: $desc")
    }
  }

  override fun toString() =
    toStringElements {
      add("proxyPort", httpService.httpPort)
      add("adminService", if (isAdminEnabled) adminService else "Disabled")
      add("metricsService", if (isMetricsEnabled) metricsService else "Disabled")
    }

  companion object : KLogging() {
    internal const val AGENT_ID_KEY = "agent-id"
    internal val ATTRIB_AGENT_ID: Attributes.Key<String> = Attributes.Key.create(AGENT_ID_KEY)

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