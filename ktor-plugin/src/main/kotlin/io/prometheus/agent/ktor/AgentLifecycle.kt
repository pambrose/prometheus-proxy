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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// The agent of one Application, started and stopped by the app's lifecycle events:
//
//  - ApplicationStarted, once every module has loaded, starts it. Ktor rethrows what a handler throws, so a required
//    agent that can't start fails the app's startup. It's raised before the engine binds its port, and an engine that
//    can't bind throws without disposing the app, so none of the events below come; an embedded agent's thread is a
//    daemon, so the app's JVM still exits, unless the agent's admin or metrics port is on, and Ktor's shutdown hook
//    disposes the app, which stops the agent.
//  - ApplicationStopPreparing, which the engine raises as it stops accepting connections, begins its stop on a thread
//    of its own: the agent disconnects, and the proxy drops the app's paths, while the server drains the requests in
//    flight. Stopping here, on the engine's thread, would take the agent's stop, which can last seconds, out of the
//    server's shutdown grace period, cutting short the app's own requests.
//  - ApplicationStopping, raised once the engine has stopped, as the app is disposed, waits for the stop to finish, or
//    stops the agent itself when the app is disposed without the engine stopping. The agent's handle stops waiting for
//    its stop once the settings' stopTimeout is up, which the plugin sets to 30 seconds unless the file or an agent
//    block sets another, and this waits a second more, for the handle to return. A block that sets none leaves the
//    wait to the handle, which gives up after the agent's own 30 seconds.
//
// The handlers are the app's, not the plugin's: in development mode, a reload disposes the app and creates another on
// the same monitor, so each handler checks that the event is its app's, but for ApplicationStopPreparing's, which
// carries the environment rather than the app, and they're all removed once the app stops.
internal class AgentLifecycle(
  private val application: Application,
  private val config: PrometheusProxyAgentConfig,
  // How the agent starts: a required one with start(), so a failure fails the app's start, and any other with
  // startIfConfigured(), which logs a failure, with why, tells the settings' onFailure handlers, and leaves the agent
  // off. Tests replace it.
  private val launch: (EmbeddedAgentConfig) -> EmbeddedAgentInfo? = { settings ->
    if (config.required) settings.start(logBanner = false) else settings.startIfConfigured(logBanner = false)
  },
) {
  private val agent = AtomicReference<EmbeddedAgentInfo?>()
  private val stopping = AtomicReference<Thread?>()

  // The started agent's settings' stopTimeout, which its handle's shutdown waits for at most; null, as long as the
  // handle's shutdown takes, which gives up after the agent's own 30 seconds
  private val stopTimeout = AtomicReference<Duration?>()

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
    val builder = EmbeddedAgentConfig.builder()
    try {
      config.applyTo(builder)
      launchAgent(builder.build())
    } catch (e: Exception) {
      // A value in the file the builder rejects (ConfigLoadException), an agent block's own failure, or, for a required
      // agent, its start's: a setting the agent rejects, or a failure to start, its start timing out included
      // (IllegalStateException). Kotlin can throw a checked exception undeclared, so Exception is caught, not only
      // RuntimeException. Any other agent's start logs its own failure, tells the handlers, and returns null.
      failed(builder, e)
    }
  }

  private fun launchAgent(settings: EmbeddedAgentConfig) {
    // Decided once the file and the agent blocks have made the settings, by the agent's own rule, which
    // startIfConfigured follows too: a proxy from the builder, a settings key, or the agent's PROXY_HOSTNAME
    if (!settings.namesProxy()) {
      withoutAProxy()
      return
    }
    if (config.registry == null && !config.path.isNullOrBlank())
      logger.warn {
        "prometheus-proxy.agent.path is ${config.path}, but no registry is set, so the agent serves none there"
      }
    logger.info { "Starting the Prometheus agent with $settings" }
    val started = launch(settings) ?: return
    stopTimeout.set(settings.stopTimeout)
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

  // A start that threw e: a required agent fails the app's startup, telling no handler, as start() tells none. Any
  // other stays off, and the builder logs why and tells the onFailure handlers the blocks added, which applyTo ran
  // whatever failed: its settings weren't complete, or startIfConfigured would have told them itself.
  private fun failed(
    builder: EmbeddedAgentConfig.Builder,
    e: Exception,
  ) {
    if (config.required)
      throw e as? RuntimeException ?: IllegalStateException("The Prometheus agent could not start", e)
    builder.reportFailure(e)
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
    val timeout = stopTimeout.get()
    if (timeout == null) {
      thread.join()
      return
    }
    // The handle's shutdown returns once its stop timeout is up; a second more lets it, before the app stops anyway.
    // Added as durations, which saturate, so an infinite timeout waits for as long as the stop takes.
    thread.join((timeout + HANDLE_RETURN_MARGIN_MILLIS.milliseconds).inWholeMilliseconds)
    if (thread.isAlive)
      logger.warn { "The Prometheus agent was still stopping after $timeout; the app stops anyway" }
  }

  private companion object {
    private val logger = logger {}
    const val PROXY_UNSET = "No proxy is set (prometheus-proxy.agent.proxy or PROXY_HOSTNAME)"

    // How long past the stop timeout the app's disposal waits for the handle's shutdown to return
    const val HANDLE_RETURN_MARGIN_MILLIS = 1_000L
  }
}
