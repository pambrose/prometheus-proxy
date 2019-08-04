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

import io.ktor.client.call.receive
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.dsl.KtorDsl.blockingGet
import mu.KLogging
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class AdminNonDefaultPathTest {

    @Test
    @KtorExperimentalAPI
    fun proxyPingPathTest() {
        assertThat(PROXY.configVals.admin.port).isEqualTo(8099)
        assertThat(PROXY.configVals.admin.pingPath).isEqualTo("pingPath2")
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.pingPath}") {
                assertThat(it.status.value).isEqualTo(200)
                assertThat(it.receive<String>()).startsWith("pong")
            }
        }
    }

    @Test
    @KtorExperimentalAPI
    fun proxyVersionPathTest() {
        assertThat(PROXY.configVals.admin.port).isEqualTo(8099)
        assertThat(PROXY.configVals.admin.versionPath).isEqualTo("versionPath2")
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.versionPath}") {
                assertThat(it.status.value).isEqualTo(200)
                assertThat(it.receive<String>()).contains("Version")
            }
        }
    }

    @Test
    @KtorExperimentalAPI
    fun proxyHealthCheckPathTest() {
        assertThat(PROXY.configVals.admin.healthCheckPath).isEqualTo("healthCheckPath2")
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.healthCheckPath}") {
                assertThat(it.status.value).isEqualTo(200)
                assertThat(it.receive<String>().length).isGreaterThan(10)
            }
        }
    }

    @Test
    @KtorExperimentalAPI
    fun proxyThreadDumpPathTest() {
        assertThat(PROXY.configVals.admin.threadDumpPath).isEqualTo("threadDumpPath2")
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.threadDumpPath}") {
                assertThat(it.receive<String>().length).isGreaterThan(10)
            }
        }
    }

    companion object : KLogging() {
        private lateinit var PROXY: Proxy
        private lateinit var AGENT: Agent

        @JvmStatic
        @BeforeClass
        @Throws(IOException::class, InterruptedException::class, TimeoutException::class)
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            val args = listOf(
                "-Dproxy.admin.port=8099",
                "-Dproxy.admin.pingPath=pingPath2",
                "-Dproxy.admin.versionPath=versionPath2",
                "-Dproxy.admin.healthCheckPath=healthCheckPath2",
                "-Dproxy.admin.threadDumpPath=threadDumpPath2"
            )
            PROXY = startProxy(adminEnabled = true, argv = args)
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
