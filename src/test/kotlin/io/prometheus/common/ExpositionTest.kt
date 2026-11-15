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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ExpositionTest : StringSpec() {
  init {
    // ==================== isTextContentType ====================

    // A target that sends no content type is read as text, as the agent's metric filter has always read it.
    "text/plain, OpenMetrics, and a blank content type should be text, whatever their parameters and case" {
      Exposition.isTextContentType("").shouldBeTrue()
      Exposition.isTextContentType("  ").shouldBeTrue()
      Exposition.isTextContentType("text/plain").shouldBeTrue()
      Exposition.isTextContentType("text/plain; version=0.0.4; charset=utf-8").shouldBeTrue()
      Exposition.isTextContentType("TEXT/PLAIN").shouldBeTrue()
      Exposition.isTextContentType(" text/plain ;charset=utf-8").shouldBeTrue()
      Exposition.isTextContentType("application/openmetrics-text; version=1.0.0; charset=utf-8").shouldBeTrue()
    }

    "protobuf, an HTML page, and other content types should not be text" {
      Exposition.isTextContentType("application/vnd.google.protobuf").shouldBeFalse()
      Exposition.isTextContentType(
        "application/vnd.google.protobuf; proto=io.prometheus.client.MetricFamily; encoding=delimited",
      ).shouldBeFalse()
      Exposition.isTextContentType("application/octet-stream").shouldBeFalse()
      Exposition.isTextContentType("text/html; charset=utf-8").shouldBeFalse()
      Exposition.isTextContentType("text/plainer").shouldBeFalse()
    }

    // ==================== firstNonBlank ====================

    "firstNonBlank should skip spaces and tabs to a line's first token" {
      Exposition.firstNonBlank("  \tup 1", 0, 7) shouldBe 3
      Exposition.firstNonBlank("up 1", 0, 4) shouldBe 0
    }

    "firstNonBlank should look only within its range" {
      val text = "up 1\n  go 2\n"

      Exposition.firstNonBlank(text, 5, 11) shouldBe 7
      Exposition.firstNonBlank(text, 4, 4) shouldBe 4
    }

    "firstNonBlank should return the range's end for a blank range" {
      Exposition.firstNonBlank("x   \r", 1, 5) shouldBe 5
      Exposition.firstNonBlank("", 0, 0) shouldBe 0
    }

    // ==================== commentFamily ====================

    "HELP, TYPE, and UNIT lines should open the family they name" {
      Exposition.commentFamily("# HELP http_requests_total Requests served.") shouldBe "http_requests_total"
      Exposition.commentFamily("# TYPE http_requests_total counter") shouldBe "http_requests_total"
      Exposition.commentFamily("# UNIT request_seconds seconds") shouldBe "request_seconds"
      Exposition.commentFamily("# TYPE up") shouldBe "up"
    }

    // A space and a tab both end the keyword and the name, and the format allows any blanks between the tokens.
    "a tab or several blanks between the tokens should be read as one space" {
      Exposition.commentFamily("# TYPE\tfoo counter") shouldBe "foo"
      Exposition.commentFamily("# TYPE foo\tcounter") shouldBe "foo"
      Exposition.commentFamily("#\tHELP \t foo  Help text.") shouldBe "foo"
      Exposition.commentFamily("#TYPE foo counter") shouldBe "foo"
    }

    // Prometheus 3 writes a name outside the legacy character set quoted; a space inside the quotes doesn't end it.
    "a quoted name should be unquoted, spaces and all" {
      Exposition.commentFamily("# TYPE \"a.b\" counter") shouldBe "a.b"
      Exposition.commentFamily("# HELP \"a b\" A spaced counter.") shouldBe "a b"
      Exposition.commentFamily("# TYPE\t\"a\tb\"\tgauge") shouldBe "a\tb"
      Exposition.commentFamily("# TYPE \"say \\\"hi\\\"\" gauge") shouldBe "say \"hi\""
    }

    "a quoted name that is never closed should open no family" {
      Exposition.commentFamily("# TYPE \"a.b counter").shouldBeNull()
    }

    "a keyword with no name, or not followed by a blank, should open no family" {
      Exposition.commentFamily("# TYPE").shouldBeNull()
      Exposition.commentFamily("# TYPE ").shouldBeNull()
      Exposition.commentFamily("# TYPE \"\" counter").shouldBeNull()
      Exposition.commentFamily("# TYPEfoo counter").shouldBeNull()
    }

    // Keywords are matched case-sensitively, and only these three open a family.
    "any other comment should open no family" {
      Exposition.commentFamily("# EOF").shouldBeNull()
      Exposition.commentFamily("# just a comment").shouldBeNull()
      Exposition.commentFamily("# type foo counter").shouldBeNull()
      Exposition.commentFamily("#").shouldBeNull()
    }

    // The caller cuts the line to start at its first token; a sample line, or one still indented, is not a comment.
    "a line that doesn't start with a hash should open no family" {
      Exposition.commentFamily("TYPE foo counter").shouldBeNull()
      Exposition.commentFamily("  # TYPE foo counter").shouldBeNull()
      Exposition.commentFamily("foo 1").shouldBeNull()
      Exposition.commentFamily("").shouldBeNull()
    }

    // ==================== unquote ====================

    "unquote should unescape a backslash, a quote, and a line feed" {
      Exposition.unquote("\"a\\\\b\\\"c\\nd\" rest", 0) shouldBe "a\\b\"c\nd"
    }

    // The format escapes nothing else in a name; the character after any other backslash is kept, without it.
    "unquote should keep the character after a backslash it doesn't know" {
      Exposition.unquote("\"a\\.b\"", 0) shouldBe "a.b"
    }

    "unquote should start at its quote and stop at the first unescaped quote" {
      Exposition.unquote("{\"a.b\",job=\"x\"} 1", 1) shouldBe "a.b"
      Exposition.unquote("\"\"", 0) shouldBe ""
    }

    "unquote should return null when the quote isn't closed before its end" {
      Exposition.unquote("\"a.b", 0).shouldBeNull()
      Exposition.unquote("\"a.b\"", 0, 4).shouldBeNull()
      // An escape cut by the end is no escape: the backslash is kept, and the quote is still open.
      Exposition.unquote("\"a\\", 0).shouldBeNull()
    }
  }
}
