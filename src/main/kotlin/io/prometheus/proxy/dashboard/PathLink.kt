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

import io.ktor.http.encodeURLParameter
import io.prometheus.proxy.AgentAuthManager.Companion.LEGACY_IDENTITY_NAME
import java.net.URI
import java.net.URISyntaxException

/**
 * A link from each row of the Paths table into another tool, one entry of `proxy.dashboard.links`: its [name], which is
 * the link's text, and a URL template whose placeholders each row fills from its path.
 *
 * The proxy can't build such a link itself. Service discovery gives every path the same target, the proxy's own scrape
 * address, and Prometheus drops `__metrics_path__` after relabeling unless a deployment keeps it, so what identifies a
 * path in Prometheus or Grafana is the deployment's choice: usually a label the agent registered the path with, such
 * as a job of its own. The operator says which, in the template.
 *
 * Parsed once, at startup ([parseAll]), so a template the dashboard can't use fails startup rather than putting a
 * broken link on every row, and each push only joins the parsed pieces.
 */
internal class PathLink private constructor(
  /** The link's text, and the tool its `aria-label` says the path opens in. */
  val name: String,
  // The template cut at its placeholders: literal text gives itself, and a placeholder gives its value for a path,
  // encoded, or null when the path has none.
  private val segments: List<(PathView) -> String?>,
) {
  /**
   * This link's URL for [path], or null when the path lacks a value the template needs: a label it didn't register
   * (any label, for a departed path, whose labels went with its entry), an identity, or a serving agent. A link with an
   * empty slot would open the tool somewhere other than the path while looking just like one that works.
   */
  fun urlFor(path: PathView): String? =
    buildString {
      for (segment in segments) append(segment(path) ?: return null)
    }

  /** One `proxy.dashboard.links` entry, apart from the generated `ConfigVals` type so [parseAll] is unit-testable. */
  data class Entry(
    val name: String,
    val url: String,
  )

  companion object {
    private const val LABEL_PREFIX = "label."

    // A placeholder is braces around anything but braces. A brace outside one stays in the URL, which then fails to
    // parse, so PromQL's own braces must be percent-encoded, as a URL needs them to be anyway.
    private val PLACEHOLDER = Regex("""\{([^{}]*)\}""")

    private val SCHEMES = setOf("http", "https")

    // How a path fills each placeholder but a label. The legacy proxy.agentToken is an identity in name only, one token
    // every agent shares, so it fills no {identity}.
    private val VALUES: Map<String, (PathView) -> String?> =
      mapOf(
        "path" to { it.path },
        "agent" to { it.servingAgent },
        "identity" to { path -> path.identity.takeUnless { it == LEGACY_IDENTITY_NAME } },
      )

    /**
     * Parses each of [entries], refusing a link the dashboard couldn't render: a blank name, a URL that isn't an
     * absolute http or https URL, a placeholder it doesn't know, or a placeholder before the end of the URL's host,
     * where a value an agent supplies could choose the site the link opens.
     *
     * @throws IllegalArgumentException naming the link and what is wrong with it
     */
    fun parseAll(entries: List<Entry>): List<PathLink> =
      entries.mapIndexed { index, entry ->
        require(entry.name.isNotBlank()) { "proxy.dashboard.links entry ${index + 1} has a blank name" }
        parse(entry)
      }

    private fun parse(entry: Entry): PathLink {
      val template = entry.url
      val placeholders = PLACEHOLDER.findAll(template).toList()
      requireAbsoluteUrl(entry, placeholders)

      val segments =
        buildList<(PathView) -> String?> {
          var start = 0
          for (placeholder in placeholders) {
            literal(template.substring(start, placeholder.range.first))
            val key = placeholder.groupValues[1]
            val value =
              requireNotNull(valueOf(key)) {
                "${entry.described} has an unknown placeholder {$key} in its url; " +
                  "use {path}, {agent}, {identity}, or {label.<name>}"
              }
            // Encoded as a URL component, so a value an agent supplies can't add a path, query, or fragment of its own.
            add { path -> value(path)?.takeIf { it.isNotEmpty() }?.encodeURLParameter() }
            start = placeholder.range.last + 1
          }
          literal(template.substring(start))
        }
      return PathLink(entry.name, segments)
    }

    // How a refusal names the link.
    private val Entry.described: String get() = "proxy.dashboard.links link '$name'"

    private fun MutableList<(PathView) -> String?>.literal(text: String) {
      if (text.isNotEmpty()) add { text }
    }

    private fun valueOf(key: String): ((PathView) -> String?)? {
      val label = key.removePrefix(LABEL_PREFIX)
      return when {
        key in VALUES -> VALUES[key]
        key.startsWith(LABEL_PREFIX) && label.isNotEmpty() -> { path -> path.labelValues[label] }
        else -> null
      }
    }

    // Checks the entry's URL with each placeholder replaced by as many x's, so a position in the one is the same
    // position in the other, which is how the last check tells where the host ends.
    private fun requireAbsoluteUrl(
      entry: Entry,
      placeholders: List<MatchResult>,
    ) {
      val filled = PLACEHOLDER.replace(entry.url) { "x".repeat(it.value.length) }
      val uri =
        try {
          URI(filled)
        } catch (e: URISyntaxException) {
          val problem = "has a url that isn't a valid URL (${e.reason})"
          throw IllegalArgumentException("${entry.described} $problem: ${entry.url}", e)
        }
      require(uri.scheme?.lowercase() in SCHEMES && !uri.rawAuthority.isNullOrEmpty()) {
        "${entry.described} has a url that isn't an absolute http or https URL: ${entry.url}"
      }
      val origin = "${uri.scheme}://${uri.rawAuthority}".length
      require(placeholders.none { it.range.first < origin }) {
        "${entry.described} has a placeholder in its url's scheme or host, where a value an agent supplies could " +
          "choose the site the link opens: ${entry.url}"
      }
    }
  }
}
