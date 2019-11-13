/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import com.google.common.base.Joiner
import com.sudothought.common.dsl.GuavaDsl.toStringElements
import com.sudothought.common.util.getBanner
import io.grpc.Attributes
import io.prometheus.common.AdminConfig.Companion.newAdminConfig
import io.prometheus.common.ConfigVals
import io.prometheus.common.GenericService
import io.prometheus.common.MetricsConfig.Companion.newMetricsConfig
import io.prometheus.common.ZipkinConfig.Companion.newZipkinConfig
import io.prometheus.common.delay
import io.prometheus.common.getVersionDesc
import io.prometheus.common.newMapHealthCheck
import io.prometheus.dsl.MetricsDsl.healthCheck
import io.prometheus.proxy.AgentContextCleanupService
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyGrpcService
import io.prometheus.proxy.ProxyHttpService
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyOptions
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ScrapeRequestManager
import kotlinx.coroutines.runBlocking
import mu.KLogging
import kotlin.properties.Delegates.notNull
import kotlin.time.milliseconds

class Proxy(options: ProxyOptions,
            proxyHttpPort: Int = options.proxyHttpPort,
            inProcessServerName: String = "",
            testMode: Boolean = false,
            initBlock: (Proxy.() -> Unit)? = null) :
    GenericService(options.configVals,
                   newAdminConfig(options.adminEnabled,
                                  options.adminPort,
                                  options.configVals.proxy.admin),
                   newMetricsConfig(options.metricsEnabled,
                                    options.metricsPort,
                                    options.configVals.proxy.metrics),
                   newZipkinConfig(options.configVals.proxy.internal.zipkin),
                   testMode) {
    val configVals: ConfigVals.Proxy2.Internal2 = genericConfigVals.proxy.internal
    val pathManager = ProxyPathManager(isTestMode)
    val scrapeRequestManager = ScrapeRequestManager()
    val agentContextManager = AgentContextManager()
    var metrics: ProxyMetrics by notNull()

    private val httpService = ProxyHttpService(this, proxyHttpPort)
    private val grpcService =
        if (inProcessServerName.isEmpty())
            ProxyGrpcService(this, port = options.proxyAgentPort)
        else
            ProxyGrpcService(this, inProcessName = inProcessServerName)

    private var agentCleanupService: AgentContextCleanupService by notNull()

    init {
        if (isMetricsEnabled)
            metrics = ProxyMetrics(this)
        if (configVals.staleAgentCheckEnabled)
            agentCleanupService = AgentContextCleanupService(this) { addServices(this) }
        addServices(grpcService, httpService)
        initService()
        initBlock?.invoke(this)
    }

    override fun startUp() {
        super.startUp()

        grpcService.startSync()
        httpService.startSync()

        if (configVals.staleAgentCheckEnabled)
            agentCleanupService.startSync()
        else
            logger.info { "Agent eviction thread not started" }
    }

    override fun shutDown() {
        grpcService.stopSync()
        httpService.stopSync()
        if (configVals.staleAgentCheckEnabled)
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
                register("scrape_response_map_check",
                         newMapHealthCheck(scrapeRequestManager.scrapeRequestMap,
                                           configVals.scrapeRequestMapUnhealthySize))
                register("agent_scrape_request_backlog",
                         healthCheck {
                             val unhealthySize = configVals.scrapeRequestBacklogUnhealthySize
                             val vals =
                                 agentContextManager.agentContextMap.entries
                                     .filter { it.value.scrapeRequestBacklogSize >= unhealthySize }
                                     .map { "${it.value} ${it.value.scrapeRequestBacklogSize}" }
                                     .toList()
                             if (vals.isEmpty()) {
                                 HealthCheck.Result.healthy()
                             } else {
                                 val s = Joiner.on(", ").join(vals)
                                 HealthCheck.Result.unhealthy("Large agent scrape request backlog: $s")
                             }
                         })
            }
    }

    fun removeAgentContext(agentId: String?) =
        if (agentId == null || agentId.isEmpty()) {
            logger.error { "Missing agentId" }
            null
        } else {
            val agentContext = agentContextManager.removeAgentContext(agentId)
            if (agentContext == null) {
                logger.error { "Missing AgentContext for agentId: $agentId" }
            } else {
                logger.info { "Removed $agentContext" }
                agentContext.invalidate()
            }
            agentContext
        }

    override fun toString() =
        toStringElements {
            add("proxyPort", httpService.httpPort)
            add("adminService", if (isAdminEnabled) adminService else "Disabled")
            add("metricsService", if (isMetricsEnabled) metricsService else "Disabled")
        }

    companion object : KLogging() {
        const val AGENT_ID = "agent-id"
        val ATTRIB_AGENT_ID: Attributes.Key<String> = Attributes.Key.create(AGENT_ID)

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