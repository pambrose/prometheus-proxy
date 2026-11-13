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
import com.pambrose.common.util.simpleClassName
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.agent.AgentPathManager
import io.prometheus.common.Utils.failureKey
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polls a [PathDiscoverySource] on an interval and reconciles the agent's discovered paths.
 *
 * Launched by [io.prometheus.Agent] as a per-connection task, so it starts on each connect and is cancelled on
 * disconnect. A connect clears every path and registers the static ones, and the first tick registers the discovered
 * set again. A successful read, an empty one included, is reconciled and kept as the last good read. A failed read is
 * logged, and the last good read is reconciled instead: a no-op while its paths are still registered, and it registers
 * them again after a reconnect cleared them, so an agent that reconnects while the file is unreadable still serves the
 * paths it last read (November #11). The agent keeps one instance across connections, so the last good read outlives
 * a reconnect; before the first one, a failed read reconciles nothing.
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
  // The last reconcile's failure, as Utils.failureKey gives it, or null after a success; see reconcileOnce.
  private var lastFailure: String? = null

  // The paths the source last read successfully, or null before the first good read. The live path map can't be the
  // last-known-good set: every connect clears it, so a reconnect while the file was unreadable registered nothing.
  private var lastGoodPaths: List<DiscoveredPath>? = null

  // The entries the last read dropped as unusable, empty for a source that does not report them.
  val skippedEntries: List<DiscoveredPath>
    get() = (source as? SkippedEntryReporter)?.skippedEntries.orEmpty()

  /**
   * Polls and reconciles until it is cancelled or [keepRunning] returns false.
   *
   * The Agent ends it by cancelling it: `Agent.launchConnectionTask` cancels every sibling connection task when any
   * one ends, as one does on a disconnect or a shutdown (November #11). [keepRunning], which the Agent passes as
   * `{ isRunning && connectionContext.connected }`, is only a backstop: the loop checks it before each tick, and the
   * interval wait, which is sliced into short polls, before each slice, so discovery still ends within a slice of the
   * connection closing or the agent stopping, rather than a full interval later, if nothing cancels it.
   */
  suspend fun run(keepRunning: () -> Boolean) {
    logger.info { "Path discovery reconciling every ${intervalSecs.seconds}" }
    // Each connection starts with no discovered paths, so a failure left over from the last one is news again.
    lastFailure = null
    while (keepRunning()) {
      reconcileOnce()
      sleepInterval(keepRunning)
    }
    logger.info { "Path discovery completed" }
  }

  // One poll-and-reconcile tick. runCatchingCancellable rethrows CancellationException (so
  // disconnect/shutdown ends the loop) but swallows a read/parse or per-reconcile failure, so a bad
  // tick logs rather than killing discovery. A failed read reconciles the last list read successfully instead: a
  // no-op while those paths are still registered, and it registers them again after a reconnect cleared them.
  // A missing or malformed file fails every tick, so a failure is logged in full at WARN only when it differs from
  // the last one, and at DEBUG while it repeats; the next success is logged at INFO. A failure with a gRPC status is
  // compared by its code, since a deadline's description carries its elapsed time, and comparing whole messages logged
  // a WARN on every poll (November #39). One without a status is compared by class and message: a file's parse error
  // names its line, so an edit that trades one error for another is news.
  internal suspend fun reconcileOnce() {
    val read = runCatchingCancellable { source.read() }.onSuccess { lastGoodPaths = it }
    runCatchingCancellable {
      (read.getOrNull() ?: lastGoodPaths)?.let { pathManager.reconcileDiscoveredPaths(it) }
      read.getOrThrow()
    }.onSuccess {
      if (lastFailure != null)
        logger.info { "Path discovery reconcile succeeded again" }
      lastFailure = null
    }.onFailure { e ->
      val failure = failureKey(e, withMessage = true)
      if (failure == lastFailure)
        logger.debug {
          "Path discovery reconcile failed again; keeping the paths last read: ${e.simpleClassName}: ${e.message}"
        }
      else
        logger.warn(e) { "Path discovery reconcile failed; keeping the paths last read" }
      lastFailure = failure
    }
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
