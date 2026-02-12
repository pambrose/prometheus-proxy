@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.agent.AgentOptions
import io.prometheus.proxy.ProxyOptions
import java.io.File
import java.net.URI
import kotlin.io.path.createTempDirectory

class BaseOptionsTest : FunSpec() {
  init {
    // ==================== Shared Option Defaults (via ProxyOptions) ====================

    test("adminEnabled should default to false") {
      val options = ProxyOptions(listOf())
      options.adminEnabled.shouldBeFalse()
    }

    test("metricsEnabled should default to false") {
      val options = ProxyOptions(listOf())
      options.metricsEnabled.shouldBeFalse()
    }

    test("debugEnabled should default to false") {
      val options = ProxyOptions(listOf())
      options.debugEnabled.shouldBeFalse()
    }

    test("transportFilterDisabled should default to false") {
      val options = ProxyOptions(listOf())
      options.transportFilterDisabled.shouldBeFalse()
    }

    test("certChainFilePath should default to empty") {
      val options = ProxyOptions(listOf())
      options.certChainFilePath.shouldBeEmpty()
    }

    test("privateKeyFilePath should default to empty") {
      val options = ProxyOptions(listOf())
      options.privateKeyFilePath.shouldBeEmpty()
    }

    test("trustCertCollectionFilePath should default to empty") {
      val options = ProxyOptions(listOf())
      options.trustCertCollectionFilePath.shouldBeEmpty()
    }

    test("logLevel should default to empty") {
      val options = ProxyOptions(listOf())
      options.logLevel.shouldBeEmpty()
    }

    // ==================== Admin Option Overrides ====================

    test("adminEnabled should be settable via -r flag") {
      val options = ProxyOptions(listOf("-r"))
      options.adminEnabled shouldBe true
    }

    test("adminPort should be settable via -i flag") {
      val options = ProxyOptions(listOf("-i", "9000"))
      options.adminPort shouldBe 9000
    }

    test("metricsEnabled should be settable via -e flag") {
      val options = ProxyOptions(listOf("-e"))
      options.metricsEnabled shouldBe true
    }

    test("metricsPort should be settable via -m flag") {
      val options = ProxyOptions(listOf("-m", "9100"))
      options.metricsPort shouldBe 9100
    }

    test("debugEnabled should be settable via -b flag") {
      val options = ProxyOptions(listOf("-b"))
      options.debugEnabled shouldBe true
    }

    // ==================== Transport Filter and TLS ====================

    // Bug #10: isTlsEnabled property for detecting plaintext auth header forwarding
    test("isTlsEnabled should be false when no TLS options set") {
      val options = ProxyOptions(listOf())
      options.isTlsEnabled shouldBe false
    }

    test("isTlsEnabled should be true when cert chain file path is set") {
      val options = ProxyOptions(listOf("-t", "/path/to/cert.pem"))
      options.isTlsEnabled shouldBe true
    }

    test("isTlsEnabled should be true when private key file path is set") {
      val options = ProxyOptions(listOf("-k", "/path/to/key.pem"))
      options.isTlsEnabled shouldBe true
    }

    test("transportFilterDisabled should be settable via --tf_disabled") {
      val options = ProxyOptions(listOf("--tf_disabled"))
      options.transportFilterDisabled shouldBe true
    }

    test("transportFilterDisabled should accept hyphenated variant --tf-disabled") {
      val options = ProxyOptions(listOf("--tf-disabled"))
      options.transportFilterDisabled shouldBe true
    }

    test("certChainFilePath should be settable via -t flag") {
      val options = ProxyOptions(listOf("-t", "/path/to/cert.pem"))
      options.certChainFilePath shouldBe "/path/to/cert.pem"
    }

    test("privateKeyFilePath should be settable via -k flag") {
      val options = ProxyOptions(listOf("-k", "/path/to/key.pem"))
      options.privateKeyFilePath shouldBe "/path/to/key.pem"
    }

    test("trustCertCollectionFilePath should be settable via -s flag") {
      val options = ProxyOptions(listOf("-s", "/path/to/trust.pem"))
      options.trustCertCollectionFilePath shouldBe "/path/to/trust.pem"
    }

    // ==================== KeepAlive Settings ====================

    test("keepAliveTimeSecs should be settable via --keepalive_time_secs") {
      val options = ProxyOptions(listOf("--keepalive_time_secs", "600"))
      options.keepAliveTimeSecs shouldBe 600L
    }

    test("keepAliveTimeoutSecs should be settable via --keepalive_timeout_secs") {
      val options = ProxyOptions(listOf("--keepalive_timeout_secs", "30"))
      options.keepAliveTimeoutSecs shouldBe 30L
    }

    // ==================== Dynamic Parameters ====================

    test("dynamic params should be empty by default") {
      val options = ProxyOptions(listOf())
      options.dynamicParams.size shouldBe 0
    }

    test("dynamic params should capture -D values") {
      val options = ProxyOptions(listOf("-Dproxy.http.port=9999"))
      options.dynamicParams.size shouldBe 1
    }

    // ==================== Shared Defaults via AgentOptions ====================

    test("agent adminEnabled should default to false") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.adminEnabled.shouldBeFalse()
    }

