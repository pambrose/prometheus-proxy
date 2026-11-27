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

import brave.handler.MutableSpan
import ch.qos.logback.classic.Level
import com.google.protobuf.ByteString
import com.pambrose.common.util.zip
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.common.AgentValues.shownValue
import io.prometheus.common.CountingProxyMetrics
import io.prometheus.common.ScrapeResults
import io.prometheus.common.ScrapeTracing.Companion.FETCH_SPAN
import io.prometheus.common.ScrapeTracing.Companion.FORWARD_SPAN
import io.prometheus.common.ScrapeTracing.Companion.SCRAPE_SPAN
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.common.captureLogs
import io.prometheus.common.collectingScrapeTracing
import io.prometheus.common.proxyOptions
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.chunkData
import io.prometheus.grpc.chunkedScrapeResponse
import io.prometheus.grpc.headerData
import io.prometheus.grpc.registerAgentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

// ScrapeDispatch sends a scrape to a path's agents and merges their answers, for the scrape route and the dashboard's
// scrape-now alike: the request's admission, queueing, timeout, and cleanup, the body's decoding and content type, the
// outcome labels, and the merge of consolidated agents' answers. ProxyHttpRoutesTest covers the route around it, which
// records the scrapes Prometheus makes.
@Suppress("LargeClass")
class ScrapeDispatchTest : StringSpec() {
  private val testProxies: MutableList<Proxy> = []

  private fun createSpyProxyForSubmit(
    timeoutSecs: Int = 1,
    args: List<String> = emptyList(),
  ): Proxy {
    val proxy = spyk(
      Proxy(
        options = proxyOptions(["-Dproxy.internal.scrapeRequestTimeoutSecs=$timeoutSecs"] + args),
        inProcessServerName = "proxy-submit-test-${System.nanoTime()}",
        testMode = true,
      ),
    )
    every { proxy.isRunning } returns true
    testProxies += proxy
    return proxy
  }

  // Runs one successful scrape whose target answered with Content-Type [contentType], and returns the proxy's response.
  private suspend fun submitWithContentType(contentType: String): ScrapeRequestResponse =
    submitPlainResult(contentType = contentType)

