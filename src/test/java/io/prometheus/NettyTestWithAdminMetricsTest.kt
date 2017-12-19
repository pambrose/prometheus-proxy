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

import io.prometheus.client.CollectorRegistry
import io.prometheus.common.Utils
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

class NettyTestWithAdminMetricsTest {

    @Test
    @Throws(Exception::class)
    fun missingPathTest() {
        Tests.missingPathTest()
    }

    @Test
    @Throws(Exception::class)
    fun invalidPathTest() {
        Tests.invalidPathTest()
    }

    @Test
    @Throws(Exception::class)
    fun addRemovePathsTest() {
        Tests.addRemovePathsTest(AGENT!!)
    }

    @Test
    @Throws(Exception::class)
    fun threadedAddRemovePathsTest() {
        Tests.threadedAddRemovePathsTest(AGENT!!)
    }

    @Test
    @Throws(Exception::class)
    fun invalidAgentUrlTest() {
        Tests.invalidAgentUrlTest(AGENT!!)
    }

    @Test
    @Throws(Exception::class)
    fun timeoutTest() {
        Tests.timeoutTest(AGENT!!)
    }

    companion object {

        private val logger = LoggerFactory.getLogger(NettyTestWithAdminMetricsTest::class.java)

        private var PROXY: Proxy? = null
        private var AGENT: Agent? = null

        @JvmStatic
        @BeforeClass
        @Throws(IOException::class, InterruptedException::class, TimeoutException::class)
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            PROXY = TestUtils.startProxy(null, true, true, emptyList())
            AGENT = TestUtils.startAgent(null, true, true, emptyList())

            AGENT!!.awaitInitialConnection(10, SECONDS)

            // Wait long enough to trigger heartbeat for code coverage
            Utils.sleepForSecs(15)
        }

        @JvmStatic
        @AfterClass
        @Throws(InterruptedException::class, TimeoutException::class)
        fun takeDown() {
            PROXY!!.stopAsync()
            PROXY!!.awaitTerminated(5, SECONDS)
            AGENT!!.stopAsync()
            AGENT!!.awaitTerminated(5, SECONDS)
        }
    }

    // proxyCallTest() called in InProcess tests
}
