/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import io.prometheus.Agent

/**
 * Lifecycle handle for an [Agent] started in the background of a host JVM.
 *
 * Returned by [Agent.startAsyncAgent][io.prometheus.Agent.Companion.startAsyncAgent], this is the
 * supported way for embedders to interact with an Agent they did not construct directly. The
 * underlying [Agent] instance is intentionally hidden so consumers depend only on this stable,
 * minimal surface — identity (for logging and metrics correlation) and shutdown.
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
 * @property launchId Random 15-character identifier generated when the [Agent] was constructed.
 *   Stable for the lifetime of the running process; rotates on every restart, so it is the right
 *   key for distinguishing this run from prior runs of the same `agentName` in metrics labels and
 *   log correlation.
 * @property agentName Human-readable name from [AgentOptions.agentName], falling back to
 *   `Unnamed-<hostname>` when no name is configured. Surfaces in Prometheus metric labels and the
 *   Agent's log output. Unlike [launchId], this is intended to be stable across restarts.
 */
data class EmbeddedAgentInfo(
  private val agent: Agent,
) {
  /** See class-level KDoc. */
  val launchId: String get() = agent.launchId

  /** See class-level KDoc. */
  val agentName: String get() = agent.agentName

  /**
   * Gracefully stops the embedded [Agent].
   *
   * Closes the gRPC channel to the Proxy, shuts down the cached HTTP scrape clients, drains
   * in-flight scrape requests, and releases the admin/metrics servlets. Idempotent at the
   * `GenericService` level — calling twice is safe but only the first invocation does work.
   *
   * Blocks the calling thread until the Agent has finished shutting down. Call this from your
   * host application's shutdown hook (or equivalent) to avoid leaking the gRPC connection and
   * background scrape coroutines past process exit.
   */
  fun shutdown() {
    agent.stop()
  }
}
