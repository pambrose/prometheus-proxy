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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

// The tap's CI runs Homebrew's style check on the formulae make homebrew-formulae writes from etc/homebrew, and it caps
// a line at 118 characters, fewer than this repository's 120, so a formula line that passed here failed only there,
// after the release was published.
class HomebrewFormulaTest : StringSpec() {
  init {
    "every etc/homebrew formula should keep its lines within Homebrew's 118 characters, once make fills it in" {
      val formulae = File(FORMULA_DIR).listFiles { file -> file.extension == "rb" }.orEmpty().sortedBy { it.name }
      formulae.shouldNotBeEmpty()

      val tooLong =
        formulae.flatMap { formula ->
          formula.readLines()
            .map { it.replace("@VERSION@", VERSION).replace("@SHA256@", SHA256) }
            .withIndex()
            .filter { (_, line) -> line.length > MAX_LINE_LENGTH }
            .map { (index, line) -> "${formula.path}:${index + 1} is ${line.length} characters" }
        }
      tooLong.shouldBeEmpty()
    }
  }

  companion object {
    private const val FORMULA_DIR = "etc/homebrew"

    // Homebrew's RuboCop Layout/LineLength maximum.
    private const val MAX_LINE_LENGTH = 118

    // What make homebrew-formulae puts in place of @VERSION@ and @SHA256@: a version at least as long as a release's,
    // and a checksum as long as a SHA-256's 64 hex digits.
    private const val VERSION = "10.10.10"
    private val SHA256 = "0".repeat(64)
  }
}
