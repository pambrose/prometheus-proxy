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

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

internal class HttpClientCache(
  private val maxCacheSize: Int = 100,
  private val maxAge: Duration = 30.minutes,
  private val maxIdleTime: Duration = 10.minutes,
  private val cleanupInterval: Duration = 5.minutes,
) {
  private val cache = ConcurrentHashMap<String, CacheEntry>()

  // Use a LinkedHashMap to maintain access order
  private val accessOrder = LinkedHashMap<String, Long>()
  private val accessMutex = Mutex()
  private val cleanupJob: Job
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  init {
    cleanupJob = scope.launch {
      while (true) {
        delay(cleanupInterval)
        accessMutex.withLock {
          cleanupExpiredEntries()
        }
      }
    }
  }

  data class CacheEntry(
    val client: HttpClient,
    val createdAt: Long = System.currentTimeMillis(),
    var lastAccessedAt: Long = System.currentTimeMillis(),
  ) {
    private val markedForClose = AtomicBoolean(false)
    private val inUseCount = AtomicInt(0)

    private fun isMarkedForClose() = markedForClose.load()

    private fun markInUse() = inUseCount.incrementAndFetch()

    private fun markNotInUse() = inUseCount.decrementAndFetch()

    private fun isInUse() = inUseCount.load() > 0

    private fun isNotInUse() = !isInUse()

    // Called with mutex
    fun markForClose() = markedForClose.store(true)

    // Called with mutex
    fun onStartedWithClient() = markInUse()

    // Called with mutex
    fun onFinishedWithClient() {
      markNotInUse()
      if (isNotInUse() && isMarkedForClose())
        client.close()
    }
  }

  data class ClientKey(
    val username: String?,
    val password: String?,
  ) {
    override fun toString(): String = "${username ?: "__no_username__"}:${password ?: "__no_password__"}"
  }

  fun currentCacheSize() = cache.size

  // When an entry is returned from the cache, it is marked as in use.
  suspend fun getOrCreateClient(
    key: ClientKey,
    clientFactory: () -> HttpClient,
  ): CacheEntry {
    val keyString = key.toString()
    val now = System.currentTimeMillis()

    return accessMutex.withLock {
      cache[keyString]?.let { entry ->
        if (isEntryValid(entry, now)) {
          entry.lastAccessedAt = now
          updateAccessOrder(keyString, now)

          logger.debug { "Using cached HTTP client for key: $keyString" }
          entry.onStartedWithClient()
          return@withLock entry
        } else {
          logger.debug { "Removing expired HTTP client for key: $keyString" }
          removeEntry(keyString)
        }
      }

      createAndCacheClient(keyString, clientFactory, now).apply { onStartedWithClient() }
    }
  }

  // Called with mutex
  // When an agent is done with client for a given scrape, the entry is marked as not in use.
  // It is then closed if it is not in use and marked for close.
  suspend fun checkIfNeedsToBeClosed(entry: CacheEntry) {
    accessMutex.withLock {
      entry.onFinishedWithClient()
    }
  }

  // Called with mutex
  private fun createAndCacheClient(
    keyString: String,
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
    logger.info { "Created and cached HTTP client for key: $keyString" }
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
      logger.info { "Evicting LRU HTTP client for key: $lruKey" }
      removeEntry(lruKey)
    }
  }

  // Called with mutex
  private fun removeEntry(keyString: String) {
    val entry = cache.remove(keyString)
    entry?.markForClose()
    accessOrder.remove(keyString)
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
    accessMutex.withLock {
      cleanupJob.cancel()
      cache.values.forEach { it.client.close() }
      cache.clear()
      accessOrder.clear()
    }
  }

  fun getCacheStats(): CacheStats {
    val now = System.currentTimeMillis()
    val validEntries = cache.values.count { isEntryValid(it, now) }
    return CacheStats(
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
    private val logger = KotlinLogging.logger {}
  }
}
