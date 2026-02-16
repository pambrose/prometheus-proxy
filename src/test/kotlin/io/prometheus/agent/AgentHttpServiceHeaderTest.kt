/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.dsl.KtorDsl.newHttpClient
import com.google.common.net.HttpHeaders.ACCEPT
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.*
import io.ktor.server.cio.CIO as ServerCIO

// Bug #11: The Accept header was set in the HttpClient's defaultRequest block when the client
// was created. Since HttpClient instances are cached and reused, a stale Accept header from
// the first request persisted for all subsequent requests using that cached client.
// The fix moves the Accept header to per-request headers (prepareRequestHeaders).
// These tests verify that per-request headers produce the correct Accept value for each
// request, and that baking headers into defaultRequest causes staleness.
class AgentHttpServiceHeaderTest : StringSpec() {
  init {
    "per-request Accept header should vary independently of cached client" {
      val capturedHeaders = Collections.synchronizedList(mutableListOf<String?>())
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            capturedHeaders.add(call.request.header(HttpHeaders.Accept))
            call.respondText("test_metric 1")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port

        // Create a client WITHOUT defaultRequest Accept header (the fix pattern)
        val client = newHttpClient()

        // Request 1: openmetrics Accept
        client.get("http://localhost:$port/metrics") {
          header(ACCEPT, "application/openmetrics-text")
        }
        // Request 2: text/plain Accept
        client.get("http://localhost:$port/metrics") {
          header(ACCEPT, "text/plain")
        }
        // Request 3: no Accept header
        client.get("http://localhost:$port/metrics")

        capturedHeaders[0] shouldBe "application/openmetrics-text"
        capturedHeaders[1] shouldBe "text/plain"
        // With no explicit Accept header, Ktor sends the default */*
        capturedHeaders[2] shouldBe "*/*"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "defaultRequest Accept header should persist across requests demonstrating old bug" {
      val capturedHeaders = Collections.synchronizedList(mutableListOf<String?>())
      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get("/metrics") {
            capturedHeaders.add(call.request.header(HttpHeaders.Accept))
            call.respondText("test_metric 1")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port

        // Create a client WITH defaultRequest Accept header (the OLD buggy pattern)
        val staleClient = HttpClient(CIO) {
          expectSuccess = false
          defaultRequest {
            header(ACCEPT, "application/openmetrics-text")
          }
        }

        // Request 1: uses default Accept (matches)
        staleClient.get("http://localhost:$port/metrics")
        // Request 2: tries to override with text/plain per-request
        staleClient.get("http://localhost:$port/metrics") {
          header(ACCEPT, "text/plain")
        }

        // The default Accept from client creation persists in BOTH requests
        // Request 2 has BOTH headers merged (default + per-request)
        capturedHeaders[0] shouldBe "application/openmetrics-text"
        // Request 2: the stale default is still present alongside the per-request header
        capturedHeaders[1]!!.contains("application/openmetrics-text") shouldBe true

        staleClient.close()
      } finally {
        server.stop(0, 0)
      }
    }
  }
}
