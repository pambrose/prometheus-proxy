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

import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class NettyTestNoAdminMetricsTest {

    @Test
    fun missingPathTest() {
        CommonTests.missingPathTest(javaClass.simpleName)
    }

    @Test
    fun invalidPathTest() {
        CommonTests.invalidPathTest(javaClass.simpleName)
    }

    @Test
    fun addRemovePathsTest() {
        CommonTests.addRemovePathsTest(AGENT, javaClass.simpleName)
    }

    @Test
    fun threadedAddRemovePathsTest() {
        CommonTests.threadedAddRemovePathsTest(AGENT, javaClass.simpleName)
    }

    @Test
    fun invalidAgentUrlTest() {
        CommonTests.invalidAgentUrlTest(AGENT, caller = javaClass.simpleName)
    }

    @Test
    fun timeoutTest() {
        CommonTests.timeoutTest(AGENT, caller = javaClass.simpleName)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(NettyTestNoAdminMetricsTest::class.java)

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
            logger.info("Stopping Proxy and Agent")
            PROXY.stopAsync()
            PROXY.awaitTerminated(5, SECONDS)
            AGENT.stopAsync()
            AGENT.awaitTerminated(5, SECONDS)
        }
    }
}
