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

package io.prometheus.agent.dashboard

import com.pambrose.common.util.runCatchingCancellable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.Agent
import io.prometheus.agent.EmbeddedAgentConfig
import io.prometheus.agent.createTempKeyStore
import io.prometheus.agent.embeddedAgent
import io.prometheus.common.TestCerts
import io.prometheus.common.TestPorts
import io.prometheus.common.agentOptionsFromConfig
import io.prometheus.common.dashboard.text
import io.prometheus.common.dashboard.value

/**
 * [AgentSettings], from an agent built but never started: what it resolved, worded as the view shows it, and no secret
 * anywhere in it, neither its token, the trust store's or the dashboard's password, a PEM, nor a `-D` override's value.
 * The wording both dashboards share is covered in `SettingsViewTest` and `CommonSettingsTest`.
 */
class AgentSettingsTest : StringSpec() {
  private val created = mutableListOf<Agent>()

  private fun agent(
    config: String,
    extraArgs: List<String> = emptyList(),
  ): Agent =
    Agent(
      agentOptionsFromConfig(config, extraArgs = extraArgs),
      inProcessServerName = "agent-settings-${System.nanoTime()}",
      testMode = true,
    ).also { created += it }

  private val keyBase64 = TestCerts.base64(TestCerts.CLIENT_KEY)

  // A trust store the agent can load as it's built, and its password.
  private val trustStore = createTempKeyStore("agent-settings")

  private val configured =
    """
    agent {
      name = "east"
      agentToken = "tok-3n7"
      scrapeTimeoutSecs = 7
      proxy.endpoints = ["proxy-a:50051", "proxy-b:50052"]
      http {
        trustStorePath = "${trustStore.first}"
        trustStorePassword = "${trustStore.second}"
        clientCache.cleanupIntervalMins = 7
      }
      internal.cioTimeoutSecs = 45
      tls {
        certChainFilePath = "${TestCerts.CLIENT_CERT}"
        privateKeyPem = "$keyBase64"
        trustCertCollectionFilePath = "${TestCerts.CA_CERT}"
        overrideAuthority = "${TestCerts.SERVER_AUTHORITY}"
      }
      dashboard { enabled = true, password = "dash-pw-9" }
      pathConfigs = [{ name = "Node", path = "node_metrics", url = "http://node:9100/metrics" }]
      filters = [{ path = "node_metrics", metricNameAllow = [], metricNameDeny = ["go_.*"] }]
    }
    """

