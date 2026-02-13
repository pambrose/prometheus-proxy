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

import com.github.pambrose.common.util.zip
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.prometheus.Proxy
import io.prometheus.common.ScrapeResults
import io.prometheus.proxy.ProxyHttpRoutes.ensureLeadingSlash
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import io.ktor.server.cio.CIO as ServerCIO

// Bug #12: The service discovery endpoint was registered using the raw sdPath config value,
// which defaults to "discovery" (no leading slash). The fix normalizes the path with
// ensureLeadingSlash() to ensure consistent route registration regardless of config format.
class ProxyHttpRoutesTest : StringSpec() {
  private fun callLogActivityForResponse(
    path: String,
    response: ScrapeRequestResponse,
    proxy: Proxy,
  ) {
    val method = ProxyHttpRoutes::class.java.getDeclaredMethod(
      "logActivityForResponse",
      String::class.java,
      ScrapeRequestResponse::class.java,
      Proxy::class.java,
    )
    method.isAccessible = true
    method.invoke(ProxyHttpRoutes, path, response, proxy)
  }

  private fun createSpyProxyForSubmit(
    timeoutSecs: Int = 1,
    checkMillis: Int = 50,
    args: List<String> = emptyList(),
  ): Proxy {
    val proxy = spyk(
      Proxy(
        options = ProxyOptions(
          listOf(
            "-Dproxy.internal.scrapeRequestTimeoutSecs=$timeoutSecs",
            "-Dproxy.internal.scrapeRequestCheckMillis=$checkMillis",
          ) + args,
        ),
        inProcessServerName = "proxy-submit-test-${System.nanoTime()}",
        testMode = true,
      ),
    )
    every { proxy.isRunning } returns true
    return proxy
  }

  private fun createSpyProxyForRoutes(vararg extraArgs: String): Proxy {
    val proxy = spyk(
      Proxy(
        options = ProxyOptions(
          listOf(*extraArgs),
        ),
        inProcessServerName = "proxy-routes-test-${System.nanoTime()}",
        testMode = true,
      ),
    )
    every { proxy.isRunning } returns true
    return proxy
  }

  init {
    "ensureLeadingSlash should add slash when missing" {
      "discovery".ensureLeadingSlash() shouldBe "/discovery"
    }

    "ensureLeadingSlash should not double slash when already present" {
      "/discovery".ensureLeadingSlash() shouldBe "/discovery"
    }

    "ensureLeadingSlash should handle nested paths without slash" {
      "api/discovery".ensureLeadingSlash() shouldBe "/api/discovery"
    }

    "ensureLeadingSlash should handle nested paths with slash" {
      "/api/discovery".ensureLeadingSlash() shouldBe "/api/discovery"
    }

    // Verifies that a normalized path (with leading slash) correctly registers
    // and matches incoming requests via Ktor routing.
    "normalized path should match incoming requests" {
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
        delay(100.milliseconds) // Allow CIO engine to fully initialize
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
    "config path with leading slash should still match after normalization" {
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
        delay(100.milliseconds) // Allow CIO engine to fully initialize
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/discovery")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "found"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== ScrapeRequestResponse Tests ====================

    "ScrapeRequestResponse should store all properties" {
      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.OK,
        updateMsg = "success",
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
        contentText = """{"metric":"value"}""",
        failureReason = "",
        url = "http://localhost:8080/metrics",
        fetchDuration = 150.milliseconds,
      )

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentText shouldBe """{"metric":"value"}"""
      response.failureReason shouldBe ""
      response.url shouldBe "http://localhost:8080/metrics"
      response.fetchDuration shouldBe 150.milliseconds
    }

    "ScrapeRequestResponse should default to plain text content type" {
      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.ServiceUnavailable,
        updateMsg = "timed_out",
        fetchDuration = 5000.milliseconds,
      )

      response.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
      response.contentText shouldBe ""
      response.failureReason shouldBe ""
      response.url shouldBe ""
    }

    "ScrapeRequestResponse should handle error status codes" {
      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.NotFound,
        updateMsg = "path_not_found",
        failureReason = "Agent not found for path",
        url = "http://localhost/metrics",
        fetchDuration = 50.milliseconds,
      )

      response.statusCode shouldBe HttpStatusCode.NotFound
      response.updateMsg shouldBe "path_not_found"
      response.failureReason shouldBe "Agent not found for path"
    }

