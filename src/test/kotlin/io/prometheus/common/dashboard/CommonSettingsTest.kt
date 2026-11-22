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

package io.prometheus.common.dashboard

import com.pambrose.common.service.AdminConfig
import com.pambrose.common.service.MetricsConfig
import com.pambrose.common.service.ZipkinConfig
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith
import io.prometheus.agent.AgentOptions
import io.prometheus.common.TestCerts
import io.prometheus.common.proxyOptionsFromConfig
import io.prometheus.proxy.ProxyOptions
import kotlin.time.Duration.Companion.minutes

/**
 * The groups both dashboards' Settings views share ([CommonSettings]), from options built here, and where the config
 * came from (`BaseOptions.configShown`). That no secret reaches a whole view is covered on each side, in
 * `ProxySettingsTest` and `AgentSettingsTest`.
 */
class CommonSettingsTest : StringSpec() {
  private fun metrics(enabled: Boolean) =
    MetricsConfig(
      enabled = enabled,
      port = 8082,
      path = "metrics",
      standardExportsEnabled = true,
      memoryPoolsExportsEnabled = false,
      garbageCollectorExportsEnabled = true,
      threadExportsEnabled = false,
      classLoadingExportsEnabled = false,
      versionInfoExportsEnabled = false,
      nativeMemoryExportsEnabled = true,
    )

  init {
    "the About group should name the version, launch, start, config, and only the keys of the -D overrides" {
      val options =
        proxyOptionsFromConfig("", extraArgs = ["-Dproxy.agentToken=tok-d-5", "-Dproxy.http.idleTimeoutSecs=30"])

      CommonSettings.about(options, "launch-9", 5.minutes, leading = [Setting("Agent name", "east")]).apply {
        rows.map { it.name } shouldBe ["Version", "Agent name", "Launch ID", "Started", "Config", "Overrides (-D)"]
        value("Launch ID") shouldBe "launch-9"
        value("Config") shouldEndWith ".conf"
        value("Overrides (-D)") shouldBe "proxy.agentToken, proxy.http.idleTimeoutSecs"
        rows.none { "tok-d-5" in it.value } shouldBe true
        rows.single { it.name == "Started" }.at.shouldNotBeNull()
      }
      CommonSettings.about(proxyOptionsFromConfig(""), "l", 1.minutes).value("Overrides (-D)") shouldBe "none"
    }

    // A file's path is no secret; a PEM is, so only that it's set.
    "the TLS group should name each file, and only whether a PEM is set" {
      val options =
        proxyOptionsFromConfig(
          """
          proxy.tls {
            certChainFilePath = "${TestCerts.SERVER_CERT}"
            privateKeyPem = "${TestCerts.base64(TestCerts.SERVER_KEY)}"
          }
          """,
        )

      CommonSettings.tls(options, "TLS", [Setting("Override authority", "x")]).apply {
        value("Transport") shouldBe "TLS"
        value("Certificate chain") shouldBe "file ${TestCerts.SERVER_CERT}"
        value("Private key") shouldBe "PEM, set"
        value("Trusted certificates") shouldBe "not set"
        value("Override authority") shouldBe "x"
      }
    }

    "the Admin group should show its port and endpoints, or that it's off" {
      val admin = AdminConfig(true, 8092, "ping", "/version", "healthcheck", "threaddump")

      CommonSettings.admin(admin, debugEnabled = true).apply {
        value("Admin") shouldBe "on, port 8092"
        value("Endpoints") shouldBe "/ping, /version, /healthcheck, /threaddump"
        value("Debug servlet") shouldBe "on"
      }
      CommonSettings.admin(admin.copy(enabled = false), debugEnabled = false).value("Admin") shouldBe "off"
    }

    "the Metrics group should show its endpoint and the JVM metrics that are on" {
      CommonSettings.metrics(metrics(true)).apply {
        value("Metrics") shouldBe "on, port 8082, /metrics"
        value("JVM metrics") shouldBe "standard, garbage collector, native memory"
      }
      CommonSettings.metrics(
        metrics(false).copy(
          standardExportsEnabled = false,
          garbageCollectorExportsEnabled = false,
          nativeMemoryExportsEnabled = false,
        ),
      ).apply {
        value("Metrics") shouldBe "off"
        value("JVM metrics") shouldBe "none"
      }
    }

    // A page shows the dashboard's username, which whoever reads it already logged in with, and never its password.
    "the Dashboard group should show where it listens and its login, never the password" {
      val options =
        proxyOptionsFromConfig(
          """proxy.dashboard { enabled = true, username = "operator", password = "dash-pw-4", host = "127.0.0.1" }""",
        )

      CommonSettings
        .dashboard(
          options = options,
          values =
            DashboardValues(
              refreshIntervalSecs = 2,
              pathHistorySize = 20,
              activityLogSize = 500,
              scrapeNowEnabled = false,
              maxSessions = 50,
              allowedOrigins = emptyList(),
              allowedHosts = ["proxy.example", "proxy"],
            ),
          extra = [Setting("Departed paths kept", "3600s")],
        ).apply {
          value("Listening on") shouldBe "127.0.0.1:8094/dashboard"
          value("Login") shouldBe "on, username operator"
          value("Refresh") shouldBe "every 2s"
          value("Scrape now") shouldBe "off"
          value("Allowed origins") shouldBe "the dashboard's own host"
          value("Allowed hosts") shouldBe "proxy.example, proxy"
          value("Departed paths kept") shouldBe "3600s"
          rows.none { "dash-pw-4" in it.value || "dash-pw-4" in it.name } shouldBe true
        }
    }

    "the Tracing and Logging groups should show Zipkin and the log level" {
      CommonSettings.tracing(ZipkinConfig(true, "zipkin", 9411, "/api/v2/spans", "prometheus-proxy"))
        .value("Zipkin") shouldBe "on, zipkin:9411/api/v2/spans, service prometheus-proxy"
      CommonSettings.tracing(ZipkinConfig(false, "zipkin", 9411, "api/v2/spans", "x")).value("Zipkin") shouldBe "off"
      CommonSettings.logging("").value("Log level") shouldBe "info"
      CommonSettings.logging("warn").value("Log level") shouldBe "warn"
    }

    // Parsed only, so the URL is never fetched: what the view shows is the URL as every message names it.
    "where the config came from should be the file, the URL redacted, or in memory" {
      ProxyOptions(arrayOf("--config", "https://ops:s3cret@config.example/proxy.conf?token=abc"), parseOnly = true)
        .configShown shouldBe "https://***@config.example/proxy.conf?token=***"
      AgentOptions(ConfigFactory.parseString("agent.name = in-memory")).configShown shouldBe "in memory"
      proxyOptionsFromConfig("").configShown shouldStartWith "/"
      // Neither --config nor PROXY_CONFIG, which the test JVM doesn't set.
      ProxyOptions(emptyArray(), parseOnly = true).configShown shouldBe "built-in defaults"
    }
  }
}
