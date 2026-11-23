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

package io.prometheus.agent

import io.prometheus.common.ConfigLoadException
import io.prometheus.common.EnvVars
import io.prometheus.common.TlsMaterial
import io.prometheus.common.Utils.parseEndpointList

// A variable EmbeddedAgentConfig.Builder.fromEnvironment reads, the setting it sets, by its key under agent, and the
// agent's own variable for that setting, which takes precedence over it when set.
internal enum class EnvironmentSetting(
  val variable: String,
  val key: String,
  private val agentVariable: EnvVars,
) {
  PROXY("PROMETHEUS_PROXY", "proxy.endpoints", EnvVars.PROXY_HOSTNAME),
  TOKEN("PROMETHEUS_AGENT_TOKEN", "agentToken", EnvVars.AGENT_TOKEN),
  CERT("PROMETHEUS_PROXY_CERT", "tls.trustCertCollectionPem", EnvVars.TRUST_CERT_COLLECTION_PEM),
  AUTHORITY("PROMETHEUS_PROXY_AUTHORITY", "tls.overrideAuthority", EnvVars.OVERRIDE_AUTHORITY),
  ;

  // What the agent throws for [value] in this setting as it starts, its message in the agent's words, or null when it
  // takes it: an endpoint it can't parse, or a certificate it can't read, checked as the agent checks them. It takes
  // any token or authority as it starts: the proxy refuses a wrong token once the agent connects, and a wrong authority
  // fails the TLS handshake. The message never holds the value, which can be a token or a certificate.
  fun rejection(value: String): Throwable? =
    runCatching {
      when (this) {
        PROXY -> {
          val _ = parseEndpointList(value, DEFAULT_PORT)
        }

        CERT -> {
          TlsMaterial.checkTrustCollection(TRUST_SETTING, value)
        }

        TOKEN, AUTHORITY -> {}
      }
    }.exceptionOrNull()

  companion object {
    // The port the agent gives an endpoint without one; it plays no part in whether an endpoint parses.
    private const val DEFAULT_PORT = 50051

    // The setting the agent names when it can't read a trust collection given as PEM.
    private const val TRUST_SETTING = "trustCertCollectionPem"

    /**
     * Throws [ConfigLoadException] for the first of [read], the values fromEnvironment set that nothing has set since,
     * that the agent would refuse as it starts, naming the variable it came from, the setting, and why, in the agent's
     * words. A setting the agent's own variable sets, read with [env], is the agent's to check, as it uses that value.
     */
    fun check(
      read: Map<EnvironmentSetting, String>,
      env: (String) -> String?,
    ) {
      read.forEach { (setting, value) ->
        if (env(setting.agentVariable.name) == null)
          setting.rejection(value)?.also { e ->
            val why = e.message ?: e.javaClass.name
            throw ConfigLoadException(
              "Invalid configuration: ${setting.variable}, read by fromEnvironment() for agent.${setting.key}: $why",
              e,
            )
          }
      }
    }
  }
}
