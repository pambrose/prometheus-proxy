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

import com.github.pambrose.common.util.simpleClassName
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.time.seconds

class TlsWithMutualAuthTest : CommonTests(agent,
                                          ProxyCallTestArgs(agent,
                                                            httpServerCount = 5,
                                                            pathCount = 50,
                                                            sequentialQueryCount = 200,
                                                            parallelQueryCount = 20,
                                                            startPort = 10500,
                                                            caller = simpleClassName)) {

  companion object : KLogging() {
    private lateinit var proxy: Proxy
    private lateinit var agent: Agent

    @JvmStatic
    @BeforeAll
    fun setUp() {
      CollectorRegistry.defaultRegistry.clear()

      runBlocking {
        launch(Dispatchers.Default) {
          proxy = startProxy(serverName = "withmutualauth",
                             argv = listOf("--agent_port", "50440",
                                           "--cert", "testing/certs/server1.pem",
                                           "--key", "testing/certs/server1.key",
                                           "--trust", "testing/certs/ca.pem"))
        }

        launch(Dispatchers.Default) {
          agent = startAgent(serverName = "withmutualauth",
                             maxContentSizeKbs = 5,
                             argv = listOf("--proxy", "localhost:50440",
                                           "--cert", "testing/certs/client.pem",
                                           "--key", "testing/certs/client.key",
                                           "--trust", "testing/certs/ca.pem",
                                           "--override", "foo.test.google.fr"))
              .apply { awaitInitialConnection(10.seconds) }
        }
      }
      logger.info { "Started ${proxy.simpleClassName} and ${agent.simpleClassName}" }
    }

    @JvmStatic
    @AfterAll
    fun takeDown() {
      runBlocking {
        for (service in listOf(proxy, agent)) {
          logger.info { "Stopping ${service.simpleClassName}" }
          launch(Dispatchers.Default) { service.stopSync() }
        }
      }
      logger.info { "Finished stopping ${proxy.simpleClassName} and ${agent.simpleClassName}" }
    }
  }
}
