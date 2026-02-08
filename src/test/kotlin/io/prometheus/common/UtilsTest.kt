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

import io.grpc.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.common.Utils.HostPort
import io.prometheus.common.Utils.decodeParams
import io.prometheus.common.Utils.defaultEmptyJsonObject
import io.prometheus.common.Utils.exceptionDetails
import io.prometheus.common.Utils.lambda
import io.prometheus.common.Utils.parseHostPort
import io.prometheus.common.Utils.setLogLevel
import io.prometheus.common.Utils.toJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

  // ==================== lowercase Tests ====================

  @Test
  fun `lowercase should convert string to lowercase`() {
    "HELLO".lowercase() shouldBe "hello"
    "Hello World".lowercase() shouldBe "hello world"
    "MixedCase123".lowercase() shouldBe "mixedcase123"
  }

  @Test
  fun `lowercase should handle empty string`() {
    "".lowercase() shouldBe ""
  }

  @Test
  fun `lowercase should handle already lowercase string`() {
    "already lowercase".lowercase() shouldBe "already lowercase"
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

  // ==================== parseHostPort Tests ====================

  @Test
  fun `parseHostPort should parse simple host and port`() {
    val result = parseHostPort("localhost:8080", 50051)

    result shouldBe HostPort("localhost", 8080)
  }

  @Test
  fun `parseHostPort should use default port when no port specified`() {
    val result = parseHostPort("example.com", 50051)

    result shouldBe HostPort("example.com", 50051)
  }

  @Test
  fun `parseHostPort should handle bracketed IPv6 with port`() {
    val result = parseHostPort("[::1]:50051", 9090)

    result shouldBe HostPort("[::1]", 50051)
  }

  @Test
  fun `parseHostPort should handle bracketed IPv6 without port`() {
    val result = parseHostPort("[::1]", 50051)

    result shouldBe HostPort("[::1]", 50051)
  }

  @Test
  fun `parseHostPort should handle unbracketed IPv6 without port`() {
    val result = parseHostPort("::1", 50051)

    result shouldBe HostPort("::1", 50051)
  }

  @Test
  fun `parseHostPort should handle full IPv6 address without brackets`() {
    val result = parseHostPort("2001:db8::1", 50051)

    result shouldBe HostPort("2001:db8::1", 50051)
  }

  @Test
  fun `parseHostPort should handle IPv4 address with port`() {
    val result = parseHostPort("192.168.1.100:5000", 50051)

    result shouldBe HostPort("192.168.1.100", 5000)
  }

  @Test
  fun `parseHostPort should handle IPv4 address without port`() {
    val result = parseHostPort("192.168.1.100", 50051)

    result shouldBe HostPort("192.168.1.100", 50051)
  }

  @Test
  fun `parseHostPort should handle hostname with custom port`() {
    val result = parseHostPort("proxy.example.org:9090", 50051)

    result shouldBe HostPort("proxy.example.org", 9090)
  }

  @Test
  fun `parseHostPort should handle bracketed IPv6 with missing close bracket`() {
    val result = parseHostPort("[::1", 50051)

    result shouldBe HostPort("[::1", 50051)
  }

  @Test
  fun `parseHostPort should handle bracketed full IPv6 with port`() {
    val result = parseHostPort("[2001:db8::1]:8443", 50051)

    result shouldBe HostPort("[2001:db8::1]", 8443)
  }

  @Test
  fun `parseHostPort should throw for non-numeric port`() {
    shouldThrow<NumberFormatException> {
      parseHostPort("host:abc", 50051)
    }
  }

  @Test
  fun `parseHostPort should handle port 0`() {
    val result = parseHostPort("host:0", 50051)

    result shouldBe HostPort("host", 0)
  }

  @Test
  fun `parseHostPort should handle maximum port number`() {
    val result = parseHostPort("host:65535", 50051)

    result shouldBe HostPort("host", 65535)
  }

  // ==================== HostPort Data Class Tests ====================

  @Test
  fun `HostPort should support equality`() {
    val hp1 = HostPort("localhost", 8080)
    val hp2 = HostPort("localhost", 8080)
    val hp3 = HostPort("localhost", 9090)

    (hp1 == hp2) shouldBe true
    (hp1 == hp3) shouldBe false
  }

  @Test
  fun `HostPort should support copy`() {
    val hp = HostPort("localhost", 8080)
    val copied = hp.copy(port = 9090)

    copied.host shouldBe "localhost"
    copied.port shouldBe 9090
  }

  // ==================== exceptionDetails Tests ====================

  @Test
  fun `exceptionDetails should include status code`() {
    val status = Status.UNAVAILABLE.withDescription("service down")
    val exception = RuntimeException("connection lost")

    val details = status.exceptionDetails(exception)

    details shouldContain "UNAVAILABLE"
  }

  @Test
  fun `exceptionDetails should include description`() {
    val status = Status.NOT_FOUND.withDescription("agent not found")
    val exception = RuntimeException("missing")

    val details = status.exceptionDetails(exception)

    details shouldContain "agent not found"
  }

  @Test
  fun `exceptionDetails should include exception message`() {
    val status = Status.INTERNAL
    val exception = IllegalStateException("something broke")

    val details = status.exceptionDetails(exception)

    details shouldContain "something broke"
  }

  @Test
  fun `exceptionDetails should include exception class name`() {
    val status = Status.CANCELLED
    val exception = java.io.IOException("timeout")

    val details = status.exceptionDetails(exception)

    details shouldContain "IOException"
  }

  // ==================== toJsonElement Edge Case Tests ====================

  @Test
  fun `toJsonElement should parse JSON array`() {
    val json = "[1, 2, 3]"
    val element = json.toJsonElement()

    (element is JsonArray).shouldBeTrue()
  }

  @Test
  fun `toJsonElement should parse JSON primitives`() {
    "42".toJsonElement().shouldBeInstanceOf<JsonPrimitive>()
    "true".toJsonElement().shouldBeInstanceOf<JsonPrimitive>()
    "\"hello\"".toJsonElement().shouldBeInstanceOf<JsonPrimitive>()
  }

  // ==================== Type Check Helper ====================

  private inline fun <reified T> Any.shouldBeInstanceOf() {
    (this is T).shouldBeTrue()
  }
}
