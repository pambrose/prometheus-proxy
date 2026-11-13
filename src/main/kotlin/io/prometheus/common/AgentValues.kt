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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import io.prometheus.common.Utils.toJsonElement
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks, cuts, and escapes for the values an agent supplies -- its paths and their labels, what it reports about
 * itself and its targets, and its scrape results -- before the proxy keeps or logs them. The agent runs the path and
 * label checks on its own config at startup, so a value the proxy would refuse or leave out is reported where it can be
 * fixed.
 */
internal object AgentValues {
  // How many characters shownValue keeps of a value, unless told otherwise; the proxy's paths and agentIds have always
  // been shown cut here.
  private const val SHOWN_VALUE_CHARS = 64
  private const val ESCAPE_ALLOWANCE = 16
  private const val SHOWN_LABEL_NAME_CHARS = 64

  // A Prometheus label name, as every Prometheus version accepts one. Prometheus 3 can accept more, but a name 2.x
  // refuses fails a 2.x server's whole service discovery response.
  private val LABEL_NAME = Regex("[a-zA-Z_][a-zA-Z0-9_]*")

  /**
   * [value] cut to its first [max] characters, or one fewer when the last would be the first half of a surrogate pair:
   * an unpaired surrogate can't be encoded as UTF-8, in the logs or service discovery. Whole when it is no longer than
   * [max], or when [max] is 0 or less, the proxy.internal limits' "unlimited".
   */
  fun truncate(
    value: String,
    max: Int,
  ): String =
    if (max <= 0 || value.length <= max)
      value
    else
      value.substring(0, if (value[max - 1].isHighSurrogate()) max - 1 else max)

  /**
   * [value] as a log line or a reason shows it: whole up to [maxChars] characters, and otherwise cut there (see
   * [truncate]) and marked "...", with each control character and line or paragraph separator escaped, as `\n`, `\r`,
   * `\t`, or `\uXXXX`. Every value an agent supplies goes through it before it is logged: whole, one could flood the
   * log, and with its CR/LF intact, forge lines in it (November #16). A [maxChars] of 0 or less cuts nothing, for a
   * value already bounded where it was stored.
   */
  fun shownValue(
    value: String,
    maxChars: Int = SHOWN_VALUE_CHARS,
  ): String {
    val cut = truncate(value, maxChars).let { if (it.length < value.length) "$it..." else it }
    return if (cut.none { it.isUnsafeInLog() })
      cut
    else
      buildString(cut.length + ESCAPE_ALLOWANCE) {
        cut.forEach { c ->
          when {
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.isUnsafeInLog() -> append("\\u%04x".format(c.code))
            else -> append(c)
          }
        }
      }
  }

  // A control character (U+0000-U+001F, U+007F-U+009F, NEL among them), or a line or paragraph separator, which some
  // log viewers also break lines at.
  private fun Char.isUnsafeInLog() = isISOControl() || this == '\u2028' || this == '\u2029'

  /**
   * Why no scrape can reach [path], a path without its leading slash, because of a character in it, or null when no
   * character stops one. A control character or whitespace can't appear in an HTTP request target, and whitespace ends
   * it in the scrape port's request line; '?' starts the query, so the scrape route never sees what follows it; and a
   * client never sends '#', which starts a fragment. Each also lets a path forge or break up lines in the logs it is
   * written to (November #16). Every other character, percent-encoded or not, is left to the scrape route, as before.
   */
  fun pathCharacterProblem(path: String): String? =
    path.firstOrNull { it.isISOControl() || it.isWhitespace() || it == '?' || it == '#' }
      ?.let { "'${shownValue(it.toString())}' can't appear in a scrape's request path" }

  /** Whether [name] is a label name every Prometheus version accepts in service discovery. */
  fun isValidLabelName(name: String): Boolean = LABEL_NAME.matches(name)

  /**
   * Why some of [labels], a path's labels, can't appear in service discovery, or null when all can: a blank value, or
   * a JSON object whose keys are label names and whose values are strings. Prometheus decodes the labels as a map of
   * string to string, and one entry that isn't fails the whole response, so every target behind the proxy stops
   * updating; the proxy leaves such entries out of it.
   */
  fun labelsProblem(labels: String): String? =
    if (labels.isBlank()) null else labelsProblem(runCatching { labels.toJsonElement() }.getOrNull())

  /** [labelsProblem] for non-blank labels already parsed, as [parsed], which is null when they don't parse. */
  fun labelsProblem(parsed: JsonElement?): String? {
    val json = parsed as? JsonObject ?: return "labels must be a JSON object, such as {\"team\":\"web\"}"
    return json.entries.firstNotNullOfOrNull { (name, value) ->
      // The proxy logs this, so a name an agent sends is cut and escaped (November #16).
      val shown = shownValue(name, SHOWN_LABEL_NAME_CHARS)
      when {
        !isValidLabelName(name) -> "label name '$shown' isn't a valid Prometheus label name ([a-zA-Z_][a-zA-Z0-9_]*)"
        value !is JsonPrimitive || !value.isString -> "label '$shown' must have a string value"
        else -> null
      }
    }
  }
}
