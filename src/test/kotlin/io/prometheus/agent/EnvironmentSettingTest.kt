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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.agent

import ch.qos.logback.classic.Level
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.TestCerts.CA_CERT
import io.prometheus.common.TestCerts.read
import io.prometheus.common.captureLogs

// A setting fromEnvironment() read that the agent refuses as it starts names the variable the app set, as well as the
// setting, and never the value; one set from code keeps the agent's own message.
class EnvironmentSettingTest : StringSpec() {
  // What start() throws for [config], with [agentEnv] as the agent's own variables, none by default.
  private fun EmbeddedAgentConfig.refused(agentEnv: Map<String, String> = emptyMap()): ConfigLoadException {
    val outcome = runCatching { start(logBanner = false, env = agentEnv::get) }
    outcome.getOrNull()?.shutdown()
    return outcome.exceptionOrNull().shouldBeInstanceOf<ConfigLoadException>()
  }

  // The messages of [e] and its causes
  private fun messages(e: Throwable) = generateSequence(e) { it.cause }.map { it.toString() }.toList()

  init {
    "an endpoint the agent can't use names PROMETHEUS_PROXY and agent.proxy.endpoints" {
      val refused = embeddedAgent { fromEnvironment(mapOf("PROMETHEUS_PROXY" to "proxy:abc")::get) }.refused()

      refused.message shouldBe
        "Invalid configuration: PROMETHEUS_PROXY, read by fromEnvironment() for agent.proxy.endpoints: " +
        "Invalid port in 'proxy:abc': 'abc' is not a valid integer"
      refused.cause.shouldBeInstanceOf<IllegalArgumentException>()
    }

    "a certificate that isn't PEM names PROMETHEUS_PROXY_CERT and the setting, never the value" {
      val env = mapOf("PROMETHEUS_PROXY" to "proxy:50051", "PROMETHEUS_PROXY_CERT" to "not-a-certificate-$SECRET")

      val refused = embeddedAgent { fromEnvironment(env::get) }.refused()

      refused.message shouldBe
        "$CERT_REFUSED: trustCertCollectionPem is neither PEM text nor the base64 of PEM text"
      messages(refused).filter { SECRET in it }.shouldBeEmpty()
    }

    "PEM that holds no certificate names PROMETHEUS_PROXY_CERT, never the body" {
      val env = mapOf("PROMETHEUS_PROXY" to "proxy:50051", "PROMETHEUS_PROXY_CERT" to BAD_CERT)

      val refused = embeddedAgent { fromEnvironment(env::get) }.refused()

      refused.message shouldBe
        "$CERT_REFUSED: Input stream does not contain valid certificates."
      messages(refused).filter { SECRET in it }.shouldBeEmpty()
    }

    // As startIfConfigured logs it, which an app's log shows
    "startIfConfigured logs the variable a refused setting came from" {
      val config =
        embeddedAgent {
          fromEnvironment(mapOf("PROMETHEUS_PROXY" to "proxy:50051", "PROMETHEUS_PROXY_CERT" to "not PEM")::get)
        }

      val errors =
        captureLogs<EmbeddedAgentConfig>(Level.DEBUG) {
          config.startIfConfigured(onFailure = null, env = { null }) { config.start(logBanner = false) { null } }
            .shouldBeNull()
        }.filter { it.level == Level.ERROR }

      errors.single().formattedMessage shouldBe
        "The Prometheus agent could not start, so it stays off: Invalid configuration: PROMETHEUS_PROXY_CERT, read " +
        "by fromEnvironment() for agent.tls.trustCertCollectionPem: trustCertCollectionPem is neither PEM text nor " +
        "the base64 of PEM text"
    }

    // The agent takes any token or authority as it starts: the proxy refuses a wrong token once the agent connects
    "the agent refuses no token or authority as it starts, so neither is checked" {
      EnvironmentSetting.TOKEN.rejection("any token at all").shouldBeNull()
      EnvironmentSetting.AUTHORITY.rejection("not a host name").shouldBeNull()
      EnvironmentSetting.PROXY.rejection("proxy.example.com:50051").shouldBeNull()
      EnvironmentSetting.CERT.rejection(read(CA_CERT)).shouldBeNull()
      EnvironmentSetting.check(
        mapOf(EnvironmentSetting.TOKEN to "any token at all", EnvironmentSetting.AUTHORITY to "not a host name"),
        env = { null },
      )
    }

    // The agent uses its own variable's value, so a bad PROMETHEUS_PROXY is beside the point
    "a setting the agent's own variable sets isn't checked as the variable fromEnvironment read" {
      EnvironmentSetting.check(
        mapOf(EnvironmentSetting.PROXY to "proxy:abc", EnvironmentSetting.CERT to "not PEM"),
        env = mapOf("PROXY_HOSTNAME" to "proxy:50051", "TRUST_CERT_COLLECTION_PEM" to "set")::get,
      )
    }

    "a setting set from code after fromEnvironment keeps the agent's own message" {
      val env = mapOf("PROMETHEUS_PROXY" to "proxy:50051", "PROMETHEUS_PROXY_CERT" to read(CA_CERT))

      embeddedAgent {
        fromEnvironment(env::get)
        proxy = "proxy:abc"
      }.refused().message shouldBe "Invalid configuration: Invalid port in 'proxy:abc': 'abc' is not a valid integer"

      embeddedAgent {
        fromEnvironment(env::get)
        setting("tls.trustCertCollectionPem", "not PEM")
      }.refused().message shouldBe
        "Invalid configuration: trustCertCollectionPem is neither PEM text nor the base64 of PEM text"

      embeddedAgent {
        fromEnvironment(env::get)
        trustCertificatePem(pem = "not PEM")
      }.refused().message shouldBe
        "Invalid configuration: trustCertCollectionPem is neither PEM text nor the base64 of PEM text"
    }
  }

  private companion object {
    // How a refused PROMETHEUS_PROXY_CERT's message begins
    const val CERT_REFUSED =
      "Invalid configuration: PROMETHEUS_PROXY_CERT, read by fromEnvironment() for agent.tls.trustCertCollectionPem"

    // Shaped like a certificate's body, so one shown in a message would show it
    const val SECRET = "S3cr3tC3rtMat3rialAAAABBBBCCCCDDDD"
    const val BAD_CERT = "-----BEGIN CERTIFICATE-----\n$SECRET\n-----END CERTIFICATE-----\n"
  }
}
