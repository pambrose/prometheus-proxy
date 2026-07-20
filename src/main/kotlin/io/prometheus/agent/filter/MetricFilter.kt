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

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import java.nio.charset.CharacterCodingException
import java.util.concurrent.atomic.AtomicBoolean

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
 * Outcome of filtering one scraped payload's raw bytes.
 *
 * [text] is the filtered exposition text that [bytes] encodes, carried alongside so a caller that
 * needs the string form does not re-decode bytes this class just encoded.
 *
 * @param bytes the filtered payload, UTF-8 encoded
 * @param text the filtered payload as text
 * @param linesDropped how many lines the filter removed
 * @param bytesSaved how many bytes filtering removed from the raw payload
 */
internal class FilterOutcome(
  val bytes: ByteArray,
  val text: String,
  val linesDropped: Int,
  val bytesSaved: Int,
)

/**
 * Drops metric families from a Prometheus text exposition payload by metric name.
 *
 * Stateful and family-scoped rather than a full exposition parser: a `# HELP`/`# TYPE`/`# UNIT`
 * comment line opens a family and the allow/deny verdict is computed once against the family name;
 * every subsequent sample line that belongs to that family (exact name match, or the family name plus
 * a recognized histogram/summary/OpenMetrics suffix -- see [belongsToFamily]) inherits the verdict
 * without being matched again. A sample line that does not belong to the open family closes it and is
 * judged literally against its own full name. Regexes are compiled once at path-registration time
 * (see [createOrNull]), never per scrape, and are matched **fully anchored** to mirror Prometheus's
 * own `relabel_config` semantics.
 *
 * One consequence of family scoping worth calling out: once a family is open, a series-level rule such
 * as `deny = ["http_req_duration_seconds_bucket"]` is silently a no-op, because `_bucket` lines inherit
 * the family's verdict rather than being matched against the deny list themselves. This is intended --
 * it's what keeps a histogram's buckets, sum, and count together -- but it means allow/deny rules only
 * ever operate at family granularity, never at individual series granularity.
 *
 * @see io.prometheus.agent.AgentPathManager
 */
