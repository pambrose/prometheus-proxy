/*
 * Copyright © 2025 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.harness

import com.github.pambrose.common.util.simpleClassName
import io.prometheus.harness.HarnessConstants.CONCURRENT_CLIENTS
import io.prometheus.harness.HarnessConstants.DEFAULT_CHUNK_SIZE_BYTES
import io.prometheus.harness.HarnessConstants.DEFAULT_SCRAPE_TIMEOUT_SECS
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.harness.support.AbstractHarnessTests
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.ProxyCallTestArgs
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

class InProcessTestWithAdminMetricsTest :
  AbstractHarnessTests(
    args = ProxyCallTestArgs(
      agent = agent,
      proxyPort = PROXY_PORT,
      startPort = 10700,
      caller = simpleClassName,
    ),
  ) {
  companion object : HarnessSetup() {
    @JvmStatic
    @BeforeAll
    fun setUp() =
      setupProxyAndAgent(
        proxyPort = PROXY_PORT,
        proxySetup = {
          startProxy(
            serverName = "withmetrics",
            adminEnabled = true,
            metricsEnabled = true,
          )
        },
        agentSetup = {
          startAgent(
            serverName = "withmetrics",
            adminEnabled = true,
            metricsEnabled = true,
            scrapeTimeoutSecs = DEFAULT_SCRAPE_TIMEOUT_SECS,
            chunkContentSizeBytes = DEFAULT_CHUNK_SIZE_BYTES,
            maxConcurrentClients = CONCURRENT_CLIENTS,
          )
        },
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeDownProxyAndAgent()
  }
}
