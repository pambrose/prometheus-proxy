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

package website

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.agent.ktor.PrometheusProxyAgent
import io.prometheus.agent.ktor.prometheusProxyAgent
import kotlin.time.Duration.Companion.seconds

// The docs site's Ktor Plugin page shows these, each from its start marker to its end marker. KtorPluginExamplesTest
// runs them, and make consumer-check compiles them against the published POM.

// --8<-- [start:install]
fun Application.module() {
  // Ktor's HTTP metrics and the JVM's, then the app's own, in one registry
  val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  install(MicrometerMetrics) { registry = meterRegistry }

  install(PrometheusProxyAgent) {
    // Served in-process, at the proxy's /my_app_metrics
    registry = meterRegistry.prometheusRegistry
    path = "my_app_metrics"
    agent {
      name = "my-app"
      // PROMETHEUS_PROXY, PROMETHEUS_AGENT_TOKEN, and PROMETHEUS_PROXY_CERT. Without PROMETHEUS_PROXY, as on a
      // developer's machine, the agent stays off
      fromEnvironment()
    }
  }

  routing {
    agentHealth()
  }
}
// --8<-- [end:install]

// --8<-- [start:install-file]
fun Application.moduleWithFileSettings() {
  val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  install(MicrometerMetrics) { registry = meterRegistry }

  // The rest comes from prometheus-proxy.agent in application.conf
  install(PrometheusProxyAgent) {
    registry = meterRegistry.prometheusRegistry
  }
}
// --8<-- [end:install-file]

// --8<-- [start:on-failure]
fun Application.moduleWithFailureAlerts(alert: (String) -> Unit) {
  val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  install(MicrometerMetrics) { registry = meterRegistry }

  // The rest comes from prometheus-proxy.agent in application.conf
  install(PrometheusProxyAgent) {
    registry = meterRegistry.prometheusRegistry
    agent {
      // Inside the platform's shutdown window, such as Cloud Run's 10 seconds after SIGTERM
      stopTimeout = 5.seconds
      // An agent that can't start stays off, and the app runs on without it: this tells someone why
      onFailure { reason, _ -> alert("The Prometheus agent could not start: $reason") }
    }
  }
}
// --8<-- [end:on-failure]

// --8<-- [start:health]
// The agent's connection, for a dashboard or a person; always 200, since a proxy that's away shouldn't take the app
// out of a load balancer or have it restarted
fun Route.agentHealth() {
  get("/health/agent") {
    val agent = call.application.prometheusProxyAgent
    val status =
      when {
        agent == null -> "off"
        agent.isConnected -> "connected to ${agent.proxyEndpoint}"
        else -> "connecting"
      }
    call.respondText(status)
  }
}
// --8<-- [end:health]
