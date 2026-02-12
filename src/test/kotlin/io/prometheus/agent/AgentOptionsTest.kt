@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class AgentOptionsTest : FunSpec() {
  init {
    // ==================== Default Value Tests ====================

    test("default proxyHostname should be set from config") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "myhost:1234"), false)
      options.proxyHostname shouldBe "myhost:1234"
    }

    test("default agentName should be overridable via command line") {
      val options = AgentOptions(listOf("--name", "custom-agent", "--proxy", "host"), false)
      options.agentName shouldBe "custom-agent"
    }

    test("consolidated should default to false") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.consolidated.shouldBeFalse()
    }

    test("consolidated should be settable via command line") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "-o"), false)
      options.consolidated.shouldBeTrue()
    }

    // ==================== Scrape Configuration Tests ====================

    test("scrapeTimeoutSecs should be settable via command line") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--timeout", "45"), false)
      options.scrapeTimeoutSecs shouldBe 45
    }

    test("scrapeMaxRetries should be settable via command line") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--max_retries", "3"), false)
      options.scrapeMaxRetries shouldBe 3
    }

    // ==================== Chunk and Gzip Tests ====================

    test("chunkContentSizeBytes should be multiplied by 1024") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--chunk", "32"), false)
      options.chunkContentSizeBytes shouldBe 32 * 1024
    }

    test("minGzipSizeBytes should be settable via command line") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--gzip", "2048"), false)
      options.minGzipSizeBytes shouldBe 2048
    }

    // ==================== HTTP Client Tests ====================

    test("trustAllX509Certificates should default to false") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.trustAllX509Certificates.shouldBeFalse()
    }

    test("trustAllX509Certificates should be settable via command line") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--trust_all_x509"), false)
      options.trustAllX509Certificates.shouldBeTrue()
    }

    test("maxConcurrentHttpClients should have positive default") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxConcurrentHttpClients shouldBeGreaterThan 0
    }

    test("httpClientTimeoutSecs should have positive default") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.httpClientTimeoutSecs shouldBeGreaterThan 0
    }

    // ==================== Cache Settings Tests ====================

    test("maxCacheSize should have valid default") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxCacheSize shouldBeGreaterThan 1
    }

    test("maxCacheAgeMins should have valid default") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxCacheAgeMins shouldBeGreaterThan 1
    }

    test("maxCacheIdleMins should have valid default") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxCacheIdleMins shouldBeGreaterThan 1
    }

    test("cacheCleanupIntervalMins should have valid default") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.cacheCleanupIntervalMins shouldBeGreaterThan 1
    }

    // ==================== gRPC Settings Tests ====================

    test("keepAliveWithoutCalls should default to false") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.keepAliveWithoutCalls.shouldBeFalse()
    }

    test("keepAliveWithoutCalls should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--keepalive_without_calls"),
        false,
      )
      options.keepAliveWithoutCalls.shouldBeTrue()
    }

    test("unaryDeadlineSecs should have positive default") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.unaryDeadlineSecs shouldBeGreaterThan 0
    }

    test("unaryDeadlineSecs should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--unary_deadline_secs", "60"),
        false,
      )
      options.unaryDeadlineSecs shouldBe 60
    }

    // ==================== Constructor Variants ====================

    test("list constructor should work") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.agentName shouldBe "test"
    }

    test("configVals should be populated after construction") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.configVals.agent.name.shouldNotBeNull()
    }

    // ==================== Override Authority Tests ====================

    test("overrideAuthority should default to empty") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.overrideAuthority shouldBe ""
    }

    test("overrideAuthority should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--override", "my-authority"),
        false,
      )
      options.overrideAuthority shouldBe "my-authority"
    }

    // ==================== CLI Args for HTTP Client and Cache ====================

    test("maxConcurrentHttpClients should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_concurrent_clients", "25"),
        false,
      )
      options.maxConcurrentHttpClients shouldBe 25
    }

    test("httpClientTimeoutSecs should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--client_timeout_secs", "120"),
        false,
      )
      options.httpClientTimeoutSecs shouldBe 120
    }

    test("maxCacheSize should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_size", "200"),
        false,
      )
      options.maxCacheSize shouldBe 200
    }

    test("maxCacheAgeMins should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_age_mins", "60"),
        false,
      )
      options.maxCacheAgeMins shouldBe 60
    }

    test("maxCacheIdleMins should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--max_cache_idle_mins", "20"),
        false,
      )
      options.maxCacheIdleMins shouldBe 20
    }

    test("cacheCleanupIntervalMins should be settable via command line") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--cache_cleanup_interval_mins", "15"),
        false,
      )
      options.cacheCleanupIntervalMins shouldBe 15
    }

    // ==================== Validation Tests ====================

    test("maxConcurrentHttpClients of 0 should throw IllegalArgumentException") {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--max_concurrent_clients", "0"),
          false,
        )
      }
    }

    test("httpClientTimeoutSecs of 0 should throw IllegalArgumentException") {
      shouldThrow<IllegalArgumentException> {
        AgentOptions(
          listOf("--name", "test", "--proxy", "host", "--client_timeout_secs", "0"),
          false,
        )
      }
    }
  }
}
