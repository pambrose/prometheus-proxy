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
import io.prometheus.CommonTests.ProxyCallTestArgs
import io.prometheus.CommonTests.addRemovePathsTest
import io.prometheus.CommonTests.invalidAgentUrlTest
import io.prometheus.CommonTests.invalidPathTest
import io.prometheus.CommonTests.missingPathTest
import io.prometheus.CommonTests.proxyCallTest
import io.prometheus.CommonTests.threadedAddRemovePathsTest
import io.prometheus.CommonTests.timeoutTest
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.Millis
import io.prometheus.common.simpleClassName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class InProcessTestNoAdminMetricsTest {

    @Test
    @KtorExperimentalAPI
    fun missingPathTest() = missingPathTest(simpleClassName)

    @Test
    @KtorExperimentalAPI
    fun invalidPathTest() = invalidPathTest(simpleClassName)

    @Test
    fun addRemovePathsTest() = addRemovePathsTest(AGENT, simpleClassName)

    @Test
    fun threadedAddRemovePathsTest() = threadedAddRemovePathsTest(AGENT, simpleClassName)

    @Test
    @KtorExperimentalAPI
    fun invalidAgentUrlTest() = invalidAgentUrlTest(AGENT, simpleClassName)

    @Test
    @KtorExperimentalAPI
    fun timeoutTest() = timeoutTest(AGENT, simpleClassName)

    @Test
    @InternalCoroutinesApi
    @KtorExperimentalAPI
    fun proxyCallTest() =
        proxyCallTest(
            ProxyCallTestArgs(
                AGENT,
                httpServerCount = 25,
                pathCount = 25,
                sequentialQueryCount = 100,
                sequentialPauseMillis = Millis(25),
                parallelQueryCount = 25,
                caller = simpleClassName,
                startingPort = 10100
            )
        )

    companion object : KLogging() {
        private lateinit var PROXY: Proxy
        private lateinit var AGENT: Agent

        @JvmStatic
        @BeforeClass
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()

            logger.info { "Starting Proxy and Agent" }
            runBlocking {
                launch(Dispatchers.Default) { PROXY = startProxy("nometrics") }
                launch(Dispatchers.Default) {
                    AGENT = startAgent("nometrics").apply { awaitInitialConnection(10, SECONDS) }
                }
            }
            logger.info { "Finished starting Proxy and Agent" }
        }

        @JvmStatic
        @AfterClass
        fun takeDown() {
            logger.info { "Stopping Proxy and Agent" }
            runBlocking {
                launch(Dispatchers.Default) { PROXY.stopSync() }
                launch(Dispatchers.Default) { AGENT.stopSync() }
            }
            logger.info { "Finished stopping Proxy and Agent" }
        }
    }
}