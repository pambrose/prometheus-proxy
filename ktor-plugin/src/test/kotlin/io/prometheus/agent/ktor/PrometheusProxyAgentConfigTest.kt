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

package io.prometheus.agent.ktor

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.yaml.YamlConfig
import io.prometheus.agent.EmbeddedAgentConfig
import io.prometheus.common.ConfigLoadException
import io.prometheus.metrics.model.registry.PrometheusRegistry

class PrometheusProxyAgentConfigTest : StringSpec() {
  init {
    "an empty section sets nothing, and leaves the agent enabled and not required" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig())

      config.enabled shouldBe true
      config.required shouldBe false
      config.path.shouldBeNull()
      config.labels.shouldBeEmpty()
      config.builder().build().toConfig() shouldBe EmbeddedAgentConfig.builder().build().toConfig()
    }

    "a HOCON section sets what the same builder calls would" {
      val config = PrometheusProxyAgentConfig(hocon(FULL_SECTION))

      config.enabled shouldBe true
      config.required shouldBe true
      config.path shouldBe "my_app_metrics"
      config.labels shouldBe mapOf("team" to "web")
      config.builder().build().toConfig() shouldBe expectedTree(timeout = 5, clientTimeout = 10)
    }

    "a YAML section sets the same" {
      val config = PrometheusProxyAgentConfig(yaml("full-section.yaml"))

      config.required shouldBe true
      config.path shouldBe "my_app_metrics"
      config.labels shouldBe mapOf("team" to "web")
      config.builder().build().toConfig() shouldBe expectedTree(timeout = "5", clientTimeout = "10")
    }

    "a YAML value from an environment variable that isn't set counts as unset" {
      PrometheusProxyAgentConfig(yaml("unset-proxy.yaml")).builder().proxy.shouldBeNull()
    }

    // A MapApplicationConfig holds every value as text, and a list as numbered keys
    "a section of text values sets the same" {
      val config =
        PrometheusProxyAgentConfig(
          MapApplicationConfig(
            "required" to "true",
            "name" to "my-app",
            "proxy" to "proxy-a:50051,proxy-b",
            "token" to "s3cret",
            "trust-certificate" to "TRUSTED",
            "authority" to "prometheus-proxy",
            "client-certificate" to "CHAIN",
            "client-key" to "KEY",
            "consolidated" to "true",
            "metrics-port" to "9101",
            "metrics-proxy-path" to "my_app_agent_metrics",
            "metrics-proxy-path-labels.team" to "agents",
            "dashboard.port" to "9102",
            "dashboard.host" to "127.0.0.1",
            "dashboard.username" to "ops",
            "dashboard.password" to "pw",
            "paths.size" to "1",
            "paths.0.path" to "jobs_metrics",
            "paths.0.url" to "http://localhost:9000/metrics",
            "paths.0.username" to "scraper",
            "paths.0.password" to "secret",
            "paths.0.labels.team" to "jobs",
            "settings.scrapeTimeoutSecs" to "5",
            "settings.http.clientTimeoutSecs" to "10",
            "path" to "my_app_metrics",
            "labels.team" to "web",
          ),
        )

      config.required shouldBe true
      config.path shouldBe "my_app_metrics"
      config.labels shouldBe mapOf("team" to "web")
      config.builder().build().toConfig() shouldBe expectedTree(timeout = "5", clientTimeout = "10")
    }

    "a blank value counts as unset, as an unset environment variable gives" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig("proxy" to "", "token" to " ", "path" to ""))

      config.path.shouldBeNull()
      config.builder().proxy.shouldBeNull()
      config.builder().token.shouldBeNull()
    }

    "a client key alone still sets the client certificate, for the agent to reject" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig("client-key" to "KEY"))

      config.builder().build().toString() shouldContain "certChainPem=(none), privateKeyPem=(set)"
    }

    "the dashboard's login defaults to admin" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig("dashboard.password" to "pw"))

      config.builder().build().toString() shouldContain "username=admin"
    }

    "enabled and required that aren't true or false fail at once" {
      shouldThrow<ConfigLoadException> {
        PrometheusProxyAgentConfig(MapApplicationConfig("enabled" to "yes"))
      }.message shouldBe "prometheus-proxy.agent.enabled must be true or false: yes"
    }

    "a port that isn't a number fails the settings" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig("metrics-port" to "ninety"))

      shouldThrow<ConfigLoadException> { config.builder() }.message shouldBe
        "prometheus-proxy.agent.metrics-port must be a number: ninety"
    }

    "the registry is served in-process at the path" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig("path" to "my_app_metrics", "labels.team" to "web"))
      config.registry = PrometheusRegistry()

      val shown = config.builder().build().toString()

      shown shouldContain "sources={my_app_metrics=in-process}"
    }

    "a registry without a path fails the settings" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig())
      config.registry = PrometheusRegistry()

      shouldThrow<ConfigLoadException> { config.builder() }.message shouldBe
        "prometheus-proxy.agent.path is required to serve a registry"
    }

    "the install block's settings take precedence over the file's" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig("proxy" to "from-file:50051", "path" to "file_path"))
      config.path = "code_path"
      config.registry = PrometheusRegistry()
      config.agent { proxy = "from-code:50051" }

      val builder = config.builder()

      builder.proxy shouldBe "from-code:50051"
      builder.build().toString() shouldContain "code_path=in-process"
      builder.build().toString() shouldNotContain "file_path"
    }

    "every agent block runs, in turn" {
      val config = PrometheusProxyAgentConfig(MapApplicationConfig())
      config.agent { name = "first" }
      config.agent { token = "s3cret" }
      config.agent { name = "second" }

      val builder = config.builder()

      builder.name shouldBe "second"
      builder.token shouldBe "s3cret"
    }
  }

  private companion object {
    val FULL_SECTION =
      """
      enabled = true
      required = true
      name = my-app
      proxy = "proxy-a:50051,proxy-b"
      token = s3cret
      trust-certificate = TRUSTED
      authority = prometheus-proxy
      client-certificate = CHAIN
      client-key = KEY
      consolidated = true
      metrics-port = 9101
      metrics-proxy-path = my_app_agent_metrics
      metrics-proxy-path-labels { team = agents }
      dashboard { port = 9102, host = "127.0.0.1", username = ops, password = pw }
      paths = [
        {
          path = jobs_metrics
          url = "http://localhost:9000/metrics"
          username = scraper
          password = secret
          labels { team = jobs }
        }
      ]
      settings { scrapeTimeoutSecs = 5, http.clientTimeoutSecs = 10 }
      path = my_app_metrics
      labels { team = web }
      """.trimIndent()

    fun hocon(section: String): ApplicationConfig = HoconApplicationConfig(ConfigFactory.parseString(section))

    // The plugin's section of a YAML resource, as Ktor hands it to the plugin
    fun yaml(resource: String): ApplicationConfig =
      requireNotNull(YamlConfig(resource)) { "no $resource" }.config("prometheus-proxy.agent")

    // The tree the builder calls give that a FULL_SECTION file does; settings keep the type the file gives them
    fun expectedTree(
      timeout: Any,
      clientTimeout: Any,
    ) = EmbeddedAgentConfig
      .builder()
      .apply {
        name = "my-app"
        proxy = "proxy-a:50051,proxy-b"
        token = "s3cret"
        trustCertificatePem(pem = "TRUSTED", authority = "prometheus-proxy")
        clientCertificatePem(certChainPem = "CHAIN", privateKeyPem = "KEY")
        consolidated = true
        agentMetricsPort = 9101
        agentMetricsAt(path = "my_app_agent_metrics", labels = mapOf("team" to "agents"))
        dashboard(port = 9102, host = "127.0.0.1")
        dashboardLogin(password = "pw", username = "ops")
        path(
          path = "jobs_metrics",
          url = "http://localhost:9000/metrics",
          username = "scraper",
          password = "secret",
          labels = mapOf("team" to "jobs"),
        )
        setting(key = "scrapeTimeoutSecs", value = timeout)
        setting(key = "http.clientTimeoutSecs", value = clientTimeout)
      }.build()
      .toConfig()
  }
}
