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

package io.prometheus.agent.filter

/**
 * Outcome of filtering one scraped payload.
 *
 * @param text the filtered exposition text
 * @param linesDropped how many lines the filter removed
 */
internal data class FilterResult(
  val text: String,
  val linesDropped: Int,
)

/**
 * Drops metric families from a Prometheus text exposition payload by metric name.
 *
 * Deliberately line-oriented rather than a full exposition parser: the format is line-based, so
 * allow/deny filtering needs only the metric-name prefix of each line. Regexes are compiled once at
 * path-registration time (see [createOrNull]), never per scrape, and are matched **fully anchored**
 * to mirror Prometheus's own `relabel_config` semantics.
 *
 * @see io.prometheus.agent.AgentPathManager
 */
internal class MetricFilter private constructor(
  private val allow: List<Regex>,
  private val deny: List<Regex>,
) {
  fun filterText(text: String): FilterResult {
    if (text.isEmpty())
      return FilterResult(text, 0)

    val sb = StringBuilder(text.length)
    var linesDropped = 0
    var currentFamily: String? = null
    var currentVerdict = true
    val lines = text.split('\n')

    for ((index, rawLine) in lines.withIndex()) {
      // Inspect without a trailing \r, but emit the original line verbatim so CRLF survives.
      val line = rawLine.removeSuffix("\r")
      val keep =
        when {
          line.isBlank() -> {
            true
          }

          line.startsWith("#") -> {
            val family = parseCommentFamily(line)
            if (family == null) {
              true // Unrecognized comment (e.g. "# EOF") passes through untouched.
            } else {
              // A HELP/TYPE line opens a family: decide once here, then every sample line of the
              // family inherits the verdict, so histograms and summaries stay intact.
              currentFamily = family
              currentVerdict = keepFamily(family)
              currentVerdict
            }
          }

          else -> {
            val name = parseSampleName(line)
            val family = currentFamily
            if (family != null && belongsToFamily(name, family)) {
              currentVerdict
            } else {
              // Not part of the open family -- close it and judge this line on its own name.
              currentFamily = null
              keepFamily(name)
            }
          }
        }

      if (keep) {
        sb.append(rawLine)
        if (index != lines.lastIndex)
          sb.append('\n')
      } else {
        linesDropped++
      }
    }

    return FilterResult(sb.toString(), linesDropped)
  }

  // An empty allow list allows everything; deny is evaluated after allow, so deny wins on overlap.
  private fun keepFamily(family: String): Boolean =
    (allow.isEmpty() || allow.any { it.matches(family) }) && deny.none { it.matches(family) }

  companion object {
    /**
     * Compiles [allow] and [deny] into a filter, or returns `null` when both are empty so that
     * "no filter" stays a distinct state rather than an identity transform.
     *
     * @param path the config path these rules belong to, used only in the invalid-regex message
     * @throws IllegalArgumentException if any pattern fails to compile
     */
    fun createOrNull(
      allow: List<String>,
      deny: List<String>,
      path: String,
    ): MetricFilter? =
      if (allow.isEmpty() && deny.isEmpty())
        null
      else
        MetricFilter(allow.map { compile(it, path) }, deny.map { compile(it, path) })

    private fun compile(
      pattern: String,
      path: String,
    ): Regex =
      runCatching { Regex(pattern) }
        .getOrElse {
          throw IllegalArgumentException("Invalid metric filter regex \"$pattern\" for path /$path", it)
        }

    // Metric name is everything before the first '{' or whitespace, so label values -- which may
    // contain '}' or '#' -- can never influence matching.
    internal fun parseSampleName(line: String): String {
      val end = line.indexOfFirst { it == '{' || it == ' ' || it == '\t' }
      return if (end < 0) line else line.substring(0, end)
    }

    // Suffixes a histogram, summary, or OpenMetrics counter family appends to its family name.
    private val FAMILY_SUFFIXES =
      setOf("_bucket", "_sum", "_count", "_created", "_total", "_gsum", "_gcount", "_info")

    // "# HELP foo help text" / "# TYPE foo counter" -> "foo". Any other comment -> null.
    internal fun parseCommentFamily(line: String): String? {
      val body = line.removePrefix("#").trimStart()
      val rest =
        when {
          body.startsWith("HELP ") -> body.removePrefix("HELP ")
          body.startsWith("TYPE ") -> body.removePrefix("TYPE ")
          else -> return null
        }
      return rest.trimStart().substringBefore(' ').substringBefore('\t').takeIf { it.isNotEmpty() }
    }

    // Exact family name, or the family plus one of its recognized suffixes. Deliberately NOT a
    // startsWith() test: that would fold "http_requests_total" into an unrelated "http_requests"
    // family, and fold a standalone "items_count" counter into family "items".
    internal fun belongsToFamily(
      name: String,
      family: String,
    ): Boolean = name == family || (name.startsWith(family) && name.substring(family.length) in FAMILY_SUFFIXES)
  }
}
