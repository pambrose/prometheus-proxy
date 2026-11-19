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

package io.prometheus.harness

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.Agent
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.io.File
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

// TLS with every certificate and key given as PEM text, none as a file: the form a platform that passes secrets as
// environment variables needs, which lets the published distroless proxy image run TLS with no wrapper. Over Netty, so
// each scrape crosses a real TLS channel. The proxy takes its material as base64 PEM, as a one-line variable field
// holds it; the agent, embedded, takes its own as PEM text in an in-memory config, so neither side reads a file.
class TlsPemTest : StringSpec() {
  init {
    "scrapes flow over TLS when the proxy's certificate and key, and the agent's trust, are PEM" {
      scrapeThroughTls(
        httpPort = TestPorts.TLS_PEM_HTTP_PORT,
        agentPort = TestPorts.TLS_PEM_AGENT_PORT,
        proxyTls =
          [
            "-Dproxy.tls.certChainPem=${base64(SERVER_CERT)}",
            "-Dproxy.tls.privateKeyPem=${base64(SERVER_KEY)}",
          ],
        agentTls = mapOf("trustCertCollectionPem" to read(CA_CERT)),
      )
    }

    "scrapes flow over mutual TLS when both sides' certificates, keys and trust are PEM" {
      scrapeThroughTls(
        httpPort = TestPorts.TLS_PEM_MUTUAL_HTTP_PORT,
        agentPort = TestPorts.TLS_PEM_MUTUAL_AGENT_PORT,
        proxyTls =
          [
            "-Dproxy.tls.certChainPem=${base64(SERVER_CERT)}",
            "-Dproxy.tls.privateKeyPem=${base64(SERVER_KEY)}",
            "-Dproxy.tls.trustCertCollectionPem=${base64(CA_CERT)}",
          ],
        agentTls =
          mapOf(
            "certChainPem" to read(CLIENT_CERT),
            "privateKeyPem" to read(CLIENT_KEY),
            "trustCertCollectionPem" to read(CA_CERT),
          ),
      )
    }
  }

  // Starts a proxy with [proxyTls], a scrape target, and an embedded agent whose TLS settings are [agentTls], then
  // scrapes the target through the proxy until it answers.
  private suspend fun scrapeThroughTls(
    httpPort: Int,
    agentPort: Int,
    proxyTls: List<String>,
    agentTls: Map<String, String>,
  ) {
    PrometheusRegistry.defaultRegistry.clear()

    val target =
      embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing { get("/metrics") { call.respondText(TARGET_METRICS) } }
      }
    val proxy = startProxy(args = ["--agent_port", "$agentPort"] + proxyTls, proxyPort = httpPort)
    var agent: EmbeddedAgentInfo? = null
    try {
      val targetUrl = "http://$LOOPBACK_HOST:${target.startAndAwaitReady()}/metrics"
      val config =
        ConfigFactory.parseMap(
          mapOf(
            "agent" to
              mapOf(
                "name" to "tls-pem-agent",
                "proxy" to mapOf("hostname" to "localhost", "port" to agentPort),
                // The test certificates are issued for *.test.google.fr, not localhost.
                "tls" to agentTls + ("overrideAuthority" to "foo.test.google.fr"),
                "pathConfigs" to [mapOf("name" to "app", "path" to PATH, "url" to targetUrl)],
                "internal" to mapOf("reconnectPauseSecs" to 1),
              ),
          ),
        )
      agent = Agent.startAsyncAgent(config, logBanner = false)

      HttpClient(ClientCIO).use { client ->
        eventually(30.seconds) {
          val response = client.get("http://$LOOPBACK_HOST:$httpPort/$PATH")
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain TARGET_METRICS
        }
      }
    } finally {
      runCatching { agent?.shutdown() }
      runCatching { proxy.stopSync(10.seconds) }
      target.stop(0, 0)
    }
  }

  companion object {
    private const val PATH = "tls_pem_metrics"
    private const val TARGET_METRICS = "tls_pem_target_metric 1.0"

    private const val CA_CERT = "testing/certs/ca.pem"
    private const val SERVER_CERT = "testing/certs/server1.pem"
    private const val SERVER_KEY = "testing/certs/server1.key"
    private const val CLIENT_CERT = "testing/certs/client.pem"
    private const val CLIENT_KEY = "testing/certs/client.key"

    private fun read(path: String) = File(path).readText()

    private fun base64(path: String) = Base64.getEncoder().encodeToString(read(path).toByteArray())
  }
}
