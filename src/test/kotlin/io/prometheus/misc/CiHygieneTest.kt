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
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldMatch
import java.io.File

// What CI runs should change only with a diff in the repo, and a hung job should give its check back (October #38).
class CiHygieneTest : StringSpec() {
  private val workflows by lazy { File(WORKFLOWS_DIR).listFiles { file -> file.extension == "yml" }.orEmpty().toList() }

  // The Makefile's assignments, by name.
  private val makeVars by lazy {
    MAKE_ASSIGNMENT.findAll(File(MAKEFILE).readText()).associate { it.groupValues[1] to it.groupValues[2] }
  }

  init {
    // Without one, a hung harness test would hold the required build check for GitHub's 6-hour default. A job that
    // calls a reusable workflow can't set it; that workflow's own jobs do.
    "every workflow job should set timeout-minutes" {
      workflows.shouldNotBeEmpty()

      workflows
        .flatMap { workflow ->
          val root = Yaml.default.parseToYamlNode(workflow.readText()) as YamlMap
          val jobs = requireNotNull(root.get<YamlMap>("jobs")) { "no jobs in ${workflow.name}" }
          jobs.entries
            .filterValues { job -> !job.has("timeout-minutes") && !job.has("uses") }
            .map { (name, _) -> "${workflow.name}/${name.content}" }
        }.shouldBeEmpty()
    }

    // A tag can be pushed again for another image, and a digest can't. The tag stays alongside it for the reader.
    "every tool image in the Makefile should be pinned by digest" {
      val images = makeVars.filterKeys { it.endsWith("_IMAGE") }
      images.keys shouldContainAll listOf("PROMETHEUS_IMAGE", "HELM_IMAGE", "KUBECONFORM_IMAGE", "ZIZMOR_IMAGE")

      images.forEach { (name, image) -> withClue(name) { image shouldMatch DIGEST_PINNED } }
    }

    // datreeio's main branch moves, and with it the ServiceMonitor schema that helm-lint validates against.
    "the CRD schemas should come from a fixed commit of the catalog" {
      makeVars["CRD_SCHEMAS_COMMIT"].orEmpty() shouldMatch Regex("[0-9a-f]{40}")
      makeVars["CRD_SCHEMAS"].orEmpty() shouldMatch Regex(""".*/CRDs-catalog/\$\(CRD_SCHEMAS_COMMIT\)/.*""")
    }
  }

  private fun YamlNode.has(key: String) = (this as YamlMap).get<YamlNode>(key) != null

  companion object {
    private const val WORKFLOWS_DIR = ".github/workflows"
    private const val MAKEFILE = "Makefile"
    private val MAKE_ASSIGNMENT = Regex("""(?m)^([A-Z][A-Z0-9_]*) :?= (.*)$""")
    private val DIGEST_PINNED = Regex("""\S+:\S+@sha256:[0-9a-f]{64}""")
  }
}
