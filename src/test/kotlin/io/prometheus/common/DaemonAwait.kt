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

import kotlinx.coroutines.future.await
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

// Runs [block] on a daemon thread and suspends until it returns, so a test's Kotest timeout can fail a call that runs
// far too long. A CPU-bound loop such as a backtracking regex ignores coroutine cancellation and Thread.interrupt
// alike, so run in the test's own thread it held the test, even with blockingTest, until it finished. Awaiting here is
// a suspension point the timeout cancels; the abandoned thread keeps running, but as a daemon it doesn't hold the JVM.
internal suspend fun <T> awaitOnDaemonThread(block: () -> T): T {
  val result = CompletableFuture<T>()
  thread(isDaemon = true, name = "awaitOnDaemonThread") {
    runCatching(block).fold(result::complete, result::completeExceptionally)
  }
  return result.await()
}