    // ==================== ResponseResults Tests ====================

    "ResponseResults should have correct defaults" {
      val results = ResponseResults()

      results.statusCode shouldBe HttpStatusCode.OK
      results.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
      results.contentText shouldBe ""
      results.updateMsg shouldBe ""
    }

    "ResponseResults should accept custom values" {
      val results = ResponseResults(
        statusCode = HttpStatusCode.ServiceUnavailable,
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
        contentText = """{"error":"proxy stopped"}""",
        updateMsg = "proxy_stopped",
      )

      results.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      results.contentText shouldBe """{"error":"proxy stopped"}"""
      results.updateMsg shouldBe "proxy_stopped"
    }

    "ResponseResults copy should produce modified instance" {
      val results = ResponseResults()

      val modified = results.copy(
        statusCode = HttpStatusCode.NotFound,
        contentText = "modified content",
        updateMsg = "modified_msg",
      )

      modified.statusCode shouldBe HttpStatusCode.NotFound
      modified.contentText shouldBe "modified content"
      modified.updateMsg shouldBe "modified_msg"
    }

    // ==================== ClosedSendChannelException Handling Tests ====================

    "writeScrapeRequest on invalidated context should throw ClosedSendChannelException" {
      val context = AgentContext("remote-addr")
      val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)

      context.invalidate()

