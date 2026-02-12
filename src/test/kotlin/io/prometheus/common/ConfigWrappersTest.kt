@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty

class ConfigWrappersTest : FunSpec() {
  private fun loadDefaultConfigVals(): ConfigVals {
    val config = ConfigFactory.load()
    return ConfigVals(config)
  }

  init {
    // ==================== Proxy AdminConfig Tests ====================

    test("newAdminConfig for proxy should create config with correct values") {
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

    test("newAdminConfig for proxy should respect disabled flag") {
      val configVals = loadDefaultConfigVals()
      val adminConfig = ConfigWrappers.newAdminConfig(
        enabled = false,
        port = 8099,
        admin = configVals.proxy.admin,
      )

      adminConfig.enabled shouldBe false
    }

    // ==================== Agent AdminConfig Tests ====================

    test("newAdminConfig for agent should create config with correct values") {
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

    test("newMetricsConfig for proxy should create config with correct values") {
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

    test("newMetricsConfig for proxy should respect disabled flag") {
      val configVals = loadDefaultConfigVals()
      val metricsConfig = ConfigWrappers.newMetricsConfig(
        enabled = false,
        port = 8082,
        metrics = configVals.proxy.metrics,
      )

      metricsConfig.enabled shouldBe false
    }

    // ==================== Agent MetricsConfig Tests ====================

    test("newMetricsConfig for agent should create config with correct values") {
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

    test("newZipkinConfig for proxy should create config with correct values") {
      val configVals = loadDefaultConfigVals()
      val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.proxy.internal.zipkin)

      zipkinConfig.enabled.shouldBeFalse()
      zipkinConfig.hostname.shouldNotBeEmpty()
      zipkinConfig.port shouldBeGreaterThan 0
    }

    // ==================== Agent ZipkinConfig Tests ====================

    test("newZipkinConfig for agent should create config with correct values") {
      val configVals = loadDefaultConfigVals()
      val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.agent.internal.zipkin)

      zipkinConfig.enabled.shouldBeFalse()
      zipkinConfig.hostname.shouldNotBeEmpty()
    }

    // ==================== Proxy AdminConfig Field Value Tests ====================

    test("proxy AdminConfig should have correct default path values") {
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

    test("agent AdminConfig should have correct default path values") {
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

    test("proxy MetricsConfig should have all export flags accessible") {
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

    test("agent MetricsConfig should have all export flags accessible") {
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

    test("proxy ZipkinConfig should have correct default field values") {
      val configVals = loadDefaultConfigVals()
      val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.proxy.internal.zipkin)

      zipkinConfig.enabled.shouldBeFalse()
      zipkinConfig.hostname shouldBe "localhost"
      zipkinConfig.port shouldBeGreaterThan 0
      zipkinConfig.path shouldBe "api/v2/spans"
      zipkinConfig.serviceName shouldBe "prometheus-proxy"
    }

    test("agent ZipkinConfig should have correct default field values") {
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
