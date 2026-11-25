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
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldNotBeZero
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

// The parts of make release-check that read the checkout, run as make runs them (November 23 #25, #61, #62): the
// bumped files' stale-version check, the changelogs' release date, and the demos' NEEDS_RELEASE_AFTER; and the notes
// make github-release gives the GitHub release.
class ReleaseCheckTest : StringSpec() {
  private class MakeResult(
    val exitCode: Int,
    val output: String,
    val errors: String,
  )

  // A release whose sections both changelogs date the same day: the newest one below the top, which is released.
  private class Release(
    val version: String,
    val date: String,
    val previous: String,
  )

  // The version the bumped files give, which the docs site gives too: gradle.properties can hold a -SNAPSHOT.
  private val siteVersion by lazy { make("site-version").also { it.exitCode shouldBe 0 }.output }

  private val releaseVersionFiles by lazy { makeVariable("RELEASE_VERSION_FILES").split(Regex("""\s+""")) }

  private val released by lazy {
    val sections = CHANGELOG_SECTION.findAll(File(CHANGELOG).readText()).map { it.groupValues }.toList()
    val notes = File(RELEASE_NOTES).readText()
    val index =
      (1 until sections.size - 1).first { i ->
        val (_, version, date) = sections[i]
        "## $version\n\n_Released ${date}_" in notes
      }
    Release(version = sections[index][1], date = sections[index][2], previous = sections[index + 1][1])
  }

  init {
    // Given the version as the previous one, the check pretends a bump changed nothing, so it has to name every line
    // a bump changes. A mention it misses is one a bump can skip and still pass.
    "the stale-version check should name every line of the bumped files that gives the version, but its history" {
      val version = siteVersion
      val result = make("_check-version-files", "VERSION=$version", "PREVIOUS_RELEASE=$version")
      result.exitCode.shouldNotBeZero()

      val named = STALE_LINE.findAll(result.errors).map { it.groupValues[1] }.toSet()
      named.shouldNotBeEmpty()
      val token = Regex("""(^|[^0-9.])${Regex.escape(version)}([^0-9]|$)""")
      val history = historyPatterns(version)
      val missed =
        releaseVersionFiles.flatMap { path ->
          File(path)
            .readLines()
            .withIndex()
            .filter { (_, line) -> token.containsMatchIn(line) && history.none { it.containsMatchIn(line) } }
            .map { (i, line) -> "$path:${i + 1}" to line }
        }.filterNot { (location, _) -> location in named }
          .map { (location, line) -> "$location: $line" }
      withClue("lines a bump could leave at the previous release and pass release-check") { missed.shouldBeEmpty() }
    }

    "the stale-version check should name the Quick Start's JAR release page and download links" {
      val version = siteVersion
      val result = make("_check-version-files", "VERSION=$version", "PREVIOUS_RELEASE=$version")
      val jarLines =
        File(GETTING_STARTED)
          .readLines()
          .withIndex()
          .filter { (_, line) -> "/releases/tag/$version" in line || "/releases/download/$version/" in line }
          .map { (i, _) -> "$GETTING_STARTED:${i + 1}" }
      jarLines.size shouldBe 3
      STALE_LINE.findAll(result.errors).map { it.groupValues[1] }.toList() shouldContainAll jarLines
    }

    "the stale-version check should pass a tree bumped to the version" {
      make("_check-version-files", "VERSION=$siteVersion").exitCode shouldBe 0
    }

    "the changelog check should pass only on the day both changelogs give the release" {
      val release = released
      make("_check-changelogs", "VERSION=${release.version}", "RELEASE_DATE=${release.date}").exitCode shouldBe 0

      make("_check-changelogs", "VERSION=${release.version}", "RELEASE_DATE=2000-01-01").apply {
        exitCode.shouldNotBeZero()
        errors shouldContain "CHANGELOG.md dates ${release.version} ${release.date}, not 2000-01-01"
      }
      make("_check-changelogs", "VERSION=0.0.1", "RELEASE_DATE=${release.date}").apply {
        exitCode.shouldNotBeZero()
        errors shouldContain "no dated '## [0.0.1] - YYYY-MM-DD' section"
      }
    }

    "the changelog check should fail when the release notes give another day than the changelog" {
      val release = released
      withCopies(CHANGELOG, RELEASE_NOTES) { dir ->
        val notes = File(dir, RELEASE_NOTES)
        notes.writeText(notes.readText().replace("_Released ${release.date}_", "_Released 2000-01-01_"))
        make("_check-changelogs", "VERSION=${release.version}", "RELEASE_DATE=${release.date}", dir = dir).apply {
          exitCode.shouldNotBeZero()
          errors shouldContain "RELEASE_NOTES.md dates ${release.version} 2000-01-01, not ${release.date}"
        }
      }
    }

    "the demos check should refuse a release at or below a demo's NEEDS_RELEASE_AFTER" {
      val values =
        demoScripts().associate { script ->
          val value = NEEDS_RELEASE_AFTER.find(script.readText())
          requireNotNull(value) { "no NEEDS_RELEASE_AFTER in $script" }
          script.path to value.groupValues[1]
        }
      values.keys.shouldNotBeEmpty()
      val highest = values.values.maxWith(compareBy { version(it) })

      make("_check-demos-pull-release", "VERSION=99.0.0").exitCode shouldBe 0
      make("_check-demos-pull-release", "VERSION=$highest").apply {
        exitCode.shouldNotBeZero()
        values.filterValues { it == highest }.keys.forEach { script ->
          errors shouldContain "$script: NEEDS_RELEASE_AFTER=$highest"
        }
      }
    }

    // A value it can't read once passed unchecked.
    "the demos check should refuse a demo whose NEEDS_RELEASE_AFTER it can't read" {
      val scripts = demoScripts().map { it.path }
      listOf("readonly NEEDS_RELEASE_AFTER=4.6.0", "NEEDS_RELEASE_AFTER=next", "").forEach { assignment ->
        withCopies(*scripts.toTypedArray()) { dir ->
          val edited = File(dir, scripts.first())
          edited.writeText(edited.readText().replace(NEEDS_RELEASE_AFTER, assignment))
          make("_check-demos-pull-release", "VERSION=99.0.0", dir = dir).apply {
            withClue(assignment) {
              exitCode.shouldNotBeZero()
              errors shouldContain "${scripts.first()}: no NEEDS_RELEASE_AFTER=x.y.z line"
            }
          }
        }
      }
    }

    // GitHub shows a release's line breaks as written, so a paragraph or list item wrapped over lines would show
    // broken there.
    "the GitHub release notes should be the release notes' section, unwrapped, then the full changelog's link" {
      val release = released
      val result = make("github-release-notes", "VERSION=${release.version}")
      result.exitCode shouldBe 0

      val lines = result.output.lines()
      val section = releaseNotesSection(release.version)
      lines.filter { it.startsWith("- ") }.size shouldBe section.count { it.startsWith("- ") }
      lines.filter { it.startsWith("## ") } shouldBe section.filter { it.startsWith("### ") }.map { it.drop(1) }
      lines.filter { it.startsWith(" ") && !it.startsWith("  - ") }.shouldBeEmpty()
      result.output shouldNotContain "_Released"
      lines.last() shouldBe
        "**Full Changelog**: https://github.com/pambrose/prometheus-proxy/compare/${release.previous}...${release.version}"
    }
  }

