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

package io.prometheus.harness.support

import com.github.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.properties.Delegates.notNull
import kotlin.time.Duration.Companion.seconds

open class HarnessSetup {
  private val logger = logger {}
  protected var proxy: Proxy by notNull()
  protected var agent: Agent by notNull()

  protected fun setupProxyAndAgent(
    proxySetup: () -> Proxy,
    agentSetup: () -> Agent,
    actions: () -> Unit = Utils.lambda {},
  ) {
    CollectorRegistry.defaultRegistry.clear()

    // Start the proxy first and then allow the agent to connect
    proxy = proxySetup.invoke()
    agent = agentSetup.invoke().apply { awaitInitialConnection(10.seconds) }

    actions.invoke()

    logger.info { "Started ${proxy.simpleClassName} and ${agent.simpleClassName}" }
  }

  protected fun takeDownProxyAndAgent() {
    runBlocking {
      for (service in listOf(proxy, agent)) {
        logger.info { "Stopping ${service.simpleClassName}" }
        launch(Dispatchers.IO + exceptionHandler(logger)) { service.stopSync() }
      }
    }

    logger.info { "Stopped ${proxy.simpleClassName} and ${agent.simpleClassName}" }
  }
}
