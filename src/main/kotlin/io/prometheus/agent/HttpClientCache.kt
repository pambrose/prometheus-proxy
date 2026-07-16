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

package io.prometheus.agent

import com.pambrose.common.util.runCatchingCancellable
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
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource.Monotonic

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
  // A single access-ordered LinkedHashMap (accessOrder = true) is both the cache and the recency
  // tracker: get() moves an entry to the most-recently-used end, so the least-recently-used entry is
  // always the iteration head -- O(1) eviction, no parallel recency map (finding 25). Every access is
  // under accessMutex, so the plain map is safe; cacheSize mirrors the size for a lock-free
  // currentCacheSize() read from the metrics/health-check threads (Bug #12).
  private val cache = LinkedHashMap<String, CacheEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)
  private val cacheSize = AtomicInt(0)

  private val accessMutex = Mutex()
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Set to true (under accessMutex) by close(). Once closed, getOrCreateClient() rejects new
  // requests, preventing an in-flight scrape racing shutdown from creating and caching a fresh
  // HttpClient into the now-dead cache (whose cleanup coroutine is cancelled) — which would leak
  // that client until JVM exit. Especially relevant for embedded agents restarted in a host JVM.
  private var closed = false

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
        clientsToClose.forEach { closeQuietly(it) }
      }
    }
  }

  class CacheEntry(
    val client: HttpClient,
    // Monotonic TimeMarks rather than epoch millis so an NTP wall-clock step can't mass-evict or
    // immortalize entries, matching the rest of the project (finding 33).
    val createdAt: TimeMark = Monotonic.markNow(),
    var lastAccessedAt: TimeMark = Monotonic.markNow(),
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

  // A basic-auth credential pair. Modeling username+password as a single non-nullable value object
  // (rather than two independent nullables on ClientKey) makes the "both-or-neither" invariant
  // unrepresentable to violate and removes the need for !! at the use sites.
  data class Credentials(
    val username: String,
    val password: String,
  )

  data class ClientKey(
    val credentials: Credentials?,
  ) {
    // Convenience that enforces the both-or-neither auth gate in one place: credentials exist only
    // when both username and password are present.
    constructor(username: String?, password: String?) : this(
      if (username != null && password != null) Credentials(username, password) else null,
    )

    fun hasAuth() = credentials != null

    override fun toString() = maskedKey()

    // Mask credentials to avoid logging sensitive data
    internal fun maskedKey() = if (hasAuth()) "***:***" else NO_AUTH

    // Cache-map key derived from a salted HMAC of the credentials rather than the raw
    // "username:password". This keeps the plaintext password out of the long-lived map key (which
    // would otherwise sit on the heap for the cache lifetime, up to maxAge). Residual exposure
    // remains and is by design: this ClientKey instance and the built HttpClient still hold the raw
    // credentials in memory — basic-auth requires them to issue requests — so this only removes the
    // extra long-lived plaintext copy, not all of them.
    internal fun cacheKey() = credentials?.let { credentialDigest("${it.username}:${it.password}") } ?: NO_AUTH

    companion object {
      internal const val NO_AUTH = "__no_auth__"

      private const val SALT_BYTES = 16
      private const val HMAC_ALGORITHM = "HmacSHA256"

      // Per-process random salt: the digest is stable within a run (so equal credentials map to the
      // same cache entry) but is not a plain, precomputable hash of the credentials.
      private val SALT: ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

      private fun credentialDigest(credentials: String): String =
        Mac.getInstance(HMAC_ALGORITHM)
          .apply { init(SecretKeySpec(SALT, HMAC_ALGORITHM)) }
          .doFinal(credentials.toByteArray(Charsets.UTF_8))
          .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
  }

  fun currentCacheSize() = cacheSize.load()

  // When an entry is returned from the cache, it is marked as in use.
  suspend fun getOrCreateClient(
    key: ClientKey,
    clientFactory: () -> HttpClient,
  ): CacheEntry {
    val cacheKey = key.cacheKey()
    val maskedString = key.maskedKey()

    val (entry, clientsToClose) =
      accessMutex.withLock {
        check(!closed) { "HttpClientCache is closed" }
        // cache[cacheKey] is a get(): on an accessOrder LinkedHashMap it also marks the entry MRU.
        val result = cache[cacheKey]?.let { existing ->
          if (isEntryValid(existing)) {
            existing.lastAccessedAt = Monotonic.markNow()
            logger.debug { "Using cached HTTP client for key: $maskedString" }
            existing.onStartWithClient()
            existing
          } else {
            logger.debug { "Removing expired HTTP client for key: $maskedString" }
            removeEntry(cacheKey)
            null
          }
        } ?: createAndCacheClient(cacheKey, maskedString, clientFactory).apply { onStartWithClient() }

        result to drainPendingCloses()
      }

    clientsToClose.forEach { closeQuietly(it) }
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
      closeQuietly(entry.client)
    }
  }

  // Called with mutex
  private fun createAndCacheClient(
    keyString: String,
    maskedKey: String,
    clientFactory: () -> HttpClient,
  ): CacheEntry {
    if (cache.size >= maxCacheSize)
      evictLeastRecentlyUsed()

    val entry = CacheEntry(clientFactory())
    cache[keyString] = entry
    cacheSize.store(cache.size)
    logger.info { "Created and cached HTTP client for key: $maskedKey" }
    return entry
  }

  private fun isEntryValid(entry: CacheEntry): Boolean =
    entry.createdAt.elapsedNow() < maxAge && entry.lastAccessedAt.elapsedNow() < maxIdleTime

  // Called with mutex
  private fun evictLeastRecentlyUsed() {
    // accessOrder = true keeps the least-recently-used entry at the iteration head.
    val lruKey = cache.keys.firstOrNull()
    if (lruKey != null) {
      // The cache key for authenticated clients is a salted digest (non-sensitive), but mask it
      // anyway so eviction logs never expose a per-credential identifier; NO_AUTH is left as-is.
      val logKey = if (lruKey == ClientKey.NO_AUTH) lruKey else "***:***"
      logger.info { "Evicting LRU HTTP client for key: $logKey" }
      removeEntry(lruKey)
    }
  }

  // Called with mutex. Marks the entry for close. If the entry is not in use,
  // adds its client to pendingCloses for closing outside the lock.
  private fun removeEntry(keyString: String) {
    val entry = cache.remove(keyString)
    if (entry != null) {
      cacheSize.store(cache.size)
      entry.markForClose()
      if (!entry.isInUse()) {
        pendingCloses += entry.client
      }
      // In-use entries will be closed when the last user calls onFinishedWithClient()
    }
  }

  // Called with mutex. Returns and clears the pending-close list.
  private fun drainPendingCloses(): List<HttpClient> {
    if (pendingCloses.isEmpty()) return emptyList()
    return pendingCloses.toList().also { pendingCloses.clear() }
  }

  // Called with mutex
  private fun cleanupExpiredEntries() {
    val expiredKeys = cache.entries
      .filter { !isEntryValid(it.value) }
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
      cacheSize.store(0)
      // Set the terminal flag in the same critical section that clears the map, so there is no
      // window where getOrCreateClient() could repopulate the cache after this point.
      closed = true
    }
    // Close clients outside the lock to avoid blocking
    clientsToClose.forEach { closeQuietly(it) }
  }

  suspend fun getCacheStats(): CacheStats =
    accessMutex.withLock {
      val validEntries = cache.values.count { isEntryValid(it) }
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

    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f

    // Closes a client, swallowing and logging any failure so it can never propagate. Critical for the
    // background sweeper loop: a throwing HttpClient.close() there would break out of the while(isActive)
    // loop and kill the sweeper permanently, leaking every future expired client (finding 9). At the
    // batch-close sites it also keeps one bad close() from aborting the remaining closes.
    internal fun closeQuietly(client: HttpClient) {
      runCatching { client.close() }
        .onFailure { e -> logger.warn(e) { "Error closing HTTP client: ${e.message}" } }
    }
  }
}
