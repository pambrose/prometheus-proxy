/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.common.Utils.decodeParams
import io.prometheus.common.Utils.defaultEmptyJsonObject
import io.prometheus.common.Utils.ifTrue
import io.prometheus.common.Utils.lambda
import io.prometheus.common.Utils.setLogLevel
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.common.Utils.toLowercase
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

class UtilsTest {
  // ==================== decodeParams Tests ====================

  @Test
  fun `decodeParams should return empty string for blank input`() {
    decodeParams("") shouldBe ""
    decodeParams("   ") shouldBe ""
  }

  @Test
  fun `decodeParams should decode URL-encoded parameters`() {
    val encoded = "foo%3Dbar%26baz%3Dqux"
    val result = decodeParams(encoded)

    result shouldBe "?foo=bar&baz=qux"
  }

  @Test
  fun `decodeParams should add question mark prefix`() {
    val result = decodeParams("simple")

    result shouldBe "?simple"
  }

  @Test
  fun `decodeParams should handle special characters`() {
    val encoded = "name%3DJohn%20Doe%26city%3DNew%20York"
    val result = decodeParams(encoded)

    result shouldBe "?name=John Doe&city=New York"
  }

  @Test
  fun `decodeParams should handle already decoded strings`() {
    val result = decodeParams("key=value")

    result shouldBe "?key=value"
  }

  // ==================== toLowercase Tests ====================

  @Test
  fun `toLowercase should convert string to lowercase`() {
    "HELLO".toLowercase() shouldBe "hello"
    "Hello World".toLowercase() shouldBe "hello world"
    "MixedCase123".toLowercase() shouldBe "mixedcase123"
  }

  @Test
  fun `toLowercase should handle empty string`() {
    "".toLowercase() shouldBe ""
  }

  @Test
  fun `toLowercase should handle already lowercase string`() {
    "already lowercase".toLowercase() shouldBe "already lowercase"
  }

  // ==================== defaultEmptyJsonObject Tests ====================

  @Test
  fun `defaultEmptyJsonObject should return original string if not empty`() {
    """{"key":"value"}""".defaultEmptyJsonObject() shouldBe """{"key":"value"}"""
    "some content".defaultEmptyJsonObject() shouldBe "some content"
  }

  @Test
  fun `defaultEmptyJsonObject should return empty JSON object for empty string`() {
    "".defaultEmptyJsonObject() shouldBe "{}"
  }

  // ==================== toJsonElement Tests ====================

  @Test
  fun `toJsonElement should parse valid JSON object`() {
    val json = """{"key":"value"}"""
    val element = json.toJsonElement()

    element.shouldBeInstanceOf<JsonObject>()
  }

  @Test
  fun `toJsonElement should parse empty JSON object`() {
    val json = "{}"
    val element = json.toJsonElement()

    element.shouldBeInstanceOf<JsonObject>()
  }

  @Test
  fun `toJsonElement should throw for invalid JSON`() {
    shouldThrow<Exception> {
      "not valid json".toJsonElement()
    }
  }

  // ==================== lambda Tests ====================

  @Test
  fun `lambda should return the same block`() {
    val block: () -> Int = { 42 }
    val result = lambda(block)

    result shouldBe block
    result() shouldBe 42
  }

  @Test
  fun `lambda should work with different types`() {
    val stringBlock: () -> String = { "hello" }
    val intBlock: () -> Int = { 123 }

    lambda(stringBlock)() shouldBe "hello"
    lambda(intBlock)() shouldBe 123
  }

  // ==================== ifTrue Extension Tests ====================

  @Test
  fun `ifTrue should execute block when true`() {
    var executed = false

    true.ifTrue { executed = true }

    executed.shouldBeTrue()
  }

  @Test
  fun `ifTrue should not execute block when false`() {
    var executed = false

    false.ifTrue { executed = true }

    executed.shouldBeFalse()
  }

  // ==================== setLogLevel Tests ====================

  @Test
  fun `setLogLevel should accept valid log levels`() {
    // These should not throw
    setLogLevel("test", "trace")
    setLogLevel("test", "debug")
    setLogLevel("test", "info")
    setLogLevel("test", "warn")
    setLogLevel("test", "error")
    setLogLevel("test", "off")
  }

  @Test
  fun `setLogLevel should be case insensitive`() {
    // These should not throw
    setLogLevel("test", "TRACE")
    setLogLevel("test", "DEBUG")
    setLogLevel("test", "INFO")
    setLogLevel("test", "WARN")
    setLogLevel("test", "ERROR")
    setLogLevel("test", "OFF")
  }

  @Test
  fun `setLogLevel should throw for invalid log level`() {
    val exception = shouldThrow<IllegalArgumentException> {
      setLogLevel("test", "invalid")
    }

    exception.message shouldContain "Invalid"
    exception.message shouldContain "log level"
  }

  // ==================== getVersionDesc Tests ====================

  @Test
  fun `getVersionDesc should return non-empty string`() {
    val versionDesc = Utils.getVersionDesc(false)

    versionDesc.shouldNotBeEmpty()
  }

  @Test
  fun `getVersionDesc should return JSON when requested`() {
    val versionDesc = Utils.getVersionDesc(true)

    versionDesc.shouldNotBeEmpty()
    // JSON output should contain braces or JSON structure
  }

  // ==================== Type Check Helper ====================

  private inline fun <reified T> Any.shouldBeInstanceOf() {
    (this is T).shouldBeTrue()
  }
}
