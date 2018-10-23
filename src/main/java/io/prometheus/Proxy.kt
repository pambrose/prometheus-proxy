/*
 * Copyright © 2018 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus

import com.codahale.metrics.health.HealthCheck
import com.google.common.base.Joiner
import io.grpc.Attributes
import io.prometheus.common.AdminConfig.Companion.newAdminConfig
import io.prometheus.common.ConfigVals
import io.prometheus.common.GenericService
import io.prometheus.common.MetricsConfig.Companion.newMetricsConfig
import io.prometheus.common.ZipkinConfig.Companion.newZipkinConfig
import io.prometheus.common.getBanner
import io.prometheus.common.getVersionDesc
import io.prometheus.common.newMapHealthCheck
import io.prometheus.common.sleepForMillis
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.MetricsDsl.healthCheck
import io.prometheus.proxy.AgentContextCleanupService
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.PathManager
import io.prometheus.proxy.ProxyGrpcService.Companion.newProxyGrpcService
import io.prometheus.proxy.ProxyHttpService
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyOptions
import io.prometheus.proxy.ScrapeRequestManager
import mu.KLogging
import kotlin.properties.Delegates

class Proxy(options: ProxyOptions,
            proxyPort: Int = options.agentPort,
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
    val pathManager = PathManager(isTestMode)
    val scrapeRequestManager = ScrapeRequestManager()
    val agentContextManager = AgentContextManager()
    var metrics: ProxyMetrics by Delegates.notNull()

    private val httpService = ProxyHttpService(this, proxyPort)
    private val grpcService =
            if (inProcessServerName.isEmpty())
                newProxyGrpcService(proxy = this, port = options.agentPort)
            else
                newProxyGrpcService(proxy = this, serverName = inProcessServerName)

    private var agentCleanupService: AgentContextCleanupService by Delegates.notNull()


    val configVals: ConfigVals.Proxy2
        get() = genericConfigVals.proxy

    init {
        if (isMetricsEnabled)
            metrics = ProxyMetrics(this)
        if (configVals.internal.staleAgentCheckEnabled)
            agentCleanupService = AgentContextCleanupService(this) { addServices(this) }
        addServices(grpcService, httpService)
        initService()
        initBlock?.invoke(this)
    }

    override fun startUp() {
        super.startUp()
        grpcService.apply { startSync() }
        httpService.apply { startSync() }

        if (configVals.internal.staleAgentCheckEnabled)
            agentCleanupService.apply { startSync() }
        else
            logger.info { "Agent eviction thread not started" }
    }

    override fun shutDown() {
        grpcService.stopSync()
        httpService.stopSync()
        if (configVals.internal.staleAgentCheckEnabled)
            agentCleanupService.stopSync()
        super.shutDown()
    }

    override fun run() {
        while (isRunning)
            sleepForMillis(500)
    }

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        healthCheckRegistry
                .apply {
                    register("grpc_service", grpcService.healthCheck)
                    register("scrape_response_map_check",
                             newMapHealthCheck(scrapeRequestManager.scrapeRequestMap, configVals.internal.scrapeRequestMapUnhealthySize))
                    register("agent_scrape_request_queue",
                             healthCheck {
                                 val unhealthySize = configVals.internal.scrapeRequestQueueUnhealthySize
                                 val vals =
                                         agentContextManager
                                                 .agentContextMap
                                                 .entries
                                                 .asSequence()
                                                 .filter { it.value.scrapeRequestQueueSize >= unhealthySize }
                                                 .map { "${it.value} ${it.value.scrapeRequestQueueSize}" }
                                                 .toList()
                                 if (vals.isEmpty())
                                     HealthCheck.Result.healthy()
                                 else
                                     HealthCheck.Result.unhealthy("Large scrapeRequestQueues: ${Joiner.on(", ").join(vals)}")
                             })
                }
    }

    fun removeAgentContext(agentId: String?) =
            if (agentId == null || agentId.isEmpty()) {
                logger.error { "Missing agentId" }
                null
            }
            else {
                val agentContext = agentContextManager.removeAgentContext(agentId)
                if (agentContext == null) {
                    logger.error { "Missing AgentContext for agentId: $agentId" }
                }
                else {
                    logger.info { "Removed $agentContext" }
                    agentContext.markInvalid()
                }
                agentContext
            }

    override fun toString() =
            toStringElements {
                add("proxyPort", httpService.port)
                add("adminService", if (isAdminEnabled) adminService else "Disabled")
                add("metricsService", if (isMetricsEnabled) metricsService else "Disabled")
            }

    companion object : KLogging() {
        const val AGENT_ID = "agent-id"
        val ATTRIB_AGENT_ID: Attributes.Key<String> = Attributes.Key.create(AGENT_ID)

        @JvmStatic
        fun main(argv: Array<String>) {
            val options = ProxyOptions(argv)

            logger.info { getBanner("banners/proxy.txt", logger) }
            logger.info { getVersionDesc(false) }

            Proxy(options = options) { startSync() }
        }
    }
}