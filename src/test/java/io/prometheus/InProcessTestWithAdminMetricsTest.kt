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
import kotlin.properties.Delegates

class InProcessTestWithAdminMetricsTest {

    @Test
    fun missingPathTest() {
        MiscTests.missingPathTest(this.javaClass.simpleName)
    }

    @Test
    fun invalidPathTest() {
        MiscTests.invalidPathTest(this.javaClass.simpleName)
    }

    @Test
    fun addRemovePathsTest() {
        MiscTests.addRemovePathsTest(AGENT, this.javaClass.simpleName)
    }

    @Test
    fun threadedAddRemovePathsTest() {
        MiscTests.threadedAddRemovePathsTest(AGENT, caller = this.javaClass.simpleName)
    }

    @Test
    fun invalidAgentUrlTest() {
        MiscTests.invalidAgentUrlTest(AGENT, caller = this.javaClass.simpleName)
    }

    @Test
    fun timeoutTest() {
        MiscTests.timeoutTest(AGENT, caller = this.javaClass.simpleName)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(InProcessTestWithAdminMetricsTest::class.java)

        private var PROXY: Proxy by Delegates.notNull()
        private var AGENT: Agent by Delegates.notNull()

        @JvmStatic
        @BeforeClass
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            PROXY = startProxy("withmetrics", adminEnabled = true, metricsEnabled = true)
            AGENT = startAgent("withmetrics", adminEnabled = true, metricsEnabled = true)

            AGENT.awaitInitialConnection(10, SECONDS)
        }

        @JvmStatic
        @AfterClass
        fun takeDown() {
            logger.info("Stopping Proxy and Agent")
            PROXY.stopAsync()
            PROXY.awaitTerminated(5, SECONDS)
            AGENT.stopAsync()
            AGENT.awaitTerminated(5, SECONDS)
        }
    }

    // proxyCallTest() called in InProcess tests
}
