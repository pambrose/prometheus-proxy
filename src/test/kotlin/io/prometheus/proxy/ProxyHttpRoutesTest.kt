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

package io.prometheus.proxy

import com.pambrose.common.dsl.KtorDsl.newHttpClient
import com.pambrose.common.util.ensureLeadingSlash
import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedByteChannelException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.ScrapeResults
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.captureLogs
import io.prometheus.common.proxyOptions
import ch.qos.logback.classic.Level
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.metrics.core.metrics.Counter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import java.net.Socket
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource.Monotonic
import io.ktor.server.cio.CIO as ServerCIO
import kotlinx.coroutines.async

// Bug #12: The service discovery endpoint was registered using the raw sdPath config value,
// which defaults to "discovery" (no leading slash). The fix normalizes the path with
// ensureLeadingSlash() to ensure consistent route registration regardless of config format.
@Suppress("LargeClass")
class ProxyHttpRoutesTest : StringSpec() {
  private val testProxies: MutableList<Proxy> = []

  // A mock proxy with debug and the dashboard switched as given, plus its event bus, for the bookkeeping tests.
  private fun bookkeepingProxy(
    debugEnabled: Boolean,
    dashboardEnabled: Boolean,
  ): Pair<Proxy, ProxyEventBus> {
    val options = mockk<ProxyOptions>(relaxed = true)
    every { options.debugEnabled } returns debugEnabled
    every { options.dashboardEnabled } returns dashboardEnabled
    val bus = mockk<ProxyEventBus>(relaxed = true)
    val proxy = mockk<Proxy>(relaxed = true)
    every { proxy.options } returns options
    every { proxy.eventBus } returns bus
    return proxy to bus
  }

  private fun scrapedResponse() =
    ScrapeRequestResponse(statusCode = HttpStatusCode.OK, updateMsg = "success", fetchDuration = 10.milliseconds)

  init {
    afterTest {
      testProxies.forEach { proxy ->
        proxy.scrapeRequestManager.failAllInFlightScrapeRequests("test cleanup", ProxyFailure.PROXY_STOPPED)
        proxy.agentContextManager.invalidateAllAgentContexts()
      }
      testProxies.clear()
    }

    // ==================== Per-scrape bookkeeping ====================

    // The activity log feeds only /debug, and the scrape record and ScrapeCompleted event feed only the dashboard.
    // Both are off by default, so a scrape must not pay for either one unless it is on.
    "recordScrapeOutcome should skip all bookkeeping when debug and the dashboard are both off" {
      val (proxy, bus) = bookkeepingProxy(debugEnabled = false, dashboardEnabled = false)

      ProxyHttpRoutes.recordScrapeOutcome("metrics", scrapingAgent(), scrapedResponse(), proxy)

      verify(exactly = 0) { proxy.logActivity(any()) }
      verify(exactly = 0) { proxy.recordScrape(any()) }
      verify(exactly = 0) { bus.emit(any()) }
    }

    "recordScrapeOutcome should write only the activity log when only debug is on" {
      val (proxy, bus) = bookkeepingProxy(debugEnabled = true, dashboardEnabled = false)

      ProxyHttpRoutes.recordScrapeOutcome("metrics", scrapingAgent(), scrapedResponse(), proxy)

      verify(exactly = 1) { proxy.logActivity(match { it.contains("/metrics - success - 200") }) }
      verify(exactly = 0) { proxy.recordScrape(any()) }
      verify(exactly = 0) { bus.emit(any()) }
    }

    "recordScrapeOutcome should record the scrape and emit its event when only the dashboard is on" {
      val (proxy, bus) = bookkeepingProxy(debugEnabled = false, dashboardEnabled = true)

      ProxyHttpRoutes.recordScrapeOutcome("metrics", scrapingAgent(), scrapedResponse(), proxy)

      verify(exactly = 0) { proxy.logActivity(any()) }
      verify(exactly = 1) { proxy.recordScrape(match { it.path == "metrics" && it.outcome == "success" }) }
      verify(exactly = 1) { bus.emit(ofType<ProxyEvent.ScrapeCompleted>()) }
    }

    // A departed path's row is attributed by its last scrape when the proxy no longer holds its departure, so the
    // record carries what the row and the search show of the agent: its name and the identity it registered as.
    "recordScrapeOutcome should record the agent's id, name, and identity on the scrape" {
      val (proxy, _) = bookkeepingProxy(debugEnabled = false, dashboardEnabled = true)

      ProxyHttpRoutes.recordScrapeOutcome("metrics", scrapingAgent(), scrapedResponse(), proxy)

      verify(exactly = 1) {
        proxy.recordScrape(match { it.agentId == "agent-1" && it.agentName == "agent" && it.identity == "team-a" })
      }
    }
  }

