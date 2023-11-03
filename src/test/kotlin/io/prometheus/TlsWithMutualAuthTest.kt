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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll

class TlsWithMutualAuthTest : CommonTests(
  ProxyCallTestArgs(
    agent = agent,
    startPort = 10800,
    caller = simpleClassName,
  ),
) {
  companion object : CommonCompanion() {
    @JvmStatic
    @BeforeAll
    fun setUp() =
      setItUp(
        proxySetup = {
          startProxy(
            serverName = "withmutualauth",
            argv = listOf(
              "--agent_port",
              "50440",
              "--cert",
              "testing/certs/server1.pem",
              "--key",
              "testing/certs/server1.key",
              "--trust",
              "testing/certs/ca.pem",
            ),
          )
        },
        agentSetup = {
          startAgent(
            serverName = "withmutualauth",
            scrapeTimeoutSecs = DEFAULT_TIMEOUT,
            chunkContentSizeKbs = DEFAULT_CHUNK_SIZE,
            argv = listOf(
              "--proxy",
              "localhost:50440",
              "--cert",
              "testing/certs/client.pem",
              "--key",
              "testing/certs/client.key",
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
    fun takeDown() = takeItDown()
  }
}