  init {
    afterTest {
      created.forEach { agent ->
        runCatching { agent.grpcService.shutDown() }
        runCatchingCancellable { agent.agentHttpService.close() }
      }
      created.clear()
    }

    "the settings should come in groups, in the order an operator reads them" {
      AgentSettings.of(agent("")).map { it.title } shouldBe
        ["About", "Proxy connection", "Scraping", "Paths", "TLS", "Dashboard", "Admin", "Metrics", "Tracing", "Logging"]
    }

    "the settings should show what the agent resolved, worded as the view shows it" {
      val settings = AgentSettings.of(agent(configured))

      settings.value("About", "Agent name") shouldBe "east"
      settings.value("Proxy connection", "Proxy endpoints") shouldBe "proxy-a:50051, proxy-b:50052"
      settings.value("Proxy connection", "Token") shouldBe "set"
      settings.value("Scraping", "Scrape timeout") shouldBe "7s"
      // The deprecated cioTimeoutSecs, overridden while clientTimeoutSecs keeps its default, is the timeout in use.
      settings.value("Scraping", "HTTP clients") shouldBe "1 at once, 45s timeout"
      settings.value("Scraping", "HTTP client cache") shouldContain "cleaned every 7 min"
      settings.value("Scraping", "Trust store") shouldBe trustStore.first
      settings.value("Scraping", "Trust store password") shouldBe "set"
      settings.value("Paths", "Configured paths") shouldBe "1"
      settings.value("Paths", "Metric filters") shouldBe "1"
      settings.value("TLS", "Transport") shouldContain "TLS"
      settings.value("TLS", "Certificate chain") shouldBe "file ${TestCerts.CLIENT_CERT}"
      settings.value("TLS", "Private key") shouldBe "PEM, set"
      settings.value("TLS", "Trusted certificates") shouldBe "file ${TestCerts.CA_CERT}"
      settings.value("TLS", "Override authority") shouldBe TestCerts.SERVER_AUTHORITY
      settings.value("Dashboard", "Login") shouldBe "on, username admin"
    }

    "with nothing configured, the settings should show the defaults" {
      val settings = AgentSettings.of(agent(""))

      settings.value("Proxy connection", "Proxy endpoints") shouldBe "localhost:50051"
      settings.value("Proxy connection", "Token") shouldBe "not set"
      settings.value("Proxy connection", "Keepalive time") shouldBe "gRPC default"
      settings.value("Proxy connection", "Heartbeat") shouldBe "on, after 5s idle, checked every 500 ms"
      settings.value("Scraping", "HTTP clients") shouldBe "1 at once, 90s timeout"
      settings.value("Scraping", "Target certificates") shouldBe "verified"
      settings.value("Scraping", "Trust store") shouldBe "JDK default"
      settings.value("Paths", "Refused path retry") shouldBe "after 10s, doubling up to 300s"
      settings.value("Paths", "Discovery") shouldBe "off"
      settings.value("TLS", "Transport") shouldBe "plaintext"
      settings.value("TLS", "Override authority") shouldBe "–"
      settings.value("Dashboard", "Login") shouldBe "off"
    }

    "with discovery on, the settings should name its file and how often it's read" {
      val file = kotlin.io.path.createTempFile("targets", ".conf").toFile().apply { writeText("discovered = []") }
      try {
        AgentSettings
          .of(agent("""agent.discovery { enabled = true, file.path = "${file.path}", reconcileIntervalSecs = 15 }"""))
          .value("Paths", "Discovery") shouldBe "on, file ${file.path}, read every 15s"
      } finally {
        file.delete()
      }
    }

    // An embedded agent's own metrics, served through the proxy with agentMetricsAt, are on though their port is off
    "the settings should show an embedded agent's metrics served in-process, and their port when it's on too" {
      fun metricsShown(configure: EmbeddedAgentConfig.Builder.() -> Unit): String {
        val built =
          Agent(
            embeddedAgent(configure).agentOptions(),
            inProcessServerName = "agent-settings-${System.nanoTime()}",
            testMode = true,
          ).also { created += it }
        return AgentSettings.of(built).value("Metrics", "Metrics")
      }

      metricsShown { agentMetricsAt(path = "east_agent_metrics") } shouldBe
        "on, in-process, at the proxy's /east_agent_metrics"
      metricsShown {
        agentMetrics(port = TestPorts.AGENT_METRICS_PORT)
        agentMetricsAt(path = "east_agent_metrics")
      } shouldBe
        "on, port ${TestPorts.AGENT_METRICS_PORT}, /metrics, and in-process, at the proxy's /east_agent_metrics"
      metricsShown {} shouldBe "off"
    }

    // The view is open to whoever reaches the dashboard, behind a login or not, so a secret never reaches it.
    "no token, password, PEM, or -D value should appear anywhere in the settings" {
      val text = AgentSettings.of(agent(configured, extraArgs = ["-Dagent.agentToken=tok-d-6"])).text()

      listOf("tok-3n7", trustStore.second, "dash-pw-9", "tok-d-6", keyBase64).filter { it in text } shouldBe
        emptyList()
      TestCerts.read(TestCerts.CLIENT_KEY).lines().filter { it.length > 20 && it in text } shouldBe emptyList()
      text shouldContain "Overrides (-D): agent.agentToken"
    }
  }
}