    test("agent metricsEnabled should default to false") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.metricsEnabled.shouldBeFalse()
    }

    test("agent debugEnabled should default to false") {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.debugEnabled.shouldBeFalse()
    }

    test("agent logLevel should be settable via --log_level") {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--log_level", "DEBUG"),
        false,
      )
      options.logLevel shouldBe "DEBUG"
    }

    // ==================== Config File Loading Tests ====================

    test("should load config from conf file") {
      val tempDir = createTempDirectory("base-options-test").toFile()
      try {
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
      } finally {
        tempDir.deleteRecursively()
      }
    }

    test("should load config from json file") {
      val tempDir = createTempDirectory("base-options-test").toFile()
      try {
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
      } finally {
        tempDir.deleteRecursively()
      }
    }

    test("should load config from properties file") {
      val tempDir = createTempDirectory("base-options-test").toFile()
      try {
        val propsFile = File(tempDir, "test.properties")
        propsFile.writeText("proxy.http.port=6666")

        val options = ProxyOptions(listOf("-c", propsFile.absolutePath))
        options.proxyPort shouldBe 6666
      } finally {
        tempDir.deleteRecursively()
      }
    }

    test("dynamic params should strip surrounding quotes") {
      val options = ProxyOptions(listOf("-Dproxy.http.port=\"5555\""))
      options.proxyPort shouldBe 5555
    }

    // ==================== Bug #15: Config resolution with single resolve() ====================

    test("config with variable substitution should resolve correctly") {
      val tempDir = createTempDirectory("base-options-test").toFile()
      try {
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
      } finally {
        tempDir.deleteRecursively()
      }
    }

    // ==================== Bug #18: URI.toURL() replaces deprecated URL() constructor ====================

    test("URI toURL should produce valid URL for http config") {
      val uri = URI("http://example.com/config.conf")
      val url = uri.toURL()

      url.protocol shouldBe "http"
      url.host shouldBe "example.com"
      url.path shouldBe "/config.conf"
    }

    test("URI toURL should produce valid URL for https config") {
      val uri = URI("https://example.com/path/config.json")
      val url = uri.toURL()

      url.protocol shouldBe "https"
      url.host shouldBe "example.com"
      url.path shouldBe "/path/config.json"
    }

    // ==================== Bug #19: Version flag handled after parsing ====================

    test("version flag should not trigger exitProcess during construction") {
      // Before the fix, passing -v would call exitProcess(0) inside the JCommander
      // validator during parse(). Now the version flag is checked after parsing,
      // so constructing with other flags should not exit.
      // This test verifies normal construction still works (no exitProcess during parsing).
      val options = ProxyOptions(listOf("-b"))
      options.debugEnabled shouldBe true
    }

    // ==================== ConfigVals Tests ====================

    test("configVals should be initialized after construction") {
      val options = ProxyOptions(listOf())
      options.configVals.proxy.http.port shouldBeGreaterThan 0
      options.configVals.proxy.admin.pingPath.shouldNotBeEmpty()
    }
  }
}
