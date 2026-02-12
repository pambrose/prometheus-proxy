@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.misc

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.common.ConfigVals

class ConfigValsTest : FunSpec() {
  private fun loadDefaultConfigVals(): ConfigVals {
    val config = ConfigFactory.load()
    return ConfigVals(config)
  }

  init {
    // ==================== Agent Internal Defaults ====================

    test("agent internal config should have correct defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.agent.internal.apply {
        cioTimeoutSecs shouldBe 90
        heartbeatEnabled.shouldBeTrue()
        heartbeatCheckPauseMillis shouldBe 500
        heartbeatMaxInactivitySecs shouldBe 5
        reconnectPauseSecs shouldBe 3
        scrapeRequestBacklogUnhealthySize shouldBe 25
      }
    }

    // ==================== Agent HTTP Client Cache Defaults ====================

    test("agent HTTP client cache config should have correct defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.agent.http.clientCache.apply {
        maxSize shouldBe 100
        maxAgeMins shouldBe 30
        maxIdleMins shouldBe 10
        cleanupIntervalMins shouldBe 5
      }
    }

    // ==================== Agent TLS Defaults ====================

    test("agent TLS config should have empty defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.agent.tls.apply {
        certChainFilePath.shouldBeEmpty()
        privateKeyFilePath.shouldBeEmpty()
        trustCertCollectionFilePath.shouldBeEmpty()
        overrideAuthority.shouldBeEmpty()
      }
    }

    // ==================== Agent gRPC Defaults ====================

    test("agent gRPC config should have correct defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.agent.grpc.apply {
        keepAliveTimeSecs shouldBe -1L
        keepAliveTimeoutSecs shouldBe -1L
        keepAliveWithoutCalls.shouldBeFalse()
      }
    }

    // ==================== Proxy HTTP Defaults ====================

    test("proxy HTTP config should have correct defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.http.apply {
        port shouldBe 8080
        idleTimeoutSecs shouldBe 45
        maxThreads shouldBe -1
        minThreads shouldBe -1
        requestLoggingEnabled.shouldBeTrue()
      }
    }

    // ==================== Proxy Internal Defaults ====================

    test("proxy internal config should have correct defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.internal.apply {
        scrapeRequestTimeoutSecs shouldBe 90
        staleAgentCheckEnabled.shouldBeTrue()
        staleAgentCheckPauseSecs shouldBe 10
        maxAgentInactivitySecs shouldBe 60
        scrapeRequestCheckMillis shouldBe 500
        scrapeRequestBacklogUnhealthySize shouldBe 25
        scrapeRequestMapUnhealthySize shouldBe 25
        chunkContextMapUnhealthySize shouldBe 25
        blitz.enabled.shouldBeFalse()
        blitz.path.shouldNotBeEmpty()
      }
    }

    // ==================== Proxy Service Discovery Defaults ====================

    test("proxy service discovery config should have correct defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.service.discovery.apply {
        enabled.shouldBeFalse()
        path shouldBe "discovery"
        targetPrefix shouldBe "http://localhost:8080/"
      }
    }

    // ==================== Proxy gRPC Defaults ====================

    test("proxy gRPC config should have correct defaults") {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.grpc.apply {
        handshakeTimeoutSecs shouldBe -1L
        keepAliveTimeSecs shouldBe -1L
        keepAliveTimeoutSecs shouldBe -1L
        maxConnectionIdleSecs shouldBe -1L
        maxConnectionAgeSecs shouldBe -1L
        maxConnectionAgeGraceSecs shouldBe -1L
        permitKeepAliveTimeSecs shouldBe -1L
        permitKeepAliveWithoutCalls.shouldBeFalse()
      }
    }
  }
}
