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

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File

// Each demo's README and its page on the docs site have a table of the demo's tour, a row for each part with what it
// shows and why that matters, which make up and make tour link to. The parts are the tour's TOUR_PARTS in demo.sh, so a
// part renamed, added, or dropped there fails here until both tables, and the README's Guided Tour, follow. The table
// reads as a table of contents for what follows it, so a page's row links only to a section below the table, and a
// README's to its part's section of its Guided Tour.
class DemoTourTablesTest : StringSpec() {
  private class Row(
    val number: Int,
    val name: String,
    val anchor: String?,
  )

  private class Demo(
    val dir: File,
  ) {
    val name: String get() = dir.name
    val script: String by lazy { File(dir, "demo.sh").readText() }
    val readme: String by lazy { File(dir, "README.md").readText() }
    val page: File get() = File(SITE_DIR, "$name-demo.md")

    // The tour's parts, in order, without the minutes each entry starts with.
    val parts: List<String> by lazy {
      val block = requireNotNull(TOUR_PARTS.find(script)) { "$name/demo.sh has no TOUR_PARTS" }.groupValues[1]
      TOUR_PART.findAll(block).map { it.groupValues[1] }.toList()
    }
  }

  private val demos by lazy {
    File(DEMOS_DIR)
      .listFiles { file -> File(file, "demo.sh").isFile }
      .orEmpty()
      .sortedBy { it.name }
      .map { Demo(it) }
  }

  init {
    "every demo with a demo.sh should have a tour" {
      demos.shouldNotBeEmpty()
      demos.forEach { demo -> withClue(demo.name) { demo.parts.shouldNotBeEmpty() } }
    }

    "each demo's README and website page should list its tour's parts, in order" {
      demos.forEach { demo ->
        listOf("README.md" to demo.readme, demo.page.name to demo.page.readText()).forEach { (doc, text) ->
          withClue("${demo.name}: $doc") {
            val rows = rows(text)
            rows.map { it.number } shouldContainExactly (1..demo.parts.size).toList()
            rows.map { it.name } shouldContainExactly demo.parts
          }
        }
      }
    }

    // The section is the part's: its heading is the part's name, title-cased, so a part renamed in TOUR_PARTS and both
    // tables fails here until its README section follows, as does a row linked to another part's section.
    "each part in a README's table should link to the part's section of its Guided Tour" {
      demos.forEach { demo ->
        rows(demo.readme).forEach { row ->
          withClue("${demo.name}: part ${row.number}") {
            val heading = Regex("""^### ${row.number}\. (.+)$""", RegexOption.MULTILINE).find(demo.readme)
            heading shouldNotBe null
            val title = heading!!.groupValues[1]
            title.lowercase() shouldBe row.name.lowercase()
            row.anchor shouldBe slug("${row.number}. $title")
          }
        }
      }
    }

    "each part in a website page's table should link to a section below the table, if it links" {
      demos.forEach { demo ->
        val text = demo.page.readText()
        val below = anchors(text.substring(text.indexOf(TABLE_HEADING).coerceAtLeast(0)))
        withClue(demo.page.name) {
          rows(text).mapNotNull { it.anchor }.filterNot { it in below }.shouldBeEmpty()
        }
      }
    }

    "each demo's PAGE_URL should be its website page, which make up and make tour link to" {
      demos.forEach { demo ->
        withClue(demo.name) {
          PAGE_URL.find(demo.script)?.groupValues?.get(1) shouldBe "${demo.name}-demo"
          demo.page.isFile.shouldBeTrue()
        }
      }
    }

    // A Markdown file isn't readable without a viewer, so a demo points its reader only to its page on the site: make up
    // and the tour printed the README's path beside it. A comment, for whoever edits the script, may name one.
    "each demo should point to its website page, never to a Markdown file" {
      demos.forEach { demo ->
        withClue(demo.name) {
          demo.script
            .lines()
            .filterNot { it.trimStart().startsWith("#") }
            .filter { MARKDOWN_FILE.containsMatchIn(it) }
            .shouldBeEmpty()
        }
      }
    }

    // demo.sh prints links to sections of its page, ${PAGE_URL}#running-it among them, and a section renamed on the
    // page leaves the link landing at the page's top.
    "every section of its page a demo.sh links to should be one the page has" {
      val links = demos.flatMap { demo -> PAGE_ANCHOR.findAll(demo.script).map { demo to it.groupValues[1] } }
      links.map { (_, anchor) -> anchor } shouldContain "what-this-demo-shows"

      links
        .filterNot { (demo, anchor) -> anchor in anchors(demo.page.readText()) }
        .map { (demo, anchor) -> "${demo.name}/demo.sh: #$anchor" } shouldBe emptyList()
    }
  }

  // The rows of the page's What This Demo Shows table, or none.
  private fun rows(text: String): List<Row> {
    val start = text.indexOf(TABLE_HEADING)
    if (start < 0) return emptyList()
    val end = text.indexOf("\n## ", start + 1).takeIf { it >= 0 } ?: text.length
    return ROW
      .findAll(text.substring(start, end))
      .map { match ->
        val groups = match.groupValues
        Row(
          number = groups[1].toInt(),
          name = groups[2].ifEmpty { groups[4] },
          anchor = groups[3].ifEmpty { null },
        )
      }.toList()
  }

  // The anchors of the headings in [text]: each one's own, as { #name } gives it, or its slug.
  private fun anchors(text: String) =
    HEADING.findAll(text).map { it.groups[2]?.value ?: slug(it.groupValues[1]) }.toSet()

  // A heading's anchor as GitHub and the docs site make one: lowercase, without punctuation, spaces as hyphens.
  private fun slug(heading: String) = heading.trim().lowercase().replace(PUNCTUATION, "").replace(' ', '-')

  companion object {
    private const val DEMOS_DIR = "demos"
    private const val SITE_DIR = "website/prometheus-proxy/docs"
    private const val TABLE_HEADING = "\n## What This Demo Shows\n"
    private val TOUR_PARTS =
      Regex("""^TOUR_PARTS=\(\n(.*?)\n\)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
    private val TOUR_PART = Regex("""^\s+"\d+ (.+)"$""", RegexOption.MULTILINE)
    private val MARKDOWN_FILE = Regex("""\.md\b""")
    private val PAGE_URL = Regex("""^PAGE_URL="https://prometheus-proxy\.com/([a-z-]+)/"$""", RegexOption.MULTILINE)

    // A link to a section of the demo's page: ${PAGE_URL}#running-it, or $PAGE_URL#running-it
    private val PAGE_ANCHOR = Regex("""\$\{?PAGE_URL}?#([A-Za-z0-9_-]+)""")
    private val ROW = Regex("""^\| (\d+)\. (?:\[(.+?)]\(#([^)]+)\)|([^|\[]+?)) \|""", RegexOption.MULTILINE)
    private val PUNCTUATION = Regex("""[^a-z0-9 _-]""")
    private val HEADING = Regex("""^#{2,6} (.+?)(?: \{ #([a-z0-9-]+) })?$""", RegexOption.MULTILINE)
  }
}
