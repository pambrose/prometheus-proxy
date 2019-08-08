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

import io.ktor.util.KtorExperimentalAPI
import io.prometheus.TestConstants.PROXY_PORT
import io.prometheus.agent.AgentOptions
import io.prometheus.common.getBanner
import io.prometheus.common.getVersionDesc
import io.prometheus.proxy.ProxyOptions
import mu.KLogging
import java.io.IOException
import java.util.concurrent.TimeoutException

@KtorExperimentalAPI
object TestUtils : KLogging() {
    @Throws(IOException::class, TimeoutException::class)
    fun startProxy(serverName: String = "",
                   adminEnabled: Boolean = false,
                   metricsEnabled: Boolean = false,
                   argv: List<String> = emptyList()): Proxy {

        logger.info { getBanner("banners/proxy.txt", logger) }
        logger.info { getVersionDesc(false) }

        return Proxy(options =
        ProxyOptions(
            mutableListOf<String>()
                .apply {
                    addAll(TestConstants.args)
                    addAll(argv)
                    add("-Dproxy.admin.enabled=$adminEnabled")
                    add("-Dproxy.metrics.enabled=$metricsEnabled")
                }),
            proxyHttpPort = PROXY_PORT,
            inProcessServerName = serverName,
            testMode = true) { startSync() }
    }

    @Throws(IOException::class, TimeoutException::class)
    fun startAgent(serverName: String = "",
                   adminEnabled: Boolean = false,
                   metricsEnabled: Boolean = false,
                   argv: List<String> = emptyList()): Agent {

        logger.info { getBanner("banners/agent.txt", logger) }
        logger.info { getVersionDesc(false) }

        return Agent(options =
        AgentOptions(
            mutableListOf<String>()
                .apply {
                    addAll(TestConstants.args)
                    addAll(argv)
                    add("-Dagent.admin.enabled=$adminEnabled")
                    add("-Dagent.metrics.enabled=$metricsEnabled")
                },
            false),
            inProcessServerName = serverName,
            testMode = true) { startSync() }
    }
}
