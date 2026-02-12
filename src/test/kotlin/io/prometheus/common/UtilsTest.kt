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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.common.Utils.HostPort
import io.prometheus.common.Utils.decodeParams
import io.prometheus.common.Utils.defaultEmptyJsonObject
import io.prometheus.common.Utils.exceptionDetails
import io.prometheus.common.Utils.parseHostPort
import io.prometheus.common.Utils.setLogLevel
import io.prometheus.common.Utils.toJsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class UtilsTest : StringSpec() {
  // ==================== Type Check Helper ====================

  private inline fun <reified T> Any.shouldBeInstanceOf() {
    (this is T).shouldBeTrue()
  }

  init {
    // ==================== decodeParams Tests ====================

    "decodeParams should return empty string for blank input" {
      decodeParams("") shouldBe ""
      decodeParams("   ") shouldBe ""
    }

    "decodeParams should decode URL-encoded parameters" {
      val encoded = "foo%3Dbar%26baz%3Dqux"
      val result = decodeParams(encoded)

      result shouldBe "?foo=bar&baz=qux"
    }

    "decodeParams should add question mark prefix" {
      val result = decodeParams("simple")

      result shouldBe "?simple"
    }

    "decodeParams should handle special characters" {
      val encoded = "name%3DJohn%20Doe%26city%3DNew%20York"
      val result = decodeParams(encoded)

      result shouldBe "?name=John Doe&city=New York"
    }

    "decodeParams should handle already decoded strings" {
      val result = decodeParams("key=value")

      result shouldBe "?key=value"
    }

    // ==================== lowercase Tests ====================

    "lowercase should convert string to lowercase" {
      "HELLO".lowercase() shouldBe "hello"
      "Hello World".lowercase() shouldBe "hello world"
      "MixedCase123".lowercase() shouldBe "mixedcase123"
    }

    "lowercase should handle empty string" {
      "".lowercase() shouldBe ""
    }

    "lowercase should handle already lowercase string" {
      "already lowercase".lowercase() shouldBe "already lowercase"
    }

    // ==================== defaultEmptyJsonObject Tests ====================

    "defaultEmptyJsonObject should return original string if not empty" {
      """{"key":"value"}""".defaultEmptyJsonObject() shouldBe """{"key":"value"}"""
      "some content".defaultEmptyJsonObject() shouldBe "some content"
    }

    "defaultEmptyJsonObject should return empty JSON object for empty string" {
      "".defaultEmptyJsonObject() shouldBe "{}"
    }

    // ==================== toJsonElement Tests ====================

    "toJsonElement should parse valid JSON object" {
      val json = """{"key":"value"}"""
      val element = json.toJsonElement()

      element.shouldBeInstanceOf<JsonObject>()
    }

    "toJsonElement should parse empty JSON object" {
      val json = "{}"
      val element = json.toJsonElement()

      element.shouldBeInstanceOf<JsonObject>()
    }

    "toJsonElement should throw for invalid JSON" {
      shouldThrow<Exception> {
        "not valid json".toJsonElement()
      }
    }

    // ==================== setLogLevel Tests ====================

    "setLogLevel should accept valid log levels" {
      // These should not throw
      setLogLevel("test", "trace")
      setLogLevel("test", "debug")
      setLogLevel("test", "info")
      setLogLevel("test", "warn")
      setLogLevel("test", "error")
      setLogLevel("test", "off")
    }

    "setLogLevel should be case insensitive" {
      // These should not throw
      setLogLevel("test", "TRACE")
      setLogLevel("test", "DEBUG")
      setLogLevel("test", "INFO")
      setLogLevel("test", "WARN")
      setLogLevel("test", "ERROR")
      setLogLevel("test", "OFF")
    }

    "setLogLevel should throw for invalid log level" {
      val exception = shouldThrow<IllegalArgumentException> {
        setLogLevel("test", "invalid")
      }

      exception.message shouldContain "Invalid"
      exception.message shouldContain "log level"
    }

    // ==================== getVersionDesc Tests ====================

    "getVersionDesc should return non-empty string" {
      val versionDesc = Utils.getVersionDesc(false)

      versionDesc.shouldNotBeEmpty()
    }

    "getVersionDesc should return JSON when requested" {
      val versionDesc = Utils.getVersionDesc(true)

      versionDesc.shouldNotBeEmpty()
      // JSON output should contain braces or JSON structure
    }

    // Bug #19: VersionValidator was removed — version printing now happens after parsing
    // in BaseOptions, not inside a JCommander validator that calls exitProcess.
    "getVersionDesc should be callable without triggering exitProcess" {
      // Before the fix, calling VersionValidator.validate() would call exitProcess(0).
      // Now getVersionDesc is a standalone utility — calling it must not exit the JVM.
      val plainDesc = Utils.getVersionDesc(false)
      val jsonDesc = Utils.getVersionDesc(true)

      plainDesc.shouldNotBeEmpty()
      jsonDesc.shouldNotBeEmpty()
    }

    // ==================== parseHostPort Tests ====================

    "parseHostPort should parse simple host and port" {
      val result = parseHostPort("localhost:8080", 50051)

      result shouldBe HostPort("localhost", 8080)
    }

    "parseHostPort should use default port when no port specified" {
      val result = parseHostPort("example.com", 50051)

      result shouldBe HostPort("example.com", 50051)
    }

    "parseHostPort should handle bracketed IPv6 with port" {
      val result = parseHostPort("[::1]:50051", 9090)

      result shouldBe HostPort("[::1]", 50051)
    }

    "parseHostPort should handle bracketed IPv6 without port" {
      val result = parseHostPort("[::1]", 50051)

      result shouldBe HostPort("[::1]", 50051)
    }

    "parseHostPort should handle unbracketed IPv6 without port" {
      val result = parseHostPort("::1", 50051)

      result shouldBe HostPort("::1", 50051)
    }

    "parseHostPort should handle full IPv6 address without brackets" {
      val result = parseHostPort("2001:db8::1", 50051)

      result shouldBe HostPort("2001:db8::1", 50051)
    }

    "parseHostPort should handle IPv4 address with port" {
      val result = parseHostPort("192.168.1.100:5000", 50051)

      result shouldBe HostPort("192.168.1.100", 5000)
    }

    "parseHostPort should handle IPv4 address without port" {
      val result = parseHostPort("192.168.1.100", 50051)

      result shouldBe HostPort("192.168.1.100", 50051)
    }

    "parseHostPort should handle hostname with custom port" {
      val result = parseHostPort("proxy.example.org:9090", 50051)

      result shouldBe HostPort("proxy.example.org", 9090)
    }

    "parseHostPort should throw for malformed IPv6 with missing close bracket" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[::1", 50051)
      }
      exception.message shouldContain "missing closing bracket"
    }

    "parseHostPort should throw for malformed IPv6 with content after unclosed bracket" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[2001:db8::1", 50051)
      }
      exception.message shouldContain "missing closing bracket"
    }

    "parseHostPort should handle bracketed full IPv6 with port" {
      val result = parseHostPort("[2001:db8::1]:8443", 50051)

      result shouldBe HostPort("[2001:db8::1]", 8443)
    }

    // M4: parseHostPort now validates port values with descriptive error messages
    // instead of throwing raw NumberFormatException.
    "parseHostPort should throw IllegalArgumentException for non-numeric port" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("host:abc", 50051)
      }
      exception.message shouldContain "host:abc"
      exception.message shouldContain "abc"
    }

    "parseHostPort should throw IllegalArgumentException for port above 65535" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("host:99999", 50051)
      }
      exception.message shouldContain "host:99999"
    }

    "parseHostPort should throw IllegalArgumentException for negative port" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("host:-1", 50051)
      }
      exception.message shouldContain "host:-1"
    }

    "parseHostPort should throw IllegalArgumentException for non-numeric port in bracketed IPv6" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[::1]:abc", 50051)
      }
      exception.message shouldContain "abc"
    }

    "parseHostPort should throw IllegalArgumentException for out-of-range port in bracketed IPv6" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[::1]:70000", 50051)
      }
      exception.message shouldContain "70000"
    }

    "parseHostPort should handle port 0" {
      val result = parseHostPort("host:0", 50051)

      result shouldBe HostPort("host", 0)
    }

    "parseHostPort should handle maximum port number" {
      val result = parseHostPort("host:65535", 50051)

      result shouldBe HostPort("host", 65535)
    }

    // ==================== HostPort Data Class Tests ====================

    "HostPort should support equality" {
      val hp1 = HostPort("localhost", 8080)
      val hp2 = HostPort("localhost", 8080)
      val hp3 = HostPort("localhost", 9090)

      (hp1 == hp2) shouldBe true
      (hp1 == hp3) shouldBe false
    }

    "HostPort should support copy" {
      val hp = HostPort("localhost", 8080)
      val copied = hp.copy(port = 9090)

      copied.host shouldBe "localhost"
      copied.port shouldBe 9090
    }

    // ==================== exceptionDetails Tests ====================

    "exceptionDetails should include status code" {
      val status = Status.UNAVAILABLE.withDescription("service down")
      val exception = RuntimeException("connection lost")

      val details = status.exceptionDetails(exception)

      details shouldContain "UNAVAILABLE"
    }

    "exceptionDetails should include description" {
      val status = Status.NOT_FOUND.withDescription("agent not found")
      val exception = RuntimeException("missing")

      val details = status.exceptionDetails(exception)

      details shouldContain "agent not found"
    }

    "exceptionDetails should include exception message" {
      val status = Status.INTERNAL
      val exception = IllegalStateException("something broke")

      val details = status.exceptionDetails(exception)

      details shouldContain "something broke"
    }

    "exceptionDetails should include exception class name" {
      val status = Status.CANCELLED
      val exception = java.io.IOException("timeout")

      val details = status.exceptionDetails(exception)

      details shouldContain "IOException"
    }

    // ==================== toJsonElement Edge Case Tests ====================

    "toJsonElement should parse JSON array" {
      val json = "[1, 2, 3]"
      val element = json.toJsonElement()

      (element is JsonArray).shouldBeTrue()
    }

    "toJsonElement should parse JSON primitives" {
      "42".toJsonElement().shouldBeInstanceOf<JsonPrimitive>()
      "true".toJsonElement().shouldBeInstanceOf<JsonPrimitive>()
      "\"hello\"".toJsonElement().shouldBeInstanceOf<JsonPrimitive>()
    }

    // ==================== Bug #12: parseHostPort blank input validation ====================

    "parseHostPort should throw for empty string" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("", 50051)
      }
      exception.message shouldContain "must not be blank"
    }

    "parseHostPort should throw for blank string" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("   ", 50051)
      }
      exception.message shouldContain "must not be blank"
    }
  }
}
