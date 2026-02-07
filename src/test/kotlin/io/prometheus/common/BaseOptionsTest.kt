@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.prometheus.agent.AgentOptions
import io.prometheus.proxy.ProxyOptions
import org.junit.jupiter.api.Test

class BaseOptionsTest {
  // ==================== Shared Option Defaults (via ProxyOptions) ====================

  @Test
  fun `adminEnabled should default to false`() {
    val options = ProxyOptions(listOf())
    options.adminEnabled.shouldBeFalse()
  }

  @Test
  fun `metricsEnabled should default to false`() {
    val options = ProxyOptions(listOf())
    options.metricsEnabled.shouldBeFalse()
  }

  @Test
  fun `debugEnabled should default to false`() {
    val options = ProxyOptions(listOf())
    options.debugEnabled.shouldBeFalse()
  }

  @Test
  fun `transportFilterDisabled should default to false`() {
    val options = ProxyOptions(listOf())
    options.transportFilterDisabled.shouldBeFalse()
  }

  @Test
  fun `certChainFilePath should default to empty`() {
    val options = ProxyOptions(listOf())
    options.certChainFilePath.shouldBeEmpty()
  }

  @Test
  fun `privateKeyFilePath should default to empty`() {
    val options = ProxyOptions(listOf())
    options.privateKeyFilePath.shouldBeEmpty()
  }

  @Test
  fun `trustCertCollectionFilePath should default to empty`() {
    val options = ProxyOptions(listOf())
    options.trustCertCollectionFilePath.shouldBeEmpty()
  }

  @Test
  fun `logLevel should default to empty`() {
    val options = ProxyOptions(listOf())
    options.logLevel.shouldBeEmpty()
  }

  // ==================== Admin Option Overrides ====================

  @Test
  fun `adminEnabled should be settable via -r flag`() {
    val options = ProxyOptions(listOf("-r"))
    options.adminEnabled shouldBe true
  }

  @Test
  fun `adminPort should be settable via -i flag`() {
    val options = ProxyOptions(listOf("-i", "9000"))
    options.adminPort shouldBe 9000
  }

  @Test
  fun `metricsEnabled should be settable via -e flag`() {
    val options = ProxyOptions(listOf("-e"))
    options.metricsEnabled shouldBe true
  }

  @Test
  fun `metricsPort should be settable via -m flag`() {
    val options = ProxyOptions(listOf("-m", "9100"))
    options.metricsPort shouldBe 9100
  }

  @Test
  fun `debugEnabled should be settable via -b flag`() {
    val options = ProxyOptions(listOf("-b"))
    options.debugEnabled shouldBe true
  }

  // ==================== Transport Filter and TLS ====================

  @Test
  fun `transportFilterDisabled should be settable via --tf_disabled`() {
    val options = ProxyOptions(listOf("--tf_disabled"))
    options.transportFilterDisabled shouldBe true
  }

  @Test
  fun `transportFilterDisabled should accept hyphenated variant --tf-disabled`() {
    val options = ProxyOptions(listOf("--tf-disabled"))
    options.transportFilterDisabled shouldBe true
  }

  @Test
  fun `certChainFilePath should be settable via -t flag`() {
    val options = ProxyOptions(listOf("-t", "/path/to/cert.pem"))
    options.certChainFilePath shouldBe "/path/to/cert.pem"
  }

  @Test
  fun `privateKeyFilePath should be settable via -k flag`() {
    val options = ProxyOptions(listOf("-k", "/path/to/key.pem"))
    options.privateKeyFilePath shouldBe "/path/to/key.pem"
  }

  @Test
  fun `trustCertCollectionFilePath should be settable via -s flag`() {
    val options = ProxyOptions(listOf("-s", "/path/to/trust.pem"))
    options.trustCertCollectionFilePath shouldBe "/path/to/trust.pem"
  }

  // ==================== KeepAlive Settings ====================

  @Test
  fun `keepAliveTimeSecs should be settable via --keepalive_time_secs`() {
    val options = ProxyOptions(listOf("--keepalive_time_secs", "600"))
    options.keepAliveTimeSecs shouldBe 600L
  }

  @Test
  fun `keepAliveTimeoutSecs should be settable via --keepalive_timeout_secs`() {
    val options = ProxyOptions(listOf("--keepalive_timeout_secs", "30"))
    options.keepAliveTimeoutSecs shouldBe 30L
  }

  // ==================== Dynamic Parameters ====================

  @Test
  fun `dynamic params should be empty by default`() {
    val options = ProxyOptions(listOf())
    options.dynamicParams.size shouldBe 0
  }

  @Test
  fun `dynamic params should capture -D values`() {
    val options = ProxyOptions(listOf("-Dproxy.http.port=9999"))
    options.dynamicParams.size shouldBe 1
  }

  // ==================== Shared Defaults via AgentOptions ====================

  @Test
  fun `agent adminEnabled should default to false`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.adminEnabled.shouldBeFalse()
  }

  @Test
  fun `agent metricsEnabled should default to false`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.metricsEnabled.shouldBeFalse()
  }

  @Test
  fun `agent debugEnabled should default to false`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.debugEnabled.shouldBeFalse()
  }

  @Test
  fun `agent logLevel should be settable via --log_level`() {
    val options = AgentOptions(
      listOf("--name", "test", "--proxy", "host", "--log_level", "DEBUG"),
      false,
    )
    options.logLevel shouldBe "DEBUG"
  }
}
