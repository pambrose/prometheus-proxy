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

import io.prometheus.ProxyTests.ProxyCallTestArgs
import io.prometheus.ProxyTests.proxyCallTest
import io.prometheus.ProxyTests.timeoutTest
import io.prometheus.SimpleTests.addRemovePathsTest
import io.prometheus.SimpleTests.invalidAgentUrlTest
import io.prometheus.SimpleTests.invalidPathTest
import io.prometheus.SimpleTests.missingPathTest
import io.prometheus.SimpleTests.threadedAddRemovePathsTest
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.delay
import io.prometheus.common.simpleClassName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeoutException
import kotlin.time.seconds

class NettyTestWithAdminMetricsTest {

    @Test
    fun missingPathTest() = missingPathTest(simpleClassName)

    @Test
    fun invalidPathTest() = invalidPathTest(simpleClassName)

    @Test
    fun addRemovePathsTest() = addRemovePathsTest(agent.pathManager, simpleClassName)

    @Test
    fun threadedAddRemovePathsTest() = threadedAddRemovePathsTest(agent.pathManager, simpleClassName)

    @Test
    fun invalidAgentUrlTest() = invalidAgentUrlTest(agent.pathManager, simpleClassName)

    @Test
    fun timeoutTest() = timeoutTest(agent.pathManager, simpleClassName)

    @Test
    fun proxyCallTest() =
        proxyCallTest(ProxyCallTestArgs(agent.pathManager,
                                        httpServerCount = 5,
                                        pathCount = 25,
                                        sequentialQueryCount = 100,
                                        parallelQueryCount = 250,
                                        startingPort = 10900,
                                        caller = simpleClassName))

    companion object : KLogging() {
        private lateinit var proxy: Proxy
        private lateinit var agent: Agent

        @JvmStatic
        @BeforeAll
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()

            runBlocking {
                launch(Dispatchers.Default) { proxy = startProxy(adminEnabled = true, metricsEnabled = true) }
                launch(Dispatchers.Default) {
                    agent = startAgent(adminEnabled = true, metricsEnabled = true)
                        .apply { awaitInitialConnection(10.seconds) }
                }
            }

            // Wait long enough to trigger heartbeat for code coverage
            runBlocking {
                delay(15.seconds)
            }

            logger.info { "Started ${proxy.simpleClassName} and ${agent.simpleClassName}" }
        }

        @JvmStatic
        @AfterAll
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