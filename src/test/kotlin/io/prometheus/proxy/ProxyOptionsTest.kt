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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_DASHBOARD_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.proxyOptions
import io.prometheus.common.proxyOptionsFromConfig

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

    // The dashboard must stay OFF unless asked for, matching the admin and metrics posture -- it renders agent
    // names, hostnames, target URLs and recent activity in one place on a port with no auth and no TLS.
    "dashboardEnabled should default to false" {
      val options = proxyOptions(emptyList())
      options.dashboardEnabled.shouldBeFalse()
    }

    "default dashboardPort should be $PROXY_DASHBOARD_PORT and be distinct from the admin port" {
      val options = proxyOptions(emptyList())
      options.dashboardPort shouldBe PROXY_DASHBOARD_PORT
      // Separate from admin on purpose: k8s probes hit /ping and /healthcheck there, and the dashboard should
      // be firewallable without taking the probe endpoints with it.
      options.dashboardPort shouldNotBe options.adminPort
    }

    "dashboardEnabled and dashboardPort should be settable from the command line" {
      val options = proxyOptions(["--dashboard", "--dashboard_port", "9099", "--dashboard_path", "dashboard"])
      options.dashboardEnabled.shouldBeTrue()
      options.dashboardPort shouldBe 9099
      options.dashboardPath shouldBe "dashboard"
    }

    "an out-of-range dashboardPort should be rejected" {
      shouldThrow<IllegalArgumentException> { proxyOptions(["--dashboard_port", "70000"]) }
    }

    // Reflection lets any peer that reaches the agent port list the proxy's API, so it is off unless asked for.
    "reflectionDisabled should default to true" {
      val options = proxyOptions(emptyList())
      options.reflectionDisabled.shouldBeTrue()
    }

    "reflection should be re-enabled by proxy.reflectionDisabled=false" {
      val options = proxyOptions(["-Dproxy.reflectionDisabled=false"])
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

    // ==================== Service Discovery and Dashboard Path Validation ====================

    "an empty sdPath should be rejected when service discovery is enabled" {
      val exception =
        shouldThrow<IllegalArgumentException> {
          proxyOptionsFromConfig("""proxy.service.discovery.path = "" """, extraArgs = ["--sd_enabled"])
        }
      exception.message shouldContain "sdPath is empty"
    }

    "an empty sdTargetPrefix should be rejected when service discovery is enabled" {
      val exception =
        shouldThrow<IllegalArgumentException> {
          proxyOptionsFromConfig("""proxy.service.discovery.targetPrefix = "" """, extraArgs = ["--sd_enabled"])
        }
      exception.message shouldContain "sdTargetPrefix is empty"
    }

    "an empty sdPath should be accepted while service discovery stays disabled" {
      // The guard is gated on sdEnabled, so a blank path is harmless until the endpoint is turned on.
      val options = proxyOptionsFromConfig("""proxy.service.discovery.path = "" """)
      options.sdEnabled.shouldBeFalse()
      options.sdPath shouldBe ""
    }

    "an empty dashboardPath should be rejected when the dashboard is enabled" {
      val exception =
        shouldThrow<IllegalArgumentException> {
          proxyOptionsFromConfig("""proxy.dashboard.path = "" """, extraArgs = ["--dashboard"])
        }
      exception.message shouldContain "dashboardPath is empty"
    }

    "a non-positive scrapeRequestTimeoutSecs should be rejected" {
      val exception =
        shouldThrow<IllegalArgumentException> { proxyOptions(["-Dproxy.internal.scrapeRequestTimeoutSecs=0"]) }
      exception.message shouldContain "scrapeRequestTimeoutSecs"
    }

    "proxyAgentPort above 65535 should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--agent_port", "70000"]) }
      exception.message shouldContain "proxyAgentPort"
    }

    "dashboardPort of 0 should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["--dashboard_port", "0"]) }
      exception.message shouldContain "dashboardPort"
    }

    "dashboardHost should default to all interfaces and be settable from the command line" {
      proxyOptions(emptyList()).dashboardHost shouldBe "0.0.0.0"
      proxyOptions(["--dashboard_host", "127.0.0.1"]).dashboardHost shouldBe "127.0.0.1"
    }

    "a blank dashboardHost should be rejected when the dashboard is enabled" {
      val exception =
        shouldThrow<IllegalArgumentException> {
          proxyOptionsFromConfig("""proxy.dashboard.host = "" """, extraArgs = ["--dashboard"])
        }
      exception.message shouldContain "dashboardHost"
    }

    "a non-positive dashboard maxSessions should be rejected when the dashboard is enabled" {
      val exception =
        shouldThrow<IllegalArgumentException> { proxyOptions(["--dashboard", "-Dproxy.dashboard.maxSessions=0"]) }
      exception.message shouldContain "maxSessions"
    }

    // The dashboard has no authentication, so listening on every interface earns a startup warning. Only a
    // literal wildcard address counts: a hostname is never resolved at startup just to decide whether to warn.
    "only a wildcard address counts as listening on all interfaces" {
      ProxyOptions.isWildcardAddress("0.0.0.0").shouldBeTrue()
      ProxyOptions.isWildcardAddress("::").shouldBeTrue()
      ProxyOptions.isWildcardAddress("[::]").shouldBeTrue()
      ProxyOptions.isWildcardAddress("127.0.0.1").shouldBeFalse()
      ProxyOptions.isWildcardAddress("::1").shouldBeFalse()
      ProxyOptions.isWildcardAddress("dashboard.internal").shouldBeFalse()
    }

    // The startup "agent gRPC port is unauthenticated" warning predates per-agent identities. Left
    // guarded on only the legacy token and mTLS, it fires for a proxy.auth-only config — the very
    // setup the docs recommend — training operators to ignore a security-critical warning.

    "the agent port counts as unauthenticated with no token, no identities and no mutual TLS" {
      ProxyOptions.isAgentPortUnauthenticated(
        agentToken = "",
        authIdentityCount = 0,
        isTlsEnabled = false,
        trustCertCollectionFilePath = "",
      ).shouldBeTrue()
    }

    "per-agent identities alone authenticate the agent port" {
      ProxyOptions.isAgentPortUnauthenticated(
        agentToken = "",
        authIdentityCount = 1,
        isTlsEnabled = false,
        trustCertCollectionFilePath = "",
      ).shouldBeFalse()
    }

    "a legacy agent token alone authenticates the agent port" {
      ProxyOptions.isAgentPortUnauthenticated(
        agentToken = "tok",
        authIdentityCount = 0,
        isTlsEnabled = false,
        trustCertCollectionFilePath = "",
      ).shouldBeFalse()
    }

    "mutual TLS alone authenticates the agent port" {
      ProxyOptions.isAgentPortUnauthenticated(
        agentToken = "",
        authIdentityCount = 0,
        isTlsEnabled = true,
        trustCertCollectionFilePath = "/certs/ca.pem",
      ).shouldBeFalse()
    }

    // The gRPC server uses TLS only when both a certificate and a key are set. A trust store on its own leaves
    // the port in plaintext with no client-certificate check, so it must not silence the warning.
    "a trust store without TLS enabled does not authenticate the agent port" {
      ProxyOptions.isAgentPortUnauthenticated(
        agentToken = "",
        authIdentityCount = 0,
        isTlsEnabled = false,
        trustCertCollectionFilePath = "/certs/ca.pem",
      ).shouldBeTrue()
    }

    // Server-only TLS encrypts the channel but does not check who the agent is.
    "server-only TLS does not authenticate the agent port" {
      ProxyOptions.isAgentPortUnauthenticated(
        agentToken = "",
        authIdentityCount = 0,
        isTlsEnabled = true,
        trustCertCollectionFilePath = "",
      ).shouldBeTrue()
    }

    // Tokens and identities authenticate agents, but without TLS the tokens cross the network in plaintext, where
    // anyone who can observe the traffic can capture and replay them.

    "agent tokens are sent in cleartext when a token is configured without TLS" {
      ProxyOptions.areAgentTokensSentInCleartext(agentToken = "tok", authIdentityCount = 0, isTlsEnabled = false)
        .shouldBeTrue()
    }

    "agent tokens are sent in cleartext when identities are configured without TLS" {
      ProxyOptions.areAgentTokensSentInCleartext(agentToken = "", authIdentityCount = 1, isTlsEnabled = false)
        .shouldBeTrue()
    }

    "agent tokens are not sent in cleartext when TLS is enabled" {
      ProxyOptions.areAgentTokensSentInCleartext(agentToken = "tok", authIdentityCount = 1, isTlsEnabled = true)
        .shouldBeFalse()
    }

    "nothing is sent in cleartext when no tokens are configured" {
      ProxyOptions.areAgentTokensSentInCleartext(agentToken = "", authIdentityCount = 0, isTlsEnabled = false)
        .shouldBeFalse()
    }

    // A non-positive limit would refuse every scrape.
    "maxInFlightScrapeRequests of 0 should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> {
        proxyOptions(["-Dproxy.internal.maxInFlightScrapeRequests=0"])
      }
      exception.message shouldContain "maxInFlightScrapeRequests"
    }

    // A blank bind address would fail only when the scrape server starts, with an opaque Ktor error.
    "a blank proxy.http.host should be rejected" {
      val exception = shouldThrow<IllegalArgumentException> { proxyOptions(["-Dproxy.http.host="]) }
      exception.message shouldContain "http.host"
    }
  }
}
