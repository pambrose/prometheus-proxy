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

package io.prometheus.agent

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import kotlin.concurrent.thread
import kotlin.time.Duration

/**
 * Lifecycle handle for an [Agent] started in the background of a host JVM.
 *
 * Returned by [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent], [EmbeddedAgentConfig.start], and
 * [EmbeddedAgentConfig.startIfConfigured], this is the supported way for embedders to interact with an Agent they did
 * not construct directly. The
 * underlying [Agent] instance is intentionally hidden so consumers depend only on this stable,
 * minimal surface — identity (for logging and metrics correlation), its connection to the proxy, and shutdown.
 *
 * The handle is a Kotlin `data class` purely to inherit value-equality and `toString()`; do not
 * `copy()` or destructure it. The `private val agent` is the canonical owner of the running Agent,
 * and holding multiple `EmbeddedAgentInfo` instances pointing at the same Agent is supported but
 * not idiomatic.
 *
 * ## Example
 *
 * ```kotlin
 * val info = Agent.startAsyncAgent("/etc/prom-proxy/agent.conf", exitOnMissingConfig = false)
 * logger.info { "Started agent ${info.agentName} (launchId=${info.launchId})" }
 * // ... later, during host-app shutdown:
 * info.shutdown()
 * ```
 *
 * It's [AutoCloseable], [close] doing what [shutdown] does, so an agent that runs while a block does is stopped after
 * it by Kotlin's `use` or Java's try-with-resources:
 *
 * ```kotlin
 * embeddedAgent {
 *   name = "worker"
 *   fromEnvironment()
 *   source(path = "worker_metrics", registry = registry)
 * }.startIfConfigured().use { worker.run() }
 * ```
 *
 * @property launchId Random 15-character identifier generated when the [Agent] was constructed.
 *   Stable for the lifetime of this agent; every agent gets its own, whether after a restart or
 *   alongside it in the same JVM, so it is the right key for distinguishing this run from prior
 *   runs, and from other agents, of the same `agentName` in metrics labels and log correlation.
 * @property agentName Human-readable name from [AgentOptions.agentName], falling back to
 *   `Unnamed-<hostname>` when no name is configured. Surfaces in Prometheus metric labels and the
 *   Agent's log output. Unlike [launchId], this is intended to be stable across restarts.
 */
data class EmbeddedAgentInfo(
  private val agent: Agent,
) : AutoCloseable {
  // How long shutdown waits for the agent to stop: the builder's stopTimeout, or, null, for as long as the stop takes
  internal var stopTimeout: Duration? = null
    private set

  // A handle whose shutdown waits at most [stopTimeout] for the agent to stop
  internal constructor(agent: Agent, stopTimeout: Duration?) : this(agent) {
    this.stopTimeout = stopTimeout
  }

  /** See class-level KDoc. */
  val launchId: String get() = agent.launchId

  /** See class-level KDoc. */
  val agentName: String get() = agent.agentName

  /**
   * Whether the agent is connected to a proxy and has registered its paths there, so Prometheus can scrape through it
   * now. False before its first connection, while it reconnects after losing one, and once it has stopped. Read live
   * each time, so a host's health check can report it, though without failing the app's health: a lost proxy
   * shouldn't take the app down.
   */
  val isConnected: Boolean get() = agent.isConnectedToProxy

  /** The proxy the agent is connected to, as `host:port`, or null when it isn't ([isConnected]). */
  val proxyEndpoint: String? get() = agent.connectedProxyEndpoint

  /**
   * Gracefully stops the embedded [Agent].
   *
   * Closes the gRPC channel to the Proxy, shuts down the cached HTTP scrape clients, drains
   * in-flight scrape requests, and releases the admin/metrics servlets. Idempotent at the
   * `GenericService` level — calling twice is safe but only the first invocation does work. Also safe on an
   * agent that has failed: a failed agent has already released what it held, so there is nothing to stop.
   *
   * Blocks the calling thread until the Agent has finished shutting down, or, for an agent whose settings give a
   * [stopTimeout][EmbeddedAgentConfig.Builder.stopTimeout], until that's up: the agent then goes on stopping on a
   * daemon thread of its own, with a warning in the log, so a host's shutdown fits the window its platform gives it. A
   * stop that fails within the timeout throws here, as one without a timeout does. An embedded Agent registers no JVM
   * shutdown hook of its own, so call this where the host stops its own services, or from a shutdown hook of the
   * host's, to avoid leaking the gRPC connection and background scrape coroutines past process exit.
   */
  fun shutdown() {
    val timeout = stopTimeout
    if (timeout == null) agent.stop() else stopWithin(timeout)
  }

  // Stops the agent on a thread of its own, waiting for it at most [timeout]. A failure that comes before this stops
  // waiting is thrown here, as the stop's own would be, Error included; one that comes after is logged, since nothing
  // else can be told. The lock decides which, so none is lost between the two.
  @Suppress("TooGenericExceptionCaught")
  private fun stopWithin(timeout: Duration) {
    val lock = Any()
    var failure: Throwable? = null
    var abandoned = false
    val stopping =
      thread(name = "prometheus-agent-stop", isDaemon = true) {
        try {
          agent.stop()
        } catch (e: Throwable) {
          synchronized(lock) {
            if (abandoned)
              logger.warn(e) { "The Prometheus agent $agentName failed as it went on stopping: ${e.message}" }
            else
              failure = e
          }
        }
      }
    // At least a millisecond, since join(0) waits for as long as the stop takes
    stopping.join(timeout.inWholeMilliseconds.coerceAtLeast(1))
    val failed =
      synchronized(lock) {
        if (failure == null) abandoned = stopping.isAlive
        failure
      }
    when {
      failed != null -> {
        throw failed
      }

      abandoned -> {
        logger.warn {
          "The Prometheus agent $agentName was still stopping after $timeout, and goes on stopping on its own"
        }
      }
    }
  }

  // This agent's handle, with [timeout] as its stop timeout
  internal fun withStopTimeout(timeout: Duration?): EmbeddedAgentInfo =
    if (timeout == null) this else EmbeddedAgentInfo(agent, timeout)

  /** Stops the agent, as [shutdown] does, for Kotlin's `use` and Java's try-with-resources. */
  override fun close() = shutdown()

  private companion object {
    private val logger = logger {}
  }
}
