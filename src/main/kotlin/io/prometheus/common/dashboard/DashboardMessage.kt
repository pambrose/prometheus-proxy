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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What a dashboard page's script sends on its WebSocket ([DashboardScript]): `{"select": …, "layout": …, "seq": …,
 * "search": …}`, read field by field, each defaulting on anything unexpected rather than throwing inside the socket's
 * loop.
 */
internal object DashboardMessage {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Reads `{"select": "<id>"}`, the selected row's id, from a session message.
   *
   * Returns null on anything unexpected: this is browser-supplied input on a port that may have no auth,
   * so malformed content must degrade to "no selection" rather than propagate an exception into the
   * WebSocket loop and drop the session.
   */
  fun selection(text: String): String? = stringField(text, "select")?.takeIf { it.isNotEmpty() }

  /**
   * Reads `{"layout": "<name>"}` from a session message: the entry of [entries] it names, or [default] on anything
   * unexpected -- garbage, a missing field, an unknown name, or a browser predating the field.
   */
  fun <E : Enum<E>> layout(
    text: String,
    entries: List<E>,
    default: E,
  ): E = stringField(text, "layout")?.let { name -> entries.firstOrNull { it.name == name } } ?: default

  /**
   * Reads `{"seq": 12}`, the count of messages the page has sent, from a session message, defaulting to 0.
   *
   * Same contract as [selection]: anything unexpected -- garbage, a missing field, a string, a fraction, or a
   * negative number -- resolves to 0 rather than throwing inside the WebSocket loop. A page that predates the field
   * sends none and ignores the stamp, so 0 changes nothing for it.
   */
  fun seq(text: String): Long =
    runCatching {
      json.parseToJsonElement(text)
        .jsonObject["seq"]
        ?.jsonPrimitive
        ?.takeUnless { it.isString }
        ?.longOrNull
        ?.takeIf { it >= 0 }
    }.getOrNull() ?: 0L

  /** Reads `{"search": "?q=…"}`, the query of the page's URL, from a session message, or null without one. */
  fun search(text: String): String? = stringField(text, "search")

  // The string [field] of the JSON object [text], or null on anything else.
  private fun stringField(
    text: String,
    field: String,
  ): String? =
    runCatching { (json.parseToJsonElement(text).jsonObject[field] as? JsonPrimitive)?.takeIf { it.isString }?.content }
      .getOrNull()
}
