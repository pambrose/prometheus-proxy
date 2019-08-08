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
import io.ktor.http.HttpStatusCode
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.simpleClassName
import io.prometheus.dsl.KtorDsl.blockingGet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldStartWith
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

@KtorExperimentalAPI
class AdminNonDefaultPathTest {

    @Test
    fun proxyPingPathTest() {
        proxy.configVals.admin.port shouldEqual 8099
        proxy.configVals.admin.pingPath shouldEqual "pingPath2"
        proxy.configVals.admin
            .also { admin ->
                blockingGet("${admin.port}/${admin.pingPath}".fixUrl()) { resp ->
                    resp.status shouldEqual HttpStatusCode.OK
                    resp.receive<String>() shouldStartWith "pong"
                }
            }
    }

    @Test
    fun proxyVersionPathTest() {
        proxy.configVals.admin.port shouldEqual 8099
        proxy.configVals.admin.versionPath shouldEqual "versionPath2"
        proxy.configVals.admin
            .also { admin ->
                blockingGet("${admin.port}/${admin.versionPath}".fixUrl()) { resp ->
                    resp.status shouldEqual HttpStatusCode.OK
                    resp.receive<String>() shouldContain "Version"
                }
            }
    }

    @Test
    fun proxyHealthCheckPathTest() {
        proxy.configVals.admin.healthCheckPath shouldEqual "healthCheckPath2"
        proxy.configVals.admin
            .also { admin ->
                blockingGet("${admin.port}/${admin.healthCheckPath}".fixUrl()) { resp ->
                    resp.status shouldEqual HttpStatusCode.OK
                    resp.receive<String>().length shouldBeGreaterThan 10
                }
            }
    }

    @Test
    fun proxyThreadDumpPathTest() {
        proxy.configVals.admin.threadDumpPath shouldEqual "threadDumpPath2"
        proxy.configVals.admin
            .also { admin ->
                blockingGet("${admin.port}/${admin.threadDumpPath}".fixUrl()) { resp ->
                    resp.receive<String>().length shouldBeGreaterThan 10
                }
            }
    }

    companion object : KLogging() {
        private lateinit var proxy: Proxy
        private lateinit var agent: Agent

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

            logger.info { "Starting ${proxy.simpleClassName} and ${agent.simpleClassName}" }
            runBlocking {
                launch(Dispatchers.Default) { proxy = startProxy(adminEnabled = true, argv = args) }
                launch(Dispatchers.Default) {
                    agent = startAgent(adminEnabled = true).apply { awaitInitialConnection(5, SECONDS) }
                }
            }
            logger.info { "Finished starting ${proxy.simpleClassName} and ${agent.simpleClassName}" }
        }

        @JvmStatic
        @AfterClass
        @Throws(InterruptedException::class, TimeoutException::class)
        fun takeDown() {
            runBlocking {
                for (service in listOf(proxy, agent)) {
                    logger.info { "Stopping ${service.simpleClassName}" }
                    launch(Dispatchers.Default) { service.stopSync() }
                }
            }
            logger.info { "Finished stopping ${proxy.simpleClassName} and ${agent.simpleClassName}" }
        }
    }
}
