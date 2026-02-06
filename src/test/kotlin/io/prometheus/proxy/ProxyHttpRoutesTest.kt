/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.proxy

import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.prometheus.proxy.ProxyHttpRoutes.ensureLeadingSlash
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

// Bug #12: The service discovery endpoint was registered using the raw sdPath config value,
// which defaults to "discovery" (no leading slash). The fix normalizes the path with
// ensureLeadingSlash() to ensure consistent route registration regardless of config format.
class ProxyHttpRoutesTest {
  @Test
  fun `ensureLeadingSlash should add slash when missing`() {
    "discovery".ensureLeadingSlash() shouldBe "/discovery"
  }

  @Test
  fun `ensureLeadingSlash should not double slash when already present`() {
    "/discovery".ensureLeadingSlash() shouldBe "/discovery"
  }

  @Test
  fun `ensureLeadingSlash should handle nested paths without slash`() {
    "api/discovery".ensureLeadingSlash() shouldBe "/api/discovery"
  }

  @Test
  fun `ensureLeadingSlash should handle nested paths with slash`() {
    "/api/discovery".ensureLeadingSlash() shouldBe "/api/discovery"
  }

  // Verifies that a normalized path (with leading slash) correctly registers
  // and matches incoming requests via Ktor routing.
  @Test
  fun `normalized path should match incoming requests`(): Unit =
    runBlocking {
      // Simulate the fix: config value "discovery" -> ensureLeadingSlash -> "/discovery"
      val configPath = "discovery"
      val normalizedPath = configPath.ensureLeadingSlash()

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get(normalizedPath) {
            call.respondText("found")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/discovery")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "found"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

  // Verifies that a config value already containing a leading slash
  // is handled correctly without double-slashing.
  @Test
  fun `config path with leading slash should still match after normalization`(): Unit =
    runBlocking {
      val configPath = "/discovery"
      val normalizedPath = configPath.ensureLeadingSlash()
      normalizedPath shouldBe "/discovery"

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          get(normalizedPath) {
            call.respondText("found")
          }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/discovery")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "found"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }
}
