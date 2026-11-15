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

package io.prometheus.common

/**
 * Reads the lines of a Prometheus text exposition body, as the agent's metric filter and the dashboard's scrape-now
 * both read them, so the two agree on which bodies are text, where a line's first token starts, and which comment
 * lines open a metric family under what name. Not a full exposition parser: a family's name is all either needs from a
 * comment.
 *
 * The metric filter calls [firstNonBlank] on every line of every scrape it filters, so it works on the caller's indexes
 * rather than a copy of the line.
 */
internal object Exposition {
  // Keywords whose comment line opens a metric family.
  private val FAMILY_KEYWORDS = listOf("HELP", "TYPE", "UNIT")

  /**
   * Whether a body of [contentType] is in one of the text formats, and so can be read line by line. Blank,
   * `text/plain`, and `application/openmetrics-text` are, whatever their parameters and case; anything else, such as
   * protobuf or an HTML error page, is not.
   */
  fun isTextContentType(contentType: String): Boolean =
    contentType.substringBefore(';').trim().lowercase()
      .let { it.isEmpty() || it == "text/plain" || it == "application/openmetrics-text" }

  /**
   * The index of the first non-blank character in text[[start], [end]), or [end] when the range is blank, without
   * materializing the range. The text format allows blanks before a line's first token.
   */
  fun firstNonBlank(
    text: String,
    start: Int,
    end: Int,
  ): Int {
    for (i in start until end) {
      if (!text[i].isWhitespace()) return i
    }
    return end
  }

  /**
   * The metric family a comment [line] opens, or null for any other line: "# HELP foo help text", "# TYPE foo counter",
   * and "# UNIT foo seconds" open "foo". [line] is cut to start at its first non-blank character, since the text format
   * allows blanks before it, and to end before a trailing carriage return; one that doesn't start with `#` opens
   * nothing.
   *
   * The keyword terminator matches either a space or a tab, symmetric with the name terminator, so "# TYPE\tfoo
   * counter" opens the family just like "# TYPE foo counter". A quoted name, such as `# TYPE "a.b" counter`, is
   * unquoted (see [unquote]), so it matches as written in a config, and a space inside it doesn't end it.
   */
  fun commentFamily(line: String): String? {
    if (!line.startsWith('#')) return null
    val body = line.substring(1).trimStart()
    val keyword = FAMILY_KEYWORDS.firstOrNull { body.startsWith("$it ") || body.startsWith("$it\t") } ?: return null
    val rest = body.substring(keyword.length + 1).trimStart()
    val name =
      if (rest.startsWith('"'))
        unquote(rest, 0)
      else
        rest.substringBefore(' ').substringBefore('\t')
    return name?.takeIf { it.isNotEmpty() }
  }

  /**
   * The quoted string that opens at text[[quote]], unescaped as the text format escapes a name (`\\`, `\"`, and `\n`),
   * or null when the quote is not closed before [end]. Prometheus 3 writes a name outside the legacy character set
   * this way.
   */
  fun unquote(
    text: String,
    quote: Int,
    end: Int = text.length,
  ): String? {
    val name = StringBuilder()
    var i = quote + 1
    while (i < end) {
      when (val c = text[i]) {
        '"' -> {
          return name.toString()
        }

        '\\' if i + 1 < end -> {
          name.append(if (text[i + 1] == 'n') '\n' else text[i + 1])
          i++
        }

        else -> {
          name.append(c)
        }
      }
      i++
    }
    return null
  }
}
