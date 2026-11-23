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

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.prometheus.Agent
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.TestCerts.CA_CERT
import io.prometheus.common.TestCerts.CLIENT_CERT
import io.prometheus.common.TestCerts.CLIENT_KEY
import io.prometheus.common.TestCerts.read
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.net.URI

// The typed builder for an embedded agent's in-memory config, and its Kotlin DSL: each method's setting reaches the
// agent's options as a config file's would, credentials are percent-encoded into a path's URL so they survive any
// character, and the built settings print without their secrets.
class EmbeddedAgentConfigTest : StringSpec() {
  // The options an agent started from the config would resolve.
  private fun EmbeddedAgentConfig.options() = AgentOptions(toConfig())

  // The pathConfigs the agent would register.
  private fun EmbeddedAgentConfig.pathConfigs() = options().configVals.agent.pathConfigs

  init {
    "each builder method's setting reaches the agent's options" {
      val options =
        EmbeddedAgentConfig
          .builder()
          .name("my-app-1.0")
          .proxy("proxy-a.example.com:50443", "proxy-b.example.com")
          .token(TOKEN)
          .trustCertificatePem(read(CA_CERT), authority = "prometheus-proxy")
          .clientCertificatePem(read(CLIENT_CERT), read(CLIENT_KEY))
          .consolidated()
          .agentMetrics(9183)
          .build()
          .options()

      options.agentName shouldBe "my-app-1.0"
      options.proxyHostname shouldBe "proxy-a.example.com:50443,proxy-b.example.com:50051"
      options.agentToken shouldBe TOKEN
      options.overrideAuthority shouldBe "prometheus-proxy"
      options.hasTrustCertCollection.shouldBeTrue()
      options.isTlsEnabled.shouldBeTrue()
      options.consolidated.shouldBeTrue()
      options.metricsEnabled.shouldBeTrue()
      options.metricsPort shouldBe 9183
    }

    // In Kotlin, the settings that take one value are properties too.
    "each property sets what its method sets, and reads it back" {
      val byProperty =
        embeddedAgent {
          name = "my-app"
          proxy = "proxy.example.com:50443"
          token = TOKEN
          consolidated = true
          agentMetricsPort = 9183

          name shouldBe "my-app"
          proxy shouldBe "proxy.example.com:50443"
          token shouldBe TOKEN
          consolidated.shouldBeTrue()
          agentMetricsPort shouldBe 9183
        }
      val byMethod =
        embeddedAgent {
          name("my-app")
          proxy("proxy.example.com:50443")
          token(TOKEN)
          consolidated()
          agentMetrics(9183)
        }

      byProperty.toConfig() shouldBe byMethod.toConfig()
    }

    // As PROXY_HOSTNAME does, the proxy property takes several endpoints, separated by commas, and reads them back so.
    "the proxy property takes a comma-separated failover list, as the vararg method does" {
      val config = embeddedAgent { proxy = "proxy-a.example.com:50443, proxy-b.example.com" }
      config.options().proxyHostname shouldBe "proxy-a.example.com:50443,proxy-b.example.com:50051"
      config.toConfig() shouldBe
        embeddedAgent { proxy("proxy-a.example.com:50443", "proxy-b.example.com") }.toConfig()
      val endpoints = embeddedAgent { proxy("a:1", "b:2") }.toConfig().getStringList("agent.proxy.endpoints")
      endpoints shouldBe listOf("a:1", "b:2")
    }

    "a property set to null takes its setting out" {
      val options =
        embeddedAgent {
          name = "my-app"
          token = TOKEN
          agentMetricsPort = 9183
          name = null
          token = null
          agentMetricsPort = null
        }.options()

      options.agentName shouldBe ""
      options.agentToken shouldBe ""
      options.metricsEnabled.shouldBeFalse()
    }

    // The agent's dashboard, off unless a port is given; its host and login are the builder's too.
    "dashboard turns on the agent's dashboard, on its port and host, with a login when one is set" {
      val options =
        EmbeddedAgentConfig
          .builder()
          .dashboard(9195, "127.0.0.1")
          .dashboardLogin(password = DASHBOARD_PASSWORD, username = "ops")
          .build()
          .options()

      options.dashboardEnabled.shouldBeTrue()
      options.dashboardPort shouldBe 9195
      options.dashboardHost shouldBe "127.0.0.1"
      options.dashboardUsername shouldBe "ops"
      options.dashboardPassword shouldBe DASHBOARD_PASSWORD
      EmbeddedAgentConfig.builder().build().options().dashboardEnabled.shouldBeFalse()
    }

    "the dashboard port is a property too, and its password stays out of toString" {
      val config =
        embeddedAgent {
          dashboardPort = 9195
          dashboardPort shouldBe 9195
          dashboardLogin(password = DASHBOARD_PASSWORD)
        }

      config.options().dashboardUsername shouldBe "admin"
      config.toString() shouldNotContain DASHBOARD_PASSWORD
      embeddedAgent {
        dashboardPort = 9195
        dashboardPort = null
      }.options().dashboardEnabled.shouldBeFalse()
    }

    "a builder with nothing set gives the built-in defaults" {
      val options = EmbeddedAgentConfig.builder().build().options()
      options.proxyHostname shouldBe "localhost:50051"
      options.agentToken shouldBe ""
      options.hasTrustCertCollection.shouldBeFalse()
      options.consolidated.shouldBeFalse()
      options.metricsEnabled.shouldBeFalse()
    }

    // The agent sends a URL's user info as Basic Auth, so every character a password can hold has to survive it.
    "scrapeSelf percent-encodes the credentials into the app's URL, so any character survives" {
      val pathConfig =
        embeddedAgent { scrapeSelf(path = "my_app_metrics", port = 8080, username = "agent", password = PASSWORD) }
          .pathConfigs()
          .single()

      pathConfig.path shouldBe "my_app_metrics"
      val url = URI(pathConfig.url)
      url.userInfo shouldBe "agent:$PASSWORD"
      url.host shouldBe "127.0.0.1"
      url.port shouldBe 8080
      url.path shouldBe "/metrics"
    }

    "scrapeSelf takes another metrics path, with or without its slash, and no credentials" {
      val urls =
        embeddedAgent {
          scrapeSelf(path = "a", port = 9000, metricsPath = "/actuator/prometheus")
          scrapeSelf(path = "/b", port = 9001, metricsPath = "stats")
        }.pathConfigs().map { it.path to it.url }

      urls shouldBe listOf("a" to "http://127.0.0.1:9000/actuator/prometheus", "b" to "http://127.0.0.1:9001/stats")
    }

    // An app reads its settings from env vars, which give an empty string when unset.
    "an empty username and password, or an empty certificate, count as none" {
      val config = embeddedAgent { scrapeSelf("app", 8080, username = "", password = "").trustCertificatePem("") }
      config.pathConfigs().single().url shouldBe "http://127.0.0.1:8080/metrics"
      config.toString() shouldContain "trustCertCollectionPem=(none)"
      config.options().hasTrustCertCollection.shouldBeFalse()
    }

    "path splices a username and password into any URL, encoded" {
      val config = embeddedAgent { path("x", "https://sidecar:9443/metrics", "user", "pass word") }
      val url = URI(config.pathConfigs().single().url)
      url.scheme shouldBe "https"
      url.userInfo shouldBe "user:pass word"
      url.host shouldBe "sidecar"
    }

    // Read as the agent reads it: a Compose service name such as my_sidecar isn't a host java.net.URI accepts.
    "path refuses separate credentials for a URL that already has its own" {
      listOf("http://u:p@sidecar:9100/metrics", "http://u:p@my_sidecar:9100/metrics").forEach { url ->
        val e = shouldThrow<ConfigLoadException> { embeddedAgent { path("x", url, "user", "password") } }
        e.message shouldContain "already has credentials"
      }
    }

    "a path's labels are written as the JSON pathConfigs takes" {
      embeddedAgent { scrapeSelf("app", 8080, labels = mapOf("job" to "my-app", "team" to "a b")) }
        .pathConfigs()
        .single()
        .labels shouldBe """{"job":"my-app","team":"a b"}"""
    }

    // A source is code, which no config tree can hold, so the agent gets it apart from the tree, as a static path.
    "source adds a path the agent serves in-process, which the config tree leaves out" {
      val source = MetricsSource { _, _, _ -> "" }
      val config = embeddedAgent { source(path = "/app_metrics", source = source, labels = mapOf("job" to "app")) }

      config.agentOptions().metricsSources.single().apply {
        path shouldBe "app_metrics"
        url shouldBe IN_PROCESS_TARGET
        labels shouldBe """{"job":"app"}"""
        metricsSource shouldBeSameInstanceAs source
      }
      config.toConfig().hasPath("agent.pathConfigs").shouldBeFalse()
      config.options().metricsSources.shouldBeEmpty()
    }

    "a source path without labels gets the empty JSON object pathConfigs gives" {
      embeddedAgent { source("app_metrics") { _, _, _ -> "" } }
        .agentOptions()
        .metricsSources
        .single()
        .labels shouldBe "{}"
    }

    // A source in hand, as from Java, goes after the path, with labels between them or not.
    "source takes a source value after the path, with or without labels" {
      val source = MetricsSource { _, _, _ -> "" }
      val sources =
        embeddedAgent {
          source("a", source)
          source("b", mapOf("job" to "b"), source)
        }.agentOptions().metricsSources

      sources.map { it.path to it.labels } shouldBe listOf("a" to "{}", "b" to """{"job":"b"}""")
      sources.forEach { it.metricsSource shouldBeSameInstanceAs source }
    }

    "toString shows a source path as in-process" {
      embeddedAgent { source("app_metrics") { _, _, _ -> "" } }.toString() shouldContain "app_metrics=in-process"
    }

    // The agent checks a source path as it checks a pathConfigs entry, at startup, where it can be fixed, and names it
    // as what added it, since no pathConfigs entry holds it. Only a duplicate and a character the proxy refuses are
    // checked there: a path the proxy refuses for another reason, such as one with two segments, fails as it registers.
    "a source path that a pathConfigs entry also names, or with a character the proxy refuses, fails the start" {
      fun startFailure(config: EmbeddedAgentConfig): String =
        runCatching { config.start(logBanner = false) }
          .onSuccess { it.shutdown() }
          .exceptionOrNull()
          .shouldBeInstanceOf<ConfigLoadException>()
          .message
          .orEmpty()

      val duplicate =
        startFailure(
          embeddedAgent {
            path("app_metrics", "http://127.0.0.1:9100/metrics")
            source("/app_metrics") { _, _, _ -> "" }
          },
        )
      duplicate shouldContain "Path /app_metrics is given by agent.pathConfigs entry \"app_metrics\" and source()"

      val refused = startFailure(embeddedAgent { source("app metrics") { _, _, _ -> "" } })
      refused shouldContain "Path 'app metrics', given by source(), is one the proxy refuses"
      refused shouldNotContain "pathConfigs"
    }

    "setting sets any agent setting by its key, ahead of a builder method's" {
      val options =
        embeddedAgent {
          name("from-the-builder")
          setting("name", "from-a-setting")
          setting("scrapeTimeoutSecs", 7)
          setting("http.clientTimeoutSecs", 33)
        }.options()

      options.agentName shouldBe "from-a-setting"
      options.scrapeTimeoutSecs shouldBe 7
      options.httpClientTimeoutSecs shouldBe 33
    }

    // An app may log its settings; the token, the private key, and a path's credentials stay out.
    "toString leaves out the token, the private key, and a path's credentials" {
      val shown =
        embeddedAgent {
          name("app")
          token(TOKEN)
          clientCertificatePem(read(CLIENT_CERT), read(CLIENT_KEY))
          trustCertificatePem(read(CA_CERT))
          scrapeSelf("app_metrics", 8080, "user", PASSWORD)
        }.toString()

      shown shouldContain "name=app"
      shown shouldContain "agentToken=(set)"
      shown shouldContain "privateKeyPem=(set)"
      shown shouldContain "url=http://***@127.0.0.1:8080/metrics"
      listOf(TOKEN, "user", "p%40ss", read(CLIENT_KEY).lines()[1]).forEach { shown shouldNotContain it }
    }

    // It's read from the built tree, so what setting() overrides shows too, and a secret set that way stays out.
    "toString shows what setting sets, with its secrets left out" {
      val shown =
        embeddedAgent {
          name("from-the-builder")
          setting("name", "from-a-setting")
          setting("agentToken", TOKEN)
          setting("http.trustStorePassword", PASSWORD)
        }.toString()

      shown shouldContain "name=from-a-setting"
      shown shouldContain "agentToken=(set)"
      shown shouldContain "trustStorePassword=(set)"
      listOf("from-the-builder", TOKEN, PASSWORD).forEach { shown shouldNotContain it }
    }

    // Spring Boot lowercases a map key read from an environment variable, as the starter's settings.* gives them: a
    // secret's key in any case stays out, as the toString an app logs mustn't show a token or a PEM.
    "toString leaves out a secret whose key is in another case" {
      val shown =
        embeddedAgent {
          setting("agenttoken", TOKEN)
          setting("tls.privatekeypem", PEM)
          setting("dashboard.PASSWORD", PASSWORD)
        }.toString()

      shown shouldContain "agenttoken=(set)"
      shown shouldContain "privatekeypem=(set)"
      shown shouldContain "PASSWORD=(set)"
      listOf(TOKEN, PEM, PASSWORD).forEach { shown shouldNotContain it }
    }

    "fromEnvironment sets the proxy, the token, and the certificate from the PROMETHEUS_ variables" {
      val env =
        mapOf(
          "PROMETHEUS_PROXY" to " proxy-a.example.com:50443,proxy-b.example.com ",
          "PROMETHEUS_AGENT_TOKEN" to "$TOKEN\n",
          "PROMETHEUS_PROXY_CERT" to read(CA_CERT),
          "PROMETHEUS_PROXY_AUTHORITY" to "proxy.internal",
        )

      val options = EmbeddedAgentConfig.builder().fromEnvironment(env::get).build().options()

      options.proxyHostname shouldBe "proxy-a.example.com:50443,proxy-b.example.com:50051"
      options.agentToken shouldBe TOKEN
      options.hasTrustCertCollection.shouldBeTrue()
      options.overrideAuthority shouldBe "proxy.internal"
    }

    // The Cloud Platforms page issues the proxy's certificate to prometheus-proxy, as the demos' apps default it
    "fromEnvironment checks the certificate against prometheus-proxy without PROMETHEUS_PROXY_AUTHORITY" {
      val env = mapOf("PROMETHEUS_PROXY_CERT" to read(CA_CERT), "PROMETHEUS_PROXY_AUTHORITY" to " ")

      embeddedAgent { fromEnvironment(env::get) }.options().overrideAuthority shouldBe "prometheus-proxy"
    }

    "fromEnvironment leaves a setting alone when its variable is unset or blank" {
      val env = mapOf("PROMETHEUS_PROXY" to "", "PROMETHEUS_AGENT_TOKEN" to "  ", "PROMETHEUS_PROXY_AUTHORITY" to "x")

      val config =
        embeddedAgent {
          proxy = "proxy.example.com"
          token = TOKEN
          fromEnvironment(env::get)
        }

      config.options().proxyHostname shouldBe "proxy.example.com:50051"
      config.options().agentToken shouldBe TOKEN
      // The authority goes with a certificate, and there is none
      config.options().isTlsEnabled.shouldBeFalse()
      config.toString() shouldNotContain "overrideAuthority"
    }

    "a builder method called after fromEnvironment takes precedence" {
      val env = mapOf("PROMETHEUS_PROXY" to "from-the-environment:50051")

      embeddedAgent {
        fromEnvironment(env::get)
        proxy = "from-the-code:50051"
      }.options().proxyHostname shouldBe "from-the-code:50051"
    }

    // System.getenv can't be set in a test; the tests' environment has none of the variables
    "fromEnvironment reads the process's environment" {
      val fromProcess = embeddedAgent { fromEnvironment() }.toString()

      fromProcess shouldBe
        embeddedAgent { fromEnvironment { name -> System.getenv(name) } }.toString()
    }

    "agentMetricsAt adds an in-process path for the agent's own metrics, which turns on its metrics but no port" {
      val config =
        embeddedAgent {
          agentMetricsAt(path = "/my_app_agent_metrics", labels = mapOf("team" to "web"))
        }
      // An agent built from the settings, never started
      val agent =
        Agent(
          options = config.agentOptions(),
          inProcessServerName = "embedded-config-${System.nanoTime()}",
          testMode = true,
        )
      try {
        val path = agent.pathManager.pathConfigs.single()
        path.path shouldBe "my_app_agent_metrics"
        path.url shouldBe "in-process"
        path.labels shouldBe """{"team":"web"}"""
        path.metricsSource.shouldBeInstanceOf<AgentMetricsSource>()
        // A startup message names the method that added it
        path.origin shouldBe "agentMetricsAt()"
        agent.collectsMetrics.shouldBeTrue()
        agent.isMetricsEnabled.shouldBeFalse()
      } finally {
        agent.grpcService.shutDown()
        agent.agentHttpService.close()
      }
      config.toString() shouldContain "agentMetricsAt=my_app_agent_metrics"
      config.toConfig().hasPath("agent.pathConfigs").shouldBeFalse()
    }

    "agentMetricsAt called again moves the agent's metrics to the new path" {
      val config =
        embeddedAgent {
          agentMetricsAt(path = "first_agent_metrics")
          agentMetricsAt(path = "second_agent_metrics")
        }

      config.agentOptions().agentMetricsPath shouldBe "second_agent_metrics"
      config.agentOptions().agentMetricsLabels shouldBe "{}"
    }

    "source with a registry serves it in-process, with or without labels" {
      val registry = PrometheusRegistry()
      val sources =
        embeddedAgent {
          source(path = "app_metrics", registry = registry)
          source(path = "jobs_metrics", labels = mapOf("team" to "batch"), registry = registry)
        }.agentOptions().metricsSources

      sources.map { it.path } shouldBe listOf("app_metrics", "jobs_metrics")
      sources.map { it.labels } shouldBe listOf("{}", """{"team":"batch"}""")
      sources.forEach { it.metricsSource.shouldBeInstanceOf<RegistryMetricsSource>() }
    }

    // As a setting the agent rejects throws ConfigLoadException when it starts, so does a value the builder rejects.
    "a blank path, a port out of range, or a URL with no scheme throws ConfigLoadException" {
      shouldThrow<ConfigLoadException> { embeddedAgent { agentMetricsAt(path = "/") } }
      shouldThrow<ConfigLoadException> { embeddedAgent { scrapeSelf(" ", 8080) } }
      shouldThrow<ConfigLoadException> { embeddedAgent { scrapeSelf("app", 0) } }
      shouldThrow<ConfigLoadException> { embeddedAgent { path("", "http://host/metrics") } }
      shouldThrow<ConfigLoadException> { embeddedAgent { source("/") { _, _, _ -> "" } } }
      shouldThrow<ConfigLoadException> { embeddedAgent { path("app", "sidecar:9100/metrics", "user", "pw") } }
      shouldThrow<ConfigLoadException> { embeddedAgent { agentMetrics(70000) }.options() }
    }
  }

  companion object {
    private const val DASHBOARD_PASSWORD = "dash-Passw0rd-9"
    private const val TOKEN = "0123456789abcdef0123456789abcdef"

    // Every character a URL's user info must escape, and some it need not
    private const val PASSWORD = "p@ss:w/rd%#+ ?&é"

    // Shaped like a private key, so a key that isn't masked shows it
    private const val PEM = "-----BEGIN PRIVATE KEY-----MIIEvQIBADANBg-----END PRIVATE KEY-----"
  }
}
