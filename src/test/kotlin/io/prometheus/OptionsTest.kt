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

package io.prometheus

import io.prometheus.TestConstants.OPTIONS_CONFIG
import io.prometheus.agent.AgentOptions
import io.prometheus.proxy.ProxyOptions
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

class OptionsTest {
  @Test
  fun verifyDefaultValues() {
    val configVals = readProxyOptions(listOf())
    configVals.proxy
      .apply {
        http.port shouldBeEqualTo 8080
        internal.zipkin.enabled.shouldBeFalse()
      }
  }

  @Test
  fun verifyConfValues() {
    val configVals = readProxyOptions(listOf("--config", OPTIONS_CONFIG))
    configVals.proxy
      .apply {
        http.port shouldBeEqualTo 8181
        internal.zipkin.enabled.shouldBeTrue()
      }
  }

  @Test
  fun verifyUnquotedPropValue() {
    val configVals = readProxyOptions(listOf("-Dproxy.http.port=9393", "-Dproxy.internal.zipkin.enabled=true"))
    configVals.proxy
      .apply {
        http.port shouldBeEqualTo 9393
        internal.zipkin.enabled.shouldBeTrue()
      }
  }

  @Test
  fun verifyQuotedPropValue() {
    val configVals = readProxyOptions(listOf("-Dproxy.http.port=9394"))
    configVals.proxy.http.port shouldBeEqualTo 9394
  }

  @Test
  fun verifyPathConfigs() {
    val configVals = readAgentOptions(listOf("--config", OPTIONS_CONFIG))
    configVals.agent.pathConfigs.size shouldBeEqualTo 3
  }

  @Test
  fun verifyProxyDefaults() {
    ProxyOptions(listOf())
      .apply {
        proxyHttpPort shouldBeEqualTo 8080
        proxyAgentPort shouldBeEqualTo 50051
      }
  }

  @Test
  fun verifyAgentDefaults() {
    val options = AgentOptions(listOf("--name", "test-name", "--proxy", "host5"), false)
    options
      .apply {
        metricsEnabled shouldBeEqualTo false
        dynamicParams.size shouldBeEqualTo 0
        agentName shouldBeEqualTo "test-name"
        proxyHostname shouldBeEqualTo "host5"
      }
  }

  private fun readProxyOptions(argList: List<String>) = ProxyOptions(argList).configVals

  private fun readAgentOptions(argList: List<String>) = AgentOptions(argList, false).configVals
}
