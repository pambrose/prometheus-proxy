/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import io.prometheus.common.AdminConfig
import io.prometheus.common.ConfigVals
import io.prometheus.common.MetricsConfig
import io.prometheus.common.ZipkinConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class DataClassTest {

    private fun configVals(str: String): ConfigVals {
        val config = ConfigFactory.parseString(str, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
        return ConfigVals(config.withFallback(ConfigFactory.load().resolve()).resolve())
    }

    @Test
    fun adminConfigTest() {
        var vals = configVals("agent.admin.enabled=true")
        AdminConfig.create(vals.agent.admin.enabled, -1, vals.agent.admin)
                .let {
                    assertThat(it.enabled).isTrue()
                }

        vals = configVals("agent.admin.port=888")
        AdminConfig.create(vals.agent.admin.enabled, vals.agent.admin.port, vals.agent.admin)
                .let {
                    assertThat(it.enabled).isFalse()
                    assertThat(it.port).isEqualTo(888)
                }

        AdminConfig.create(true, 444, configVals("agent.admin.pingPath=a pingpath val").agent.admin)
                .let {
                    assertThat(it.pingPath).isEqualTo("a pingpath val")
                }

        AdminConfig.create(true, 444, configVals("agent.admin.versionPath=a versionpath val").agent.admin)
                .let {
                    assertThat(it.versionPath).isEqualTo("a versionpath val")
                }

        AdminConfig.create(true, 444, configVals("agent.admin.healthCheckPath=a healthCheckPath val").agent.admin)
                .let {
                    assertThat(it.healthCheckPath).isEqualTo("a healthCheckPath val")
                }

        AdminConfig.create(true, 444, configVals("agent.admin.threadDumpPath=a threadDumpPath val").agent.admin)
                .let {
                    assertThat(it.threadDumpPath).isEqualTo("a threadDumpPath val")
                }
    }

    @Test
    fun metricsConfigTest() {
        MetricsConfig.create(true, 555, configVals("agent.metrics.enabled=true").agent.metrics)
                .let {
                    assertThat(it.enabled).isTrue()
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.hostname=testval").agent.metrics)
                .let {
                    assertThat(it.port).isEqualTo(555)
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.path=a path val").agent.metrics)
                .let {
                    assertThat(it.path).isEqualTo("a path val")
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.standardExportsEnabled=true").agent.metrics)
                .let {
                    assertThat(it.standardExportsEnabled).isTrue()
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.memoryPoolsExportsEnabled=true").agent.metrics)
                .let {
                    assertThat(it.memoryPoolsExportsEnabled).isTrue()
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.garbageCollectorExportsEnabled=true").agent.metrics)
                .let {
                    assertThat(it.garbageCollectorExportsEnabled).isTrue()
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.threadExportsEnabled=true").agent.metrics)
                .let {
                    assertThat(it.threadExportsEnabled).isTrue()
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.classLoadingExportsEnabled=true").agent.metrics)
                .let {
                    assertThat(it.classLoadingExportsEnabled).isTrue()
                }

        MetricsConfig.create(true, 555, configVals("agent.metrics.versionInfoExportsEnabled=true").agent.metrics)
                .let {
                    assertThat(it.versionInfoExportsEnabled).isTrue()
                }
    }

    @Test
    fun zipkinConfigTest() {
        ZipkinConfig.create(configVals("agent.internal.zipkin.enabled=true").agent.internal.zipkin)
                .let {
                    assertThat(it.enabled).isTrue()
                }

        ZipkinConfig.create(configVals("agent.internal.zipkin.hostname=testval").agent.internal.zipkin)
                .let {
                    assertThat(it.hostname).isEqualTo("testval")
                }

        ZipkinConfig.create(configVals("agent.internal.zipkin.port=999").agent.internal.zipkin)
                .let {
                    assertThat(it.port).isEqualTo(999)
                }

        ZipkinConfig.create(configVals("agent.internal.zipkin.path=a path val").agent.internal.zipkin)
                .let {
                    assertThat(it.path).isEqualTo("a path val")
                }

        ZipkinConfig.create(configVals("agent.internal.zipkin.serviceName=a service name").agent.internal.zipkin)
                .let {
                    assertThat(it.serviceName).isEqualTo("a service name")
                }
    }
}
