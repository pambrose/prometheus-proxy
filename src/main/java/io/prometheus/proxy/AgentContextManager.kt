package io.prometheus.proxy

import com.google.common.collect.Maps
import java.util.concurrent.ConcurrentMap

class AgentContextManager {
    // Map agent_id to AgentContext
    val agentContextMap: ConcurrentMap<String, AgentContext> = Maps.newConcurrentMap<String, AgentContext>()

    val agentContextSize: Int
        get() = agentContextMap.size

    val totalAgentRequestQueueSize: Int
        get() = agentContextMap.values.map { it.scrapeRequestQueueSize }.sum()


    fun addAgentContext(agentContext: AgentContext) = agentContextMap.put(agentContext.agentId, agentContext)

    fun getAgentContext(agentId: String) = agentContextMap[agentId]

    fun removeAgentContext(agentId: String) = agentContextMap.remove(agentId)

}