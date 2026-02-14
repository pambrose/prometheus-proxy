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

package io.prometheus.proxy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class ProxyOptionsTest : StringSpec() {
  init {
    // ==================== Default Values ====================

    "default proxyPort should be 8080" {
      val options = ProxyOptions(listOf())
      options.proxyPort shouldBe 8080
    }

    "default proxyAgentPort should be 50051" {
      val options = ProxyOptions(listOf())
      options.proxyAgentPort shouldBe 50051
    }

    "sdEnabled should default to false" {
      val options = ProxyOptions(listOf())
      options.sdEnabled.shouldBeFalse()
    }

    "reflectionDisabled should default to false" {
      val options = ProxyOptions(listOf())
      options.reflectionDisabled.shouldBeFalse()
    }

    "handshakeTimeoutSecs should default to -1" {
      val options = ProxyOptions(listOf())
      options.handshakeTimeoutSecs shouldBe -1L
    }

    "permitKeepAliveWithoutCalls should default to false" {
      val options = ProxyOptions(listOf())
      options.permitKeepAliveWithoutCalls.shouldBeFalse()
    }

    "maxConnectionIdleSecs should default to -1" {
      val options = ProxyOptions(listOf())
      options.maxConnectionIdleSecs shouldBe -1L
    }

    "maxConnectionAgeSecs should default to -1" {
      val options = ProxyOptions(listOf())
      options.maxConnectionAgeSecs shouldBe -1L
    }

    "maxConnectionAgeGraceSecs should default to -1" {
      val options = ProxyOptions(listOf())
      options.maxConnectionAgeGraceSecs shouldBe -1L
    }

    // ==================== Command-Line Override Tests ====================

    "proxyPort should be settable via -p flag" {
      val options = ProxyOptions(listOf("-p", "9090"))
      options.proxyPort shouldBe 9090
    }

    "proxyAgentPort should be settable via -a flag" {
      val options = ProxyOptions(listOf("-a", "50052"))
      options.proxyAgentPort shouldBe 50052
    }

    "sdEnabled should be settable via command line" {
      val options = ProxyOptions(
        listOf("--sd_enabled", "--sd_path", "/sd", "--sd_target_prefix", "http://proxy:8080"),
      )
      options.sdEnabled.shouldBeTrue()
      options.sdPath shouldBe "/sd"
      options.sdTargetPrefix shouldBe "http://proxy:8080"
    }

    "reflectionDisabled should be settable via --ref_disabled" {
      val options = ProxyOptions(listOf("--ref_disabled"))
      options.reflectionDisabled.shouldBeTrue()
    }

    "reflectionDisabled should accept hyphenated variant --ref-disabled" {
      val options = ProxyOptions(listOf("--ref-disabled"))
      options.reflectionDisabled.shouldBeTrue()
    }

    // ==================== gRPC Configuration Tests ====================

    "handshakeTimeoutSecs should be settable" {
      val options = ProxyOptions(listOf("--handshake_timeout_secs", "60"))
      options.handshakeTimeoutSecs shouldBe 60L
    }

    "permitKeepAliveTimeSecs should be settable" {
      val options = ProxyOptions(listOf("--permit_keepalive_time_secs", "120"))
      options.permitKeepAliveTimeSecs shouldBe 120L
    }

    "maxConnectionIdleSecs should be settable" {
      val options = ProxyOptions(listOf("--max_connection_idle_secs", "300"))
      options.maxConnectionIdleSecs shouldBe 300L
    }

    "maxConnectionAgeSecs should be settable" {
      val options = ProxyOptions(listOf("--max_connection_age_secs", "3600"))
      options.maxConnectionAgeSecs shouldBe 3600L
    }

    "maxConnectionAgeGraceSecs should be settable" {
      val options = ProxyOptions(listOf("--max_connection_age_grace_secs", "60"))
      options.maxConnectionAgeGraceSecs shouldBe 60L
    }

    "permitKeepAliveWithoutCalls should be settable" {
      val options = ProxyOptions(listOf("--permit_keepalive_without_calls"))
      options.permitKeepAliveWithoutCalls.shouldBeTrue()
    }

    // ==================== Combined Settings Tests ====================

    "multiple gRPC settings should be settable together" {
      val options = ProxyOptions(
        listOf(
          "--handshake_timeout_secs",
          "30",
          "--permit_keepalive_time_secs",
          "60",
          "--max_connection_idle_secs",
          "120",
          "--max_connection_age_secs",
          "1800",
          "--max_connection_age_grace_secs",
          "30",
          "--permit_keepalive_without_calls",
        ),
      )
      options.handshakeTimeoutSecs shouldBe 30L
      options.permitKeepAliveTimeSecs shouldBe 60L
      options.maxConnectionIdleSecs shouldBe 120L
      options.maxConnectionAgeSecs shouldBe 1800L
      options.maxConnectionAgeGraceSecs shouldBe 30L
      options.permitKeepAliveWithoutCalls.shouldBeTrue()
    }

    // ==================== Constructor Variants Tests ====================

    "list constructor should work" {
      val options = ProxyOptions(listOf("-p", "7070"))
      options.proxyPort shouldBe 7070
    }

    "configVals should be populated after construction" {
      val options = ProxyOptions(listOf())
      options.configVals.proxy.http.port shouldBe 8080
    }

    // ==================== KeepAlive Defaults Tests ====================

    "keepAliveTimeSecs should default to -1" {
      val options = ProxyOptions(listOf())
      options.keepAliveTimeSecs shouldBe -1L
    }

    "keepAliveTimeoutSecs should default to -1" {
      val options = ProxyOptions(listOf())
      options.keepAliveTimeoutSecs shouldBe -1L
    }

    "permitKeepAliveTimeSecs should default to -1" {
      val options = ProxyOptions(listOf())
      options.permitKeepAliveTimeSecs shouldBe -1L
    }
  }
}
