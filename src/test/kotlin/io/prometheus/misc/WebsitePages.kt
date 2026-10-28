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

package io.prometheus.misc

import java.io.File

// The docs site's pages, for the specs that check the metric names, config keys, and defaults their prose gives.
internal object WebsitePages {
  private const val DOCS_DIR = "website/prometheus-proxy/docs"

  // A fenced code block, indented when it sits in a tab or an admonition. Its fences would otherwise pair with the
  // inline code around them, and its contents are configs, commands, and snippets rather than prose.
  private val FENCED_BLOCK =
    Regex("""^[ \t]*```.*?^[ \t]*```""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
  private val INLINE_CODE = Regex("""`([^`]+)`""")

  // Each page without its fenced code blocks, keyed by the page's path under the docs directory.
  val prose: Map<String, String> by lazy {
    val root = File(DOCS_DIR)
    root
      .walkTopDown()
      .filter { it.isFile && it.extension == "md" }
      .sortedBy { it.path }
      .associate { page -> page.relativeTo(root).invariantSeparatorsPath to page.readText().replace(FENCED_BLOCK, "") }
  }

  // The inline code in each page's prose.
  val inlineCode: Map<String, List<String>> by lazy {
    prose.mapValues { (_, text) -> INLINE_CODE.findAll(text).map { it.groupValues[1] }.toList() }
  }
}
