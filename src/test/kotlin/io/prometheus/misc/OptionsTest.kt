/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.prometheus.agent.AgentOptions
import io.prometheus.harness.support.HarnessConstants.OPTIONS_CONFIG
import io.prometheus.proxy.ProxyOptions
import org.junit.jupiter.api.Test

class OptionsTest {
  // ==================== Proxy Default Values Tests ====================

  @Test
  fun verifyDefaultValues() {
    val configVals = readProxyOptions(listOf())
    configVals.proxy
      .apply {
        http.port shouldBe 8080
        internal.zipkin.enabled.shouldBeFalse()
      }
  }

  @Test
  fun verifyConfValues() {
    val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG))
    configVals.proxy
      .apply {
        http.port shouldBe 8181
        internal.zipkin.enabled.shouldBeTrue()
      }
  }

  @Test
  fun verifyUnquotedPropValue() {
    val configVals = readProxyOptions(listOf("-Dproxy.http.port=9393", "-Dproxy.internal.zipkin.enabled=true"))
    configVals.proxy
      .apply {
        http.port shouldBe 9393
        internal.zipkin.enabled.shouldBeTrue()
      }
  }

  @Test
  fun verifyQuotedPropValue() {
    val configVals = readProxyOptions(listOf("-Dproxy.http.port=9394"))
    configVals.proxy.http.port shouldBe 9394
  }

  @Test
  fun verifyPathConfigs() {
    val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
    configVals.agent.pathConfigs.size shouldBe 3
  }

  @Test
  fun verifyProxyDefaults() {
    ProxyOptions(listOf())
      .apply {
        proxyHttpPort shouldBe 8080
        proxyAgentPort shouldBe 50051
      }
  }

  @Test
  fun verifyAgentDefaults() {
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

  @Test
  fun `verifyPathConfigs should parse multiple path entries correctly`() {
    val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
    val pathConfigs = configVals.agent.pathConfigs

    pathConfigs.size shouldBe 3
    pathConfigs[0].name shouldBe "agent1"
    pathConfigs[0].path shouldBe "agent1_metrics"
    pathConfigs[0].url shouldBe "http://localhost:8084/metrics"
  }

  @Test
  fun `verifyPathConfigs should have correct properties for each path`() {
    val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
    val pathConfigs = configVals.agent.pathConfigs

    // Verify each path config has all required properties
    pathConfigs.forEach { pathConfig ->
      pathConfig.name.isNotEmpty().shouldBeTrue()
      pathConfig.path.isNotEmpty().shouldBeTrue()
      pathConfig.url.isNotEmpty().shouldBeTrue()
    }
  }

  @Test
  fun `verifyPathConfigs should differentiate between paths`() {
    val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
    val pathConfigs = configVals.agent.pathConfigs

    // Ensure all paths are unique
    val paths = pathConfigs.map { it.path }
    paths.distinct().size shouldBe paths.size

    // Ensure all names are unique
    val names = pathConfigs.map { it.name }
    names.distinct().size shouldBe names.size
  }

  @Test
  fun `verifyAgentHttpClientDefaults should have valid defaults`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host"), false)
    options.apply {
      // HTTP client settings should have sensible defaults after config loading
      // The defaults come from the built-in configuration, not command line args
      maxConcurrentHttpClients shouldBe 1 // Default from config
    }
  }

  @Test
  fun `verifyAgentChunkSettings should accept custom chunk size`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--chunk", "64"), false)
    // chunkContentSizeKbs is multiplied by 1024 in processing
    options.chunkContentSizeKbs shouldBe 64 * 1024
  }

  @Test
  fun `verifyAgentGzipSettings should accept custom gzip threshold`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "--gzip", "1024"), false)
    options.minGzipSizeBytes shouldBe 1024
  }

  @Test
  fun `verifyAgentConsolidated mode can be enabled via command line`() {
    val options = AgentOptions(listOf("--name", "test", "--proxy", "host", "-o"), false)
    options.consolidated.shouldBeTrue()
  }

  @Test
  fun `verifyAgentScrapeSettings should accept timeout and retries`() {
    val options = AgentOptions(
      listOf("--name", "test", "--proxy", "host", "--timeout", "30", "--max_retries", "5"),
      false,
    )
    options.scrapeTimeoutSecs shouldBe 30
    options.scrapeMaxRetries shouldBe 5
  }

  // ==================== Proxy Configuration Edge Cases (2.1.2) ====================

  @Test
  fun `verifyProxyPortOverride should override default port`() {
    val options = ProxyOptions(listOf("-p", "9090"))
    options.proxyHttpPort shouldBe 9090
  }

  @Test
  fun `verifyProxyAgentPortOverride should override default agent port`() {
    val options = ProxyOptions(listOf("-a", "50052"))
    options.proxyAgentPort shouldBe 50052
  }

  @Test
  fun `verifyProxyServiceDiscovery should be configurable`() {
    val options = ProxyOptions(
      listOf("--sd_enabled", "--sd_path", "/sd", "--sd_target_prefix", "http://proxy:8080"),
    )
    options.sdEnabled.shouldBeTrue()
    options.sdPath shouldBe "/sd"
    options.sdTargetPrefix shouldBe "http://proxy:8080"
  }

  @Test
  fun `verifyProxyReflection can be disabled`() {
    val options = ProxyOptions(listOf("--ref_disabled"))
    options.reflectionDisabled.shouldBeTrue()
  }

  @Test
  fun `verifyProxyGrpcSettings should accept custom timeouts`() {
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

  @Test
  fun `verifyProxyConnectionAge settings should be configurable`() {
    val options = ProxyOptions(listOf("--max_connection_age_secs", "3600", "--max_connection_age_grace_secs", "60"))
    options.maxConnectionAgeSecs shouldBe 3600L
    options.maxConnectionAgeGraceSecs shouldBe 60L
  }

  @Test
  fun `verifyProxyKeepAlive without calls should be configurable`() {
    val options = ProxyOptions(listOf("--permit_keepalive_without_calls"))
    options.permitKeepAliveWithoutCalls.shouldBeTrue()
  }

  @Test
  fun `verifyProxySystemPropertyOverride should work for port`() {
    val configVals = readProxyOptions(listOf("-Dproxy.http.port=7777"))
    configVals.proxy.http.port shouldBe 7777
  }

  @Test
  fun `verifyProxyConfigFileOverride should take precedence over defaults`() {
    val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG))
    // junit-test.conf sets port to 8181
    configVals.proxy.http.port shouldBe 8181
  }

  @Test
  fun `verifyProxyInternalSettings from config`() {
    val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG))
    // zipkin is enabled in junit-test.conf
    configVals.proxy.internal.zipkin.enabled.shouldBeTrue()
  }

  private fun readProxyOptions(argList: List<String>) = ProxyOptions(argList).configVals

  private fun readAgentOptions(argList: List<String>) = AgentOptions(argList, false).configVals
}
