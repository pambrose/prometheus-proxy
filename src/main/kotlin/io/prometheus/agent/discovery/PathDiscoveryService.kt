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

package io.prometheus.agent.discovery

import com.pambrose.common.util.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.agent.AgentPathManager
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polls a [PathDiscoverySource] on an interval and reconciles the agent's discovered paths.
 *
 * Launched by [io.prometheus.Agent] as a per-connection task, so it starts fresh on each connect and
 * is cancelled on disconnect — the reconnect path re-seeds the static baseline, so the next tick
 * re-establishes the discovered set. A read/parse failure is logged and skips the tick, leaving the
 * live (last-known-good) set untouched; only a successful read (including an empty one) reconciles.
 *
 * @param pathManager the manager whose discovered paths are reconciled
 * @param source the discovery source to poll
 * @param intervalSecs seconds between poll-and-reconcile ticks (and the full-resync interval)
 * @see PathDiscoverySource
 * @see io.prometheus.agent.AgentPathManager.reconcileDiscoveredPaths
 */
internal class PathDiscoveryService(
  private val pathManager: AgentPathManager,
  private val source: PathDiscoverySource,
  private val intervalSecs: Int,
) {
  /**
   * Polls and reconciles until [keepRunning] returns false.
   *
   * On a graceful disconnect nothing cancels this coroutine (matching the other connection tasks), so
   * the loop — and the interval wait, which is sliced into short polls — checks [keepRunning] to end
   * promptly rather than blocking a full interval past disconnect or hanging shutdown. The Agent
   * passes `{ isRunning && connectionContext.connected }`.
   */
  suspend fun run(keepRunning: () -> Boolean) {
    logger.info { "Path discovery reconciling every ${intervalSecs.seconds}" }
    while (keepRunning()) {
      reconcileOnce()
      sleepInterval(keepRunning)
    }
    logger.info { "Path discovery completed" }
  }

  // One poll-and-reconcile tick. runCatchingCancellable rethrows CancellationException (so
  // disconnect/shutdown ends the loop) but swallows a read/parse or per-reconcile failure, so a bad
  // tick logs and skips — leaving the live (last-known-good) set untouched — rather than killing discovery.
  internal suspend fun reconcileOnce() {
    runCatchingCancellable {
      val desired = source.read()
      pathManager.reconcileDiscoveredPaths(desired)
    }.onFailure { e -> logger.warn(e) { "Path discovery reconcile failed; keeping current paths" } }
  }

  // Waits out one reconcile interval in short slices, bailing early once keepRunning() is false.
  private suspend fun sleepInterval(keepRunning: () -> Boolean) {
    var remaining = intervalSecs.seconds
    while (remaining > Duration.ZERO && keepRunning()) {
      val slice = minOf(remaining, POLL_SLICE)
      delay(slice)
      remaining -= slice
    }
  }

  companion object {
    private val logger = logger {}
    private val POLL_SLICE = 500.milliseconds
  }
}
