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

package io.prometheus.agent

import com.google.common.collect.Maps.newConcurrentMap
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Agent
import io.prometheus.common.GrpcObjects
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import mu.KLogging
import kotlin.time.ExperimentalTime

@KtorExperimentalAPI
@ExperimentalTime
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
class AgentPathManager(private val agent: Agent) {

    private val configVals = agent.genericConfigVals.agent
    private val pathContextMap = newConcurrentMap<String, PathContext>()

    fun clear() {
        pathContextMap.clear()
    }

    operator fun get(path: String): PathContext? = pathContextMap[path]

    private val pathConfigs =
        configVals.pathConfigs
            .map {
                mapOf("name" to it.name,
                      "path" to it.path,
                      "url" to it.url)
            }
            .onEach { logger.info { "Proxy path /${it["path"]} will be assigned to ${it["url"]}" } }
            .toList()

    @Throws(RequestFailureException::class)
    fun registerPaths() =
        pathConfigs.forEach {
            val path = it["path"]
            val url = it["url"]
            if (path != null && url != null)
                registerPath(path, url)
            else
                logger.error { "Null path/url values: $path/$url" }
        }

    @Throws(RequestFailureException::class)
    fun registerPath(pathVal: String, url: String) {
        val path = if (pathVal.startsWith("/")) pathVal.substring(1) else pathVal
        val pathId = registerPathOnProxy(path)
        if (!agent.isTestMode)
            logger.info { "Registered $url as /$path" }
        pathContextMap[path] = PathContext(pathId, path, url)
    }

    @Throws(RequestFailureException::class)
    fun unregisterPath(pathVal: String) {
        val path = if (pathVal.startsWith("/")) pathVal.substring(1) else pathVal
        unregisterPathOnProxy(path)
        val pathContext = pathContextMap.remove(path)
        when {
            pathContext == null -> logger.info { "No path value /$path found in pathContextMap" }
            !agent.isTestMode -> logger.info { "Unregistered /$path for ${pathContext.url}" }
        }
    }

    fun pathMapSize(): Int {
        val request = GrpcObjects.newPathMapSizeRequest(agent.agentId)
        return agent.grpcService.blockingStub.pathMapSize(request)
            .let { resp ->
                agent.markMsgSent()
                resp.pathCount
            }
    }

    @Throws(RequestFailureException::class)
    private fun registerPathOnProxy(path: String): Long {
        val request = GrpcObjects.newRegisterPathRequest(agent.agentId, path)
        return agent.grpcService.blockingStub.registerPath(request)
            .let { resp ->
                agent.markMsgSent()
                if (!resp.valid)
                    throw RequestFailureException("registerPath() - ${resp.reason}")
                resp.pathId
            }
    }

    @Throws(RequestFailureException::class)
    private fun unregisterPathOnProxy(path: String) {
        val request = GrpcObjects.newUnregisterPathRequest(agent.agentId, path)
        agent.grpcService.blockingStub.unregisterPath(request)
            .also { resp ->
                agent.markMsgSent()
                if (!resp.valid)
                    throw RequestFailureException("unregisterPath() - ${resp.reason}")
            }
    }

    companion object : KLogging()

    class PathContext(val pathId: Long, val path: String, val url: String)
}