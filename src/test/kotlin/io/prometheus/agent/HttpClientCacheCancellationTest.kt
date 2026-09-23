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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.core.spec.style.StringSpec
import io.ktor.client.HttpClient
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.agent.HttpClientCache.ClientKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

// AgentHttpService.fetchContent releases its cached client in a finally, which runs in an already-cancelled coroutine
// when the scrape is cancelled -- by its timeout, or the connection dropping. Kept apart from HttpClientCacheTest,
// which is at detekt's size limit.
class HttpClientCacheCancellationTest : StringSpec() {
  init {
    // If the cache is busy at that moment, the release must still happen, or the client's in-use count never returns
    // to zero and nothing ever closes it. Found by HttpClientCacheLincheckTest.
    "a scrape cancelled while the cache is busy should still release its client" {
      val cache = HttpClientCache(maxCacheSize = 2, cleanupInterval = 1.hours)
      val client = mockk<HttpClient>(relaxed = true)
      val entry = cache.getOrCreateClient(ClientKey(null, null)) { client }

      // Hold the cache's lock: a second key's client factory runs under it and blocks until released.
      val lockHeld = CountDownLatch(1)
      val releaseLock = CountDownLatch(1)
      val holder =
        launch(Dispatchers.IO) {
          cache.getOrCreateClient(ClientKey("user", "pass")) {
            lockHeld.countDown()
            releaseLock.await()
            mockk(relaxed = true)
          }
        }
      lockHeld.await()

      // Undispatched, so the scrape is inside its try before it is cancelled.
      val scrape =
        launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
          try {
            awaitCancellation()
          } finally {
            cache.onFinishedWithClient(entry)
          }
        }
      scrape.cancel()
      // Give the finally time to reach the held lock. Timing can only make this test pass wrongly on a broken cache,
      // never fail a correct one: a release that waits for the lock succeeds whenever the lock is freed.
      delay(100.milliseconds)
      releaseLock.countDown()
      joinAll(holder, scrape)

      cache.close()
      verify(exactly = 1) { client.close() }
    }
  }
}
