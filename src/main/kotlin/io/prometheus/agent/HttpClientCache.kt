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
  )

  data class ClientKey(
    val username: String?,
    val password: String?,
  ) {
    override fun toString(): String = "${username ?: "__no_username__"}:${password ?: "__no_password__"}"
  }

  suspend fun getOrCreateClient(
    key: ClientKey,
    clientFactory: () -> HttpClient,
  ): HttpClient {
    val keyString = key.toString()
    val now = System.currentTimeMillis()

    return accessMutex.withLock {
      cache[keyString]?.let { entry ->
        if (isEntryValid(entry, now)) {
          entry.lastAccessedAt = now
          updateAccessOrder(keyString, now)

          logger.debug { "Using cached HTTP client for key: $keyString" }
          return@withLock entry.client
        } else {
          logger.debug { "Removing expired HTTP client for key: $keyString" }
          removeEntry(keyString)
        }
      }

      createAndCacheClient(keyString, clientFactory, now)
    }
  }

  // Called with mutex
  private fun createAndCacheClient(
    keyString: String,
    clientFactory: () -> HttpClient,
    now: Long,
  ): HttpClient {
    if (cache.size >= maxCacheSize) {
      evictLeastRecentlyUsed()
    }

    val client = clientFactory()
    val entry = CacheEntry(client, now, now)
    cache[keyString] = entry
    updateAccessOrder(keyString, now)

    logger.debug { "Created and cached new HTTP client for key: $keyString" }
    return client
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
    cache.remove(keyString)?.client?.close()
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
