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

import io.ktor.server.config.ApplicationConfig
import io.prometheus.agent.EmbeddedAgentConfig
import io.prometheus.common.ConfigLoadException
import io.prometheus.metrics.model.registry.PrometheusRegistry

/**
 * The [PrometheusProxyAgent] plugin's settings. Each starts from the `prometheus-proxy.agent` section of the app's
 * configuration file, in HOCON or YAML, when it has one, and the install block can change it: the block runs after
 * the file is read, so a setting it makes takes precedence. An app started with `embeddedServer` and no file has only
 * the block's.
 *
 * The file's section takes the Spring Boot starter's property names: `proxy`, `token`, `trust-certificate`,
 * `authority`, `client-certificate`, `client-key`, `name`, `consolidated`, `metrics-port`, `dashboard.port`,
 * `dashboard.host`, `dashboard.username`, `dashboard.password`, `paths` (a list of targets, each with `path`, `url`,
 * `username`, `password`, and `labels`), and `settings`, any other agent setting by its key; and [enabled], [required],
 * [path], and [labels] here. A blank value counts as unset, as an unset environment variable gives.
 */
class PrometheusProxyAgentConfig internal constructor(
  file: ApplicationConfig,
) {
  private val fileSettings = FileSettings(file)

  /** Whether to run the agent at all; it also needs a proxy. The file's `enabled`; true by default. */
  var enabled: Boolean = fileSettings.boolean("enabled") ?: true

  /**
   * Whether the app fails to start when the agent can't, or has no proxy, rather than running without it. The file's
   * `required`; false by default.
   */
  var required: Boolean = fileSettings.boolean("required") ?: false

  /**
   * The registry the agent serves at [path], read in-process for each scrape, in the format Prometheus asks for:
   * Micrometer's `PrometheusMeterRegistry.prometheusRegistry`, or the Prometheus Java client's own
   * `PrometheusRegistry.defaultRegistry`. Without one, the agent serves only the paths the file's `paths` and [agent]
   * add.
   */
  var registry: PrometheusRegistry? = null

  /**
   * The proxy's path the [registry]'s metrics are served at, such as `my_app_metrics`; required with a registry, since
   * two apps that defaulted to the same path would take it from each other. The file's `path`.
   */
  var path: String? = fileSettings.string("path")

  /** The labels of [path], in the proxy's service discovery. The file's `labels`. */
  var labels: Map<String, String> = fileSettings.stringMap("labels")

  private val agentBlocks = mutableListOf<EmbeddedAgentConfig.Builder.() -> Unit>()

  /**
   * Makes the rest of the agent's settings in code, with [EmbeddedAgentConfig.Builder], such as its proxy, token, and
   * certificates, read from wherever the app keeps them. It runs after the file's settings are applied, so a setting it
   * makes takes precedence. Called more than once, each block runs, in turn.
   */
  fun agent(block: EmbeddedAgentConfig.Builder.() -> Unit) {
    agentBlocks += block
  }

  // The settings the agent starts with: the file's, then the registry at its path, then each agent block. Read as the
  // agent starts, so a file value that isn't valid fails the start, as a setting the agent rejects does.
  internal fun builder(): EmbeddedAgentConfig.Builder {
    val builder = EmbeddedAgentConfig.builder()
    fileSettings.applyTo(builder)
    registry?.also { registry ->
      val servedAt = path
      if (servedAt.isNullOrBlank())
        throw ConfigLoadException("prometheus-proxy.agent.path is required to serve a registry")
      builder.source(path = servedAt, labels = labels, source = RegistryMetricsSource(registry))
    }
    agentBlocks.forEach { builder.it() }
    return builder
  }
}
