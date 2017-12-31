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

import io.prometheus.ConstantsTest.PROXY_PORT
import io.prometheus.agent.AgentOptions
import io.prometheus.common.getBanner
import io.prometheus.common.getVersionDesc
import io.prometheus.proxy.ProxyOptions
import okhttp3.Request
import okhttp3.Response
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object TestUtils {

    private val logger = LoggerFactory.getLogger(TestUtils::class.java)

    internal inline fun String.http(block: (Response) -> Unit): Unit {
        ConstantsTest.OK_HTTP_CLIENT
                .newCall(Request.Builder().url(this).build())
                .execute()
                .use(block)
    }

    @Throws(IOException::class, TimeoutException::class)
    fun startProxy(serverName: String = "",
                   adminEnabled: Boolean = false,
                   metricsEnabled: Boolean = false,
                   argv: List<String> = emptyList()): Proxy {
        val args =
                mutableListOf<String>().apply {
                    addAll(ConstantsTest.args)
                    addAll(argv)
                    add("-Dproxy.admin.enabled=$adminEnabled")
                    add("-Dproxy.metrics.enabled=$metricsEnabled")
                }
        val options = ProxyOptions(args)

        logger.info(getBanner("banners/proxy.txt"))
        logger.info(getVersionDesc(false))

        val proxy = Proxy(options = options, proxyPort = PROXY_PORT, inProcessServerName = serverName, testMode = true)
        proxy.startAsync()
        proxy.awaitRunning(5, TimeUnit.SECONDS)
        return proxy
    }

    @Throws(IOException::class, TimeoutException::class)
    fun startAgent(serverName: String = "",
                   adminEnabled: Boolean = false,
                   metricsEnabled: Boolean = false,
                   argv: List<String> = emptyList()): Agent {
        val args =
                mutableListOf<String>().apply {
                    addAll(ConstantsTest.args)
                    addAll(argv)
                    add("-Dagent.admin.enabled=$adminEnabled")
                    add("-Dagent.metrics.enabled=$metricsEnabled")
                }
        val options = AgentOptions(args, false)

        logger.info(getBanner("banners/agent.txt"))
        logger.info(getVersionDesc(false))

        return Agent(options = options, inProcessServerName = serverName, testMode = true).apply {
            startAsync()
            awaitRunning(5, TimeUnit.SECONDS)
        }
    }
}
