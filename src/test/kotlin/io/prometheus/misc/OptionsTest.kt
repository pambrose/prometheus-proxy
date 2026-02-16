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

package io.prometheus.misc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.agent.AgentOptions
import io.prometheus.harness.HarnessConstants.OPTIONS_CONFIG
import io.prometheus.proxy.ProxyOptions

class OptionsTest : StringSpec() {
  private fun readProxyOptions(argList: List<String>) = ProxyOptions(argList).configVals

  private fun readAgentOptions(argList: List<String>) = AgentOptions(argList, false).configVals

  init {
    // ==================== Proxy Default Values Tests ====================

    "should use default values when no config is provided" {
      val configVals = readProxyOptions(listOf())
      configVals.proxy
        .apply {
          http.port shouldBe 8080
          internal.zipkin.enabled.shouldBeFalse()
        }
    }

    "should override defaults with config file values" {
      val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG))
      configVals.proxy
        .apply {
          http.port shouldBe 8181
          internal.zipkin.enabled.shouldBeTrue()
        }
    }

    "should override defaults with unquoted system property values" {
      val configVals = readProxyOptions(listOf("-Dproxy.http.port=9393", "-Dproxy.internal.zipkin.enabled=true"))
      configVals.proxy
        .apply {
          http.port shouldBe 9393
          internal.zipkin.enabled.shouldBeTrue()
        }
    }

    "should override defaults with quoted system property values" {
      val configVals = readProxyOptions(listOf("-Dproxy.http.port=9394"))
      configVals.proxy.http.port shouldBe 9394
    }

    "should parse path configs from config file" {
      val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
      configVals.agent.pathConfigs.size shouldBe 3
    }

    "should have correct proxy default port values" {
      ProxyOptions(listOf())
        .apply {
          proxyPort shouldBe 8080
          proxyAgentPort shouldBe 50051
        }
    }

    "should have correct agent default values" {
      val options = AgentOptions(listOf("--name", "test-name", "--proxy", "host5"), false)
      options
        .apply {
          metricsEnabled shouldBe false
          dynamicParams.size shouldBe 0
          agentName shouldBe "test-name"
          proxyHostname shouldBe "host5"
        }
    }

    // ==================== Complex Agent Path Configuration Tests (2.1.1) ====================

    "verifyPathConfigs should parse multiple path entries correctly" {
      val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
      val pathConfigs = configVals.agent.pathConfigs

      pathConfigs.size shouldBe 3
      pathConfigs[0].name shouldBe "agent1"
      pathConfigs[0].path shouldBe "agent1_metrics"
      pathConfigs[0].url shouldBe "http://localhost:8084/metrics"
    }

    "verifyPathConfigs should have correct properties for each path" {
      val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
      val pathConfigs = configVals.agent.pathConfigs

      // Verify each path config has all required properties
      pathConfigs.forEach { pathConfig ->
        pathConfig.name.isNotEmpty().shouldBeTrue()
        pathConfig.path.isNotEmpty().shouldBeTrue()
        pathConfig.url.isNotEmpty().shouldBeTrue()
      }
    }

    "verifyPathConfigs should differentiate between paths" {
      val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
      val pathConfigs = configVals.agent.pathConfigs

      // Ensure all paths are unique
      val paths = pathConfigs.map { it.path }
      paths.distinct().size shouldBe paths.size

      // Ensure all names are unique
      val names = pathConfigs.map { it.name }
      names.distinct().size shouldBe names.size
    }

    "verifyAgentHttpClientDefaults should have valid defaults" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.apply {
        // HTTP client settings should have sensible defaults after config loading
        // The defaults come from the built-in configuration, not command line args
        maxConcurrentHttpClients shouldBe 1 // Default from config
      }
    }

    "verifyAgentChunkSettings should accept custom chunk size" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--chunk", "64"), false)
      // chunkContentSizeBytes is multiplied by 1024 in processing
      options.chunkContentSizeBytes shouldBe 64 * 1024
    }

    "verifyAgentGzipSettings should accept custom gzip threshold" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--gzip", "1024"), false)
      options.minGzipSizeBytes shouldBe 1024
    }

    "verifyAgentConsolidated mode can be enabled via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "-o"), false)
      options.consolidated.shouldBeTrue()
    }

    "verifyAgentScrapeSettings should accept timeout and retries" {
      val options = AgentOptions(
        listOf("--name", "test", "--proxy", "host", "--timeout", "30", "--max_retries", "5"),
        false,
      )
      options.scrapeTimeoutSecs shouldBe 30
      options.scrapeMaxRetries shouldBe 5
    }

    // ==================== Proxy Configuration Edge Cases (2.1.2) ====================

    "verifyProxyPortOverride should override default port" {
      val options = ProxyOptions(listOf("-p", "9090"))
      options.proxyPort shouldBe 9090
    }

    "verifyProxyAgentPortOverride should override default agent port" {
      val options = ProxyOptions(listOf("-a", "50052"))
      options.proxyAgentPort shouldBe 50052
    }

    "verifyProxyServiceDiscovery should be configurable" {
      val options = ProxyOptions(
        listOf("--sd_enabled", "--sd_path", "/sd", "--sd_target_prefix", "http://proxy:8080"),
      )
      options.sdEnabled.shouldBeTrue()
      options.sdPath shouldBe "/sd"
      options.sdTargetPrefix shouldBe "http://proxy:8080"
    }

    "verifyProxyReflection can be disabled" {
      val options = ProxyOptions(listOf("--ref_disabled"))
      options.reflectionDisabled.shouldBeTrue()
    }

    "verifyProxyGrpcSettings should accept custom timeouts" {
      val options = ProxyOptions(
        listOf(
          "--handshake_timeout_secs",
          "60",
          "--permit_keepalive_time_secs",
          "120",
          "--max_connection_idle_secs",
          "300",
        ),
      )
      options.handshakeTimeoutSecs shouldBe 60L
      options.permitKeepAliveTimeSecs shouldBe 120L
      options.maxConnectionIdleSecs shouldBe 300L
    }

    "verifyProxyConnectionAge settings should be configurable" {
      val options = ProxyOptions(listOf("--max_connection_age_secs", "3600", "--max_connection_age_grace_secs", "60"))
      options.maxConnectionAgeSecs shouldBe 3600L
      options.maxConnectionAgeGraceSecs shouldBe 60L
    }

    "verifyProxyKeepAlive without calls should be configurable" {
      val options = ProxyOptions(listOf("--permit_keepalive_without_calls"))
      options.permitKeepAliveWithoutCalls.shouldBeTrue()
    }

    "verifyProxySystemPropertyOverride should work for port" {
      val configVals = readProxyOptions(listOf("-Dproxy.http.port=7777"))
      configVals.proxy.http.port shouldBe 7777
    }

    "verifyProxyConfigFileOverride should take precedence over defaults" {
      val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG))
      // junit-test.conf sets port to 8181
      configVals.proxy.http.port shouldBe 8181
    }

    "verifyProxyInternalSettings from config" {
      val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG))
      // zipkin is enabled in junit-test.conf
      configVals.proxy.internal.zipkin.enabled.shouldBeTrue()
    }

    // ==================== Dynamic Parameter Tests ====================

    // M5: Dynamic params no longer call System.setProperty() — they only apply to
    // the Config object via ConfigFactory.parseString(). This avoids leaking global
    // JVM state that was never cleaned up.
    "dynamic param should not set system property" {
      val propKey = "proxy.http.port"
      val original = System.getProperty(propKey)
      try {
        readProxyOptions(listOf("-D$propKey=6161"))

        // System property should NOT have been set
        System.getProperty(propKey) shouldBe original
      } finally {
        // Defensive cleanup in case the test fails and property was set
        if (original == null) System.clearProperty(propKey) else System.setProperty(propKey, original)
      }
    }

    "dynamic param with quoted value should apply to config correctly" {
      val configVals = readProxyOptions(listOf("-Dproxy.http.port=\"7272\""))

      // Quotes are stripped and config reflects the bare value
      configVals.proxy.http.port shouldBe 7272
    }

    "dynamic param should apply to config correctly" {
      val configVals = readProxyOptions(listOf("-Dproxy.http.port=8282"))

      // Config should reflect the value
      configVals.proxy.http.port shouldBe 8282
    }

    "multiple dynamic params should not leak any system properties" {
      val keys = listOf("proxy.http.port", "proxy.internal.zipkin.enabled")
      val originals = keys.associateWith { System.getProperty(it) }
      try {
        readProxyOptions(listOf("-Dproxy.http.port=5555", "-Dproxy.internal.zipkin.enabled=true"))

        // No system properties should have been set
        keys.forEach { key ->
          System.getProperty(key) shouldBe originals[key]
        }
      } finally {
        keys.forEach { key ->
          val orig = originals[key]
          if (orig == null) System.clearProperty(key) else System.setProperty(key, orig)
        }
      }
    }

    // ==================== Agent Trust and KeepAlive Tests ====================

    "agent trustAllX509Certificates should be settable via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--trust_all_x509"), false)
      options.trustAllX509Certificates.shouldBeTrue()
    }

    "agent trustAllX509Certificates should default to false" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.trustAllX509Certificates.shouldBeFalse()
    }

    "agent keepAliveWithoutCalls should be settable via command line" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--keepalive_without_calls"), false)
      options.keepAliveWithoutCalls.shouldBeTrue()
    }

    // ==================== Agent HTTP Client Cache Default Tests ====================

    "agent HTTP client cache settings should have valid defaults" {
      val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
      options.maxCacheSize shouldBeGreaterThan 1
      options.maxCacheAgeMins shouldBeGreaterThan 1
      options.maxCacheIdleMins shouldBeGreaterThan 1
      options.cacheCleanupIntervalMins shouldBeGreaterThan 1
    }

    // ==================== Agent Internal Config Defaults ====================

    "agent internal config should have expected defaults" {
      val configVals = readAgentOptions(listOf())
      configVals.agent.internal.apply {
        cioTimeoutSecs shouldBe 90
        heartbeatEnabled.shouldBeTrue()
        reconnectPauseSecs shouldBe 3
        heartbeatCheckPauseMillis shouldBe 500
        heartbeatMaxInactivitySecs shouldBe 5
        scrapeRequestBacklogUnhealthySize shouldBe 25
      }
    }

    // ==================== Config Precedence Tests ====================

    "dynamic param should override config file value" {
      val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG, "-Dproxy.http.port=4444"))
      // Dynamic param (4444) should override junit-test.conf (8181)
      configVals.proxy.http.port shouldBe 4444
    }

    "multiple dynamic params should all be applied" {
      val configVals = readProxyOptions(
        listOf("-Dproxy.http.port=3333", "-Dproxy.internal.zipkin.enabled=true"),
      )
      configVals.proxy.http.port shouldBe 3333
      configVals.proxy.internal.zipkin.enabled.shouldBeTrue()
    }

    // ==================== Proxy Request Logging and Path Config Labels ====================

    "proxy requestLoggingEnabled should default to true" {
      val configVals = readProxyOptions(listOf())
      configVals.proxy.http.requestLoggingEnabled.shouldBeTrue()
    }

    "agent path config labels should default to empty JSON object" {
      val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
      val pathConfigs = configVals.agent.pathConfigs
      pathConfigs.forEach { pathConfig ->
        pathConfig.labels.shouldNotBeEmpty()
        pathConfig.labels shouldBe "{}"
      }
    }

    "agent proxy hostname and port should have defaults from config" {
      val configVals = readAgentOptions(listOf())
      configVals.agent.proxy.hostname shouldBe "localhost"
      configVals.agent.proxy.port shouldBe 50051
    }
  }
}
