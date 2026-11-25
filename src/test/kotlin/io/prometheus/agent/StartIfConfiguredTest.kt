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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.http.encodeURLParameter
import io.mockk.mockk
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.TestCerts.CA_CERT
import io.prometheus.common.TestCerts.CLIENT_CERT
import io.prometheus.common.TestCerts.read
import io.prometheus.common.captureLogs
import java.io.IOException
import java.net.BindException
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

// startIfConfigured, which keeps an app running without its agent: off without a proxy, and a failed start logged and
// reported, with no token, password, or PEM in why.
class StartIfConfiguredTest : StringSpec() {
  // What a failed start reported to onFailure: why, and what was thrown
  private class Failure(
    val reason: String,
    val thrown: Exception,
  )

  // Starts [config] with startIfConfigured, as an app does, and returns what it reported of the failure the start
  // has to meet. An agent that starts anyway is stopped, and fails the test.
  private fun failedStart(config: EmbeddedAgentConfig): Failure {
    val failures = mutableListOf<Failure>()
    val started = config.startIfConfigured(logBanner = false) { reason, e -> failures += Failure(reason, e) }
    started?.shutdown()
    started.shouldBeNull()
    return failures.single()
  }

  // Every message a failure carries: why, what was thrown, and each of its causes
  private fun Failure.messages(): List<String> =
    listOf(reason) + generateSequence<Throwable>(thrown) { it.cause }.map { it.toString() }.toList()

