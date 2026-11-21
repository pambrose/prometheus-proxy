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

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.prometheus.common.AgentValues
import io.prometheus.common.Exposition
import kotlin.time.Duration

/**
 * What a scrape-now found, for a dashboard's result panel ([ScrapeNowHtml]): how the scrape went, what came back, and
 * the start of it.
 *
 * Built by [of] from the [RawScrape] a dashboard got: on the proxy's, the path's agents' responses merged as the scrape
 * route merges them for Prometheus, so the panel shows what Prometheus would have got; on the agent's, the body of its
 * own fetch of the target.
 *
 * @param outcome how the scrape ended, as the role's scrape metrics name it ("success", "upstream_error", ...)
 * @param duration how long the scrape took; on the proxy's, its slowest agent's
 * @param bytes the body's size before decoding; on the proxy's, across the agents
 * @param families the metric families the body's `# HELP`, `# TYPE`, and `# UNIT` lines open, read as the agent's
 *   metric filter reads them (see [Exposition]), or 0 for a body in neither text format, such as protobuf (see
 *   [isText])
 * @param samples its sample lines, or 0 for a body in neither text format
 * @param failure the first agent's failure reason, cut and escaped, or empty
 * @param excerpt the start of the body, at most [MAX_EXCERPT_CHARS] characters, ending at a line break where it is cut,
 *   with each control character but a line break, a tab, and a carriage return escaped
 * @param isWhole whether [excerpt] is the whole body
 * @param filteredBytes on the agent's, the body's size after the path's metric filter, which [excerpt] is the start
 *   of; null on the proxy's, whose bodies arrive filtered
 */
internal data class ScrapeSummary(
  val path: String,
  val statusCode: HttpStatusCode,
  val outcome: String,
  val duration: Duration,
  val bytes: Long,
  val contentType: String,
  val families: Int,
  val samples: Int,
  val hasEof: Boolean,
  val failure: String,
  val excerpt: String,
  val isWhole: Boolean,
  val filteredBytes: Long? = null,
) {
  val isSuccess: Boolean get() = statusCode.isSuccess()

  // Whether the body is in one of the text formats, the only ones whose lines [families] and [samples] count. Derived
  // from the content type, as [of] decides what to count, so a summary can't hold counts and a flag that disagree; the
  // result panel shows the counts only for such a body, since 0 of each reads as an empty text body.
  val isText: Boolean get() = Exposition.isTextContentType(contentType)

  val durationMillis: Long get() = duration.inWholeMilliseconds

  companion object {
    // Enough to recognize a payload and read its first families, little enough to show in a panel.
    const val MAX_EXCERPT_CHARS = 2048

    // As much of a failure reason as a log line shows.
    private const val MAX_FAILURE_CHARS = 512

    /**
     * The summary of [raw]: its body's families and samples when it is in a text format, its end marker, and its start,
     * with its failure's reason cut and escaped.
     */
    fun of(raw: RawScrape): ScrapeSummary {
      val text = raw.text
      val excerpt = excerpt(text)
      // Only a body in one of the text formats, the ones the agent's metric filter reads, has lines to count; a
      // protobuf body read as text would count its bytes as samples.
      val counted = if (Exposition.isTextContentType(raw.contentType)) text else ""
      return ScrapeSummary(
        path = raw.path,
        statusCode = raw.statusCode,
        outcome = raw.outcome,
        duration = raw.duration,
        bytes = raw.bytes,
        contentType = raw.contentType,
        families = families(counted),
        samples = samples(counted),
        hasEof = endsWithEof(text),
        failure = if (raw.failure.isEmpty()) "" else AgentValues.shownValue(raw.failure, MAX_FAILURE_CHARS),
        // A body's own layout kept, and every other control character escaped, as a log line escapes it.
        excerpt = AgentValues.escaped(excerpt) { it == '\n' || it == '\t' || it == '\r' },
        isWhole = excerpt.length == text.length,
        filteredBytes = raw.filteredBytes,
      )
    }

    // The distinct metric families the body's HELP, TYPE, and UNIT lines open, named as the agent's metric filter
    // names them: a quoted name unquoted, so `# TYPE "a b" counter` opens "a b", and a tab after the keyword as good
    // as a space.
    internal fun families(text: String): Int =
      nonBlankLines(text).mapNotNullTo(HashSet(), Exposition::commentFamily).size

    // Lines that are neither blank nor a comment: one sample each.
    internal fun samples(text: String): Int = nonBlankLines(text).count { !it.startsWith('#') }

    // The body's non-blank lines, each cut from its first token to before a carriage return that ends it, as the
    // agent's metric filter reads a line, so an indented comment is a comment and a carriage return never ends up in a
    // family's name. Split at line feeds rather than by lineSequence(), which also splits at a lone carriage return;
    // the filter doesn't.
    private fun nonBlankLines(text: String): Sequence<String> =
      text.splitToSequence('\n')
        .map { line ->
          val end = if (line.endsWith('\r')) line.length - 1 else line.length
          line.substring(Exposition.firstNonBlank(line, 0, end), end)
        }.filter { it.isNotEmpty() }

    // The body whole, or its first MAX_EXCERPT_CHARS characters cut at the last line break, and never inside a
    // surrogate pair when it has none.
    internal fun excerpt(text: String): String {
      if (text.length <= MAX_EXCERPT_CHARS) return text
      val lineEnd = text.lastIndexOf('\n', MAX_EXCERPT_CHARS - 1)
      return if (lineEnd > 0) text.substring(0, lineEnd + 1) else AgentValues.truncate(text, MAX_EXCERPT_CHARS)
    }

    // Whether the body's last line is the OpenMetrics end marker, without copying a body of several MB to trim it.
    private fun endsWithEof(text: String): Boolean {
      val end = text.indexOfLast { !it.isWhitespace() } + 1
      val marker = Exposition.EOF_MARKER
      return end >= marker.length && text.regionMatches(end - marker.length, marker, 0, marker.length)
    }
  }
}

/**
 * A scrape as a dashboard's scrape-now got it, before [ScrapeSummary.of] summarizes it.
 *
 * @param bytes the body's size before decoding
 * @param text the body, as the proxy gets it
 * @param failure why it failed, as the role reported it, or empty
 * @param filteredBytes on the agent's, the body's size after the path's metric filter, which [text] is then the body
 *   of; null on the proxy's
 */
internal data class RawScrape(
  val path: String,
  val statusCode: HttpStatusCode,
  val outcome: String,
  val duration: Duration,
  val bytes: Long,
  val contentType: String,
  val text: String,
  val failure: String,
  val filteredBytes: Long? = null,
)
