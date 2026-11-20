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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.common.TestCerts.CA_CERT
import io.prometheus.common.TestCerts.CLIENT_CERT
import io.prometheus.common.TestCerts.CLIENT_KEY
import io.prometheus.common.TestCerts.read
import java.net.URI

// The typed builder for an embedded agent's in-memory config, and its Kotlin DSL: each method's setting reaches the
// agent's options as a config file's would, credentials are percent-encoded into a path's URL so they survive any
// character, and the built settings print without their secrets.
class EmbeddedAgentConfigTest : StringSpec() {
  // The options an agent started from the config would resolve.
  private fun EmbeddedAgentConfig.options() = AgentOptions(toConfig())

  // The pathConfigs the agent would register.
  private fun EmbeddedAgentConfig.pathConfigs() = options().configVals.agent.pathConfigs

  init {
    "each builder method's setting reaches the agent's options" {
      val options =
        EmbeddedAgentConfig
          .builder()
          .name("my-app-1.0")
          .proxy("proxy-a.example.com:50443", "proxy-b.example.com")
          .token(TOKEN)
          .trustCertificatePem(read(CA_CERT), authority = "prometheus-proxy")
          .clientCertificatePem(read(CLIENT_CERT), read(CLIENT_KEY))
          .consolidated()
          .agentMetrics(9183)
          .build()
          .options()

      options.agentName shouldBe "my-app-1.0"
      options.proxyHostname shouldBe "proxy-a.example.com:50443,proxy-b.example.com:50051"
      options.agentToken shouldBe TOKEN
      options.overrideAuthority shouldBe "prometheus-proxy"
      options.hasTrustCertCollection.shouldBeTrue()
      options.isTlsEnabled.shouldBeTrue()
      options.consolidated.shouldBeTrue()
      options.metricsEnabled.shouldBeTrue()
      options.metricsPort shouldBe 9183
    }

    "a builder with nothing set gives the built-in defaults" {
      val options = EmbeddedAgentConfig.builder().build().options()
      options.proxyHostname shouldBe "localhost:50051"
      options.agentToken shouldBe ""
      options.hasTrustCertCollection.shouldBeFalse()
      options.consolidated.shouldBeFalse()
      options.metricsEnabled.shouldBeFalse()
    }

    // The agent sends a URL's user info as Basic Auth, so every character a password can hold has to survive it.
    "scrapeSelf percent-encodes the credentials into the app's URL, so any character survives" {
      val pathConfig =
        embeddedAgent { scrapeSelf(path = "my_app_metrics", port = 8080, username = "agent", password = PASSWORD) }
          .pathConfigs()
          .single()

      pathConfig.path shouldBe "my_app_metrics"
      val url = URI(pathConfig.url)
      url.userInfo shouldBe "agent:$PASSWORD"
      url.host shouldBe "127.0.0.1"
      url.port shouldBe 8080
      url.path shouldBe "/metrics"
    }

    "scrapeSelf takes another metrics path, with or without its slash, and no credentials" {
      val urls =
        embeddedAgent {
          scrapeSelf(path = "a", port = 9000, metricsPath = "/actuator/prometheus")
          scrapeSelf(path = "/b", port = 9001, metricsPath = "stats")
        }.pathConfigs().map { it.path to it.url }

      urls shouldBe listOf("a" to "http://127.0.0.1:9000/actuator/prometheus", "b" to "http://127.0.0.1:9001/stats")
    }

    // An app reads its settings from env vars, which give an empty string when unset.
    "an empty username and password, or an empty certificate, count as none" {
      val config = embeddedAgent { scrapeSelf("app", 8080, username = "", password = "").trustCertificatePem("") }
      config.pathConfigs().single().url shouldBe "http://127.0.0.1:8080/metrics"
      config.toString() shouldContain "tls=off"
      config.options().hasTrustCertCollection.shouldBeFalse()
    }

    "path splices a username and password into any URL, encoded" {
      val config = embeddedAgent { path("x", "https://sidecar:9443/metrics", "user", "pass word") }
      val url = URI(config.pathConfigs().single().url)
      url.scheme shouldBe "https"
      url.userInfo shouldBe "user:pass word"
      url.host shouldBe "sidecar"
    }

    "path refuses separate credentials for a URL that already has its own" {
      val e =
        shouldThrow<IllegalArgumentException> {
          embeddedAgent { path("x", "http://u:p@sidecar:9100/metrics", "user", "password") }
        }
      e.message shouldContain "already has credentials"
    }

    "a path's labels are written as the JSON pathConfigs takes" {
      embeddedAgent { scrapeSelf("app", 8080, labels = mapOf("job" to "my-app", "team" to "a b")) }
        .pathConfigs()
        .single()
        .labels shouldBe """{"job":"my-app","team":"a b"}"""
    }

    "setting sets any agent setting by its key, ahead of a builder method's" {
      val options =
        embeddedAgent {
          name("from-the-builder")
          setting("name", "from-a-setting")
          setting("scrapeTimeoutSecs", 7)
          setting("http.clientTimeoutSecs", 33)
        }.options()

      options.agentName shouldBe "from-a-setting"
      options.scrapeTimeoutSecs shouldBe 7
      options.httpClientTimeoutSecs shouldBe 33
    }

    "the DSL and the builder build the same config" {
      val dsl =
        embeddedAgent {
          name("app")
          proxy("proxy:50051")
          token(TOKEN)
          scrapeSelf("app_metrics", 8080, "user", PASSWORD)
        }
      val builder =
        EmbeddedAgentConfig
          .builder()
          .name("app")
          .proxy("proxy:50051")
          .token(TOKEN)
          .scrapeSelf("app_metrics", 8080, "user", PASSWORD)
          .build()

      dsl.toConfig().root().unwrapped() shouldBe builder.toConfig().root().unwrapped()
    }

    // An app may log its settings; the token, the private key, and a path's credentials stay out.
    "toString leaves out the token, the private key, and a path's credentials" {
      val shown =
        embeddedAgent {
          name("app")
          token(TOKEN)
          clientCertificatePem(read(CLIENT_CERT), read(CLIENT_KEY))
          trustCertificatePem(read(CA_CERT))
          scrapeSelf("app_metrics", 8080, "user", PASSWORD)
        }.toString()

      shown shouldContain "name=app"
      shown shouldContain "token=set"
      shown shouldContain "tls=mutual"
      shown shouldContain "/app_metrics -> http://***@127.0.0.1:8080/metrics"
      listOf(TOKEN, "user", "p%40ss", read(CLIENT_KEY).lines()[1]).forEach { shown shouldNotContain it }
    }

    "a blank path, or a port out of range, is refused" {
      shouldThrow<IllegalArgumentException> { embeddedAgent { scrapeSelf(" ", 8080) } }
      shouldThrow<IllegalArgumentException> { embeddedAgent { scrapeSelf("app", 0) } }
      shouldThrow<IllegalArgumentException> { embeddedAgent { path("", "http://host/metrics") } }
      shouldThrow<IllegalArgumentException> { embeddedAgent { agentMetrics(70000) } }
    }

    "path and scrapeSelf each add a path" {
      embeddedAgent {
        scrapeSelf("a", 8080)
        path("b", "http://sidecar:9100/metrics")
      }.pathConfigs() shouldHaveSize 2
    }
  }

  companion object {
    private const val TOKEN = "0123456789abcdef0123456789abcdef"

    // Every character a URL's user info must escape, and some it need not
    private const val PASSWORD = "p@ss:w/rd%#+ ?&é"
  }
}
