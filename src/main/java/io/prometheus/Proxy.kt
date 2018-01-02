/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus

import com.codahale.metrics.health.HealthCheck
import com.google.common.base.Joiner
import com.google.common.collect.Maps
import io.grpc.Attributes
import io.prometheus.common.*
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.MetricsDsl.newHealthCheck
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.proxy.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentMap
import kotlin.properties.Delegates

class Proxy(options: ProxyOptions,
            proxyPort: Int = options.agentPort,
            inProcessServerName: String = "",
            testMode: Boolean = false) : GenericService(options.configVals,
                                                        AdminConfig.create(options.adminEnabled,
                                                                           options.adminPort,
                                                                           options.configVals.proxy.admin),
                                                        MetricsConfig.create(options.metricsEnabled,
                                                                             options.metricsPort,
                                                                             options.configVals.proxy.metrics),
                                                        ZipkinConfig.create(options.configVals.proxy.internal.zipkin),
                                                        testMode) {

    private val pathMap = Maps.newConcurrentMap<String, AgentContext>() // Map path to AgentContext
    private val scrapeRequestMap = Maps.newConcurrentMap<Long, ScrapeRequestWrapper>() // Map scrape_id to agent_id

    val agentContextMap: ConcurrentMap<String, AgentContext> = Maps.newConcurrentMap<String, AgentContext>() // Map agent_id to AgentContext
    var metrics: ProxyMetrics by Delegates.notNull()

    private val httpService = ProxyHttpService(this, proxyPort)
    private val grpcService: ProxyGrpcService =
            if (inProcessServerName.isEmpty())
                ProxyGrpcService.create(this, options.agentPort)
            else
                ProxyGrpcService.create(this, inProcessServerName)

    private var agentCleanupService: AgentContextCleanupService by Delegates.notNull()

    val agentContextSize: Int
        get() = agentContextMap.size

    val pathMapSize: Int
        get() = pathMap.size

    val scrapeMapSize: Int
        get() = scrapeRequestMap.size

    val configVals: ConfigVals.Proxy2
        get() = genericConfigVals.proxy

    val totalAgentRequestQueueSize: Int
        get() = agentContextMap.values.map { it.scrapeRequestQueueSize }.sum()

    init {
        if (isMetricsEnabled)
            metrics = ProxyMetrics(this)
        if (configVals.internal.staleAgentCheckEnabled)
            agentCleanupService = AgentContextCleanupService(this).apply {
                addServices(this)
            }
        addServices(grpcService, httpService)
        initService()
    }

    override fun startUp() {
        super.startUp()
        grpcService.startAsync()
        httpService.startAsync()
        if (configVals.internal.staleAgentCheckEnabled)
            agentCleanupService.startAsync()
        else
            logger.info("Agent eviction thread not started")
    }

    override fun shutDown() {
        grpcService.stopAsync()
        httpService.stopAsync()
        if (configVals.internal.staleAgentCheckEnabled)
            agentCleanupService.stopAsync()
        super.shutDown()
    }

    override fun run() {
        while (isRunning)
            sleepForMillis(500)
    }

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        healthCheckRegistry.apply {
            register("grpc_service", grpcService.healthCheck)
            register("scrape_response_map_check",
                     newMapHealthCheck(scrapeRequestMap, configVals.internal.scrapeRequestMapUnhealthySize))
            register("agent_scrape_request_queue",
                     newHealthCheck {
                         val unhealthySize = configVals.internal.scrapeRequestQueueUnhealthySize
                         val vals =
                                 agentContextMap.entries
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

    fun addAgentContext(agentContext: AgentContext) = agentContextMap.put(agentContext.agentId, agentContext)

    fun getAgentContext(agentId: String) = agentContextMap[agentId]

    fun removeAgentContext(agentId: String?): AgentContext? {
        return if (agentId.isNullOrEmpty()) {
            logger.error("Missing agentId")
            null
        }
        else {
            val agentContext = agentContextMap.remove(agentId)
            if (agentContext == null) {
                logger.error("Missing AgentContext for agentId: $agentId")
            }
            else {
                logger.info("Removed $agentContext")
                agentContext.markInvalid()
            }
            agentContext
        }
    }

    fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper) = scrapeRequestMap.put(scrapeRequest.scrapeId,
                                                                                          scrapeRequest)

    fun getFromScrapeRequestMap(scrapeId: Long) = scrapeRequestMap[scrapeId]

    fun removeFromScrapeRequestMap(scrapeId: Long) = scrapeRequestMap.remove(scrapeId)

    fun getAgentContextByPath(path: String) = pathMap[path]

    fun containsPath(path: String) = pathMap.containsKey(path)

    fun pathMapSize() = pathMap.size

    fun addPath(path: String, agentContext: AgentContext) {
        synchronized(pathMap) {
            pathMap.put(path, agentContext)
            if (!isTestMode)
                logger.info("Added path /$path for $agentContext")
        }
    }

    fun removePath(path: String, agentId: String, responseBuilder: UnregisterPathResponse.Builder) {
        synchronized(pathMap) {
            val agentContext = pathMap[path]
            when {
                agentContext == null            -> {
                    val msg = "Unable to remove path /$path - path not found"
                    logger.error(msg)
                    responseBuilder.setValid(false).setReason(msg)
                }
                agentContext.agentId != agentId -> {
                    val msg = "Unable to remove path /$path - invalid agentId: $agentId (owner is ${agentContext.agentId})"
                    logger.error(msg)
                    responseBuilder.setValid(false).setReason(msg)
                }
                else                            -> {
                    pathMap.remove(path)
                    if (!isTestMode)
                        logger.info("Removed path /$path for $agentContext")
                    responseBuilder.setValid(true).setReason("")
                }
            }
        }
    }

    fun removePathByAgentId(agentId: String?) {
        if (agentId.isNullOrEmpty())
            logger.error("Missing agentId")
        else
            synchronized(pathMap) {
                pathMap.forEach { k, v ->
                    if (v.agentId == agentId)
                        pathMap.remove(k)?.let { logger.info("Removed path /$k for $it") } ?: logger.error("Missing path /$k for agentId: $agentId")
                }
            }
    }

    override fun toString() =
            toStringElements {
                add("proxyPort", httpService.port)
                add("adminService", if (isAdminEnabled) adminService else "Disabled")
                add("metricsService", if (isMetricsEnabled) metricsService else "Disabled")
            }

    companion object {
        private val logger = LoggerFactory.getLogger(Proxy::class.java)

        val AGENT_ID = "agent-id"
        val ATTRIB_AGENT_ID: Attributes.Key<String> = Attributes.Key.of(AGENT_ID)

        @JvmStatic
        fun main(argv: Array<String>) {
            val options = ProxyOptions(argv)

            logger.info(getBanner("banners/proxy.txt"))
            logger.info(getVersionDesc(false))

            Proxy(options = options).startAsync()
        }
    }
}
