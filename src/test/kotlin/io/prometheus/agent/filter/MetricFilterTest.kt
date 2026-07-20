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
import io.kotest.matchers.string.shouldNotContain

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

    "a denied family should drop its HELP and TYPE lines with it" {
      val text =
        """
        # HELP go_goroutines Number of goroutines
        # TYPE go_goroutines gauge
        go_goroutines 12
        up 1
        """.trimIndent() + "\n"
      val result = filter(deny = ["go_.*"]).filterText(text)
      result.text shouldBe "up 1\n"
      result.linesDropped shouldBe 3
    }

    "an allow list should keep a whole histogram family" {
      val text =
        """
        # HELP http_req_duration_seconds Request duration
        # TYPE http_req_duration_seconds histogram
        http_req_duration_seconds_bucket{le="0.1"} 5
        http_req_duration_seconds_bucket{le="+Inf"} 9
        http_req_duration_seconds_sum 3.2
        http_req_duration_seconds_count 9
        go_goroutines 12
        """.trimIndent() + "\n"
      val result = filter(allow = ["http_req_duration_seconds"]).filterText(text)
      result.text shouldContain "http_req_duration_seconds_bucket{le=\"0.1\"} 5"
      result.text shouldContain "http_req_duration_seconds_sum 3.2"
      result.text shouldContain "http_req_duration_seconds_count 9"
      result.text shouldNotContain "go_goroutines"
      result.linesDropped shouldBe 1
    }

    "a denied family should drop every suffixed series" {
      val text =
        """
        # TYPE http_req_duration_seconds histogram
        http_req_duration_seconds_bucket{le="+Inf"} 9
        http_req_duration_seconds_sum 3.2
        http_req_duration_seconds_count 9
        up 1
        """.trimIndent() + "\n"
      filter(deny = ["http_.*"]).filterText(text).text shouldBe "up 1\n"
    }

    "an OpenMetrics counter family should keep its _total and _created series" {
      val text =
        """
        # TYPE requests counter
        requests_total 7
        requests_created 1.6e9
        """.trimIndent() + "\n"
      filter(allow = ["requests"]).filterText(text).text shouldBe text
    }

    "a series outside the open family should close it and be judged literally" {
      val text =
        """
        # TYPE items histogram
        items_sum 3
        items_count 9
        unrelated_metric 1
        """.trimIndent() + "\n"
      // "unrelated_metric" does not belong to family "items", so the deny rule applies to it directly.
      val result = filter(deny = ["unrelated_metric"]).filterText(text)
      result.text shouldContain "items_sum 3"
      result.text shouldNotContain "unrelated_metric"
      result.linesDropped shouldBe 1
    }

    "a sample line with no open family should be judged literally" {
      // No TYPE line, so each line stands alone -- the documented fallback.
      val text = "foo_bucket{le=\"1\"} 2\nfoo_sum 3\n"
      val result = filter(allow = ["foo"]).filterText(text)
      result.text shouldBe ""
      result.linesDropped shouldBe 2
    }

    "a standalone counter named with a family suffix should not be mis-filed" {
      // Without an open "items" family, items_count is judged on its own full name.
      val result = filter(deny = ["items"]).filterText("items_count 42\n")
      result.text shouldBe "items_count 42\n"
      result.linesDropped shouldBe 0
    }
  }
}
