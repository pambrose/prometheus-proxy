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
import io.prometheus.CommonTests.addRemovePathsTest
import io.prometheus.CommonTests.invalidAgentUrlTest
import io.prometheus.CommonTests.invalidPathTest
import io.prometheus.CommonTests.missingPathTest
import io.prometheus.CommonTests.threadedAddRemovePathsTest
import io.prometheus.CommonTests.timeoutTest
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.Millis
import kotlinx.coroutines.InternalCoroutinesApi
import mu.KLogging
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class NettyTestNoAdminMetricsTest {

    @Test
    @KtorExperimentalAPI
    fun missingPathTest() = missingPathTest(javaClass.simpleName)

    @Test
    @KtorExperimentalAPI
    fun invalidPathTest() = invalidPathTest(javaClass.simpleName)

    @Test
    fun addRemovePathsTest() = addRemovePathsTest(AGENT, javaClass.simpleName)

    @Test
    fun threadedAddRemovePathsTest() = threadedAddRemovePathsTest(AGENT, javaClass.simpleName)

    @Test
    @KtorExperimentalAPI
    fun invalidAgentUrlTest() = invalidAgentUrlTest(AGENT, javaClass.simpleName)

    @Test
    @KtorExperimentalAPI
    fun timeoutTest() = timeoutTest(AGENT, javaClass.simpleName)

    @Test
    @InternalCoroutinesApi
    @KtorExperimentalAPI
    fun proxyCallTest() {
        CommonTests.proxyCallTest(
            AGENT,
            httpServerCount = 25,
            pathCount = 50,
            sequentialQueryCount = 500,
            sequentialPauseMillis = Millis(25),
            parallelQueryCount = 100,
            caller = javaClass.simpleName
        )
    }

    companion object : KLogging() {
        private lateinit var PROXY: Proxy
        private lateinit var AGENT: Agent

        @JvmStatic
        @BeforeClass
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            PROXY = startProxy()
            AGENT = startAgent()

            AGENT.awaitInitialConnection(10, SECONDS)
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
