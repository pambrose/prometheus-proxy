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
import io.kotest.matchers.string.shouldContain
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

  // Every demo's compose file, one per directory under demos/ that has one.
  private val demoComposeFiles by lazy {
    File(DEMOS_DIR)
      .listFiles { file -> file.isDirectory }
      .orEmpty()
      .map { File(it, DEMO_COMPOSE) }
      .filter { it.isFile }
      .sortedBy { it.invariantSeparatorsPath }
  }

  // The Kubernetes demo's manifests and kind cluster configs, which name its third-party images: it runs on kind, not
  // compose.
  private val kubernetesDemoFiles by lazy {
    KUBERNETES_DEMO_IMAGE_DIRS
      .flatMap { dir -> File(dir).listFiles { file -> file.extension in YAML_EXTENSIONS }.orEmpty().toList() }
      .sortedBy { it.invariantSeparatorsPath }
  }

  // The images a compose file, Kubernetes manifest, or kind config pulls: the value of every `image:` key, but the
  // proxy and agent images built from this checkout.
  private fun pulledImages(file: File) =
    YAML_IMAGE
      .findAll(file.readText())
      .map { it.groupValues[1] }
      .filterNot { it.endsWith(":local") }
      .toList()

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

    // Each demo's compose file (demos/<name>/docker/compose.yml) pulls its third-party images, and check-rules
    // validates each demo's Prometheus config with the Makefile's promtool, so a demo runs that Prometheus. Their
    // proxy and agent images are tagged :local, whether demo.sh pulled them from Docker Hub at the latest release or
    // built them from this checkout, so have no digest.
    "every image a demo pulls should be pinned by digest, its Prometheus the Makefile's" {
      val composeFiles = demoComposeFiles.map { it.invariantSeparatorsPath }
      composeFiles shouldContainAll listOf(BASICS_DEMO_COMPOSE, NETWORK_DEMO_COMPOSE)

      demoComposeFiles.forEach { file ->
        withClue(file.invariantSeparatorsPath) {
          val images = pulledImages(file)
          images.shouldNotBeEmpty()
          images.forEach { image -> withClue(image) { image shouldMatch DIGEST_PINNED } }
          images.filter { it.startsWith("prom/prometheus:") }.forEach { it shouldBe makeVars["PROMETHEUS_IMAGE"] }
        }
      }

      // The basics demo runs both, so the walk above has a Prometheus and a Grafana to check.
      pulledImages(File(BASICS_DEMO_COMPOSE)).map { it.substringBefore(":") } shouldContainAll
        listOf("prom/prometheus", "grafana/grafana")
    }

    // The Kubernetes demo (demos/kubernetes) has no compose file: its kind clusters' node image is in its kind configs,
    // and Prometheus and the Python image its services and targets-sync run on are in its manifests. check-rules
    // validates its Prometheus config with the Makefile's promtool, so it runs that Prometheus too. Its proxy and agent
    // images are the charts' own at the latest release, or ones built from this checkout and loaded into the clusters,
    // which demo.sh names on helm's command line, not here.
    "every image the Kubernetes demo runs should be pinned by digest, its Prometheus the Makefile's" {
      val images = kubernetesDemoFiles.flatMap(::pulledImages)
      images.map { it.substringBefore(":") }.distinct() shouldContainAll
        listOf("kindest/node", "prom/prometheus", "python")

      images.forEach { image -> withClue(image) { image shouldMatch DIGEST_PINNED } }
      images.filter { it.startsWith("prom/prometheus:") }.forEach { it shouldBe makeVars["PROMETHEUS_IMAGE"] }
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

    // The live site describes the release its visitors can install, so only make release deploys it, through the
    // workflow_dispatch make gh-docs starts once the release is published. A push to master builds the site and the
    // KDocs, a check make release-check requires, without uploading or deploying them.
    "the docs site should deploy only from the workflow_dispatch make release starts" {
      workflows
        .getValue(DOCS_WORKFLOW)
        .get<YamlMap>("on")
        ?.entries
        ?.keys
        ?.map { it.content }
        .orEmpty() shouldContainAll listOf("push", "workflow_dispatch")

      val docsJobs = jobs.toMap()
      docsJobs.getValue("$DOCS_WORKFLOW/deploy").get<YamlScalar>("if")?.content shouldBe DISPATCH_ONLY
      val uploads =
        docsJobs
          .getValue("$DOCS_WORKFLOW/build")
          .get<YamlList>("steps")
          ?.items
          .orEmpty()
          .map { it as YamlMap }
          .filter { it.get<YamlScalar>("uses")?.content.orEmpty().startsWith(UPLOAD_PAGES) }
      uploads shouldHaveSize 1
      uploads.single().get<YamlScalar>("if")?.content shouldBe DISPATCH_ONLY

      val makefile = File(MAKEFILE).readText()
      makefile shouldContain Regex("""(?m)^gh-docs:.*\n\tgh workflow run $DOCS_WORKFLOW$""")
      makefile shouldContain "$(MAKE) --no-print-directory gh-docs"
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
    private const val DEMOS_DIR = "demos"
    private const val DEMO_COMPOSE = "docker/compose.yml"
    private const val BASICS_DEMO_COMPOSE = "$DEMOS_DIR/basics/$DEMO_COMPOSE"
    private const val NETWORK_DEMO_COMPOSE = "$DEMOS_DIR/network/$DEMO_COMPOSE"
    private val KUBERNETES_DEMO_IMAGE_DIRS = listOf("$DEMOS_DIR/kubernetes/k8s", "$DEMOS_DIR/kubernetes/kind")
    private val YAML_EXTENSIONS = setOf("yaml", "yml")
    private const val SETUP_UV = "astral-sh/setup-uv@"
    private const val DOCS_WORKFLOW = "docs.yml"
    private const val UPLOAD_PAGES = "actions/upload-pages-artifact@"
    private const val DISPATCH_ONLY = "github.event_name == 'workflow_dispatch'"
    private val MAKE_ASSIGNMENT = Regex("""(?m)^([A-Z][A-Z0-9_]*) :?= (.*)$""")

    // A `const val` whose name ends in _IMAGE, with its string on the same line or the next.
    private val CONTAINER_IMAGE = Regex("""const val ([A-Z][A-Z0-9_]*_IMAGE) =\s*"([^"]*)"""")

    // An `image:` key's value: a compose service's, a Kubernetes container's, or a kind node's.
    private val YAML_IMAGE = Regex("""(?m)^\s+image: (\S+)""")
    private val DIGEST_PINNED = Regex("""\S+:\S+@sha256:[0-9a-f]{64}""")
  }
}
