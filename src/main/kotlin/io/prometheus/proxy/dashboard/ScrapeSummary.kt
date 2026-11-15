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

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.prometheus.common.AgentValues
import io.prometheus.common.Exposition
import io.prometheus.proxy.ProxyHttpRoutes
import io.prometheus.proxy.ScrapeRequestResponse
import kotlin.time.Duration

/**
 * What a scrape-now found, for the dashboard's result panel: how the scrape went, what came back, and the start of it.
 *
 * Built from the scrape's responses, one per agent of the path, merged as the scrape route merges them for Prometheus,
 * so the panel shows what Prometheus would have got.
 *
 * @param outcome each agent's outcome label, as the proxy's scrape metrics name it ("success", "upstream_error", ...)
 * @param duration the slowest agent's
 * @param bytes the body's size before decoding, across the agents
 * @param families the metric families the body's `# HELP`, `# TYPE`, and `# UNIT` lines open, read as the agent's
 *   metric filter reads them (see [Exposition]), or 0 for a body in neither text format, such as protobuf
 * @param samples its sample lines, or 0 for a body in neither text format
 * @param failure the first agent's failure reason, cut and escaped, or empty
 * @param excerpt the start of the body, at most [MAX_EXCERPT_CHARS] characters, ending at a line break where it is cut,
 *   with each control character but a line break, a tab, and a carriage return escaped
 * @param isWhole whether [excerpt] is the whole body
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
) {
  val isSuccess: Boolean get() = statusCode.isSuccess()

  val durationMillis: Long get() = duration.inWholeMilliseconds

  companion object {
    // Enough to recognize a payload and read its first families, little enough to show in a panel.
    const val MAX_EXCERPT_CHARS = 2048

    // As much of an agent's failure reason as a log line shows.
    private const val MAX_FAILURE_CHARS = 512

    fun of(
      path: String,
      responses: List<ScrapeRequestResponse>,
    ): ScrapeSummary {
      val merged = ProxyHttpRoutes.mergeResponseResults(responses)
      val text = merged.contentText
      val excerpt = excerpt(text)
      val contentType = merged.contentType.withoutParameters().toString()
      // Only a body in one of the text formats, the ones the agent's metric filter reads, has lines to count; a
      // protobuf body read as text would count its bytes as samples.
      val counted = if (Exposition.isTextContentType(contentType)) text else ""
      return ScrapeSummary(
        path = path,
        statusCode = merged.statusCode,
        outcome = merged.updateMsgs.distinct().joinToString(", "),
        duration = responses.maxOf { it.fetchDuration },
        bytes = responses.sumOf { it.byteCount },
        contentType = contentType,
        families = families(counted),
        samples = samples(counted),
        hasEof = endsWithEof(text),
        failure = responses.firstOrNull { it.failureReason.isNotEmpty() }
          ?.let { AgentValues.shownValue(it.failureReason, MAX_FAILURE_CHARS) }
          .orEmpty(),
        // A body's own layout kept, and every other control character escaped, as a log line escapes it.
        excerpt = AgentValues.escaped(excerpt) { it == '\n' || it == '\t' || it == '\r' },
        isWhole = excerpt.length == text.length,
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
      val marker = ProxyHttpRoutes.EOF_MARKER
      return end >= marker.length && text.regionMatches(end - marker.length, marker, 0, marker.length)
    }
  }
}
