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

import io.prometheus.ConstantsTest.OK_HTTP_CLIENT
import io.prometheus.client.CollectorRegistry
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException
import kotlin.properties.Delegates

class AdminNonDefaultPathTest {

    @Test
    fun proxyPingPathTest() {
        assertThat(PROXY.configVals.admin.port).isEqualTo(8099)
        assertThat(PROXY.configVals.admin.pingPath).isEqualTo("pingPath2")
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.pingPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string()).startsWith("pong")
        }
    }

    @Test
    fun proxyVersionPathTest() {
        assertThat(PROXY.configVals.admin.port).isEqualTo(8099)
        assertThat(PROXY.configVals.admin.versionPath).isEqualTo("versionPath2")
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.versionPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string()).contains("Version")
        }
    }

    @Test
    fun proxyHealthCheckPathTest() {
        assertThat(PROXY.configVals.admin.healthCheckPath).isEqualTo("healthCheckPath2")
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.healthCheckPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string().length).isGreaterThan(10)
        }
    }

    @Test
    fun proxyThreadDumpPathTest() {
        assertThat(PROXY.configVals.admin.threadDumpPath).isEqualTo("threadDumpPath2")
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.threadDumpPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use { assertThat(it.body()!!.string().length).isGreaterThan(10) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AdminNonDefaultPathTest::class.java)

        private var PROXY: Proxy by Delegates.notNull()
        private var AGENT: Agent by Delegates.notNull()

        @JvmStatic
        @BeforeClass
        @Throws(IOException::class, InterruptedException::class, TimeoutException::class)
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            val args = listOf("-Dproxy.admin.port=8099",
                              "-Dproxy.admin.pingPath=pingPath2",
                              "-Dproxy.admin.versionPath=versionPath2",
                              "-Dproxy.admin.healthCheckPath=healthCheckPath2",
                              "-Dproxy.admin.threadDumpPath=threadDumpPath2"
                             )
            PROXY = TestUtils.startProxy(adminEnabled = true, argv = args)
            AGENT = TestUtils.startAgent(adminEnabled = true)

            AGENT.awaitInitialConnection(5, SECONDS)
        }

        @JvmStatic
        @AfterClass
        @Throws(InterruptedException::class, TimeoutException::class)
        fun takeDown() {
            logger.info("Stopping Proxy and Agent")
            PROXY.stopAsync()
            PROXY.awaitTerminated(5, SECONDS)
            AGENT.stopAsync()
            AGENT.awaitTerminated(5, SECONDS)
        }
    }
}