  // The agent a bookkeeping test's scrape went to, connected as the identity team-a.
  private fun scrapingAgent(): AgentContext =
    mockk {
      every { agentId } returns "agent-1"
      every { agentName } returns "agent"
      every { identityName } returns "team-a"
    }

  // Calls recordScrapeOutcome on [proxy] with debug switched on, so the /debug activity line these tests assert is
  // written.
  private fun callRecordScrapeOutcome(
    path: String,
    response: ScrapeRequestResponse,
    proxy: Proxy,
  ) {
    val options = mockk<ProxyOptions>(relaxed = true)
    every { options.debugEnabled } returns true
    every { proxy.options } returns options
    ProxyHttpRoutes.recordScrapeOutcome(path, scrapingAgent(), response, proxy)
  }

  private fun createSpyProxyForRoutes(vararg extraArgs: String): Proxy {
    val proxy = spyk(
      Proxy(
        options = proxyOptions(extraArgs.asList()),
        inProcessServerName = "proxy-routes-test-${System.nanoTime()}",
        testMode = true,
      ),
    )
    every { proxy.isRunning } returns true
    testProxies += proxy
    return proxy
  }

  private suspend fun startServerAndGetPort(server: EmbeddedServer<*, *>): Int {
    server.start(wait = false)
    val port = server.engine.resolvedConnectors().first().port

    // A bound TCP port doesn't mean Ktor's accept loop is wired up — the kernel
    // can complete the SYN/ACK handshake before user-space accept() runs, and
    // the very next real request lands as a "Connection reset". Probe with an
    // actual HTTP exchange and retry on transient channel/connect failures.
    val deadline = Monotonic.markNow() + 5.seconds
    newHttpClient().use { probeClient ->
      while (Monotonic.markNow() < deadline) {
        try {
          probeClient.get("http://localhost:$port/__readiness_probe__")
          return port
        } catch (_: java.io.IOException) {
          delay(20.milliseconds)
        } catch (_: ClosedByteChannelException) {
          delay(20.milliseconds)
        }
      }
    }
    error("Embedded server on port $port did not start accepting requests within 5s")
  }

