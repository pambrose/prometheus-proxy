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
import io.prometheus.agent.HttpClientCache.ClientKey
import io.prometheus.common.Lincheck
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.hours

// Lincheck races HttpClientCache's scrapes -- get a client, use it, finish with it -- against LRU eviction and close().
// The cache holds one client, so a scrape under the other key evicts it, possibly while a scrape is still using it. A
// scrape checks it is never handed a closed client and that its client isn't closed under it; after every run
// validateNoLeaks checks that the only open clients are the cached ones, or none at all once close() has run.
//
// The clients are MockK stand-ins that record their close(): only whether and when the cache closes a client matters,
// and real Ktor clients bring in their engines' own threads and coroutines. Stress only, as MockK's generated classes
// are more than Lincheck's model checking can replay deterministically.
class HttpClientCacheLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "client cache never closes a client in use or leaks one under stress" {
      StressOptions()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(HttpClientCacheOps::class)
    }
  }

  companion object {
    private const val ITERATIONS = 30
    private const val INVOCATIONS = 300
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 3
  }
}

class HttpClientCacheOps {
  // One client at a time, so the second key evicts the first. The sweeper never fires within a run.
  private val cache = HttpClientCache(maxCacheSize = 1, cleanupInterval = 1.hours)

  // Every client the cache created, and the ones it has closed.
  private val created = ConcurrentLinkedQueue<HttpClient>()
  private val closedClients = ConcurrentHashMap.newKeySet<HttpClient>()

  @Volatile
  private var closed = false

  // Lincheck records an exception thrown by an operation as its result, not as a failure, so operations note what
  // they saw go wrong and validateNoLeaks reports it.
  private val violations = ConcurrentLinkedQueue<String>()

  // What AgentHttpService does for one scrape: get the client for the target's credentials, use it, and finish with
  // it. A closed cache refuses the scrape, as it does for a scrape racing agent shutdown.
  //
  // Blocking rather than suspending, like closeCache: Lincheck's stress runner loses track of an operation suspended on
  // the cache's Mutex and reports it as never resuming (20,000 real-thread runs of the same scrapes never hang), and it
  // counts a cancelled operation as finished before its finally has released the client. Inside runBlocking, a scrape
  // waits for the Mutex as it does on the agent's own threads. Cancellation during release is covered by
  // HttpClientCacheTest instead.
  @Operation(blocking = true)
  fun scrape(auth: Boolean) = runBlocking { scrapeOnce(auth) }

  private suspend fun scrapeOnce(auth: Boolean) {
    val key = if (auth) ClientKey("user", "pass") else ClientKey(null, null)
    val entry =
      try {
        cache.getOrCreateClient(key) { newClient() }
      } catch (_: IllegalStateException) {
        return
      }
    // As AgentHttpService.fetchContent does, release the client in a finally, which also runs when the scrape is
    // cancelled -- by its timeout, or by the connection dropping.
    try {
      if (entry.client in closedClients)
        violations += "getOrCreateClient handed out a closed client"
      if (entry.client in closedClients)
        violations += "A client was closed while a scrape was using it"
    } finally {
      cache.onFinishedWithClient(entry)
    }
  }

  private fun newClient(): HttpClient =
    mockk<HttpClient>().also { client ->
      every { client.close() } answers {
        if (!closedClients.add(client))
          violations += "A client was closed twice"
      }
      created += client
    }

  @Operation(blocking = true)
  fun closeCache() =
    runBlocking {
      cache.close()
      closed = true
    }

  @Validate
  fun validateNoLeaks() {
    check(violations.isEmpty()) { violations.joinToString("; ") }
    val open = created.count { it !in closedClients }
    if (closed)
      check(open == 0) { "$open clients left open after close()" }
    else
      check(open == cache.currentCacheSize()) { "$open clients open, but the cache holds ${cache.currentCacheSize()}" }
  }
}
