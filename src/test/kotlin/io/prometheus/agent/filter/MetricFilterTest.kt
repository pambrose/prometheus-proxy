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

package io.prometheus.agent.filter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MetricFilterTest : StringSpec() {
  private fun filter(
    allow: List<String> = emptyList(),
    deny: List<String> = emptyList(),
  ) = MetricFilter.createOrNull(allow, deny, "test_path").shouldNotBeNull()

  init {
    "createOrNull should return null when both lists are empty" {
      MetricFilter.createOrNull(emptyList(), emptyList(), "test_path").shouldBeNull()
    }

    "createOrNull should return a filter when only deny is populated" {
      MetricFilter.createOrNull(emptyList(), ["go_.*"], "test_path").shouldNotBeNull()
    }

    "createOrNull should reject an invalid regex naming the path and pattern" {
      val e = shouldThrow<IllegalArgumentException> {
        MetricFilter.createOrNull(emptyList(), ["go_(("], "test_path")
      }
      e.message.shouldNotBeNull() shouldContain "go_(("
      e.message.shouldNotBeNull() shouldContain "test_path"
    }

    "deny should drop a matching sample line" {
      val result = filter(deny = ["go_goroutines"]).filterText("go_goroutines 12\nup 1\n")
      result.text shouldBe "up 1\n"
      result.linesDropped shouldBe 1
    }

    "regexes should be fully anchored" {
      // "go_" must NOT match "go_goroutines" -- anchored semantics, matching Prometheus.
      filter(deny = ["go_"]).filterText("go_goroutines 12\n").text shouldBe "go_goroutines 12\n"
      filter(deny = ["go_.*"]).filterText("go_goroutines 12\n").text shouldBe ""
    }

    "an empty allow list should allow everything" {
      val result = filter(deny = ["nope"]).filterText("up 1\nfoo 2\n")
      result.text shouldBe "up 1\nfoo 2\n"
      result.linesDropped shouldBe 0
    }

    "a populated allow list should drop non-matching lines" {
      val result = filter(allow = ["up"]).filterText("up 1\nfoo 2\n")
      result.text shouldBe "up 1\n"
      result.linesDropped shouldBe 1
    }

    "deny should win over allow on overlap" {
      val result = filter(allow = ["up"], deny = ["up"]).filterText("up 1\n")
      result.text shouldBe ""
      result.linesDropped shouldBe 1
    }

    "label values should not influence name extraction" {
      // The '}' and '#' inside the label value must not confuse the parser.
      val line = """http_requests{path="/a}b#c",method="GET"} 5"""
      filter(deny = ["http_requests"]).filterText("$line\n").text shouldBe ""
      filter(deny = ["nomatch"]).filterText("$line\n").text shouldBe "$line\n"
    }

    "blank lines and unrecognized comments should pass through" {
      val text = "# EOF\n\nup 1\n"
      filter(deny = ["nomatch"]).filterText(text).text shouldBe text
    }

    "an empty payload should be returned unchanged" {
      val result = filter(deny = ["go_.*"]).filterText("")
      result.text shouldBe ""
      result.linesDropped shouldBe 0
    }

    "a payload with no trailing newline should not gain one" {
      filter(deny = ["nomatch"]).filterText("up 1").text shouldBe "up 1"
    }

    "CRLF line endings should be preserved on kept lines" {
      val result = filter(deny = ["go_.*"]).filterText("go_goroutines 12\r\nup 1\r\n")
      result.text shouldBe "up 1\r\n"
    }
  }
}
