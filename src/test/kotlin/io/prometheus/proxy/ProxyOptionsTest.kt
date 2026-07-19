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

package io.prometheus.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.proxyOptions

class ProxyOptionsTest : StringSpec() {
  init {
    // ==================== Default Values ====================

    "default proxyPort should be $PROXY_HTTP_PORT" {
      val options = proxyOptions(emptyList())
      options.proxyPort shouldBe PROXY_HTTP_PORT
    }

    "default proxyAgentPort should be $PROXY_AGENT_PORT" {
      val options = proxyOptions(emptyList())
      options.proxyAgentPort shouldBe PROXY_AGENT_PORT
    }

    "sdEnabled should default to false" {
      val options = proxyOptions(emptyList())
      options.sdEnabled.shouldBeFalse()
    }

    "reflectionDisabled should default to false" {
      val options = proxyOptions(emptyList())
      options.reflectionDisabled.shouldBeFalse()
    }

    "handshakeTimeoutSecs should default to -1" {
      val options = proxyOptions(emptyList())
      options.handshakeTimeoutSecs shouldBe -1L
    }

    "permitKeepAliveWithoutCalls should default to false" {
      val options = proxyOptions(emptyList())
      options.permitKeepAliveWithoutCalls.shouldBeFalse()
    }

    "maxConnectionIdleSecs should default to -1" {
      val options = proxyOptions(emptyList())
      options.maxConnectionIdleSecs shouldBe -1L
    }

    "maxConnectionAgeSecs should default to -1" {
      val options = proxyOptions(emptyList())
      options.maxConnectionAgeSecs shouldBe -1L
    }

    "maxConnectionAgeGraceSecs should default to -1" {
      val options = proxyOptions(emptyList())
      options.maxConnectionAgeGraceSecs shouldBe -1L
    }

    // ==================== Command-Line Override Tests ====================

    "proxyPort should be settable via -p flag" {
      val options = proxyOptions(["-p", "$PROMETHEUS_PORT"])
      options.proxyPort shouldBe PROMETHEUS_PORT
    }

    "proxyAgentPort should be settable via -a flag" {
      val options = proxyOptions(["-a", "50052"])
      options.proxyAgentPort shouldBe 50052
    }

    "sdEnabled should be settable via command line" {
      val options = proxyOptions(
        ["--sd_enabled", "--sd_path", "/sd", "--sd_target_prefix", "http://proxy:$PROXY_HTTP_PORT"],
      )
      options.sdEnabled.shouldBeTrue()
      options.sdPath shouldBe "/sd"
      options.sdTargetPrefix shouldBe "http://proxy:$PROXY_HTTP_PORT"
    }

    "reflectionDisabled should be settable via --ref_disabled" {
      val options = proxyOptions(["--ref_disabled"])
      options.reflectionDisabled.shouldBeTrue()
    }

    "reflectionDisabled should accept hyphenated variant --ref-disabled" {
      val options = proxyOptions(["--ref-disabled"])
      options.reflectionDisabled.shouldBeTrue()
    }

    // ==================== gRPC Configuration Tests ====================

    "handshakeTimeoutSecs should be settable" {
      val options = proxyOptions(["--handshake_timeout_secs", "60"])
      options.handshakeTimeoutSecs shouldBe 60L
    }

    "permitKeepAliveTimeSecs should be settable" {
      val options = proxyOptions(["--permit_keepalive_time_secs", "120"])
      options.permitKeepAliveTimeSecs shouldBe 120L
    }

    "maxConnectionIdleSecs should be settable" {
      val options = proxyOptions(["--max_connection_idle_secs", "300"])
      options.maxConnectionIdleSecs shouldBe 300L
    }

    "maxConnectionAgeSecs should be settable" {
      val options = proxyOptions(["--max_connection_age_secs", "3600"])
      options.maxConnectionAgeSecs shouldBe 3600L
    }

    "maxConnectionAgeGraceSecs should be settable" {
      val options = proxyOptions(["--max_connection_age_grace_secs", "60"])
      options.maxConnectionAgeGraceSecs shouldBe 60L
    }

    "permitKeepAliveWithoutCalls should be settable" {
      val options = proxyOptions(["--permit_keepalive_without_calls"])
      options.permitKeepAliveWithoutCalls.shouldBeTrue()
    }

    // ==================== Combined Settings Tests ====================

    "multiple gRPC settings should be settable together" {
      val options = proxyOptions(
        [
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
        ],
      )
      options.handshakeTimeoutSecs shouldBe 30L
      options.permitKeepAliveTimeSecs shouldBe 60L
      options.maxConnectionIdleSecs shouldBe 120L
      options.maxConnectionAgeSecs shouldBe 1800L
      options.maxConnectionAgeGraceSecs shouldBe 30L
      options.permitKeepAliveWithoutCalls.shouldBeTrue()
    }

    // ==================== Item 27: Bounds Validation ====================
    // ProxyOptions now fails fast on out-of-range ports and gRPC timeouts during construction,
    // instead of surfacing opaque Ktor/gRPC builder exceptions later at server startup.

    "proxyPort of 0 should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--port", "0"]) }
      exception.message shouldContain "proxyPort"
    }

    "proxyPort above 65535 should be rejected" {
      shouldThrow<IllegalArgumentException> { proxyOptions(["--port", "70000"]) }
    }

    // adminPort/metricsPort were previously unvalidated, unlike proxyPort/proxyAgentPort, so an
    // out-of-range value surfaced only as an opaque bind failure. Validate them consistently.
    "adminPort of 0 should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--admin_port", "0"]) }
      exception.message shouldContain "adminPort"
    }

    "adminPort above 65535 should be rejected" {
      shouldThrow<IllegalArgumentException> { proxyOptions(["--admin_port", "70000"]) }
    }

    "metricsPort of 0 should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--metrics_port", "0"]) }
      exception.message shouldContain "metricsPort"
    }

    "metricsPort above 65535 should be rejected" {
      shouldThrow<IllegalArgumentException> { proxyOptions(["--metrics_port", "70000"]) }
    }

    "proxyAgentPort of 0 should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--agent_port", "0"]) }
      exception.message shouldContain "proxyAgentPort"
    }

    "a zero gRPC handshake timeout should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--handshake_timeout_secs", "0"]) }
      exception.message shouldContain "handshakeTimeoutSecs"
    }

    "a zero gRPC max-connection-idle timeout should be rejected" {
      shouldThrow<IllegalArgumentException> { proxyOptions(["--max_connection_idle_secs", "0"]) }
    }

    "the -1 sentinel should be accepted for gRPC timeouts" {
      val options = proxyOptions(["--handshake_timeout_secs", "-1"])
      options.handshakeTimeoutSecs shouldBe -1L
    }

    "a zero gRPC keepalive time should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--keepalive_time_secs", "0"]) }
      exception.message shouldContain "keepAliveTimeSecs"
    }

    "a zero gRPC keepalive timeout should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--keepalive_timeout_secs", "0"]) }
      exception.message shouldContain "keepAliveTimeoutSecs"
    }

    "the -1 sentinel should be accepted for gRPC keepalive timeouts" {
      val options = proxyOptions(["--keepalive_time_secs", "-1", "--keepalive_timeout_secs", "-1"])
      options.keepAliveTimeSecs shouldBe -1L
      options.keepAliveTimeoutSecs shouldBe -1L
    }

    // These internal config values are positive durations/sizes; a 0/negative would otherwise
    // surface as silent misbehavior (busy-loop, immediate eviction, all-content-rejected) rather
    // than a clear startup error.

    "a non-positive staleAgentCheckPauseSecs should be rejected" {
      val exception =
        shouldThrow<IllegalArgumentException> { proxyOptions(["-Dproxy.internal.staleAgentCheckPauseSecs=0"]) }
      exception.message shouldContain "staleAgentCheckPauseSecs"
    }

    "a non-positive maxAgentInactivitySecs should be rejected" {
      val exception =
        shouldThrow<IllegalArgumentException> { proxyOptions(["-Dproxy.internal.maxAgentInactivitySecs=0"]) }
      exception.message shouldContain "maxAgentInactivitySecs"
    }

    // 0 is a valid "reject all content" limit (used by the zip-bomb test), so only negatives reject.
    "a negative maxUnzippedContentSizeMBytes should be rejected" {
      val exception =
        shouldThrow<IllegalArgumentException> {
          proxyOptions(["-Dproxy.internal.maxUnzippedContentSizeMBytes=-1"])
        }
      exception.message shouldContain "maxUnzippedContentSizeMBytes"
    }

    "a zero maxUnzippedContentSizeMBytes should be accepted (reject-all limit)" {
      val options = proxyOptions(["-Dproxy.internal.maxUnzippedContentSizeMBytes=0"])
      options.configVals.proxy.internal.maxUnzippedContentSizeMBytes shouldBe 0
    }

    // Finding 11: maxZippedContentSizeMBytes is used in chunked-transfer size math but its sibling
    // maxUnzippedContentSizeMBytes was the only one validated. Mirror the sibling: reject negatives,
    // allow 0 as a degenerate reject-all limit.
    "Finding 11: a negative maxZippedContentSizeMBytes should be rejected" {
      val exception =
        shouldThrow<IllegalArgumentException> {
          proxyOptions(["-Dproxy.internal.maxZippedContentSizeMBytes=-1"])
        }
      exception.message shouldContain "maxZippedContentSizeMBytes"
    }

    "Finding 11: a zero maxZippedContentSizeMBytes should be accepted (reject-all limit)" {
      val options = proxyOptions(["-Dproxy.internal.maxZippedContentSizeMBytes=0"])
      options.configVals.proxy.internal.maxZippedContentSizeMBytes shouldBe 0
    }

    // ==================== Constructor Variants Tests ====================

    "list constructor should work" {
      val options = proxyOptions(["-p", "7070"])
      options.proxyPort shouldBe 7070
    }

    "configVals should be populated after construction" {
      val options = proxyOptions(emptyList())
      options.configVals.proxy.http.port shouldBe PROXY_HTTP_PORT
    }

    // ==================== KeepAlive Defaults Tests ====================

    "keepAliveTimeSecs should default to -1" {
      val options = proxyOptions(emptyList())
      options.keepAliveTimeSecs shouldBe -1L
    }

    "keepAliveTimeoutSecs should default to -1" {
      val options = proxyOptions(emptyList())
      options.keepAliveTimeoutSecs shouldBe -1L
    }

    "permitKeepAliveTimeSecs should default to -1" {
      val options = proxyOptions(emptyList())
      options.permitKeepAliveTimeSecs shouldBe -1L
    }

    // ==================== Bug #8: SD config values should be set when SD enabled ====================

    "sdPath and sdTargetPrefix should be accessible when sdEnabled is true" {
      val options = proxyOptions(
        ["--sd_enabled", "--sd_path", "/discovery", "--sd_target_prefix", "http://proxy:$PROXY_HTTP_PORT"],
      )
      options.sdEnabled.shouldBeTrue()
      options.sdPath shouldBe "/discovery"
      options.sdTargetPrefix shouldBe "http://proxy:$PROXY_HTTP_PORT"
    }

    "sdPath and sdTargetPrefix should be set when sdEnabled is false" {
      val options = proxyOptions(emptyList())
      options.sdEnabled.shouldBeFalse()
      // Values should still be assigned from config defaults (even when SD disabled)
      // Bug #8 fix ensures these values are logged regardless of sdEnabled state
      options.sdPath shouldBe options.configVals.proxy.service.discovery.path
      options.sdTargetPrefix shouldBe options.configVals.proxy.service.discovery.targetPrefix
    }
  }
}
