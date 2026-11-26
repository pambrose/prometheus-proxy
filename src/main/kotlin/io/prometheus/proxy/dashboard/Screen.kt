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

import io.prometheus.common.Utils.sanitizeUrl
import java.net.URI
import java.net.URISyntaxException

/**
 * A screen the proxy's `/screens` page shows in a tab beside the proxy's own dashboard, one entry of
 * `proxy.dashboard.screens`: an agent's dashboard, Prometheus, Grafana, or any page that allows framing.
 *
 * Parsed once, at startup ([parseAll]), so a list the page can't use fails startup, naming the entry, rather than
 * putting a broken or misleading tab on the page.
 */
internal data class Screen(
  /** The screen's id in links into the page (`/screens#<id>`), from its [name] ([idOf]). */
  val id: String,
  /** The tab's title. */
  val name: String,
  /** The screen's address, which links into it resolve against. */
  val url: String,
  /** The path the screen opens on, appended to [url], or empty to open on the url itself. */
  val start: String,
) {
  /** How the startup log names the screen: by its name, with where it opens, its url masked as every logged URL is. */
  val logged: String get() = "$name (${sanitizeUrl(url + start)})"

  /** How the Settings view shows where the screen is: its url, masked, and the path it opens on, if any. */
  val shown: String get() = sanitizeUrl(url) + if (start.isEmpty()) "" else " (opens ${sanitizeUrl(start)})"

  /** One `proxy.dashboard.screens` entry, apart from the generated `ConfigVals` type so [parseAll] is unit-testable. */
  data class Entry(
    val name: String,
    val url: String = "",
    val start: String = "",
  )

  companion object {
    /** The id of the proxy's own tab, which every page has first. */
    const val PROXY_ID = "proxy"

    /** The id of the All layout's grid in links into the page. */
    const val ALL_ID = "all"

    /** The most screens a page lists, beside the proxy's own. */
    const val MAX_SCREENS = 32

    /** The longest name a screen may have. */
    const val MAX_NAME_LENGTH = 64

    private const val SETTING = "proxy.dashboard.screens"

    private val NOT_ID = Regex("[^a-z0-9]+")

    private val SCHEMES = setOf("http", "https")

    /** A screen's id: its [name] in lowercase, each run of other characters than letters and digits a dash, trimmed. */
    fun idOf(name: String): String = name.lowercase().replace(NOT_ID, "-").trim('-')

    /**
     * Parses each of [entries], in order, refusing a list the page couldn't use: a blank or overlong name, a name
     * giving no id, an id another screen has or the page keeps for itself, a url that isn't an absolute http or
     * https URL or that carries a username or password, a start that doesn't begin with `/` or `?`, or more than
     * [MAX_SCREENS] screens.
     *
     * @throws IllegalArgumentException naming the screen and what is wrong with it
     */
    fun parseAll(entries: List<Entry>): List<Screen> {
      val names = mutableMapOf<String, String>()
      val screens =
        entries.mapIndexed { index, entry ->
          require(entry.name.isNotBlank()) { "$SETTING entry ${index + 1} has a blank name" }
          parse(entry).also { screen ->
            val other = names.putIfAbsent(screen.id, entry.name)
            require(other == null) { "${entry.described} has the id '${screen.id}', as '$other' does" }
          }
        }
      require(screens.size <= MAX_SCREENS) { "$SETTING lists ${screens.size} screens; at most $MAX_SCREENS" }
      return screens
    }

    private fun parse(entry: Entry): Screen {
      val id = idOf(entry.name)
      require(id.isNotEmpty()) { "${entry.described} has no letter or digit for its id" }
      require(entry.name.length <= MAX_NAME_LENGTH) {
        "${entry.described} has a name longer than $MAX_NAME_LENGTH characters"
      }
      require(id != PROXY_ID) { "${entry.described} has the id '$id', which the page keeps for its own tab" }
      require(id != ALL_ID) { "${entry.described} has the id '$id', which the page keeps for its grid" }
      requireWebUrl(entry)
      require(entry.start.isEmpty() || entry.start[0] in "/?") {
        "${entry.described} has a start that doesn't begin with / or ?: ${entry.start}"
      }
      return Screen(id = id, name = entry.name, url = entry.url, start = entry.start)
    }

    // An absolute http or https URL with a host, and no user info, which the page's HTML would show to anyone who can
    // read the dashboard. The authority is checked for an @ as well as the parsed user info, which a host the URI class
    // can't parse as a server, such as one with an underscore, leaves null.
    private fun requireWebUrl(entry: Entry) {
      require(entry.url.isNotEmpty()) { "${entry.described} has no url; is the variable it reads set?" }
      val uri =
        try {
          URI(entry.url)
        } catch (e: URISyntaxException) {
          throw IllegalArgumentException(entry.notWebUrl, e)
        }
      val authority = uri.rawAuthority
      require(uri.scheme?.lowercase() in SCHEMES && !authority.isNullOrEmpty()) { entry.notWebUrl }
      require(uri.rawUserInfo == null && '@' !in authority) {
        "${entry.described} has a username or password in its url, which the page would show to anyone who can " +
          "read the dashboard"
      }
    }

    // How a refusal names the screen.
    private val Entry.described: String get() = "$SETTING screen '$name'"

    private val Entry.notWebUrl: String get() = "$described has a url that isn't an absolute http or https URL: $url"
  }
}