  // The mentions of a version step 2 of docs/RELEASE.md leaves alone: llms.txt's per-feature release annotations, and
  // the README's New Features summary, which each release rewrites.
  private fun historyPatterns(version: String): List<Regex> {
    val v = Regex.escape(version)
    return listOf(Regex("""Since $v\b"""), Regex("""\($v\)"""), Regex("""Version $v adds"""))
  }

  // RELEASE_NOTES.md's lines for a version, from its heading to the next release's.
  private fun releaseNotesSection(version: String): List<String> =
    File(RELEASE_NOTES)
      .readLines()
      .dropWhile { it != "## $version" }
      .drop(1)
      .takeWhile { !it.startsWith("## ") && it != "---" }

  private fun demoScripts(): List<File> =
    File(DEMOS_DIR)
      .listFiles { file -> File(file, "demo.sh").isFile }
      .orEmpty()
      .sortedBy { it.name }
      .map { File(DEMOS_DIR, "${it.name}/demo.sh") }

  private fun version(text: String) = text.split('.').fold(0L) { acc, part -> acc * VERSION_RADIX + part.toLong() }

  // Copies the files into a temporary directory, as the repository has them, and runs the block with it.
  private fun withCopies(
    vararg paths: String,
    block: (File) -> Unit,
  ) {
    val dir = createTempDirectory("release-check").toFile()
    try {
      (paths.toList() + GRADLE_PROPERTIES).forEach { path -> File(path).copyTo(File(dir, path)) }
      block(dir)
    } finally {
      dir.deleteRecursively()
    }
  }

  // A Makefile variable's value, as make expands it.
  private fun makeVariable(name: String): String {
    val result = make("-p", "-n", "_require-version")
    return requireNotNull(Regex("""(?m)^$name := (.*)$""").find(result.output)) { "no $name in the Makefile" }
      .groupValues[1]
      .trim()
  }

  // Runs the project's Makefile from dir, with a parent make's flags left out, since the build can run under one (make
  // ci-build).
  private fun make(
    vararg args: String,
    dir: File? = null,
  ): MakeResult {
    val makefile = File(MAKEFILE).absolutePath
    val process =
      ProcessBuilder(listOf("make", "-s", "--no-print-directory", "-f", makefile) + args)
        .apply {
          dir?.let { directory(it) }
          environment().keys.removeAll(listOf("MAKEFLAGS", "MAKELEVEL", "MFLAGS"))
        }.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val errors = process.errorStream.bufferedReader().use { it.readText() }
    process.waitFor(MAKE_TIMEOUT_SECS, TimeUnit.SECONDS).shouldBeTrue()
    return MakeResult(process.exitValue(), output.trim(), errors)
  }

  companion object {
    private const val MAKEFILE = "Makefile"
    private const val CHANGELOG = "CHANGELOG.md"
    private const val RELEASE_NOTES = "RELEASE_NOTES.md"
    private const val GRADLE_PROPERTIES = "gradle.properties"
    private const val GETTING_STARTED = "website/prometheus-proxy/docs/getting-started.md"
    private const val DEMOS_DIR = "demos"
    private const val MAKE_TIMEOUT_SECS = 60L
    private const val VERSION_RADIX = 1000L
    private val CHANGELOG_SECTION = Regex("""(?m)^## \[([0-9]+\.[0-9]+\.[0-9]+)] - ([0-9]{4}-[0-9]{2}-[0-9]{2})$""")
    private val STALE_LINE = Regex("""(?m)^ {2}([^:\s]+:[0-9]+):""")
    private val NEEDS_RELEASE_AFTER = Regex("""(?m)^NEEDS_RELEASE_AFTER=([0-9.]+)$""")
  }
}
