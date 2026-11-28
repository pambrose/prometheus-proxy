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
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.common.Exposition
import java.nio.charset.CharacterCodingException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.plusAssign

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

/** Why a filter let a scrape through unfiltered; its metricLabel is agent_filter_fail_open_total's `reason`. */
internal enum class FailOpenReason {
  /** The scrape's content type wasn't text. */
  NON_TEXT,

  /** The scrape's body wasn't valid UTF-8. */
  INVALID_UTF8,
}

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
  // Told why, once per cause, when the filter first lets a scrape through unfiltered: the agent dashboard's activity
  // feed.
  private val onFailedOpen: (String) -> Unit,
) {
  // Latched the first time each fail-open guard trips. A scrape recurs every scrape interval forever,
  // so an unguarded warn would be a permanent log-spam source. Scoped to this filter instance -- which
  // is already 1:1 with a path -- so the latches are reclaimed with the filter when a path goes away.
  private val contentTypeWarned = AtomicBoolean(false)
  private val invalidUtf8Warned = AtomicBoolean(false)

  // What the filter has dropped since the agent started, for the agent's dashboard, which shows it whether or not the
  // agent's metrics are on.
  private val droppedLines = AtomicLong(0L)
  private val savedBytes = AtomicLong(0L)

  /** The metric names kept, as regular expressions; empty keeps every name the deny list doesn't drop. */
  val allowPatterns: List<String> get() = allow.map { it.pattern }

  /** The metric names dropped, as regular expressions. */
  val denyPatterns: List<String> get() = deny.map { it.pattern }

  /** The lines the filter has dropped since the agent started. */
  val linesDropped: Long get() = droppedLines.load()

  /** The bytes the filter has saved since the agent started. */
  val bytesSaved: Long get() = savedBytes.load()

  /** Why the filter has let a scrape through unfiltered, or null when it never has (see [filterBytes]). */
  val failedOpen: String?
    get() =
      when {
        contentTypeWarned.load() -> "a scrape's content type was not text"
        invalidUtf8Warned.load() -> INVALID_UTF8
        else -> null
      }

  /**
   * Filters [bytes], or returns `null` when the payload was deliberately left untouched.
   *
   * Fails open on a non-text [contentType] or a body that is not valid UTF-8, warning once per
   * condition. A `null` return means "use the raw bytes" and is distinct from a filter that ran and
   * happened to drop nothing, so a fail-open scrape never records filter metrics. What it drops is added to
   * [linesDropped] and [bytesSaved]; [previewBytes] filters alike and leaves no trace. [onFailOpen] is told why each
   * fail-open happened, every time, for agent_filter_fail_open_total; the warning is logged only the first.
   */
  fun filterBytes(
    bytes: ByteArray,
    contentType: String,
    onFailOpen: (FailOpenReason) -> Unit = {},
  ): FilterOutcome? =
    outcome(bytes, contentType) { reason ->
      onFailOpen(reason)
      noteFailOpen(reason, contentType)
    }?.also {
      droppedLines += it.linesDropped.toLong()
      savedBytes += it.bytesSaved.toLong()
    }

  /**
   * Filters [bytes] as [filterBytes] does, leaving no trace: what it drops isn't added to [linesDropped] and
   * [bytesSaved], and a payload it lets through unfiltered is neither logged nor told to the activity feed, nor shown
   * in [failedOpen]. For a scrape a dashboard asked for, which isn't one of Prometheus's.
   */
  fun previewBytes(
    bytes: ByteArray,
    contentType: String,
  ): FilterOutcome? = outcome(bytes, contentType) {}

  // Logs why a scrape of Prometheus's went through unfiltered, and tells the activity feed, the first time for each
  // [reason]. A dashboard's preview latched it too, so it showed the filter failed open for good, and kept the first
  // scrape of Prometheus's that did from logging it (November 26 #18).
  private fun noteFailOpen(
    reason: FailOpenReason,
    contentType: String,
  ) {
    when (reason) {
      FailOpenReason.NON_TEXT -> {
        if (contentTypeWarned.compareAndSet(expectedValue = false, newValue = true)) {
          // The target's, so cut and escaped, as a value from outside the agent is logged (November 26 #42)
          val shown = shownValue(contentType, SHOWN_CONTENT_TYPE_CHARS)
          logger.warn { "Skipping metric filter for /$path: content type \"$shown\" is not text" }
          onFailedOpen("a scrape's content type, $contentType, was not text")
        }
      }

      FailOpenReason.INVALID_UTF8 -> {
        if (invalidUtf8Warned.compareAndSet(expectedValue = false, newValue = true)) {
          logger.warn { "Skipping metric filter for /$path: response body is not valid UTF-8" }
          onFailedOpen(INVALID_UTF8)
        }
      }
    }
  }

  // [bytes] filtered, or null when [failsOpen] is told why they're left untouched.
  private fun outcome(
    bytes: ByteArray,
    contentType: String,
    failsOpen: (FailOpenReason) -> Unit,
  ): FilterOutcome? {
    if (!isFilterableContentType(contentType)) {
      failsOpen(FailOpenReason.NON_TEXT)
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
      failsOpen(FailOpenReason.INVALID_UTF8)
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

    // Judges a sample line by its name, name[nameStart, nameEnd): it inherits the open family's verdict when it belongs
    // to that family, and is judged on its own name otherwise, which closes the family.
    fun keepSample(
      name: String,
      nameStart: Int,
      nameEnd: Int,
    ): Boolean {
      val family = currentFamily
      if (family != null && belongsToFamily(name, nameStart, nameEnd, family))
        return currentVerdict
      // Not part of the open family. This is the only branch that has to materialize the name, since keepFamily
      // matches regexes.
      currentFamily = null
      return keepFamily(name.substring(nameStart, nameEnd))
    }

    // Scanned by index rather than text.split('\n'): a multi-megabyte payload would otherwise
    // materialize one String per line plus the backing List before a single line is judged.
    var start = 0
    while (start <= text.length) {
      val newline = text.indexOf('\n', start)
      val end = if (newline < 0) text.length else newline
      // Inspect without a trailing \r, but emit the original line verbatim so CRLF survives.
      val inspectEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
      // The text format allows blanks before a line's first token.
      val first = Exposition.firstNonBlank(text, start, inspectEnd)

      val keep =
        when {
          first == inspectEnd -> {
            true
          }

          text[first] == '#' -> {
            val family = Exposition.commentFamily(text.substring(first, inspectEnd))
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
            // A quoted name, written as the first thing in the braces ({"a.b",...}), is unescaped first; a plain one is
            // compared in place.
            val quoted = quotedSampleName(text, first, inspectEnd)
            if (quoted != null)
              keepSample(quoted, 0, quoted.length)
            else
              keepSample(text, first, sampleNameEnd(text, first, inspectEnd))
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
     * @param onFailedOpen told why, once per cause, when the filter first lets a scrape through unfiltered: the agent
     *   dashboard's activity feed
     * @throws IllegalArgumentException if any pattern fails to compile
     */
    fun createOrNull(
      allow: List<String>,
      deny: List<String>,
      path: String,
      onFailedOpen: (String) -> Unit = {},
    ): MetricFilter? =
      if (allow.isEmpty() && deny.isEmpty())
        null
      else
        MetricFilter(path, allow.map { compile(it, path) }, deny.map { compile(it, path) }, onFailedOpen)

    private const val INVALID_UTF8 = "a scrape's body was not valid UTF-8"

    // How much of a content type the warning shows, enough for any the exposition formats use
    private const val SHOWN_CONTENT_TYPE_CHARS = 256

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
     * through untouched so a misconfigured filter can never corrupt a payload. The dashboard's
     * scrape-now counts families and samples only in the same bodies.
     */
    internal fun isFilterableContentType(contentType: String): Boolean = Exposition.isTextContentType(contentType)

    // The name of a sample line that starts at text[start] with a quoted name, such as {"a.b",job="x"} 1, or null for
    // a line with a plain name. Prometheus 3 writes a name outside the legacy character set this way. The text format
    // allows blanks before it in the braces, as before a label: { "a.b", job="x" } 1 was judged by the name "".
    private fun quotedSampleName(
      text: String,
      start: Int,
      end: Int,
    ): String? {
      if (text[start] != '{')
        return null
      val quote = Exposition.firstNonBlank(text, start + 1, end)
      return if (quote < end && text[quote] == '"') Exposition.unquote(text, quote, end) else null
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
