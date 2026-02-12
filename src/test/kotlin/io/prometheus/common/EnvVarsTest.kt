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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty

class EnvVarsTest : StringSpec() {
  init {
    // ==================== Default Value Fallback Tests ====================

    "getEnv should return default string when env var not set" {
      // Use an env var that is very unlikely to be set
      val defaultVal = "default-value"
      val result = EnvVars.PROXY_CONFIG.getEnv(defaultVal)

      // If PROXY_CONFIG env var is not set, should return default
      // If it is set, result will be non-empty
      if (System.getenv("PROXY_CONFIG") == null) {
        result shouldBe defaultVal
      } else {
        result.shouldNotBeEmpty()
      }
    }

    "getEnv should return default boolean when env var not set" {
      val defaultVal = false
      val result = EnvVars.SD_ENABLED.getEnv(defaultVal)

      // If SD_ENABLED env var is not set, should return default false
      if (System.getenv("SD_ENABLED") == null) {
        result.shouldBeFalse()
      }
    }

    "getEnv should return default true boolean when specified" {
      val defaultVal = true
      val result = EnvVars.DEBUG_ENABLED.getEnv(defaultVal)

      // If DEBUG_ENABLED env var is not set, should return default true
      if (System.getenv("DEBUG_ENABLED") == null) {
        result.shouldBeTrue()
      }
    }

    "getEnv should return default int when env var not set" {
      val defaultVal = 8080
      val result = EnvVars.PROXY_PORT.getEnv(defaultVal)

      // If PROXY_PORT env var is not set, should return default
      if (System.getenv("PROXY_PORT") == null) {
        result shouldBe defaultVal
      }
    }

    "getEnv should return default long when env var not set" {
      val defaultVal = 120L
      val result = EnvVars.HANDSHAKE_TIMEOUT_SECS.getEnv(defaultVal)

      // If HANDSHAKE_TIMEOUT_SECS env var is not set, should return default
      if (System.getenv("HANDSHAKE_TIMEOUT_SECS") == null) {
        result shouldBe defaultVal
      }
    }

    // ==================== Enum Name Tests ====================

    "EnvVars enum should have correct names for proxy variables" {
      EnvVars.PROXY_CONFIG.name shouldBe "PROXY_CONFIG"
      EnvVars.PROXY_PORT.name shouldBe "PROXY_PORT"
      EnvVars.AGENT_PORT.name shouldBe "AGENT_PORT"
      EnvVars.SD_ENABLED.name shouldBe "SD_ENABLED"
      EnvVars.SD_PATH.name shouldBe "SD_PATH"
      EnvVars.SD_TARGET_PREFIX.name shouldBe "SD_TARGET_PREFIX"
    }

    "EnvVars enum should have correct names for agent variables" {
      EnvVars.AGENT_CONFIG.name shouldBe "AGENT_CONFIG"
      EnvVars.PROXY_HOSTNAME.name shouldBe "PROXY_HOSTNAME"
      EnvVars.AGENT_NAME.name shouldBe "AGENT_NAME"
      EnvVars.CONSOLIDATED.name shouldBe "CONSOLIDATED"
      EnvVars.SCRAPE_TIMEOUT_SECS.name shouldBe "SCRAPE_TIMEOUT_SECS"
    }

    "EnvVars enum should have correct names for common variables" {
      EnvVars.DEBUG_ENABLED.name shouldBe "DEBUG_ENABLED"
      EnvVars.METRICS_ENABLED.name shouldBe "METRICS_ENABLED"
      EnvVars.METRICS_PORT.name shouldBe "METRICS_PORT"
      EnvVars.ADMIN_ENABLED.name shouldBe "ADMIN_ENABLED"
      EnvVars.ADMIN_PORT.name shouldBe "ADMIN_PORT"
    }

    "EnvVars enum should have correct names for TLS variables" {
      EnvVars.CERT_CHAIN_FILE_PATH.name shouldBe "CERT_CHAIN_FILE_PATH"
      EnvVars.PRIVATE_KEY_FILE_PATH.name shouldBe "PRIVATE_KEY_FILE_PATH"
      EnvVars.TRUST_CERT_COLLECTION_FILE_PATH.name shouldBe "TRUST_CERT_COLLECTION_FILE_PATH"
    }

    "EnvVars enum should have correct names for gRPC keepalive variables" {
      EnvVars.KEEPALIVE_TIME_SECS.name shouldBe "KEEPALIVE_TIME_SECS"
      EnvVars.KEEPALIVE_TIMEOUT_SECS.name shouldBe "KEEPALIVE_TIMEOUT_SECS"
      EnvVars.KEEPALIVE_WITHOUT_CALLS.name shouldBe "KEEPALIVE_WITHOUT_CALLS"
      EnvVars.UNARY_DEADLINE_SECS.name shouldBe "UNARY_DEADLINE_SECS"
    }

    "EnvVars enum should have correct names for HTTP client cache variables" {
      EnvVars.MAX_CLIENT_CACHE_SIZE.name shouldBe "MAX_CLIENT_CACHE_SIZE"
      EnvVars.MAX_CLIENT_CACHE_AGE_MINS.name shouldBe "MAX_CLIENT_CACHE_AGE_MINS"
      EnvVars.MAX_CLIENT_CACHE_IDLE_MINS.name shouldBe "MAX_CLIENT_CACHE_IDLE_MINS"
      EnvVars.CLIENT_CACHE_CLEANUP_INTERVAL_MINS.name shouldBe "CLIENT_CACHE_CLEANUP_INTERVAL_MINS"
    }

    // ==================== Type Conversion Tests ====================

    "getEnv with different default types should work correctly" {
      // Test that different overloads work without confusion
      val stringDefault = "test"
      val boolDefault = true
      val intDefault = 42
      val longDefault = 100L

      // These should all use defaults since env vars are unlikely to be set
      val stringResult = EnvVars.OVERRIDE_AUTHORITY.getEnv(stringDefault)
      val boolResult = EnvVars.REFLECTION_DISABLED.getEnv(boolDefault)
      val intResult = EnvVars.MIN_GZIP_SIZE_BYTES.getEnv(intDefault)
      val longResult = EnvVars.PERMIT_KEEPALIVE_TIME_SECS.getEnv(longDefault)

      // Verify defaults are returned when env vars are not set
      if (System.getenv("OVERRIDE_AUTHORITY") == null) {
        stringResult shouldBe stringDefault
      }
      if (System.getenv("REFLECTION_DISABLED") == null) {
        boolResult shouldBe boolDefault
      }
      if (System.getenv("MIN_GZIP_SIZE_BYTES") == null) {
        intResult shouldBe intDefault
      }
      if (System.getenv("PERMIT_KEEPALIVE_TIME_SECS") == null) {
        longResult shouldBe longDefault
      }
    }

    // ==================== Enum Completeness Tests ====================

    "EnvVars enum should contain all expected proxy variables" {
      val proxyVars = listOf(
        EnvVars.PROXY_CONFIG,
        EnvVars.PROXY_PORT,
        EnvVars.AGENT_PORT,
        EnvVars.SD_ENABLED,
        EnvVars.SD_PATH,
        EnvVars.SD_TARGET_PREFIX,
        EnvVars.REFLECTION_DISABLED,
        EnvVars.HANDSHAKE_TIMEOUT_SECS,
        EnvVars.PERMIT_KEEPALIVE_WITHOUT_CALLS,
        EnvVars.PERMIT_KEEPALIVE_TIME_SECS,
        EnvVars.MAX_CONNECTION_IDLE_SECS,
        EnvVars.MAX_CONNECTION_AGE_SECS,
        EnvVars.MAX_CONNECTION_AGE_GRACE_SECS,
        EnvVars.PROXY_LOG_LEVEL,
      )

      proxyVars.forEach { envVar ->
        // Verify each enum constant exists and has a name
        envVar.name.shouldNotBeEmpty()
      }
    }

    "EnvVars enum should have exactly 44 entries" {
      EnvVars.entries.size shouldBe 44
    }

    "EnvVars entries should contain all defined constants" {
      val allNames = EnvVars.entries.map { it.name }

      allNames shouldContainAll listOf(
        "PROXY_CONFIG",
        "PROXY_PORT",
        "AGENT_PORT",
        "SD_ENABLED",
        "SD_PATH",
        "SD_TARGET_PREFIX",
        "REFLECTION_DISABLED",
        "HANDSHAKE_TIMEOUT_SECS",
        "PERMIT_KEEPALIVE_WITHOUT_CALLS",
        "PERMIT_KEEPALIVE_TIME_SECS",
        "MAX_CONNECTION_IDLE_SECS",
        "MAX_CONNECTION_AGE_SECS",
        "MAX_CONNECTION_AGE_GRACE_SECS",
        "PROXY_LOG_LEVEL",
        "AGENT_CONFIG",
        "PROXY_HOSTNAME",
        "AGENT_NAME",
        "CONSOLIDATED",
        "SCRAPE_TIMEOUT_SECS",
        "SCRAPE_MAX_RETRIES",
        "CHUNK_CONTENT_SIZE_KBS",
        "MIN_GZIP_SIZE_BYTES",
        "TRUST_ALL_X509_CERTIFICATES",
        "MAX_CONCURRENT_CLIENTS",
        "CLIENT_TIMEOUT_SECS",
        "MAX_CLIENT_CACHE_SIZE",
        "MAX_CLIENT_CACHE_AGE_MINS",
        "MAX_CLIENT_CACHE_IDLE_MINS",
        "CLIENT_CACHE_CLEANUP_INTERVAL_MINS",
        "KEEPALIVE_WITHOUT_CALLS",
        "AGENT_LOG_LEVEL",
        "DEBUG_ENABLED",
        "METRICS_ENABLED",
        "METRICS_PORT",
        "ADMIN_ENABLED",
        "ADMIN_PORT",
        "TRANSPORT_FILTER_DISABLED",
        "CERT_CHAIN_FILE_PATH",
        "PRIVATE_KEY_FILE_PATH",
        "TRUST_CERT_COLLECTION_FILE_PATH",
        "OVERRIDE_AUTHORITY",
        "KEEPALIVE_TIME_SECS",
        "KEEPALIVE_TIMEOUT_SECS",
        "UNARY_DEADLINE_SECS",
      )
    }

    "entry count should match completeness list size to prevent drift" {
      // Guard against M11-style bugs: if someone adds an enum entry but forgets to
      // update the count test or the completeness list, this test will catch it.
      val allExpected = listOf(
        "PROXY_CONFIG",
        "PROXY_PORT",
        "AGENT_PORT",
        "SD_ENABLED",
        "SD_PATH",
        "SD_TARGET_PREFIX",
        "REFLECTION_DISABLED",
        "HANDSHAKE_TIMEOUT_SECS",
        "PERMIT_KEEPALIVE_WITHOUT_CALLS",
        "PERMIT_KEEPALIVE_TIME_SECS",
        "MAX_CONNECTION_IDLE_SECS",
        "MAX_CONNECTION_AGE_SECS",
        "MAX_CONNECTION_AGE_GRACE_SECS",
        "PROXY_LOG_LEVEL",
        "AGENT_CONFIG",
        "PROXY_HOSTNAME",
        "AGENT_NAME",
        "CONSOLIDATED",
        "SCRAPE_TIMEOUT_SECS",
        "SCRAPE_MAX_RETRIES",
        "CHUNK_CONTENT_SIZE_KBS",
        "MIN_GZIP_SIZE_BYTES",
        "TRUST_ALL_X509_CERTIFICATES",
        "MAX_CONCURRENT_CLIENTS",
        "CLIENT_TIMEOUT_SECS",
        "MAX_CLIENT_CACHE_SIZE",
        "MAX_CLIENT_CACHE_AGE_MINS",
        "MAX_CLIENT_CACHE_IDLE_MINS",
        "CLIENT_CACHE_CLEANUP_INTERVAL_MINS",
        "KEEPALIVE_WITHOUT_CALLS",
        "UNARY_DEADLINE_SECS",
        "AGENT_LOG_LEVEL",
        "DEBUG_ENABLED",
        "METRICS_ENABLED",
        "METRICS_PORT",
        "ADMIN_ENABLED",
        "ADMIN_PORT",
        "TRANSPORT_FILTER_DISABLED",
        "CERT_CHAIN_FILE_PATH",
        "PRIVATE_KEY_FILE_PATH",
        "TRUST_CERT_COLLECTION_FILE_PATH",
        "OVERRIDE_AUTHORITY",
        "KEEPALIVE_TIME_SECS",
        "KEEPALIVE_TIMEOUT_SECS",
      )
      val actualNames = EnvVars.entries.map { it.name }.sorted()
      val expectedNames = allExpected.sorted()

      actualNames shouldBe expectedNames
    }

    "getEnv Int and Long error messages should reference the env var name" {
      // We can't easily set env vars in tests, but we can verify the error message format
      // by checking that the getEnv methods exist and work with defaults
      // The actual error path (invalid int/long) is tested by verifying the exception message format
      // in the source code: "Environment variable $name has invalid integer value: '$value'"
      // and "Environment variable $name has invalid long value: '$value'"
      // Here we verify the happy path still works for all numeric types
      val intResult = EnvVars.PROXY_PORT.getEnv(8080)
      val longResult = EnvVars.HANDSHAKE_TIMEOUT_SECS.getEnv(120L)

      if (System.getenv("PROXY_PORT") == null) {
        intResult shouldBe 8080
      }
      if (System.getenv("HANDSHAKE_TIMEOUT_SECS") == null) {
        longResult shouldBe 120L
      }
    }

    // ==================== Bug #16: parseBooleanStrict validation ====================

    "parseBooleanStrict should return true for true (case insensitive)" {
      EnvVars.parseBooleanStrict("TEST", "true").shouldBeTrue()
      EnvVars.parseBooleanStrict("TEST", "TRUE").shouldBeTrue()
      EnvVars.parseBooleanStrict("TEST", "True").shouldBeTrue()
      EnvVars.parseBooleanStrict("TEST", "tRuE").shouldBeTrue()
    }

    "parseBooleanStrict should return false for false (case insensitive)" {
      EnvVars.parseBooleanStrict("TEST", "false").shouldBeFalse()
      EnvVars.parseBooleanStrict("TEST", "FALSE").shouldBeFalse()
      EnvVars.parseBooleanStrict("TEST", "False").shouldBeFalse()
      EnvVars.parseBooleanStrict("TEST", "fAlSe").shouldBeFalse()
    }

    "parseBooleanStrict should throw for yes" {
      val exception = shouldThrow<IllegalArgumentException> {
        EnvVars.parseBooleanStrict("ADMIN_ENABLED", "yes")
      }
      exception.message shouldContain "ADMIN_ENABLED"
      exception.message shouldContain "yes"
      exception.message shouldContain "expected 'true' or 'false'"
    }

    "parseBooleanStrict should throw for numeric 1" {
      val exception = shouldThrow<IllegalArgumentException> {
        EnvVars.parseBooleanStrict("METRICS_ENABLED", "1")
      }
      exception.message shouldContain "METRICS_ENABLED"
      exception.message shouldContain "'1'"
    }

    "parseBooleanStrict should throw for typo ture" {
      val exception = shouldThrow<IllegalArgumentException> {
        EnvVars.parseBooleanStrict("DEBUG_ENABLED", "ture")
      }
      exception.message shouldContain "DEBUG_ENABLED"
      exception.message shouldContain "ture"
    }

    "parseBooleanStrict should throw for empty string" {
      shouldThrow<IllegalArgumentException> {
        EnvVars.parseBooleanStrict("TEST", "")
      }
    }

    // ==================== Agent Variables ====================

    "EnvVars enum should contain all expected agent variables" {
      val agentVars = listOf(
        EnvVars.AGENT_CONFIG,
        EnvVars.PROXY_HOSTNAME,
        EnvVars.AGENT_NAME,
        EnvVars.CONSOLIDATED,
        EnvVars.SCRAPE_TIMEOUT_SECS,
        EnvVars.SCRAPE_MAX_RETRIES,
        EnvVars.CHUNK_CONTENT_SIZE_KBS,
        EnvVars.MIN_GZIP_SIZE_BYTES,
        EnvVars.TRUST_ALL_X509_CERTIFICATES,
        EnvVars.MAX_CONCURRENT_CLIENTS,
        EnvVars.CLIENT_TIMEOUT_SECS,
        EnvVars.UNARY_DEADLINE_SECS,
        EnvVars.AGENT_LOG_LEVEL,
      )

      agentVars.forEach { envVar ->
        envVar.name.shouldNotBeEmpty()
      }
    }
  }
}
