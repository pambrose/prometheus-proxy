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
import java.net.ServerSocket

// The plugin installed in a test app, with a real agent: what starts it, from the file and from code, and that it stops
// with the app. Its proxy is a port nothing listens on, so it starts, and keeps trying to connect, until the app stops.
class PrometheusProxyAgentPluginTest : StringSpec() {
  init {
    "an app without a proxy runs without the agent" {
      var app: Application? = null
      testApplication {
        application {
          app = this
          install(PrometheusProxyAgent) {
            registry = PrometheusRegistry()
            path = "my_app_metrics"
          }
        }
        startApplication()

        app?.prometheusProxyAgent.shouldBeNull()
      }
    }

    "the file's proxy starts the agent, which stops with the app" {
      var app: Application? = null
      testApplication {
        environment {
          config = MapApplicationConfig("prometheus-proxy.agent.proxy" to "localhost:${closedPort()}")
        }
        application {
          app = this
          install(PrometheusProxyAgent) {
            registry = PrometheusRegistry()
            path = "my_app_metrics"
            agent { name = "plugin-test" }
          }
        }
        startApplication()

        val agent = app?.prometheusProxyAgent.shouldNotBeNull()
        agent.agentName shouldBe "plugin-test"
        agent.isConnected shouldBe false
      }

      app?.prometheusProxyAgent.shouldBeNull()
    }

    "a proxy in the install block starts the agent too" {
      var app: Application? = null
      testApplication {
        application {
          app = this
          install(PrometheusProxyAgent) {
            agent { proxy = "localhost:${closedPort()}" }
          }
        }
        startApplication()

        app?.prometheusProxyAgent.shouldNotBeNull()
      }
    }

    "an agent the file disables stays off, with a proxy" {
      var app: Application? = null
      testApplication {
        environment {
          config =
            MapApplicationConfig(
              "prometheus-proxy.agent.enabled" to "false",
              "prometheus-proxy.agent.proxy" to "localhost:${closedPort()}",
            )
        }
        application {
          app = this
          install(PrometheusProxyAgent)
        }
        startApplication()

        app?.prometheusProxyAgent.shouldBeNull()
      }
    }

    "a setting the agent rejects leaves it off, and the app running" {
      var app: Application? = null
      testApplication {
        application {
          app = this
          install(PrometheusProxyAgent) {
            agent {
              proxy = "localhost:${closedPort()}"
              trustCertificatePem(pem = "not a certificate", authority = null)
            }
          }
        }
        startApplication()

        app?.prometheusProxyAgent.shouldBeNull()
      }
    }

    "a required agent with a setting it rejects fails the app's start" {
      shouldThrow<ConfigLoadException> {
        testApplication {
          application {
            install(PrometheusProxyAgent) {
              required = true
              agent {
                proxy = "localhost:${closedPort()}"
                trustCertificatePem(pem = "not a certificate", authority = null)
              }
            }
          }
          startApplication()
        }
      }
    }

    "a required agent without a proxy fails the app's start" {
      shouldThrow<IllegalStateException> {
        testApplication {
          environment { config = MapApplicationConfig("prometheus-proxy.agent.required" to "true") }
          application { install(PrometheusProxyAgent) }
          startApplication()
        }
      }.message shouldBe "prometheus-proxy.agent.proxy isn't set, and prometheus-proxy.agent.required is true"
    }
  }

  private companion object {
    // A port nothing listens on: taken from the OS, then closed
    fun closedPort(): Int = ServerSocket(0).use { it.localPort }
  }
}