      // Verify the exact exception type that submitScrapeRequest needs to catch
      val exception = shouldThrow<Exception> {
        context.writeScrapeRequest(wrapper)
      }
      exception.shouldBeInstanceOf<ClosedSendChannelException>()
    }

    // Verifies that writeScrapeRequest on an invalidated AgentContext produces a
    // ClosedSendChannelException, and that backlog stays at zero (Bug #2 fix ensures
    // the counter is decremented on failure). This is the exact exception type that
    // submitScrapeRequest catches (Bug #3 fix) to return 503 ServiceUnavailable
    // instead of propagating as an HTTP 500.
    "writeScrapeRequest after concurrent invalidation should not corrupt backlog counter" {
      val context = AgentContext("remote-addr")
      val wrapper = mockk<ScrapeRequestWrapper>(relaxed = true)

      // Write some items, then invalidate, then try to write again
      context.writeScrapeRequest(mockk(relaxed = true))
      context.writeScrapeRequest(mockk(relaxed = true))
      context.scrapeRequestBacklogSize shouldBe 2

      context.invalidate()
      context.scrapeRequestBacklogSize shouldBe 0

      // Attempting to write after invalidation should throw ClosedSendChannelException
      // and the backlog counter should remain at 0 (not go to 1 then fail)
      val exception = shouldThrow<Exception> {
        context.writeScrapeRequest(wrapper)
      }
      exception.shouldBeInstanceOf<ClosedSendChannelException>()
      context.scrapeRequestBacklogSize shouldBe 0
    }

    // ==================== logActivityForResponse Tests ====================
    // logActivityForResponse is private, so we test it via reflection.
    // It formats: "/$path - $updateMsg - $statusCode [reason: [$failureReason]] time: $fetchDuration url: $url"

    "logActivityForResponse should format success status without failure reason" {
      val capturedActivity = slot<String>()
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.logActivity(capture(capturedActivity)) } returns Unit

      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.OK,
        updateMsg = "success",
        contentText = "metric 1.0",
        url = "http://localhost:8080/metrics",
        fetchDuration = 150.milliseconds,
      )

      callLogActivityForResponse("metrics", response, mockProxy)

      capturedActivity.captured shouldContain "/metrics - success - 200 OK"
      capturedActivity.captured shouldContain "time: 150ms"
      capturedActivity.captured shouldContain "url: http://localhost:8080/metrics"
      // Success should NOT include "reason:" text
      capturedActivity.captured shouldNotContain "reason:"
    }

    "logActivityForResponse should include failure reason for non-success status" {
      val capturedActivity = slot<String>()
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.logActivity(capture(capturedActivity)) } returns Unit

      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.NotFound,
        updateMsg = "path_not_found",
        failureReason = "Agent not found for path",
        url = "http://localhost:8080/missing",
        fetchDuration = 50.milliseconds,
      )

      callLogActivityForResponse("missing", response, mockProxy)

      capturedActivity.captured shouldContain "/missing - path_not_found - 404 Not Found"
      capturedActivity.captured shouldContain "reason: [Agent not found for path]"
      capturedActivity.captured shouldContain "url: http://localhost:8080/missing"
    }

    "logActivityForResponse should include failure reason for ServiceUnavailable" {
      val capturedActivity = slot<String>()
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.logActivity(capture(capturedActivity)) } returns Unit

      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.ServiceUnavailable,
        updateMsg = "timed_out",
        failureReason = "",
        url = "",
        fetchDuration = 5000.milliseconds,
      )

      callLogActivityForResponse("slow-endpoint", response, mockProxy)

      capturedActivity.captured shouldContain "/slow-endpoint - timed_out - 503 Service Unavailable"
      capturedActivity.captured shouldContain "reason:"
    }

    // ==================== authHeaderWithoutTlsWarned Tests ====================
    // The AtomicBoolean ensures the auth-without-TLS warning fires only once.

    "authHeaderWithoutTlsWarned should be a single-fire AtomicBoolean" {
      // Access the private field on the ProxyHttpRoutes object
      val field = ProxyHttpRoutes::class.java.getDeclaredField("authHeaderWithoutTlsWarned")
      field.isAccessible = true
      val atomicBoolean = field.get(ProxyHttpRoutes) as AtomicBoolean

      // Reset it for testing
      atomicBoolean.store(false)

      // First compareAndSet should succeed (false -> true)
      atomicBoolean.compareAndSet(false, true) shouldBe true

      // Second compareAndSet should fail (already true)
      atomicBoolean.compareAndSet(false, true) shouldBe false

      // Reset for other tests
      atomicBoolean.store(false)
    }

    // ==================== submitScrapeRequest Tests ====================
    // submitScrapeRequest is internal, so accessible from same-package tests.
    // These tests exercise the error and success branches in the core scrape request handler.

    "submitScrapeRequest should return agent_disconnected on ClosedSendChannelException" {
      val proxy = createSpyProxyForSubmit()
      val agentContext = AgentContext("test-remote")
      agentContext.invalidate()

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "agent_disconnected"
    }

    "submitScrapeRequest should return timed_out when scrape request times out" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 1, checkMillis = 50)
      val agentContext = AgentContext("test-remote")

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "timed_out"
    }

    "submitScrapeRequest should return timed_out when agent disconnects during scrape" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")

      launch {
        delay(200.milliseconds)
        agentContext.invalidate()
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "timed_out"
    }

    "submitScrapeRequest should return timed_out when proxy stops during scrape" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")

      // Start with proxy running, then stop after a brief delay
      var proxyRunning = true
      every { proxy.isRunning } answers { proxyRunning }

      launch {
        delay(200.milliseconds)
        proxyRunning = false
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "timed_out"
    }

    "submitScrapeRequest should unblock immediately when agent is removed" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")
      proxy.agentContextManager.addAgentContext(agentContext)

      launch {
        delay(200.milliseconds)
        proxy.removeAgentContext(agentContext.agentId, "test disconnect")
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      // It should return 502 Bad Gateway because failScrapeRequest uses that code
      response.statusCode shouldBe HttpStatusCode.BadGateway
      response.updateMsg shouldBe "path_not_found" // assigned by failScrapeRequest
    }

    "submitScrapeRequest should unblock immediately when proxy is shut down" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")
      proxy.agentContextManager.addAgentContext(agentContext)

      launch {
        delay(200.milliseconds)
        // Simulate what Proxy.shutDown() does
        proxy.agentContextManager.invalidateAllAgentContexts()
        proxy.scrapeRequestManager.failAllInFlightScrapeRequests("Proxy is shutting down")
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.BadGateway
      response.updateMsg shouldBe "path_not_found"
    }

    "submitScrapeRequest should return success for valid non-zipped response" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        val results = ScrapeResults(
          srAgentId = agentContext.agentId,
          srScrapeId = wrapper.scrapeId,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain; charset=utf-8",
          srContentAsText = "metric_value 1.0",
          srUrl = "http://localhost:8080/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results)
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentText shouldBe "metric_value 1.0"
      response.url shouldBe "http://localhost:8080/metrics"
    }

    "submitScrapeRequest should unzip zipped response content" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")
      val originalContent = "metric_value 1.0\nmetric_value2 2.0"

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        val results = ScrapeResults(
          srAgentId = agentContext.agentId,
          srScrapeId = wrapper.scrapeId,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain; charset=utf-8",
          srZipped = true,
          srContentAsZipped = originalContent.zip(),
          srUrl = "http://localhost:8080/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results)
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentText shouldBe originalContent
    }

    "submitScrapeRequest should return PayloadTooLarge for zip bombs" {
      // Set a very small limit for testing via system property
      val proxy = createSpyProxyForSubmit(
        timeoutSecs = 30,
        args = listOf("-Dproxy.internal.maxUnzippedContentSizeMBytes=0"),
      )

      val agentContext = AgentContext("test-remote")
      val originalContent = "a".repeat(1024)

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        val results = ScrapeResults(
          srAgentId = agentContext.agentId,
          srScrapeId = wrapper.scrapeId,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain; charset=utf-8",
          srZipped = true,
          srContentAsZipped = originalContent.zip(),
          srUrl = "http://localhost:8080/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results)
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.PayloadTooLarge
      response.updateMsg shouldBe "payload_too_large"
      response.failureReason shouldContain "exceeds limit"
    }

    "submitScrapeRequest should fallback to plain text on content type parse error" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        val results = ScrapeResults(
          srAgentId = agentContext.agentId,
          srScrapeId = wrapper.scrapeId,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "this-is-not-a-valid-content-type!!!",
          srContentAsText = "metric_value 1.0",
          srUrl = "http://localhost:8080/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results)
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    }

    "submitScrapeRequest should return path_not_found for non-success status" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, checkMillis = 50)
      val agentContext = AgentContext("test-remote")

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        val results = ScrapeResults(
          srAgentId = agentContext.agentId,
          srScrapeId = wrapper.scrapeId,
          srValidResponse = false,
          srStatusCode = 404,
          srContentType = "text/plain; charset=utf-8",
          srFailureReason = "Endpoint not found",
          srUrl = "http://localhost:8080/missing",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results)
      }

      val response = ProxyHttpRoutes.submitScrapeRequest(
        agentContext,
        proxy,
        "metrics",
        "",
        mockk<ApplicationRequest>(relaxed = true),
      )

      response.statusCode shouldBe HttpStatusCode.NotFound
      response.updateMsg shouldBe "path_not_found"
      response.failureReason shouldBe "Endpoint not found"
      response.contentText shouldBe ""
    }

    // ==================== handleClientRequests Integration Tests ====================
    // These tests exercise the full request dispatch logic through embedded HTTP servers
    // with ProxyHttpRoutes.handleRequests() installed as the routing handler.

    "handleClientRequests should return ServiceUnavailable when proxy is not running" {
      val proxy = createSpyProxyForRoutes()
      every { proxy.isRunning } returns false

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/metrics")
        response.status shouldBe HttpStatusCode.ServiceUnavailable

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "handleClientRequests should return NotFound for favicon request" {
      val proxy = createSpyProxyForRoutes()

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/favicon.ico")
        response.status shouldBe HttpStatusCode.NotFound

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "handleClientRequests should return NotFound for unregistered path" {
      val proxy = createSpyProxyForRoutes()

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/unknown-metrics")
        response.status shouldBe HttpStatusCode.NotFound

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "handleClientRequests should return 42 for blitz request" {
      val proxy = createSpyProxyForRoutes(
        "-Dproxy.internal.blitz.enabled=true",
        "-Dproxy.internal.blitz.path=blitz-test",
      )

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/blitz-test")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "42"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "handleClientRequests should return NotFound for invalidated agent context" {
      val proxy = createSpyProxyForRoutes()
      val agentContext = AgentContext("test-remote")
      proxy.pathManager.addPath("test-metrics", "", agentContext)
      agentContext.invalidate()

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/test-metrics")
        response.status shouldBe HttpStatusCode.NotFound

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "handleServiceDiscoveryEndpoint should return JSON when enabled" {
      val proxy = createSpyProxyForRoutes(
        "--sd_enabled",
        "--sd_path",
        "/test-sd",
        "--sd_target_prefix",
        "http://localhost:8080",
      )

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        val response = client.get("http://localhost:$port/test-sd")
        response.status shouldBe HttpStatusCode.OK
        val body = response.bodyAsText()
        body shouldContain "["
        body shouldContain "]"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Bug #13: Consolidated OpenMetrics EOF Handling Tests ====================

    // Bug #13: When consolidated paths have multiple agents returning OpenMetrics format,
    // each agent's response ends with "# EOF". Naively joining with "\n" produces
    // "# EOF" markers mid-stream, which is invalid OpenMetrics. mergeContentTexts()
    // strips intermediate "# EOF" markers and appends a single one at the end.

    "Bug #13: mergeContentTexts should strip intermediate EOF markers" {
      val results = listOf(
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
      )

      val merged = ProxyHttpRoutes.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      // Should end with exactly one # EOF
      merged.trimEnd().endsWith("# EOF") shouldBe true
      // Should NOT have # EOF in the middle
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
    }

    "Bug #13: mergeContentTexts should not add EOF when no results have it" {
      val results = listOf(
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0\n",
          fetchDuration = 10.milliseconds,
        ),
      )

      val merged = ProxyHttpRoutes.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      merged shouldNotContain "# EOF"
    }

    "Bug #13: mergeContentTexts should return single result unchanged" {
      val results = listOf(
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
      )

      val merged = ProxyHttpRoutes.mergeContentTexts(results)

      // Single result should be returned as-is
      merged shouldBe "metric_a 1.0\n# EOF"
    }

    "Bug #13: mergeContentTexts should handle three agents with EOF" {
      val results = listOf(
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_c 3.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
      )

      val merged = ProxyHttpRoutes.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      merged shouldContain "metric_c 3.0"
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
      merged.trimEnd().endsWith("# EOF") shouldBe true
    }

    "Bug #13: mergeContentTexts should handle mixed EOF and non-EOF results" {
      val results = listOf(
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0\n",
          fetchDuration = 10.milliseconds,
        ),
      )

      val merged = ProxyHttpRoutes.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      // If any result had EOF, the merged result should end with EOF
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
      merged.trimEnd().endsWith("# EOF") shouldBe true
    }

    "handleServiceDiscoveryEndpoint should not register when sdEnabled is false" {
      val proxy = createSpyProxyForRoutes()
      // SD is disabled by default

      val server = embeddedServer(ServerCIO, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }.start(wait = false)

      try {
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClient(CIO) { expectSuccess = false }

        // With SD disabled, /discovery is handled by get("/*") as an unknown path
        val response = client.get("http://localhost:$port/discovery")
        response.status shouldBe HttpStatusCode.NotFound

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }
  }
}
