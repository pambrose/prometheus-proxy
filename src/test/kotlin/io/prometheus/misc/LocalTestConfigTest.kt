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
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

// A spec reads its config from the checkout. A fallback that fetched a missing file from GitHub master hid a renamed or
// deleted config and quietly tested whatever master held: September #31 took it out of HarnessConstants, and
// AgentPathAuthTest kept a copy of its own until November #34.
class LocalTestConfigTest : StringSpec() {
  init {
    "no source under src/test/kotlin/io should name raw.githubusercontent.com" {
      val sources =
        File(TEST_SOURCES)
          .walkTopDown()
          .filter { it.isFile && it.invariantSeparatorsPath !in EXEMPT }
          .toList()
      sources.size shouldBeGreaterThan 50

      sources
        .flatMap { source ->
          source
            .readLines()
            .withIndex()
            .filter { (_, line) -> REMOTE_HOST in line }
            .map { (index, _) -> "${source.invariantSeparatorsPath}:${index + 1}" }
        } shouldBe emptyList()
    }
  }

  companion object {
    private const val TEST_SOURCES = "src/test/kotlin/io"
    private const val REMOTE_HOST = "raw.githubusercontent"

    // This spec names the host it looks for. The website's snippet sources, which give README-style commands with
    // GitHub URLs, are under src/test/kotlin/website, outside the directory checked.
    private val EXEMPT = setOf("$TEST_SOURCES/prometheus/misc/LocalTestConfigTest.kt")
  }
}
