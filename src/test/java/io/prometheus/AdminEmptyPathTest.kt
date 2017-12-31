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

import io.prometheus.TestUtils.http
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class AdminEmptyPathTest {

    @Test
    fun proxyPingPathTest() {
        assertThat(PROXY.configVals.admin.port).isEqualTo(8098)
        assertThat(PROXY.configVals.admin.pingPath).isEqualTo("")
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.pingPath}"
                .http { assertThat(it.code()).isEqualTo(404) }
    }

    @Test
    fun proxyVersionPathTest() {
        assertThat(PROXY.configVals.admin.port).isEqualTo(8098)
        assertThat(PROXY.configVals.admin.versionPath).isEqualTo("")
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.versionPath}"
                .http { assertThat(it.code()).isEqualTo(404) }
    }

    @Test
    fun proxyHealthCheckPathTest() {
        assertThat(PROXY.configVals.admin.healthCheckPath).isEqualTo("")
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.healthCheckPath}"
                .http { assertThat(it.code()).isEqualTo(404) }
    }

    @Test
    fun proxyThreadDumpPathTest() {
        assertThat(PROXY.configVals.admin.threadDumpPath).isEqualTo("")
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.threadDumpPath}"
                .http { assertThat(it.code()).isEqualTo(404) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AdminEmptyPathTest::class.java)

        private lateinit var PROXY: Proxy
        private lateinit var AGENT: Agent

        @JvmStatic
        @BeforeClass
        @Throws(IOException::class, InterruptedException::class, TimeoutException::class)
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            val args = listOf("-Dproxy.admin.port=8098",
                              "-Dproxy.admin.pingPath=\"\"",
                              "-Dproxy.admin.versionPath=\"\"",
                              "-Dproxy.admin.healthCheckPath=\"\"",
                              "-Dproxy.admin.threadDumpPath=\"\"")
            PROXY = startProxy(adminEnabled = true, argv = args)
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
