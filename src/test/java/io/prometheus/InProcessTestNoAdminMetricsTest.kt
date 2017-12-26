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
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.TimeUnit.SECONDS

class InProcessTestNoAdminMetricsTest {

    @Test
    fun missingPathTest() {
        Tests.missingPathTest()
    }

    @Test
    fun invalidPathTest() {
        Tests.invalidPathTest()
    }

    @Test
    fun addRemovePathsTest() {
        Tests.addRemovePathsTest(AGENT!!)
    }

    @Test
    fun threadedAddRemovePathsTest() {
        Tests.threadedAddRemovePathsTest(AGENT!!)
    }

    @Test
    fun invalidAgentUrlTest() {
        Tests.invalidAgentUrlTest(AGENT!!)
    }

    @Test
    fun timeoutTest() {
        Tests.timeoutTest(AGENT!!)
    }

    @Test
    fun proxyCallTest() {
        Tests.proxyCallTest(AGENT!!, 25, 50, 500, 50)
    }

    companion object {

        private var PROXY: Proxy? = null
        private var AGENT: Agent? = null

        @JvmStatic
        @BeforeClass
        fun setUp() {
            CollectorRegistry.defaultRegistry.clear()
            PROXY = TestUtils.startProxy("nometrics", false, false, emptyList())
            AGENT = TestUtils.startAgent("nometrics", false, false, emptyList())

            AGENT!!.awaitInitialConnection(10, SECONDS)
        }

        @JvmStatic
        @AfterClass
        fun takeDown() {
            PROXY!!.stopAsync()
            PROXY!!.awaitTerminated(5, SECONDS)
            AGENT!!.stopAsync()
            AGENT!!.awaitTerminated(5, SECONDS)
        }
    }

}
