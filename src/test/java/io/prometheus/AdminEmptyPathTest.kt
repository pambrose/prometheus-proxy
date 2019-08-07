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
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.dsl.KtorDsl.blockingGet
import mu.KLogging
import org.amshove.kluent.shouldEqual
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class AdminEmptyPathTest {

    @Test
    @KtorExperimentalAPI
    fun proxyPingPathTest() {
        PROXY.configVals.admin.port shouldEqual 8098
        PROXY.configVals.admin.pingPath shouldEqual ""
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.pingPath}") {
                it.status.value shouldEqual 404
            }
        }
    }

    @Test
    @KtorExperimentalAPI
    fun proxyVersionPathTest() {
        PROXY.configVals.admin.port shouldEqual 8098
        PROXY.configVals.admin.versionPath shouldEqual ""
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.versionPath}") {
                it.status.value shouldEqual 404
            }
        }
    }

    @Test
    @KtorExperimentalAPI
    fun proxyHealthCheckPathTest() {
        PROXY.configVals.admin.healthCheckPath shouldEqual ""
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.healthCheckPath}") {
                it.status.value shouldEqual 404
            }
        }
    }

    @Test
    @KtorExperimentalAPI
    fun proxyThreadDumpPathTest() {
        PROXY.configVals.admin.threadDumpPath shouldEqual ""
        PROXY.configVals.admin.also { admin ->
            blockingGet("${admin.port}/${admin.threadDumpPath}") {
                it.status.value shouldEqual 404
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
                "-Dproxy.admin.port=8098",
                "-Dproxy.admin.pingPath=\"\"",
                "-Dproxy.admin.versionPath=\"\"",
                "-Dproxy.admin.healthCheckPath=\"\"",
                "-Dproxy.admin.threadDumpPath=\"\""
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
