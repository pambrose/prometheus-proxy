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
import io.prometheus.proxy.ProxyOptions
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.amshove.kluent.shouldEqual
import org.junit.Test

class OptionsTest {

    @Test
    fun verifyDefaultValues() {
        val configVals = readProxyOptions(listOf())
        configVals
            .apply {
                proxy.http.port shouldEqual 8080
                proxy.internal.zipkin.enabled.shouldBeFalse()
            }
    }

    @Test
    fun verifyConfValues() {
        val configVals = readProxyOptions(listOf("--config", CONFIG))
        configVals
            .apply {
                proxy.http.port shouldEqual 8181
                proxy.internal.zipkin.enabled.shouldBeTrue()
            }
    }

    @Test
    fun verifyUnquotedPropValue() {
        val configVals = readProxyOptions(listOf("-Dproxy.http.port=9393", "-Dproxy.internal.zipkin.enabled=true"))
        configVals
            .apply {
                proxy.http.port shouldEqual 9393
                proxy.internal.zipkin.enabled.shouldBeTrue()
            }
    }

    @Test
    fun verifyQuotedPropValue() {
        val configVals = readProxyOptions(listOf("-D\"proxy.http.port=9394\""))
        configVals.proxy.http.port shouldEqual 9394
    }

    @Test
    fun verifyPathConfigs() {
        val configVals = readAgentOptions(listOf("--config", CONFIG))
        configVals.agent.pathConfigs.size shouldEqual 3
    }

    @Test
    fun verifyProxyDefaults() {
        val options = ProxyOptions(listOf())

        options
            .apply {
                proxyHttpPort shouldEqual 8080
                proxyAgentPort shouldEqual 50051
            }
    }

    @Test
    fun verifyAgentDefaults() {
        val options = AgentOptions(listOf("--name", "test-name", "--proxy", "host5"), false)

        options
            .apply {
                metricsEnabled shouldEqual false
                dynamicParams.size shouldEqual 0
                agentName shouldEqual "test-name"
                proxyHostname shouldEqual "host5"
            }
    }

    private fun readProxyOptions(argList: List<String>) = ProxyOptions(argList).configVals

    private fun readAgentOptions(argList: List<String>) = AgentOptions(argList, false).configVals

    companion object {
        private const val CONFIG =
            "https://raw.githubusercontent.com/pambrose/prometheus-proxy/master/etc/test-configs/junit-test.conf"
        //private const val CONFIG = "etc/test-configs/junit-test.conf"
    }
}