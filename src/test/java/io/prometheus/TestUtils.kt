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

import io.prometheus.agent.AgentOptions
import io.prometheus.common.Utils
import io.prometheus.proxy.ProxyOptions
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object TestUtils {

    private val logger = LoggerFactory.getLogger(TestUtils::class.java)

    @Throws(IOException::class, TimeoutException::class)
    fun startProxy(serverName: String?, adminEnabled: Boolean, metricsEnabled: Boolean, argv: List<String>): Proxy {
        val args =
                mutableListOf<String>().apply {
                    addAll(TestConstants.args)
                    addAll(argv)
                    add("-Dproxy.admin.enabled=$adminEnabled")
                    add("-Dproxy.metrics.enabled=$metricsEnabled")
                }
        val options = ProxyOptions(args)

        logger.info(Utils.getBanner("banners/proxy.txt"))
        logger.info(Utils.getVersionDesc(false))

        val proxy = Proxy(options, TestConstants.PROXY_PORT, serverName, true)
        proxy.startAsync()
        proxy.awaitRunning(5, TimeUnit.SECONDS)
        return proxy
    }

    @Throws(IOException::class, TimeoutException::class)
    fun startAgent(serverName: String?, adminEnabled: Boolean, metricsEnabled: Boolean, argv: List<String>): Agent {
        val args =
                mutableListOf<String>().apply {
                    addAll(TestConstants.args)
                    addAll(argv)
                    add("-Dagent.admin.enabled=$adminEnabled")
                    add("-Dagent.metrics.enabled=$metricsEnabled")
                }
        val options = AgentOptions(args, false)

        logger.info(Utils.getBanner("banners/agent.txt"))
        logger.info(Utils.getVersionDesc(false))

        return Agent(options, serverName, true).apply {
            startAsync()
            awaitRunning(5, TimeUnit.SECONDS)
        }
    }
}
