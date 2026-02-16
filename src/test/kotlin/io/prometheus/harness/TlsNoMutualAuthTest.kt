/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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
import io.prometheus.harness.HarnessConstants.DEFAULT_CHUNK_SIZE_BYTES
import io.prometheus.harness.HarnessConstants.DEFAULT_SCRAPE_TIMEOUT_SECS
import io.prometheus.harness.HarnessConstants.HARNESS_CONFIG
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.harness.support.AbstractHarnessTests
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.ProxyCallTestArgs
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy

class TlsNoMutualAuthTest :
  AbstractHarnessTests(
    argsProvider = {
      ProxyCallTestArgs(
        agent = agent,
        proxyPort = PROXY_PORT,
        startPort = 10200,
        caller = simpleClassName,
      )
    },
  ) {
  companion object : HarnessSetup()

  init {
    beforeSpec {
      setupProxyAndAgent(
        proxyPort = PROXY_PORT,
        proxySetup = {
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
        agentSetup = {
          startAgent(
            serverName = "nomutualauth",
            scrapeTimeoutSecs = DEFAULT_SCRAPE_TIMEOUT_SECS,
            chunkContentSizeBytes = DEFAULT_CHUNK_SIZE_BYTES,
            maxConcurrentClients = HARNESS_CONFIG.concurrentClients,
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
    }

    afterSpec {
      takeDownProxyAndAgent()
    }
  }
}
