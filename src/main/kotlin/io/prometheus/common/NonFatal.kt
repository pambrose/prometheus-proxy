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

import kotlin.coroutines.cancellation.CancellationException

/**
 * What [block] returns, or the exception it throws, as [runCatching] gives them, but for two that are rethrown: a
 * CancellationException, so a coroutine's cancellation isn't taken for a failure, as common-utils'
 * runCatchingCancellable does, and a JVM [Error], such as OutOfMemoryError.
 *
 * For the agent's connection tasks, response streams, heartbeat, discovery tick, and per-path calls, which caught an
 * Error as any other failure, so an agent out of heap ended a connection and reconnected, running on in the state an
 * Error can leave the JVM in (November #7). Rethrown, it fails the connection, and Agent.handleConnectionFailure
 * rethrows it from the run loop, so the agent stops, as a scrape's Error stops it.
 */
internal suspend fun <R> runCatchingNonFatal(block: suspend () -> R): Result<R> =
  runCatching { block() }.onFailure { if (it is CancellationException || it is Error) throw it }
