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
import com.google.common.base.Strings.isNullOrEmpty
import com.google.common.collect.Maps
import io.grpc.Attributes
import io.prometheus.common.*
import io.prometheus.grpc.UnregisterPathResponse
import io.prometheus.proxy.*
import org.slf4j.LoggerFactory
import java.lang.String.format
import java.util.stream.Collectors

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

    val agentContextMap = Maps.newConcurrentMap<String, AgentContext>() // Map agent_id to AgentContext
    private val pathMap = Maps.newConcurrentMap<String, AgentContext>() // Map path to AgentContext
    private val scrapeRequestMap = Maps.newConcurrentMap<Long, ScrapeRequestWrapper>() // Map scrape_id to agent_id

    val metrics: ProxyMetrics?
    private val grpcService: ProxyGrpcService
    private val httpService: ProxyHttpService
    private val agentCleanupService: AgentContextCleanupService?

    val agentContextSize: Int
        get() = this.agentContextMap.size

    val pathMapSize: Int
        get() = this.pathMap.size

    val scrapeMapSize: Int
        get() = this.scrapeRequestMap.size

    val configVals: ConfigVals.Proxy2
        get() = this.genericConfigVals.proxy

    val totalAgentRequestQueueSize: Int
        get() = this.agentContextMap.values
                .stream()
                .mapToInt({ it.scrapeRequestQueueSize() })
                .sum()

    init {

        this.metrics = if (this.metricsEnabled) ProxyMetrics(this) else null
        this.grpcService = if (isNullOrEmpty(inProcessServerName))
            ProxyGrpcService.create(this, options.agentPort!!)
        else
            ProxyGrpcService.create(this, inProcessServerName!!)
        this.httpService = ProxyHttpService(this, proxyPort)
        this.agentCleanupService = if (this.configVals.internal.staleAgentCheckEnabled)
            AgentContextCleanupService(this)
        else
            null

        this.addServices(this.grpcService, this.httpService, this.agentCleanupService!!)
        this.init()
    }

    @Throws(Exception::class)
    override fun startUp() {
        super.startUp()
        this.grpcService.startAsync()
        this.httpService.startAsync()

        if (this.agentCleanupService != null)
            this.agentCleanupService.startAsync()
        else
            logger.info("Agent eviction thread not started")
    }

    @Throws(Exception::class)
    override fun shutDown() {
        this.grpcService.stopAsync()
        this.httpService.stopAsync()
        if (this.agentCleanupService != null)
            this.agentCleanupService.stopAsync()
        super.shutDown()
    }

    override fun run() {
        while (this.isRunning) {
            Utils.sleepForMillis(500)
        }
    }

    override fun registerHealthChecks() {
        super.registerHealthChecks()
        this.healthCheckRegistry.register("grpc_service", this.grpcService.healthCheck)
        this.healthCheckRegistry
                .register("scrape_response_map_check",
                          Utils.mapHealthCheck(scrapeRequestMap, this.configVals.internal.scrapeRequestMapUnhealthySize))
        this.healthCheckRegistry
                .register("agent_scrape_request_queue",
                          object : HealthCheck() {
                              @Throws(Exception::class)
                              override fun check(): HealthCheck.Result {
                                  val unhealthySize = configVals.internal.scrapeRequestQueueUnhealthySize
                                  val vals = getAgentContextMap().entries
                                          .stream()
                                          .filter { kv -> kv.value.scrapeRequestQueueSize() >= unhealthySize }
                                          .map { kv ->
                                              format("%s %d",
                                                     kv.value,
                                                     kv.value.scrapeRequestQueueSize())
                                          }
                                          .collect(Collectors.toList())
                                  return if (vals.isEmpty())
                                      HealthCheck.Result.healthy()
                                  else
                                      HealthCheck.Result.unhealthy(format("Large scrapeRequestQueues: %s",
                                                                          Joiner.on(", ").join(vals)))
                              }
                          })

    }

    fun addAgentContext(agentContext: AgentContext) {
        this.agentContextMap.put(agentContext.agentId, agentContext)
    }

    fun getAgentContext(agentId: String): AgentContext? {
        return this.agentContextMap[agentId]
    }

    fun removeAgentContext(agentId: String?): AgentContext? {
        if (agentId == null) {
            logger.error("Null agentId")
            return null
        }

        val agentContext = this.agentContextMap.remove(agentId)
        if (agentContext != null) {
            logger.info("Removed {}", agentContext)
            agentContext.markInvalid()
        }
        else
            logger.error("Missing AgentContext for agentId: ${agentId}")

        return agentContext
    }

    fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper) {
        this.scrapeRequestMap.put(scrapeRequest.scrapeId, scrapeRequest)
    }

    fun getFromScrapeRequestMap(scrapeId: Long): ScrapeRequestWrapper? {
        return this.scrapeRequestMap[scrapeId]
    }

    fun removeFromScrapeRequestMap(scrapeId: Long): ScrapeRequestWrapper? {
        return this.scrapeRequestMap.remove(scrapeId)
    }

    fun getAgentContextByPath(path: String): AgentContext? {
        return this.pathMap[path]
    }

    fun containsPath(path: String): Boolean {
        return this.pathMap.containsKey(path)
    }

    fun addPath(path: String, agentContext: AgentContext) {
        synchronized(this.pathMap) {
            this.pathMap.put(path, agentContext)
            if (!this.isTestMode)
                logger.info("Added path /{} for {}", path, agentContext)
        }
    }

    fun removePath(path: String, agentId: String,
                   responseBuilder: UnregisterPathResponse.Builder) {
        synchronized(this.pathMap) {
            val agentContext = this.pathMap[path]
            if (agentContext == null) {
                val msg = format("Unable to remove path /%s - path not found", path)
                logger.info(msg)
                responseBuilder.setValid(false).setReason(msg)
            }
            else if (agentContext.agentId != agentId) {
                val msg = format("Unable to remove path /%s - invalid agentId: %s (owner is %s)",
                                 path, agentId, agentContext.agentId)
                logger.info(msg)
                responseBuilder.setValid(false).setReason(msg)
            }
            else {
                this.pathMap.remove(path)
                if (!this.isTestMode)
                    logger.info("Removed path /{} for {}", path, agentContext)
                responseBuilder.setValid(true).setReason("")
            }
        }
    }

    fun removePathByAgentId(agentId: String?) {
        if (agentId == null) {
            logger.info("Null agentId")
            return
        }
        synchronized(this.pathMap) {
            for ((key, value) in this.pathMap) {
                if (value.agentId == agentId) {
                    val agentContext = this.pathMap.remove(key)
                    if (agentContext != null)
                        logger.info("Removed path /$key for $agentContext")
                    else
                        logger.error("Missing path /$key for agentId: $agentId")
                }
            }
        }
    }

    fun pathMapSize(): Int {
        return this.pathMap.size
    }

    fun getAgentContextMap(): Map<String, AgentContext> {
        return this.agentContextMap
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
                .add("proxyPort", this.httpService.port)
                .add("adminService", if (this.adminEnabled) this.adminService else "Disabled")
                .add("metricsService", if (this.metricsEnabled) this.metricsService else "Disabled")
                .toString()
    }

    companion object {

        private val logger = LoggerFactory.getLogger(Proxy::class.java)

        val AGENT_ID = "agent-id"
        val ATTRIB_AGENT_ID: Attributes.Key<String> = Attributes.Key.of(AGENT_ID)

        @JvmStatic
        fun main(argv: Array<String>) {
            val options = ProxyOptions(argv)

            logger.info(Utils.getBanner("banners/proxy.txt"))
            logger.info(Utils.getVersionDesc(false))

            val proxy = Proxy(options, options.proxyPort!!, null, false)
            proxy.startAsync()
        }
    }
}
