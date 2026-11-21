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

import io.prometheus.common.Utils
import io.prometheus.common.Utils.sha256
import java.security.MessageDigest
import java.util.Base64

/**
 * The login the dashboard requires once `proxy.dashboard.password` is set, with `proxy.dashboard.username` (default
 * `admin`): checked against the login page's form, which starts a session (see [DashboardSessions]), and against an
 * HTTP Basic `Authorization` header, which a script such as `curl -u` sends with each request instead.
 *
 * Both are compared as UTF-8: the form's text is, and curl and the like send a non-ASCII Basic password that way. Only
 * the values' digests are kept ([Utils.sha256]), and [toString] shows neither.
 */
internal class DashboardLogin(
  username: String,
  password: String,
) {
  private val usernameDigest = sha256(username)
  private val passwordDigest = sha256(password)

  /** Whether [header], a request's `Authorization` value, or null when it has none, carries this login. */
  fun accepts(header: String?): Boolean =
    credentials(header)?.let { (username, password) ->
      accepts(String(username, Charsets.UTF_8), String(password, Charsets.UTF_8))
    } == true

  /** Whether the login page's form gave this login's [username] and [password]. */
  fun accepts(
    username: String,
    password: String,
  ): Boolean {
    // Both halves are compared every time, so a wrong username takes as long as a wrong password.
    val usernameMatches = MessageDigest.isEqual(sha256(username), usernameDigest)
    val passwordMatches = MessageDigest.isEqual(sha256(password), passwordDigest)
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
  }
}
