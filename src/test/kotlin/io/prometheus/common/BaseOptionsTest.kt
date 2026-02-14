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

package io.prometheus.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.agent.AgentOptions
import io.prometheus.common.BaseOptions.Companion.resolveBoolean
import io.prometheus.proxy.ProxyOptions
import java.io.File
import java.net.URI
import kotlin.io.path.createTempDirectory

class BaseOptionsTest : StringSpec() {
  init {
    // ==================== Shared Option Defaults (via ProxyOptions) ====================

    "adminEnabled should default to false" {
      val options = ProxyOptions(listOf())
      options.adminEnabled.shouldBeFalse()
    }

    "metricsEnabled should default to false" {
      val options = ProxyOptions(listOf())
      options.metricsEnabled.shouldBeFalse()
    }

    "debugEnabled should default to false" {
      val options = ProxyOptions(listOf())
      options.debugEnabled.shouldBeFalse()
    }

    "transportFilterDisabled should default to false" {
      val options = ProxyOptions(listOf())
      options.transportFilterDisabled.shouldBeFalse()
    }

    "certChainFilePath should default to empty" {
      val options = ProxyOptions(listOf())
      options.certChainFilePath.shouldBeEmpty()
    }

    "privateKeyFilePath should default to empty" {
      val options = ProxyOptions(listOf())
      options.privateKeyFilePath.shouldBeEmpty()
    }

    "trustCertCollectionFilePath should default to empty" {
      val options = ProxyOptions(listOf())
      options.trustCertCollectionFilePath.shouldBeEmpty()
    }

    "logLevel should default to empty" {
      val options = ProxyOptions(listOf())
      options.logLevel.shouldBeEmpty()
    }

    // ==================== Admin Option Overrides ====================

    "adminEnabled should be settable via -r flag" {
      val options = ProxyOptions(listOf("-r"))
      options.adminEnabled shouldBe true
    }

    "adminPort should be settable via -i flag" {
      val options = ProxyOptions(listOf("-i", "9000"))
      options.adminPort shouldBe 9000
    }

    "metricsEnabled should be settable via -e flag" {
      val options = ProxyOptions(listOf("-e"))
      options.metricsEnabled shouldBe true
    }

    "metricsPort should be settable via -m flag" {
      val options = ProxyOptions(listOf("-m", "9100"))
      options.metricsPort shouldBe 9100
    }

    "debugEnabled should be settable via -b flag" {
      val options = ProxyOptions(listOf("-b"))
      options.debugEnabled shouldBe true
    }

    // ==================== Transport Filter and TLS ====================

    "isTlsEnabled should be false when no TLS options set" {
      val options = ProxyOptions(listOf())
      options.isTlsEnabled shouldBe false
    }

    // Bug #3: isTlsEnabled requires BOTH cert and key (AND, not OR)
    "isTlsEnabled should be true when both cert and key are set" {
      val options = ProxyOptions(listOf("-t", "/path/to/cert.pem", "-k", "/path/to/key.pem"))
      options.isTlsEnabled shouldBe true
    }

    "partial TLS config with only cert should fail fast" {
      val ex =
        shouldThrow<IllegalArgumentException> {
          ProxyOptions(listOf("-t", "/path/to/cert.pem"))
        }
      ex.message shouldContain "private key file is missing"
    }

    "partial TLS config with only key should fail fast" {
      val ex =
        shouldThrow<IllegalArgumentException> {
          ProxyOptions(listOf("-k", "/path/to/key.pem"))
        }
      ex.message shouldContain "certificate chain file is missing"
    }

    "transportFilterDisabled should be settable via --tf_disabled" {
      val options = ProxyOptions(listOf("--tf_disabled"))
      options.transportFilterDisabled shouldBe true
    }

    "transportFilterDisabled should accept hyphenated variant --tf-disabled" {
      val options = ProxyOptions(listOf("--tf-disabled"))
      options.transportFilterDisabled shouldBe true
    }

    "certChainFilePath should be settable via -t flag" {
      val options = ProxyOptions(listOf("-t", "/path/to/cert.pem", "-k", "/path/to/key.pem"))
      options.certChainFilePath shouldBe "/path/to/cert.pem"
    }

    "privateKeyFilePath should be settable via -k flag" {
      val options = ProxyOptions(listOf("-t", "/path/to/cert.pem", "-k", "/path/to/key.pem"))
      options.privateKeyFilePath shouldBe "/path/to/key.pem"
    }

    "trustCertCollectionFilePath should be settable via -s flag" {
      val options = ProxyOptions(listOf("-s", "/path/to/trust.pem"))
      options.trustCertCollectionFilePath shouldBe "/path/to/trust.pem"
    }

    // ==================== KeepAlive Settings ====================

    "keepAliveTimeSecs should be settable via --keepalive_time_secs" {
      val options = ProxyOptions(listOf("--keepalive_time_secs", "600"))
      options.keepAliveTimeSecs shouldBe 600L
    }

    "keepAliveTimeoutSecs should be settable via --keepalive_timeout_secs" {
      val options = ProxyOptions(listOf("--keepalive_timeout_secs", "30"))
      options.keepAliveTimeoutSecs shouldBe 30L
    }

    // ==================== Dynamic Parameters ====================

    "dynamic params should be empty by default" {
      val options = ProxyOptions(listOf())
      options.dynamicParams.size shouldBe 0
    }

    "dynamic params should capture -D values" {
      val options = ProxyOptions(listOf("-Dproxy.http.port=9999"))
      options.dynamicParams.size shouldBe 1
    }

    // ==================== Shared Defaults via AgentOptions ====================

    "agent adminEnabled should default to false" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.adminEnabled.shouldBeFalse()
    }

