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

import com.google.common.util.concurrent.Service
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts.ZIPKIN_AGENT_HTTP_PORT
import io.prometheus.common.TestPorts.ZIPKIN_BOTH_HTTP_PORT
import io.prometheus.common.TestPorts.ZIPKIN_PROXY_HTTP_PORT
import io.prometheus.common.TestPorts.ZIPKIN_UNREACHABLE_PORT
import io.prometheus.common.startAndAwaitReady
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.metrics.model.registry.PrometheusRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.seconds
import io.ktor.server.cio.CIO as ServerCIO

// November #7: turning on Zipkin crashed the agent and the proxy while they were built. Each installed its tracing
// interceptor as it built its gRPC channel (agent) or server (proxy), which read zipkinReporterService before
// initServletService had assigned it, so the constructor threw UninitializedPropertyAccessException. Each case builds
// and starts the real proxy and agent, in-process, with Zipkin on, and scrapes through them.
class InProcessZipkinTest : StringSpec() {
  init {
    // Nothing listens on the Zipkin port, and the reporter sends on its own thread, so a collector that can't be
    // reached costs a logged warning, not a failed startup, scrape, or shutdown.
    "a proxy with Zipkin enabled should start, serve a scrape, and stop" {
      scrapeRoundTrip(
        httpPort = ZIPKIN_PROXY_HTTP_PORT,
        proxyArgs = zipkinArgs("proxy", ZIPKIN_UNREACHABLE_PORT),
      )
    }

    "an agent with Zipkin enabled should start, serve a scrape, and stop" {
      scrapeRoundTrip(
        httpPort = ZIPKIN_AGENT_HTTP_PORT,
        agentArgs = zipkinArgs("agent", ZIPKIN_UNREACHABLE_PORT),
      )
    }

    // What the website's Zipkin section describes: each side reports its gRPC spans under its configured serviceName,
    // and the proxy's server span joins the trace the agent's client span started.
    "with Zipkin enabled on both sides, each should report its spans under its configured service name" {
      val collector = SpanCollector()
      val collectorPort = collector.start()
      try {
        scrapeRoundTrip(
          httpPort = ZIPKIN_BOTH_HTTP_PORT,
          proxyArgs = zipkinArgs("proxy", collectorPort, PROXY_SERVICE_NAME),
          agentArgs = zipkinArgs("agent", collectorPort, AGENT_SERVICE_NAME),
        )

        // Stopping each side flushed its reporter, so every span it finished has been posted.
        eventually(10.seconds) {
          val spans = collector.spans()
          val clientSpans = spans.filter { it.serviceName == AGENT_SERVICE_NAME && it.kind == "CLIENT" }
          val serverSpans = spans.filter { it.serviceName == PROXY_SERVICE_NAME && it.kind == "SERVER" }
          clientSpans.shouldNotBeEmpty()
          serverSpans.shouldNotBeEmpty()
          serverSpans.map { it.traceId }.intersect(clientSpans.map { it.traceId }.toSet()).shouldNotBeEmpty()
        }
      } finally {
        collector.stop()
      }
    }
  }

  // Starts a proxy and an agent with [proxyArgs] and [agentArgs], scrapes a target through them, and stops both,
  // checking that each started and then stopped cleanly.
  private suspend fun scrapeRoundTrip(
    httpPort: Int,
    proxyArgs: List<String> = emptyList(),
    agentArgs: List<String> = emptyList(),
  ) {
    PrometheusRegistry.defaultRegistry.clear()

    val serverName = "zipkin-${System.nanoTime()}"
    val target =
      embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          get("/metrics") { call.respondText(METRIC_LINE) }
        }
      }
    val targetPort = target.startAndAwaitReady()
    val client = HttpClient(CIO)
    try {
      // startProxy and startAgent build each one and call startSync(), so a constructor or startup failure throws here.
      val proxy = startProxy(serverName = serverName, proxyPort = httpPort, args = proxyArgs)
      try {
        proxy.isRunning.shouldBeTrue()
        val agent = startAgent(serverName = serverName, args = agentArgs)
        try {
          agent.isRunning.shouldBeTrue()
          agent.awaitInitialConnection(10.seconds).shouldBeTrue()
          agent.pathManager.registerPath(PATH, "http://localhost:$targetPort/metrics")

          eventually(10.seconds) {
            val response = client.get("http://localhost:$httpPort/$PATH")
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain METRIC_LINE.trim()
          }

          agent.stopSync(STOP_TIMEOUT)
          agent.state() shouldBe Service.State.TERMINATED
        } finally {
          if (agent.isRunning) runCatching { agent.stopSync(STOP_TIMEOUT) }
        }

        proxy.stopSync(STOP_TIMEOUT)
        proxy.state() shouldBe Service.State.TERMINATED
      } finally {
        if (proxy.isRunning) runCatching { proxy.stopSync(STOP_TIMEOUT) }
      }
    } finally {
      client.close()
      target.stop(0, 0)
    }
  }

  // Turns on Zipkin for one side ("proxy" or "agent"), reporting to [port] on loopback.
  private fun zipkinArgs(
    side: String,
    port: Int,
    serviceName: String? = null,
  ): List<String> =
    buildList {
      add("-D$side.internal.zipkin.enabled=true")
      add("-D$side.internal.zipkin.hostname=$LOOPBACK_HOST")
      add("-D$side.internal.zipkin.port=$port")
      serviceName?.also { add("-D$side.internal.zipkin.serviceName=$it") }
    }

  // A stand-in Zipkin collector: keeps the spans each reporter posts to the default api/v2/spans path.
  private class SpanCollector {
    private val bodies = CopyOnWriteArrayList<String>()
    private val server =
      embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
        routing {
          post("/api/v2/spans") {
            val bytes = call.receive<ByteArray>()
            // The reporter's OkHttp sender gzips its batches by default.
            val gzipped = call.request.headers[HttpHeaders.ContentEncoding] == "gzip"
            bodies += (if (gzipped) GZIPInputStream(bytes.inputStream()).readBytes() else bytes).decodeToString()
            call.respond(HttpStatusCode.Accepted)
          }
        }
      }

    suspend fun start(): Int = server.startAndAwaitReady()

    fun stop() = server.stop(0, 0)

    fun spans(): List<ReportedSpan> =
      bodies.flatMap { body ->
        Json.parseToJsonElement(body).jsonArray.map { element ->
          val span = element.jsonObject
          ReportedSpan(
            traceId = span.getValue("traceId").jsonPrimitive.content,
            kind = span["kind"]?.jsonPrimitive?.content,
            serviceName = span["localEndpoint"]?.jsonObject?.get("serviceName")?.jsonPrimitive?.content,
          )
        }
      }
  }

  private data class ReportedSpan(
    val traceId: String,
    val kind: String?,
    val serviceName: String?,
  )

  companion object {
    private const val PATH = "zipkin_metrics"
    private const val METRIC_LINE = "zipkin_test_metric 1\n"

    // Zipkin lowercases service names, so these are lowercase to compare as reported.
    private const val PROXY_SERVICE_NAME = "zipkin-test-proxy"
    private const val AGENT_SERVICE_NAME = "zipkin-test-agent"

    private val STOP_TIMEOUT = 10.seconds
  }
}
