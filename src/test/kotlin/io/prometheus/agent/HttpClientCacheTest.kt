/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.agent.HttpClientCache.CacheEntry
import io.prometheus.agent.HttpClientCache.ClientKey
import io.prometheus.agent.HttpClientCache.ClientKey.Companion.NO_AUTH
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpClientCacheTest : StringSpec() {
  private lateinit var cache: HttpClientCache
  private var clientCount = 0

  private fun createMockHttpClient(): HttpClient {
    clientCount++
    return HttpClient(CIO) {
      expectSuccess = false
    }
  }

  init {
    beforeEach {
      clientCount = 0
      cache = HttpClientCache(
        maxCacheSize = 5,
        maxAge = 1.seconds,
        maxIdleTime = 500.milliseconds,
        cleanupInterval = 100.milliseconds,
      )
    }

    afterEach {
      cache.close()
    }

    "should cache and reuse clients for same key" {
      val key = ClientKey("user1", "pass1")

      // First request should create a new client
      val entry1 = cache.getOrCreateClient(key) { createMockHttpClient() }
      cache.currentCacheSize() shouldBe 1

      // Second request should reuse the same client
      val entry2 = cache.getOrCreateClient(key) { createMockHttpClient() }
      cache.currentCacheSize() shouldBe 1

      // Should be the same client instance
      entry1.client shouldBe entry2.client

      // Clean up
      cache.onFinishedWithClient(entry1)
      cache.onFinishedWithClient(entry2)
    }

    "should create different clients for different keys" {
      val key1 = ClientKey("user1", "pass1")
      val key2 = ClientKey("user2", "pass2")

      val entry1 = cache.getOrCreateClient(key1) { createMockHttpClient() }
      val entry2 = cache.getOrCreateClient(key2) { createMockHttpClient() }

      cache.currentCacheSize() shouldBe 2
      entry1.client shouldNotBe entry2.client

      // Clean up
      cache.onFinishedWithClient(entry1)
      cache.onFinishedWithClient(entry2)
    }

    "should handle null username and password" {
      val key1 = ClientKey(null, null)
      val key2 = ClientKey("user1", null)
      val key3 = ClientKey(null, "pass1")

      val entry1 = cache.getOrCreateClient(key1) { createMockHttpClient() }
      val entry2 = cache.getOrCreateClient(key2) { createMockHttpClient() }
      val entry3 = cache.getOrCreateClient(key3) { createMockHttpClient() }

      cache.currentCacheSize() shouldBe 1

      // All should be the same client (all map to NO_AUTH cache key)
      entry1.client shouldBe entry2.client
      entry2.client shouldBe entry3.client
      entry1.client shouldBe entry3.client

      // Clean up
      cache.onFinishedWithClient(entry1)
      cache.onFinishedWithClient(entry2)
      cache.onFinishedWithClient(entry3)
    }

    "should evict least recently used client when cache is full" {
      // Use a dedicated cache with long expiry to prevent the cleanup coroutine
      // from removing idle entries during the test. The shared cache's 500ms idle
      // time can be exceeded under load (e.g. when running the full test suite),
      // causing the cleanup coroutine to shrink the cache before the eviction step.
      val lruCache = HttpClientCache(
        maxCacheSize = 5,
        maxAge = 30.seconds,
        maxIdleTime = 30.seconds,
        cleanupInterval = 30.seconds,
      )

      try {
        // Fill cache to capacity with distinct timestamps so LRU order is deterministic
        val entries = mutableListOf<CacheEntry>()
        for (i in 1..5) {
          val key = ClientKey("user$i", "pass$i")
          val entry = lruCache.getOrCreateClient(key) { createMockHttpClient() }
          entries.add(entry)
          delay(2) // Ensure each entry gets a distinct timestamp
        }

        lruCache.currentCacheSize() shouldBe 5

        // Access user1 to make it most recently used (now has newest timestamp)
        delay(2)
        val firstKey = ClientKey("user1", "pass1")
        val firstEntry = lruCache.getOrCreateClient(firstKey) { createMockHttpClient() }
        firstEntry.client shouldBe entries[0].client

        // Add user6, which should evict user2 (oldest timestamp after user1 was refreshed)
        val newKey = ClientKey("user6", "pass6")
        val newEntry = lruCache.getOrCreateClient(newKey) { createMockHttpClient() }

        lruCache.currentCacheSize() shouldBe 5

        // user1 should still be cached (it was re-accessed)
        val user1Entry = lruCache.getOrCreateClient(firstKey) { createMockHttpClient() }
        user1Entry.client shouldBe entries[0].client

        // user2 should have been evicted (oldest entry after user1 refresh)
        val evictedKey = ClientKey("user2", "pass2")
        val evictedEntry = lruCache.getOrCreateClient(evictedKey) { createMockHttpClient() }
        evictedEntry.client shouldNotBe entries[1].client

        // Clean up
        entries.forEach { lruCache.onFinishedWithClient(it) }
        lruCache.onFinishedWithClient(firstEntry)
        lruCache.onFinishedWithClient(user1Entry)
        lruCache.onFinishedWithClient(newEntry)
        lruCache.onFinishedWithClient(evictedEntry)
      } finally {
        lruCache.close()
      }
    }

    "should expire entries based on max age" {
      val key = ClientKey("user1", "pass1")

      // Create an entry
      val entry1 = cache.getOrCreateClient(key) { createMockHttpClient() }
      val originalClient = entry1.client
      cache.currentCacheSize() shouldBe 1

      // Wait for max age to expire
      delay(1200.milliseconds)

      // Request again - should create a new client
      val entry2 = cache.getOrCreateClient(key) { createMockHttpClient() }
      entry2.client shouldNotBe originalClient

      // Clean up
      cache.onFinishedWithClient(entry1)
      cache.onFinishedWithClient(entry2)
    }

    "should expire entries based on max idle time" {
      val key = ClientKey("user1", "pass1")

      // Create an entry
      val entry1 = cache.getOrCreateClient(key) { createMockHttpClient() }
      val originalClient = entry1.client
      cache.currentCacheSize() shouldBe 1

      // Wait for max idle time to expire
      delay(600.milliseconds)

      // Request again - should create a new client
      val entry2 = cache.getOrCreateClient(key) { createMockHttpClient() }
      entry2.client shouldNotBe originalClient

      // Clean up
      cache.onFinishedWithClient(entry1)
      cache.onFinishedWithClient(entry2)
    }

    "should reset last accessed time when client is reused" {
      val key = ClientKey("user1", "pass1")

      // Create an entry
      val entry1 = cache.getOrCreateClient(key) { createMockHttpClient() }
      val originalClient = entry1.client
      cache.onFinishedWithClient(entry1)

      // Wait half the idle time
      delay(300.milliseconds)

      // Access again - should reset last accessed time
      val entry2 = cache.getOrCreateClient(key) { createMockHttpClient() }
      entry2.client shouldBe originalClient
      cache.onFinishedWithClient(entry2)

      // Wait another half idle time (total would exceed idle time but entry was accessed)
      delay(300.milliseconds)

      // Should still be the same client (not expired)
      val entry3 = cache.getOrCreateClient(key) { createMockHttpClient() }
      entry3.client shouldBe originalClient

      // Clean up
      cache.onFinishedWithClient(entry3)
    }

    "should handle concurrent access safely" {
      val key = ClientKey("user1", "pass1")
      val entries = mutableListOf<CacheEntry>()

      // Launch multiple coroutines to access the cache concurrently
      val jobs = (1..10).map {
        launch {
          val entry = cache.getOrCreateClient(key) { createMockHttpClient() }
          entries.add(entry)
          delay(50.milliseconds)
          cache.onFinishedWithClient(entry)
        }
      }

      // Wait for all jobs to complete
      jobs.joinAll()

      // Should have only created one client (all should be the same)
      val uniqueClients = entries.map { it.client }.toSet()
      uniqueClients.size shouldBe 1

      // Cache should contain only one entry
      cache.currentCacheSize() shouldBe 1
    }

    "should provide accurate cache statistics" {
      // Create a cache with longer cleanup interval to prevent automatic cleanup
      val testCache = HttpClientCache(
        maxCacheSize = 5,
        maxAge = 500.milliseconds,
        maxIdleTime = 300.milliseconds,
        cleanupInterval = 10.seconds, // Long cleanup interval
      )

      try {
        // Initially empty
        var stats = testCache.getCacheStats()
        stats.totalEntries shouldBe 0
        stats.validEntries shouldBe 0
        stats.expiredEntries shouldBe 0

        // Add some entries
        val key1 = ClientKey("user1", "pass1")
        val key2 = ClientKey("user2", "pass2")
        val entry1 = testCache.getOrCreateClient(key1) { createMockHttpClient() }
        val entry2 = testCache.getOrCreateClient(key2) { createMockHttpClient() }

        stats = testCache.getCacheStats()
        stats.totalEntries shouldBe 2
        stats.validEntries shouldBe 2
        stats.expiredEntries shouldBe 0

        // Wait for entries to expire but not be cleaned up
        delay(600.milliseconds)

        stats = testCache.getCacheStats()
        stats.totalEntries shouldBe 2
        stats.validEntries shouldBe 0
        stats.expiredEntries shouldBe 2

        // Clean up
        testCache.onFinishedWithClient(entry1)
        testCache.onFinishedWithClient(entry2)
      } finally {
        testCache.close()
      }
    }

    "should handle cache cleanup properly" {
      val key = ClientKey("user1", "pass1")

      // Create entry and let it expire
      val entry = cache.getOrCreateClient(key) { createMockHttpClient() }
      cache.currentCacheSize() shouldBe 1

      // Wait for expiration and cleanup
      delay(1200.milliseconds)

      // Give cleanup time to run
      delay(200.milliseconds)

      // Cache should be cleaned up
      cache.currentCacheSize() shouldBe 0

      // Clean up
      cache.onFinishedWithClient(entry)
    }

    "should handle client key toString correctly" {
      // toString() should mask credentials for security
      val key1 = ClientKey("user", "pass")
      key1.toString() shouldBe "***:***"
      // cacheKey() returns actual value for internal use
      key1.cacheKey() shouldBe "user:pass"

      val key2 = ClientKey(null, "pass")
      key2.toString() shouldBe NO_AUTH
      key2.cacheKey() shouldBe NO_AUTH

      val key3 = ClientKey("user", null)
      key3.toString() shouldBe NO_AUTH
      key3.cacheKey() shouldBe NO_AUTH

      val key4 = ClientKey(null, null)
      key4.toString() shouldBe NO_AUTH
      key4.cacheKey() shouldBe NO_AUTH
    }

    "should handle cache entry lifecycle properly" {
      val key = ClientKey("user1", "pass1")

      // Get a client entry
      val entry = cache.getOrCreateClient(key) { createMockHttpClient() }

      // Entry should be marked as in use
      cache.currentCacheSize() shouldBe 1

      // Mark as finished - should not close immediately since not marked for close
      cache.onFinishedWithClient(entry)

      // Entry should still be in cache
      cache.currentCacheSize() shouldBe 1

      // Get the same entry again
      val entry2 = cache.getOrCreateClient(key) { createMockHttpClient() }
      entry2.client shouldBe entry.client

      // Clean up
      cache.onFinishedWithClient(entry2)
    }

    // Bug #10: The old code called scope.cancel() inside accessMutex.withLock in close().
    // If the cleanup coroutine held the mutex when close() was called, close() would block
    // on runBlocking waiting for the mutex indefinitely. The fix cancels the scope before
    // acquiring the mutex, so the cleanup coroutine is stopped first.
    // This test uses a very short cleanup interval to ensure the cleanup coroutine is actively
    // cycling while close() is called, and verifies close() completes promptly.
    "close should complete promptly with active cleanup coroutine" {
      val fastCleanupCache = HttpClientCache(
        maxCacheSize = 5,
        maxAge = 500.milliseconds,
        maxIdleTime = 200.milliseconds,
        cleanupInterval = 10.milliseconds, // Very fast to maximize mutex contention
      )

      // Populate cache to give the cleanup coroutine work to do
      val entries = mutableListOf<CacheEntry>()
      for (i in 1..5) {
        val key = ClientKey("user$i", "pass$i")
        val entry = fastCleanupCache.getOrCreateClient(key) { createMockHttpClient() }
        entries.add(entry)
      }
      entries.forEach { fastCleanupCache.onFinishedWithClient(it) }

      // Let entries expire so cleanup has work to do
      delay(300.milliseconds)

      // close() should complete within a reasonable time even though
      // the cleanup coroutine is actively running every 10ms
      val latch = CountDownLatch(1)
      val closeThread = Thread {
        runBlocking { fastCleanupCache.close() }
        latch.countDown()
      }
      closeThread.start()

      // If close() deadlocked (old bug), this would time out
      latch.await(5, TimeUnit.SECONDS).shouldBeTrue()
      fastCleanupCache.currentCacheSize() shouldBe 0
    }

    "close should clear all entries even when cleanup coroutine is active" {
      val fastCleanupCache = HttpClientCache(
        maxCacheSize = 10,
        maxAge = 30_000.milliseconds, // Long maxAge so entries don't expire
        maxIdleTime = 30_000.milliseconds,
        cleanupInterval = 10.milliseconds,
      )

      for (i in 1..5) {
        val key = ClientKey("user$i", "pass$i")
        val entry = fastCleanupCache.getOrCreateClient(key) { createMockHttpClient() }
        fastCleanupCache.onFinishedWithClient(entry)
      }
      fastCleanupCache.currentCacheSize() shouldBe 5

      // Let the cleanup coroutine run a few cycles
      delay(50.milliseconds)

      fastCleanupCache.close()
      fastCleanupCache.currentCacheSize() shouldBe 0
    }

    "onDoneWithClient should close client when entry is marked for close and not in use" {
      val key = ClientKey("user1", "pass1")

      // Get a client entry (marks it as in use)
      val entry = cache.getOrCreateClient(key) { createMockHttpClient() }
      cache.currentCacheSize() shouldBe 1

      // Now evict it (triggers markForClose) by filling cache past capacity
      for (i in 2..6) {
        val k = ClientKey("user$i", "pass$i")
        val e = cache.getOrCreateClient(k) { createMockHttpClient() }
        cache.onFinishedWithClient(e)
      }

      // The original entry was evicted (marked for close), but still in use
      // When we call onFinishedWithClient, it should close the client
      cache.onFinishedWithClient(entry)

      // The evicted entry's client was closed - getting the same key creates a new client
      val newEntry = cache.getOrCreateClient(key) { createMockHttpClient() }
      newEntry.client shouldNotBe entry.client

      cache.onFinishedWithClient(newEntry)
    }

    // M6: onFinishedWithClient now closes the HTTP client outside the mutex.
    // This test verifies that a slow client.close() does not block other cache operations.
    "onFinishedWithClient should not block cache operations during slow client close" {
      // This prevents flakiness in the test
      val slowCache = HttpClientCache(
        maxCacheSize = 10,
        maxAge = 30.seconds,
        maxIdleTime = 30.seconds,
        cleanupInterval = 30.seconds,
      )

      try {
        // Create a mock client whose close() blocks until we release it
        val slowClient = mockk<HttpClient>(relaxed = true)
        val closeStarted = CompletableDeferred<Unit>()
        val closeCanProceed = CountDownLatch(1)
        every { slowClient.close() } answers {
          closeStarted.complete(Unit)
          closeCanProceed.await(10, TimeUnit.SECONDS)
        }

        // Get the entry with the slow client, then mark it for eviction
        val slowKey = ClientKey("slow", "client")
        val slowEntry = slowCache.getOrCreateClient(slowKey) { slowClient }

        // Ensure the slow entry has an older timestamp than the filler entries.
        // Without this, entries created in the same millisecond have equal access
        // times, and HashMap iteration order makes LRU eviction non-deterministic —
        // a filler entry could be evicted instead of the slow entry, causing the
        // slow entry to never be marked for close.
        delay(50.milliseconds)

        // Fill the cache to evict the slow entry (marks it for close).
        // Use mock clients (not real CIO clients) to avoid IO thread pool pressure
        // which can cause flakiness when the full test suite runs concurrently.
        for (i in 1..10) {
          val k = ClientKey("other$i", "pass$i")
          val e = slowCache.getOrCreateClient(k) { mockk<HttpClient>(relaxed = true) }
          slowCache.onFinishedWithClient(e)
        }

        // onFinishedWithClient triggers close outside the mutex.
        // Must run on Dispatchers.IO so the blocking close doesn't
        // starve the single-threaded runBlocking dispatcher.
        val closeJob = async(Dispatchers.IO) { slowCache.onFinishedWithClient(slowEntry) }

        // Suspending wait for close to start
        withTimeout(10.seconds) { closeStarted.await() }

        // While the slow close is blocked, other cache operations should NOT be blocked
        val start = System.currentTimeMillis()
        val otherKey = ClientKey("concurrent", "access")
        val otherEntry = slowCache.getOrCreateClient(otherKey) { mockk<HttpClient>(relaxed = true) }
        val elapsed = System.currentTimeMillis() - start

        // Should complete quickly (well under 1 second)
        elapsed shouldBeLessThan 500L
        slowCache.onFinishedWithClient(otherEntry)

        // Release the slow close and wait for completion
        closeCanProceed.countDown()
        closeJob.await()
      } finally {
        slowCache.close()
      }
    }

    // M7: close() now marks in-use entries for close instead of forcibly closing them.
    // The client is closed when the last user calls onFinishedWithClient().
    "close should not close in-use clients immediately" {
      val testCache = HttpClientCache(
        maxCacheSize = 10,
        maxAge = 30.seconds,
        maxIdleTime = 30.seconds,
        cleanupInterval = 30.seconds,
      )

      val mockClient = mockk<HttpClient>(relaxed = true)
      val key = ClientKey("user1", "pass1")

      // Get entry (marks it in-use)
      val entry = testCache.getOrCreateClient(key) { mockClient }

      // close() should NOT close the in-use client
      testCache.close()

      verify(exactly = 0) { mockClient.close() }

      // When the user finishes, the client should be closed
      testCache.onFinishedWithClient(entry)

      verify(exactly = 1) { mockClient.close() }
    }

    "close should immediately close clients that are not in use" {
      val testCache = HttpClientCache(
        maxCacheSize = 10,
        maxAge = 30.seconds,
        maxIdleTime = 30.seconds,
        cleanupInterval = 30.seconds,
      )

      val mockClient = mockk<HttpClient>(relaxed = true)
      val key = ClientKey("user1", "pass1")

      // Get entry and release it (not in use)
      val entry = testCache.getOrCreateClient(key) { mockClient }
      testCache.onFinishedWithClient(entry)

      verify(exactly = 0) { mockClient.close() }

      // close() should close the idle client immediately
      testCache.close()

      verify(exactly = 1) { mockClient.close() }
    }

    "close should handle mix of in-use and idle clients" {
      val testCache = HttpClientCache(
        maxCacheSize = 10,
        maxAge = 30.seconds,
        maxIdleTime = 30.seconds,
        cleanupInterval = 30.seconds,
      )

      val idleClient = mockk<HttpClient>(relaxed = true)
      val inUseClient = mockk<HttpClient>(relaxed = true)

      // Create an idle entry
      val idleKey = ClientKey("idle", "client")
      val idleEntry = testCache.getOrCreateClient(idleKey) { idleClient }
      testCache.onFinishedWithClient(idleEntry)

      // Create an in-use entry
      val inUseKey = ClientKey("inuse", "client")
      val inUseEntry = testCache.getOrCreateClient(inUseKey) { inUseClient }

      // close() should close idle client but not in-use client
      testCache.close()

      verify(exactly = 1) { idleClient.close() }
      verify(exactly = 0) { inUseClient.close() }

      // When the in-use client is released, it should be closed
      testCache.onFinishedWithClient(inUseEntry)

      verify(exactly = 1) { inUseClient.close() }
    }

    // L11: CacheEntry is now a regular class (not data class), so equality
    // is identity-based. Mutable lastAccessedAt no longer affects equals/hashCode.
    "CacheEntry equality should be identity-based" {
      val client = createMockHttpClient()
      val now = System.currentTimeMillis()
      val entry1 = CacheEntry(client, now, now)
      val entry2 = CacheEntry(client, now, now)

      // Same instance should be equal
      (entry1 == entry1).shouldBeTrue()

      // Different instances with same values should NOT be equal (identity-based)
      (entry1 == entry2).shouldBeFalse()
    }

    "cleanupExpiredEntries should remove all expired entries" {
      // Create a cache with short expiry but long cleanup interval (manual cleanup)
      val testCache = HttpClientCache(
        maxCacheSize = 10,
        maxAge = 200.milliseconds,
        maxIdleTime = 100.milliseconds,
        cleanupInterval = 100.milliseconds,
      )

      try {
        // Create multiple entries
        val entries = mutableListOf<CacheEntry>()
        for (i in 1..3) {
          val key = ClientKey("user$i", "pass$i")
          val entry = testCache.getOrCreateClient(key) { createMockHttpClient() }
          entries.add(entry)
          testCache.onFinishedWithClient(entry)
        }

        testCache.currentCacheSize() shouldBe 3

        // Wait for entries to expire
        delay(300.milliseconds)

        // Wait for cleanup to run
        delay(200.milliseconds)

        // All expired entries should be removed by cleanup
        testCache.currentCacheSize() shouldBe 0
      } finally {
        testCache.close()
      }
    }
  }
}
