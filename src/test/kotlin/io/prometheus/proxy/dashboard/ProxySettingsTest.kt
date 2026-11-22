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

package io.prometheus.proxy.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.Proxy
import io.prometheus.common.TestCerts
import io.prometheus.common.dashboard.group
import io.prometheus.common.dashboard.text
import io.prometheus.common.dashboard.value
import io.prometheus.common.proxyOptionsFromConfig

/**
 * [ProxySettings], from a proxy built but never started: what it resolved, worded as the view shows it, and no secret
 * anywhere in it, neither a token, the dashboard's password, a PEM, nor a `-D` override's value. The wording both
 * dashboards share is covered in `SettingsViewTest` and `CommonSettingsTest`.
 */
class ProxySettingsTest : StringSpec() {
  private fun proxy(
    config: String,
    extraArgs: List<String> = emptyList(),
  ) = Proxy(
    proxyOptionsFromConfig(config, extraArgs = extraArgs),
    inProcessServerName = "proxy-settings-${System.nanoTime()}",
    testMode = true,
  )

  private val keyBase64 = TestCerts.base64(TestCerts.SERVER_KEY)

  private val configured =
    """
    proxy {
      agentToken = "tok-shared-1"
      auth = [
        { name = "team-a", token = "tok-team-a-2", paths = ["orders_*", "billing_*"] },
        { name = "ops", token = "tok-ops-3", paths = [] },
      ]
      tls {
        certChainFilePath = "${TestCerts.SERVER_CERT}"
        privateKeyPem = "$keyBase64"
      }
      grpc.handshakeTimeoutSecs = 30
      internal {
        maxPathsPerAgent = 0
        maxAgentInactivitySecs = 45
        staleAgentCheckEnabled = false
      }
      transportFilterDisabled = true
      dashboard {
        enabled = true
        username = "operator"
        password = "dash-pw-4"
        links = [{ name = "Grafana", url = "https://grafana.example/d/x?var-job={label.job}", icon = "grafana" }]
      }
    }
    """

  init {
    "the settings should come in groups, in the order an operator reads them" {
      ProxySettings.of(proxy("")).map { it.title } shouldBe
        [
          "About", "Listeners", "Agent authentication", "TLS", "Scrapes", "Agent limits", "gRPC server",
          "Service discovery", "Dashboard", "Admin", "Metrics", "Tracing", "Logging",
        ]
    }

    "the settings should show what the proxy resolved, worded as the view shows it" {
      val settings = ProxySettings.of(proxy(configured, extraArgs = ["-Dproxy.internal.maxPathLength=300"]))

      settings.value("Agent authentication", "Authentication") shouldBe "2 identities and a shared token"
      settings.value("Agent authentication", "Identity team-a") shouldBe "paths orders_*, billing_*"
      settings.value("Agent authentication", "Identity ops") shouldBe "every path"
      settings.value("Agent authentication", "Transport filter") shouldBe "disabled"
      settings.value("TLS", "Transport") shouldContain "TLS"
      settings.value("TLS", "Certificate chain") shouldBe "file ${TestCerts.SERVER_CERT}"
      settings.value("TLS", "Private key") shouldBe "PEM, set"
      settings.value("gRPC server", "Handshake timeout") shouldBe "30s"
      settings.value("Agent limits", "Paths per agent") shouldBe "unlimited"
      settings.value("Agent limits", "Path length") shouldBe "300 characters"
      // Off in the config, but forced on by the disabled transport filter, as the proxy runs it.
      settings.value("Agent limits", "Stale agent check") shouldBe
        "on, every 10s, evicting after 45s idle, forced on by the disabled transport filter"
      settings.value("Dashboard", "Login") shouldBe "on, username operator"
      settings.value("Dashboard", "Link Grafana") shouldBe
        "https://grafana.example/d/x?var-job={label.job} (grafana icon)"
      settings.value("About", "Overrides (-D)") shouldBe "proxy.internal.maxPathLength"
    }

    "with nothing configured, the settings should show the defaults, and warn of an open agent port" {
      val settings = ProxySettings.of(proxy(""))

      settings.value("Agent authentication", "Authentication") shouldBe
        "off; any peer that reaches the agent port can register"
      settings.group("Agent authentication").rows.map { it.name } shouldBe
        ["Authentication", "gRPC reflection", "Transport filter"]
      settings.value("Agent authentication", "gRPC reflection") shouldBe "off"
      settings.value("TLS", "Transport") shouldBe "plaintext"
      settings.value("Listeners", "Idle scrape connections") shouldBe "closed after 45s"
      settings.value("Listeners", "Blitz") shouldBe "off"
      settings.value("Scrapes", "Agent backlog") shouldBe "unhealthy at 25, full at 50"
      settings.value("Scrapes", "Scrapes in flight") shouldBe "unhealthy at 800"
      settings.value("gRPC server", "Handshake timeout") shouldBe "gRPC default (120s)"
      settings.value("gRPC server", "Max connection age") shouldBe "gRPC default (no limit)"
      settings.value("Agent limits", "Stale agent check") shouldBe "on, every 10s, evicting after 60s idle"
      settings.value("Service discovery", "Service discovery") shouldBe "off"
      settings.value("Dashboard", "Login") shouldBe "off"
      settings.value("Dashboard", "Links") shouldBe "none"
      settings.value("About", "Overrides (-D)") shouldBe "none"
    }

    // The view is open to whoever reaches the dashboard, behind a login or not, so a secret never reaches it.
    "no token, password, PEM, or -D value should appear anywhere in the settings" {
      val text = ProxySettings.of(proxy(configured, extraArgs = ["-Dproxy.agentToken=tok-d-5"])).text()

      listOf("tok-shared-1", "tok-team-a-2", "tok-ops-3", "dash-pw-4", "tok-d-5", keyBase64)
        .filter { it in text } shouldBe emptyList()
      TestCerts.read(TestCerts.SERVER_KEY).lines().filter { it.length > 20 && it in text } shouldBe emptyList()
      text.lines() shouldContainAll ["Overrides (-D): proxy.agentToken", "Private key: PEM, set"]
    }

    "the authentication mode should say how agents authenticate, and warn as the startup log does" {
      fun mode(
        config: String,
        identities: Int,
      ) = ProxySettings.authenticationMode(proxyOptionsFromConfig(config), identities)

      mode("""proxy.agentToken = "t"""", 0) shouldBe "shared token; tokens sent without TLS"
      mode("", 1) shouldBe "1 identity; tokens sent without TLS"
      mode("", 3) shouldBe "3 identities; tokens sent without TLS"
      mode("", 0) shouldBe "off; any peer that reaches the agent port can register"
    }
  }
}
