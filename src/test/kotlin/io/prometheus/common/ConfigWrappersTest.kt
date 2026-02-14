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

package io.prometheus.common

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

class ConfigWrappersTest : StringSpec() {
  private fun loadDefaultConfigVals(): ConfigVals {
    val config = ConfigFactory.load()
    return ConfigVals(config)
  }

  init {
    // ==================== Proxy AdminConfig Tests ====================

    "newAdminConfig for proxy should create config with correct values" {
      val configVals = loadDefaultConfigVals()
      val adminConfig = ConfigWrappers.newAdminConfig(
        enabled = true,
        port = 8099,
        admin = configVals.proxy.admin,
      )

      adminConfig.enabled shouldBe true
      adminConfig.port shouldBe 8099
      adminConfig.pingPath.shouldNotBeEmpty()
      adminConfig.versionPath.shouldNotBeEmpty()
      adminConfig.healthCheckPath.shouldNotBeEmpty()
      adminConfig.threadDumpPath.shouldNotBeEmpty()
    }

    "newAdminConfig for proxy should respect disabled flag" {
      val configVals = loadDefaultConfigVals()
      val adminConfig = ConfigWrappers.newAdminConfig(
        enabled = false,
        port = 8099,
        admin = configVals.proxy.admin,
      )

      adminConfig.enabled shouldBe false
    }

    // ==================== Agent AdminConfig Tests ====================

    "newAdminConfig for agent should create config with correct values" {
      val configVals = loadDefaultConfigVals()
      val adminConfig = ConfigWrappers.newAdminConfig(
        enabled = true,
        port = 8199,
        admin = configVals.agent.admin,
      )

      adminConfig.enabled shouldBe true
      adminConfig.port shouldBe 8199
      adminConfig.pingPath.shouldNotBeEmpty()
    }

    // ==================== Proxy MetricsConfig Tests ====================

    "newMetricsConfig for proxy should create config with correct values" {
      val configVals = loadDefaultConfigVals()
      val metricsConfig = ConfigWrappers.newMetricsConfig(
        enabled = true,
        port = 8082,
        metrics = configVals.proxy.metrics,
      )

      metricsConfig.enabled shouldBe true
      metricsConfig.port shouldBe 8082
      metricsConfig.path.shouldNotBeEmpty()
    }

    "newMetricsConfig for proxy should respect disabled flag" {
      val configVals = loadDefaultConfigVals()
      val metricsConfig = ConfigWrappers.newMetricsConfig(
        enabled = false,
        port = 8082,
        metrics = configVals.proxy.metrics,
      )

      metricsConfig.enabled shouldBe false
    }

    // ==================== Agent MetricsConfig Tests ====================

    "newMetricsConfig for agent should create config with correct values" {
      val configVals = loadDefaultConfigVals()
      val metricsConfig = ConfigWrappers.newMetricsConfig(
        enabled = true,
        port = 8182,
        metrics = configVals.agent.metrics,
      )

      metricsConfig.enabled shouldBe true
      metricsConfig.port shouldBe 8182
    }

    // ==================== Proxy ZipkinConfig Tests ====================

    "newZipkinConfig for proxy should create config with correct values" {
      val configVals = loadDefaultConfigVals()
      val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.proxy.internal.zipkin)

      zipkinConfig.enabled.shouldBeFalse()
      zipkinConfig.hostname.shouldNotBeEmpty()
      zipkinConfig.port shouldBeGreaterThan 0
    }

    // ==================== Agent ZipkinConfig Tests ====================

    "newZipkinConfig for agent should create config with correct values" {
      val configVals = loadDefaultConfigVals()
      val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.agent.internal.zipkin)

      zipkinConfig.enabled.shouldBeFalse()
      zipkinConfig.hostname.shouldNotBeEmpty()
    }

    // ==================== Proxy AdminConfig Field Value Tests ====================

    "proxy AdminConfig should have correct default path values" {
      val configVals = loadDefaultConfigVals()
      val adminConfig = ConfigWrappers.newAdminConfig(
        enabled = true,
        port = 8092,
        admin = configVals.proxy.admin,
      )

      adminConfig.pingPath shouldBe "ping"
      adminConfig.versionPath shouldBe "version"
      adminConfig.healthCheckPath shouldBe "healthcheck"
      adminConfig.threadDumpPath shouldBe "threaddump"
    }

    "agent AdminConfig should have correct default path values" {
      val configVals = loadDefaultConfigVals()
      val adminConfig = ConfigWrappers.newAdminConfig(
        enabled = true,
        port = 8093,
        admin = configVals.agent.admin,
      )

      adminConfig.pingPath shouldBe "ping"
      adminConfig.versionPath shouldBe "version"
      adminConfig.healthCheckPath shouldBe "healthcheck"
      adminConfig.threadDumpPath shouldBe "threaddump"
    }

    // ==================== MetricsConfig Export Flag Tests ====================

    "proxy MetricsConfig should have all export flags accessible" {
      val configVals = loadDefaultConfigVals()
      val metricsConfig = ConfigWrappers.newMetricsConfig(
        enabled = true,
        port = 8082,
        metrics = configVals.proxy.metrics,
      )

      metricsConfig.path shouldBe "metrics"
      // Default export flags should be false in the default config
      metricsConfig.standardExportsEnabled.shouldBeFalse()
      metricsConfig.memoryPoolsExportsEnabled.shouldBeFalse()
      metricsConfig.garbageCollectorExportsEnabled.shouldBeFalse()
      metricsConfig.threadExportsEnabled.shouldBeFalse()
      metricsConfig.classLoadingExportsEnabled.shouldBeFalse()
      metricsConfig.versionInfoExportsEnabled.shouldBeFalse()
    }

    "agent MetricsConfig should have all export flags accessible" {
      val configVals = loadDefaultConfigVals()
      val metricsConfig = ConfigWrappers.newMetricsConfig(
        enabled = true,
        port = 8083,
        metrics = configVals.agent.metrics,
      )

      metricsConfig.path shouldBe "metrics"
      metricsConfig.standardExportsEnabled.shouldBeFalse()
      metricsConfig.memoryPoolsExportsEnabled.shouldBeFalse()
      metricsConfig.garbageCollectorExportsEnabled.shouldBeFalse()
      metricsConfig.threadExportsEnabled.shouldBeFalse()
      metricsConfig.classLoadingExportsEnabled.shouldBeFalse()
      metricsConfig.versionInfoExportsEnabled.shouldBeFalse()
    }

    // ==================== ZipkinConfig Field Value Tests ====================

    "proxy ZipkinConfig should have correct default field values" {
      val configVals = loadDefaultConfigVals()
      val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.proxy.internal.zipkin)

      zipkinConfig.enabled.shouldBeFalse()
      zipkinConfig.hostname shouldBe "localhost"
      zipkinConfig.port shouldBeGreaterThan 0
      zipkinConfig.path shouldBe "api/v2/spans"
      zipkinConfig.serviceName shouldBe "prometheus-proxy"
    }

    "agent ZipkinConfig should have correct default field values" {
      val configVals = loadDefaultConfigVals()
      val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.agent.internal.zipkin)

      zipkinConfig.enabled.shouldBeFalse()
      zipkinConfig.hostname shouldBe "localhost"
      zipkinConfig.port shouldBeGreaterThan 0
      zipkinConfig.path shouldBe "api/v2/spans"
      zipkinConfig.serviceName shouldBe "prometheus-agent"
    }
  }
}
