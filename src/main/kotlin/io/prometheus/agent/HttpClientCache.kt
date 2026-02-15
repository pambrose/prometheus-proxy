/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.agent

import com.github.pambrose.common.util.runCatchingCancellable
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * LRU cache for Ktor [HttpClient] instances keyed by authentication credentials.
 *
 * Caches HTTP clients to avoid creating a new client per scrape request. Entries are
 * evicted when they exceed [maxAge], sit idle longer than [maxIdleTime], or when the
 * cache exceeds [maxCacheSize] (least-recently-used eviction). A background coroutine
 * periodically cleans up expired entries. Credential-based keys are masked in logs to
 * prevent leaking secrets.
 *
 * @param maxCacheSize maximum number of cached clients
 * @param maxAge maximum lifetime of a cache entry
 * @param maxIdleTime maximum idle time before an entry is considered expired
 * @param cleanupInterval interval between background cleanup sweeps
 * @see AgentHttpService
 */
internal class HttpClientCache(
  private val maxCacheSize: Int = 100,
  private val maxAge: Duration = 30.minutes,
  private val maxIdleTime: Duration = 10.minutes,
  private val cleanupInterval: Duration = 5.minutes,
) {
  private val cache = ConcurrentHashMap<String, CacheEntry>()

  private val accessOrder = HashMap<String, Long>()
  private val accessMutex = Mutex()
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Clients removed via removeEntry() that are not in use and need to be closed.
  // Only accessed while holding accessMutex. Drained after releasing the mutex
  // so that potentially slow HttpClient.close() calls don't block other cache operations.
  private val pendingCloses = mutableListOf<HttpClient>()

  init {
    scope.launch {
      while (isActive) {
        delay(cleanupInterval)
        val clientsToClose =
          runCatchingCancellable {
            accessMutex.withLock {
              cleanupExpiredEntries()
              drainPendingCloses()
            }
          }.getOrElse { e ->
            logger.error(e) { "Error during HTTP client cache cleanup" }
            emptyList()
          }
        clientsToClose.forEach { it.close() }
      }
    }
  }

  class CacheEntry(
    val client: HttpClient,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
  ) {
    private val markedForClose = AtomicBoolean(false)
    private val inUseCount = AtomicInt(0)

    private fun isMarkedForClose() = markedForClose.load()

    private fun markInUse() = inUseCount.incrementAndFetch()

    private fun markNotInUse() = inUseCount.decrementAndFetch()

    fun isInUse() = inUseCount.load() > 0

    private fun isNotInUse() = !isInUse()

    // Called with mutex
    fun markForClose() = markedForClose.store(true)

    // Called with mutex
    fun onStartWithClient() = markInUse()

    // Called with mutex. Returns true if the client should be closed outside the lock.
    fun onDoneWithClient(): Boolean {
      markNotInUse()
      return isNotInUse() && isMarkedForClose()
    }
  }

  data class ClientKey(
    val username: String?,
    val password: String?,
  ) {
    fun hasAuth() = username != null && password != null

    override fun toString() = maskedKey()

    // Mask credentials to avoid logging sensitive data
    internal fun maskedKey() = if (hasAuth()) "***:***" else NO_AUTH

    // Internal key for cache lookup (not logged)
    internal fun cacheKey() = if (hasAuth()) "$username:$password" else NO_AUTH

    companion object {
      internal const val NO_AUTH = "__no_auth__"
    }
  }

  fun currentCacheSize() = cache.size

  // When an entry is returned from the cache, it is marked as in use.
  suspend fun getOrCreateClient(
    key: ClientKey,
    clientFactory: () -> HttpClient,
  ): CacheEntry {
    val cacheKey = key.cacheKey()
    val maskedString = key.maskedKey()
    val now = System.currentTimeMillis()

    val (entry, clientsToClose) =
      accessMutex.withLock {
        val result = cache[cacheKey]?.let { existing ->
          if (isEntryValid(existing, now)) {
            existing.lastAccessedAt = now
            updateAccessOrder(cacheKey, now)
            logger.debug { "Using cached HTTP client for key: $maskedString" }
            existing.onStartWithClient()
            existing
          } else {
            logger.debug { "Removing expired HTTP client for key: $maskedString" }
            removeEntry(cacheKey)
            null
          }
        } ?: createAndCacheClient(
          cacheKey,
          maskedString,
          clientFactory = clientFactory,
          now = now,
        ).apply { onStartWithClient() }

        result to drainPendingCloses()
      }

    clientsToClose.forEach { it.close() }
    return entry
  }

  // When an agent is done with client for a given scrape, the entry is marked as not in use.
  // If the entry is no longer in use and marked for close, the client is closed outside the
  // mutex to avoid blocking other cache operations during a potentially slow I/O close.
  suspend fun onFinishedWithClient(entry: CacheEntry) {
    val shouldClose = accessMutex.withLock {
      entry.onDoneWithClient()
    }
    if (shouldClose) {
      entry.client.close()
    }
  }

  // Called with mutex
  private fun createAndCacheClient(
    keyString: String,
    maskedKey: String,
    clientFactory: () -> HttpClient,
    now: Long,
  ): CacheEntry {
    if (cache.size >= maxCacheSize) {
      evictLeastRecentlyUsed()
    }

    val client = clientFactory()
    val entry = CacheEntry(client, now, now)
    cache[keyString] = entry
    updateAccessOrder(keyString, now)
    logger.info { "Created and cached HTTP client for key: $maskedKey" }
    return entry
  }

  private fun updateAccessOrder(
    keyString: String,
    timestamp: Long,
  ) {
    accessOrder[keyString] = timestamp
  }

  private fun isEntryValid(
    entry: CacheEntry,
    now: Long,
  ): Boolean {
    val age = now - entry.createdAt
    val idleTime = now - entry.lastAccessedAt
    return age < maxAge.inWholeMilliseconds && idleTime < maxIdleTime.inWholeMilliseconds
  }

  // Called with mutex
  private fun evictLeastRecentlyUsed() {
    val lruKey = accessOrder.entries.minByOrNull { it.value }?.key
    if (lruKey != null) {
      // Mask the key if it contains credentials (not NO_AUTH)
      val logKey = if (lruKey != ClientKey.NO_AUTH && ":" in lruKey) "***:***" else lruKey
      logger.info { "Evicting LRU HTTP client for key: $logKey" }
      removeEntry(lruKey)
    }
  }

  // Called with mutex. Marks the entry for close. If the entry is not in use,
  // adds its client to pendingCloses for closing outside the lock.
  private fun removeEntry(keyString: String) {
    val entry = cache.remove(keyString)
    if (entry != null) {
      entry.markForClose()
      if (!entry.isInUse()) {
        pendingCloses += entry.client
      }
      // In-use entries will be closed when the last user calls onFinishedWithClient()
    }
    accessOrder.remove(keyString)
  }

  // Called with mutex. Returns and clears the pending-close list.
  private fun drainPendingCloses(): List<HttpClient> {
    if (pendingCloses.isEmpty()) return emptyList()
    return pendingCloses.toList().also { pendingCloses.clear() }
  }

  // Called with mutex
  private fun cleanupExpiredEntries() {
    val now = System.currentTimeMillis()
    val expiredKeys = cache.entries
      .filter { !isEntryValid(it.value, now) }
      .map { it.key }

    if (expiredKeys.isNotEmpty()) {
      logger.debug { "Cleaning up ${expiredKeys.size} expired HTTP clients" }
      expiredKeys.forEach { removeEntry(it) }
    }
  }

  suspend fun close() {
    // Cancel the scope first, before acquiring the mutex, to stop the cleanup coroutine.
    // The old code called scope.cancel() inside accessMutex.withLock, which could deadlock
    // if the cleanup coroutine held the mutex when close() tried to acquire it via runBlocking.
    scope.cancel()

    val clientsToClose = mutableListOf<HttpClient>()
    accessMutex.withLock {
      cache.values.forEach { entry ->
        entry.markForClose()
        if (!entry.isInUse()) {
          // Entry is not in use — safe to close immediately
          clientsToClose += entry.client
        }
        // In-use entries will be closed when the last user calls onFinishedWithClient()
      }
      // Drain any pending closes from prior removeEntry() calls
      clientsToClose += drainPendingCloses()
      cache.clear()
      accessOrder.clear()
    }
    // Close clients outside the lock to avoid blocking
    clientsToClose.forEach { it.close() }
  }

  suspend fun getCacheStats(): CacheStats =
    accessMutex.withLock {
      val now = System.currentTimeMillis()
      val validEntries = cache.values.count { isEntryValid(it, now) }
      CacheStats(
        totalEntries = cache.size,
        validEntries = validEntries,
        expiredEntries = cache.size - validEntries,
      )
    }

  data class CacheStats(
    val totalEntries: Int,
    val validEntries: Int,
    val expiredEntries: Int,
  )

  companion object {
    private val logger = logger {}
  }
}
