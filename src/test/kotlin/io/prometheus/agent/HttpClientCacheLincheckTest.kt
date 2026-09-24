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
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.utils.io.InternalAPI
import io.prometheus.agent.HttpClientCache.ClientKey
import io.prometheus.common.Lincheck
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

// Lincheck races HttpClientCache's scrapes -- get a client, use it, finish with it -- against LRU eviction, expiry, and
// close(). The cache holds two clients and scrapes use three credentials, so a scrape under the third evicts the least
// recently used client, possibly while a scrape is still using it; and expire() moves the cache's clock past the idle
// limit, so the next scrape of a key replaces its expired client, again possibly while another scrape is using it.
// With room for only one client, replacing an expired one would first evict it, hiding a replacement that forgot to
// close it. A scrape checks it is never handed a closed client and that
// its client isn't closed under it; after every run validateNoLeaks checks that the only open clients are the cached
// ones, or none at all once close() has run.
//
// Each client is a real Ktor HttpClient on a RecordingEngine, which never sends a request and records when the client
// closes it. Not a MockK mock: to mock a final class, MockK rewrites the bytecode of it and its superclasses, Object's
// methods included, for the rest of the JVM, routing every equals() and exception constructor through a lookup keyed by
// identity hash code. Every Lincheck spec run after it in the same JVM then behaves differently when Lincheck replays
// an interleaving, which it reports as "Non-determinism found" -- it failed AgentContextLincheckTest that way. Keep
// MockK out of every Lincheck spec. Stress only, as a real HttpClient is more than model checking can replay.
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

@Param(name = "key", gen = IntGen::class, conf = "0:2")
class HttpClientCacheOps {
  // Entry ages and idle times are measured on this clock, which only expire() moves.
  private val clock = TestTimeSource()

  // Two clients at a time, so the third key evicts one. The sweeper never fires within a run, so an expired client is
  // replaced only when a scrape asks for its key.
  private val cache =
    HttpClientCache(maxCacheSize = 2, maxIdleTime = IDLE_LIMIT, cleanupInterval = 1.hours, timeSource = clock)

  // Every client the cache created, with the engine that records its close.
  private val created = ConcurrentLinkedQueue<HttpClient>()
  private val engines = ConcurrentHashMap<HttpClient, RecordingEngine>()

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
  fun scrape(
    @Param(name = "key") key: Int,
  ) = runBlocking { scrapeOnce(KEYS[key]) }

  private suspend fun scrapeOnce(key: ClientKey) {
    val entry =
      try {
        cache.getOrCreateClient(key) { newClient() }
      } catch (_: IllegalStateException) {
        return
      }
    // As AgentHttpService.fetchContent does, release the client in a finally, which also runs when the scrape is
    // cancelled -- by its timeout, or by the connection dropping.
    try {
      if (entry.client.isClosed())
        violations += "getOrCreateClient handed out a closed client"
      if (entry.client.isClosed())
        violations += "A client was closed while a scrape was using it"
    } finally {
      cache.onFinishedWithClient(entry)
    }
  }

  // HttpClient(factory) owns the engine it creates, so closing the client closes the engine. HttpClient.close() is
  // idempotent, so a second close by the cache would be harmless, and isn't visible here.
  private fun newClient(): HttpClient {
    val engine = RecordingEngine()
    val client =
      HttpClient(
        object : HttpClientEngineFactory<HttpClientEngineConfig> {
          override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine = engine
        },
      )
    engines[client] = engine
    created += client
    return client
  }

  private fun HttpClient.isClosed() = engines.getValue(this).closed

  // Every cached client goes idle past the limit, so the next scrape of its key replaces it.
  @Operation
  fun expire() {
    synchronized(clock) { clock += IDLE_LIMIT * 2 }
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
    val open = created.count { !it.isClosed() }
    if (closed)
      check(open == 0) { "$open clients left open after close()" }
    else
      check(open == cache.currentCacheSize()) { "$open clients open, but the cache holds ${cache.currentCacheSize()}" }
  }

  companion object {
    private val IDLE_LIMIT = 1.minutes
    private val KEYS = listOf(ClientKey(null, null), ClientKey("user-a", "pass-a"), ClientKey("user-b", "pass-b"))
  }
}

// An engine that never sends a request and records when its client closes it.
class RecordingEngine : HttpClientEngineBase("lincheck") {
  override val config = HttpClientEngineConfig()

  @Volatile
  var closed = false
    private set

  @InternalAPI
  override suspend fun execute(data: HttpRequestData): HttpResponseData =
    error("The client cache spec sends no requests")

  override fun close() {
    closed = true
    super.close()
  }
}
