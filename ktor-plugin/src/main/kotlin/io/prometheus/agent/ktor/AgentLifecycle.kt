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

package io.prometheus.agent.ktor

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ApplicationStopping
import io.prometheus.agent.EmbeddedAgentConfig
import io.prometheus.agent.EmbeddedAgentInfo
import kotlinx.coroutines.DisposableHandle
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// The agent of one Application, started and stopped by the app's lifecycle events:
//
//  - ApplicationStarted, once every module has loaded, starts it. Ktor rethrows what a handler throws, so a required
//    agent that can't start fails the app's startup.
//  - ApplicationStopPreparing, which the engine raises as it stops accepting connections, begins its stop on a thread
//    of its own: the agent disconnects, and the proxy drops the app's paths, while the server drains the requests in
//    flight. Stopping here, on the engine's thread, would take the agent's stop, which can last seconds, out of the
//    server's shutdown grace period, cutting short the app's own requests.
//  - ApplicationStopping, raised once the engine has stopped, as the app is disposed, waits for the stop to finish, or
//    stops the agent itself when the app is disposed without the engine stopping.
//
// The handlers are the app's, not the plugin's: in development mode, a reload disposes the app and creates another on
// the same monitor, so each handler checks that the event is its app's, but for ApplicationStopPreparing's, which
// carries the environment rather than the app, and they're all removed once the app stops.
internal class AgentLifecycle(
  private val application: Application,
  private val config: PrometheusProxyAgentConfig,
  // How long the app's disposal waits for the agent to stop; tests shorten it
  private val stopTimeoutMillis: Long = STOP_TIMEOUT_MILLIS,
  // How the agent starts: a required one with start(), so a failure fails the app's start, and any other with
  // startIfConfigured(), which logs a failure, with why, and leaves the agent off. Tests replace it.
  private val launch: (EmbeddedAgentConfig) -> EmbeddedAgentInfo? = { settings ->
    if (config.required) settings.start(logBanner = false) else settings.startIfConfigured(logBanner = false)
  },
) {
  private val agent = AtomicReference<EmbeddedAgentInfo?>()
  private val stopping = AtomicReference<Thread?>()

  fun subscribe() {
    val monitor = application.monitor
    val handles = mutableListOf<DisposableHandle>()
    handles += monitor.subscribe(ApplicationStarted) { if (it === application) start() }
    handles += monitor.subscribe(ApplicationStopPreparing) { beginStop() }
    handles += monitor.subscribe(ApplicationStopping) { if (it === application) finishStop() }
    handles +=
      monitor.subscribe(ApplicationStopped) { if (it === application) handles.forEach(DisposableHandle::dispose) }
  }

  @Suppress("TooGenericExceptionCaught")
  fun start() {
    if (!config.enabled) {
      logger.info { "prometheus-proxy.agent.enabled is false, so the Prometheus agent stays off" }
      return
    }
    try {
      launchAgent()
    } catch (e: Exception) {
      // A value in the file the builder rejects (ConfigLoadException), an agent block's own failure, or, for a required
      // agent, its start's: a setting the agent rejects, or a checked exception, which Kotlin throws undeclared, such
      // as the start timing out. Any other agent's start logs its own failure and returns null.
      failed(e)
    }
  }

  private fun launchAgent() {
    val builder = config.builder()
    if (builder.proxy.isNullOrBlank()) {
      withoutAProxy()
      return
    }
    if (config.registry == null && !config.path.isNullOrBlank())
      logger.warn {
        "prometheus-proxy.agent.path is ${config.path}, but no registry is set, so the agent serves none there"
      }
    val settings = builder.build()
    logger.info { "Starting the Prometheus agent with $settings" }
    val started = launch(settings) ?: return
    agent.set(started)
    application.attributes.put(AgentKey, started)
    // No proxy named: the agent's own PROXY_HOSTNAME can override the setting's. The line above gives the settings, and
    // the agent logs the proxy it connects to.
    logger.info { "Prometheus agent ${started.agentName} started" }
  }

  // No proxy: the agent stays off, as on a developer's machine, unless it's required
  private fun withoutAProxy() {
    check(!config.required) { "$PROXY_UNSET, and prometheus-proxy.agent.required is true" }
    logger.info { "$PROXY_UNSET, so the Prometheus agent stays off" }
  }

  // A start that threw e: a required agent fails the app's startup, and any other stays off, logging why
  private fun failed(e: Exception) {
    if (config.required)
      throw e as? RuntimeException ?: IllegalStateException("The Prometheus agent could not start", e)
    logger.error { "The Prometheus agent could not start, so it stays off: ${e.message ?: e::class.qualifiedName}" }
  }

  fun beginStop() {
    val started = agent.getAndSet(null) ?: return
    application.attributes.remove(AgentKey)
    logger.info { "Stopping the Prometheus agent ${started.agentName}" }
    stopping.set(
      thread(name = "prometheus-agent-stop", isDaemon = true) {
        runCatching { started.shutdown() }
          .onFailure { logger.warn(it) { "The Prometheus agent ${started.agentName} did not stop cleanly" } }
      },
    )
  }

  fun finishStop() {
    // Begins the stop here when the engine didn't
    beginStop()
    val thread = stopping.getAndSet(null) ?: return
    thread.join(stopTimeoutMillis)
    if (thread.isAlive)
      logger.warn { "The Prometheus agent was still stopping after ${stopTimeoutMillis}ms; the app stops anyway" }
  }

  private companion object {
    private val logger = logger {}
    const val PROXY_UNSET = "prometheus-proxy.agent.proxy isn't set"

    // As long as Spring waits for a lifecycle phase to stop, which is how long the Spring Boot starter's agent gets
    const val STOP_TIMEOUT_MILLIS = 30_000L
  }
}
