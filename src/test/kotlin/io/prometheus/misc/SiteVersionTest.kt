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

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldNotBeZero
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

// The docs site gives the version step 2 of docs/RELEASE.md bumps, not the gradle.properties one, which is a -SNAPSHOT
// between releases. docs.yml read gradle.properties to decide whether a push to master deploys, so with a -SNAPSHOT
// there, it deployed nothing until the next release (November #22). make site-version prints what the site gives.
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

    "docs.yml should decide whether to deploy from make site-version, not from gradle.properties" {
      val steps =
        (Yaml.default.parseToYamlNode(File(DOCS_WORKFLOW).readText()) as YamlMap)
          .get<YamlMap>("jobs")
          ?.get<YamlMap>("build")
          ?.get<YamlList>("steps")
          ?.items
          .orEmpty()
          .map { it as YamlMap }
      val check = steps.filter { it.get<YamlScalar>("id")?.content == "published" }
      check shouldHaveSize 1

      val run = check.single().get<YamlScalar>("run")?.content.orEmpty()
      run shouldContain "make -s --no-print-directory site-version"
      run shouldNotContain "gradle.properties"
    }

    // A -SNAPSHOT version never has an image, and the version after it has none until it's released.
    "bin/docker-agent.sh and bin/docker-proxy.sh should run the version the site gives" {
      DOCKER_SCRIPTS
        .filter { path ->
          val code = File(path).readLines().filterNot { it.trimStart().startsWith("#") }
          code.none { "site-version" in it } || code.any { "gradle.properties" in it }
        }.shouldBeEmpty()
    }
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
    private const val DOCS_WORKFLOW = ".github/workflows/docs.yml"
    private const val SNAPSHOT = "0.0.0-SNAPSHOT"
    private const val MAKE_TIMEOUT_SECS = 60L
    private val DOCKER_SCRIPTS = listOf("bin/docker-agent.sh", "bin/docker-proxy.sh")
    private val DOCKER_TAG = Regex("""pambrose/prometheus-proxy:(\S+)""")
    private val RELEASE_VERSION = Regex("""[0-9]+\.[0-9]+\.[0-9]+""")
  }
}