  // Runs one scrape whose agent answers with a plain (not gzipped) result of [statusCode], [contentType], and [body],
  // on a proxy started with [args], and returns the proxy's response.
  private suspend fun submitPlainResult(
    statusCode: Int = 200,
    contentType: String = "text/plain; charset=utf-8",
    body: String = "metric_value 1.0",
    args: List<String> = emptyList(),
  ): ScrapeRequestResponse =
    coroutineScope {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30, args = args)
      val agentContext = AgentContext("test-remote")
      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        proxy.scrapeRequestManager.assignScrapeResults(
          ScrapeResults(
            srAgentId = agentContext.agentId,
            srScrapeId = wrapper.scrapeId,
            srValidResponse = true,
            srStatusCode = statusCode,
            srContentType = contentType,
            srContentAsText = body,
            srUrl = "http://target/metrics",
          ),
        ) { true }
      }
      ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))
    }

  // Runs [handler] while [agentContext]'s agent opens a chunked transfer for the scrape it is sent, a header and one
  // chunk, and then keeps its stream open with no summary. Returns what [handler] returned, and how many transfers were
  // open once the chunk was read and once [handler] had returned, both before the stream ends and sweeps what is left.
  private suspend fun <T> withUnfinishedTransfer(
    proxy: Proxy,
    agentContext: AgentContext,
    handler: suspend CoroutineScope.(transferOpen: CompletableDeferred<Unit>) -> T,
  ): Triple<T, Int, Int> =
    coroutineScope {
      val transferOpen = CompletableDeferred<Unit>()
      val handlerDone = CompletableDeferred<Unit>()
      var openDuring = -1
      val stream =
        launch {
          val scrapeId = agentContext.readScrapeRequest()!!.scrapeId
          val data = "metric 1".toByteArray()
          ProxyServiceImpl(proxy).writeChunkedResponsesToProxy(
            flow {
              emit(
                chunkedScrapeResponse {
                  header = headerData {
                    headerValidResponse = true
                    headerScrapeId = scrapeId
                    headerAgentId = agentContext.agentId
                    headerStatusCode = 200
                    headerContentType = "text/plain"
                  }
                },
              )
              emit(
                chunkedScrapeResponse {
                  chunk = chunkData {
                    chunkScrapeId = scrapeId
                    chunkCount = 1
                    chunkByteCount = data.size
                    chunkChecksum = CRC32().apply { update(data) }.value
                    chunkBytes = ByteString.copyFrom(data)
                  }
                },
              )
              openDuring = proxy.agentContextManager.chunkedContextSize
              transferOpen.complete(Unit)
              handlerDone.await()
            },
          )
        }
      val result = handler(transferOpen)
      val openAfter = proxy.agentContextManager.chunkedContextSize
      handlerDone.complete(Unit)
      stream.join()
      Triple(result, openDuring, openAfter)
    }

  // Plays [agentContext]'s agent for one scrape, answering it with a small plain-text body, and returns the scrape
  // request it was sent.
  private fun CoroutineScope.answerOne(
    proxy: Proxy,
    agentContext: AgentContext,
  ): Deferred<ScrapeRequest> =
    async {
      val wrapper = agentContext.readScrapeRequest()!!
      proxy.scrapeRequestManager.assignScrapeResults(plainResults(agentContext, wrapper, "metric 1")) { true }
      wrapper.scrapeRequest
    }

  // A successful plain-text result for [wrapper]'s scrape, as [agentContext]'s agent would send it.
  private fun plainResults(
    agentContext: AgentContext,
    wrapper: ScrapeRequestWrapper,
    body: String,
  ) = ScrapeResults(
    srAgentId = agentContext.agentId,
    srScrapeId = wrapper.scrapeId,
    srValidResponse = true,
    srStatusCode = 200,
    srContentType = "text/plain; charset=utf-8",
    srContentAsText = body,
  )

  init {
    afterTest {
      testProxies.forEach { proxy ->
        proxy.scrapeRequestManager.failAllInFlightScrapeRequests("test cleanup", ProxyFailure.PROXY_STOPPED)
        proxy.agentContextManager.invalidateAllAgentContexts()
      }
      testProxies.clear()
    }

    // ==================== ScrapeRequestResponse Tests ====================

    "ScrapeRequestResponse should store all properties" {
      val response = ScrapeRequestResponse(
        statusCode = HttpStatusCode.OK,
        updateMsg = "success",
        contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
        contentText = """{"metric":"value"}""",
        failureReason = "",
        url = "http://localhost:$PROXY_HTTP_PORT/metrics",
        fetchDuration = 150.milliseconds,
      )

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentText shouldBe """{"metric":"value"}"""
      response.failureReason shouldBe ""
      response.url shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics"
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

    // ==================== authHeaderWithoutTlsWarned Tests ====================
    // The AtomicBoolean ensures the auth-without-TLS warning fires only once.

    "authHeaderWithoutTlsWarned should be a single-fire AtomicBoolean" {
      // Access the private field on the ScrapeDispatch object
      val field = ScrapeDispatch::class.java.getDeclaredField("authHeaderWithoutTlsWarned")
      field.isAccessible = true
      val atomicBoolean = field.get(ScrapeDispatch) as AtomicBoolean

      // Reset it for testing
      atomicBoolean.store(false)

      // First compareAndSet should succeed (false -> true)
      atomicBoolean.compareAndSet(expectedValue = false, newValue = true) shouldBe true

      // Second compareAndSet should fail (already true)
      atomicBoolean.compareAndSet(expectedValue = false, newValue = true) shouldBe false

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

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "agent_disconnected"
    }

    "submitScrapeRequest should return timed_out when scrape request times out" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 1)
      val agentContext = AgentContext("test-remote")

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "timed_out"
    }

    // The dashboard's scrape-now waits less than the proxy's scrape timeout, each agent on its own, so on a
    // consolidated path one slow agent leaves the others' answers standing, as Prometheus would get them.
    "submitScrapeRequest should time out at a timeout it is given rather than the proxy's" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)

      val elapsed =
        measureTime {
          ScrapeDispatch.submitScrapeRequest(
            AgentContext("test-remote"),
            proxy,
            ScrapeSpec("metrics", timeout = 100.milliseconds),
          ).updateMsg shouldBe "timed_out"
        }

      (elapsed < 10.seconds) shouldBe true
    }

    // Beyond maxInFlightScrapeRequests across all agents, a scrape is refused at once with a 503 rather than
    // queued, and the refused request is not left tracked.
    "submitScrapeRequest should return 503 when the proxy's in-flight limit is reached" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 5, args = ["-Dproxy.internal.maxInFlightScrapeRequests=1"])
      val agentContext = AgentContext("test-remote")

      val first =
        async { ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics")) }
      eventually(2.seconds) { proxy.scrapeRequestManager.scrapeMapSize shouldBe 1 }

      val refused = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      refused.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      refused.updateMsg shouldBe "proxy_in_flight_limit"
      proxy.scrapeRequestManager.scrapeMapSize shouldBe 1
      agentContext.invalidate()
      first.await()
    }

    // Each agent's queue is capped at twice scrapeRequestBacklogUnhealthySize; here that is 2.
    "submitScrapeRequest should return 503 when the agent's backlog cap is reached" {
      val proxy =
        createSpyProxyForSubmit(timeoutSecs = 5, args = ["-Dproxy.internal.scrapeRequestBacklogUnhealthySize=1"])
      val agentContext = AgentContext("test-remote")

      val queued =
        List(2) {
          async { ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics")) }
        }
      eventually(2.seconds) { agentContext.scrapeRequestBacklogSize shouldBe 2 }

      val refused = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      refused.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      refused.updateMsg shouldBe "agent_backlog_full"
      proxy.scrapeRequestManager.scrapeMapSize shouldBe 2
      agentContext.invalidate()
      queued.awaitAll()
    }

    "submitScrapeRequest should report agent_disconnected (not timed_out) when the agent disconnects mid-scrape" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")

      launch {
        delay(200.milliseconds)
        agentContext.invalidate()
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      // invalidate() fails the buffered wrapper as the proxy's own AGENT_DISCONNECTED failure. It was reported as a
      // 502 upstream_error, which sent operators to the target instead of the agent connection.
      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "agent_disconnected"
    }

    "submitScrapeRequest should unblock immediately when agent is removed" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")
      proxy.agentContextManager.addAgentContext(agentContext)

      launch {
        delay(200.milliseconds)
        proxy.removeAgentContext(agentContext.agentId, RemovalReason.TERMINATION)
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      // Proxy.removeAgentContext fails the agent's in-flight scrapes as AGENT_DISCONNECTED, the same outcome as a
      // disconnect before the request was queued.
      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "agent_disconnected"
    }

    // A chunked transfer outlived its scrape: the handler's finally stopped tracking the scrape but left its transfer,
    // which an agent keeping the stream open then held for as long as it stayed connected (November #13).
    "submitScrapeRequest should drop its scrape's unfinished chunked transfer when it times out" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 1)
      val agentContext = AgentContext("test-remote")

      val (response, openDuring, openAfter) =
        withUnfinishedTransfer(proxy, agentContext) {
          ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))
        }

      response.updateMsg shouldBe "timed_out"
      openDuring shouldBe 1
      openAfter shouldBe 0
    }

    // The handler's drop of a transfer was logged at DEBUG and counted nowhere, though the stream's sweep and an
    // agent's removal each counted theirs as abandoned. The stream's sweep, when it ends, finds the transfer gone, so
    // it's counted once.
    "submitScrapeRequest should count the unfinished chunked transfer it drops as abandoned, once" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 1)
      val counts = CountingProxyMetrics().also { it.installOn(proxy) }
      val agentContext = AgentContext("test-remote")

      val (response, _, _) =
        withUnfinishedTransfer(proxy, agentContext) {
          ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))
        }

      response.updateMsg shouldBe "timed_out"
      counts.abandonedTransfers.get() shouldBe 1.0
    }

    "submitScrapeRequest should drop its scrape's unfinished chunked transfer when Prometheus hangs up" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")

      val (_, openDuring, openAfter) =
        withUnfinishedTransfer(proxy, agentContext) { transferOpen ->
          val handler =
            async { ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics")) }
          transferOpen.await()
          handler.cancelAndJoin()
        }

      openDuring shouldBe 1
      openAfter shouldBe 0
    }

    "submitScrapeRequest should unblock immediately when proxy is shut down" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")
      proxy.agentContextManager.addAgentContext(agentContext)

      launch {
        delay(200.milliseconds)
        // Simulate what Proxy.shutDown() does, in its order: fail in-flight requests, then invalidate the agents
        proxy.scrapeRequestManager.failAllInFlightScrapeRequests("Proxy is shutting down", ProxyFailure.PROXY_STOPPED)
        proxy.agentContextManager.invalidateAllAgentContexts()
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      // The same outcome as a scrape that arrives once the proxy has stopped.
      response.statusCode shouldBe HttpStatusCode.ServiceUnavailable
      response.updateMsg shouldBe "proxy_stopped"
    }

    // A chunk or summary that fails validation, or a response the proxy can't process, is the proxy's finding about
    // what the agent sent, not a status the target returned.
    "submitScrapeRequest should label a response the proxy could not process invalid_response" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        proxy.scrapeRequestManager.failScrapeRequest(
          wrapper.scrapeId,
          "Chunk validation failed: checksum mismatch",
          ProxyFailure.INVALID_RESPONSE,
        )
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.BadGateway
      response.updateMsg shouldBe "invalid_response"
      response.failureReason shouldContain "checksum mismatch"
    }

    "submitScrapeRequest should return success for valid non-zipped response" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
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
          srUrl = "http://localhost:$PROXY_HTTP_PORT/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results) { true }
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentText shouldBe "metric_value 1.0"
      response.url shouldBe "http://localhost:$PROXY_HTTP_PORT/metrics"
    }

    "submitScrapeRequest should unzip zipped response content" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
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
          srUrl = "http://localhost:$PROXY_HTTP_PORT/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results) { true }
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentText shouldBe originalContent
    }

    "submitScrapeRequest should return BadGateway for invalid gzip content" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        val results = ScrapeResults(
          srAgentId = agentContext.agentId,
          srScrapeId = wrapper.scrapeId,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain; charset=utf-8",
          srZipped = true,
          srContentAsZipped = "not-gzip".toByteArray(),
          srUrl = "http://localhost:$PROXY_HTTP_PORT/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results) { true }
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.BadGateway
      response.updateMsg shouldBe "invalid_gzip"
      response.failureReason.shouldNotBeEmpty()
    }

    "submitScrapeRequest should return PayloadTooLarge for zip bombs" {
      // Set a very small limit for testing via system property
      val proxy = createSpyProxyForSubmit(
        timeoutSecs = 30,
        args = ["-Dproxy.internal.maxUnzippedContentSizeMBytes=0"],
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
          srUrl = "http://localhost:$PROXY_HTTP_PORT/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results) { true }
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.PayloadTooLarge
      response.updateMsg shouldBe "payload_too_large"
      response.failureReason shouldContain "exceeds limit"
    }

    "submitScrapeRequest should fallback to plain text on content type parse error" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
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
          srUrl = "http://localhost:$PROXY_HTTP_PORT/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results) { true }
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.OK
      response.updateMsg shouldBe "success"
      response.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
    }

    // The proxy served whatever Content-Type the target sent from its own origin, so a compromised target (or an agent
    // limited to its own paths) could return text/html with a script that ran on the proxy's origin in an operator's
    // browser, able to read every other path. Only the Prometheus exposition types pass through.
    "submitScrapeRequest should serve a non-exposition content type as text/plain" {
      for (served in ["text/html; charset=utf-8", "application/javascript", "image/svg+xml"]) {
        val response = submitWithContentType(served)

        withClue(served) {
          response.statusCode shouldBe HttpStatusCode.OK
          response.contentType shouldBe ContentType.Text.Plain.withCharset(Charsets.UTF_8)
        }
      }
    }

    // November #12: an unparseable content type was logged whole at WARN on every scrape, and one served as text/plain
    // whole at DEBUG; an agent could make either 4 MiB a scrape, with its CR/LF intact (November #16).
    "a huge agent content type should be logged cut and escaped, whether or not it parses" {
      for (contentType in [
        "bad\r\nforged" + "x".repeat(
          1 shl 20,
        ), "text/html; a=\"\r\nforged" + "x".repeat(1 shl 20),
      ]) {
        val messages =
          captureLogs<ScrapeDispatch>(Level.DEBUG) { submitWithContentType(contentType) }
            .map { it.formattedMessage }
            .filter { "content type" in it }

        withClue(contentType.take(16)) {
          val message = messages.single()
          message.length shouldBeLessThan 10_000
          message shouldContain "\\r\\nforged"
          message.none { it == '\r' || it == '\n' }.shouldBeTrue()
        }
      }
    }

    "submitScrapeRequest should keep the Prometheus exposition content types" {
      for (served in [
        "text/plain; version=0.0.4; charset=utf-8",
        "application/openmetrics-text; version=1.0.0; charset=utf-8",
        "application/vnd.google.protobuf; proto=io.prometheus.client.MetricFamily; encoding=delimited",
      ]) {
        withClue(served) { submitWithContentType(served).contentType shouldBe ContentType.parse(served) }
      }
    }

    // An exposition type passed every parameter through, so an agent could make the scrape's Content-Type header
    // megabytes long, past what Prometheus and a reverse proxy take, or give it a parameter name with CR/LF in it,
    // which Ktor refused only as it wrote the header, answering 500. Only the exposition formats' own parameters are
    // kept, each with a short token for its value.
    "submitScrapeRequest should keep only an exposition type's own parameters, each with a short token value" {
      val served =
        mapOf(
          "text/plain; a=${"x".repeat(1 shl 20)}" to "text/plain",
          "text/plain; a\r\nb=1" to "text/plain",
          "text/plain; version=${"9".repeat(1000)}; charset=utf-8" to "text/plain; charset=utf-8",
          "text/plain; charset=\"utf-8\r\nX-Forged: 1\"" to "text/plain",
          "text/plain; version=0.0.4; version=1.0.0" to "text/plain; version=0.0.4",
          "TEXT/Plain; Version=0.0.4" to "text/plain; version=0.0.4",
          "application/openmetrics-text; version=1.0.0; charset=utf-8; escaping=underscores; extra=1" to
            "application/openmetrics-text; version=1.0.0; charset=utf-8; escaping=underscores",
        )

      served.forEach { (sent, expected) ->
        val contentType = submitWithContentType(sent).contentType

        withClue(sent.take(64)) {
          contentType shouldBe ContentType.parse(expected)
          contentType.toString() shouldBe expected.lowercase()
        }
      }
    }

    // An agent's status went out as it was sent, any 32-bit number: an interim 1xx, which desynchronizes the client's
    // connection, a 204 or 205 with the body after it, or a number that isn't an HTTP status at all.
    "submitScrapeRequest should answer 502 invalid_response for a status the proxy can't serve" {
      for (status in [-1, 0, 99, 100, 101, 103, 204, 205, 304, 600, 99_999]) {
        val response = submitPlainResult(statusCode = status)

        withClue(status) {
          response.statusCode shouldBe HttpStatusCode.BadGateway
          response.updateMsg shouldBe ProxyFailure.INVALID_RESPONSE.label
          response.contentText shouldBe ""
          response.failureReason shouldContain "$status"
          response.url shouldBe "http://target/metrics"
        }
      }
    }

    "submitScrapeRequest should pass a target's other statuses through" {
      for ((status, body) in [200 to "metric_value 1.0", 203 to "metric_value 1.0", 302 to "", 404 to "", 503 to ""]) {
        val response = submitPlainResult(statusCode = status)

        withClue(status) {
          response.statusCode shouldBe HttpStatusCode.fromValue(status)
          response.contentText shouldBe body
        }
      }
    }

    // maxUnzippedContentSizeMBytes bounded only a gzipped body, so a plain one, up to gRPC's 4 MiB message limit,
    // passed any lower limit, including 0, which is to refuse all content.
    "submitScrapeRequest should hold a plain body to maxUnzippedContentSizeMBytes, in bytes" {
      val refused = submitPlainResult(body = "a", args = ["-Dproxy.internal.maxUnzippedContentSizeMBytes=0"])
      val oneMiB = ["-Dproxy.internal.maxUnzippedContentSizeMBytes=1"]
      val atLimit = submitPlainResult(body = "a".repeat(1 shl 20), args = oneMiB)
      // Half a MiB of characters, but a byte past a MiB of UTF-8.
      val pastLimit = submitPlainResult(body = "é".repeat(1 shl 19) + "a", args = oneMiB)

      refused.statusCode shouldBe HttpStatusCode.PayloadTooLarge
      refused.updateMsg shouldBe "payload_too_large"
      refused.failureReason shouldContain "exceeds limit"
      refused.url shouldBe "http://target/metrics"
      atLimit.statusCode shouldBe HttpStatusCode.OK
      atLimit.byteCount shouldBe (1L shl 20)
      pastLimit.statusCode shouldBe HttpStatusCode.PayloadTooLarge
      pastLimit.updateMsg shouldBe "payload_too_large"
    }

    // Finding 10: a non-2xx upstream status must map to a label that reflects the cause, so operators
    // can tell a down target, a timeout, an oversized payload, and a truly unknown path apart -- instead
    // of every failure being labeled path_not_found.
    "Finding 10: upstreamErrorLabel maps status codes to distinct labels" {
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.NotFound) shouldBe "path_not_found"
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.RequestTimeout) shouldBe "upstream_timed_out"
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.GatewayTimeout) shouldBe "upstream_timed_out"
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.PayloadTooLarge) shouldBe "content_too_large"
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.InternalServerError) shouldBe "upstream_error"
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.BadGateway) shouldBe "upstream_error"
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.ServiceUnavailable) shouldBe "upstream_error"
    }

    // The agent's own scrapeTimeoutSecs (default 15) is well below the proxy's
    // scrapeRequestTimeoutSecs (default 90), so a slow target trips the agent first and the upstream
    // leg is the dominant contributor to timeout metrics. Labeling both legs "timed_out" collapsed
    // them into one series even though they have opposite remediations: raise the proxy timeout / fix
    // the agent link, versus raise the agent scrape timeout / fix the slow target.
    "upstreamErrorLabel should not reuse the proxy-side timed_out label for an upstream timeout" {
      val upstream = ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.RequestTimeout)

      upstream shouldNotBe ScrapeDispatch.PROXY_TIMEOUT_LABEL
      upstream shouldBe "upstream_timed_out"
      ScrapeDispatch.upstreamErrorLabel(HttpStatusCode.GatewayTimeout) shouldNotBe
        ScrapeDispatch.PROXY_TIMEOUT_LABEL
    }

    // Prometheus sends its scrape timeout in X-Prometheus-Scrape-Timeout-Seconds as a decimal number of seconds.
    "scrapeTimeoutFromHeader should parse whole and fractional seconds" {
      ScrapeHeaders.scrapeTimeoutFromHeader("10") shouldBe 10.seconds
      ScrapeHeaders.scrapeTimeoutFromHeader(" 2.5 ") shouldBe 2500.milliseconds
    }

    "scrapeTimeoutFromHeader should ignore a missing, malformed, or non-positive value" {
      listOf(null, "", " ", "abc", "0", "-1", "NaN", "Infinity").forEach { value ->
        ScrapeHeaders.scrapeTimeoutFromHeader(value) shouldBe null
      }
    }

    "Finding 10: submitScrapeRequest labels a 500 upstream response upstream_error" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        val results = ScrapeResults(
          srAgentId = agentContext.agentId,
          srScrapeId = wrapper.scrapeId,
          srValidResponse = false,
          srStatusCode = 500,
          srContentType = "text/plain; charset=utf-8",
          srFailureReason = "Internal Server Error",
          srUrl = "http://localhost:$PROXY_HTTP_PORT/metrics",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results) { true }
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.InternalServerError
      response.updateMsg shouldBe "upstream_error"
      response.failureReason shouldBe "Internal Server Error"
      response.contentText shouldBe ""
    }

    "submitScrapeRequest should return path_not_found for a 404 (unregistered path) status" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
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
          srUrl = "http://localhost:$PROXY_HTTP_PORT/missing",
        )
        proxy.scrapeRequestManager.assignScrapeResults(results) { true }
      }

      val response = ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics"))

      response.statusCode shouldBe HttpStatusCode.NotFound
      response.updateMsg shouldBe "path_not_found"
      response.failureReason shouldBe "Endpoint not found"
      response.contentText shouldBe ""
    }

    // ============== mergeResponseResults (consolidated status/contentType selection) ==============

    // One OK + one failure: the merged response is OK, takes the OK result's contentType, and merges
    // the bodies. This selection was previously only exercised by the Docker-gated consolidated test.
    "mergeResponseResults should prefer OK status and the OK result's contentType" {
      val okContentType = ContentType.Application.Json
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "unavailable",
          contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentType = okContentType,
          contentText = "metric_a 1.0",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeResponseResults(results)

      merged.statusCode shouldBe HttpStatusCode.OK
      merged.contentType shouldBe okContentType
      merged.contentText shouldContain "metric_a 1.0"
    }

    // No agent returns OK: the merged status is the first distinct status code (statusCodes[0]).
    "mergeResponseResults should return the first status code when no agent returns OK" {
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "unavailable",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.NotFound,
          updateMsg = "not_found",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeResponseResults(results)

      merged.statusCode shouldBe HttpStatusCode.ServiceUnavailable
    }

    // No agent returns OK: the contentType falls back to the first distinct contentType (contentTypes[0]).
    "mergeResponseResults should fall back to the first contentType when no agent returns OK" {
      val firstContentType = ContentType.Application.Json
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "unavailable",
          contentType = firstContentType,
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.NotFound,
          updateMsg = "not_found",
          contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeResponseResults(results)

      merged.contentType shouldBe firstContentType
    }

    // ==================== Bug #13: Consolidated OpenMetrics EOF Handling Tests ====================

    // Bug #13: When consolidated paths have multiple agents returning OpenMetrics format,
    // each agent's response ends with "# EOF". Naively joining with "\n" produces
    // "# EOF" markers mid-stream, which is invalid OpenMetrics. mergeContentTexts()
    // strips intermediate "# EOF" markers and appends a single one at the end.

    "Bug #13: mergeContentTexts should strip intermediate EOF markers" {
      val results = [
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
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      // Should end with exactly one # EOF
      merged.trimEnd().endsWith("# EOF") shouldBe true
      // Should NOT have # EOF in the middle
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
    }

    "Bug #13: mergeContentTexts should not add EOF when no results have it" {
      val results = [
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
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      merged shouldNotContain "# EOF"
    }

    "Bug #13: mergeContentTexts should return single result unchanged" {
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      // Single result should be returned as-is
      merged shouldBe "metric_a 1.0\n# EOF"
    }

    "Bug #13: mergeContentTexts should handle three agents with EOF" {
      val results = [
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
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      merged shouldContain "metric_c 3.0"
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
      merged.trimEnd().endsWith("# EOF") shouldBe true
    }

    // ==================== Bug #4: Empty Content from Failed Agents ====================

    // Bug #4: mergeContentTexts included empty contentText from failed agents in the
    // join, producing extra newlines (e.g., "metric_a 1.0\n\n"). The fix filters out
    // results with empty contentText before merging.

    "Bug #4: mergeContentTexts should exclude empty content from failed agents" {
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "agent_disconnected",
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldBe "metric_a 1.0\n"
      merged shouldNotContain "\n\n"
    }

    "Bug #4: mergeContentTexts should handle multiple failed agents with one success" {
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "timed_out",
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0\n",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "agent_disconnected",
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldBe "metric_b 2.0\n"
    }

    "Bug #4: mergeContentTexts should return empty string when all agents fail" {
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "timed_out",
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "agent_disconnected",
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldBe ""
    }

    "Bug #4: mergeContentTexts should not add blank lines from empty failed agents" {
      // Without the fix, the empty string from the failed agent would be joined in,
      // producing three consecutive newlines between the two metrics.
      // With the fix, the empty result is filtered out first.
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "agent_disconnected",
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_c 3.0\n",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_c 3.0"
      // The empty agent should not produce a blank line between the two metrics.
      // Without the fix this would be "metric_a 1.0\n\n\nmetric_c 3.0\n" (three newlines).
      // With the fix it's "metric_a 1.0\nmetric_c 3.0": each result is trimmed, then joined by one newline.
      merged shouldNotContain "\n\n\n"
    }

    "Bug #4: mergeContentTexts should handle failed agents with EOF results" {
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.ServiceUnavailable,
          updateMsg = "agent_disconnected",
          contentText = "",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_c 3.0\n# EOF",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_c 3.0"
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
      merged.trimEnd().endsWith("# EOF") shouldBe true
    }

    "Bug #13: mergeContentTexts should handle mixed EOF and non-EOF results" {
      val results = [
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
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      // If any result had EOF, the merged result should end with EOF
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
      merged.trimEnd().endsWith("# EOF") shouldBe true
    }

    // ==================== Bug #14: Trailing Whitespace Inconsistency in mergeContentTexts ====================

    // Bug #14: When merging content from multiple agents, lines ending with "# EOF" had
    // trailing whitespace stripped (via trimEnd()), but lines without "# EOF" preserved
    // the raw text including trailing newlines. When joined with "\n", this produced
    // double-newlines in the merged output — e.g., "metric1\nmetric2\n\n# EOF".
    // The fix uses `trimmed` instead of `text` for the non-EOF branch.

    "Bug #14: mergeContentTexts should not produce double newlines with trailing whitespace" {
      // Agent1 has EOF with trailing newline; Agent2 has trailing newline but no EOF
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF\n",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0\n",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      // Before the fix: "metric_a 1.0\nmetric_b 2.0\n\n# EOF" (double newline)
      // After the fix:  "metric_a 1.0\nmetric_b 2.0\n# EOF" (single newline)
      merged shouldNotContain "\n\n"
      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      merged.trimEnd().endsWith("# EOF") shouldBe true
    }

    "Bug #14: mergeContentTexts should trim trailing whitespace from non-EOF results" {
      val results = [
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
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      // Both results have trailing newlines; they should be trimmed before joining
      merged shouldBe "metric_a 1.0\nmetric_b 2.0"
      merged shouldNotContain "\n\n"
    }

    "Bug #14: mergeContentTexts should handle mixed trailing whitespace with EOF" {
      // Three agents: two with trailing newlines + EOF, one without EOF but with trailing whitespace
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF\n",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0  \n  ",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_c 3.0\n# EOF\n",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      merged shouldContain "metric_a 1.0"
      merged shouldContain "metric_b 2.0"
      merged shouldContain "metric_c 3.0"
      merged shouldNotContain "\n\n"
      val eofCount = merged.split("# EOF").size - 1
      eofCount shouldBe 1
    }

    "Bug #14: mergeContentTexts should produce clean output with no trailing whitespace per entry" {
      // Verify that each entry's trailing whitespace is stripped before joining,
      // regardless of whether the entry has EOF
      val results = [
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_a 1.0\n# EOF\n\n",
          fetchDuration = 10.milliseconds,
        ),
        ScrapeRequestResponse(
          statusCode = HttpStatusCode.OK,
          updateMsg = "success",
          contentText = "metric_b 2.0\n\n\n",
          fetchDuration = 10.milliseconds,
        ),
      ]

      val merged = ScrapeDispatch.mergeContentTexts(results)

      // Both entries have multiple trailing newlines that should be trimmed
      merged shouldBe "metric_a 1.0\nmetric_b 2.0\n# EOF"
    }

    // An agent whose registry is empty, or whose metric filter dropped every family, answers OpenMetrics with "# EOF"
    // alone. Its body was empty once the marker was stripped, and joining it left a blank line, which OpenMetrics
    // forbids, so Prometheus refused the whole consolidated scrape. A body of whitespace alone did the same.
    "mergeContentTexts should leave no blank line for an agent that answered only # EOF, or only whitespace" {
      fun merged(vararg bodies: String) =
        ScrapeDispatch.mergeContentTexts(
          bodies.map {
            ScrapeRequestResponse(
              statusCode = HttpStatusCode.OK,
              updateMsg = "success",
              contentText = it,
              fetchDuration = 10.milliseconds,
            )
          },
        )

      merged("metric_a 1.0\n# EOF\n", "# EOF\n") shouldBe "metric_a 1.0\n# EOF"
      merged("# EOF\n", "metric_b 2.0\n# EOF\n") shouldBe "metric_b 2.0\n# EOF"
      merged("# EOF\n", "# EOF\n") shouldBe "# EOF"
      merged("metric_a 1.0\n", " \n") shouldBe "metric_a 1.0"
      merged(" \n", "\n") shouldBe ""
    }

    // ==================== submitScrapeRequests Tests ====================

    // The scrape route zips the responses with the path's agents to record each agent's outcome, so they must come back
    // in the agents' order, not in the order the agents answered.
    "submitScrapeRequests should answer in the agents' order, whichever agent answers first" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val first = AgentContext("first-remote")
      val second = AgentContext("second-remote")
      val info = ProxyPathManager.AgentContextInfo(isConsolidated = true, agentContexts = [first, second])
      val secondAnswered = CompletableDeferred<Unit>()

      launch {
        val wrapper = first.readScrapeRequest()!!
        secondAnswered.await()
        proxy.scrapeRequestManager.assignScrapeResults(plainResults(first, wrapper, "first_metric 1")) { true }
      }
      launch {
        val wrapper = second.readScrapeRequest()!!
        proxy.scrapeRequestManager.assignScrapeResults(plainResults(second, wrapper, "second_metric 2")) { true }
        secondAnswered.complete(Unit)
      }

      val responses = ScrapeDispatch.submitScrapeRequests(info, proxy, ScrapeSpec("metrics"))

      responses.map { it.contentText } shouldBe ["first_metric 1", "second_metric 2"]
    }

    // The dashboard's scrape-now submits through here, and must leave no trace in what the proxy reports: only the
    // scrape route records a scrape, around this call. So with debug and the dashboard both on, a scrape submitted here
    // writes no /debug line, scrape record, or event, and touches no metric.
    "submitScrapeRequests should record nothing the proxy reports" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val options = spyk(proxy.options)
      every { options.debugEnabled } returns true
      every { options.dashboardEnabled } returns true
      every { proxy.options } returns options
      val bus = mockk<ProxyEventBus>(relaxed = true)
      every { proxy.eventBus } returns bus
      val agentContext = AgentContext("test-remote")
      val info = ProxyPathManager.AgentContextInfo(isConsolidated = false, agentContexts = [agentContext])

      launch {
        val wrapper = agentContext.readScrapeRequest()!!
        proxy.scrapeRequestManager.assignScrapeResults(plainResults(agentContext, wrapper, "metric_value 1.0")) { true }
      }

      val responses = ScrapeDispatch.submitScrapeRequests(info, proxy, ScrapeSpec("metrics"))

      responses.single().updateMsg shouldBe "success"
      verify(exactly = 0) { proxy.logActivity(any()) }
      verify(exactly = 0) { proxy.recordScrape(any()) }
      verify(exactly = 0) { proxy.metrics(any<ProxyMetrics.() -> Unit>()) }
      verify(exactly = 0) { bus.emit(any()) }
    }

    // What a scrape asks for reaches the agent as it was given. So does who asked for it, which the agent leaves out of
    // the scrape metrics it reports to Prometheus when it was the dashboard: false for Prometheus's scrapes, the
    // default and what a proxy predating the field sends, and true for the dashboard's.
    "a scrape request should carry the scrape's query, headers, and origin to the agent" {
      val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
      val agentContext = AgentContext("test-remote")
      val headers = ScrapeHeaders(accept = "text/plain", scrapeTimeout = 2.seconds)
      val origins = [ScrapeOrigin.PROMETHEUS, ScrapeOrigin.DASHBOARD]
      val sent =
        async {
          origins.map {
            val wrapper = agentContext.readScrapeRequest()!!
            proxy.scrapeRequestManager.assignScrapeResults(plainResults(agentContext, wrapper, "metric 1")) { true }
            wrapper.scrapeRequest
          }
        }

      origins.forEach { origin ->
        ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics", "a=1", headers, origin = origin))
          .updateMsg shouldBe "success"
      }

      sent.await().apply {
        map { "${it.path}?${it.encodedQueryParams} ${it.accept}" } shouldBe List(2) { "metrics?a=1 text/plain" }
        map { it.scrapeTimeoutMillis } shouldBe [2_000L, 2_000L]
        map { it.dashboardScrape } shouldBe [false, true]
      }
    }

    // ==================== Forward spans ====================

    // Each agent of a consolidated path gets a forward span of its own under the scrape's, and its request carries that
    // span's trace, so the agent's fetch joins the span of its own request.
    "each agent of a consolidated path should get a forward span under the scrape's, carried in its own request" {
      val spans = CopyOnWriteArrayList<MutableSpan>()
      collectingScrapeTracing(spans).use { tracing ->
        val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
        every { proxy.tracing } returns tracing
        val agents = [AgentContext("first-remote"), AgentContext("second-remote")]
        val info = ProxyPathManager.AgentContextInfo(isConsolidated = true, agentContexts = agents)
        val sent = agents.map { agent -> answerOne(proxy, agent) }
        val scrape = tracing.newTrace(SCRAPE_SPAN)

        ScrapeDispatch.submitScrapeRequests(info, proxy, ScrapeSpec("metrics", trace = scrape.context()))
          .map { it.updateMsg } shouldBe ["success", "success"]

        val forwards = spans.filter { it.name() == FORWARD_SPAN }
        forwards.map { it.traceId() to it.parentId() } shouldBe
          List(2) { scrape.context().traceIdString() to scrape.context().spanIdString() }
        forwards.map { it.tag("agentId") } shouldContainExactlyInAnyOrder agents.map { it.agentId }
        sent.awaitAll().zip(agents).forEach { (request, agent) ->
          val fetch = tracing.joined(request.traceContextMap, FETCH_SPAN).also { it.abandon() }
          fetch.context().parentIdString() shouldBe forwards.single { it.tag("agentId") == agent.agentId }.id()
        }
      }
    }

    // A failed forward is tagged with how it failed, as the scrape counters label it, and as an error, which Zipkin
    // shows in red.
    "a forward span should end as an error tagged with its failure: timed out, or the agent gone" {
      val spans = CopyOnWriteArrayList<MutableSpan>()
      collectingScrapeTracing(spans).use { tracing ->
        val proxy = createSpyProxyForSubmit()
        every { proxy.tracing } returns tracing
        val silent = AgentContext("silent-remote")
        val gone = AgentContext("gone-remote").apply { invalidate() }
        val trace = tracing.newTrace(SCRAPE_SPAN).context()

        listOf(silent, gone).forEach { agent ->
          ScrapeDispatch.submitScrapeRequest(
            agent,
            proxy,
            ScrapeSpec("metrics", timeout = 200.milliseconds, trace = trace),
          )
        }

        spans.map { it.tags().filterKeys { key -> key != "agentId" && key != "agentName" } } shouldBe
          listOf(ScrapeDispatch.PROXY_TIMEOUT_LABEL, ProxyFailure.AGENT_DISCONNECTED.label).map { label ->
            mapOf("outcome" to label, "http.status_code" to "503", "error" to label)
          }
      }
    }

    // A dashboard's scrape-now has no trace, so it records no span, and its request carries no trace to the agent.
    "a scrape that isn't traced should have no forward span, and send no trace to the agent" {
      val spans = CopyOnWriteArrayList<MutableSpan>()
      collectingScrapeTracing(spans).use { tracing ->
        val proxy = createSpyProxyForSubmit(timeoutSecs = 30)
        every { proxy.tracing } returns tracing
        val agent = AgentContext("test-remote")
        val sent = answerOne(proxy, agent)

        ScrapeDispatch.submitScrapeRequest(agent, proxy, ScrapeSpec("metrics", origin = ScrapeOrigin.DASHBOARD))
          .updateMsg shouldBe "success"

        sent.await().traceContextMap.shouldBeEmpty()
        spans.shouldBeEmpty()
      }
    }

    // The agent's name is its own to report, so its forward span shows it cut and escaped, as a log line does: a line
    // break in it can't split what Zipkin shows.
    "a forward span should show the agent's name cut and escaped" {
      val spans = CopyOnWriteArrayList<MutableSpan>()
      collectingScrapeTracing(spans).use { tracing ->
        val proxy = createSpyProxyForSubmit()
        every { proxy.tracing } returns tracing
        val name = "evil\nname-" + "x".repeat(100)
        val agent = AgentContext("test-remote").apply { assignProperties(registerAgentRequest { agentName = name }) }
        val trace = tracing.newTrace(SCRAPE_SPAN).context()

        ScrapeDispatch.submitScrapeRequest(
          agent,
          proxy,
          ScrapeSpec("metrics", timeout = 50.milliseconds, trace = trace),
        )

        spans.single().apply {
          tag("agentName") shouldBe shownValue(name)
          tag("agentName").orEmpty() shouldNotContain "\n"
          tag("agentName").orEmpty() shouldStartWith "evil\\nname-"
          tag("agentId") shouldBe agent.agentId
        }
      }
    }

    // The Authorization header is forwarded to the agent, so over a gRPC connection without TLS the proxy says that
    // credentials may be exposed in transit: once, not on every scrape.
    "an Authorization header forwarded without TLS should be warned about once" {
      val proxy = createSpyProxyForSubmit()
      val agentContext = AgentContext("test-remote")
      agentContext.invalidate()
      val field = ScrapeDispatch::class.java.getDeclaredField("authHeaderWithoutTlsWarned")
      field.isAccessible = true
      val warned = field.get(ScrapeDispatch) as AtomicBoolean
      warned.store(false)

      val warnings =
        captureLogs<ScrapeDispatch>(Level.WARN) {
          repeat(2) {
            val headers = ScrapeHeaders(authorization = "Bearer token")
            ScrapeDispatch.submitScrapeRequest(agentContext, proxy, ScrapeSpec("metrics", headers = headers))
              .updateMsg shouldBe "agent_disconnected"
          }
        }.map { it.formattedMessage }
          .filter { "non-TLS" in it }

      warnings.size shouldBe 1
      warned.store(false)
    }
  }
}
