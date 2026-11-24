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

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.server.config.ApplicationConfig
import io.prometheus.agent.EmbeddedAgentConfig
import io.prometheus.common.ConfigLoadException

// The prometheus-proxy.agent section of the app's configuration file, or one of its paths entries, read through Ktor's
// ApplicationConfig, so HOCON, YAML, and a MapApplicationConfig read alike. Each value is read as text, as YAML and a
// MapApplicationConfig hold it, and a blank one counts as unset, as an unset environment variable gives.
internal class FileSettings(
  private val config: ApplicationConfig,
) {
  fun string(key: String): String? = config.propertyOrNull(key)?.getString()?.takeUnless { it.isBlank() }

  fun boolean(key: String): Boolean? =
    string(key)?.let { it.toBooleanStrictOrNull() ?: invalid("$SECTION.$key must be true or false: $it") }

  fun int(key: String): Int? = string(key)?.let { it.toIntOrNull() ?: invalid("$SECTION.$key must be a number: $it") }

  // A section of keys and values, such as labels, its nested keys joined with dots
  fun stringMap(key: String): Map<String, String> = section(key).mapValues { it.value.toString() }

  // The file's settings, as the Spring Boot starter applies its properties: who the agent is and how it reaches the
  // proxy, what it serves of its own, the targets it scrapes, then settings, any other agent setting by its key.
  fun applyTo(builder: EmbeddedAgentConfig.Builder) {
    connection(builder)
    features(builder)
    targets().forEach { target ->
      builder.path(
        path = target.string("path").orEmpty(),
        url = target.string("url").orEmpty(),
        username = target.string("username"),
        password = target.string("password"),
        labels = target.stringMap("labels"),
      )
    }
    section("settings").forEach { (key, value) -> builder.setting(key = key, value = value) }
  }

  private fun connection(builder: EmbeddedAgentConfig.Builder) {
    string("name")?.also { builder.name = it }
    string("proxy")?.also { builder.proxy = it }
    string("token")?.also { builder.token = it }
    string("trust-certificate")?.also { builder.trustCertificatePem(pem = it, authority = string("authority")) }
    val clientCertificate = string("client-certificate")
    val clientKey = string("client-key")
    if (clientCertificate != null || clientKey != null)
      builder.clientCertificatePem(certChainPem = clientCertificate.orEmpty(), privateKeyPem = clientKey.orEmpty())
  }

  private fun features(builder: EmbeddedAgentConfig.Builder) {
    boolean("consolidated")?.also { builder.consolidated = it }
    int("metrics-port")?.also { builder.agentMetricsPort = it }
    metricsProxyPath(builder)
    int("dashboard.port")?.also { builder.dashboard(port = it, host = string("dashboard.host")) }
    string("dashboard.password")?.also {
      builder.dashboardLogin(password = it, username = string("dashboard.username") ?: "admin")
    }
  }

  // The agent's own metrics at a proxy path, with its labels, which do nothing without it, as the log then says
  private fun metricsProxyPath(builder: EmbeddedAgentConfig.Builder) {
    val labels = stringMap("metrics-proxy-path-labels")
    val path = string("metrics-proxy-path")
    if (path != null)
      builder.agentMetricsAt(path = path, labels = labels)
    else if (labels.isNotEmpty())
      logger.warn { "$SECTION.metrics-proxy-path-labels is set without metrics-proxy-path, so it does nothing" }
  }

  // The paths list's targets, or none when the file has no paths
  private fun targets(): List<FileSettings> =
    if (has("paths")) config.configList("paths").map(::FileSettings) else emptyList()

  // A section's values by their keys, its nested sections' joined with dots, as the agent's settings take them
  private fun section(key: String): Map<String, Any> =
    if (has(key)) flattened(config.config(key).toMap()) else emptyMap()

  // Whether the file has a value or a section at key: Ktor's configs differ in whether a missing one throws
  private fun has(key: String): Boolean = config.keys().any { it == key || it.startsWith("$key.") }

  private companion object {
    const val SECTION = "prometheus-proxy.agent"
    val logger = logger {}

    fun flattened(
      values: Map<String, Any?>,
      prefix: String = "",
    ): Map<String, Any> =
      buildMap {
        values.forEach { (key, value) ->
          when (value) {
            null -> {}

            is Map<*, *> -> {
              putAll(flattened(value.entries.associate { it.key.toString() to it.value }, "$prefix$key."))
            }

            else -> {
              put("$prefix$key", value)
            }
          }
        }
      }

    fun invalid(message: String): Nothing = throw ConfigLoadException(message)
  }
}
