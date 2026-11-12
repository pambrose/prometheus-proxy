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
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlScalar
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.prometheus.containers.support.ContainerTestSupport
import org.testcontainers.utility.DockerImageName
import java.io.File

// What CI runs should change only with a diff in the repo, and a hung job should give its check back (October #38).
class CiHygieneTest : StringSpec() {
  // Each workflow's parsed YAML, by file name.
  private val workflows by lazy {
    File(WORKFLOWS_DIR)
      .listFiles { file -> file.extension == "yml" }
      .orEmpty()
      .sortedBy { it.name }
      .associate { it.name to Yaml.default.parseToYamlNode(it.readText()) as YamlMap }
  }

  // Each workflow's jobs, keyed by workflow-file/job-name.
  private val jobs by lazy {
    workflows.flatMap { (file, root) ->
      val jobs = requireNotNull(root.get<YamlMap>("jobs")) { "no jobs in $file" }
      jobs.entries.map { (name, job) -> "$file/${name.content}" to job as YamlMap }
    }
  }

  // The Makefile's assignments, by name.
  private val makeVars by lazy {
    MAKE_ASSIGNMENT.findAll(File(MAKEFILE).readText()).associate { it.groupValues[1] to it.groupValues[2] }
  }

  init {
    // Without one, a hung harness test would hold the required build check for GitHub's 6-hour default. A job that
    // calls a reusable workflow can't set it; that workflow's own jobs do.
    "every workflow job should set timeout-minutes" {
      workflows.shouldNotBeEmpty()

      jobs
        .filter { (_, job) -> !job.has("timeout-minutes") && !job.has("uses") }
        .map { (name, _) -> name }
        .shouldBeEmpty()
    }

    // A tag can be pushed again for another image, and a digest can't. The tag stays alongside it for the reader.
    "every tool image in the Makefile should be pinned by digest" {
      val images = makeVars.filterKeys { it.endsWith("_IMAGE") }
      images.keys shouldContainAll listOf("PROMETHEUS_IMAGE", "HELM_IMAGE", "KUBECONFORM_IMAGE", "ZIZMOR_IMAGE")

      images.forEach { (name, image) -> withClue(name) { image shouldMatch DIGEST_PINNED } }
    }

    // container-tests is a required check, and an nginx tag moves with every nginx and Alpine rebuild (November #37).
    // The suite's own proxy and agent images (the *_TEST_IMAGE ones) are built from this checkout, so have no digest.
    // Testcontainers inspects and runs an image by the whole name it's given, and pulls the digest.
    "every third-party image the container suite runs should be pinned by digest" {
      val images =
        CONTAINER_IMAGE
          .findAll(File(CONTAINER_TEST_SUPPORT).readText())
          .associate { it.groupValues[1] to it.groupValues[2] }
          .filterKeys { !it.endsWith("_TEST_IMAGE") }
      images.keys shouldContainAll listOf("NGINX_IMAGE", "PROMETHEUS_IMAGE")

      images.forEach { (name, image) ->
        withClue(name) {
          image shouldMatch DIGEST_PINNED
          val parsed = DockerImageName.parse(image)
          parsed.versionPart shouldBe image.substringAfter("@")
          parsed.asCanonicalNameString() shouldBe image
        }
      }
    }

    // check-rules' promtool and the Prometheus the container suite scrapes through are meant to be one release.
    "the container suite should run the Prometheus image the Makefile pins" {
      ContainerTestSupport.PROMETHEUS_IMAGE shouldBe makeVars["PROMETHEUS_IMAGE"]
    }

    // Without a version, setup-uv installs the newest uv, so what builds and deploys the docs site changed with each uv
    // release and no diff here (November #25). And docs-site checks the build docs.yml deploys only if both install the
    // same one.
    "every setup-uv step should pin uv's version, and all of them the same one" {
      val setupUv =
        jobs.flatMap { (name, job) ->
          job
            .get<YamlList>("steps")
            ?.items
            .orEmpty()
            .filter { step -> (step as YamlMap).get<YamlScalar>("uses")?.content.orEmpty().startsWith(SETUP_UV) }
            .map { step -> name to step }
        }
      setupUv.shouldNotBeEmpty()

      setupUv
        .filter { (_, step) -> step.input("version") == null && step.input("version-file") == null }
        .map { (name, _) -> name }
        .shouldBeEmpty()
      setupUv.map { (_, step) -> step.input("version") ?: step.input("version-file") }.distinct() shouldHaveSize 1
    }

    // datreeio's main branch moves, and with it the ServiceMonitor schema that helm-lint validates against.
    "the CRD schemas should come from a fixed commit of the catalog" {
      makeVars["CRD_SCHEMAS_COMMIT"].orEmpty() shouldMatch Regex("[0-9a-f]{40}")
      makeVars["CRD_SCHEMAS"].orEmpty() shouldMatch Regex(""".*/CRDs-catalog/\$\(CRD_SCHEMAS_COMMIT\)/.*""")
    }
  }

  private fun YamlNode.has(key: String) = (this as YamlMap).get<YamlNode>(key) != null

  // The value a step gives one of its action's inputs, if it gives one.
  private fun YamlNode.input(key: String) = (this as YamlMap).get<YamlMap>("with")?.get<YamlScalar>(key)?.content

  companion object {
    private const val WORKFLOWS_DIR = ".github/workflows"
    private const val MAKEFILE = "Makefile"
    private const val CONTAINER_TEST_SUPPORT =
      "src/test/kotlin/io/prometheus/containers/support/ContainerTestSupport.kt"
    private const val SETUP_UV = "astral-sh/setup-uv@"
    private val MAKE_ASSIGNMENT = Regex("""(?m)^([A-Z][A-Z0-9_]*) :?= (.*)$""")

    // A `const val` whose name ends in _IMAGE, with its string on the same line or the next.
    private val CONTAINER_IMAGE = Regex("""const val ([A-Z][A-Z0-9_]*_IMAGE) =\s*"([^"]*)"""")
    private val DIGEST_PINNED = Regex("""\S+:\S+@sha256:[0-9a-f]{64}""")
  }
}
