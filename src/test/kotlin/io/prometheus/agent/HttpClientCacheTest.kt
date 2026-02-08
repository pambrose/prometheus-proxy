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

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.prometheus.agent.HttpClientCache.CacheEntry
import io.prometheus.agent.HttpClientCache.ClientKey
import io.prometheus.agent.HttpClientCache.ClientKey.Companion.NO_AUTH
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpClientCacheTest {
  private lateinit var cache: HttpClientCache
  private var clientCount = 0

  @BeforeEach
  fun setUp() {
    clientCount = 0
    cache = HttpClientCache(
      maxCacheSize = 5,
      maxAge = 1.seconds,
      maxIdleTime = 500.milliseconds,
      cleanupInterval = 100.milliseconds,
    )
  }

  @AfterEach
  fun tearDown() {
    runBlocking {
      cache.close()
    }
  }

  private fun createMockHttpClient(): HttpClient {
    clientCount++
    return HttpClient(CIO) {
      expectSuccess = false
    }
  }

  @Test
  fun `should cache and reuse clients for same key`() =
    runBlocking {
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

  @Test
  fun `should create different clients for different keys`() =
    runBlocking {
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

  @Test
  fun `should handle null username and password`() =
    runBlocking {
      val key1 = ClientKey(null, null)
      val key2 = ClientKey("user1", null)
      val key3 = ClientKey(null, "pass1")

      val entry1 = cache.getOrCreateClient(key1) { createMockHttpClient() }
      val entry2 = cache.getOrCreateClient(key2) { createMockHttpClient() }
      val entry3 = cache.getOrCreateClient(key3) { createMockHttpClient() }

      cache.currentCacheSize() shouldBe 1

      // All should be different clients
      entry1.client shouldBe entry2.client
      entry2.client shouldBe entry3.client
      entry1.client shouldBe entry3.client

      // Clean up
      cache.onFinishedWithClient(entry1)
      cache.onFinishedWithClient(entry2)
      cache.onFinishedWithClient(entry3)
    }

  @Test
  fun `should evict least recently used client when cache is full`() =
    runBlocking {
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
        // Fill cache to capacity
        val entries = mutableListOf<CacheEntry>()
        for (i in 1..5) {
          val key = ClientKey("user$i", "pass$i")
          val entry = lruCache.getOrCreateClient(key) { createMockHttpClient() }
          entries.add(entry)
        }

        lruCache.currentCacheSize() shouldBe 5

        // Ensure the re-access gets a strictly later timestamp.
        // Without this, all entries can share the same System.currentTimeMillis()
        // value, and minByOrNull picks user1 (first in insertion order) as the
        // LRU victim instead of user2.
        delay(2)

        // Access the first entry to make it most recently used
        val firstKey = ClientKey("user1", "pass1")
        val firstEntry = lruCache.getOrCreateClient(firstKey) { createMockHttpClient() }
        firstEntry.client shouldBe entries[0].client

        // Add a new entry, which should evict the least recently used (user2)
        val newKey = ClientKey("user6", "pass6")
        val newEntry = lruCache.getOrCreateClient(newKey) { createMockHttpClient() }

        lruCache.currentCacheSize() shouldBe 5

        // Try to get user2 again - should create a new client since it was evicted
        val evictedKey = ClientKey("user2", "pass2")
        val evictedEntry = lruCache.getOrCreateClient(evictedKey) { createMockHttpClient() }
        evictedEntry.client shouldNotBe entries[1].client

        // Clean up
        entries.forEach { lruCache.onFinishedWithClient(it) }
        lruCache.onFinishedWithClient(firstEntry)
        lruCache.onFinishedWithClient(newEntry)
        lruCache.onFinishedWithClient(evictedEntry)
      } finally {
        lruCache.close()
      }
    }

  @Test
  fun `should expire entries based on max age`() =
    runBlocking {
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

  @Test
  fun `should expire entries based on max idle time`() =
    runBlocking {
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

  @Test
  fun `should reset last accessed time when client is reused`() =
    runBlocking {
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

  @Test
  fun `should handle concurrent access safely`() {
    runBlocking {
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
  }

  @Test
  fun `should provide accurate cache statistics`() =
    runBlocking {
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

  @Test
  fun `should handle cache cleanup properly`() =
    runBlocking {
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

  @Test
  fun `should handle client key toString correctly`() {
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

  @Test
  fun `should handle cache entry lifecycle properly`() =
    runBlocking {
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
  @Test
  fun `close should complete promptly with active cleanup coroutine`() {
    val fastCleanupCache = HttpClientCache(
      maxCacheSize = 5,
      maxAge = 500.milliseconds,
      maxIdleTime = 200.milliseconds,
      cleanupInterval = 10.milliseconds, // Very fast to maximize mutex contention
    )

    runBlocking {
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
    }

    // close() should complete within a reasonable time even though
    // the cleanup coroutine is actively running every 10ms
    val latch = CountDownLatch(1)
    val closeThread = Thread {
      fastCleanupCache.close()
      latch.countDown()
    }
    closeThread.start()

    // If close() deadlocked (old bug), this would time out
    latch.await(5, TimeUnit.SECONDS).shouldBeTrue()
    fastCleanupCache.currentCacheSize() shouldBe 0
  }

  @Test
  fun `close should clear all entries even when cleanup coroutine is active`() {
    val fastCleanupCache = HttpClientCache(
      maxCacheSize = 10,
      maxAge = 30_000.milliseconds, // Long maxAge so entries don't expire
      maxIdleTime = 30_000.milliseconds,
      cleanupInterval = 10.milliseconds,
    )

    runBlocking {
      for (i in 1..5) {
        val key = ClientKey("user$i", "pass$i")
        val entry = fastCleanupCache.getOrCreateClient(key) { createMockHttpClient() }
        fastCleanupCache.onFinishedWithClient(entry)
      }
      fastCleanupCache.currentCacheSize() shouldBe 5

      // Let the cleanup coroutine run a few cycles
      delay(50.milliseconds)
    }

    fastCleanupCache.close()
    fastCleanupCache.currentCacheSize() shouldBe 0
  }

  @Test
  fun `onDoneWithClient should close client when entry is marked for close and not in use`(): Unit =
    runBlocking {
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

  @Test
  fun `cleanupExpiredEntries should remove all expired entries`(): Unit =
    runBlocking {
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
