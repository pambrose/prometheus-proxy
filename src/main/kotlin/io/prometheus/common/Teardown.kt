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

package io.prometheus.common

import com.google.common.util.concurrent.Service
import java.util.concurrent.TimeUnit.SECONDS

// How the proxy and the agent release what they hold, as they stop and when their startup fails. Every step runs, even
// after one that throws, such as a sub-service that fails to stop, or doesn't within its timeout, so one step can't
// leave the ports, threads, shared metrics, or tracing the later ones release behind.

// As long as GenericIdleService.stopSync() waits for a service to stop.
private const val STOP_TIMEOUT_SECS = 30L

/**
 * Runs [start], and when it throws, runs every one of [release] and rethrows what [start] threw, with what they threw
 * attached as suppressed exceptions.
 */
internal fun startOrRelease(
  release: List<() -> Unit>,
  start: () -> Unit,
) {
  runCatching(start).onFailure { e ->
    release.runEach().forEach(e::addSuppressed)
    throw e
  }
}

/** Runs every step, including those after one that throws, and rethrows the first failure, with the rest suppressed. */
internal fun List<() -> Unit>.runEachOrThrow() {
  val failures = runEach()
  failures.firstOrNull()?.let { first ->
    failures.drop(1).forEach(first::addSuppressed)
    throw first
  }
}

/**
 * Stops this service and waits for it to terminate, as common-utils' stopSync() does, unless it has FAILED: a service
 * that failed has gone as far toward stopping as it can, and the wait would throw. One that never started terminates
 * at once.
 */
internal fun Service.stopUnlessFailed() {
  if (state() != Service.State.FAILED) {
    stopAsync()
    awaitTerminated(STOP_TIMEOUT_SECS, SECONDS)
  }
}

// Runs every step, including those after one that throws, and returns what they threw.
private fun List<() -> Unit>.runEach(): List<Throwable> = mapNotNull { step -> runCatching(step).exceptionOrNull() }
