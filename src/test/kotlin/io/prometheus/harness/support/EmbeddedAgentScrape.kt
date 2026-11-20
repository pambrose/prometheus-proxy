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

package io.prometheus.harness.support

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.Proxy
import io.prometheus.agent.EmbeddedAgentConfig
import io.prometheus.agent.EmbeddedAgentInfo
import io.prometheus.agent.embeddedAgent
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

/**
 * An agent started embedded from a config built in memory, with the builder (`embeddedAgent { }`), scraped end to
 * end: starts a proxy on [httpPort], taking agents on [agentPort] with [proxyArgs], a target that serves
 * [targetMetrics], and the agent, named [agentName], which registers the target as [path] and connects to localhost,
 * with whatever else [configure] sets, such as its TLS. With [targetCredentials], the target answers only a request
 * that presents them as Basic Auth, and the agent is given them for the path. Once the proxy serves the target's
 * metrics at [path], runs [block] with the proxy and the agent, then stops all three.
 */
internal suspend fun withEmbeddedAgentScraped(
  httpPort: Int,
  agentPort: Int,
  agentName: String = "embedded-agent",
  path: String = "embedded_metrics",
  targetMetrics: String = "embedded_target_metric 1.0",
  proxyArgs: List<String> = emptyList(),
  targetCredentials: Pair<String, String>? = null,
  configure: EmbeddedAgentConfig.Builder.() -> Unit = {},
  block: suspend (proxy: Proxy, agent: EmbeddedAgentInfo) -> Unit = { _, _ -> },
) {
  PrometheusRegistry.defaultRegistry.clear()

  val expectedAuth =
    targetCredentials?.let { (user, password) ->
      "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
    }
  val target =
    embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
      routing {
        get("/metrics") {
          if (expectedAuth == null || call.request.headers[HttpHeaders.Authorization] == expectedAuth)
            call.respondText(targetMetrics)
          else
            call.respond(HttpStatusCode.Unauthorized)
        }
      }
    }
  val proxyServer = startProxy(args = ["--agent_port", "$agentPort"] + proxyArgs, proxyPort = httpPort)
  var agent: EmbeddedAgentInfo? = null
  try {
    val targetUrl = "http://$LOOPBACK_HOST:${target.startAndAwaitReady()}/metrics"
    agent =
      embeddedAgent {
        name = agentName
        // localhost, the name a test certificate's override authority stands in for
        proxy = "localhost:$agentPort"
        path(path, targetUrl, targetCredentials?.first, targetCredentials?.second)
        setting("internal.reconnectPauseSecs", 1)
        configure()
      }.start(logBanner = false)

    HttpClient(ClientCIO).use { client ->
      eventually(30.seconds) {
        val response = client.get("http://$LOOPBACK_HOST:$httpPort/$path")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain targetMetrics
      }
    }
    block(proxyServer, agent)
  } finally {
    runCatching { agent?.shutdown() }
    runCatching { proxyServer.stopSync(10.seconds) }
    target.stop(0, 0)
  }
}
