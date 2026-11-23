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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.prometheus.common.ConfigLoadException
import io.prometheus.metrics.model.registry.PrometheusRegistry

// The plugin installed in a test app, with a real agent: what starts it, from the file and from code, and that it stops
// with the app. Its proxy is a port nothing listens on, so it starts, and keeps trying to connect, until the app stops.
// Without a proxy, it stays off, as AgentLifecycleTest and KtorPluginExamplesTest show.
class PrometheusProxyAgentPluginTest : StringSpec() {
  init {
    "the file's proxy starts the agent, which stops with the app" {
      lateinit var app: Application
      testApplication {
        environment {
          config = MapApplicationConfig("prometheus-proxy.agent.proxy" to "localhost:${closedPort()}")
        }
        application {
          install(PrometheusProxyAgent) {
            registry = PrometheusRegistry()
            path = "my_app_metrics"
            agent { name = "plugin-test" }
          }
        }
        startApplication()
        app = application

        val agent = app.prometheusProxyAgent.shouldNotBeNull()
        agent.agentName shouldBe "plugin-test"
        agent.isConnected shouldBe false
      }

      app.prometheusProxyAgent.shouldBeNull()
    }

    "a proxy in the install block starts the agent too" {
      testApplication {
        application {
          install(PrometheusProxyAgent) {
            agent { proxy = "localhost:${closedPort()}" }
          }
        }
        startApplication()

        application.prometheusProxyAgent.shouldNotBeNull()
      }
    }

    "an agent the file disables stays off, with a proxy" {
      testApplication {
        environment {
          config =
            MapApplicationConfig(
              "prometheus-proxy.agent.enabled" to "false",
              "prometheus-proxy.agent.proxy" to "localhost:${closedPort()}",
            )
        }
        application { install(PrometheusProxyAgent) }
        startApplication()

        application.prometheusProxyAgent.shouldBeNull()
      }
    }

    "a setting the agent rejects leaves it off, and the app running" {
      testApplication {
        application { install(PrometheusProxyAgent) { rejectedSettings() } }
        startApplication()

        application.prometheusProxyAgent.shouldBeNull()
      }
    }

    // Ktor rethrows what an ApplicationStarted handler throws, so the app's start fails
    "a required agent with a setting it rejects fails the app's start" {
      shouldThrow<ConfigLoadException> {
        testApplication {
          application {
            install(PrometheusProxyAgent) {
              required = true
              rejectedSettings()
            }
          }
          startApplication()
        }
      }
    }
  }

  private companion object {
    // A proxy, and a certificate the agent can't read
    fun PrometheusProxyAgentConfig.rejectedSettings() =
      agent {
        proxy = "localhost:${closedPort()}"
        trustCertificatePem(pem = "not a certificate", authority = null)
      }
  }
}
