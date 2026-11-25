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

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import io.prometheus.agent.EmbeddedAgentInfo

/**
 * Runs a prometheus-proxy agent embedded in a Ktor app, started and stopped with the app, and serves the app's metrics
 * registry to Prometheus through the proxy, read in-process, so the app needs no `/metrics` route, port, or credentials
 * for the agent to read them:
 *
 * ```kotlin
 * val meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
 * install(MicrometerMetrics) { registry = meterRegistry }
 * install(PrometheusProxyAgent) {
 *   registry = meterRegistry.prometheusRegistry
 *   path = "my_app_metrics"
 *   agent {
 *     // PROMETHEUS_PROXY, PROMETHEUS_AGENT_TOKEN, and the proxy's certificate
 *     fromEnvironment()
 *   }
 * }
 * ```
 *
 * Its settings are the `prometheus-proxy.agent` section of the app's configuration file, when it has one, then the
 * install block's, which take precedence, but not over the file's `settings.<key>` keys (see
 * [PrometheusProxyAgentConfig]).
 *
 * The agent starts once every module of the app has loaded, and only where its settings name a proxy, as
 * [EmbeddedAgentConfig.namesProxy][io.prometheus.agent.EmbeddedAgentConfig.namesProxy] tells: the file's `proxy`, the
 * install block's, a `settings` key of one, or the agent's own `PROXY_HOSTNAME` environment variable. Without one it
 * logs a line and stays off, as on a developer's machine. An agent that can't start, such as one given a certificate it
 * can't read, is logged and stays off too, and the app runs without it, unless
 * [required][PrometheusProxyAgentConfig.required] is set; the agent block's
 * [onFailure][io.prometheus.agent.EmbeddedAgentConfig.Builder.onFailure] has the app told why, to report it elsewhere
 * too. It starts before the server binds its port, but its thread
 * doesn't keep the JVM running, so an app whose port is taken still exits, and Ktor's shutdown hook stops the agent;
 * the servers of its own metrics and admin ports, when they're on, do keep the JVM running.
 *
 * It begins stopping as soon as the server does, before the server drains the requests in flight, so the proxy drops
 * the app's paths and no scrape reaches an app that's going away; the app's disposal waits for it to finish, up to the
 * settings' [stopTimeout][io.prometheus.agent.EmbeddedAgentConfig.Builder.stopTimeout], 30 seconds unless the file's
 * `stop-timeout` or the agent block sets another. While it
 * runs, [prometheusProxyAgent] is its handle, with its connection to the proxy, for a health check.
 */
val PrometheusProxyAgent: ApplicationPlugin<PrometheusProxyAgentConfig> =
  createApplicationPlugin(
    name = "PrometheusProxyAgent",
    configurationPath = "prometheus-proxy.agent",
    createConfiguration = ::PrometheusProxyAgentConfig,
  ) {
    AgentLifecycle(application = application, config = pluginConfig).subscribe()
  }

/**
 * The running agent's handle, with its connection to the proxy, read live, so a health check can report it; null while
 * the [PrometheusProxyAgent] plugin's agent is off, before it starts, and once it stops.
 */
val Application.prometheusProxyAgent: EmbeddedAgentInfo?
  get() = attributes.getOrNull(AgentKey)

internal val AgentKey = AttributeKey<EmbeddedAgentInfo>("PrometheusProxyAgent.agent")
