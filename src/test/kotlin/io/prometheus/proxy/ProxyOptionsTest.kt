@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class ProxyOptionsTest : FunSpec() {
  init {
    // ==================== Default Values ====================

    test("default proxyPort should be 8080") {
      val options = ProxyOptions(listOf())
      options.proxyPort shouldBe 8080
    }

    test("default proxyAgentPort should be 50051") {
      val options = ProxyOptions(listOf())
      options.proxyAgentPort shouldBe 50051
    }

    test("sdEnabled should default to false") {
      val options = ProxyOptions(listOf())
      options.sdEnabled.shouldBeFalse()
    }

    test("reflectionDisabled should default to false") {
      val options = ProxyOptions(listOf())
      options.reflectionDisabled.shouldBeFalse()
    }

    test("handshakeTimeoutSecs should default to -1") {
      val options = ProxyOptions(listOf())
      options.handshakeTimeoutSecs shouldBe -1L
    }

    test("permitKeepAliveWithoutCalls should default to false") {
      val options = ProxyOptions(listOf())
      options.permitKeepAliveWithoutCalls.shouldBeFalse()
    }

    test("maxConnectionIdleSecs should default to -1") {
      val options = ProxyOptions(listOf())
      options.maxConnectionIdleSecs shouldBe -1L
    }

    test("maxConnectionAgeSecs should default to -1") {
      val options = ProxyOptions(listOf())
      options.maxConnectionAgeSecs shouldBe -1L
    }

    test("maxConnectionAgeGraceSecs should default to -1") {
      val options = ProxyOptions(listOf())
      options.maxConnectionAgeGraceSecs shouldBe -1L
    }

    // ==================== Command-Line Override Tests ====================

    test("proxyPort should be settable via -p flag") {
      val options = ProxyOptions(listOf("-p", "9090"))
      options.proxyPort shouldBe 9090
    }

    test("proxyAgentPort should be settable via -a flag") {
      val options = ProxyOptions(listOf("-a", "50052"))
      options.proxyAgentPort shouldBe 50052
    }

    test("sdEnabled should be settable via command line") {
      val options = ProxyOptions(
        listOf("--sd_enabled", "--sd_path", "/sd", "--sd_target_prefix", "http://proxy:8080"),
      )
      options.sdEnabled.shouldBeTrue()
      options.sdPath shouldBe "/sd"
      options.sdTargetPrefix shouldBe "http://proxy:8080"
    }

    test("reflectionDisabled should be settable via --ref_disabled") {
      val options = ProxyOptions(listOf("--ref_disabled"))
      options.reflectionDisabled.shouldBeTrue()
    }

    test("reflectionDisabled should accept hyphenated variant --ref-disabled") {
      val options = ProxyOptions(listOf("--ref-disabled"))
      options.reflectionDisabled.shouldBeTrue()
    }

    // ==================== gRPC Configuration Tests ====================

    test("handshakeTimeoutSecs should be settable") {
      val options = ProxyOptions(listOf("--handshake_timeout_secs", "60"))
      options.handshakeTimeoutSecs shouldBe 60L
    }

    test("permitKeepAliveTimeSecs should be settable") {
      val options = ProxyOptions(listOf("--permit_keepalive_time_secs", "120"))
      options.permitKeepAliveTimeSecs shouldBe 120L
    }

    test("maxConnectionIdleSecs should be settable") {
      val options = ProxyOptions(listOf("--max_connection_idle_secs", "300"))
      options.maxConnectionIdleSecs shouldBe 300L
    }

    test("maxConnectionAgeSecs should be settable") {
      val options = ProxyOptions(listOf("--max_connection_age_secs", "3600"))
      options.maxConnectionAgeSecs shouldBe 3600L
    }

    test("maxConnectionAgeGraceSecs should be settable") {
      val options = ProxyOptions(listOf("--max_connection_age_grace_secs", "60"))
      options.maxConnectionAgeGraceSecs shouldBe 60L
    }

    test("permitKeepAliveWithoutCalls should be settable") {
      val options = ProxyOptions(listOf("--permit_keepalive_without_calls"))
      options.permitKeepAliveWithoutCalls.shouldBeTrue()
    }

    // ==================== Combined Settings Tests ====================

    test("multiple gRPC settings should be settable together") {
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

    test("list constructor should work") {
      val options = ProxyOptions(listOf("-p", "7070"))
      options.proxyPort shouldBe 7070
    }

    test("configVals should be populated after construction") {
      val options = ProxyOptions(listOf())
      options.configVals.proxy.http.port shouldBe 8080
    }

    // ==================== KeepAlive Defaults Tests ====================

    test("keepAliveTimeSecs should default to -1") {
      val options = ProxyOptions(listOf())
      options.keepAliveTimeSecs shouldBe -1L
    }

    test("keepAliveTimeoutSecs should default to -1") {
      val options = ProxyOptions(listOf())
      options.keepAliveTimeoutSecs shouldBe -1L
    }

    test("permitKeepAliveTimeSecs should default to -1") {
      val options = ProxyOptions(listOf())
      options.permitKeepAliveTimeSecs shouldBe -1L
    }
  }
}
