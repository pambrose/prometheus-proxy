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
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

// docs/TESTING.md names every spec and support file and says what each one pins. Kept by hand, that list fell behind
// the suite more than once, so a file added without an entry fails here, next to the change that added it.
class TestingGuideTest : StringSpec() {
  // The guide without its fenced code blocks, whose "# ..." shell comments would read as headings.
  private val guide by lazy { File(TESTING_GUIDE).readText().replace(FENCED_BLOCK, "") }

  // The text of each section whose heading names a package directory, such as "### Unit Tests — Agent (`agent/`)",
  // keyed by that directory. A section runs to the next heading of its level or above, so it takes in its ####
  // subsections.
  private val sections by lazy { SECTION.findAll(guide).associate { it.groupValues[1] to it.groupValues[2] } }

  private fun sourceFiles(dir: String) = File(dir).walkTopDown().filter { it.isFile }.toList()

  // The build's subprojects, as settings.gradle.kts includes them, each a directory of its own with its tests under
  // src/test, which can't use the root project's test sources.
  private val subprojects by lazy {
    INCLUDE.findAll(File(SETTINGS).readText()).map { it.groupValues[1] }.toList()
  }

  private fun String.isSpec() = endsWith("Test.kt") || endsWith("Test.java")

  init {
    // Two packages have specs of the same name (agent/ and proxy/ each have an AgentContextTest), so a spec under
    // io/prometheus must be named in its own package's section, where its entry says what it pins. A package with no
    // section fails every spec in it.
    "every spec under src/test/kotlin should be named in its package's section of docs/TESTING.md" {
      val specs = sourceFiles(TEST_SOURCES).filter { it.name.endsWith("Test.kt") }
      specs.size shouldBeGreaterThan 50

      specs
        .filterNot { spec ->
          val relative = spec.relativeTo(File(PACKAGE_ROOT)).invariantSeparatorsPath
          val section = if (relative.startsWith("..")) guide else sections[relative.substringBefore('/')].orEmpty()
          "**${spec.nameWithoutExtension}**" in section
        }.map { it.invariantSeparatorsPath } shouldBe emptyList()
    }

    // The Spring Boot starter's specs are JUnit 5 classes in Java and the Ktor plugin's Kotest specs, each named in its
    // subproject's section, "### Ktor Plugin Tests (`ktor-plugin/`)". A subproject with no section fails every spec in
    // it.
    "every spec of a subproject should be named in its section of docs/TESTING.md" {
      subprojects shouldContainAll listOf("ktor-plugin", "spring-boot-starter")
      val specs = subprojects.flatMap { sub -> sourceFiles("$sub/$TEST_SOURCE_ROOT").filter { it.name.isSpec() } }
      specs.size shouldBeGreaterThan 5

      specs
        .filterNot { spec ->
          val sub = spec.invariantSeparatorsPath.substringBefore('/')
          "**${spec.nameWithoutExtension}**" in sections[sub].orEmpty()
        }.map { it.invariantSeparatorsPath } shouldBe emptyList()
    }

    // A helper is named either as an entry or by its file name in the prose that describes it.
    "every support file under src/test, the subprojects' included, should be named in docs/TESTING.md" {
      val support =
        (listOf(TEST_SOURCE_ROOT) + subprojects.map { "$it/$TEST_SOURCE_ROOT" })
          .flatMap(::sourceFiles)
          .filter { it.extension in setOf("kt", "java") && !it.name.isSpec() }
      support.size shouldBeGreaterThan 10

      support
        .filterNot { "**${it.nameWithoutExtension}**" in guide || it.name in guide }
        .map { it.invariantSeparatorsPath } shouldBe emptyList()
    }

    // The checks above are only as good as the sections they find: a heading that names a subproject, with its hyphens,
    // must be found as one, and a section must stop at the next heading of its level.
    "the guide's sections should be found by their directories, a subproject's among them" {
      sections.keys shouldContainAll listOf("agent", "proxy", "misc", "harness", "ktor-plugin", "spring-boot-starter")
      sections.getValue("ktor-plugin") shouldContain "**AgentLifecycleTest**"
      sections.getValue("ktor-plugin") shouldNotContain "**PrometheusProxyAgentAutoConfigurationTest**"
    }
  }

  companion object {
    private const val TESTING_GUIDE = "docs/TESTING.md"
    private const val TEST_SOURCE_ROOT = "src/test"
    private const val TEST_SOURCES = "src/test/kotlin"
    private const val PACKAGE_ROOT = "src/test/kotlin/io/prometheus"
    private const val SETTINGS = "settings.gradle.kts"
    private val INCLUDE = Regex("""^include\("([^"]+)"\)""", RegexOption.MULTILINE)
    private val FENCED_BLOCK =
      Regex("""^[ \t]*```.*?^[ \t]*```""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
    private val SECTION =
      Regex(
        """^### [^\n]*\(`([a-z-]+)/`\)\n(.*?)(?=^#{2,3} |\z)""",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
      )
  }
}
