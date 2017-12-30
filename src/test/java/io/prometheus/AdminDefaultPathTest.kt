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
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
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

class AdminDefaultPathTest {

    @Test
    fun proxyPingPathTest() {
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.pingPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string()).startsWith("pong")
        }
    }

    @Test
    fun agentPingPathTest() {
        val url = "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.pingPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string()).startsWith("pong")
        }
    }

    @Test
    fun proxyVersionPathTest() {
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.versionPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string()).contains("Version")
        }
    }

    @Test
    fun agentVersionPathTest() {
        val url = "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.versionPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string()).contains("Version")
        }
    }

    @Test
    fun proxyHealthCheckPathTest() {
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.healthCheckPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use {
            assertThat(it.code()).isEqualTo(200)
            assertThat(it.body()!!.string().length).isGreaterThan(10)
        }
    }

    @Test
    fun agentHealthCheckPathTest() {
        val url = "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.healthCheckPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use { assertThat(it.body()!!.string().length).isGreaterThan(10) }
    }

    @Test
    fun proxyThreadDumpPathTest() {
        val url = "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.threadDumpPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use { assertThat(it.body()!!.string().length).isGreaterThan(10) }
    }

    @Test
    fun agentThreadDumpPathTest() {
        val url = "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.threadDumpPath}"
        val request = Request.Builder().url(url)
        OK_HTTP_CLIENT.newCall(request.build()).execute().use { assertThat(it.body()!!.string().length).isGreaterThan(10) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AdminDefaultPathTest::class.java)

        private var PROXY: Proxy by Delegates.notNull()
        private var AGENT: Agent by Delegates.notNull()

        @JvmStatic
        @BeforeClass
        @Throws(IOException::class, InterruptedException::class, TimeoutException::class)
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            PROXY = startProxy(adminEnabled = true)
            AGENT = startAgent(adminEnabled = true)

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
