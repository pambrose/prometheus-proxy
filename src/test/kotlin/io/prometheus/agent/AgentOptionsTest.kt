/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class AgentOptionsTest : StringSpec() {
  init {
    // ==================== Default Value Tests ====================

    "default proxyHostname should be set from config" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "myhost:1234"), false)
      options.proxyHostname shouldBe "myhost:1234"
    }

    "default agentName should be overridable via command line" {
      val options = AgentOptions(listOf("--name", "custom-agent", "--proxy", "host"), false)
      options.agentName shouldBe "custom-agent"
    }

    "consolidated should default to false" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.consolidated.shouldBeFalse()
    }

    "consolidated should be settable via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "-o"), false)
      options.consolidated.shouldBeTrue()
    }

    // ==================== Scrape Configuration Tests ====================

    "scrapeTimeoutSecs should be settable via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--timeout", "45"), false)
      options.scrapeTimeoutSecs shouldBe 45
    }

    "scrapeMaxRetries should be settable via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--max_retries", "3"), false)
      options.scrapeMaxRetries shouldBe 3
    }

    // ==================== Chunk and Gzip Tests ====================

    "chunkContentSizeBytes should be multiplied by 1024" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--chunk", "32"), false)
      options.chunkContentSizeBytes shouldBe 32 * 1024
    }

    "minGzipSizeBytes should be settable via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--gzip", "2048"), false)
      options.minGzipSizeBytes shouldBe 2048
    }

    // ==================== HTTP Client Tests ====================

    "trustAllX509Certificates should default to false" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.trustAllX509Certificates.shouldBeFalse()
    }

    "trustAllX509Certificates should be settable via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--trust_all_x509"), false)
      options.trustAllX509Certificates.shouldBeTrue()
    }

    "maxConcurrentHttpClients should have positive default" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxConcurrentHttpClients shouldBeGreaterThan 0
    }

    "httpClientTimeoutSecs should have positive default" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.httpClientTimeoutSecs shouldBeGreaterThan 0
    }

    // ==================== Cache Settings Tests ====================

    "maxCacheSize should have valid default" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxCacheSize shouldBeGreaterThan 0
    }

    "maxCacheAgeMins should have valid default" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxCacheAgeMins shouldBeGreaterThan 0
    }

    "maxCacheIdleMins should have valid default" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxCacheIdleMins shouldBeGreaterThan 0
    }

    "cacheCleanupIntervalMins should have valid default" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.cacheCleanupIntervalMins shouldBeGreaterThan 0
    }

    // ==================== gRPC Settings Tests ====================

    "keepAliveWithoutCalls should default to false" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.keepAliveWithoutCalls.shouldBeFalse()
    }

    "keepAliveWithoutCalls should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--keepalive_without_calls"),
        false,
      )
      options.keepAliveWithoutCalls.shouldBeTrue()
    }

    "unaryDeadlineSecs should have positive default" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.unaryDeadlineSecs shouldBeGreaterThan 0
    }

    "unaryDeadlineSecs should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--unary_deadline_secs", "60"),
        false,
      )
      options.unaryDeadlineSecs shouldBe 60
    }

    // ==================== Constructor Variants ====================

    "list constructor should work" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.agentName shouldBe "test"
    }

    "configVals should be populated after construction" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.configVals.agent.name.shouldNotBeNull()
    }

    // ==================== Override Authority Tests ====================

    "overrideAuthority should default to empty" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.overrideAuthority shouldBe ""
    }

    "overrideAuthority should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--override", "my-authority"),
        false,
      )
      options.overrideAuthority shouldBe "my-authority"
    }

    // ==================== CLI Args for HTTP Client and Cache ====================

    "maxConcurrentHttpClients should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_concurrent_clients", "25"),
        false,
      )
      options.maxConcurrentHttpClients shouldBe 25
    }

    "httpClientTimeoutSecs should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--client_timeout_secs", "120"),
        false,
      )
      options.httpClientTimeoutSecs shouldBe 120
    }

    "maxCacheSize should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_size", "200"),
        false,
      )
      options.maxCacheSize shouldBe 200
    }

    "maxCacheAgeMins should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_age_mins", "60"),
        false,
      )
      options.maxCacheAgeMins shouldBe 60
    }

    "maxCacheIdleMins should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_idle_mins", "20"),
        false,
      )
      options.maxCacheIdleMins shouldBe 20
    }

    "cacheCleanupIntervalMins should be settable via command line" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--cache_cleanup_interval_mins", "15"),
        false,
      )
      options.cacheCleanupIntervalMins shouldBe 15
    }

    // ==================== Validation Tests ====================

    "maxConcurrentHttpClients of 0 should throw IllegalArgumentException" {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--max_concurrent_clients", "0"),
          false,
        )
      }
    }

    "httpClientTimeoutSecs of 0 should throw IllegalArgumentException" {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--client_timeout_secs", "0"),
          false,
        )
      }
    }

    // Bug #5: Cache validations used > 1 instead of > 0, rejecting the valid value of 1.
    // These tests verify that 1 is now accepted and 0 is still rejected.

    "maxCacheSize of 1 should be accepted" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_size", "1"),
        false,
      )
      options.maxCacheSize shouldBe 1
    }

    "maxCacheSize of 0 should throw IllegalArgumentException" {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--max_cache_size", "0"),
          false,
        )
      }
    }

    "maxCacheAgeMins of 1 should be accepted" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_age_mins", "1"),
        false,
      )
      options.maxCacheAgeMins shouldBe 1
    }

    "maxCacheAgeMins of 0 should throw IllegalArgumentException" {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--max_cache_age_mins", "0"),
          false,
        )
      }
    }

    "maxCacheIdleMins of 1 should be accepted" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_idle_mins", "1"),
        false,
      )
      options.maxCacheIdleMins shouldBe 1
    }

    "maxCacheIdleMins of 0 should throw IllegalArgumentException" {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--max_cache_idle_mins", "0"),
          false,
        )
      }
    }

    "cacheCleanupIntervalMins of 1 should be accepted" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--cache_cleanup_interval_mins", "1"),
        false,
      )
      options.cacheCleanupIntervalMins shouldBe 1
    }

    "cacheCleanupIntervalMins of 0 should throw IllegalArgumentException" {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--cache_cleanup_interval_mins", "0"),
          false,
        )
      }
    }

    // ==================== Bug #9: chunkContentSizeBytes overflow protection ====================

    // Bug #9: The *= 1024 multiplication was applied unconditionally with no overflow
    // protection. For large KB values (e.g., 2097152), the multiplication overflows
    // Int.MAX_VALUE silently, producing a negative or incorrect value. The fix validates
    // the KB value is positive and checks for overflow before the conversion.

    "Bug #9: chunkContentSizeBytes should correctly convert KB to bytes" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--chunk", "64"),
        false,
      )
      options.chunkContentSizeBytes shouldBe 64 * 1024
    }

    "Bug #9: chunkContentSizeBytes of 1 KB should be accepted" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--chunk", "1"),
        false,
      )
      options.chunkContentSizeBytes shouldBe 1024
    }

    "Bug #9: chunkContentSizeBytes at max safe KB value should be accepted" {
      // Int.MAX_VALUE / 1024 = 2097151
      val maxSafeKb = (Int.MAX_VALUE / 1024).toString()
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--chunk", maxSafeKb),
        false,
      )
      options.chunkContentSizeBytes shouldBe (Int.MAX_VALUE / 1024) * 1024
    }

    "Bug #9: chunkContentSizeBytes exceeding max safe KB should throw" {
      // Int.MAX_VALUE / 1024 + 1 = 2097152, which overflows when multiplied by 1024
      val overflowKb = (Int.MAX_VALUE / 1024 + 1).toString()
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--chunk", overflowKb),
          false,
        )
      }
    }

    "Bug #9: chunkContentSizeBytes of 0 should throw" {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--chunk", "0"),
          false,
        )
      }
    }
  }
}
