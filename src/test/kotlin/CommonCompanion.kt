import com.github.pambrose.common.util.simpleClassName
import io.prometheus.Agent
import io.prometheus.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import kotlin.properties.Delegates.notNull

open class CommonCompanion : KLogging() {
  protected var proxy: Proxy by notNull()
  protected var agent: Agent by notNull()

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