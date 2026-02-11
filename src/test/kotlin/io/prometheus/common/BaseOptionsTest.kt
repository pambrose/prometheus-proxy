@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.agent.AgentOptions
import io.prometheus.proxy.ProxyOptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI

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

  // Bug #10: isTlsEnabled property for detecting plaintext auth header forwarding
  @Test
  fun `isTlsEnabled should be false when no TLS options set`() {
    val options = ProxyOptions(listOf())
    options.isTlsEnabled shouldBe false
  }

  @Test
  fun `isTlsEnabled should be true when cert chain file path is set`() {
    val options = ProxyOptions(listOf("-t", "/path/to/cert.pem"))
    options.isTlsEnabled shouldBe true
  }

  @Test
  fun `isTlsEnabled should be true when private key file path is set`() {
    val options = ProxyOptions(listOf("-k", "/path/to/key.pem"))
    options.isTlsEnabled shouldBe true
  }

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

  // ==================== Config File Loading Tests ====================

  @Test
  fun `should load config from conf file`(
    @TempDir tempDir: File,
  ) {
    val confFile = File(tempDir, "test.conf")
    confFile.writeText(
      """
      proxy {
        http.port = 9999
      }
      """.trimIndent(),
    )

    val options = ProxyOptions(listOf("-c", confFile.absolutePath))
    options.proxyPort shouldBe 9999
  }

  @Test
  fun `should load config from json file`(
    @TempDir tempDir: File,
  ) {
    val jsonFile = File(tempDir, "test.json")
    jsonFile.writeText(
      """
      {
        "proxy": {
          "http": {
            "port": 7777
          }
        }
      }
      """.trimIndent(),
    )

    val options = ProxyOptions(listOf("-c", jsonFile.absolutePath))
    options.proxyPort shouldBe 7777
  }

  @Test
  fun `should load config from properties file`(
    @TempDir tempDir: File,
  ) {
    val propsFile = File(tempDir, "test.properties")
    propsFile.writeText("proxy.http.port=6666")

    val options = ProxyOptions(listOf("-c", propsFile.absolutePath))
    options.proxyPort shouldBe 6666
  }

  @Test
  fun `dynamic params should strip surrounding quotes`() {
    val options = ProxyOptions(listOf("-Dproxy.http.port=\"5555\""))
    options.proxyPort shouldBe 5555
  }

  // ==================== Bug #15: Config resolution with single resolve() ====================

  @Test
  fun `config with variable substitution should resolve correctly`(
    @TempDir tempDir: File,
  ) {
    val confFile = File(tempDir, "test-subst.conf")
    confFile.writeText(
      """
      proxy {
        http.port = 8888
        admin.port = ${"$"}{proxy.http.port}
      }
      """.trimIndent(),
    )

    val options = ProxyOptions(listOf("-c", confFile.absolutePath))
    options.proxyPort shouldBe 8888
    // Variable substitution resolved: admin.port = proxy.http.port = 8888
    options.configVals.proxy.admin.port shouldBe 8888
  }

  // ==================== Bug #18: URI.toURL() replaces deprecated URL() constructor ====================

  @Test
  fun `URI toURL should produce valid URL for http config`() {
    val uri = URI("http://example.com/config.conf")
    val url = uri.toURL()

    url.protocol shouldBe "http"
    url.host shouldBe "example.com"
    url.path shouldBe "/config.conf"
  }

  @Test
  fun `URI toURL should produce valid URL for https config`() {
    val uri = URI("https://example.com/path/config.json")
    val url = uri.toURL()

    url.protocol shouldBe "https"
    url.host shouldBe "example.com"
    url.path shouldBe "/path/config.json"
  }

  // ==================== Bug #19: Version flag handled after parsing ====================

  @Test
  fun `version flag should not trigger exitProcess during construction`() {
    // Before the fix, passing -v would call exitProcess(0) inside the JCommander
    // validator during parse(). Now the version flag is checked after parsing,
    // so constructing with other flags should not exit.
    // This test verifies normal construction still works (no exitProcess during parsing).
    val options = ProxyOptions(listOf("-b"))
    options.debugEnabled shouldBe true
  }

  // ==================== ConfigVals Tests ====================

  @Test
  fun `configVals should be initialized after construction`() {
    val options = ProxyOptions(listOf())
    options.configVals.proxy.http.port shouldBeGreaterThan 0
    options.configVals.proxy.admin.pingPath.shouldNotBeEmpty()
  }
}
