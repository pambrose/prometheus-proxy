@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.typesafe.config.ConfigFactory
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.jupiter.api.Test

class ConfigWrappersTest {
  private fun loadDefaultConfigVals(): ConfigVals {
    val config = ConfigFactory.load()
    return ConfigVals(config)
  }

  // ==================== Proxy AdminConfig Tests ====================

  @Test
  fun `newAdminConfig for proxy should create config with correct values`() {
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

  @Test
  fun `newAdminConfig for proxy should respect disabled flag`() {
    val configVals = loadDefaultConfigVals()
    val adminConfig = ConfigWrappers.newAdminConfig(
      enabled = false,
      port = 8099,
      admin = configVals.proxy.admin,
    )

    adminConfig.enabled shouldBe false
  }

  // ==================== Agent AdminConfig Tests ====================

  @Test
  fun `newAdminConfig for agent should create config with correct values`() {
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

  @Test
  fun `newMetricsConfig for proxy should create config with correct values`() {
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

  @Test
  fun `newMetricsConfig for proxy should respect disabled flag`() {
    val configVals = loadDefaultConfigVals()
    val metricsConfig = ConfigWrappers.newMetricsConfig(
      enabled = false,
      port = 8082,
      metrics = configVals.proxy.metrics,
    )

    metricsConfig.enabled shouldBe false
  }

  // ==================== Agent MetricsConfig Tests ====================

  @Test
  fun `newMetricsConfig for agent should create config with correct values`() {
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

  @Test
  fun `newZipkinConfig for proxy should create config with correct values`() {
    val configVals = loadDefaultConfigVals()
    val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.proxy.internal.zipkin)

    zipkinConfig.enabled.shouldBeFalse()
    zipkinConfig.hostname.shouldNotBeEmpty()
    zipkinConfig.port shouldBe zipkinConfig.port // non-negative
  }

  // ==================== Agent ZipkinConfig Tests ====================

  @Test
  fun `newZipkinConfig for agent should create config with correct values`() {
    val configVals = loadDefaultConfigVals()
    val zipkinConfig = ConfigWrappers.newZipkinConfig(configVals.agent.internal.zipkin)

    zipkinConfig.enabled.shouldBeFalse()
    zipkinConfig.hostname.shouldNotBeEmpty()
  }
}
