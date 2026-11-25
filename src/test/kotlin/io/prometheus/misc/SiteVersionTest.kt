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
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldNotBeZero
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

// The docs site gives the version step 2 of docs/RELEASE.md bumps, not the gradle.properties one, which is a -SNAPSHOT
// between releases, with no image (November #22). make site-version prints what the site gives, and bin/docker-*.sh
// and the demos run its images.
class SiteVersionTest : StringSpec() {
  private class MakeResult(
    val exitCode: Int,
    val output: String,
    val errors: String,
  )

  // The image tag the Docker page's examples give.
  private val dockerExamplesTag by lazy {
    requireNotNull(DOCKER_TAG.find(File(DOCKER_EXAMPLES).readText())) { "no image tag in $DOCKER_EXAMPLES" }
      .groupValues[1]
  }

  init {
    // VERSION is the gradle.properties version, which mustn't change what it prints.
    "make site-version should print the image tag the website's Docker examples give, a release version" {
      val result = siteVersion("VERSION=$SNAPSHOT")

      result.exitCode shouldBe 0
      result.output shouldBe dockerExamplesTag
      result.output shouldMatch RELEASE_VERSION
    }

    "make site-version should read image tags and Maven coordinates, and fail unless they give one version" {
      val dir = createTempDirectory("site-version").toFile()
      try {
        File(dir, "page.md").writeText(
          """
          docker pull pambrose/prometheus-proxy:1.2.3
          Run pambrose/prometheus-agent:1.2.3.
          docker pull pambrose/prometheus-proxy:latest
          implementation("com.pambrose:prometheus-proxy:1.2.3")
          """.trimIndent(),
        )
        siteVersion("SITE_VERSION_SOURCES=${dir.path}").apply {
          exitCode shouldBe 0
          output shouldBe "1.2.3"
        }

        // The Gradle coordinates of the Spring Boot starter and the Ktor plugin, released at the agent's version, are
        // ones a bump can miss too
        listOf("prometheus-proxy-spring-boot-starter", "prometheus-proxy-ktor").forEach { artifact ->
          File(dir, "$artifact.txt").writeText("""implementation("com.pambrose:$artifact:1.2.4")""")
          siteVersion("SITE_VERSION_SOURCES=${dir.path}").apply {
            exitCode.shouldNotBeZero()
            errors shouldContain "1.2.4"
          }
          File(dir, "$artifact.txt").delete()
        }

        File(dir, "other.txt").writeText("image: pambrose/prometheus-agent:1.2.4\n")
        siteVersion("SITE_VERSION_SOURCES=${dir.path}").apply {
          exitCode.shouldNotBeZero()
          output shouldBe ""
          errors shouldContain "1.2.3"
          errors shouldContain "1.2.4"
        }

        dir.listFiles().orEmpty().forEach { it.delete() }
        siteVersion("SITE_VERSION_SOURCES=${dir.path}").apply {
          exitCode.shouldNotBeZero()
          output shouldBe ""
        }
      } finally {
        dir.deleteRecursively()
      }
    }

    // A -SNAPSHOT version never has an image, and the version after it has none until it's released.
    "bin/docker-agent.sh and bin/docker-proxy.sh should run the version the site gives" {
      DOCKER_SCRIPTS
        .filter { path ->
          val code = File(path).readLines().filterNot { it.trimStart().startsWith("#") }
          code.none { "site-version" in it } || code.any { "gradle.properties" in it }
        }.shouldBeEmpty()
    }

    // From a version bump until its release is out, the site gives a version Docker Hub has no image of (November 23
    // #25). The scripts run with a docker on the PATH that answers as Docker Hub would.
    "bin/docker-agent.sh and bin/docker-proxy.sh should say when Docker Hub has no image of the site's version" {
      val version = siteVersion().output
      val dir = createTempDirectory("docker-stub").toFile()
      try {
        File(dir, "docker").apply {
          writeText(DOCKER_STUB)
          setExecutable(true).shouldBeTrue()
        }
        DOCKER_SCRIPTS.forEach { script ->
          val image = "pambrose/prometheus-" + script.removePrefix("bin/docker-").removeSuffix(".sh")
          runScript(script, dir, hub = "missing").apply {
            exitCode.shouldNotBeZero()
            errors shouldContain "$image:$version isn't on Docker Hub yet"
            output shouldBe ""
          }
          runScript(script, dir, hub = "published").apply {
            exitCode shouldBe 0
            output shouldContain "docker run"
            output shouldContain "$image:$version"
          }
        }
      } finally {
        dir.deleteRecursively()
      }
    }

    // zensical.toml's snippets base_path gives the site files outside the directories site-version reads, such as
    // examples/cloud-proxy/docker-compose.yml, which the Cloud Platforms page shows with an image tag (November 23
    // #30).
    "every file a website page includes should be one site-version reads, or name no version" {
      val sources = makeVariable("SITE_VERSION_SOURCES").split(Regex("""\s+""")).map { File(it).canonicalFile }
      val included = includedSnippets()
      included.shouldNotBeEmpty()
      included
        .filterNot { file -> sources.any { file.canonicalFile.startsWith(it) } }
        .filter { file -> SITE_VERSION.containsMatchIn(file.readText()) }
        .map { it.path }
        .shouldBeEmpty()
    }
  }