  init {
    "without a proxy, the agent stays off, with one INFO line, and nothing starts" {
      val events =
        captureLogs<EmbeddedAgentConfig>(Level.DEBUG) {
          embeddedAgent { name = "app" }
            .startIfConfigured(onFailure = null, env = { null }) { error("started") }
            .shouldBeNull()
        }

      events.map { it.level to it.formattedMessage } shouldBe
        listOf(Level.INFO to "No proxy is set, so the Prometheus agent stays off")
    }

    // Each is how the agent learns of a proxy, and each blank one is none, which would have it try localhost
    "the builder's proxy, a setting of one, or PROXY_HOSTNAME starts the agent, and a blank one doesn't" {
      val handle = mockk<EmbeddedAgentInfo>()

      fun started(
        env: Map<String, String> = emptyMap(),
        configure: EmbeddedAgentConfig.Builder.() -> Unit,
      ) = embeddedAgent(configure).startIfConfigured(onFailure = null, env = env::get) { handle }

      started { proxy = "proxy.example.com" } shouldBeSameInstanceAs handle
      started { setting("proxy.hostname", "proxy.example.com") } shouldBeSameInstanceAs handle
      started { setting("proxy.endpoints", listOf("proxy.example.com")) } shouldBeSameInstanceAs handle
      started(env = mapOf("PROXY_HOSTNAME" to "proxy.example.com")) {} shouldBeSameInstanceAs handle

      started { proxy = " , " }.shouldBeNull()
      started { setting("proxy.hostname", " ") }.shouldBeNull()
      started { setting("proxy.endpoints", listOf(" ")) }.shouldBeNull()
      started(env = mapOf("PROXY_HOSTNAME" to " ")) {}.shouldBeNull()
    }

    // The rule above, public, for an app that decides for itself once its settings are complete, as the Ktor plugin and
    // the Spring Boot starter do: theirs decided by their own proxy setting alone (November 23 review, #11)
    "namesProxy says whether the builder's proxy, a setting of one, or PROXY_HOSTNAME names a proxy" {
      fun namesProxy(
        env: Map<String, String> = emptyMap(),
        configure: EmbeddedAgentConfig.Builder.() -> Unit,
      ) = embeddedAgent(configure).namesProxy(env::get)

      namesProxy { proxy = "proxy.example.com" } shouldBe true
      namesProxy { setting("proxy.hostname", "proxy.example.com") } shouldBe true
      namesProxy { setting("proxy.endpoints", listOf("proxy.example.com")) } shouldBe true
      namesProxy(env = mapOf("PROXY_HOSTNAME" to "proxy.example.com")) { name = "app" } shouldBe true
      namesProxy { name = "app" } shouldBe false
      namesProxy { proxy = " , " } shouldBe false
      namesProxy(env = mapOf("PROXY_HOSTNAME" to " ")) { name = "app" } shouldBe false
      // The public form, which reads PROXY_HOSTNAME from the process's environment
      embeddedAgent { proxy = "proxy.example.com" }.namesProxy() shouldBe true
    }

    "a start that fails returns null, logs why at ERROR, with its cause, and tells onFailure" {
      val thrown =
        IllegalStateException(
          "Expected the service Agent [FAILED] to be RUNNING, but the service has FAILED",
          BindException("Address already in use"),
        )
      val failures = mutableListOf<Failure>()

      val events =
        captureLogs<EmbeddedAgentConfig>(Level.DEBUG) {
          embeddedAgent { proxy = "proxy.example.com" }
            .startIfConfigured(onFailure = { reason, e -> failures += Failure(reason, e) }, env = { null }) {
              throw thrown
            }.shouldBeNull()
        }

      val reason =
        "Expected the service Agent [FAILED] to be RUNNING, but the service has FAILED: Address already in use"
      events.map { it.level to it.formattedMessage } shouldBe
        listOf(Level.ERROR to "The Prometheus agent could not start, so it stays off: $reason")
      failures.single().reason shouldBe reason
      failures.single().thrown shouldBeSameInstanceAs thrown
    }

    // The builder's handlers, which a Ktor install block or a Spring customizer adds, and then the call's own
    "a start that fails tells each of the builder's onFailure handlers, in turn, then the call's" {
      val heard = mutableListOf<String>()
      val thrown = IllegalStateException("bad certificate")

      embeddedAgent {
        proxy = "proxy.example.com"
        onFailure { reason, e ->
          e shouldBeSameInstanceAs thrown
          heard += "first: $reason"
        }
        onFailure { reason, _ -> heard += "second: $reason" }
      }.startIfConfigured(onFailure = { reason, _ -> heard += "the call's: $reason" }, env = { null }) {
        throw thrown
      }.shouldBeNull()

      heard shouldBe listOf("first: bad certificate", "second: bad certificate", "the call's: bad certificate")
    }

    // A handler runs inside the start, which mustn't fail for it: startIfConfigured returns null whatever it meets
    "an onFailure handler that throws is logged, and the next still runs" {
      val heard = mutableListOf<String>()

      val events =
        captureLogs<EmbeddedAgentConfig>(Level.DEBUG) {
          embeddedAgent {
            proxy = "proxy.example.com"
            onFailure { _, _ -> error("the chat channel is down") }
          }.startIfConfigured(onFailure = { reason, _ -> heard += reason }, env = { null }) {
            error("bad certificate")
          }.shouldBeNull()
        }

      heard shouldBe listOf("bad certificate")
      events.map { it.level to it.formattedMessage } shouldBe
        listOf(
          Level.ERROR to "The Prometheus agent could not start, so it stays off: bad certificate",
          Level.WARN to "An onFailure handler threw, given: bad certificate",
        )
    }

    // A proxy nothing listens on: the agent starts, and keeps trying to connect until it's stopped
    "a started agent's handle stops it within the builder's stop timeout" {
      val agent =
        embeddedAgent {
          proxy = "localhost:1"
          stopTimeout = 5.seconds
        }.start(logBanner = false)
      try {
        agent.stopTimeout shouldBe 5.seconds
      } finally {
        agent.shutdown()
      }
    }

    // Kotlin can throw a checked exception undeclared, so the start's catch takes any Exception
    "a checked exception from the start is caught too" {
      embeddedAgent { proxy = "proxy.example.com" }
        .startIfConfigured(onFailure = null, env = { null }) { throw IOException("unreachable") }
        .shouldBeNull()
    }

    "why a start failed reads each message along the cause chain once, a class for none, and masks a URL's secrets" {
      EmbeddedAgentConfig.failureReason(
        ConfigLoadException("Invalid configuration: bad value", IllegalArgumentException("bad value")),
      ) shouldBe "Invalid configuration: bad value"
      EmbeddedAgentConfig.failureReason(IllegalStateException(null, RuntimeException(" "))) shouldBe
        "java.lang.IllegalStateException: java.lang.RuntimeException"
      EmbeddedAgentConfig.failureReason(
        IllegalArgumentException("Unable to load http://ops:s3cret@host/agent.conf?token=$TOKEN"),
      ) shouldBe "Unable to load http://***@host/agent.conf?token=***"
    }

    // canvas-cache took the token and the password out of why the agent couldn't start before sending it to Slack. The
    // agent's own messages hold neither, nor a PEM, so why a start failed can be logged and sent on as it is.
    "a start that fails says why without the token, a password, or a PEM" {
      val failures =
        listOf(
          // A private key the agent can't read
          embeddedAgent {
            proxy = "localhost:1"
            token = TOKEN
            trustCertificatePem(read(CA_CERT))
            clientCertificatePem(certChainPem = read(CLIENT_CERT), privateKeyPem = BAD_KEY)
          },
          // A certificate that's neither PEM nor its base64
          embeddedAgent {
            proxy = "localhost:1"
            trustCertificatePem("not a certificate $TOKEN")
          },
          // A setting the agent rejects, with a scrape password and the token set
          embeddedAgent {
            proxy = "localhost:1"
            token = TOKEN
            scrapeSelf(path = "app_metrics", port = 8080, username = "user", password = PASSWORD)
            setting("scrapeTimeoutSecs", 0)
          },
          // The token given as a list, which isn't a string
          embeddedAgent {
            proxy = "localhost:1"
            setting("agentToken", listOf(TOKEN))
          },
          // A path given twice, one with credentials in its URL
          embeddedAgent {
            proxy = "localhost:1"
            path(path = "app_metrics", url = "http://ops:${PASSWORD.encodeURLParameter()}@127.0.0.1:8080/metrics")
            source(path = "app_metrics") { _, _, _ -> "" }
          },
          // A dashboard password the dashboard refuses
          embeddedAgent {
            proxy = "localhost:1"
            dashboard(port = 1, host = "127.0.0.1")
            dashboardLogin(password = "bad${Char(1)}$PASSWORD")
          },
        ).map { failedStart(it) }

      failures.forEach { it.thrown::class shouldBe ConfigLoadException::class }
      val secrets = listOf(TOKEN, PASSWORD, PASSWORD.encodeURLParameter(), KEY_BODY)
      failures.flatMap { it.messages() }.filter { message -> secrets.any { it in message } }.shouldBeEmpty()
    }

    // A failure to start, not a setting, comes from Guava as a message that says nothing; why is in its cause
    "a start that can't bind its admin port returns null, saying why, without the token" {
      ServerSocket(0).use { taken ->
        val failure =
          failedStart(
            embeddedAgent {
              proxy = "localhost:1"
              token = TOKEN
              scrapeSelf(path = "app_metrics", port = 8080, username = "user", password = PASSWORD)
              setting("admin.enabled", true)
              setting("admin.port", taken.localPort)
            },
          )

        failure.thrown.shouldBeInstanceOf<IllegalStateException>()
        failure.reason shouldContain "${taken.localPort}"
        failure.messages().forEach { message ->
          message shouldNotContain TOKEN
          message shouldNotContain PASSWORD.encodeURLParameter()
        }
      }
    }
  }

  private companion object {
    const val TOKEN = "0123456789abcdef0123456789abcdef"

    // Every character a URL's user info must escape, and some it need not
    const val PASSWORD = "p@ss:w/rd%#+ ?&é"

    // A private key's body, shaped as one, that isn't a key
    const val KEY_BODY = "S3cr3tK3yMat3rialAAAABBBBCCCCDDDD"
    const val BAD_KEY = "-----BEGIN PRIVATE KEY-----\n$KEY_BODY\n-----END PRIVATE KEY-----\n"
  }
}
