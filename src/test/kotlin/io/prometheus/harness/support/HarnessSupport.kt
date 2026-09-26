/*
 * Copyright © 2026 Paul Ambrose
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

package io.prometheus.harness.support

import com.pambrose.common.service.GenericService
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.common.TestPorts.HARNESS_AGENT_ADMIN_PORT
import io.prometheus.common.TestPorts.HARNESS_AGENT_METRICS_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_ADMIN_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.HARNESS_PROXY_METRICS_PORT
import io.prometheus.common.agentOptions
import io.prometheus.harness.HarnessConstants.CONFIG_ARG
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.common.proxyOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.nio.channels.ClosedSelectorException

object TestUtils {
  private val logger = logger {}

  /**
   * @param proxyPort HTTP port Prometheus scrapes. Override to stand up more than one proxy at a time.
   * @param configArgs the `--config` pair. Overridable rather than appendable because `--config` is a
   *   single-valued JCommander parameter, so a spec needing its own config file must *replace* the
   *   default rather than pass a second one through [args].
   */
  fun startProxy(
    serverName: String = "",
    adminEnabled: Boolean = false,
    debugEnabled: Boolean = false,
    metricsEnabled: Boolean = false,
    args: List<String> = emptyList(),
    proxyPort: Int = PROXY_PORT,
    configArgs: List<String> = CONFIG_ARG,
  ): Proxy {
//    logger.apply {
//      info { getBanner("banners/proxy.txt", logger) }
//      info { getVersionDesc(false) }
//    }

    val proxyOptions = proxyOptions(
      buildList {
        addAll(configArgs)
        // Off the product defaults, which a proxy already running on this machine holds (see TestPorts). Ahead of
        // args, so a spec's own -D port wins: the last -D for a key is the one kept.
        add("-Dproxy.agent.port=$HARNESS_PROXY_AGENT_PORT")
        add("-Dproxy.admin.port=$HARNESS_PROXY_ADMIN_PORT")
        add("-Dproxy.metrics.port=$HARNESS_PROXY_METRICS_PORT")
        addAll(args)
        add("-Dproxy.admin.enabled=$adminEnabled")
        add("-Dproxy.admin.debugEnabled=$debugEnabled")
        add("-Dproxy.metrics.enabled=$metricsEnabled")
      },
    )
    return Proxy(
      options = proxyOptions,
      proxyPort = proxyPort,
      inProcessServerName = serverName,
      testMode = true,
    ) { startSync() }
  }

  fun startAgent(
    serverName: String = "",
    adminEnabled: Boolean = false,
    debugEnabled: Boolean = false,
    metricsEnabled: Boolean = false,
    scrapeTimeoutSecs: Int = -1,
    chunkContentSizeBytes: Int = -1,
    maxConcurrentClients: Int = -1,
    args: List<String> = emptyList(),
    configArgs: List<String> = CONFIG_ARG,
  ): Agent {
//    logger.apply {
//      info { getBanner("banners/agent.txt", logger) }
//      info { getVersionDesc(false) }
//    }

    val agentOptions = agentOptions(
      args = buildList {
        addAll(configArgs)
        // As in startProxy: the harness proxy's gRPC port, and admin and metrics ports off the product defaults.
        add("-Dagent.proxy.port=$HARNESS_PROXY_AGENT_PORT")
        add("-Dagent.admin.port=$HARNESS_AGENT_ADMIN_PORT")
        add("-Dagent.metrics.port=$HARNESS_AGENT_METRICS_PORT")
        addAll(args)
        add("-Dagent.admin.enabled=$adminEnabled")
        add("-Dagent.admin.debugEnabled=$debugEnabled")
        add("-Dagent.metrics.enabled=$metricsEnabled")
        if (scrapeTimeoutSecs != -1)
          add("-Dagent.scrapeTimeoutSecs=$scrapeTimeoutSecs")
        if (chunkContentSizeBytes != -1)
          add("-Dagent.chunkContentSizeBytes=$chunkContentSizeBytes")
        if (maxConcurrentClients != -1)
          add("-Dagent.http.maxConcurrentClients=$maxConcurrentClients")
      },
      exitOnMissingConfig = false,
    )
    return Agent(options = agentOptions, inProcessServerName = serverName, testMode = true) { startSync() }
  }

  /**
   * Stops [services] concurrently, skipping any a test already stopped.
   */
  suspend fun stopAll(vararg services: GenericService<*>) {
    coroutineScope {
      for (service in services.filter { it.isRunning }) {
        logger.info { "Stopping ${service.simpleClassName}" }
        launch(Dispatchers.IO + exceptionHandler(logger)) { service.stopSync() }
      }
    }
  }
}

fun exceptionHandler(logger: KLogger) =
  CoroutineExceptionHandler { _, e ->
    if (e is ClosedSelectorException)
      logger.info { "CoroutineExceptionHandler caught: $e" }
    else
      logger.warn(e) { "CoroutineExceptionHandler caught: $e" }
  }

fun String.withPrefix(prefix: String = "http://localhost:") = if (this.startsWith(prefix)) this else (prefix + this)
