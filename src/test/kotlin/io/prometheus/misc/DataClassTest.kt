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

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigSyntax
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.prometheus.common.ConfigVals
import io.prometheus.common.ConfigWrappers.newAdminConfig
import io.prometheus.common.ConfigWrappers.newMetricsConfig
import io.prometheus.common.ConfigWrappers.newZipkinConfig

class DataClassTest : FunSpec() {
  private fun configVals(str: String): ConfigVals {
    val config = ConfigFactory.parseString(str, ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF))
    return ConfigVals(config.withFallback(ConfigFactory.load().resolve()).resolve())
  }

  init {
    test("agent admin config should parse all fields correctly") {
      var vals = configVals("agent.admin.enabled=true")
      newAdminConfig(vals.agent.admin.enabled, -1, vals.agent.admin)
        .also {
          it.enabled.shouldBeTrue()
        }

      vals = configVals("agent.admin.port=888")
      newAdminConfig(vals.agent.admin.enabled, vals.agent.admin.port, vals.agent.admin)
        .also {
          it.enabled.shouldBeFalse()
          it.port shouldBe 888
        }

      newAdminConfig(true, 444, configVals("agent.admin.pingPath=a pingpath val").agent.admin)
        .also {
          it.pingPath shouldBe "a pingpath val"
        }

      newAdminConfig(true, 444, configVals("agent.admin.versionPath=a versionpath val").agent.admin)
        .also {
          it.versionPath shouldBe "a versionpath val"
        }

      newAdminConfig(true, 444, configVals("agent.admin.healthCheckPath=a healthCheckPath val").agent.admin)
        .also {
          it.healthCheckPath shouldBe "a healthCheckPath val"
        }

      newAdminConfig(true, 444, configVals("agent.admin.threadDumpPath=a threadDumpPath val").agent.admin)
        .also {
          it.threadDumpPath shouldBe "a threadDumpPath val"
        }
    }

    test("agent metrics config should parse all fields correctly") {
      newMetricsConfig(true, 555, configVals("agent.metrics.enabled=true").agent.metrics)
        .also {
          it.enabled.shouldBeTrue()
        }

      newMetricsConfig(true, 555, configVals("agent.metrics.hostname=testval").agent.metrics)
        .also {
          it.port shouldBe 555
        }

      newMetricsConfig(true, 555, configVals("agent.metrics.path=a path val").agent.metrics)
        .also {
          it.path shouldBe "a path val"
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

    test("zipkin config should parse all fields correctly") {
      newZipkinConfig(configVals("agent.internal.zipkin.enabled=true").agent.internal.zipkin)
        .also {
          it.enabled.shouldBeTrue()
        }

      newZipkinConfig(configVals("agent.internal.zipkin.hostname=testval").agent.internal.zipkin)
        .also {
          it.hostname shouldBe "testval"
        }

      newZipkinConfig(configVals("agent.internal.zipkin.port=999").agent.internal.zipkin)
        .also {
          it.port shouldBe 999
        }

      newZipkinConfig(configVals("agent.internal.zipkin.path=a path val").agent.internal.zipkin)
        .also {
          it.path shouldBe "a path val"
        }

      newZipkinConfig(configVals("agent.internal.zipkin.serviceName=a service name").agent.internal.zipkin)
        .also {
          it.serviceName shouldBe "a service name"
        }
    }

    // ==================== Proxy Admin Config Tests ====================

    test("proxy admin config should parse all fields correctly") {
      var vals = configVals("proxy.admin.enabled=true")
      newAdminConfig(vals.proxy.admin.enabled, -1, vals.proxy.admin)
        .also {
          it.enabled.shouldBeTrue()
        }

      vals = configVals("proxy.admin.port=777")
      newAdminConfig(vals.proxy.admin.enabled, vals.proxy.admin.port, vals.proxy.admin)
        .also {
          it.enabled.shouldBeFalse()
          it.port shouldBe 777
        }

      newAdminConfig(true, 444, configVals("proxy.admin.pingPath=proxy ping val").proxy.admin)
        .also {
          it.pingPath shouldBe "proxy ping val"
        }

      newAdminConfig(true, 444, configVals("proxy.admin.versionPath=proxy version val").proxy.admin)
        .also {
          it.versionPath shouldBe "proxy version val"
        }

      newAdminConfig(true, 444, configVals("proxy.admin.healthCheckPath=proxy healthCheck val").proxy.admin)
        .also {
          it.healthCheckPath shouldBe "proxy healthCheck val"
        }

      newAdminConfig(true, 444, configVals("proxy.admin.threadDumpPath=proxy threadDump val").proxy.admin)
        .also {
          it.threadDumpPath shouldBe "proxy threadDump val"
        }
    }

    // ==================== Proxy Metrics Config Tests ====================

    test("proxy metrics config should parse all fields correctly") {
      newMetricsConfig(true, 666, configVals("proxy.metrics.enabled=true").proxy.metrics)
        .also {
          it.enabled.shouldBeTrue()
        }

      newMetricsConfig(true, 666, configVals("proxy.metrics.path=proxy path val").proxy.metrics)
        .also {
          it.path shouldBe "proxy path val"
        }

      newMetricsConfig(true, 666, configVals("proxy.metrics.standardExportsEnabled=true").proxy.metrics)
        .also {
          it.standardExportsEnabled.shouldBeTrue()
        }

      newMetricsConfig(true, 666, configVals("proxy.metrics.memoryPoolsExportsEnabled=true").proxy.metrics)
        .also {
          it.memoryPoolsExportsEnabled.shouldBeTrue()
        }

      newMetricsConfig(true, 666, configVals("proxy.metrics.garbageCollectorExportsEnabled=true").proxy.metrics)
        .also {
          it.garbageCollectorExportsEnabled.shouldBeTrue()
        }

      newMetricsConfig(true, 666, configVals("proxy.metrics.threadExportsEnabled=true").proxy.metrics)
        .also {
          it.threadExportsEnabled.shouldBeTrue()
        }

      newMetricsConfig(true, 666, configVals("proxy.metrics.classLoadingExportsEnabled=true").proxy.metrics)
        .also {
          it.classLoadingExportsEnabled.shouldBeTrue()
        }

      newMetricsConfig(true, 666, configVals("proxy.metrics.versionInfoExportsEnabled=true").proxy.metrics)
        .also {
          it.versionInfoExportsEnabled.shouldBeTrue()
        }
    }

    // ==================== gRPC Metrics Config Tests ====================

    test("gRPC metrics config should parse all fields correctly") {
      // Agent gRPC metrics
      configVals("agent.metrics.grpc.metricsEnabled=true").agent.metrics.grpc
        .also {
          it.metricsEnabled.shouldBeTrue()
        }

      configVals("agent.metrics.grpc.allMetricsReported=true").agent.metrics.grpc
        .also {
          it.allMetricsReported.shouldBeTrue()
        }

      // Proxy gRPC metrics
      configVals("proxy.metrics.grpc.metricsEnabled=true").proxy.metrics.grpc
        .also {
          it.metricsEnabled.shouldBeTrue()
        }

      configVals("proxy.metrics.grpc.allMetricsReported=true").proxy.metrics.grpc
        .also {
          it.allMetricsReported.shouldBeTrue()
        }

      // Defaults should be false
      configVals("agent.name=test").agent.metrics.grpc
        .also {
          it.metricsEnabled.shouldBeFalse()
          it.allMetricsReported.shouldBeFalse()
        }

      configVals("proxy.http.port=8080").proxy.metrics.grpc
        .also {
          it.metricsEnabled.shouldBeFalse()
          it.allMetricsReported.shouldBeFalse()
        }
    }
  }
}
