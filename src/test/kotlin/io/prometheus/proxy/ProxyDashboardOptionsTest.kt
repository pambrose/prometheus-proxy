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

import ch.qos.logback.classic.Level
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain as shouldContainElement
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.common.BaseOptions.Companion.failOnInvalidConfig
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.TestPorts.PROXY_DASHBOARD_PORT
import io.prometheus.common.captureLogs
import io.prometheus.common.proxyOptions
import io.prometheus.common.proxyOptionsFromConfig
import io.prometheus.proxy.dashboard.LinkIcon

/**
 * The dashboard's options, `proxy.dashboard` and the `--dashboard*` flags: their defaults, what they refuse while the
 * dashboard is on, and what is logged at startup. Split from [ProxyOptionsTest], which keeps the rest of
 * `ProxyOptions`.
 */
class ProxyDashboardOptionsTest : StringSpec() {
  init {
    // ==================== Enabling, Listening, and Sessions ====================

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

    "an empty dashboardPath should be rejected when the dashboard is enabled" {
      val exception =
        shouldThrow<IllegalArgumentException> {
          proxyOptionsFromConfig("""proxy.dashboard.path = "" """, extraArgs = ["--dashboard"])
        }
      exception.message shouldContain "dashboardPath is empty"
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

    "the dashboard's maxSessions should be logged at startup when the dashboard is enabled" {
      captureLogs<ProxyOptions>(Level.INFO) { proxyOptions(["--dashboard"]) }
        .map { it.formattedMessage } shouldContainElement "dashboard.maxSessions: 50"
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

    // ==================== Refresh, Path History, and Activity ====================

    // The dashboard's push loop waits refreshIntervalSecs between pushes; at 0 the wait returns at once, so the loop
    // spun a thread at 100%.
    "a non-positive dashboard refreshIntervalSecs should be rejected when the dashboard is enabled" {
      val exception = shouldThrow<IllegalArgumentException> {
        proxyOptions(["--dashboard", "-Dproxy.dashboard.refreshIntervalSecs=0"])
      }
      exception.message shouldContain "refreshIntervalSecs"
    }

    "a dashboard refreshIntervalSecs of 0 should be accepted while the dashboard is off" {
      proxyOptions(["-Dproxy.dashboard.refreshIntervalSecs=0"]).dashboardEnabled.shouldBeFalse()
    }

    // At 0 the path table would keep no scrape for any path, so it would lose its gone paths, the thing it is for, and
    // the activity feed would keep no line.
    "a non-positive dashboard pathHistorySize, departedPathRetentionSecs, or activityLogSize should be rejected" {
      for (name in ["pathHistorySize", "departedPathRetentionSecs", "activityLogSize"]) {
        shouldThrow<IllegalArgumentException> {
          proxyOptions(["--dashboard", "-Dproxy.dashboard.$name=0"])
        }.message shouldContain name
      }
    }

    "the dashboard's path history and activity settings should be logged at startup when the dashboard is enabled" {
      captureLogs<ProxyOptions>(Level.INFO) { proxyOptions(["--dashboard"]) }
        .map { it.formattedMessage }
        .apply {
          this shouldContainElement "dashboard.pathHistorySize: 20"
          this shouldContainElement "dashboard.departedPathRetentionSecs: 3600"
          this shouldContainElement "dashboard.activityLogSize: 500"
        }
    }

    // ==================== Dashboard links ====================

    // A list, so only a config file sets it. Parsed once, here, so ProxyOptions must keep what it parsed: a property
    // initializer below the init block would empty it again.
    "dashboard links should be parsed from the config file in order, and logged by name" {
      val config =
        """
        proxy.dashboard.links = [
          { name = "Prometheus", url = "http://prometheus:9090/graph?g0.expr=up%7Bjob%3D%22{label.job}%22%7D" }
          { name = "Runbook", url = "https://runbooks.example.com/{path}" }
        ]
        """
      var options: ProxyOptions? = null
      captureLogs<ProxyOptions>(Level.INFO) { options = proxyOptionsFromConfig(config, extraArgs = ["--dashboard"]) }
        .map { it.formattedMessage } shouldContainElement "dashboard.links: Prometheus, Runbook"
      options.shouldNotBeNull().dashboardLinks.map { it.name } shouldBe ["Prometheus", "Runbook"]
      proxyOptions(["--dashboard"]).dashboardLinks.shouldBeEmpty()
    }

    // Config-only, so failOnInvalidConfig is how a standalone proxy reports it: one Invalid configuration line, naming
    // the link (PathLinkTest has each refusal). Unchecked with the dashboard off, as its other settings are.
    "a dashboard link the dashboard can't render should fail startup, naming it, while the dashboard is on" {
      val links = """proxy.dashboard.links = [{ name = "Grafana", url = "https://g.example.com/d?job={foo}" }]"""
      shouldThrow<ConfigLoadException> {
        failOnInvalidConfig(embedded = true) { proxyOptionsFromConfig(links, extraArgs = ["--dashboard"]) }
      }.message shouldStartWith "Invalid configuration: proxy.dashboard.links link 'Grafana' has an unknown placeholder"
      proxyOptionsFromConfig(links).dashboardLinks.shouldBeEmpty()
    }

    // The key is optional, as config/config.conf declares it; PathLinkTest has how it is matched.
    "a dashboard link's icon should be read from the config file, and logged with the link" {
      val config =
        """
        proxy.dashboard.links = [
          { name = "Grafana", url = "https://g.example.com/d?job={label.job}", icon = "grafana" }
          { name = "Runbook", url = "https://runbooks.example.com/{path}" }
        ]
        """
      var options: ProxyOptions? = null
      captureLogs<ProxyOptions>(Level.INFO) { options = proxyOptionsFromConfig(config, extraArgs = ["--dashboard"]) }
        .map { it.formattedMessage } shouldContainElement "dashboard.links: Grafana (grafana icon), Runbook"
      options.shouldNotBeNull().dashboardLinks.map { it.icon } shouldBe [LinkIcon.GRAFANA, null]
    }

    "a dashboard link with an icon the dashboard doesn't have should fail startup, naming it" {
      val links = """proxy.dashboard.links = [{ name = "Logs", url = "https://logs.example.com", icon = "loki" }]"""
      shouldThrow<ConfigLoadException> {
        failOnInvalidConfig(embedded = true) { proxyOptionsFromConfig(links, extraArgs = ["--dashboard"]) }
      }.message shouldStartWith
        "Invalid configuration: proxy.dashboard.links link 'Logs' has an unknown icon 'loki'; use prometheus or grafana"
    }
  }
}
