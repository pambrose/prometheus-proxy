/*
 * Copyright © 2026 Paul Ambrose
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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldNotBeEmpty
import io.prometheus.common.ConfigVals
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT

class ConfigValsTest : StringSpec() {
  private fun loadDefaultConfigVals(): ConfigVals {
    val config = ConfigFactory.load()
    return ConfigVals(config)
  }

  init {
    // ==================== Agent Internal Defaults ====================

    "agent internal config should have correct defaults" {
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

    // ==================== Agent Filters Defaults ====================

    "agent filters should default to an empty list" {
      val configVals = loadDefaultConfigVals()
      configVals.agent.filters.shouldBeEmpty()
    }

    // ==================== Agent Proxy Endpoints Defaults ====================

    "agent proxy endpoints should default to an empty list" {
      val configVals = loadDefaultConfigVals()
      configVals.agent.proxy.endpoints.shouldBeEmpty()
    }

    // tscfg generates an UNGUARDED c.getList("endpoints") for the list -- unlike the sibling scalars
    // hostname/port, which get a hasPathOrNull check. Without the agent.proxy.endpoints = [] default in
    // reference.conf, every pre-failover config (i.e. every config in the wild) would die at load time
    // with ConfigException.Missing. Deleting that one reference.conf line must fail loudly here rather
    // than in production, so this test loads a config shaped exactly like an existing deployment's.
    "a config that predates failover and omits agent.proxy.endpoints should still load" {
      val config =
        ConfigFactory.parseString(
          """
          agent {
            proxy {
              hostname = "some-proxy"
              port = 50052
            }
          }
          """.trimIndent(),
        ).withFallback(ConfigFactory.load()).resolve()

      val configVals = ConfigVals(config)

      configVals.agent.proxy.apply {
        hostname shouldBe "some-proxy"
        port shouldBe 50052
        endpoints.shouldBeEmpty()
      }
    }

    // ==================== Proxy UI Defaults ====================

    "proxy ui config should have correct defaults" {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.ui.apply {
        enabled.shouldBeFalse()
        port shouldBe 8094
        path shouldBe "ui"
        refreshIntervalSecs shouldBe 2
        recentScrapesQueueSize shouldBe 200
      }
    }

    // The contrast with the endpoints case above is the point of having both tests: every proxy.ui key
    // is a SCALAR, so tscfg emits a hasPathOrNull guard for each and for the block itself. A config
    // predating the UI therefore loads without needing a reference.conf entry at all -- this pins that,
    // so the guard cannot silently regress into an unguarded getList.
    "a config that predates the web UI should still load" {
      val config =
        ConfigFactory.parseString("proxy { http { port = 9999 } }")
          .withFallback(ConfigFactory.load())
          .resolve()

      ConfigVals(config).proxy.apply {
        http.port shouldBe 9999
        ui.enabled.shouldBeFalse()
        ui.port shouldBe 8094
      }
    }

    // ==================== Agent HTTP Client Cache Defaults ====================

    "agent HTTP client cache config should have correct defaults" {
      val configVals = loadDefaultConfigVals()
      configVals.agent.http.clientCache.apply {
        maxSize shouldBe 100
        maxAgeMins shouldBe 30
        maxIdleMins shouldBe 10
        cleanupIntervalMins shouldBe 5
      }
    }

    // ==================== Agent TLS Defaults ====================

    "agent TLS config should have empty defaults" {
      val configVals = loadDefaultConfigVals()
      configVals.agent.tls.apply {
        certChainFilePath.shouldBeEmpty()
        privateKeyFilePath.shouldBeEmpty()
        trustCertCollectionFilePath.shouldBeEmpty()
        overrideAuthority.shouldBeEmpty()
      }
    }

    // ==================== Agent gRPC Defaults ====================

    "agent gRPC config should have correct defaults" {
      val configVals = loadDefaultConfigVals()
      configVals.agent.grpc.apply {
        keepAliveTimeSecs shouldBe -1L
        keepAliveTimeoutSecs shouldBe -1L
        keepAliveWithoutCalls.shouldBeFalse()
      }
    }

    // ==================== Proxy HTTP Defaults ====================

    "proxy HTTP config should have correct defaults" {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.http.apply {
        port shouldBe PROXY_HTTP_PORT
        idleTimeoutSecs shouldBe 45
        requestLoggingEnabled.shouldBeTrue()
      }
    }

    // ==================== Proxy Internal Defaults ====================

    "proxy internal config should have correct defaults" {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.internal.apply {
        scrapeRequestTimeoutSecs shouldBe 90
        staleAgentCheckEnabled.shouldBeTrue()
        staleAgentCheckPauseSecs shouldBe 10
        maxAgentInactivitySecs shouldBe 60
        scrapeRequestBacklogUnhealthySize shouldBe 25
        scrapeRequestMapUnhealthySize shouldBe 25
        chunkContextMapUnhealthySize shouldBe 25
        blitz.enabled.shouldBeFalse()
        blitz.path.shouldNotBeEmpty()
      }
    }

    // ==================== Proxy Service Discovery Defaults ====================

    "proxy service discovery config should have correct defaults" {
      val configVals = loadDefaultConfigVals()
      configVals.proxy.service.discovery.apply {
        enabled.shouldBeFalse()
        path shouldBe "discovery"
        targetPrefix shouldBe "http://localhost:$PROXY_HTTP_PORT/"
      }
    }

    // ==================== Proxy gRPC Defaults ====================

    "proxy gRPC config should have correct defaults" {
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
