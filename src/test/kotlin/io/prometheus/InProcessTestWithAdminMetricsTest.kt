/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus

import com.github.pambrose.common.util.simpleClassName
import io.prometheus.TestConstants.DEFAULT_CHUNK_SIZE
import io.prometheus.TestConstants.DEFAULT_TIMEOUT
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.common.Utils.lambda
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

class InProcessTestWithAdminMetricsTest :
  CommonTests(ProxyCallTestArgs(agent = agent, startPort = 10700, caller = simpleClassName)) {
  companion object : CommonCompanion() {
    @JvmStatic
    @BeforeAll
    fun setUp() =
      setItUp(
        proxySetup = lambda { startProxy("withmetrics", adminEnabled = true, metricsEnabled = true) },
        agentSetup = lambda {
          startAgent(
            serverName = "withmetrics",
            adminEnabled = true,
            metricsEnabled = true,
            scrapeTimeoutSecs = DEFAULT_TIMEOUT,
            chunkContentSizeKbs = DEFAULT_CHUNK_SIZE,
          )
        },
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}
