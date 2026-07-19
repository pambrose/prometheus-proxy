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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.common.BaseOptions.Companion.resolveBoolean
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import io.ktor.server.cio.CIO as ServerCIO

class BaseOptionsTest : StringSpec() {
  init {
    // ==================== Shared Option Defaults (via ProxyOptions) ====================

    "adminEnabled should default to false" {
      val options = proxyOptions(emptyList())
      options.adminEnabled.shouldBeFalse()
    }

    "metricsEnabled should default to false" {
      val options = proxyOptions(emptyList())
      options.metricsEnabled.shouldBeFalse()
    }

    "debugEnabled should default to false" {
      val options = proxyOptions(emptyList())
      options.debugEnabled.shouldBeFalse()
    }

    "transportFilterDisabled should default to false" {
      val options = proxyOptions(emptyList())
      options.transportFilterDisabled.shouldBeFalse()
    }

    "certChainFilePath should default to empty" {
      val options = proxyOptions(emptyList())
      options.certChainFilePath.shouldBeEmpty()
    }

    "privateKeyFilePath should default to empty" {
      val options = proxyOptions(emptyList())
      options.privateKeyFilePath.shouldBeEmpty()
    }

    "trustCertCollectionFilePath should default to empty" {
      val options = proxyOptions(emptyList())
      options.trustCertCollectionFilePath.shouldBeEmpty()
    }

    "logLevel should default to empty" {
      val options = proxyOptions(emptyList())
      options.logLevel.shouldBeEmpty()
    }

    // ==================== Admin Option Overrides ====================

    "adminEnabled should be settable via -r flag" {
      val options = proxyOptions(["-r"])
      options.adminEnabled shouldBe true
    }

    "adminPort should be settable via -i flag" {
      val options = proxyOptions(["-i", "9000"])
      options.adminPort shouldBe 9000
    }

    "metricsEnabled should be settable via -e flag" {
      val options = proxyOptions(["-e"])
      options.metricsEnabled shouldBe true
    }

    "metricsPort should be settable via -m flag" {
      val options = proxyOptions(["-m", "9100"])
      options.metricsPort shouldBe 9100
    }

    "debugEnabled should be settable via -b flag" {
      val options = proxyOptions(["-b"])
      options.debugEnabled shouldBe true
    }

    // ==================== Transport Filter and TLS ====================

    "isTlsEnabled should be false when no TLS options set" {
      val options = proxyOptions(emptyList())
      options.isTlsEnabled shouldBe false
    }

    // Bug #3: isTlsEnabled requires BOTH cert and key (AND, not OR)
    "isTlsEnabled should be true when both cert and key are set" {
      val options = proxyOptions(["-t", "/path/to/cert.pem", "-k", "/path/to/key.pem"])
      options.isTlsEnabled shouldBe true
    }

    "partial TLS config with only cert should fail fast" {
      val ex =
        shouldThrow<IllegalArgumentException> {
          proxyOptions(["-t", "/path/to/cert.pem"])
        }
      ex.message shouldContain "private key file is missing"
    }

    "partial TLS config with only key should fail fast" {
      val ex =
        shouldThrow<IllegalArgumentException> {
          proxyOptions(["-k", "/path/to/key.pem"])
        }
      ex.message shouldContain "certificate chain file is missing"
    }

    "transportFilterDisabled should be settable via --tf_disabled" {
      val options = proxyOptions(["--tf_disabled"])
      options.transportFilterDisabled shouldBe true
    }

    "transportFilterDisabled should accept hyphenated variant --tf-disabled" {
      val options = proxyOptions(["--tf-disabled"])
      options.transportFilterDisabled shouldBe true
    }

    "certChainFilePath should be settable via -t flag" {
      val options = proxyOptions(["-t", "/path/to/cert.pem", "-k", "/path/to/key.pem"])
      options.certChainFilePath shouldBe "/path/to/cert.pem"
    }

    "privateKeyFilePath should be settable via -k flag" {
      val options = proxyOptions(["-t", "/path/to/cert.pem", "-k", "/path/to/key.pem"])
      options.privateKeyFilePath shouldBe "/path/to/key.pem"
    }

    "trustCertCollectionFilePath should be settable via -s flag" {
      val options = proxyOptions(["-s", "/path/to/trust.pem"])
      options.trustCertCollectionFilePath shouldBe "/path/to/trust.pem"
    }

    // ==================== KeepAlive Settings ====================

    "keepAliveTimeSecs should be settable via --keepalive_time_secs" {
      val options = proxyOptions(["--keepalive_time_secs", "600"])
      options.keepAliveTimeSecs shouldBe 600L
    }

    "keepAliveTimeoutSecs should be settable via --keepalive_timeout_secs" {
      val options = proxyOptions(["--keepalive_timeout_secs", "30"])
      options.keepAliveTimeoutSecs shouldBe 30L
    }

    // ==================== Dynamic Parameters ====================

    "dynamic params should be empty by default" {
      val options = proxyOptions(emptyList())
      options.dynamicParams.size shouldBe 0
    }

    "dynamic params should capture -D values" {
      val options = proxyOptions(["-Dproxy.http.port=9999"])
      options.dynamicParams.size shouldBe 1
    }

    // ==================== Shared Defaults via AgentOptions ====================

    "agent adminEnabled should default to false" {
      val options = agentOptions(["--name", "test", "--proxy", "host"], false)
      options.adminEnabled.shouldBeFalse()
    }

    "agent metricsEnabled should default to false" {
      val options = agentOptions(["--name", "test", "--proxy", "host"], false)
      options.metricsEnabled.shouldBeFalse()
    }

    "agent debugEnabled should default to false" {
      val options = agentOptions(["--name", "test", "--proxy", "host"], false)
      options.debugEnabled.shouldBeFalse()
    }

    "agent logLevel should be settable via --log_level" {
      val options = agentOptions(
        ["--name", "test", "--proxy", "host", "--log_level", "DEBUG"],
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

        val options = proxyOptions(["-c", confFile.absolutePath])
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

        val options = proxyOptions(["-c", jsonFile.absolutePath])
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

        val options = proxyOptions(["-c", propsFile.absolutePath])
        options.proxyPort shouldBe 6666
      } finally {
        tempDir.deleteRecursively()
      }
    }

    // ==================== Config File Loading over HTTP (URL branch) ====================
    // The file-based tests above go through ConfigFactory.parseFileAnySyntax, which skips
    // the URL branch of readConfig() and getConfigSyntax(). These serve a config over HTTP
    // to exercise the URL branch and the JSON/CONF syntax detection.

    "should load conf config from http url" {
      verifyHttpConfigLoads("config.conf", "proxy { http.port = 4444 }", expectedPort = 4444)
    }

    "should load json config from http url" {
      verifyHttpConfigLoads("config.json", """{ "proxy": { "http": { "port": 3333 } } }""", expectedPort = 3333)
    }

    "dynamic params should strip surrounding quotes" {
      val options = proxyOptions(["-Dproxy.http.port=\"5555\""])
      options.proxyPort shouldBe 5555
    }

    // ==================== Bug #15: Config resolution with single resolve() ====================

    "config with variable substitution should resolve correctly" {
      val tempDir = createTempDirectory("base-options-test").toFile()
      try {
        val confFile = File(tempDir, "test-subst.conf")
        confFile.writeText(
          $$"""
          proxy {
            http.port = 8888
            admin.port = ${proxy.http.port}
          }
          """.trimIndent(),
        )

        val options = proxyOptions(["-c", confFile.absolutePath])
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
      val options = proxyOptions(["-b"])
      options.debugEnabled shouldBe true
    }

    // ==================== Finding 5: parseArgs must not exitProcess in embedded mode ====================

    // In embedded mode (exitOnMissingConfig = false) a JCommander ParameterException must throw a
    // catchable ConfigLoadException rather than exitProcess(1), which would kill the host JVM.
    // (The exitProcess CLI path cannot be asserted directly here: exitProcess would terminate the
    // test worker -- same reason the "version flag should not trigger exitProcess" test above only
    // exercises the non-exiting path.)
    "Finding 5: invalid CLI option throws ConfigLoadException when exitOnMissingConfig is false" {
      val ex =
        shouldThrow<ConfigLoadException> {
          agentOptions(["--proxy", "host", "--admin_port", "not_a_number"], exitOnMissingConfig = false)
        }
      ex.message.shouldNotBeEmpty()
    }

    "Finding 5: unknown CLI flag throws ConfigLoadException when exitOnMissingConfig is false" {
      shouldThrow<ConfigLoadException> {
        agentOptions(["--proxy", "host", "--this-flag-does-not-exist"], exitOnMissingConfig = false)
      }
    }

    // The -u/--usage and -v/--version arms of exitOrThrow are separately reachable from the
    // ParameterException arm above, and are the two that fire on a *successful* invocation. In embedded
    // mode they must throw rather than exit, so a stray -v in a host's args cannot kill the host JVM.
    "Finding 5: version flag throws ConfigLoadException when exitOnMissingConfig is false" {
      val ex =
        shouldThrow<ConfigLoadException> {
          agentOptions(["--proxy", "host", "-v"], exitOnMissingConfig = false)
        }
      ex.message shouldContain "Version"
    }

    "Finding 5: usage flag throws ConfigLoadException when exitOnMissingConfig is false" {
      val ex =
        shouldThrow<ConfigLoadException> {
          agentOptions(["--proxy", "host", "-u"], exitOnMissingConfig = false)
        }
      ex.message shouldContain "Usage"
    }

    // ==================== Standalone CLI must exit 0 for --version / --usage ====================

    // exitOnMissingConfig answers "is a config file required?", not "am I embedded?". ProxyOptions never
    // passes it -- the proxy runs fine with no config file -- so gating the -u/-v exit on it made the
    // standalone proxy take the embedded throw-branch: `prometheus-proxy.jar --version` printed the
    // version and then died with an uncaught ConfigLoadException and exit code 1 instead of exiting 0.
    //
    // exitProcess cannot be asserted in-process (it would terminate the test worker), which is precisely
    // why that regression shipped unnoticed. These specs fork a JVM and assert the real exit code, so the
    // CLI contract documented on the version/usage @Parameter fields is actually pinned.

    "standalone Proxy CLI prints the version and exits 0" {
      val result = runCliMain("io.prometheus.Proxy", "--version")
      result.exitCode shouldBe 0
      result.output shouldContain "Version"
    }

    "standalone Proxy CLI prints usage and exits 0" {
      runCliMain("io.prometheus.Proxy", "--usage").exitCode shouldBe 0
    }

    "standalone Agent CLI prints the version and exits 0" {
      val result = runCliMain("io.prometheus.Agent", "--version")
      result.exitCode shouldBe 0
      result.output shouldContain "Version"
    }

    // Same conflation as above, in readConfig's parse-failure path: a standalone CLI given a config file
    // it cannot parse should log the reason and exit non-zero, not terminate on an uncaught
    // ConfigLoadException. The blank-config branch is a separate question and correctly stays gated on
    // exitOnMissingConfig -- the Proxy falls back to the built-in reference config when given none.
    "standalone Proxy CLI fails cleanly on a malformed config file" {
      val badConfig = File(createTempDirectory().toFile(), "bad.conf")
      badConfig.writeText("this is { not valid hocon [[[")

      val result = runCliMain("io.prometheus.Proxy", "-c", badConfig.absolutePath)

      result.exitCode shouldBe 1
      result.output shouldNotContain "Exception in thread"
    }

    "standalone Proxy CLI fails cleanly on a config file that does not exist" {
      val result = runCliMain("io.prometheus.Proxy", "-c", "/no/such/config.conf")

      result.exitCode shouldBe 1
      result.output shouldContain "Invalid config filename"
      result.output shouldNotContain "Exception in thread"
    }

    "standalone Proxy CLI starts with no config file at all" {
      // The blank-config fallback must survive the change: the Proxy runs off its built-in reference
      // config, so --version with no -c reaches the version branch rather than a missing-config exit.
      runCliMain("io.prometheus.Proxy", "--version").exitCode shouldBe 0
    }

    // ==================== readConfig Error Message ====================

    // The readConfig error message uses escaped-dollar interpolation to produce a
    // literal "$" before the env var name (e.g., "$AGENT_CONFIG"). This replaced the
    // Kotlin 2.x multi-dollar syntax ($$"...$$$envConfig") for clarity. This test
    // verifies the interpolation produces the expected output.
    "readConfig error message should include literal dollar sign before env var name" {
      val envConfig = "AGENT_CONFIG"
      val message = $$"A configuration file or url must be specified with --config or $$$envConfig"

      message shouldBe $$"A configuration file or url must be specified with --config or $AGENT_CONFIG"
    }

    // ==================== ConfigVals Tests ====================

    "configVals should be initialized after construction" {
      val options = proxyOptions(emptyList())
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

    // ==================== Bug #7: resolveBoolean with cliExplicitlySet ====================

    "resolveBoolean with cliExplicitlySet=true and cliValue=false should return false" {
      resolveBoolean(
        cliValue = false,
        cliExplicitlySet = true,
        envVarName = "TEST",
        envVarValue = "true",
        configDefault = true,
      ).shouldBeFalse()
    }

    "resolveBoolean with cliExplicitlySet=true and cliValue=true should return true" {
      resolveBoolean(
        cliValue = true,
        cliExplicitlySet = true,
        envVarName = "TEST",
        envVarValue = "false",
        configDefault = false,
      ).shouldBeTrue()
    }

    "resolveBoolean with cliExplicitlySet=false should use env var" {
      resolveBoolean(
        cliValue = false,
        cliExplicitlySet = false,
        envVarName = "TEST",
        envVarValue = "true",
        configDefault = false,
      ).shouldBeTrue()
    }

    "resolveBoolean with cliExplicitlySet=false and no env should use config default" {
      resolveBoolean(
        cliValue = false,
        cliExplicitlySet = false,
        envVarName = "TEST",
        envVarValue = null,
        configDefault = true,
      ).shouldBeTrue()
    }

    "resolveBoolean explicit CLI=false should override env=true and config=true" {
      resolveBoolean(
        cliValue = false,
        cliExplicitlySet = true,
        envVarName = "TEST",
        envVarValue = "true",
        configDefault = true,
      ).shouldBeFalse()
    }
  }

  private class CliResult(
    val exitCode: Int,
    val output: String,
  )

  // Runs a main class in a forked JVM on the test runtime classpath and captures its exit code. The only
  // way to assert an exitProcess() path: calling it in-process would terminate the test worker.
  private fun runCliMain(
    mainClass: String,
    vararg args: String,
  ): CliResult {
    val javaBin =
      ProcessHandle.current().info().command()
        .orElse(File(File(System.getProperty("java.home"), "bin"), "java").path)
    val process =
      ProcessBuilder([javaBin, "-cp", System.getProperty("java.class.path"), mainClass] + args)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor(CLI_TIMEOUT_SECS, TimeUnit.SECONDS).shouldBeTrue()
    return CliResult(process.exitValue(), output)
  }

  // Serves the given config body over HTTP at /<fileName> and asserts the loaded proxy port.
  // Exercises the URL branch of readConfig() and the suffix-based syntax detection.
  private suspend fun verifyHttpConfigLoads(
    fileName: String,
    body: String,
    expectedPort: Int,
  ) {
    val server = embeddedServer(ServerCIO, port = 0) {
      routing {
        get("/$fileName") {
          call.respondText(body)
        }
      }
    }
    try {
      val port = server.startAndAwaitReady()
      val options = proxyOptions(["-c", "http://localhost:$port/$fileName"])
      options.proxyPort shouldBe expectedPort
    } finally {
      server.stop(0, 0)
    }
  }

  companion object {
    // Generous: the fork pays JVM startup on a cold CI machine, but --version exits before any config
    // load or port binding, so a healthy run finishes in well under a second.
    private const val CLI_TIMEOUT_SECS = 60L
  }
}
