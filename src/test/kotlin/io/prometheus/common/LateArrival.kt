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

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The timed wait lateArrival gives its subject, and how long it holds the thread past it.
private val LATE_ARRIVAL_WAIT = 100.milliseconds
private val LATE_ARRIVAL_HOLD = 300.milliseconds

/**
 * What [wait], given a timeout, returns when [arrive] hands it something just as that timeout passes. On
 * runBlocking's one thread, [wait] starts and suspends, then [arrive] runs while this coroutine holds the thread, so
 * the resumption it causes is queued behind this coroutine, and the thread stays held past the timeout, whose timer
 * fires on DefaultExecutor meanwhile. A wait that has taken an element by then, and is then cancelled by its timeout,
 * loses it: the `withTimeoutOrNull { receiveCatching() }` form AGENTS.md rules out (November #46). Nothing here races,
 * so a spec built on it fails every time its subject loses the element.
 */
internal fun <T> lateArrival(
  wait: suspend (timeout: Duration) -> T,
  arrive: suspend () -> Unit,
): T =
  runBlocking {
    val waiting = async { wait(LATE_ARRIVAL_WAIT) }
    // Lets the waiter run until it suspends in its wait.
    yield()
    arrive()
    Thread.sleep(LATE_ARRIVAL_HOLD.inWholeMilliseconds)
    waiting.await()
  }