  // Serves [proxy]'s routes on a loopback port the OS picks, for [block], which gets the port and a client.
  private suspend fun <T> withScrapeRoutes(
    proxy: Proxy,
    block: suspend (port: Int, client: HttpClient) -> T,
  ): T {
    val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
      routing {
        val r = this
        with(ProxyHttpRoutes) { r.handleRequests(proxy) }
      }
    }
    return try {
      val port = startServerAndGetPort(server)
      newHttpClient().use { client -> block(port, client) }
    } finally {
      server.stop(0, 0)
    }
  }

  // Sends [target] to the loopback [port] as it is, in a raw request line, rather than through a client, which would
  // encode it, and waits for the whole response.
  private fun rawGet(
    port: Int,
    target: String,
  ) {
    Socket(LOOPBACK_HOST, port).use { socket ->
      socket.soTimeout = 5_000
      val request = "GET $target HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
      socket.getOutputStream().apply {
        write(request.toByteArray(Charsets.ISO_8859_1))
        flush()
      }
      socket.getInputStream().readBytes()
    }
  }

  // Turns on every consumer of a scrape's path on [proxy]: debug and the dashboard, so a scrape writes its /debug line,
  // its dashboard record, and its event, which goes to the returned bus; and the metrics, whose proxy.metrics { }
  // blocks run against the returned mock, so a test can verify the per-path labels.
  private fun recordEverything(proxy: Proxy): Pair<ProxyMetrics, ProxyEventBus> {
    val options = spyk(proxy.options)
    every { options.debugEnabled } returns true
    every { options.dashboardEnabled } returns true
    every { proxy.options } returns options
    val bus = mockk<ProxyEventBus>(relaxed = true)
    every { proxy.eventBus } returns bus
    val metrics = mockk<ProxyMetrics>(relaxed = true)
    // A relaxed counter's labelValues() answers a plain DataPoint, which the route's count can't cast to a counter's; a
    // real counter, never registered, takes the counts instead.
    every { metrics.scrapeRequestCount } returns
      Counter.builder().name("test_requests_total").labelNames("type").build()
    every { proxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers { firstArg<ProxyMetrics.() -> Unit>()(metrics) }
    return metrics to bus
  }

  // Plays [agentContext]'s agent for [count] scrapes, answering each with a small plain-text body, and returns the
  // scrape requests it was sent, in order.
  private fun CoroutineScope.answerScrapes(
    proxy: Proxy,
    agentContext: AgentContext,
    count: Int,
  ): Deferred<List<ScrapeRequest>> =
    async {
      List(count) {
        val wrapper = agentContext.readScrapeRequest()!!
        proxy.scrapeRequestManager.assignScrapeResults(
          ScrapeResults(
            srAgentId = agentContext.agentId,
            srScrapeId = wrapper.scrapeId,
            srValidResponse = true,
            srStatusCode = 200,
            srContentType = "text/plain; charset=utf-8",
            srContentAsText = "metric_value 1.0",
          ),
        ) { true }
        wrapper.scrapeRequest
      }
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

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get(normalizedPath) {
            call.respondText("found")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

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

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get(normalizedPath) {
            call.respondText("found")
          }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/discovery")
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldBe "found"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== ResponseResults Tests ====================

    "ResponseResults should have correct defaults" {
      val results = ResponseResults()

      results.statusCode shouldBe HttpStatusCode.OK
      results.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
      results.contentText shouldBe ""
      results.updateMsgs shouldBe emptyList()
    }

    "ResponseResults should accept custom values" {
      val results = ResponseResults(
        statusCode = HttpStatusCode.ServiceUnavailable,
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
        contentText = """{"error":"proxy stopped"}""",
        updateMsgs = ["proxy_stopped"],
      )

      results.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      results.contentText shouldBe """{"error":"proxy stopped"}"""
      results.updateMsgs shouldBe ["proxy_stopped"]
    }

    "ResponseResults copy should produce modified instance" {
      val results = ResponseResults()

      val modified = results.copy(
        statusCode = HttpStatusCode.NotFound,
        contentText = "modified content",
        updateMsgs = ["modified_msg"],
      )

      modified.statusCode shouldBe HttpStatusCode.NotFound
      modified.contentText shouldBe "modified content"
      modified.updateMsgs shouldBe ["modified_msg"]
    }

    // ==================== recordScrapeOutcome Tests ====================
    // recordScrapeOutcome is the single point where a completed scrape becomes observable: the /debug text line
    // asserted here (written only while debug is on), plus a structured record for the dashboard and an event on the
    // bus (only while the dashboard is on).
    // It formats: "/$path - $updateMsg - $statusCode [reason: [$failureReason]] time: $fetchDuration url: $url"

    "recordScrapeOutcome should format success status without failure reason" {
      val capturedActivity = slot<String>()
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.logActivity(capture(capturedActivity)) } returns Unit

      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.OK,
        updateMsg = "success",
        contentText = "metric 1.0",
        url = "http://localhost:$PROXY_HTTP_PORT/metrics",
        fetchDuration = 150.milliseconds,
      )

      callRecordScrapeOutcome("metrics", response, mockProxy)

      capturedActivity.captured shouldContain "/metrics - success - 200 OK"
      capturedActivity.captured shouldContain "time: 150ms"
      capturedActivity.captured shouldContain "url: http://localhost:$PROXY_HTTP_PORT/metrics"
      // Success should NOT include "reason:" text
      capturedActivity.captured shouldNotContain "reason:"
    }

    "recordScrapeOutcome should include failure reason for non-success status" {
      val capturedActivity = slot<String>()
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.logActivity(capture(capturedActivity)) } returns Unit

      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.NotFound,
        updateMsg = "path_not_found",
        failureReason = "Agent not found for path",
        url = "http://localhost:$PROXY_HTTP_PORT/missing",
        fetchDuration = 50.milliseconds,
      )

      callRecordScrapeOutcome("missing", response, mockProxy)

      capturedActivity.captured shouldContain "/missing - path_not_found - 404 Not Found"
      capturedActivity.captured shouldContain "reason: [Agent not found for path]"
      capturedActivity.captured shouldContain "url: http://localhost:$PROXY_HTTP_PORT/missing"
    }

    "recordScrapeOutcome should include failure reason for ServiceUnavailable" {
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

      callRecordScrapeOutcome("slow-endpoint", response, mockProxy)

      capturedActivity.captured shouldContain "/slow-endpoint - timed_out - 503 Service Unavailable"
      capturedActivity.captured shouldContain "reason:"
    }

    // November #12: /debug's activity queue kept an agent's failure reason and URL whole, bounded only by gRPC's 4 MiB
    // message limit, 50 entries deep. Each is now cut, and escaped, so an entry stays one bounded line (November #16).
    "recordScrapeOutcome should keep a huge failure reason and URL cut, on one line" {
      val capturedActivity = slot<String>()
      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.logActivity(capture(capturedActivity)) } returns Unit
      val response =
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.BadGateway,
          updateMsg = "upstream_error",
          failureReason = "reason\r\nforged " + "r".repeat(1 shl 20),
          url = "http://target/" + "u".repeat(1 shl 20),
          fetchDuration = 5.milliseconds,
        )

      callRecordScrapeOutcome("metrics", response, mockProxy)

      val activity = capturedActivity.captured
      activity shouldContain "reason: [reason\\r\\nforged rrr"
      activity shouldContain "url: http://target/uuu"
      activity.length shouldBeLessThan 10_000
      activity.none { it == '\r' || it == '\n' }.shouldBeTrue()
    }

    // Defense in depth for the scrape port: a browser mustn't sniff a response into a runnable type, and a response
    // opened directly runs in a sandbox with no access to the proxy's origin.
    "scrape port responses should forbid content sniffing and sandbox the page" {
      val proxy = createSpyProxyForRoutes()
      val agentContext = AgentContext("test-remote")
      proxy.pathManager.addPath("test-metrics", "", agentContext)
      agentContext.invalidate()

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/test-metrics")
        response.headers["X-Content-Type-Options"] shouldBe "nosniff"
        response.headers["Content-Security-Policy"] shouldBe "sandbox"

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== handleClientRequests Integration Tests ====================
    // These tests exercise the full request dispatch logic through embedded HTTP servers
    // with ProxyHttpRoutes.handleRequests() installed as the routing handler.

    "handleClientRequests should return ServiceUnavailable when proxy is not running" {
      val proxy = createSpyProxyForRoutes()
      every { proxy.isRunning } returns false

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/metrics")
        response.status shouldBe HttpStatusCode.ServiceUnavailable

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "handleClientRequests should return NotFound for favicon request" {
      val proxy = createSpyProxyForRoutes()

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/favicon.ico")
        response.status shouldBe HttpStatusCode.NotFound

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    "handleClientRequests should return NotFound for unregistered path" {
      val proxy = createSpyProxyForRoutes()

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

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

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

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

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

        val response = client.get("http://localhost:$port/test-metrics")
        response.status shouldBe HttpStatusCode.NotFound

        client.close()
      } finally {
        server.stop(0, 0)
      }
    }

    // Ktor skips empty segments when it matches a route, so "//test-metrics" reaches the one-segment scrape route just
    // as "/test-metrics" does, and the path map found it. But the route dropped only the first slash, so the rest of
    // the scrape saw "/test-metrics": the agent, whose paths are keyed without a slash, was sent it and answered 404;
    // the per-path histograms, which keep series only for a registered key, recorded nothing; and /debug and the
    // dashboard showed the scrape under "//test-metrics". "///test-metrics" missed the path map altogether.
    "a scrape of //path should be sent, recorded, and labeled under the path's registered key" {
      val proxy = createSpyProxyForRoutes()
      val (metrics, bus) = recordEverything(proxy)
      val agentContext = AgentContext("test-remote")
      proxy.pathManager.addPath("test-metrics", "", agentContext)
      val spellings = ["//test-metrics", "///test-metrics"]
      val sent = answerScrapes(proxy, agentContext, spellings.size)

      withScrapeRoutes(proxy) { port, client ->
        spellings.forEach { spelling ->
          val response = client.get("http://localhost:$port$spelling?collect=a")
          withClue(spelling) {
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "metric_value 1.0"
          }
        }
      }

      // The query is passed on as before.
      sent.await().map { it.path to it.encodedQueryParams } shouldBe spellings.map { "test-metrics" to "collect=a" }
      verify(exactly = 2) { metrics.observeLatency("test-metrics", "success", any()) }
      verify(exactly = 2) { metrics.observeResponseBytes("test-metrics", ProxyMetrics.ENCODING_PLAIN, any()) }
      verify(exactly = 2) { proxy.logActivity(match { it.startsWith("/test-metrics - success - 200") }) }
      verify(exactly = 2) { bus.emit(ProxyEvent.ScrapeCompleted(agentContext.agentId, "test-metrics", true)) }
      // The dashboard's record of each, which its path history keys and shows by path.
      verify(exactly = 2) { proxy.recordScrape(match { it.path == "test-metrics" }) }
    }

    // The route's own answers compare the path as well, so they see it without its extra slashes too: "//blitz-test" is
    // the blitz path, which no agent can register, and "//favicon.ico" and an unregistered path are logged with one.
    "the route's own answers should see a //path without its extra slashes" {
      val proxy = createSpyProxyForRoutes(
        "-Dproxy.internal.blitz.enabled=true",
        "-Dproxy.internal.blitz.path=blitz-test",
      )

      withScrapeRoutes(proxy) { port, client ->
        client.get("http://localhost:$port//blitz-test").bodyAsText() shouldBe "42"
        client.get("http://localhost:$port//favicon.ico").status shouldBe HttpStatusCode.NotFound
        client.get("http://localhost:$port//unknown-metrics").status shouldBe HttpStatusCode.NotFound
      }

      verify(exactly = 1) { proxy.logActivity("Invalid path request /favicon.ico") }
      verify(exactly = 1) { proxy.logActivity("Invalid path request /unknown-metrics") }
    }

    // Since the route drops every leading slash (#416), a blitz path configured with one, compared as configured, was
    // answered at no spelling at all, and an agent could register it. ProxyOptions keeps it without its slashes, so the
    // route answers it however the request spells it, and registration reserves it.
    "a blitz path configured with a leading slash should be answered and reserved" {
      val proxy = createSpyProxyForRoutes(
        "-Dproxy.internal.blitz.enabled=true",
        "-Dproxy.internal.blitz.path=/blitz-test",
      )

      withScrapeRoutes(proxy) { port, client ->
        listOf("/blitz-test", "//blitz-test").forEach { spelling ->
          withClue(spelling) {
            val response = client.get("http://localhost:$port$spelling")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe "42"
          }
        }
      }

      listOf("blitz-test", "/blitz-test").forEach { path ->
        withClue(path) {
          proxy.pathManager.addPath(path, "", AgentContext("test-remote"))?.reason.orEmpty() shouldContain "blitz path"
        }
      }
      proxy.pathManager.pathMapSize shouldBe 0
    }

    // The route logged the client's path and query whole and unescaped at DEBUG, before the path map had checked the
    // path. CIO passes control characters other than whitespace in a request target, so any client of the
    // unauthenticated scrape port could forge or flood log lines. Both are now cut and escaped, as every value a client
    // or agent supplies is.
    "the route should log a client's path and query cut and escaped" {
      val proxy = createSpyProxyForRoutes()
      val path = "x" + Char(0x1b) + "[2J" + "p".repeat(1000)
      val query = "q".repeat(1000) + "=1"

      val messages =
        captureLogs<ProxyHttpRoutes>(Level.DEBUG) {
          withScrapeRoutes(proxy) { port, _ -> rawGet(port, "/$path?$query") }
        }.map { it.formattedMessage }
          .filter { "Servicing request" in it && "[2J" in it }

      val message = messages.single()
      message shouldStartWith "Servicing request for path: x\\u001b[2Jppp"
      message shouldContain " with query params qqq"
      message shouldNotContain "p".repeat(100)
      message shouldNotContain "q".repeat(100)
      message.none { it.isISOControl() }.shouldBeTrue()
    }

    "handleServiceDiscoveryEndpoint should return JSON when enabled" {
      val proxy = createSpyProxyForRoutes(
        "--sd_enabled",
        "--sd_path",
        "/test-sd",
        "--sd_target_prefix",
        "http://localhost:$PROXY_HTTP_PORT",
      )

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

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

    "handleServiceDiscoveryEndpoint should not register when sdEnabled is false" {
      val proxy = createSpyProxyForRoutes()
      // SD is disabled by default

      val server = embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          val r = this
          with(ProxyHttpRoutes) { r.handleRequests(proxy) }
        }
      }

      try {
        val port = startServerAndGetPort(server)
        val client = newHttpClient()

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
