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

package io.prometheus.common

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.AgentValues.isValidLabelName
import io.prometheus.common.AgentValues.labelsProblem
import io.prometheus.common.AgentValues.pathCharacterProblem
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.common.AgentValues.truncate

class AgentValuesTest : StringSpec() {
  init {
    "truncate should cut a longer value, never inside a surrogate pair, and leave the rest whole" {
      truncate("abcdef", 4) shouldBe "abcd"
      truncate("abc😀def", 4) shouldBe "abc"
      truncate("abc", 4) shouldBe "abc"
      truncate("abcdef", 0) shouldBe "abcdef"
    }

    // November #16: an agent-supplied value logged with its CR/LF intact let the agent forge whole log lines.
    "shownValue should escape every control character and line separator" {
      shownValue("a\r\nb\tc\u0000d\u007fe\u0085f\u2028g\u2029h") shouldBe
        "a\\r\\nb\\tc\\u0000d\\u007fe\\u0085f\\u2028g\\u2029h"
      shownValue("team_a.metrics-1~:@!") shouldBe "team_a.metrics-1~:@!"
      shownValue("métriques") shouldBe "métriques"
    }

    "shownValue should cut a long value to its first characters, marked, before it escapes them" {
      shownValue("x".repeat(100)) shouldBe "x".repeat(64) + "..."
      shownValue("\n".repeat(100), 4) shouldBe "\\n\\n\\n\\n..."
      shownValue("abc😀def", 4) shouldBe "abc..."
      shownValue("x".repeat(100), 0) shouldBe "x".repeat(100)
    }

    // A path with one of these could never be scraped: the scrape port's request line ends the request target at
    // whitespace, the target can't carry a control character, '?' starts its query, and a client never sends '#'.
    "pathCharacterProblem should name a character no scrape's request path can carry" {
      for (c in ['\n', '\r', '\t', ' ', '\u0000', '\u0001', '\u007f', '\u0085', '\u00a0', '\u2028', '?', '#']) {
        withClue(c.code) { pathCharacterProblem("app${c}metrics").shouldNotBeNull() }
      }
      pathCharacterProblem("app\nmetrics").shouldNotBeNull() shouldContain "'\\n'"
    }

    // Every other character is left to the scrape route, as before: RFC 3986's unreserved and sub-delims, and also
    // percent-encoded or non-ASCII paths, which a client that sends the path as registered can still scrape.
    "pathCharacterProblem should accept the characters a path could be scraped with before" {
      val paths = ["app1_metrics", "team-a.metrics~1", "a!b\$c&d'e(f)g*h+i,j;k=l:m@n", "m%C3%A9triques", "métriques"]
      for (path in paths) {
        withClue(path) { pathCharacterProblem(path).shouldBeNull() }
      }
    }

    "isValidLabelName should accept only the names every Prometheus version decodes" {
      isValidLabelName("team_a1").shouldBeTrue()
      isValidLabelName("_team").shouldBeTrue()
      for (name in ["", "1team", "a-b", "a.b", "é"]) {
        withClue(name) { isValidLabelName(name).shouldBeFalse() }
      }
    }

    "labelsProblem should accept blank labels and an object of string labels" {
      labelsProblem("").shouldBeNull()
      labelsProblem("""{"team":"web","env":"prod"}""").shouldBeNull()
    }

    "labelsProblem should name labels that aren't an object, a bad name, and a value that isn't a string" {
      labelsProblem("""{"a":""").shouldNotBeNull() shouldContain "JSON object"
      labelsProblem("""["a"]""").shouldNotBeNull() shouldContain "JSON object"
      labelsProblem("""{"a-b":"c"}""").shouldNotBeNull() shouldContain "'a-b' isn't a valid Prometheus label name"
      labelsProblem("""{"count":1}""").shouldNotBeNull() shouldContain "'count' must have a string value"
    }

    // November #16: the proxy logs this as a path registers, so a name an agent sends is shown cut and escaped.
    "labelsProblem should show a bad label name with its control characters escaped" {
      val problem = labelsProblem("""{"a\r\nb":"c"}""").shouldNotBeNull()

      problem shouldContain "'a\\r\\nb'"
      problem.none { it == '\r' || it == '\n' }.shouldBeTrue()
    }
  }
}
