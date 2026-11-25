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

package io.prometheus.misc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

// The code blocks in the docs that readers copy or that describe a drawing, checked for what reading them alone misses.
class DocsCodeBlocksTest : StringSpec() {
  init {
    // It's off by default, so the path the block registers would get the agent's 503 (November 23 review, #35).
    "every HOCON block in the docs that scrapes the agent's metrics port should turn the port on" {
      val scraping = blocks("hocon").filter { (_, block) -> SCRAPES_OWN_PORT.containsMatchIn(block) }

      scraping.shouldNotBeEmpty()
      scraping.filterNot { (_, block) -> TURNS_PORT_ON.containsMatchIn(block) }.map { it.first } shouldBe emptyList()
    }

    // A diagram's description is the text a screen reader gives for it, and a line break in it is a space, so a name
    // split at its hyphen reads as two words, "agent- west-2" (November 23 review, #65).
    "no line of a diagram's description should end with a word broken at its hyphen" {
      val descriptions =
        blocks("mermaid").flatMap { (file, block) -> ACC_DESCR.findAll(block).map { file to it.groupValues[1] } }

      descriptions.shouldNotBeEmpty()
      descriptions
        .flatMap { (file, description) -> description.lines().filter { BROKEN_WORD.containsMatchIn(it) }.map { file } }
        .shouldBe(emptyList())
    }
  }

  companion object {
    private val SCRAPES_OWN_PORT = Regex("""localhost:8083/metrics""")
    private val TURNS_PORT_ON = Regex("""metrics\.enabled\s*[=:]\s*true|metrics\s*\{[^}]*\benabled\s*[=:]\s*true""")
    private val ACC_DESCR = Regex("""accDescr\s*\{(.*?)}""", RegexOption.DOT_MATCHES_ALL)
    private val BROKEN_WORD = Regex("""\p{L}-\s*$""")

    // The Markdown readers follow: the docs site's pages, the maintainer docs, and the READMEs. Not the code reviews,
    // which quote what they find.
    private fun markdown(): List<File> =
      (
        File("website/prometheus-proxy/docs").walkTopDown().toList() +
          File("docs").listFiles().orEmpty() +
          File("demos").walkTopDown().maxDepth(2).toList() +
          File("README.md")
      ).filter { it.isFile && it.extension == "md" && !it.name.startsWith("CODE_REVIEW_") }

    // Each fenced block of [language] in those files, with the file it's in. The docs site's pages put a space after
    // the fence, as in ``` mermaid, and the READMEs don't.
    private fun blocks(language: String): List<Pair<String, String>> {
      val fenced =
        Regex(
          """^[ \t]*```[ \t]*$language\b[^\n]*\n(.*?)^[ \t]*```""",
          setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        )
      return markdown().flatMap { file -> fenced.findAll(file.readText()).map { file.path to it.groupValues[1] } }
    }
  }
}
