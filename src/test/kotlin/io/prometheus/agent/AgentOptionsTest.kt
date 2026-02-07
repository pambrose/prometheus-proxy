@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentOptionsTest {
  // ==================== Default Value Tests ====================

  @Test
  fun `default proxyHostname should be set from config`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "myhost:1234"), false)
    options.proxyHostname shouldBe "myhost:1234"
  }

  @Test
  fun `default agentName should be overridable via command line`() {
    val options = AgentOptions(listOf("--name", "custom-agent", "--proxy", "host"), false)
    options.agentName shouldBe "custom-agent"
  }

  @Test
  fun `consolidated should default to false`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.consolidated.shouldBeFalse()
  }

  @Test
  fun `consolidated should be settable via command line`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "-o"), false)
    options.consolidated.shouldBeTrue()
  }

  // ==================== Scrape Configuration Tests ====================

  @Test
  fun `scrapeTimeoutSecs should be settable via command line`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--timeout", "45"), false)
    options.scrapeTimeoutSecs shouldBe 45
  }

  @Test
  fun `scrapeMaxRetries should be settable via command line`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--max_retries", "3"), false)
    options.scrapeMaxRetries shouldBe 3
  }

  // ==================== Chunk and Gzip Tests ====================

  @Test
  fun `chunkContentSizeBytes should be multiplied by 1024`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--chunk", "32"), false)
    options.chunkContentSizeBytes shouldBe 32 * 1024
  }

  @Test
  fun `minGzipSizeBytes should be settable via command line`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--gzip", "2048"), false)
    options.minGzipSizeBytes shouldBe 2048
  }

  // ==================== HTTP Client Tests ====================

  @Test
  fun `trustAllX509Certificates should default to false`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.trustAllX509Certificates.shouldBeFalse()
  }

  @Test
  fun `trustAllX509Certificates should be settable via command line`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--trust_all_x509"), false)
    options.trustAllX509Certificates.shouldBeTrue()
  }

  @Test
  fun `maxConcurrentHttpClients should have positive default`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.maxConcurrentHttpClients shouldBeGreaterThan 0
  }

  @Test
  fun `httpClientTimeoutSecs should have positive default`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.httpClientTimeoutSecs shouldBeGreaterThan 0
  }

  // ==================== Cache Settings Tests ====================

  @Test
  fun `maxCacheSize should have valid default`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.maxCacheSize shouldBeGreaterThan 1
  }

  @Test
  fun `maxCacheAgeMins should have valid default`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.maxCacheAgeMins shouldBeGreaterThan 1
  }

  @Test
  fun `maxCacheIdleMins should have valid default`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.maxCacheIdleMins shouldBeGreaterThan 1
  }

  @Test
  fun `cacheCleanupIntervalMins should have valid default`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.cacheCleanupIntervalMins shouldBeGreaterThan 1
  }

  // ==================== gRPC Settings Tests ====================

  @Test
  fun `keepAliveWithoutCalls should default to false`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.keepAliveWithoutCalls.shouldBeFalse()
  }

  @Test
  fun `keepAliveWithoutCalls should be settable via command line`() {
    val options = AgentOptions(
      listOf("--name", "test", "--proxy", "host", "--keepalive_without_calls"),
      false,
    )
    options.keepAliveWithoutCalls.shouldBeTrue()
  }

  // ==================== Constructor Variants ====================

  @Test
  fun `list constructor should work`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.agentName shouldBe "test"
  }

  @Test
  fun `configVals should be populated after construction`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.configVals.agent.name shouldBe options.configVals.agent.name // non-null access
  }

  // ==================== Override Authority Tests ====================

  @Test
  fun `overrideAuthority should default to empty`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.overrideAuthority shouldBe ""
  }

  @Test
  fun `overrideAuthority should be settable via command line`() {
    val options = AgentOptions(
      listOf("--name", "test", "--proxy", "host", "--override", "my-authority"),
      false,
    )
    options.overrideAuthority shouldBe "my-authority"
  }
}
