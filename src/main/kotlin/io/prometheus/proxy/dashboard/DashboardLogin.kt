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

package io.prometheus.proxy.dashboard

import java.security.MessageDigest
import java.util.Base64

/**
 * The login the dashboard requires once `proxy.dashboard.password` is set, with `proxy.dashboard.username` (default
 * `admin`): checked against the login page's form, which starts a session (see [DashboardSessions]), and against an
 * HTTP Basic `Authorization` header, which a script such as `curl -u` sends with each request instead.
 *
 * Both are compared as UTF-8 bytes: a browser sends a non-ASCII Basic password as UTF-8, and the form's text is
 * encoded the same way. Neither value is kept as a String, and [toString] shows neither.
 */
internal class DashboardLogin(
  username: String,
  password: String,
) {
  private val username = username.toByteArray(Charsets.UTF_8)
  private val password = password.toByteArray(Charsets.UTF_8)

  /** Whether [header], a request's `Authorization` value, or null when it has none, carries this login. */
  fun accepts(header: String?): Boolean {
    val (givenUsername, givenPassword) = credentials(header) ?: return false
    // Both halves are compared every time, so a wrong username takes as long as a wrong password, and each comparison
    // takes time that depends only on the length of what was given (see matches).
    val usernameMatches = matches(givenUsername, username)
    val passwordMatches = matches(givenPassword, password)
    return usernameMatches and passwordMatches
  }

  /** Whether the login page's form gave this login's [username] and [password]. */
  fun accepts(
    username: String,
    password: String,
  ): Boolean {
    // Both compared every time, as for a header.
    val usernameMatches = matches(username.toByteArray(Charsets.UTF_8), this.username)
    val passwordMatches = matches(password.toByteArray(Charsets.UTF_8), this.password)
    return usernameMatches and passwordMatches
  }

  override fun toString() = "DashboardLogin"

  companion object {
    /**
     * The username and password in a Basic [header], as the bytes it encodes, or null when it isn't one: no header,
     * another scheme, a value that isn't base64, or one with no colon. The scheme's name is matched in any case.
     */
    internal fun credentials(header: String?): Pair<ByteArray, ByteArray>? {
      val value = header?.trim().orEmpty()
      val space = value.indexOf(' ')
      if (space < 0 || !value.substring(0, space).equals("Basic", ignoreCase = true))
        return null
      val decoded = runCatching { Base64.getDecoder().decode(value.substring(space + 1).trim()) }.getOrNull()
      val colon = decoded?.indexOf(':'.code.toByte()) ?: -1
      if (decoded == null || colon < 0)
        return null
      return decoded.copyOfRange(0, colon) to decoded.copyOfRange(colon + 1, decoded.size)
    }

    // MessageDigest.isEqual's time depends on the length of its first argument and on nothing else, so the value given
    // goes first: a caller learns how long its own guess was, never the length of the real one.
    private fun matches(
      given: ByteArray,
      expected: ByteArray,
    ) = MessageDigest.isEqual(given, expected)
  }
}
