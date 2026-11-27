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
import com.google.common.util.concurrent.Service
import com.google.common.util.concurrent.Uninterruptibles
import io.prometheus.Agent
import java.util.concurrent.TimeUnit
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
 * not idiomatic. Equality is the agent's alone: a handle with a
 * [stopTimeout][EmbeddedAgentConfig.Builder.stopTimeout] equals one without it for the same agent, and `copy()` gives
 * one without it.
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
  // How long shutdown waits for the agent to stop: the builder's stopTimeout, or, null, as long as the agent's stop()
  // does, 30 seconds. Outside the constructor, so it isn't part of the handle's equality, and set only by the internal
  // constructor, before the handle is shared; volatile all the same, as a host may stop it from another thread.
  @Volatile
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
   * stop that fails within the timeout throws here, as one without a timeout does. Without one, it waits 30 seconds at
   * most, then throws an [IllegalStateException], whose cause is the `java.util.concurrent.TimeoutException`, and the
   * agent goes on stopping. Either way, an interrupt doesn't end the wait; the calling thread keeps it. An embedded
   * Agent registers no JVM shutdown hook of its own, so call this where the host stops its own services, or from a
   * shutdown hook of the host's, to avoid leaking the gRPC connection and background scrape coroutines past process
   * exit.
   *
   * @throws IllegalStateException when the agent doesn't stop within 30 seconds, without a stop timeout, or when its
   *   stop fails.
   */
  fun shutdown() {
    val timeout = stopTimeout
    if (timeout == null) agent.stop() else stopWithin(timeout)
  }

  // Stops the agent on a thread of its own, waiting for it at most [timeout], through an interrupt, as stop() waits, so
  // a caller that's been interrupted keeps the flag, not an InterruptedException. The thread stops the agent without
  // stop()'s 30-second limit, which would otherwise end a longer wait by throwing. A failure that comes
  // before this stops waiting is thrown here, as the stop's own would be, Error included; one that comes after is
  // logged, since nothing else can be told. The lock decides which, so none is lost between the two, and whether the
  // stop finished, which the thread's being alive can't tell: it's alive for a moment after the stop returns.
  @Suppress("TooGenericExceptionCaught")
  private fun stopWithin(timeout: Duration) {
    val lock = Any()
    var failure: Throwable? = null
    var finished = false
    var abandoned = false
    val stopping =
      thread(name = STOP_THREAD_NAME, isDaemon = true) {
        val thrown =
          try {
            stopWithoutLimit()
            null
          } catch (e: Throwable) {
            e
          }
        synchronized(lock) {
          finished = true
          if (!abandoned)
            failure = thrown
          else if (thrown != null)
            logger.warn(thrown) { "The Prometheus agent $agentName failed as it went on stopping: ${thrown.message}" }
        }
      }
    // Duration.INFINITE is Long.MAX_VALUE nanoseconds, which Guava's join takes: its deadline wraps, as
    // System.nanoTime() does
    Uninterruptibles.joinUninterruptibly(stopping, timeout.inWholeNanoseconds, TimeUnit.NANOSECONDS)
    val failed =
      synchronized(lock) {
        abandoned = !finished
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

  // The agent's stop, as Agent.stop() makes it, a failed agent's included, which has nothing to stop, but waiting for
  // as long as it takes: stop()'s stopSync() gives up at 30 seconds by throwing, which would end a longer stop timeout
  // early, and this one's thread is abandoned once the timeout is up. awaitTerminated() waits uninterruptibly, as
  // stopSync() does.
  private fun stopWithoutLimit() {
    if (agent.state() == Service.State.FAILED)
      return
    agent.stopAsync().awaitTerminated()
  }

  // This agent's handle, with [timeout] as its stop timeout
  internal fun withStopTimeout(timeout: Duration?): EmbeddedAgentInfo =
    if (timeout == null) this else EmbeddedAgentInfo(agent, timeout)

  /** Stops the agent, as [shutdown] does, for Kotlin's `use` and Java's try-with-resources. */
  override fun close() = shutdown()

  private companion object {
    private val logger = logger {}

    // The handle's stop thread, named apart from the Ktor plugin's, which calls shutdown on a thread of its own.
    // Private, or the const compiles to a public field of EmbeddedAgentInfo (November 26 review, #27).
    private const val STOP_THREAD_NAME = "prometheus-agent-shutdown"
  }
}
