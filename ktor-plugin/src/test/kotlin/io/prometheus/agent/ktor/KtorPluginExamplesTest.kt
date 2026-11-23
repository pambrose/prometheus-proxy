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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.prometheus.metrics.model.registry.PrometheusRegistry
import website.agentHealth
import website.module
import website.moduleWithFileSettings
import java.net.ServerSocket

// The docs site's Ktor Plugin page shows website/KtorPluginExample.kt and website/application.conf: they do what the
// page says.
class KtorPluginExamplesTest : StringSpec() {
  init {
    "the page's module runs without the agent where PROMETHEUS_PROXY isn't set, and its health says so" {
      var app: Application? = null
      testApplication {
        application {
          app = this
          module()
        }

        // The tests' environment has no PROMETHEUS_PROXY
        client.get("/health/agent").bodyAsText() shouldBe "off"
        app?.prometheusProxyAgent.shouldBeNull()
      }
    }

    "the page's application.conf starts the agent with the environment's proxy, serving the registry at its path" {
      val proxy = "localhost:${ServerSocket(0).use { it.localPort }}"
      // As Ktor loads application.conf, whose ${?…} substitutions read the environment and system properties
      val file =
        ConfigFactory
          .parseResources("website/application.conf")
          .withFallback(
            ConfigFactory.parseMap(mapOf("PROMETHEUS_PROXY" to proxy, "PROMETHEUS_AGENT_TOKEN" to "s3cret")),
          )
          .resolve()
      var app: Application? = null
      testApplication {
        environment { config = HoconApplicationConfig(file) }
        application {
          app = this
          moduleWithFileSettings()
          routing { agentHealth() }
        }

        // The proxy is a port nothing listens on, so the agent keeps trying to connect
        client.get("/health/agent").bodyAsText() shouldBe "connecting"
        app?.prometheusProxyAgent.shouldNotBeNull().agentName shouldBe "my-app"
      }
    }

    "the page's application.conf leaves the agent off without PROMETHEUS_PROXY" {
      val file = ConfigFactory.parseResources("website/application.conf").resolve()
      var app: Application? = null
      testApplication {
        environment { config = HoconApplicationConfig(file) }
        application {
          app = this
          moduleWithFileSettings()
        }
        startApplication()

        app?.prometheusProxyAgent.shouldBeNull()
      }
    }

    "the page's application.conf builds the settings the page describes" {
      val file =
        ConfigFactory
          .parseResources("website/application.conf")
          .withFallback(ConfigFactory.parseMap(mapOf("PROMETHEUS_PROXY" to "proxy.example.com:50051")))
          .resolve()
      val config = PrometheusProxyAgentConfig(HoconApplicationConfig(file).config("prometheus-proxy.agent"))
      config.registry = PrometheusRegistry()

      val shown = config.builder().build().toString()

      shown shouldContain "endpoints=[proxy.example.com:50051]"
      shown shouldContain "name=my-app"
      shown shouldContain "sources={my_app_metrics=in-process}"
      config.labels shouldBe mapOf("team" to "web")
    }
  }
}
