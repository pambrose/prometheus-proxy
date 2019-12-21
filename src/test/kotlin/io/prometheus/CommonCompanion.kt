@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus

import com.github.pambrose.common.util.simpleClassName
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import kotlin.properties.Delegates.notNull
import kotlin.time.seconds

open class CommonCompanion : KLogging() {
  protected var proxy: Proxy by notNull()
  protected var agent: Agent by notNull()

  protected fun setItUp(proxySetup: () -> Proxy, agentSetup: () -> Agent, actions: () -> Unit = {}) {
    CollectorRegistry.defaultRegistry.clear()

    runBlocking {
      launch(Dispatchers.Default) { proxy = proxySetup.invoke() }
      launch(Dispatchers.Default) { agent = agentSetup.invoke().apply { awaitInitialConnection(10.seconds) } }
    }

    actions.invoke()

    logger.info { "Started ${proxy.simpleClassName} and ${agent.simpleClassName}" }
  }

  protected fun takeItDown() {
    runBlocking {
      for (service in listOf(proxy, agent)) {
        logger.info { "Stopping ${service.simpleClassName}" }
        launch(Dispatchers.Default) { service.stopSync() }
      }
    }
    logger.info { "Finished stopping ${proxy.simpleClassName} and ${agent.simpleClassName}" }
  }
}