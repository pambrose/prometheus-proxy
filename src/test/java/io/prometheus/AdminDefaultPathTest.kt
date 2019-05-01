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

import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.dsl.OkHttpDsl.get
import mu.KLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class AdminDefaultPathTest {

    @Test
    fun proxyPingPathTest() {
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.pingPath}"
                .get {
                    assertThat(it.code()).isEqualTo(200)
                    assertThat(it.body()!!.string()).startsWith("pong")
                }
    }

    @Test
    fun agentPingPathTest() {
        "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.pingPath}"
                .get {
                    assertThat(it.code()).isEqualTo(200)
                    assertThat(it.body()!!.string()).startsWith("pong")
                }
    }

    @Test
    fun proxyVersionPathTest() {
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.versionPath}"
                .get {
                    assertThat(it.code()).isEqualTo(200)
                    assertThat(it.body()!!.string()).contains("Version")
                }
    }

    @Test
    fun agentVersionPathTest() {
        "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.versionPath}"
                .get {
                    assertThat(it.code()).isEqualTo(200)
                    assertThat(it.body()!!.string()).contains("Version")
                }
    }

    @Test
    fun proxyHealthCheckPathTest() {
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.healthCheckPath}"
                .get {
                    assertThat(it.code()).isEqualTo(200)
                    assertThat(it.body()!!.string().length).isGreaterThan(10)
                }
    }

    @Test
    fun agentHealthCheckPathTest() {
        "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.healthCheckPath}"
                .get { assertThat(it.body()!!.string().length).isGreaterThan(10) }
    }

    @Test
    fun proxyThreadDumpPathTest() {
        "http://localhost:${PROXY.configVals.admin.port}/${PROXY.configVals.admin.threadDumpPath}"
                .get { assertThat(it.body()!!.string().length).isGreaterThan(10) }
    }

    @Test
    fun agentThreadDumpPathTest() {
        "http://localhost:${AGENT.configVals.admin.port}/${AGENT.configVals.admin.threadDumpPath}"
                .get { assertThat(it.body()!!.string().length).isGreaterThan(10) }
    }

    companion object : KLogging() {
        private lateinit var PROXY: Proxy
        private lateinit var AGENT: Agent

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
            logger.info { "Stopping Proxy and Agent" }
            PROXY.stopSync()
            AGENT.stopSync()
        }
    }
}
