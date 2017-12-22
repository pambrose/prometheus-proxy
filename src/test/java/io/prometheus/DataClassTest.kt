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
        var c = AdminConfig.create(vals.agent.admin.enabled, -1, vals.agent.admin)
        assertThat(c.enabled).isTrue()

        vals = configVals("agent.admin.port=888")
        c = AdminConfig.create(vals.agent.admin.enabled, vals.agent.admin.port, vals.agent.admin)
        assertThat(c.enabled).isFalse()
        assertThat(c.port).isEqualTo(888)

        c = AdminConfig.create(true, 444, configVals("agent.admin.pingPath=a pingpath val").agent.admin)
        assertThat(c.pingPath).isEqualTo("a pingpath val")

        c = AdminConfig.create(true, 444, configVals("agent.admin.versionPath=a versionpath val").agent.admin)
        assertThat(c.versionPath).isEqualTo("a versionpath val")

        c = AdminConfig.create(true, 444, configVals("agent.admin.healthCheckPath=a healthCheckPath val").agent.admin)
        assertThat(c.healthCheckPath).isEqualTo("a healthCheckPath val")

        c = AdminConfig.create(true, 444, configVals("agent.admin.threadDumpPath=a threadDumpPath val").agent.admin)
        assertThat(c.threadDumpPath).isEqualTo("a threadDumpPath val")
    }

    @Test
    fun metricsConfigTest() {
        var c = MetricsConfig.create(true, 555, configVals("agent.metrics.enabled=true").agent.metrics)
        assertThat(c.enabled).isTrue()

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.hostname=testval").agent.metrics)
        assertThat(c.port).isEqualTo(555)

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.path=a path val").agent.metrics)
        assertThat(c.path).isEqualTo("a path val")

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.standardExportsEnabled=true").agent.metrics)
        assertThat(c.standardExportsEnabled).isTrue()

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.memoryPoolsExportsEnabled=true").agent.metrics)
        assertThat(c.memoryPoolsExportsEnabled).isTrue()

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.garbageCollectorExportsEnabled=true").agent.metrics)
        assertThat(c.garbageCollectorExportsEnabled).isTrue()

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.threadExportsEnabled=true").agent.metrics)
        assertThat(c.threadExportsEnabled).isTrue()

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.classLoadingExportsEnabled=true").agent.metrics)
        assertThat(c.classLoadingExportsEnabled).isTrue()

        c = MetricsConfig.create(true, 555, configVals("agent.metrics.versionInfoExportsEnabled=true").agent.metrics)
        assertThat(c.versionInfoExportsEnabled).isTrue()
    }

    @Test
    fun zipkinConfigTest() {
        var c = ZipkinConfig.create(configVals("agent.internal.zipkin.enabled=true").agent.internal.zipkin)
        assertThat(c.enabled).isTrue()

        c = ZipkinConfig.create(configVals("agent.internal.zipkin.hostname=testval").agent.internal.zipkin)
        assertThat(c.hostname).isEqualTo("testval")

        c = ZipkinConfig.create(configVals("agent.internal.zipkin.port=999").agent.internal.zipkin)
        assertThat(c.port).isEqualTo(999)

        c = ZipkinConfig.create(configVals("agent.internal.zipkin.path=a path val").agent.internal.zipkin)
        assertThat(c.path).isEqualTo("a path val")

        c = ZipkinConfig.create(configVals("agent.internal.zipkin.serviceName=a service name").agent.internal.zipkin)
        assertThat(c.serviceName).isEqualTo("a service name")
    }
}
