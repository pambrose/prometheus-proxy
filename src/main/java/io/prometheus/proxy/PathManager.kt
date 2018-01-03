package io.prometheus.proxy

import com.google.common.collect.Maps
import io.prometheus.Proxy
import io.prometheus.grpc.UnregisterPathResponse
import java.util.concurrent.ConcurrentMap

class PathManager(private val isTestMode: Boolean) {
    // Map path to AgentContext
    private val pathMap: ConcurrentMap<String, AgentContext> = Maps.newConcurrentMap<String, AgentContext>()

    fun getAgentContextByPath(path: String) = pathMap[path]

    fun containsPath(path: String) = pathMap.containsKey(path)

    fun pathMapSize() = pathMap.size

    val pathMapSize: Int
        get() = pathMap.size


    fun addPath(path: String, agentContext: AgentContext) {
        synchronized(pathMap) {
            pathMap.put(path, agentContext)
            if (!isTestMode)
                Proxy.logger.info { "Added path /$path for $agentContext" }
        }
    }

    fun removePath(path: String, agentId: String, responseBuilder: UnregisterPathResponse.Builder) {
        synchronized(pathMap) {
            val agentContext = pathMap[path]
            when {
                agentContext == null            -> {
                    val msg = "Unable to remove path /$path - path not found"
                    Proxy.logger.error { msg }
                    responseBuilder
                            .apply {
                                valid = false
                                reason = msg
                            }
                }
                agentContext.agentId != agentId -> {
                    val msg = "Unable to remove path /$path - invalid agentId: $agentId (owner is ${agentContext.agentId})"
                    Proxy.logger.error { msg }
                    responseBuilder
                            .apply {
                                valid = false
                                reason = msg
                            }
                }
                else                            -> {
                    pathMap.remove(path)
                    if (!isTestMode)
                        Proxy.logger.info { "Removed path /$path for $agentContext" }
                    responseBuilder
                            .apply {
                                valid = true
                                reason = ""
                            }
                }
            }
        }
    }

    fun removePathByAgentId(agentId: String?) {
        if (agentId.isNullOrEmpty())
            Proxy.logger.error { "Missing agentId" }
        else
            synchronized(pathMap) {
                pathMap.forEach { k, v ->
                    if (v.agentId == agentId)
                        pathMap.remove(k)
                                ?.let { Proxy.logger.info { "Removed path /$k for $it" } } ?: Proxy.logger.error { "Missing path /$k for agentId: $agentId" }
                }
            }
    }

}