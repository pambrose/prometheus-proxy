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

import ch.qos.logback.classic.Level
import io.grpc.Status
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.common.Utils.HostPort
import io.prometheus.common.Utils.appendQueryParams
import io.prometheus.common.Utils.defaultEmptyJsonObject
import io.prometheus.common.Utils.exceptionDetails
import io.prometheus.common.Utils.parseHostPort
import io.prometheus.common.Utils.sanitizeQueryParams
import io.prometheus.common.Utils.sanitizeUrl
import io.prometheus.common.Utils.setLogLevel
import io.prometheus.common.Utils.toJsonElement
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class UtilsTest : StringSpec() {
  // ==================== Type Check Helper ====================

  private inline fun <reified T> Any.shouldBeInstanceOf() {
    (this is T).shouldBeTrue()
  }

  private val originalLogLevel = Level.INFO // (LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger).level

  init {
    afterTest {
      setLogLevel("test", originalLogLevel.levelStr.lowercase())
    }

    // ==================== appendQueryParams Tests ====================
    // The input is an already-encoded query string (formUrlEncode output): raw =/& delimiters with
    // percent-encoded keys/values. appendQueryParams appends it verbatim, preserving the encoding.

    "appendQueryParams should append an encoded query string to a base url without query" {
      val url = appendQueryParams("http://localhost:$PROXY_HTTP_PORT/metrics", "foo=bar&baz=qux")

      url shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics?foo=bar&baz=qux"
    }

    "appendQueryParams should append to a base url with an existing query using &" {
      val url = appendQueryParams("http://localhost:$PROXY_HTTP_PORT/metrics?existing=1", "foo=bar")

      url shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics?existing=1&foo=bar"
    }

    "appendQueryParams should not add a separator when the base url already ends with ? or &" {
      appendQueryParams("http://localhost:$PROXY_HTTP_PORT/metrics?", "foo=bar") shouldBe
        "http://localhost:$PROXY_HTTP_PORT/metrics?foo=bar"
    }

    "appendQueryParams should return the base url unchanged for blank params" {
      appendQueryParams(
        "http://localhost:$PROXY_HTTP_PORT/metrics",
        "",
      ) shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics"
    }

    // The fix: an encoded delimiter inside a value must stay encoded, not be decoded into a real
    // & (which would split one param into two) or # (which would start a URL fragment).
    "appendQueryParams should preserve encoded delimiters inside a value" {
      appendQueryParams("http://localhost:$PROXY_HTTP_PORT/metrics", "q=a%26b%23c") shouldBe
        "http://localhost:$PROXY_HTTP_PORT/metrics?q=a%26b%23c"
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

    // "all" is no longer a valid level: ch.qos.logback.classic.Level.ALL is deprecated, so the
    // "all" branch was removed from setLogLevel(). It now throws like any other unknown level.
    "setLogLevel should reject all (Level.ALL is deprecated)" {
      shouldThrow<IllegalArgumentException> { setLogLevel("test", "all") }
      shouldThrow<IllegalArgumentException> { setLogLevel("test", "ALL") }
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
      val result = parseHostPort("localhost:$PROXY_HTTP_PORT", PROXY_AGENT_PORT)

      result shouldBe HostPort("localhost", PROXY_HTTP_PORT)
    }

    "parseHostPort should use default port when no port specified" {
      val result = parseHostPort("example.com", PROXY_AGENT_PORT)

      result shouldBe HostPort("example.com", PROXY_AGENT_PORT)
    }

    "parseHostPort should handle bracketed IPv6 with port" {
      val result = parseHostPort("[::1]:$PROXY_AGENT_PORT", PROMETHEUS_PORT)

      result shouldBe HostPort("::1", PROXY_AGENT_PORT)
    }

    "parseHostPort should handle bracketed IPv6 without port" {
      val result = parseHostPort("[::1]", PROXY_AGENT_PORT)

      result shouldBe HostPort("::1", PROXY_AGENT_PORT)
    }

    "parseHostPort should handle unbracketed IPv6 without port" {
      val result = parseHostPort("::1", PROXY_AGENT_PORT)

      result shouldBe HostPort("::1", PROXY_AGENT_PORT)
    }

    "parseHostPort should handle full IPv6 address without brackets" {
      val result = parseHostPort("2001:db8::1", PROXY_AGENT_PORT)

      result shouldBe HostPort("2001:db8::1", PROXY_AGENT_PORT)
    }

    "parseHostPort should handle IPv4 address with port" {
      val result = parseHostPort("192.168.1.100:5000", PROXY_AGENT_PORT)

      result shouldBe HostPort("192.168.1.100", 5000)
    }

    "parseHostPort should handle IPv4 address without port" {
      val result = parseHostPort("192.168.1.100", PROXY_AGENT_PORT)

      result shouldBe HostPort("192.168.1.100", PROXY_AGENT_PORT)
    }

    "parseHostPort should handle hostname with custom port" {
      val result = parseHostPort("proxy.example.org:$PROMETHEUS_PORT", PROXY_AGENT_PORT)

      result shouldBe HostPort("proxy.example.org", PROMETHEUS_PORT)
    }

    "parseHostPort should throw for malformed IPv6 with missing close bracket" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[::1", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "missing closing bracket"
    }

    "parseHostPort should throw for malformed IPv6 with content after unclosed bracket" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[2001:db8::1", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "missing closing bracket"
    }

    "parseHostPort should handle bracketed full IPv6 with port" {
      val result = parseHostPort("[2001:db8::1]:8443", PROXY_AGENT_PORT)

      result shouldBe HostPort("2001:db8::1", 8443)
    }

    // M4: parseHostPort now validates port values with descriptive error messages
    // instead of throwing raw NumberFormatException.
    "parseHostPort should throw IllegalArgumentException for non-numeric port" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("host:abc", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "host:abc"
      exception.message shouldContain "abc"
    }

    "parseHostPort should throw IllegalArgumentException for port above 65535" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("host:99999", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "host:99999"
    }

    "parseHostPort should throw IllegalArgumentException for negative port" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("host:-1", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "host:-1"
    }

    "parseHostPort should throw IllegalArgumentException for non-numeric port in bracketed IPv6" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[::1]:abc", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "abc"
    }

    "parseHostPort should throw IllegalArgumentException for out-of-range port in bracketed IPv6" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("[::1]:70000", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "70000"
    }

    "parseHostPort should handle port 0" {
      val result = parseHostPort("host:0", PROXY_AGENT_PORT)

      result shouldBe HostPort("host", 0)
    }

    "parseHostPort should handle maximum port number" {
      val result = parseHostPort("host:65535", PROXY_AGENT_PORT)

      result shouldBe HostPort("host", 65535)
    }

    // ==================== HostPort Data Class Tests ====================

    "HostPort should support equality" {
      val hp1 = HostPort("localhost", PROXY_HTTP_PORT)
      val hp2 = HostPort("localhost", PROXY_HTTP_PORT)
      val hp3 = HostPort("localhost", PROMETHEUS_PORT)

      (hp1 == hp2) shouldBe true
      (hp1 == hp3) shouldBe false
    }

    "HostPort should support copy" {
      val hp = HostPort("localhost", PROXY_HTTP_PORT)
      val copied = hp.copy(port = PROMETHEUS_PORT)

      copied.host shouldBe "localhost"
      copied.port shouldBe PROMETHEUS_PORT
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
        parseHostPort("", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "must not be blank"
    }

    "parseHostPort should throw for blank string" {
      val exception = shouldThrow<IllegalArgumentException> {
        parseHostPort("   ", PROXY_AGENT_PORT)
      }
      exception.message shouldContain "must not be blank"
    }

    // ==================== Bug #5: sanitizeUrl Tests ====================

    "sanitizeUrl should strip user and password from URL" {
      sanitizeUrl(
        "http://admin:secret@host:$PROXY_HTTP_PORT/metrics",
      ) shouldBe "http://***@host:$PROXY_HTTP_PORT/metrics"
    }

    "sanitizeUrl should strip user-only credentials from URL" {
      sanitizeUrl("http://admin@host/metrics") shouldBe "http://***@host/metrics"
    }

    "sanitizeUrl should not modify URL without credentials" {
      sanitizeUrl("http://host:$PROXY_HTTP_PORT/metrics") shouldBe "http://host:$PROXY_HTTP_PORT/metrics"
    }

    "sanitizeUrl should redact both credentials and query values" {
      sanitizeUrl("https://user:pass@example.com/path?q=1") shouldBe "https://***@example.com/path?q=***"
    }

    "sanitizeUrl should redact query value even when value contains @" {
      sanitizeUrl("http://host/path?email=user@example.com") shouldBe "http://host/path?email=***"
    }

    "sanitizeUrl should handle empty string" {
      sanitizeUrl("") shouldBe ""
    }

    "sanitizeUrl should handle URL without scheme" {
      sanitizeUrl("host:$PROXY_HTTP_PORT/metrics") shouldBe "host:$PROXY_HTTP_PORT/metrics"
    }

    // Item 1: query-string secrets (?token=…, ?api_key=…) must be redacted before logging/echoing.
    "sanitizeUrl should redact a single query-parameter value" {
      sanitizeUrl(
        "http://host:$PROXY_HTTP_PORT/metrics?token=secret123",
      ) shouldBe "http://host:$PROXY_HTTP_PORT/metrics?token=***"
    }

    "sanitizeUrl should redact every query-parameter value while preserving keys" {
      sanitizeUrl("https://host/m?api_key=abc&job=node&access_token=xyz") shouldBe
        "https://host/m?api_key=***&job=***&access_token=***"
    }

    "sanitizeUrl should redact both userinfo and query values together" {
      sanitizeUrl(
        "http://admin:hunter2@host:$PROXY_HTTP_PORT/m?token=s3cr3t",
      ) shouldBe "http://***@host:$PROXY_HTTP_PORT/m?token=***"
    }

    "sanitizeUrl should preserve the fragment after redacting query values" {
      sanitizeUrl("http://host/m?token=s#section") shouldBe "http://host/m?token=***#section"
    }

    "sanitizeUrl should leave a valueless query token untouched" {
      sanitizeUrl("http://host/m?debug&token=s") shouldBe "http://host/m?debug&token=***"
    }

    "sanitizeUrl should not modify a URL with no query string" {
      sanitizeUrl("http://host:$PROXY_HTTP_PORT/metrics") shouldBe "http://host:$PROXY_HTTP_PORT/metrics"
    }

    // ==================== sanitizeQueryParams Tests ====================

    "sanitizeQueryParams should return blank input unchanged" {
      sanitizeQueryParams("") shouldBe ""
      sanitizeQueryParams("   ") shouldBe "   "
    }

    "sanitizeQueryParams should redact values while preserving keys" {
      sanitizeQueryParams("token=abc&job=node") shouldBe "token=***&job=***"
    }

    "sanitizeQueryParams should leave a valueless key untouched" {
      sanitizeQueryParams("debug&token=abc") shouldBe "debug&token=***"
    }
  }
}
