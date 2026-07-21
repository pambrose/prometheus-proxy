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

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.harness.support.TestUtils.startAgent
import kotlin.time.Duration.Companion.seconds

/**
 * The operational web UI (Feature 5) over the Netty transport.
 *
 * Drives the real service rather than the renderer: serves the page, opens the WebSocket, and asserts
 * that a fragment arrives carrying an agent that connected *after* the socket was already open. That
 * ordering is the point — it proves the push path works, not merely that the initial render does.
 */
class ProxyWebUiTest : StringSpec() {
  init {
    "the web UI serves a page and pushes updates over the WebSocket" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy =
        startProxy(
          args = ["--agent_port", "$PROXY_GRPC_PORT", "--ui", "--ui_port", "$UI_PORT"],
          proxyPort = PROXY_HTTP_PORT,
          configArgs = ["--config", CONFIG_FILE],
        )

      val client = HttpClient(CIO) { install(WebSockets) }
      var agent: Agent? = null

      try {
        // The page must render with no agents at all -- an operator opening the UI on a fresh proxy
        // should see an explanation, not a blank pane or a stack trace.
        eventually(20.seconds) {
          val response = client.get("http://$LOOPBACK_HOST:$UI_PORT/ui")
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain "No agents connected"
        }

        // htmx itself must be served from the classpath, not a CDN: the fat JAR has to work with no
        // outbound network access, which is the normal condition for a proxy bridging a firewall.
        client.get("http://$LOOPBACK_HOST:$UI_PORT/ui/assets/htmx.min.js").also {
          it.status shouldBe HttpStatusCode.OK
          it.bodyAsText() shouldContain "htmx"
        }

        client.webSocket("ws://$LOOPBACK_HOST:$UI_PORT/ui/events") {
          // The immediate frame on connect, so a browser renders without waiting for a tick.
          val initial = (incoming.receive() as Frame.Text).readText()
          initial shouldContain "hx-swap-oob"

          // Connect an agent only NOW, with the socket already open, so the frame below can only have
          // been produced by the push path.
          agent =
            startAgent(
              configArgs = ["--config", CONFIG_FILE],
              args = ["--proxy", "$LOOPBACK_HOST:$PROXY_GRPC_PORT"],
            )
          agent.awaitInitialConnection(20.seconds).shouldBeTrue()

          // any short-circuits, so MAX_FRAMES is a budget rather than a required frame count.
          (1..MAX_FRAMES)
            .any { (incoming.receive() as Frame.Text).readText().contains(AGENT_NAME) }
            .shouldBeTrue()
        }
      } finally {
        agent?.also { if (it.isRunning) runCatching { it.stopSync(10.seconds) } }
        client.close()
        runCatching { proxy.stopSync(10.seconds) }
      }
    }

    "the UI must stay off unless enabled" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy =
        startProxy(
          args = ["--agent_port", "$OFF_GRPC_PORT"],
          proxyPort = OFF_HTTP_PORT,
          configArgs = ["--config", CONFIG_FILE],
        )

      val client = HttpClient(CIO)
      try {
        // Nothing should be listening on the UI port: the feature is opt-in, matching admin and metrics.
        runCatching { client.get("http://$LOOPBACK_HOST:$OFF_UI_PORT/ui") }
          .isFailure
          .shouldBeTrue()
      } finally {
        client.close()
        runCatching { proxy.stopSync(10.seconds) }
      }
    }
    // The whole HA story in one assertion: an agent configured with two endpoints, whose primary does
    // not exist, connects to the secondary -- and this proxy's UI says so. Without the endpoint list on
    // the wire the dashboard could not distinguish that from a fresh start.
    "the UI reports an agent that reached this proxy via failover" {
      CollectorRegistry.defaultRegistry.clear()

      val proxy =
        startProxy(
          args = ["--agent_port", "$FAILOVER_GRPC_PORT", "--ui", "--ui_port", "$FAILOVER_UI_PORT"],
          proxyPort = FAILOVER_HTTP_PORT,
          configArgs = ["--config", CONFIG_FILE],
        )
      val client = HttpClient(CIO)
      var agent: Agent? = null

      try {
        // Primary is a port nothing listens on, so the agent must advance to the second entry.
        agent =
          startAgent(
            configArgs = ["--config", CONFIG_FILE],
            args = ["--proxy", "$LOOPBACK_HOST:$DEAD_PORT,$LOOPBACK_HOST:$FAILOVER_GRPC_PORT"],
          )
        agent.awaitInitialConnection(30.seconds).shouldBeTrue()

        eventually(30.seconds) {
          val listing = client.get("http://$LOOPBACK_HOST:$FAILOVER_UI_PORT/ui").bodyAsText()
          val agentId = AGENT_ID_PATTERN.find(listing)?.groupValues?.get(1).orEmpty()
          val detail =
            client.get("http://$LOOPBACK_HOST:$FAILOVER_UI_PORT/ui/agents/$agentId").bodyAsText()

          detail shouldContain "via $LOOPBACK_HOST:$FAILOVER_GRPC_PORT (2 of 2)"
          detail shouldContain "failed over"
        }
      } finally {
        agent?.also { if (it.isRunning) runCatching { it.stopSync(10.seconds) } }
        client.close()
        runCatching { proxy.stopSync(10.seconds) }
      }
    }
  }

  companion object {
    private const val CONFIG_FILE = "config/test-configs/web-ui.conf"
    private const val AGENT_NAME = "web-ui-agent"

    // Dedicated ports, following the one-off convention the other standalone harness specs use.
    private const val PROXY_HTTP_PORT = 9540
    private const val PROXY_GRPC_PORT = 9541
    private const val UI_PORT = 9542
    private const val OFF_HTTP_PORT = 9543
    private const val OFF_GRPC_PORT = 9544
    private const val OFF_UI_PORT = 9545

    // Bounded so a push that never carries the agent fails the assertion rather than hanging the suite.
    private const val MAX_FRAMES = 12

    private const val FAILOVER_HTTP_PORT = 9546
    private const val FAILOVER_GRPC_PORT = 9547
    private const val FAILOVER_UI_PORT = 9548

    // Nothing listens here, so the agent's first endpoint fails and it advances to the second.
    private const val DEAD_PORT = 9549

    private val AGENT_ID_PATTERN = """hx-get="/ui/agents/([^"]+)"""".toRegex()
  }
}