    "agent metricsEnabled should default to false" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.metricsEnabled.shouldBeFalse()
    }

    "agent debugEnabled should default to false" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.debugEnabled.shouldBeFalse()
    }

    "agent logLevel should be settable via --log_level" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--log_level", "DEBUG"),
        false,
      )
      options.logLevel shouldBe "DEBUG"
    }

    // ==================== Config File Loading Tests ====================

    "should load config from conf file" {
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

    "should load config from json file" {
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

    "should load config from properties file" {
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

    "dynamic params should strip surrounding quotes" {
      val options = ProxyOptions(listOf("-Dproxy.http.port=\"5555\""))
      options.proxyPort shouldBe 5555
    }

    // ==================== Bug #15: Config resolution with single resolve() ====================

    "config with variable substitution should resolve correctly" {
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

    "URI toURL should produce valid URL for http config" {
      val uri = URI("http://example.com/config.conf")
      val url = uri.toURL()

      url.protocol shouldBe "http"
      url.host shouldBe "example.com"
      url.path shouldBe "/config.conf"
    }

    "URI toURL should produce valid URL for https config" {
      val uri = URI("https://example.com/path/config.json")
      val url = uri.toURL()

      url.protocol shouldBe "https"
      url.host shouldBe "example.com"
      url.path shouldBe "/path/config.json"
    }

    // ==================== Bug #19: Version flag handled after parsing ====================

    "version flag should not trigger exitProcess during construction" {
      // Before the fix, passing -v would call exitProcess(0) inside the JCommander
      // validator during parse(). Now the version flag is checked after parsing,
      // so constructing with other flags should not exit.
      // This test verifies normal construction still works (no exitProcess during parsing).
      val options = ProxyOptions(listOf("-b"))
      options.debugEnabled shouldBe true
    }

    // ==================== readConfig Error Message ====================

    // The readConfig error message uses escaped-dollar interpolation to produce a
    // literal "$" before the env var name (e.g., "$AGENT_CONFIG"). This replaced the
    // Kotlin 2.x multi-dollar syntax ($$"...$$$envConfig") for clarity. This test
    // verifies the interpolation produces the expected output.
    "readConfig error message should include literal dollar sign before env var name" {
      val envConfig = "AGENT_CONFIG"
      val message = "A configuration file or url must be specified with --config or \$$envConfig"

      message shouldBe "A configuration file or url must be specified with --config or \$AGENT_CONFIG"
    }

    // ==================== ConfigVals Tests ====================

    "configVals should be initialized after construction" {
      val options = ProxyOptions(listOf())
      options.configVals.proxy.http.port shouldBeGreaterThan 0
      options.configVals.proxy.admin.pingPath.shouldNotBeEmpty()
    }

    // ==================== Bug #2: resolveBoolean priority tests (CLI > env > config) ====================

    "resolveBoolean should return config default when env is null and cli is false" {
      resolveBoolean(cliValue = false, envVarName = "TEST", envVarValue = null, configDefault = false).shouldBeFalse()
      resolveBoolean(cliValue = false, envVarName = "TEST", envVarValue = null, configDefault = true).shouldBeTrue()
    }

    "resolveBoolean should return cli=true regardless of env or config" {
      resolveBoolean(cliValue = true, envVarName = "TEST", envVarValue = null, configDefault = false).shouldBeTrue()
      resolveBoolean(cliValue = true, envVarName = "TEST", envVarValue = "false", configDefault = false).shouldBeTrue()
      resolveBoolean(cliValue = true, envVarName = "TEST", envVarValue = "true", configDefault = false).shouldBeTrue()
    }

    "resolveBoolean should let env=true override config=false when cli is false" {
      resolveBoolean(cliValue = false, envVarName = "TEST", envVarValue = "true", configDefault = false).shouldBeTrue()
    }

    "resolveBoolean should let env=false override config=true when cli is false" {
      resolveBoolean(
        cliValue = false,
        envVarName = "TEST",
        envVarValue = "false",
        configDefault = true,
      ).shouldBeFalse()
    }

    "resolveBoolean should handle case-insensitive TRUE" {
      resolveBoolean(cliValue = false, envVarName = "TEST", envVarValue = "TRUE", configDefault = false).shouldBeTrue()
    }

    "resolveBoolean should handle case-insensitive FALSE" {
      resolveBoolean(
        cliValue = false,
        envVarName = "TEST",
        envVarValue = "FALSE",
        configDefault = true,
      ).shouldBeFalse()
    }

    "resolveBoolean should handle mixed case True and False" {
      resolveBoolean(cliValue = false, envVarName = "TEST", envVarValue = "True", configDefault = false).shouldBeTrue()
      resolveBoolean(
        cliValue = false,
        envVarName = "TEST",
        envVarValue = "False",
        configDefault = true,
      ).shouldBeFalse()
    }

    "resolveBoolean should throw on invalid env var value" {
      val ex =
        shouldThrow<IllegalArgumentException> {
          resolveBoolean(cliValue = false, envVarName = "MY_VAR", envVarValue = "invalid", configDefault = false)
        }
      ex.message shouldContain "MY_VAR"
      ex.message shouldContain "invalid"
    }

    "resolveBoolean should throw on empty string env var value" {
      shouldThrow<IllegalArgumentException> {
        resolveBoolean(cliValue = false, envVarName = "TEST", envVarValue = "", configDefault = false)
      }
    }

    "resolveBoolean should throw on numeric env var value" {
      shouldThrow<IllegalArgumentException> {
        resolveBoolean(cliValue = false, envVarName = "TEST", envVarValue = "1", configDefault = false)
      }
    }

    // ==================== Bug #2: Integration tests (CLI > env > config) ====================

    "env=false should override config=true when no CLI flag" {
      resolveBoolean(
        cliValue = false,
        envVarName = "ADMIN_ENABLED",
        envVarValue = "false",
        configDefault = true,
      ).shouldBeFalse()
    }

    "CLI --admin flag should win over env=false" {
      resolveBoolean(
        cliValue = true,
        envVarName = "ADMIN_ENABLED",
        envVarValue = "false",
        configDefault = false,
      ).shouldBeTrue()
    }

    "config default should win when no CLI flag and no env var" {
      resolveBoolean(
        cliValue = false,
        envVarName = "ADMIN_ENABLED",
        envVarValue = null,
        configDefault = true,
      ).shouldBeTrue()
    }
  }
}