internal class MetricFilter private constructor(
  private val path: String,
  private val allow: List<Regex>,
  private val deny: List<Regex>,
) {
  // Latched the first time each fail-open guard trips. A scrape recurs every scrape interval forever,
  // so an unguarded warn would be a permanent log-spam source. Scoped to this filter instance -- which
  // is already 1:1 with a path -- so the latches are reclaimed with the filter when a path goes away.
  private val contentTypeWarned = AtomicBoolean(false)
  private val invalidUtf8Warned = AtomicBoolean(false)

  /**
   * Filters [bytes], or returns `null` when the payload was deliberately left untouched.
   *
   * Fails open on a non-text [contentType] or a body that is not valid UTF-8, warning once per
   * condition. A `null` return means "use the raw bytes" and is distinct from a filter that ran and
   * happened to drop nothing, so a fail-open scrape never records filter metrics.
   */
  fun filterBytes(
    bytes: ByteArray,
    contentType: String,
  ): FilterOutcome? {
    if (!isFilterableContentType(contentType)) {
      if (contentTypeWarned.compareAndSet(false, true))
        logger.warn { "Skipping metric filter for /$path: content type \"$contentType\" is not text" }
      return null
    }

    // Lenient decodeToString() replaces each malformed byte with U+FFFD, which encodes back to 3 bytes
    // -- silently corrupting the payload, and (if the expansion outgrows what filtering removed)
    // driving bytesSaved negative, which Counter.inc() rejects with IllegalArgumentException, turning a
    // routine scrape into a 503. A strict decode sidesteps both: on malformed input it throws instead
    // of substituting, so we can detect it and pass the raw bytes through untouched.
    val decoded =
      try {
        bytes.decodeToString(throwOnInvalidSequence = true)
      } catch (expected: CharacterCodingException) {
        null
      }
    if (decoded == null) {
      if (invalidUtf8Warned.compareAndSet(false, true))
        logger.warn { "Skipping metric filter for /$path: response body is not valid UTF-8" }
      return null
    }

    val result = filterText(decoded)
    // filtered.size <= raw.size is a provable invariant here: filterText only removes whole lines from
    // a successfully strict-decoded string, and re-encoding a string that came from valid UTF-8
    // reproduces the exact bytes it was decoded from -- so bytesSaved can never go negative.
    val filtered = result.text.encodeToByteArray()
    return FilterOutcome(filtered, result.text, result.linesDropped, bytes.size - filtered.size)
  }

  fun filterText(text: String): FilterResult {
    if (text.isEmpty())
      return FilterResult(text, 0)

    val sb = StringBuilder(text.length)
    var linesDropped = 0
    var currentFamily: String? = null
    var currentVerdict = true

    // Scanned by index rather than text.split('\n'): a multi-megabyte payload would otherwise
    // materialize one String per line plus the backing List before a single line is judged.
    var start = 0
    while (start <= text.length) {
      val newline = text.indexOf('\n', start)
      val end = if (newline < 0) text.length else newline
      // Inspect without a trailing \r, but emit the original line verbatim so CRLF survives.
      val inspectEnd = if (end > start && text[end - 1] == '\r') end - 1 else end

      val keep =
        when {
          isBlank(text, start, inspectEnd) -> {
            true
          }

          text[start] == '#' -> {
            val family = parseCommentFamily(text.substring(start, inspectEnd))
            if (family == null) {
              true // Unrecognized comment (e.g. "# EOF") passes through untouched.
            } else {
              // A HELP/TYPE/UNIT line opens a family: decide once here, then every sample line of the
              // family inherits the verdict, so histograms and summaries stay intact. Guarded on a
              // name change so a family's HELP, TYPE, and UNIT lines don't each re-run every regex.
              if (family != currentFamily) {
                currentFamily = family
                currentVerdict = keepFamily(family)
              }
              currentVerdict
            }
          }

          else -> {
            val nameEnd = sampleNameEnd(text, start, inspectEnd)
            val family = currentFamily
            if (family != null && belongsToFamily(text, start, nameEnd, family)) {
              currentVerdict
            } else {
              // Not part of the open family -- close it and judge this line on its own name. This is
              // the only branch that has to materialize the name, since keepFamily matches regexes.
              currentFamily = null
              keepFamily(text.substring(start, nameEnd))
            }
          }
        }

      if (keep) {
        sb.append(text, start, end)
        if (newline >= 0)
          sb.append('\n')
      } else {
        linesDropped++
      }

      if (newline < 0) break
      start = newline + 1
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
        MetricFilter(path, allow.map { compile(it, path) }, deny.map { compile(it, path) })

    private fun compile(
      pattern: String,
      path: String,
    ): Regex =
      runCatching { Regex(pattern) }
        .getOrElse {
          throw IllegalArgumentException("Invalid metric filter regex \"$pattern\" for path /$path", it)
        }

    /**
     * Whether a payload of [contentType] is safe to filter line-by-line. Blank, `text/plain`, and
     * `application/openmetrics-text` are; anything else (protobuf, an HTML error body) is passed
     * through untouched so a misconfigured filter can never corrupt a payload.
     */
    internal fun isFilterableContentType(contentType: String): Boolean =
      contentType.substringBefore(';').trim().lowercase()
        .let { it.isEmpty() || it == "text/plain" || it == "application/openmetrics-text" }

    // Whether text[start, end) is empty or all whitespace, without materializing the range.
    private fun isBlank(
      text: String,
      start: Int,
      end: Int,
    ): Boolean {
      for (i in start until end) {
        if (!text[i].isWhitespace()) return false
      }
      return true
    }

    // Metric name is everything before the first '{' or whitespace, so label values -- which may
    // contain '}' or '#' -- can never influence matching. Returns the exclusive end index of the name
    // within text rather than the name itself: on the hot path the name is only ever compared, and the
    // comparison can run against the original string.
    private fun sampleNameEnd(
      text: String,
      start: Int,
      end: Int,
    ): Int {
      for (i in start until end) {
        val c = text[i]
        if (c == '{' || c == ' ' || c == '\t') return i
      }
      return end
    }

    // Suffixes a histogram, summary, or OpenMetrics counter family appends to its family name.
    private val FAMILY_SUFFIXES =
      setOf("_bucket", "_sum", "_count", "_created", "_total", "_gsum", "_gcount", "_info")

    // Keywords whose comment line opens a metric family.
    private val FAMILY_KEYWORDS = listOf("HELP", "TYPE", "UNIT")

    // "# HELP foo help text" / "# TYPE foo counter" / "# UNIT foo seconds" -> "foo". Any other
    // comment -> null. The keyword terminator matches either a space or a tab, symmetric with the
    // name terminator below, so "# TYPE\tfoo counter" opens the family just like "# TYPE foo counter".
    private fun parseCommentFamily(line: String): String? {
      val body = line.removePrefix("#").trimStart()
      val keyword = FAMILY_KEYWORDS.firstOrNull { body.startsWith("$it ") || body.startsWith("$it\t") } ?: return null
      return body.substring(keyword.length + 1)
        .trimStart()
        .substringBefore(' ')
        .substringBefore('\t')
        .takeIf { it.isNotEmpty() }
    }

    // Whether the metric name at text[start, nameEnd) is the exact family name, or the family plus one
    // of its recognized suffixes. Deliberately NOT a startsWith() test: that would fold
    // "http_requests_total" into an unrelated "http_requests" family, and fold a standalone
    // "items_count" counter into family "items". Compared in place so the overwhelmingly common case --
    // a sample line belonging to the open family -- allocates nothing.
    private fun belongsToFamily(
      text: String,
      start: Int,
      nameEnd: Int,
      family: String,
    ): Boolean {
      val nameLength = nameEnd - start
      if (nameLength < family.length || !text.regionMatches(start, family, 0, family.length))
        return false
      if (nameLength == family.length)
        return true
      val suffixStart = start + family.length
      val suffixLength = nameEnd - suffixStart
      return FAMILY_SUFFIXES.any { it.length == suffixLength && text.regionMatches(suffixStart, it, 0, it.length) }
    }

    private val logger = logger {}
  }
}