  // Every file a page includes with --8<--, resolved as zensical's snippets extension resolves it: against each base
  // path in turn, from the site's directory.
  private fun includedSnippets(): List<File> {
    val toml = File(SITE_DIR, "zensical.toml").readText()
    val bases =
      requireNotNull(BASE_PATHS.find(toml)) { "no snippets base_path in zensical.toml" }
        .groupValues[1]
        .split(',')
        .map { File(SITE_DIR, it.trim().removeSurrounding("\"")) }
    val names =
      File(SITE_DIR, "docs")
        .walkTopDown()
        .filter { it.extension == "md" }
        .flatMap { page -> SNIPPET.findAll(page.readText()).map { it.groupValues[1].substringBefore(':') } }
        .toSortedSet()
    return names.map { name ->
      requireNotNull(bases.map { File(it, name) }.firstOrNull { it.isFile }) { "can't find the snippet $name" }
    }
  }

  // Runs a bin/docker-*.sh script with the stub docker in dir first on the PATH.
  private fun runScript(
    script: String,
    dir: File,
    hub: String,
  ): MakeResult {
    val process =
      ProcessBuilder("bash", script)
        .apply {
          environment().keys.removeAll(listOf("MAKEFLAGS", "MAKELEVEL", "MFLAGS"))
          environment()["PATH"] = "${dir.path}${File.pathSeparator}${System.getenv("PATH")}"
          environment()["STUB_HUB"] = hub
        }.start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val errors = process.errorStream.bufferedReader().use { it.readText() }
    process.waitFor(MAKE_TIMEOUT_SECS, TimeUnit.SECONDS).shouldBeTrue()
    return MakeResult(process.exitValue(), output.trim(), errors)
  }

  // A Makefile variable's value, as make expands it.
  private fun makeVariable(name: String): String {
    val process =
      ProcessBuilder("make", "-s", "--no-print-directory", "-p", "-n", "_require-version")
        .apply { environment().keys.removeAll(listOf("MAKEFLAGS", "MAKELEVEL", "MFLAGS")) }
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor(MAKE_TIMEOUT_SECS, TimeUnit.SECONDS).shouldBeTrue()
    return requireNotNull(Regex("""(?m)^$name := (.*)$""").find(output)) { "no $name in the Makefile" }
      .groupValues[1]
      .trim()
  }

  // Runs make site-version from the project directory, with a parent make's flags left out, since the build can run
  // under one (make ci-build).
  private fun siteVersion(vararg args: String): MakeResult {
    val process =
      ProcessBuilder(listOf("make", "-s", "--no-print-directory", "site-version") + args)
        .apply { environment().keys.removeAll(listOf("MAKEFLAGS", "MAKELEVEL", "MFLAGS")) }
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val errors = process.errorStream.bufferedReader().use { it.readText() }
    process.waitFor(MAKE_TIMEOUT_SECS, TimeUnit.SECONDS).shouldBeTrue()
    return MakeResult(process.exitValue(), output.trim(), errors)
  }

  companion object {
    private const val DOCKER_EXAMPLES = "src/test/kotlin/website/DockerExamples.txt"
    private const val SNAPSHOT = "0.0.0-SNAPSHOT"
    private const val MAKE_TIMEOUT_SECS = 60L
    private val DOCKER_SCRIPTS = listOf("bin/docker-agent.sh", "bin/docker-proxy.sh")
    private val DOCKER_TAG = Regex("""pambrose/prometheus-proxy:(\S+)""")
    private val RELEASE_VERSION = Regex("""[0-9]+\.[0-9]+\.[0-9]+""")
    private const val SITE_DIR = "website/prometheus-proxy"
    private val BASE_PATHS = Regex("""(?m)^base_path = \[(.*)]$""")
    private val SNIPPET = Regex("""--8<-- "([^"]+)"""")

    // What site-version reads as a version: an image tag or a Maven coordinate of one of the published artifacts.
    private val SITE_VERSION =
      Regex("""(pambrose/prometheus-(proxy|agent)|com\.pambrose:prometheus-proxy(-spring-boot-starter|-ktor)?):[0-9]""")

    // A docker that answers image inspect as one with no images, manifest inspect as Docker Hub would, with or
    // without the image (STUB_HUB), and run by printing the command.
    private val DOCKER_STUB =
      """
      #!/bin/sh
      case "${'$'}1 ${'$'}2" in
      "image inspect") exit 1 ;;
      "manifest inspect")
        [ "${'$'}STUB_HUB" = published ] && exit 0
        echo "no such manifest: docker.io/${'$'}3" >&2
        exit 1
        ;;
      esac
      echo "docker ${'$'}*"
      """.trimIndent() + "\n"
  }
}
