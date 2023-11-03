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

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeFalse
import org.amshove.kluent.shouldBeTrue
import org.junit.jupiter.api.Test

class DataClassTest {
  private fun configVals(str: String): ConfigVals {
    val config = ConfigFactory.parseString(str, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
    return ConfigVals(config.withFallback(ConfigFactory.load().resolve()).resolve())
  }

  @Test
  fun adminConfigTest() {
    var vals = configVals("agent.admin.enabled=true")
    newAdminConfig(vals.agent.admin.enabled, -1, vals.agent.admin)
      .also {
        it.enabled.shouldBeTrue()
      }

    vals = configVals("agent.admin.port=888")
    newAdminConfig(vals.agent.admin.enabled, vals.agent.admin.port, vals.agent.admin)
      .also {
        it.enabled.shouldBeFalse()
        it.port shouldBeEqualTo 888
      }

    newAdminConfig(true, 444, configVals("agent.admin.pingPath=a pingpath val").agent.admin)
      .also {
        it.pingPath shouldBeEqualTo "a pingpath val"
      }

    newAdminConfig(true, 444, configVals("agent.admin.versionPath=a versionpath val").agent.admin)
      .also {
        it.versionPath shouldBeEqualTo "a versionpath val"
      }

    newAdminConfig(true, 444, configVals("agent.admin.healthCheckPath=a healthCheckPath val").agent.admin)
      .also {
        it.healthCheckPath shouldBeEqualTo "a healthCheckPath val"
      }

    newAdminConfig(true, 444, configVals("agent.admin.threadDumpPath=a threadDumpPath val").agent.admin)
      .also {
        it.threadDumpPath shouldBeEqualTo "a threadDumpPath val"
      }
  }

  @Test
  fun metricsConfigTest() {
    newMetricsConfig(true, 555, configVals("agent.metrics.enabled=true").agent.metrics)
      .also {
        it.enabled.shouldBeTrue()
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.hostname=testval").agent.metrics)
      .also {
        it.port shouldBeEqualTo 555
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.path=a path val").agent.metrics)
      .also {
        it.path shouldBeEqualTo "a path val"
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.standardExportsEnabled=true").agent.metrics)
      .also {
        it.standardExportsEnabled.shouldBeTrue()
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.memoryPoolsExportsEnabled=true").agent.metrics)
      .also {
        it.memoryPoolsExportsEnabled.shouldBeTrue()
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.garbageCollectorExportsEnabled=true").agent.metrics)
      .also {
        it.garbageCollectorExportsEnabled.shouldBeTrue()
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.threadExportsEnabled=true").agent.metrics)
      .also {
        it.threadExportsEnabled.shouldBeTrue()
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.classLoadingExportsEnabled=true").agent.metrics)
      .also {
        it.classLoadingExportsEnabled.shouldBeTrue()
      }

    newMetricsConfig(true, 555, configVals("agent.metrics.versionInfoExportsEnabled=true").agent.metrics)
      .also {
        it.versionInfoExportsEnabled.shouldBeTrue()
      }
  }

  @Test
  fun zipkinConfigTest() {
    newZipkinConfig(configVals("agent.internal.zipkin.enabled=true").agent.internal.zipkin)
      .also {
        it.enabled.shouldBeTrue()
      }

    newZipkinConfig(configVals("agent.internal.zipkin.hostname=testval").agent.internal.zipkin)
      .also {
        it.hostname shouldBeEqualTo "testval"
      }

    newZipkinConfig(configVals("agent.internal.zipkin.port=999").agent.internal.zipkin)
      .also {
        it.port shouldBeEqualTo 999
      }

    newZipkinConfig(configVals("agent.internal.zipkin.path=a path val").agent.internal.zipkin)
      .also {
        it.path shouldBeEqualTo "a path val"
      }

    newZipkinConfig(configVals("agent.internal.zipkin.serviceName=a service name").agent.internal.zipkin)
      .also {
        it.serviceName shouldBeEqualTo "a service name"
      }
  }
}
