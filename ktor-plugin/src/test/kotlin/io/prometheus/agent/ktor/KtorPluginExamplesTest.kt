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
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.prometheus.metrics.model.registry.PrometheusRegistry
import website.agentHealth
import website.module
import website.moduleWithFileSettings

// The docs site's Ktor Plugin page shows website/KtorPluginExample.kt and website/application.conf: they do what the
// page says.
class KtorPluginExamplesTest : StringSpec() {
  init {
    "the page's module runs without the agent where PROMETHEUS_PROXY isn't set, and its health says so" {
      testApplication {
        application { module() }

        // The tests' environment has no PROMETHEUS_PROXY
        client.get("/health/agent").bodyAsText() shouldBe "off"
        application.prometheusProxyAgent.shouldBeNull()
      }
    }

    "the page's application.conf starts the agent, with the environment's proxy and the file's name" {
      val proxy = "localhost:${closedPort()}"
      testApplication {
        environment { config = pageConfig("PROMETHEUS_PROXY" to proxy, "PROMETHEUS_AGENT_TOKEN" to "s3cret") }
        application {
          moduleWithFileSettings()
          routing { agentHealth() }
        }

        // The proxy is a port nothing listens on, so the agent keeps trying to connect
        client.get("/health/agent").bodyAsText() shouldBe "connecting"
        application.prometheusProxyAgent.shouldNotBeNull().agentName shouldBe "my-app"
      }
    }

    "the page's application.conf leaves the agent off without PROMETHEUS_PROXY" {
      testApplication {
        environment { config = pageConfig() }
        application { moduleWithFileSettings() }
        startApplication()

        application.prometheusProxyAgent.shouldBeNull()
      }
    }

    "the page's application.conf builds the settings the page describes" {
      val config =
        PrometheusProxyAgentConfig(pageConfig("PROMETHEUS_PROXY" to "proxy.example.com:50051").config(SECTION))
      config.registry = PrometheusRegistry()

      val shown = config.builder().build().toString()

      shown shouldContain "endpoints=[proxy.example.com:50051]"
      shown shouldContain "name=my-app"
      shown shouldContain "sources={my_app_metrics=in-process}"
      config.labels shouldBe mapOf("team" to "web")
    }
  }

  private companion object {
    const val SECTION = "prometheus-proxy.agent"

    // The page's application.conf, as Ktor loads it, with env standing in for the environment its ${?…} substitutions
    // read
    fun pageConfig(vararg env: Pair<String, String>) =
      HoconApplicationConfig(
        ConfigFactory
          .parseResources("website/application.conf")
          .withFallback(ConfigFactory.parseMap(env.toMap()))
          .resolve(),
      )
  }
}
