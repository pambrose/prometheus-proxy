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

package io.prometheus.common.dashboard

import io.prometheus.common.Utils.sha256
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The dashboard's logins, from its login page, each a random token the browser keeps in a cookie.
 *
 * Kept in memory, so a restart of the proxy or the agent signs everyone out. A session ends [idleTimeout] after its
 * last request or [maxAge] after it began, whichever comes first, and at most [maxSessions] are kept, the least
 * recently used going first. Only each token's SHA-256 is kept, so a lookup's timing says nothing about the tokens it
 * holds.
 *
 * Thread-safe: every access takes the map's monitor, which is a leaf.
 *
 * @param timeSource where the times come from; injectable so tests move time instead of sleeping
 */
internal class DashboardSessions(
  private val idleTimeout: Duration = 24.hours,
  private val maxAge: Duration = MAX_AGE_SECS.seconds,
  private val maxSessions: Int = MAX_SESSIONS,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private class Session(
    val began: TimeMark,
    var lastRequest: TimeMark,
  )

  // By the SHA-256 of each token. Access-ordered, so the eldest is the session used least recently. Guarded by itself.
  private val sessions =
    object : LinkedHashMap<String, Session>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>) = size > maxSessions
    }

  /** How many sessions are kept. */
  val size: Int get() = synchronized(sessions) { sessions.size }

  /** Starts a session and returns its token, 256 random bits as 43 base64url characters. */
  fun create(): String {
    val token = ENCODER.encodeToString(ByteArray(TOKEN_BYTES).also { RANDOM.nextBytes(it) })
    val key = keyOf(token)
    val now = timeSource.markNow()
    synchronized(sessions) { sessions[key] = Session(now, now) }
    return token
  }

  /**
   * Whether [token] names a session that hasn't ended. A valid one [counts] as a request, restarting its idle time,
   * unless the check is the dashboard's own, of a page it keeps open, which isn't a request.
   */
  fun isValid(
    token: String?,
    counts: Boolean = true,
  ): Boolean {
    if (token.isNullOrEmpty())
      return false
    val key = keyOf(token)
    return synchronized(sessions) {
      val session = sessions[key] ?: return false
      if (session.lastRequest.elapsedNow() >= idleTimeout || session.began.elapsedNow() >= maxAge) {
        sessions.remove(key)
        false
      } else {
        if (counts)
          session.lastRequest = timeSource.markNow()
        true
      }
    }
  }

  /** Ends [token]'s session, if there is one. */
  fun remove(token: String?) {
    if (!token.isNullOrEmpty())
      synchronized(sessions) { sessions.remove(keyOf(token)) }
  }

  companion object {
    /** A session's longest life, 7 days, which the cookie's `Max-Age` gives too. */
    const val MAX_AGE_SECS = 604_800

    private const val MAX_SESSIONS = 1_000
    private const val TOKEN_BYTES = 32
    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f
    private val RANDOM = SecureRandom()
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()

    private fun keyOf(token: String) = sha256(token).toHexString()
  }
}
