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
import io.prometheus.common.Utils.lambda
import io.prometheus.harness.support.AbstractHarnessTests
import io.prometheus.harness.support.HarnessConstants.CONCURRENT_SCRAPES
import io.prometheus.harness.support.HarnessConstants.DEFAULT_CHUNK_SIZE
import io.prometheus.harness.support.HarnessConstants.DEFAULT_TIMEOUT
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.ProxyCallTestArgs
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

class TlsNoMutualAuthTest :
  AbstractHarnessTests(
    args = ProxyCallTestArgs(
      agent = agent,
      startPort = 10200,
      caller = simpleClassName,
    ),
  ) {
  companion object : HarnessSetup() {
    @JvmStatic
    @BeforeAll
    fun setUp() =
      setupProxyAndAgent(
        proxySetup = lambda {
          startProxy(
            serverName = "nomutualauth",
            args = listOf(
              "--agent_port",
              "50440",
              "--cert",
              "testing/certs/server1.pem",
              "--key",
              "testing/certs/server1.key",
            ),
          )
        },
        agentSetup = lambda {
          startAgent(
            serverName = "nomutualauth",
            scrapeTimeoutSecs = DEFAULT_TIMEOUT,
            chunkContentSizeKbs = DEFAULT_CHUNK_SIZE,
            maxConcurrentScrapes = CONCURRENT_SCRAPES,
            args = listOf(
              "--proxy",
              "localhost:50440",
              "--trust",
              "testing/certs/ca.pem",
              "--override",
              "foo.test.google.fr",
            ),
          )
        },
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeDownProxyAndAgent()
  }
}
