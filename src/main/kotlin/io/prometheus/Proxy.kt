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
import com.google.common.base.MoreObjects
import com.google.common.collect.Maps
import io.grpc.Attributes
import io.prometheus.common.*
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.proxy.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentMap

class Proxy(options: ProxyOptions,
            proxyPort: Int,
            inProcessServerName: String?,
            testMode: Boolean) : GenericService(options.configVals!!,
                                                AdminConfig.create(options.adminEnabled,
                                                                   options.adminPort!!,
                                                                   options.configVals!!.proxy.admin),
                                                MetricsConfig.create(options.metricsEnabled,
                                                                     options.metricsPort!!,
                                                                     options.configVals!!.proxy.metrics),
                                                ZipkinConfig.create(options.configVals!!.proxy.internal.zipkin),
                                                testMode) {

    private val pathMap = Maps.newConcurrentMap<String, AgentContext>() // Map path to AgentContext
    private val scrapeRequestMap = Maps.newConcurrentMap<Long, ScrapeRequestWrapper>() // Map scrape_id to agent_id

    val agentContextMap: ConcurrentMap<String, AgentContext> = Maps.newConcurrentMap<String, AgentContext>() // Map agent_id to AgentContext
    val metrics = if (metricsEnabled) ProxyMetrics(this) else null

    private val httpService = ProxyHttpService(this, proxyPort)
    private val grpcService: ProxyGrpcService =
            if (inProcessServerName.isNullOrBlank())
                ProxyGrpcService.create(this, options.agentPort!!)
            else
                ProxyGrpcService.create(this, inProcessServerName!!)
    private val agentCleanupService =
            if (configVals.internal.staleAgentCheckEnabled)
                AgentContextCleanupService(this)
            else
                null

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
        addServices(grpcService, httpService, agentCleanupService!!)
        initService()
    }

    override fun startUp() {
        super.startUp()
        grpcService.startAsync()
        httpService.startAsync()
        agentCleanupService?.startAsync() ?: logger.info("Agent eviction thread not started")
    }

    override fun shutDown() {
        grpcService.stopAsync()
        httpService.stopAsync()
        agentCleanupService?.stopAsync()
        super.shutDown()
    }

    override fun run() {
        while (isRunning)
            sleepForMillis(500)
    }

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        healthCheckRegistry.register("grpc_service", grpcService.healthCheck)
        healthCheckRegistry.register("scrape_response_map_check",
                                     mapHealthCheck(scrapeRequestMap,
                                                    configVals.internal.scrapeRequestMapUnhealthySize))
        healthCheckRegistry
                .register("agent_scrape_request_queue",
                          object : HealthCheck() {
                              @Throws(Exception::class)
                              override fun check(): HealthCheck.Result {
                                  val unhealthySize = configVals.internal.scrapeRequestQueueUnhealthySize
                                  val vals = agentContextMap.entries
                                          .filter { it.value.scrapeRequestQueueSize >= unhealthySize }
                                          .map { "${it.value} ${it.value.scrapeRequestQueueSize}" }
                                          .toList()
                                  return if (vals.isEmpty())
                                      HealthCheck.Result.healthy()
                                  else
                                      HealthCheck.Result.unhealthy("Large scrapeRequestQueues: ${Joiner.on(", ").join(vals)}")
                              }
                          })
    }

    fun addAgentContext(agentContext: AgentContext) = agentContextMap.put(agentContext.agentId, agentContext)

    fun getAgentContext(agentId: String) = agentContextMap[agentId]

    fun removeAgentContext(agentId: String?): AgentContext? {
        if (agentId.isNullOrBlank()) {
            logger.error("Null agentId")
            return null
        }

        val agentContext = agentContextMap.remove(agentId)
        if (agentContext != null) {
            logger.info("Removed $agentContext")
            agentContext.markInvalid()
        }
        else
            logger.error("Missing AgentContext for agentId: $agentId")

        return agentContext
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
                    logger.info(msg)
                    responseBuilder.setValid(false).setReason(msg)
                }
                agentContext.agentId != agentId -> {
                    val msg = "Unable to remove path /$path - invalid agentId: $agentId (owner is ${agentContext.agentId})"
                    logger.info(msg)
                    responseBuilder.setValid(false).setReason(msg)
                }
                else
                                                -> {
                    pathMap.remove(path)
                    if (!isTestMode)
                        logger.info("Removed path /$path for $agentContext")
                    responseBuilder.setValid(true).setReason("")
                }
            }
        }
    }

    fun removePathByAgentId(agentId: String?) {
        if (agentId.isNullOrEmpty()) {
            logger.info("Null agentId")
            return
        }

        synchronized(pathMap) {
            pathMap.forEach { k, v ->
                if (v.agentId == agentId) {
                    val agentContext = pathMap.remove(k)
                    if (agentContext != null)
                        logger.info("Removed path /$k for $agentContext")
                    else
                        logger.error("Missing path /$k for agentId: $agentId")
                }
            }
        }
    }

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("proxyPort", httpService.port)
                    .add("adminService", adminService ?: "Disabled")
                    .add("metricsService", metricsService ?: "Disabled")
                    .toString()

    companion object {
        private val logger = LoggerFactory.getLogger(Proxy::class.java)

        val AGENT_ID = "agent-id"
        val ATTRIB_AGENT_ID: Attributes.Key<String> = Attributes.Key.of(AGENT_ID)

        @JvmStatic
        fun main(argv: Array<String>) {
            val options = ProxyOptions(argv)

            logger.info(getBanner("banners/proxy.txt"))
            logger.info(getVersionDesc(false))

            val proxy = Proxy(options, options.proxyPort!!, null, false)
            proxy.startAsync()
        }
    }
}
