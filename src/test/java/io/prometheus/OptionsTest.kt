/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus

import io.prometheus.agent.AgentOptions
import io.prometheus.common.ConfigVals
import io.prometheus.proxy.ProxyOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class OptionsTest {

    @Test
    fun verifyDefaultValues() {
        val configVals = readProxyOptions(listOf())
        assertThat(configVals.proxy.http.port).isEqualTo(8080)
        assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(false)
    }

    @Test
    fun verifyConfValues() {
        val configVals = readProxyOptions(listOf("--config", CONFIG))
        assertThat(configVals.proxy.http.port).isEqualTo(8181)
        assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(true)
    }

    @Test
    fun verifyUnquotedPropValue() {
        val configVals = readProxyOptions(listOf("-Dproxy.http.port=9393", "-Dproxy.internal.zipkin.enabled=true"))
        assertThat(configVals.proxy.http.port).isEqualTo(9393)
        assertThat(configVals.proxy.internal.zipkin.enabled).isEqualTo(true)
    }

    @Test
    fun verifyQuotedPropValue() {
        val configVals = readProxyOptions(listOf("-D\"proxy.http.port=9394\""))
        assertThat(configVals.proxy.http.port).isEqualTo(9394)
    }

    @Test
    fun verifyPathConfigs() {
        val configVals = readAgentOptions(listOf("--config", CONFIG))
        assertThat(configVals.agent.pathConfigs.size).isEqualTo(3)
    }


    @Test
    fun verifyProxyDefaults() {
        val options = ProxyOptions(listOf())

        assertThat(options.proxyHttpPort).isEqualTo(8080)
        assertThat(options.proxyAgentPort).isEqualTo(50051)
    }

    @Test
    fun verifyAgentDefaults() {
        val options = AgentOptions(listOf("--name", "test-name", "--proxy", "host5"),
                                   false)

        assertThat(options.metricsEnabled).isEqualTo(false)
        assertThat(options.dynamicParams.size).isEqualTo(0)
        assertThat(options.agentName).isEqualTo("test-name")
        assertThat(options.proxyHostname).isEqualTo("host5")
    }

    private fun readProxyOptions(argList: List<String>): ConfigVals {
        val options = ProxyOptions(argList)
        return options.configVals
    }

    private fun readAgentOptions(argList: List<String>): ConfigVals {
        val options = AgentOptions(argList, false)
        return options.configVals
    }

    companion object {
        private const val CONFIG = "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/etc/test-configs/junit-test.conf"
    }
}