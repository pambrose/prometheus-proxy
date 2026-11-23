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

import io.prometheus.common.collectInto
import io.prometheus.common.scrapeTracingHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import io.kotest.assertions.withClue
import io.prometheus.common.MetricBuilders.metricLabel
import io.prometheus.common.CountingAgentMetrics
import ch.qos.logback.classic.Level
import io.kotest.matchers.string.shouldNotContain
import brave.Span
import brave.Tracing
import brave.handler.MutableSpan
import com.pambrose.common.concurrent.await
import com.pambrose.common.service.ZipkinReporterService
import com.pambrose.common.util.zip
import com.pambrose.common.util.hostInfo
import io.grpc.Metadata
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.collections.shouldBeEmpty
import io.grpc.StatusRuntimeException
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import io.prometheus.Agent
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.ScrapeResults
import io.prometheus.common.deadlineExceeded
import io.prometheus.common.Utils.HostPort
import io.prometheus.common.TestPorts.PROMETHEUS_PORT
import io.prometheus.common.TestPorts.PROXY_ADMIN_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.PROXY_HTTP_PORT
import io.prometheus.grpc.ChunkedScrapeResponse
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.grpc.ProxyServiceGrpcKt
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.ScrapeResponse
import io.prometheus.grpc.agentInfo
import io.prometheus.grpc.heartBeatResponse
import io.prometheus.grpc.registerAgentResponse
import io.prometheus.grpc.registerPathResponse
import io.prometheus.grpc.pathNotice
import io.prometheus.grpc.scrapeRequest
import io.prometheus.grpc.unregisterPathResponse
import io.prometheus.common.captureLogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.minutes

@Suppress("LargeClass")
class AgentGrpcServiceTest : StringSpec() {
  private fun createMockAgent(
    proxyHostname: String,
    keepAliveTimeSecs: Long = -1L,
    keepAliveTimeoutSecs: Long = -1L,
    keepAliveWithoutCalls: Boolean = false,
    transportFilterDisabled: Boolean = false,
    activityLog: AgentActivityLog? = null,
  ): Agent {
    val mockOptions = mockk<AgentOptions>(relaxed = true)

    every { mockOptions.proxyHostname } returns proxyHostname
    every { mockOptions.consolidated } returns false
    every { mockOptions.certChainFilePath } returns ""
    every { mockOptions.privateKeyFilePath } returns ""
    every { mockOptions.trustCertCollectionFilePath } returns ""
    every { mockOptions.transportFilterDisabled } returns transportFilterDisabled
    every { mockOptions.chunkContentSizeBytes } returns 32768
    every { mockOptions.keepAliveTimeSecs } returns keepAliveTimeSecs
    every { mockOptions.keepAliveTimeoutSecs } returns keepAliveTimeoutSecs
    every { mockOptions.keepAliveWithoutCalls } returns keepAliveWithoutCalls
    every { mockOptions.unaryDeadlineSecs } returns 30
    every { mockOptions.overrideAuthority } returns ""

    val mockAgent = mockk<Agent>(relaxed = true)
    every { mockAgent.agentId } returns "test-agent-123"
    every { mockAgent.launchId } returns LAUNCH_ID
    every { mockAgent.agentName } returns "test-agent"
    every { mockAgent.options } returns mockOptions
    every { mockAgent.isRunning } returns true
    every { mockAgent.isZipkinEnabled } returns false
    every { mockAgent.proxyHost } returns proxyHostname
    every { mockAgent.activityLog } returns activityLog
    // metrics() is a no-op in tests; invoking the block with a relaxed mock
    // would cause ClassCastException because relaxed Counter mocks don't return Counter.Child
    every { mockAgent.metrics(any<AgentMetrics.() -> Unit>()) } answers {}

    return mockAgent
  }

  // try/finally so a failed assertion still tears the channel down -- a leaked service perturbs the
  // NEXT spec in the file, which turns one failure into a cascade.
  private suspend fun withService(
    proxySpec: String,
    transportFilterDisabled: Boolean = false,
    timeSource: TimeSource = TimeSource.Monotonic,
    activityLog: AgentActivityLog? = null,
    block: suspend (AgentGrpcService) -> Unit,
  ) {
    val agent = createMockAgent(proxySpec, transportFilterDisabled = transportFilterDisabled, activityLog = activityLog)
    val service = AgentGrpcService(agent, agent.options, "test-server", timeSource)
    try {
      block(service)
    } finally {
      service.shutDown()
    }
  }

  // Most specs then want a stubbed unary RPC, which is the same three lines every time. The deadline has
  // to go: with one set, unaryStub() derives a fresh stub per call via withDeadlineAfter(), so the mock
  // installed here would never be consulted.
  private suspend fun withStubbedService(
    proxySpec: String,
    transportFilterDisabled: Boolean = false,
    timeSource: TimeSource = TimeSource.Monotonic,
    activityLog: AgentActivityLog? = null,
    block: suspend (AgentGrpcService, ProxyServiceGrpcKt.ProxyServiceCoroutineStub) -> Unit,
  ) = withService(proxySpec, transportFilterDisabled, timeSource, activityLog) { service ->
    val stub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
    service.grpcStub = stub
    service.unaryDeadlineSecs = 0
    block(service, stub)
  }

  private suspend fun callProcessScrapeResults(
    service: AgentGrpcService,
    agent: Agent,
    connectionContext: AgentConnectionContext,
    nonChunkedChannel: Channel<Outgoing<ScrapeResponse>>,
    chunkedChannel: Channel<Outgoing<ChunkedScrapeResponse>>,
  ) {
    val method = AgentGrpcService::class.declaredMemberFunctions.first { it.name == "processScrapeResults" }
    method.isAccessible = true
    method.callSuspend(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)
  }

  // The next message queued for the proxy, taken as gRPC takes it to send, which counts the result it completes.
  private fun <T> Channel<Outgoing<T>>.taken(agent: Agent): T = tryReceive().getOrThrow().sent(agent)

  // The first message the agent sends on each response stream, with no result to send, after a registration whose
  // answer gives [keepaliveMillis] as the keepalive interval, or null for none in a while.
  private suspend fun firstResponses(keepaliveMillis: Long): Pair<ScrapeResponse?, ChunkedScrapeResponse?> {
    var sent: Pair<ScrapeResponse?, ChunkedScrapeResponse?> = null to null
    withStubbedService("localhost:$PROXY_AGENT_PORT") { service, stub ->
      coEvery { stub.registerAgent(any(), any<Metadata>()) } returns
        registerAgentResponse {
          valid = true
          streamKeepaliveMillis = keepaliveMillis
        }
      val nonChunked = CompletableDeferred<ScrapeResponse>()
      val chunked = CompletableDeferred<ChunkedScrapeResponse>()
      coEvery { stub.writeResponsesToProxy(any(), any()) } coAnswers {
        nonChunked.complete(arg<Flow<ScrapeResponse>>(0).first())
        EMPTY_INSTANCE
      }
      coEvery { stub.writeChunkedResponsesToProxy(any(), any()) } coAnswers {
        chunked.complete(arg<Flow<ChunkedScrapeResponse>>(0).first())
        EMPTY_INSTANCE
      }
      service.registerAgent(CountDownLatch(1))
      coroutineScope {
        val writer =
          launch { service.writeResponsesToProxyUntilDisconnected(service.agent, AgentConnectionContext(128)) }
        sent = withTimeoutOrNull(1.seconds) { nonChunked.await() } to withTimeoutOrNull(1.seconds) { chunked.await() }
        writer.cancel()
      }
    }
    return sent
  }

  init {
    "the proxy endpoint should extract hostname and port correctly" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "localhost"
      service.agentPort shouldBe PROXY_AGENT_PORT

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should use default port when not specified" {
      val agent = createMockAgent("example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe PROXY_AGENT_PORT

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should strip http prefix" {
      val agent = createMockAgent("http://example.com:$PROXY_HTTP_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe PROXY_HTTP_PORT

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should strip https prefix" {
      val agent = createMockAgent("https://example.com:8443")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe 8443

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should handle custom port" {
      val agent = createMockAgent("proxy.example.org:$PROMETHEUS_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "proxy.example.org"
      service.agentPort shouldBe PROMETHEUS_PORT

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should handle IPv4 address" {
      val agent = createMockAgent("192.168.1.100:5000")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "192.168.1.100"
      service.agentPort shouldBe 5000

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should handle hostname without port and default to $PROXY_AGENT_PORT" {
      val agent = createMockAgent("my-proxy-server")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "my-proxy-server"
      service.agentPort shouldBe PROXY_AGENT_PORT

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should strip http prefix and use custom port" {
      val agent = createMockAgent("http://localhost:9999")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "localhost"
      service.agentPort shouldBe 9999

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should strip https prefix and use custom port" {
      val agent = createMockAgent("https://secure.proxy.com:443")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "secure.proxy.com"
      service.agentPort shouldBe 443

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should handle http prefix without port" {
      val agent = createMockAgent("http://example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe PROXY_AGENT_PORT

      // Clean up
      service.shutDown()
    }

    "the proxy endpoint should handle https prefix without port" {
      val agent = createMockAgent("https://example.com")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.agentHostName shouldBe "example.com"
      service.agentPort shouldBe PROXY_AGENT_PORT

      // Clean up
      service.shutDown()
    }

    // These two tests model a counter incremented on the consumer side, with a local counter and channel; they don't
    // touch the agent. The agent counts on the producer side instead: readRequestsFromProxy increments
    // scrapeRequestBacklogSize as it queues a request, and the scrape loop in Agent.processScrapeRequests decrements
    // it in a finally. AgentBacklogDriftTest and AgentConnectionContextLincheckTest cover that accounting.
    "backlog counter should never go negative with consumer-side increment pattern" {
      val channel = Channel<Int>(UNLIMITED)
      val backlogSize = AtomicInt(0)
      val wentNegative = AtomicBoolean(false)
      val itemCount = 10_000

      coroutineScope {
        // Producer: no increment, just send
        launch(Dispatchers.IO) {
          repeat(itemCount) { i ->
            channel.send(i)
          }
          channel.close()
        }

        // Consumer: increment then try/finally decrement
        launch(Dispatchers.IO) {
          for (@Suppress("UnusedPrivateProperty") item in channel) {
            backlogSize.incrementAndFetch()
            try {
              // Simulate processing
            } finally {
              val current = backlogSize.decrementAndFetch()
              if (current < 0) {
                wentNegative.store(true)
              }
            }
          }
        }
      }

      wentNegative.load() shouldBe false
      backlogSize.load() shouldBe 0
    }

    "backlog counter should remain non-negative throughout processing" {
      val channel = Channel<Int>(UNLIMITED)
      val backlogSize = AtomicInt(0)
      val minObserved = AtomicInt(Int.MAX_VALUE)
      val itemCount = 10_000

      coroutineScope {
        // Producer: no increment, just send
        launch(Dispatchers.IO) {
          repeat(itemCount) { i ->
            channel.send(i)
          }
          channel.close()
        }

        // Consumer: increment then try/finally decrement, track minimum
        launch(Dispatchers.IO) {
          for (@Suppress("UnusedPrivateProperty") item in channel) {
            backlogSize.incrementAndFetch()
            try {
              // Simulate processing
            } finally {
              val current = backlogSize.decrementAndFetch()
              minObserved.update { min -> minOf(min, current) }
            }
          }
        }
      }

      minObserved.load() shouldBeGreaterThanOrEqual 0
      backlogSize.load() shouldBe 0
    }

    // ==================== ShutDown Tests ====================

    "shutDown should be safe to call when service has started" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // shutDown should not throw
      service.shutDown()
    }

    "shutDown should be safe to call multiple times" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Calling shutDown multiple times should not throw
      service.shutDown()
      service.shutDown()
    }

    // ==================== gRPC Stub Tests ====================

    "grpcStub should be initialized after construction" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // grpcStub should be accessible (no UninitializedPropertyAccessException)
      service.grpcStub.toString().shouldNotBeEmpty()

      service.shutDown()
    }

    // ==================== Unary Deadline Tests ====================

    "unaryDeadlineSecs should default to 30" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      service.unaryDeadlineSecs shouldBe 30L

      service.shutDown()
    }

    // ==================== connectAgent Tests ====================

    "connectAgent should return true on successful connection" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } returns EMPTY_INSTANCE

        val result = service.connectAgent()

        result.shouldBeTrue()
      }
    }

    "connectAgent should return false on connection failure" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws RuntimeException("Connection refused")

        val result = service.connectAgent()

        result.shouldBeFalse()
      }
    }

    // The agent's dashboard: the latest failure per endpoint, its URLs redacted, until that endpoint connects, and
    // whether the latest attempt failed, which a success on any endpoint clears, so a failover isn't shown as failing.
    "a failed connect should be kept for the dashboard, redacted, and a success should clear the failing flag" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusRuntimeException(Status.UNAVAILABLE.withDescription("via http://ops:s3cret@lb/route"))

        service.connectAgent().shouldBeFalse()
        service.lastConnectFailed.shouldBeTrue()
        service.connectFailuresByEndpoint.values.single().message.apply {
          this shouldContain "UNAVAILABLE"
          this shouldNotContain "s3cret"
        }

        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } returns EMPTY_INSTANCE
        service.connectAgent().shouldBeTrue()
        service.lastConnectFailed.shouldBeFalse()
        service.connectFailuresByEndpoint.keys.shouldBeEmpty()
        service.connects shouldBe 1
      }
    }

    // With the 3s reconnect pause, a proxy outage wrote about 20 ERROR stack traces a minute (October #17).
    "repeated failures to connect to one proxy should log one WARN with the stack trace, and the rest at DEBUG" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusRuntimeException(Status.UNAVAILABLE.withDescription("io exception"))

        val events = captureLogs<AgentGrpcService>(Level.DEBUG) { repeat(5) { service.connectAgent().shouldBeFalse() } }

        events.filter { it.level == Level.ERROR }.shouldBeEmpty()
        val warnings = events.filter { it.level == Level.WARN }
        warnings.map { it.formattedMessage }.single() shouldContain "Cannot connect to proxy"
        warnings.single().throwableProxy.shouldNotBeNull()
        events.count { it.level == Level.INFO && "Connecting to proxy" in it.formattedMessage } shouldBe 1
        events.count { it.level == Level.DEBUG && "Cannot connect to proxy" in it.formattedMessage } shouldBe 4
      }
    }

    "a different connect failure, or one after a connect succeeded, should log a WARN again" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        val unavailable = StatusRuntimeException(Status.UNAVAILABLE.withDescription("io exception"))
        val deadline = StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("too slow"))
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          unavailable andThenThrows unavailable andThenThrows deadline andThen EMPTY_INSTANCE andThenThrows deadline

        val events = captureLogs<AgentGrpcService>(Level.DEBUG) { repeat(5) { service.connectAgent() } }

        events.filter { it.level == Level.WARN }.map { it.formattedMessage }.let { warnings ->
          warnings.size shouldBe 3
          warnings[0] shouldContain "UNAVAILABLE"
          warnings[1] shouldContain "DEADLINE_EXCEEDED"
          warnings[2] shouldContain "DEADLINE_EXCEEDED"
        }
      }
    }

    // Logged once, a failure that won't clear by itself sat in the log's first lines while the agent went on
    // reconnecting every few seconds, each attempt below WARN.
    "a connect failure that persists should be logged at WARN again every 10 minutes, without its stack trace" {
      val time = TestTimeSource()
      withStubbedService("localhost:$PROXY_AGENT_PORT", timeSource = time) { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("not on the list"))

        val events =
          captureLogs<AgentGrpcService>(Level.DEBUG) {
            repeat(3) { service.connectAgent().shouldBeFalse() }
            time += 10.minutes
            repeat(3) { service.connectAgent().shouldBeFalse() }
          }

        val warnings = events.filter { it.level == Level.WARN }
        warnings.size shouldBe 2
        warnings.forEach { it.formattedMessage shouldContain "PERMISSION_DENIED" }
        warnings[0].throwableProxy.shouldNotBeNull()
        warnings[1].throwableProxy.shouldBeNull()
        warnings[1].formattedMessage shouldContain "still failing"
        events.count { it.level == Level.DEBUG && "Cannot connect to proxy" in it.formattedMessage } shouldBe 4
      }
    }

    // A wrong token was a WARN among the connect failures, easy to miss in an app whose metrics never arrived: it's the
    // agent's own setting, so it's an ERROR that names it. The agent keeps retrying, as the proxy's token may change.
    "a connect the proxy refuses for the agent's token should log one ERROR naming the setting, the rest at DEBUG" {
      val time = TestTimeSource()
      withStubbedService("localhost:$PROXY_AGENT_PORT", timeSource = time) { service, mockStub ->
        service.agent.options.also { every { it.agentToken } returns "wrong-token" }
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusException(Status.UNAUTHENTICATED.withDescription("Missing or invalid agent token"))

        val events =
          captureLogs<AgentGrpcService>(Level.DEBUG) {
            repeat(4) { service.connectAgent().shouldBeFalse() }
            time += 10.minutes
            service.connectAgent().shouldBeFalse()
          }

        events.filter { it.level == Level.WARN }.shouldBeEmpty()
        val errors = events.filter { it.level == Level.ERROR }
        errors.map { it.formattedMessage } shouldBe
          [
            TOKEN_REFUSED,
            "$TOKEN_REFUSED (still refused)",
          ]
        errors.forEach { it.throwableProxy.shouldBeNull() }
        events.count { it.level == Level.DEBUG && "Cannot connect to proxy" in it.formattedMessage } shouldBe 3
      }
    }

    "a connect refused for want of a token should say the agent sends none" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        service.agent.options.also { every { it.agentToken } returns "" }
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusException(Status.UNAUTHENTICATED.withDescription("Missing or invalid agent token"))

        val errors =
          captureLogs<AgentGrpcService>(Level.DEBUG) { service.connectAgent().shouldBeFalse() }
            .filter { it.level == Level.ERROR }

        errors.single().formattedMessage shouldBe
          "The proxy at localhost:$PROXY_AGENT_PORT requires an agent token, and the agent sends none " +
          "(StatusException: UNAUTHENTICATED: Missing or invalid agent token). Give the agent a token the proxy " +
          "accepts: $TOKEN_SETTINGS"
      }
    }

    // As a token rotated on the proxy refuses an agent that was connected: the rejection is news again, at ERROR, and
    // in the activity feed once, not once a retry.
    "a refused token should log an ERROR again, and be recorded again, only after a connect since" {
      val log = AgentActivityLog(capacity = 20)
      withStubbedService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT", activityLog = log) { service, mockStub ->
        service.agent.options.also { every { it.agentToken } returns "wrong-token" }
        val refused = StatusException(Status.UNAUTHENTICATED.withDescription("Missing or invalid agent token"))
        val errors =
          captureLogs<AgentGrpcService>(Level.DEBUG) {
            every { service.agent.proxyHost } returns "first:$PROXY_AGENT_PORT"
            coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws refused
            repeat(3) { service.connectAgent().shouldBeFalse() }
            every { service.agent.proxyHost } returns "second:$PROXY_HTTP_PORT"
            coEvery { mockStub.connectAgent(any(), any<Metadata>()) } returns EMPTY_INSTANCE
            service.connectAgent().shouldBeTrue()
            every { service.agent.proxyHost } returns "first:$PROXY_AGENT_PORT"
            coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws refused
            repeat(3) { service.connectAgent().shouldBeFalse() }
          }.filter { it.level == Level.ERROR }

        errors.size shouldBe 2
        errors.forEach { it.formattedMessage shouldContain "refused the agent's token" }
      }

      log.snapshot().entries.map { it.kind } shouldBe
        [AgentActivityKind.CONNECT_FAILED, AgentActivityKind.CONNECTED, AgentActivityKind.CONNECT_FAILED]
    }

    // gRPC's DEADLINE_EXCEEDED description carries the time the call took, so against a proxy that accepts TCP but
    // never answers -- a hung process, an L4 load balancer with no backend -- each attempt failed with a new message,
    // and each logged a WARN with its stack trace (November #9). The full description is still logged.
    "DEADLINE_EXCEEDED connect failures whose descriptions differ only in the elapsed time should log one WARN" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          deadlineExceeded("29.999954000") andThenThrows deadlineExceeded("29.999961000") andThenThrows
          StatusException(Status.UNAVAILABLE.withDescription("io exception"))

        val failures =
          captureLogs<AgentGrpcService>(Level.DEBUG) { repeat(3) { service.connectAgent().shouldBeFalse() } }
            .filter { "Cannot connect to proxy" in it.formattedMessage }

        // A change of status code is still a change.
        failures.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.WARN)
        failures[0].formattedMessage shouldContain "after 29.999954000s"
        failures[1].formattedMessage shouldContain "after 29.999961000s"
      }
    }

    // A failure without a gRPC status is keyed on its class, so a message that differs each time doesn't defeat the
    // dedupe, and another class is still a change.
    "connect failures without a gRPC status should log a WARN only when their class changes" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          IllegalStateException("attempt 1") andThenThrows IllegalStateException("attempt 2") andThenThrows
          IllegalArgumentException("attempt 3")

        val failures =
          captureLogs<AgentGrpcService>(Level.DEBUG) { repeat(3) { service.connectAgent().shouldBeFalse() } }
            .filter { "Cannot connect to proxy" in it.formattedMessage }

        failures.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG, Level.WARN)
      }
    }

    // The same through a real channel, to a port that completes the TCP handshake but never answers HTTP/2, as a hung
    // proxy does: gRPC builds each attempt's description from that attempt's own timings.
    "connects to a proxy that accepts TCP but never answers should log one WARN" {
      ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { blackhole ->
        val agent = createMockAgent("127.0.0.1:${blackhole.localPort}")
        // A network channel rather than the in-process one, so the failure is the one a blackholed proxy gives.
        val service = AgentGrpcService(agent, agent.options, "")
        service.unaryDeadlineSecs = 1
        try {
          val failures =
            captureLogs<AgentGrpcService>(Level.DEBUG) { repeat(2) { service.connectAgent().shouldBeFalse() } }
              .filter { "Cannot connect to proxy" in it.formattedMessage }

          failures.map { it.level } shouldBe listOf(Level.WARN, Level.DEBUG)
          failures.forEach { it.formattedMessage shouldContain "DEADLINE_EXCEEDED" }
        } finally {
          service.shutDown()
        }
      }
    }

    // A dead primary in a failover list failed once every cycle, between connects to the secondary that succeeded.
    "a dead primary in a failover list should log one WARN, though connects to the secondary succeed in between" {
      withStubbedService("primary:1,secondary:1") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusRuntimeException(Status.UNAVAILABLE) andThen EMPTY_INSTANCE andThenThrows
          StatusRuntimeException(Status.UNAVAILABLE) andThen EMPTY_INSTANCE

        val events =
          captureLogs<AgentGrpcService>(Level.DEBUG) {
            listOf("primary:1", "secondary:1", "primary:1", "secondary:1").forEach { host ->
              every { service.agent.proxyHost } returns host
              service.connectAgent()
            }
          }

        events.filter { it.level == Level.WARN }.map { it.formattedMessage }.single() shouldContain "primary:1"
      }
    }

    "connectAgent with transportFilterDisabled should assign agentId" {
      withStubbedService("localhost:$PROXY_AGENT_PORT", transportFilterDisabled = true) { service, mockStub ->
        coEvery { mockStub.connectAgentWithTransportFilterDisabled(any(), any<Metadata>()) } returns agentInfo {
          agentId = "assigned-agent-id"
        }

        val result = service.connectAgent()

        result.shouldBeTrue()
        verify { service.agent.agentId = "assigned-agent-id" }
      }
    }

    // A proxy with the transport filter disabled refuses a connect past proxy.internal.maxAgentsPerIdentity with
    // RESOURCE_EXHAUSTED (November #17). The connect fails like any other, so Agent.run pauses and tries again, rather
    // than the agent stopping.
    "connectAgent with transportFilterDisabled should report a RESOURCE_EXHAUSTED refusal as a failed connect" {
      withStubbedService("localhost:$PROXY_AGENT_PORT", transportFilterDisabled = true) { service, mockStub ->
        coEvery { mockStub.connectAgentWithTransportFilterDisabled(any(), any<Metadata>()) } throws
          StatusException(Status.RESOURCE_EXHAUSTED.withDescription("maxAgentsPerIdentity limit"))

        val events = captureLogs<AgentGrpcService>(Level.DEBUG) { service.connectAgent().shouldBeFalse() }

        verify(exactly = 0) { service.agent.agentId = any() }
        events.single { it.level == Level.WARN }.formattedMessage shouldContain "RESOURCE_EXHAUSTED"
      }
    }

    // ==================== registerAgent Tests ====================

    "registerAgent should send request with correct agent details" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse { valid = true }

        val latch = CountDownLatch(1)
        service.registerAgent(latch)

        coVerify {
          mockStub.registerAgent(
            match { it.agentId == "test-agent-123" && it.launchId == "launch-123" },
            any<Metadata>(),
          )
        }
        latch.count shouldBe 0L
      }
    }

    // What makes the proxy keep notices for the agent, which only an agent that reads them may say.
    "registerAgent should say the agent reads path notices" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse { valid = true }

        service.registerAgent(CountDownLatch(1))

        coVerify { mockStub.registerAgent(match { it.pathNotices }, any<Metadata>()) }
      }
    }

    // What makes the proxy send keepalives on readRequestsFromProxy, which only an agent that ignores them may say.
    "registerAgent should say the agent takes stream keepalives" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse { valid = true }

        service.registerAgent(CountDownLatch(1))

        coVerify { mockStub.registerAgent(match { it.streamKeepalives }, any<Metadata>()) }
      }
    }

    // The proxy sets the interval, so the agent's log is where its operator sees it.
    "registerAgent should log the stream keepalive interval the proxy gives, or that it gives none" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        suspend fun keepaliveLines(millis: Long): List<String> {
          coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse {
            valid = true
            streamKeepaliveMillis = millis
          }
          return captureLogs<AgentGrpcService>(Level.INFO) { service.registerAgent(CountDownLatch(1)) }
            .filter { it.level == Level.INFO && "stream keepalives" in it.formattedMessage }
            .map { it.formattedMessage }
        }

        keepaliveLines(30_000).single() shouldContain "after 30s without a result"
        keepaliveLines(0).single() shouldContain "no stream keepalives"
      }
    }

    // The proxy keeping the stream open: nothing to scrape.
    "readRequestsFromProxy should ignore a keepalive" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, stub ->
        val request = scrapeRequest {
          agentId = "test-agent-123"
          scrapeId = 1L
          path = "/metrics"
        }
        every { stub.readRequestsFromProxy(any(), any()) } returns flowOf(scrapeRequest { keepalive = true }, request)
        val connectionContext = AgentConnectionContext(128)

        service.readRequestsFromProxy(mockk<AgentHttpService>(relaxed = true), connectionContext)

        val channel = connectionContext.scrapeRequestActions()
        channel.tryReceive().isSuccess.shouldBeTrue()
        channel.tryReceive().isSuccess.shouldBeFalse()
      }
    }

    // Between scrapes the response streams carry nothing, so a reverse proxy in front of the proxy, such as nginx with
    // its default 60s timeouts, closed them and ended the agent's connection.
    "the response streams should send a keepalive as often as the proxy says while no result comes" {
      val (nonChunked, chunked) = firstResponses(keepaliveMillis = 50)

      nonChunked.shouldNotBeNull().keepalive.shouldBeTrue()
      chunked.shouldNotBeNull().keepalive.shouldBeTrue()
    }

    // A proxy predating them gives no interval, and would take a keepalive for a result; one with keepalives off gives
    // 0.
    "the response streams should send no keepalive when the proxy gives no interval" {
      firstResponses(keepaliveMillis = 0) shouldBe (null to null)
    }

    "registerAgent should throw RequestFailureException on invalid response" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerAgent(any(), any<Metadata>()) } returns registerAgentResponse {
          valid = false
          reason = "Agent already registered"
        }

        val latch = CountDownLatch(1)
        shouldThrow<RequestFailureException> {
          service.registerAgent(latch)
        }
      }
    }

    // ==================== Channel Termination Tests (M9) ====================

    "shutDown should fully terminate the channel" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Before shutdown, channel should not be terminated
      service.channel.isTerminated.shouldBeFalse()

      service.shutDown()

      // After shutdown with awaitTermination, channel should be fully terminated
      service.channel.isTerminated.shouldBeTrue()
    }

    "resetGrpcStubs should terminate old channel before creating new one" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val oldChannel = service.channel
      oldChannel.isTerminated.shouldBeFalse()

      // resetGrpcStubs should shut down the old channel and create a new one
      service.resetGrpcStubs()

      // Old channel should be fully terminated
      oldChannel.isTerminated.shouldBeTrue()

      // New channel should be a different instance and not terminated
      val newChannel = service.channel
      (newChannel !== oldChannel).shouldBeTrue()
      newChannel.isTerminated.shouldBeFalse()

      service.shutDown()
    }

    // ==================== Terminal shutdown must latch out later channel creation ====================

    // Agent.triggerShutdown() tears down whatever channel exists at that instant, but Guava invokes it
    // exactly once. A run-loop iteration that read `while (isRunning)` as true just before stopAsync()
    // flipped it can then reach resetGrpcStubs() and build a brand-new live channel that nothing will
    // ever break -- leaving run() parked in the idle readRequestsFromProxy collect and reinstating the
    // very deadlock triggerShutdown() exists to prevent. The terminal teardown and resetGrpcStubs() both
    // take grpcLock, so latching the terminal case makes the ordering decisive: whichever runs first, no
    // live channel survives a requested stop.

    "shutDownChannelPermanently prevents resetGrpcStubs from building another channel" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // The Agent.triggerShutdown() path, racing a run-loop iteration that is about to reconnect.
      service.shutDownChannelPermanently()
      val terminatedChannel = service.channel

      service.resetGrpcStubs()

      (service.channel === terminatedChannel).shouldBeTrue()
      service.channel.isShutdown.shouldBeTrue()

      service.shutDown()
    }

    // ==================== Finding 8: Zipkin tracing lifecycle across reconnects ====================

    // Agent.tracing is one-shot. resetGrpcStubs() runs on every reconnect and must NOT close it, or every
    // channel built after the first reconnect installs an interceptor over a closed Tracing and tracing
    // silently dies for the rest of the agent's life. The agent closes it as it stops, after the services
    // that trace; the gRPC service's own shutDown doesn't.
    "Finding 8: tracing survives reconnects and the gRPC service's shutDown" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      every { agent.isZipkinEnabled } returns true
      val tracing = spyk(Tracing.newBuilder().build())
      val zipkinService = mockk<ZipkinReporterService>(relaxed = true)
      every { agent.zipkinReporterService } returns zipkinService
      every { zipkinService.newTracing(any()) } returns tracing
      agent.tracesFromReporter()

      // Construction builds the first channel, and its first call creates `tracing` (November #7). No server answers to
      // the channel's name, so the call fails, but only after the tracing interceptor has seen it.
      val service = AgentGrpcService(agent, agent.options, "test-server")
      shouldThrow<StatusRuntimeException> {
        ProxyServiceGrpc.newBlockingStub(service.channel).connectAgent(EMPTY_INSTANCE)
      }
      verify(exactly = 1) { zipkinService.newTracing(any()) }

      // Each reconnect swaps the channel but must NOT close the one-shot tracing, and nor does stopping.
      repeat(3) { service.resetGrpcStubs() }
      service.shutDown()
      verify(exactly = 0) { tracing.close() }
    }

    // ==================== November #7: Zipkin tracing built on the first call ====================

    // The constructor's resetGrpcStubs() runs before Agent's initServletService assigns zipkinReporterService, and
    // reading it there threw UninitializedPropertyAccessException, so an agent with Zipkin on could not be built. The
    // tracing interceptor now looks the reporter up on the first call. Every channel a reconnect builds must still
    // trace, through the one Tracing.
    "November #7: the channel should be built before zipkinReporterService is assigned and trace once it is" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      every { agent.isZipkinEnabled } returns true
      every { agent.zipkinReporterService } throws
        UninitializedPropertyAccessException("lateinit property zipkinReporterService has not been initialized")
      agent.tracesFromReporter()

      val service = AgentGrpcService(agent, agent.options, "zipkin-${System.nanoTime()}")
      try {
        // What initServletService does after the constructor returns.
        val spans = LinkedBlockingQueue<MutableSpan>()
        val tracing = Tracing.newBuilder().addSpanHandler(collectInto(spans)).build()
        val zipkinService = mockk<ZipkinReporterService>(relaxed = true)
        every { zipkinService.newTracing(any()) } returns tracing
        every { agent.zipkinReporterService } returns zipkinService

        repeat(3) { attempt ->
          if (attempt > 0)
            service.resetGrpcStubs()
          // No server answers to the channel's name, so the call fails, but only after the tracing interceptor has
          // started a span for it.
          shouldThrow<StatusRuntimeException> {
            ProxyServiceGrpc.newBlockingStub(service.channel).connectAgent(EMPTY_INSTANCE)
          }
          spans.poll(5, SECONDS).shouldNotBeNull().kind() shouldBe Span.Kind.CLIENT
        }
        verify(exactly = 1) { zipkinService.newTracing(any()) }
      } finally {
        service.shutDown()
      }
    }

    // ==================== Concurrent shutDown / resetGrpcStubs Tests ====================

    "concurrent shutDown and resetGrpcStubs should not deadlock" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Run resetGrpcStubs and shutDown concurrently on separate threads.
      // Before the fix, resetGrpcStubs held grpcLock and called shutDown(),
      // which also acquired grpcLock. With ReentrantLock the self-call was fine,
      // but a concurrent external shutDown() call would block for the entire
      // duration of channel creation + awaitTermination (up to 5s).
      // After the fix, resetGrpcStubs calls shutDownChannelLocked() under the lock it already holds
      // (no nested lock acquisition), so the lock is held for the minimum
      // necessary time and concurrent callers are not blocked excessively.

      val completedWithinTimeout = AtomicBoolean(false)
      val iterations = 10
      val latch = CountDownLatch(2)

      val thread1 = Thread {
        repeat(iterations) {
          service.resetGrpcStubs()
        }
        latch.countDown()
      }

      val thread2 = Thread {
        repeat(iterations) {
          service.shutDown()
        }
        latch.countDown()
      }

      thread1.start()
      thread2.start()

      // Both threads should complete well within 10 seconds.
      // Before the fix, each resetGrpcStubs iteration could block shutDown
      // for up to 5s (awaitTermination) with the redundant nested lock.
      completedWithinTimeout.store(latch.await(10.seconds))

      completedWithinTimeout.load().shouldBeTrue()

      // Service should still be in a usable state -- either shut down or with a valid channel
      // Final cleanup
      service.shutDown()
    }

    "resetGrpcStubs called multiple times should not deadlock" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Before the fix, each resetGrpcStubs call internally called shutDown(),
      // which redundantly re-acquired grpcLock. While ReentrantLock allowed this,
      // it was unnecessary overhead. After the fix, shutDownChannelLocked() is called
      // under the lock resetGrpcStubs already holds, without the extra acquisition.

      val completedWithinTimeout = AtomicBoolean(false)
      val iterations = 20
      val latch = CountDownLatch(1)

      val thread = Thread {
        repeat(iterations) {
          service.resetGrpcStubs()
        }
        latch.countDown()
      }

      thread.start()
      completedWithinTimeout.store(latch.await(10.seconds))

      completedWithinTimeout.load().shouldBeTrue()
      service.channel.isTerminated.shouldBeFalse()

      service.shutDown()
    }

    "concurrent resetGrpcStubs from multiple threads should not deadlock" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val threadCount = 4
      val iterationsPerThread = 5
      val latch = CountDownLatch(threadCount)

      val threads = (1..threadCount).map {
        Thread {
          repeat(iterationsPerThread) {
            service.resetGrpcStubs()
          }
          latch.countDown()
        }
      }

      threads.forEach { it.start() }

      val completed = latch.await(15.seconds)
      completed.shouldBeTrue()

      // After all concurrent resets, channel should still be usable
      service.channel.isTerminated.shouldBeFalse()
      service.grpcStub.toString().shouldNotBeEmpty()

      service.shutDown()
    }

    // ==================== sendHeartBeat Tests ====================

    "sendHeartBeat should skip when agentId is empty" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        every { service.agent.agentId } returns ""

        val result = service.sendHeartBeat()

        result shouldBe HeartBeatResult.SUCCESS
        coVerify(exactly = 0) { mockStub.sendHeartBeat(any(), any<Metadata>()) }
      }
    }

    "sendHeartBeat should send request when agentId is set" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } returns heartBeatResponse { valid = true }

        val result = service.sendHeartBeat()

        result shouldBe HeartBeatResult.SUCCESS
        coVerify { mockStub.sendHeartBeat(match { it.agentId == "test-agent-123" }, any<Metadata>()) }
      }
    }

    // ==================== reclaimPathOnProxy Tests ====================

    // A reclaim is a registration the proxy refuses while a live agent of another launch serves the path.
    "reclaimPathOnProxy should send the registration as a reclaim, and registerPathOnProxy not" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerPath(any(), any<Metadata>()) } returns registerPathResponse { valid = true }

        service.reclaimPathOnProxy("metrics", "{}", "http://ops:s3cret@t/metrics", "DISCOVERED")
        service.registerPathOnProxy("other", "{}", "http://t/metrics", "STATIC")

        coVerify {
          mockStub.registerPath(
            match {
              it.path == "metrics" && it.reclaim && it.pathSource == "DISCOVERED" && "s3cret" !in it.targetUrl
            },
            any<Metadata>(),
          )
        }
        coVerify { mockStub.registerPath(match { it.path == "other" && !it.reclaim }, any<Metadata>()) }
      }
    }

    "reclaimPathOnProxy should throw the proxy's refusal, with its cause" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerPath(any(), any<Metadata>()) } returns
          registerPathResponse {
            valid = false
            reason = "held"
            rejectionCause = PathRejectionCause.HELD_BY_ANOTHER_AGENT
          }

        val e =
          shouldThrow<RequestFailureException> { service.reclaimPathOnProxy("metrics", "{}", "http://t/m", "STATIC") }

        e.rejectionCause shouldBe PathRejectionCause.HELD_BY_ANOTHER_AGENT
      }
    }

    // ==================== readPathNoticesFromProxy Tests ====================

    "readPathNoticesFromProxy should hand each notice to the path manager" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val pathManager = mockk<AgentPathManager>(relaxed = true)
      every { agent.pathManager } returns pathManager
      val service = AgentGrpcService(agent, agent.options, "test-server")
      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      val notice = pathNotice {
        path = "metrics"
        heldByAgentId = "holder"
      }
      every { mockStub.readPathNoticesFromProxy(any(), any()) } returns flowOf(notice)
      service.grpcStub = mockStub

      try {
        service.readPathNoticesFromProxy()
      } finally {
        service.shutDown()
      }

      verify { pathManager.notePathNotice(notice) }
      verify { mockStub.readPathNoticesFromProxy(match { it.agentId == "test-agent-123" }, any()) }
    }

    // A proxy predating notices has no such method. Ending the task would end the connection, and the agent would
    // reconnect to that proxy without end.
    "readPathNoticesFromProxy should wait out the connection on a proxy that predates it" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        every { mockStub.readPathNoticesFromProxy(any(), any()) } returns
          flow { throw StatusException(Status.UNIMPLEMENTED) }

        withTimeoutOrNull(300.milliseconds) { service.readPathNoticesFromProxy() }.shouldBeNull()
      }
    }

    "readPathNoticesFromProxy should end the connection on any other failure" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        every { mockStub.readPathNoticesFromProxy(any(), any()) } returns
          flow { throw StatusException(Status.UNAVAILABLE) }

        shouldThrow<StatusException> { service.readPathNoticesFromProxy() }.status.code shouldBe Status.Code.UNAVAILABLE
      }
    }

    // ==================== readRequestsFromProxy Tests ====================

    "readRequestsFromProxy should forward scrape requests to connectionContext" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      val request1 = scrapeRequest {
        agentId = "test-agent-123"
        scrapeId = 1L
        path = "/metrics"
      }
      val request2 = scrapeRequest {
        agentId = "test-agent-123"
        scrapeId = 2L
        path = "/health"
      }
      // readRequestsFromProxy is not a suspend function, so use every (not coEvery)
      every { mockStub.readRequestsFromProxy(any(), any()) } returns flowOf(request1, request2)
      service.grpcStub = mockStub

      val connectionContext = AgentConnectionContext(128)
      val mockHttpService = mockk<AgentHttpService>(relaxed = true)

      service.readRequestsFromProxy(mockHttpService, connectionContext)

      // After readRequestsFromProxy completes, items are buffered in the request channel, which holds backlogCapacity
      // (128) of them.
      // Use tryReceive to read them without cancelling the channel.
      val channel = connectionContext.scrapeRequestActions()
      channel.tryReceive().isSuccess.shouldBeTrue()
      channel.tryReceive().isSuccess.shouldBeTrue()
      channel.tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    // A scrape request carries the Authorization header Prometheus sent, so the DEBUG trace of each request must
    // not dump the whole message.
    "readRequestsFromProxy should not log the scrape request's auth header" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")
      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      val request = scrapeRequest {
        agentId = "test-agent-123"
        scrapeId = 7L
        path = "/metrics"
        authHeader = "Bearer s3cr3t-token"
      }
      every { mockStub.readRequestsFromProxy(any(), any()) } returns flowOf(request)
      service.grpcStub = mockStub

      val events =
        try {
          captureLogs<AgentGrpcService>(Level.DEBUG) {
            service.readRequestsFromProxy(mockk<AgentHttpService>(relaxed = true), AgentConnectionContext(128))
          }
        } finally {
          service.shutDown()
        }

      val output = events.joinToString("\n") { it.formattedMessage }
      // Guards against a vacuous pass: the request must actually have been traced.
      output shouldContain "readRequestsFromProxy"
      output shouldNotContain "s3cr3t-token"
    }

    "readRequestsFromProxy should handle empty flow from proxy" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      every { mockStub.readRequestsFromProxy(any(), any()) } returns flowOf()
      service.grpcStub = mockStub

      val connectionContext = AgentConnectionContext(128)
      val mockHttpService = mockk<AgentHttpService>(relaxed = true)

      // Should complete without error
      service.readRequestsFromProxy(mockHttpService, connectionContext)

      // No items should be in the channel
      connectionContext.scrapeRequestActions().tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    // ==================== processScrapeResults Tests ====================
    // processScrapeResults is private, so we test it via Kotlin reflection.
    // It routes ScrapeResults to either nonChunkedChannel or chunkedChannel based on
    // whether the content is zipped and whether the zipped size is at least chunkContentSizeBytes.

    "processScrapeResults should route non-zipped result to nonChunkedChannel" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val connectionContext = AgentConnectionContext(128)
      val nonChunkedChannel = Channel<Outgoing<ScrapeResponse>>(UNLIMITED)
      val chunkedChannel = Channel<Outgoing<ChunkedScrapeResponse>>(UNLIMITED)

      connectionContext.sendScrapeResults(
        ScrapeResults(
          srAgentId = "test-agent-123",
          srScrapeId = 42L,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain",
          srZipped = false,
          srContentAsText = "metric_name 1.0",
        ),
      )
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      // Queued, not yet counted: it counts once gRPC takes it to send.
      verify(exactly = 0) { agent.countScrapeResult(any()) }
      val result = nonChunkedChannel.taken(agent)
      result.scrapeId shouldBe 42L
      result.contentAsText shouldBe "metric_name 1.0"
      result.zipped shouldBe false

      // Chunked channel should be empty
      chunkedChannel.tryReceive().isSuccess.shouldBeFalse()
      // Counted through agent.countScrapeResult; the gRPC service leaves out the result of a scrape the dashboard asked
      // for.
      verify(exactly = 1) { agent.countScrapeResult(AgentMetrics.RESULT_NON_GZIPPED) }

      service.shutDown()
    }

    "processScrapeResults should route zipped small result to nonChunkedChannel" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      // chunkContentSizeBytes defaults to 32768 in mock
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val connectionContext = AgentConnectionContext(128)
      val nonChunkedChannel = Channel<Outgoing<ScrapeResponse>>(UNLIMITED)
      val chunkedChannel = Channel<Outgoing<ChunkedScrapeResponse>>(UNLIMITED)

      val smallContent = "small metric data"
      val zippedContent = smallContent.zip()

      connectionContext.sendScrapeResults(
        ScrapeResults(
          srAgentId = "test-agent-123",
          srScrapeId = 99L,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain",
          srZipped = true,
          srContentAsZipped = zippedContent,
        ),
      )
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      verify(exactly = 0) { agent.countScrapeResult(any()) }
      val result = nonChunkedChannel.taken(agent)
      result.scrapeId shouldBe 99L
      result.zipped shouldBe true

      // Chunked channel should be empty
      chunkedChannel.tryReceive().isSuccess.shouldBeFalse()
      verify(exactly = 1) { agent.countScrapeResult(AgentMetrics.RESULT_GZIPPED) }

      service.shutDown()
    }

    "processScrapeResults should route zipped large result to chunkedChannel with CRC32" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      // Set a very small chunk size so our test data gets chunked
      // Access the mock options directly to avoid MockK chain-mock issues
      val mockOptions = agent.options
      every { mockOptions.chunkContentSizeBytes } returns 10
      val service = AgentGrpcService(agent, mockOptions, "test-server")

      val connectionContext = AgentConnectionContext(128)
      val nonChunkedChannel = Channel<Outgoing<ScrapeResponse>>(UNLIMITED)
      val chunkedChannel = Channel<Outgoing<ChunkedScrapeResponse>>(UNLIMITED)

      val largeContent = "a]".repeat(500)
      val zippedContent = largeContent.zip()
      (zippedContent.size >= 10).shouldBeTrue()

      connectionContext.sendScrapeResults(
        ScrapeResults(
          srAgentId = "test-agent-123",
          srScrapeId = 200L,
          srValidResponse = true,
          srStatusCode = 200,
          srContentType = "text/plain",
          srZipped = true,
          srContentAsZipped = zippedContent,
        ),
      )
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      // Non-chunked channel should be empty
      nonChunkedChannel.tryReceive().isSuccess.shouldBeFalse()

      // Drain chunked channel
      val capturedChunked: MutableList<ChunkedScrapeResponse> = []
      while (true) {
        val item = chunkedChannel.tryReceive()
        if (item.isSuccess) capturedChunked.add(item.getOrThrow().sent(agent)) else break
      }

      // Should have: 1 header + N chunks + 1 summary
      (capturedChunked.size >= 3).shouldBeTrue()

      // First item should be a header
      capturedChunked[0].hasHeader().shouldBeTrue()
      capturedChunked[0].header.headerScrapeId shouldBe 200L
      capturedChunked[0].header.headerStatusCode shouldBe 200

      // Last item should be a summary
      val summary = capturedChunked.last()
      summary.hasSummary().shouldBeTrue()
      summary.summary.summaryScrapeId shouldBe 200L
      summary.summary.summaryByteCount shouldBe zippedContent.size

      // Middle items should be chunks
      val chunks = capturedChunked.drop(1).dropLast(1)
      chunks.forEach { it.hasChunk().shouldBeTrue() }

      // Verify total bytes from chunks equals zipped content size
      val totalBytes = chunks.sumOf { it.chunk.chunkByteCount }
      totalBytes shouldBe zippedContent.size

      // Verify CRC32 checksum in summary matches computed checksum
      val crc = java.util.zip.CRC32()
      chunks.forEach { chunk ->
        crc.update(chunk.chunk.chunkBytes.toByteArray())
      }
      summary.summary.summaryChecksum shouldBe crc.value
      verify(exactly = 1) { agent.countScrapeResult(AgentMetrics.RESULT_CHUNKED) }

      service.shutDown()
    }

    "processScrapeResults should handle multiple results in sequence" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val connectionContext = AgentConnectionContext(128)
      val nonChunkedChannel = Channel<Outgoing<ScrapeResponse>>(UNLIMITED)
      val chunkedChannel = Channel<Outgoing<ChunkedScrapeResponse>>(UNLIMITED)

      repeat(3) { i ->
        connectionContext.sendScrapeResults(
          ScrapeResults(
            srAgentId = "test-agent-123",
            srScrapeId = i.toLong(),
            srValidResponse = true,
            srStatusCode = 200,
            srContentType = "text/plain",
            srZipped = false,
            srContentAsText = "metric_$i 1.0",
          ),
        )
      }
      connectionContext.close()

      callProcessScrapeResults(service, agent, connectionContext, nonChunkedChannel, chunkedChannel)

      val scrapeIds = mutableSetOf<Long>()
      repeat(3) {
        scrapeIds.add(nonChunkedChannel.taken(agent).scrapeId)
      }
      scrapeIds shouldBe setOf(0L, 1L, 2L)

      // No more items
      nonChunkedChannel.tryReceive().isSuccess.shouldBeFalse()
      chunkedChannel.tryReceive().isSuccess.shouldBeFalse()

      service.shutDown()
    }

    // ==================== sendHeartBeat Error Handling Tests ====================

    "sendHeartBeat should return EVICTED when the proxy reports the agent invalid" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } returns heartBeatResponse {
          valid = false
          reason = "Agent not found"
        }

        // An invalid response means the proxy evicted this agent's context. sendHeartBeat classifies it
        // as EVICTED so startHeartBeat() tears the connection down and reconnects, instead of swallowing
        // it into a zombie state (finding 2).
        service.sendHeartBeat() shouldBe HeartBeatResult.EVICTED
      }
    }

    "sendHeartBeat should return SUCCESS when the proxy validates the heartbeat" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } returns heartBeatResponse { valid = true }

        service.sendHeartBeat() shouldBe HeartBeatResult.SUCCESS
      }
    }

    // The heartbeat deadline is passed in rather than inherited from the unary deadline, so a half-open
    // connection is detected within a few heartbeat intervals instead of three 30s unary deadlines.
    "sendHeartBeat should apply the deadline it is given" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        val deadlineStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
        every { mockStub.withDeadlineAfter(5L, SECONDS) } returns deadlineStub
        coEvery { deadlineStub.sendHeartBeat(any(), any<Metadata>()) } returns heartBeatResponse { valid = true }

        service.sendHeartBeat(deadlineSecs = 5L) shouldBe HeartBeatResult.SUCCESS

        verify { mockStub.withDeadlineAfter(5L, SECONDS) }
      }
    }

    "sendHeartBeat should return TRANSIENT_FAILURE on an RPC exception without throwing" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } throws RuntimeException("Network error")

        // A transient RPC failure is classified (not thrown) so the caller can tolerate a bounded number
        // of consecutive failures before forcing a reconnect (finding 2).
        service.sendHeartBeat() shouldBe HeartBeatResult.TRANSIENT_FAILURE
      }
    }

    "registerAgent should throw on empty agentId" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      every { agent.agentId } returns ""
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val latch = CountDownLatch(1)
      shouldThrow<IllegalArgumentException> {
        service.registerAgent(latch)
      }
      service.shutDown()
    }

    // ==================== Empty-argument guards ====================
    // registerAgent has this test above; every other agentId-bearing RPC carries the same require() and
    // must fail the same way, before a request is ever built for the stub.

    val target = "http://target:9100/metrics"
    val emptyArgGuards: List<Triple<String, Boolean, suspend AgentGrpcService.() -> Unit>> =
      [
        Triple("pathMapSize on an empty agentId", true) { pathMapSize() },
        Triple("registerPathOnProxy on an empty agentId", true) {
          registerPathOnProxy("metrics", "{}", target, "STATIC")
        },
        Triple("registerPathOnProxy on an empty path", false) { registerPathOnProxy("", "{}", target, "STATIC") },
        Triple("registerPathOnProxy on a blank path", false) {
          registerPathOnProxy("   ", "{}", target, "STATIC")
        },
        Triple("unregisterPathOnProxy on an empty agentId", true) { unregisterPathOnProxy("metrics") },
        Triple("unregisterPathOnProxy on an empty path", false) { unregisterPathOnProxy("") },
        Triple("unregisterPathOnProxy on a blank path", false) { unregisterPathOnProxy("   ") },
        Triple("readRequestsFromProxy on an empty agentId", true) {
          readRequestsFromProxy(mockk(relaxed = true), AgentConnectionContext(128))
        },
      ]

    emptyArgGuards.forEach { (name, blankAgentId, invocation) ->
      "$name should throw IllegalArgumentException" {
        withService("localhost:$PROXY_AGENT_PORT") { service ->
          if (blankAgentId) every { service.agent.agentId } returns ""
          shouldThrow<IllegalArgumentException> { service.invocation() }
        }
      }
    }

    // ==================== Path RPC responses ====================

    "registerPathOnProxy should send the path's target and source and mark a message sent" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerPath(any(), any<Metadata>()) } returns registerPathResponse { valid = true }

        val response = service.registerPathOnProxy("metrics", "{}", "http://target:9100/metrics", "DISCOVERED")

        response.valid.shouldBeTrue()
        // The target and its origin exist nowhere on the proxy except via this request.
        coVerify {
          mockStub.registerPath(
            match {
              it.agentId == "test-agent-123" &&
                it.path == "metrics" &&
                it.labels == "{}" &&
                it.targetUrl == "http://target:9100/metrics" &&
                it.pathSource == "DISCOVERED"
            },
            any<Metadata>(),
          )
        }
        verify { service.agent.markMsgSent() }
      }
    }

    // The target URL is shown on the proxy's dashboard and /debug page, so credentials in it must not leave
    // the agent.
    "registerPathOnProxy should send the target URL with credentials redacted" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerPath(any(), any<Metadata>()) } returns registerPathResponse { valid = true }

        service.registerPathOnProxy("metrics", "{}", "http://admin:hunter2@target:9100/metrics?token=s3cr3t", "STATIC")

        coVerify {
          mockStub.registerPath(
            match { it.targetUrl == "http://***@target:9100/metrics?token=***" },
            any<Metadata>(),
          )
        }
      }
    }

    "registerPathOnProxy should throw RequestFailureException on invalid response" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerPath(any(), any<Metadata>()) } returns registerPathResponse {
          valid = false
          reason = "not authorized to register path /metrics"
        }

        val exception =
          shouldThrow<RequestFailureException> {
            service.registerPathOnProxy("metrics", "{}", "http://target:9100/metrics", "STATIC")
          }
        exception.message shouldContain "registerPathOnProxy()"
        exception.message shouldContain "not authorized"
        // A proxy predating the field sends no cause.
        exception.rejectionCause shouldBe PathRejectionCause.REJECTION_CAUSE_UNSPECIFIED
      }
    }

    "registerPathOnProxy should carry the response's rejection cause on the RequestFailureException" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.registerPath(any(), any<Metadata>()) } returns registerPathResponse {
          valid = false
          reason = "Path /metrics is served by identity 'team_a'"
          rejectionCause = PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
        }

        shouldThrow<RequestFailureException> {
          service.registerPathOnProxy("metrics", "{}", "http://target:9100/metrics", "STATIC")
        }.rejectionCause shouldBe PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
      }
    }

    "unregisterPathOnProxy should send the path and mark a message sent" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.unregisterPath(any(), any<Metadata>()) } returns unregisterPathResponse { valid = true }

        val response = service.unregisterPathOnProxy("metrics")

        response.valid.shouldBeTrue()
        coVerify {
          mockStub.unregisterPath(match { it.agentId == "test-agent-123" && it.path == "metrics" }, any<Metadata>())
        }
        verify { service.agent.markMsgSent() }
      }
    }

    "unregisterPathOnProxy should throw RequestFailureException on invalid response" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.unregisterPath(any(), any<Metadata>()) } returns unregisterPathResponse {
          valid = false
          reason = "Invalid agentId: test-agent-123 (unregisterPath)"
        }

        val exception = shouldThrow<RequestFailureException> { service.unregisterPathOnProxy("metrics") }
        exception.message shouldContain "unregisterPathOnProxy()"
        exception.message shouldContain "Invalid agentId"
        // The round trip still happened, so the heartbeat clock was still bumped.
        verify { service.agent.markMsgSent() }
      }
    }

    // ==================== connectAgent Error propagation ====================

    "connectAgent should rethrow a JVM Error rather than report a failed connection" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws StackOverflowError("simulated")

        // handleConnectionFailure() has the matching test (Bug #8). Here the Error must not collapse into a
        // routine "could not connect" false, or the run loop would keep retrying on a corrupted JVM.
        shouldThrow<StackOverflowError> { service.connectAgent() }
      }
    }

    // ==================== Backlog and drop accounting ====================

    "readRequestsFromProxy should roll back the backlog increment when the connection context is closed" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        every { mockStub.readRequestsFromProxy(any(), any()) } returns
          flowOf(
            scrapeRequest {
              agentId = "test-agent-123"
              scrapeId = 1L
              path = "/metrics"
            },
          )

        // Closed before the request arrives, as when the connection drops between the proxy sending a
        // request and the agent queueing it.
        val connectionContext = AgentConnectionContext(128).apply { close() }

        shouldThrow<ClosedSendChannelException> {
          service.readRequestsFromProxy(mockk(relaxed = true), connectionContext)
        }
        // The increment for the undeliverable request is reversed, so the gauge does not drift upward.
        verify(exactly = 1) { service.agent.decrementBacklog(1) }
      }
    }

    "processScrapeResults should count a result as dropped when the response channel has closed" {
      withService("localhost:$PROXY_AGENT_PORT") { service ->
        val connectionContext = AgentConnectionContext(128)
        // A sibling writer's closeAll() has already closed the response channels.
        val nonChunkedChannel = AgentGrpcService.outgoingChannel<ScrapeResponse>(service.agent).apply { close() }
        val chunkedChannel = AgentGrpcService.outgoingChannel<ChunkedScrapeResponse>(service.agent)

        connectionContext.sendScrapeResults(
          ScrapeResults(
            srAgentId = "test-agent-123",
            srScrapeId = 42L,
            srValidResponse = true,
            srStatusCode = 200,
            srContentType = "text/plain",
            srZipped = false,
            srContentAsText = "metric_name 1.0",
          ),
        )
        connectionContext.close()

        val thrown =
          shouldThrowAny {
            callProcessScrapeResults(service, service.agent, connectionContext, nonChunkedChannel, chunkedChannel)
          }
        ((thrown as? InvocationTargetException)?.targetException ?: thrown)
          .shouldBeInstanceOf<ClosedSendChannelException>()
        // The only count is the "dropped" one, which the channel makes of the message it couldn't take.
        verify(exactly = 1) { service.agent.countScrapeResult(any()) }
        verify(exactly = 1) { service.agent.countScrapeResult(AgentMetrics.RESULT_DROPPED) }
      }
    }

    // ==================== Keepalive channel options ====================

    "keepalive options should be applied when the channel is built" {
      val agent =
        createMockAgent(
          "localhost:$PROXY_AGENT_PORT",
          keepAliveTimeSecs = 30L,
          keepAliveTimeoutSecs = 10L,
          keepAliveWithoutCalls = true,
        )
      // A network channel rather than the in-process one: the in-process builder accepts the keepalive
      // calls but ignores them, so only a real channel builder can object to the values. Nothing connects
      // until an RPC is made, so no proxy needs to be listening.
      val service = AgentGrpcService(agent, agent.options, "")
      try {
        service.channel.isShutdown.shouldBeFalse()
        service.channel.authority() shouldBe "localhost:$PROXY_AGENT_PORT"
      } finally {
        service.shutDown()
      }
    }

    // ==================== Bug #6: grpcStarted should only be true after channel is initialized ====================

    // Bug #6: Before the fix, resetGrpcStubs() set grpcStarted=true (via an else branch)
    // BEFORE the channel was assigned. If channel() threw on the first call,
    // grpcStarted was true but channel was an uninitialized lateinit var.
    // The next call to resetGrpcStubs() would enter shutDownLocked() (because
    // grpcStarted was true) and crash with UninitializedPropertyAccessException
    // when accessing channel.shutdownNow().
    //
    // The fix moves grpcStarted=true to AFTER channel is successfully assigned.

    "Bug #6: after construction channel and grpcStub should be initialized" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // channel is accessible (no UninitializedPropertyAccessException)
      service.channel.isTerminated.shouldBeFalse()

      // grpcStub is accessible
      service.grpcStub.toString().shouldNotBeEmpty()

      // shutDown accesses channel via shutDownLocked — should succeed
      service.shutDown()
      service.channel.isTerminated.shouldBeTrue()
    }

    "Bug #6: simulated stale grpcStarted=true with null channel should crash" {
      // Demonstrates the old bug: grpcStarted=true but channel not initialized.
      // If the `else grpcStarted = true` line had run but channel() threw,
      // calling shutDown() would access the uninitialized lateinit channel.
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Use reflection to null-out the channel, simulating an uninitialized lateinit.
      // Using ::channel property reference for compile-time safety: if the field is
      // renamed, this line will fail to compile instead of failing at runtime.
      val channelField = AgentGrpcService::class.java.getDeclaredField(AgentGrpcService::channel.name)
      channelField.isAccessible = true
      channelField.set(service, null) // makes lateinit "uninitialized"

      // grpcStarted is true (from the successful init), so shutDownLocked()
      // will try to access channel.shutdownNow() → UninitializedPropertyAccessException
      shouldThrow<UninitializedPropertyAccessException> {
        service.shutDown()
      }
    }

    "Bug #6: resetGrpcStubs should recover after shutDown" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Shut down the service
      service.shutDown()
      service.channel.isTerminated.shouldBeTrue()

      // resetGrpcStubs should create a new channel and restore the service
      service.resetGrpcStubs()

      service.channel.isTerminated.shouldBeFalse()
      service.grpcStub.toString().shouldNotBeEmpty()

      service.shutDown()
    }

    // ==================== Bug #6 (channel close): Producer-side close is sufficient ====================

    // Bug #6 (channel close): The outer finally block in writeResponsesToProxyUntilDisconnected
    // redundantly closed both channels after the inner finally (producer) had already closed them.
    // While Channel.close() is idempotent, the redundancy obscured which code path owned
    // channel cleanup. The fix removes the outer finally: the producer's finally closes the channels when it ends,
    // and runStreamTask's closeAll() closes them when a stream fails. These tests verify the producer-close pattern
    // is sufficient, with consumeAsFlow consumers of their own.

    "Bug #6 (channel close): producer-side close should terminate consumeAsFlow consumers" {
      // Mirrors the producer in writeResponsesToProxyUntilDisconnected, which closes the channels in its finally
      // block. The real writers read through streamOf, which ends, as consumeAsFlow() does here, once the channel is
      // closed and empty.
      val channel = Channel<Int>(UNLIMITED)
      val consumed: MutableList<Int> = []
      val consumerCompleted = AtomicBoolean(false)

      coroutineScope {
        // Producer: sends items then closes in finally
        launch(Dispatchers.IO) {
          try {
            channel.send(1)
            channel.send(2)
            channel.send(3)
          } finally {
            channel.close()
          }
        }

        // Consumer: consumes via flow
        launch(Dispatchers.IO) {
          channel.consumeAsFlow().collect { consumed.add(it) }
          consumerCompleted.store(true)
        }
      }

      consumed shouldBe [1, 2, 3]
      consumerCompleted.load().shouldBeTrue()
    }

    "Bug #6 (channel close): producer-side close on error should still terminate consumers" {
      // When the producer throws, the finally block still closes the channel,
      // allowing consumers to drain remaining items and complete.
      val channel = Channel<Int>(UNLIMITED)
      val consumed: MutableList<Int> = []
      val consumerCompleted = AtomicBoolean(false)

      coroutineScope {
        launch(Dispatchers.IO) {
          try {
            channel.send(10)
            channel.send(20)
            // Simulate producer error (e.g., processScrapeResults throws)
            error("simulated producer error")
          } catch (e: IllegalStateException) {
            // Error handled (mirrors runCatchingCancellable + onFailure): a cancellation, an IllegalStateException too,
            // is passed on.
            if (e is CancellationException) throw e
          } finally {
            channel.close()
          }
        }

        launch(Dispatchers.IO) {
          channel.consumeAsFlow().collect { consumed.add(it) }
          consumerCompleted.store(true)
        }
      }

      consumed shouldBe [10, 20]
      consumerCompleted.load().shouldBeTrue()
    }

    "Bug #6 (channel close): writeResponsesToProxyUntilDisconnected should complete with closed connectionContext" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      // Stub the streaming calls to return at once, without reading the flow
      coEvery { mockStub.writeResponsesToProxy(any()) } returns EMPTY_INSTANCE
      coEvery { mockStub.writeChunkedResponsesToProxy(any()) } returns EMPTY_INSTANCE
      service.grpcStub = mockStub

      val connectionContext = AgentConnectionContext(128)
      // Close the connectionContext immediately so processScrapeResults exits,
      // triggering the inner finally to close both channels.
      connectionContext.close()

      // Should complete without hanging — the inner finally is sufficient
      service.writeResponsesToProxyUntilDisconnected(agent, connectionContext)

      // Verify the streaming calls were invoked (consumers received the flows)
      coVerify { mockStub.writeResponsesToProxy(any(), any()) }
      coVerify { mockStub.writeChunkedResponsesToProxy(any(), any()) }

      service.shutDown()
    }

    "writeResponsesToProxyUntilDisconnected should close streams when a writer fails" {
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>(relaxed = true)
      coEvery { mockStub.writeResponsesToProxy(any(), any()) } throws IllegalStateException("boom")
      coEvery { mockStub.writeChunkedResponsesToProxy(any(), any()) } answers {
        runBlocking {
          arg<kotlinx.coroutines.flow.Flow<ChunkedScrapeResponse>>(0).collect()
        }
        EMPTY_INSTANCE
      }
      service.grpcStub = mockStub

      val connectionContext = AgentConnectionContext(128)

      shouldNotThrow<Throwable> {
        coroutineScope {
          val job = launch { service.writeResponsesToProxyUntilDisconnected(agent, connectionContext) }
          withTimeout(5.seconds) {
            while (connectionContext.connected) {
              delay(10.milliseconds)
            }
          }
          job.cancel()
          job.join()
        }
      }

      service.shutDown()
    }

    "Bug #6: grpcStarted should be true, with the channel initialized, after construction" {
      // Verify the invariant: grpcStarted=true implies channel is initialized.
      // We use reflection to read the grpcStarted delegate and verify
      // it was set correctly after construction.
      val agent = createMockAgent("localhost:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      // Read grpcStarted via the delegate field
      val delegateField = service.javaClass.getDeclaredField($$"grpcStarted$delegate")
      delegateField.isAccessible = true
      val delegate = delegateField.get(service)

      // The delegate wraps a java.util.concurrent.atomic.AtomicBoolean in a field named "atomicVal"
      // (the AtomicDelegates library uses the Java type), so this interop cast stays on the Java atomic
      // and keeps .get() even though the rest of the file now uses kotlin.concurrent.atomics.
      val atomicValField = delegate.javaClass.getDeclaredField("atomicVal")
      atomicValField.isAccessible = true
      val atomicBool = atomicValField.get(delegate) as java.util.concurrent.atomic.AtomicBoolean

      // After successful construction, grpcStarted should be true
      atomicBool.get().shouldBeTrue()

      // And channel should be initialized
      service.channel.isTerminated.shouldBeFalse()

      service.shutDown()
    }

    // ==================== registerAgent Request Contents ====================

    // The proxy stores this on AgentContext and publishes it as the reserved `hostName`
    // service-discovery label, documented as "hostnames of agents serving this path" -- and its own
    // tests (AgentContextTest, ProxyTest) are written against that meaning. Sending agentHostName
    // instead would report the PROXY endpoint: identical across every target, and with failover it
    // would change on rotation, altering Prometheus target identity and churning the series.
    "registerAgent should report the agent's own host, not the proxy endpoint" {
      val agent = createMockAgent("proxy-endpoint-host:$PROXY_AGENT_PORT")
      val service = AgentGrpcService(agent, agent.options, "test-server")

      try {
        val mockStub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>()
        val request = slot<RegisterAgentRequest>()
        coEvery { mockStub.registerAgent(capture(request), any()) } returns registerAgentResponse { valid = true }

        service.grpcStub = mockStub
        // Bypass withDeadlineAfter so unaryStub() hands back the mock itself.
        service.unaryDeadlineSecs = 0

        service.registerAgent(CountDownLatch(1))

        request.captured.hostName shouldBe hostInfo.hostName
        request.captured.hostName shouldNotBe "proxy-endpoint-host"
        request.captured.hostName shouldNotBe service.agentHostName
      } finally {
        service.shutDown()
      }
    }

    // ==================== Activity Feed ====================

    // The feed records what the log says at WARN or INFO: a connect, and a failure when it is new or changes, not
    // each repeat of one, which would fill the feed through a long outage.
    "connectAgent should record a connect, and a failure only when it is new or changes" {
      val log = AgentActivityLog(capacity = 20)
      withStubbedService("localhost:$PROXY_AGENT_PORT", activityLog = log) { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusException(Status.UNAVAILABLE.withDescription("proxy down"))
        repeat(3) { service.connectAgent().shouldBeFalse() }
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusException(Status.UNAUTHENTICATED.withDescription("bad token"))
        service.connectAgent().shouldBeFalse()
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } returns EMPTY_INSTANCE
        service.connectAgent().shouldBeTrue()
      }

      log.snapshot().entries.map { it.kind to it.detail }.apply {
        this shouldBe
          [
            AgentActivityKind.CONNECTED to "localhost:$PROXY_AGENT_PORT",
            AgentActivityKind.CONNECT_FAILED to
              "localhost:$PROXY_AGENT_PORT: StatusException: UNAUTHENTICATED: bad token",
            AgentActivityKind.CONNECT_FAILED to
              "localhost:$PROXY_AGENT_PORT: StatusException: UNAVAILABLE: proxy down",
          ]
      }
    }

    // nginx answering with a 504 gives a status whose description carries its whole HTML error page: the feed and the
    // Connection view show its first line, and the log has the rest.
    "a connect failure should be shown as the first line of its status" {
      val log = AgentActivityLog(capacity = 20)
      withStubbedService("localhost:$PROXY_AGENT_PORT", activityLog = log) { service, mockStub ->
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws
          StatusException(
            Status.UNAVAILABLE.withDescription(
              "HTTP status code 504\ninvalid content-type: text/html\n<html>\r\n<title>504 Gateway Time-out</title>",
            ),
          )
        service.connectAgent().shouldBeFalse()

        service.connectFailuresByEndpoint.values.single().message shouldBe
          "StatusException: UNAVAILABLE: HTTP status code 504"
      }

      log.snapshot().entries.single().detail shouldBe
        "localhost:$PROXY_AGENT_PORT: StatusException: UNAVAILABLE: HTTP status code 504"
    }

    // A failure the endpoint still holds from before is news again once the agent has connected elsewhere since: after
    // a failback to a primary that is still down, the feed says why it failed this time.
    "a failure should be recorded again after a connect elsewhere, though its endpoint never connected" {
      val log = AgentActivityLog(capacity = 20)
      withStubbedService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT", activityLog = log) { service, mockStub ->
        val down = StatusException(Status.UNAVAILABLE.withDescription("proxy down"))
        every { service.agent.proxyHost } returns "first:$PROXY_AGENT_PORT"
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws down
        service.connectAgent().shouldBeFalse()
        every { service.agent.proxyHost } returns "second:$PROXY_HTTP_PORT"
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } returns EMPTY_INSTANCE
        service.connectAgent().shouldBeTrue()
        every { service.agent.proxyHost } returns "first:$PROXY_AGENT_PORT"
        coEvery { mockStub.connectAgent(any(), any<Metadata>()) } throws down
        repeat(2) { service.connectAgent().shouldBeFalse() }
      }

      log.snapshot().entries.map { it.kind } shouldBe
        [AgentActivityKind.CONNECT_FAILED, AgentActivityKind.CONNECTED, AgentActivityKind.CONNECT_FAILED]
    }

    // Leaving the primary is the news; cycling on through the backups during the same outage is not.
    "failing over from the primary, and back to it, should be recorded, and cycling through the backups not" {
      val log = AgentActivityLog(capacity = 20)
      withService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT,third:$PROXY_ADMIN_PORT", activityLog = log) {
        it.advanceEndpoint()
        it.advanceEndpoint()
        it.resetEndpoint()
        it.resetEndpoint()
      }

      log.snapshot().entries.map { it.kind to it.detail } shouldBe
        [
          AgentActivityKind.FAILED_BACK to "to first:$PROXY_AGENT_PORT",
          AgentActivityKind.FAILED_OVER to "from first:$PROXY_AGENT_PORT to second:$PROXY_HTTP_PORT",
        ]
    }

    // ==================== Failover Endpoint Rotation ====================

    // The whole feature must be inert for a single-endpoint agent -- i.e. every deployment that existed
    // before failover. advanceEndpoint() returns false, which is what tells EndpointFailover.beforeAttempt not to
    // rebuild the channel for a new endpoint, so the retry path stays byte-for-byte what it was.
    "a single endpoint should report no failover and refuse to rotate" {
      withService("localhost:$PROXY_AGENT_PORT") { service ->
        service.failoverEndpointsDesc.shouldBeNull()
        service.advanceEndpoint().shouldBeFalse()
        service.currentEndpoint shouldBe HostPort("localhost", PROXY_AGENT_PORT)

        // Failback on a single endpoint is a no-op rather than an error.
        service.resetEndpoint()
        service.currentEndpoint shouldBe HostPort("localhost", PROXY_AGENT_PORT)
      }
    }

    "a comma-separated list should select the first endpoint and report failover available" {
      withService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT") { service ->
        service.currentEndpoint shouldBe HostPort("first", PROXY_AGENT_PORT)
        service.failoverEndpointsDesc shouldBe "first:$PROXY_AGENT_PORT, second:$PROXY_HTTP_PORT"
      }
    }

    "advanceEndpoint should move to the next endpoint and wrap around" {
      withService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT") { service ->
        service.advanceEndpoint().shouldBeTrue()
        service.currentEndpoint shouldBe HostPort("second", PROXY_HTTP_PORT)

        service.advanceEndpoint().shouldBeTrue()
        service.currentEndpoint shouldBe HostPort("first", PROXY_AGENT_PORT)
      }
    }

    // resetEndpoint is the entire failback mechanism: without it the agent would stay on whichever
    // endpoint answered last and never re-probe a recovered primary.
    "resetEndpoint should return to the primary" {
      withService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT") { service ->
        service.advanceEndpoint().shouldBeTrue()
        service.currentEndpoint shouldBe HostPort("second", PROXY_HTTP_PORT)

        service.resetEndpoint()
        service.currentEndpoint shouldBe HostPort("first", PROXY_AGENT_PORT)
      }
    }

    // Rotation must never resurrect a channel after a requested stop. advanceEndpoint moves the cursor,
    // but only resetGrpcStubs builds channels, and its shutDownRequested latch has to keep winning --
    // bypassing it would reinstate the run-loop deadlock that latch was added to fix.
    "rotation after a permanent shutdown must not build another channel" {
      withService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT") { service ->
        val originalChannel = service.channel

        service.shutDownChannelPermanently()
        originalChannel.isShutdown.shouldBeTrue()

        service.advanceEndpoint().shouldBeTrue()
        service.resetGrpcStubs()

        // Same terminated channel: no new one was created for the rotated-to endpoint.
        (service.channel === originalChannel).shouldBeTrue()
        service.channel.isShutdown.shouldBeTrue()
      }
    }
    // ==================== what the agent counts ====================

    "advanceEndpoint should count each move to the next endpoint, and a single endpoint none" {
      withService("first:$PROXY_AGENT_PORT,second:$PROXY_HTTP_PORT") { service ->
        val counts = CountingAgentMetrics().also { it.installOn(service.agent) }

        repeat(3) { service.advanceEndpoint().shouldBeTrue() }

        counts.endpointAdvances.labelValues(LAUNCH_ID).get() shouldBe 3.0
      }
      withService("localhost:$PROXY_AGENT_PORT") { service ->
        val counts = CountingAgentMetrics().also { it.installOn(service.agent) }

        service.advanceEndpoint().shouldBeFalse()

        counts.endpointAdvances.collect().dataPoints.size shouldBe 0
      }
    }

    "each heartbeat sent should count by its outcome" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        val counts = CountingAgentMetrics().also { it.installOn(service.agent) }
        coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } returnsMany
          listOf(heartBeatResponse { valid = true }, heartBeatResponse { valid = false })

        service.sendHeartBeat()
        service.sendHeartBeat()
        coEvery { mockStub.sendHeartBeat(any(), any<Metadata>()) } throws StatusException(Status.UNAVAILABLE)
        service.sendHeartBeat()

        HeartBeatResult.entries.associate {
          it to counts.heartbeats.labelValues(LAUNCH_ID, it.metricLabel).get()
        } shouldBe
          mapOf(
            HeartBeatResult.SUCCESS to 1.0,
            HeartBeatResult.EVICTED to 1.0,
            HeartBeatResult.TRANSIENT_FAILURE to 1.0,
          )
      }
    }

    // A heartbeat with no agentId isn't sent, so it isn't one.
    "a heartbeat skipped for an empty agentId should not count" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, _ ->
        val counts = CountingAgentMetrics().also { it.installOn(service.agent) }
        every { service.agent.agentId } returns ""

        service.sendHeartBeat() shouldBe HeartBeatResult.SUCCESS

        counts.heartbeats.collect().dataPoints.size shouldBe 0
      }
    }

    "a scrape request should record how long it waited to start, unless a dashboard asked for it" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        val counts = CountingAgentMetrics().also { it.installOn(service.agent) }
        every { mockStub.readRequestsFromProxy(any(), any()) } returns
          flowOf(
            scrapeRequest { scrapeId = 1L },
            scrapeRequest {
              scrapeId = 2L
              dashboardScrape = true
            },
          )
        val connectionContext = AgentConnectionContext(128)

        service.readRequestsFromProxy(mockk(relaxed = true), connectionContext)
        counts.queueWaitCount shouldBe 0L

        val actions = connectionContext.scrapeRequestActions()
        repeat(2) { actions.tryReceive().getOrThrow().invoke() }

        counts.queueWaitCount shouldBe 1L
      }
    }

    // A result counts as sent once gRPC takes it from its channel; what a failed stream left queued, which gRPC
    // cancels, discarding it, counts as dropped. Counted as it was queued, a result the failure discarded counted as
    // sent, and draining the channels afterward found nothing: the cancel had already emptied the failed stream's.
    "a result should count as sent once a writer takes it, and one a failed writer left queued as dropped" {
      withStubbedService("localhost:$PROXY_AGENT_PORT") { service, mockStub ->
        val agent = service.agent
        coEvery { mockStub.writeResponsesToProxy(any(), any()) } coAnswers {
          // Takes the first result, then fails, as a stream does when its connection breaks.
          arg<Flow<ScrapeResponse>>(0).first()
          throw StatusException(Status.UNAVAILABLE)
        }
        coEvery { mockStub.writeChunkedResponsesToProxy(any(), any()) } coAnswers {
          arg<Flow<ChunkedScrapeResponse>>(0).collect()
          EMPTY_INSTANCE
        }
        val connectionContext = AgentConnectionContext(128)
        repeat(2) { id ->
          connectionContext.sendScrapeResults(
            ScrapeResults(srAgentId = "test-agent-123", srScrapeId = id.toLong(), srContentAsText = "metric 1"),
          )
        }
        connectionContext.close()

        withTimeout(10.seconds) { service.writeResponsesToProxyUntilDisconnected(agent, connectionContext) }

        verify(exactly = 1) { agent.countScrapeResult(AgentMetrics.RESULT_NON_GZIPPED) }
        verify(exactly = 1) { agent.countScrapeResult(AgentMetrics.RESULT_DROPPED) }
      }
    }
  }

  // Has [this] mocked agent build its tracing from its reporter on first use, as Agent.tracing does.
  private fun Agent.tracesFromReporter() {
    val tracing = scrapeTracingHolder(1.0)
    every { this@tracesFromReporter.tracing } answers { tracing.value }
  }

  private companion object {
    // createMockAgent's launch ID, the first label of every agent series.
    const val LAUNCH_ID = "launch-123"

    // Where the ERROR for a refused token says the token is set
    const val TOKEN_SETTINGS =
      "it's given with --agent_token, AGENT_TOKEN, or agent.agentToken, in that order of precedence, which an " +
        "embedded agent's token() sets, and fromEnvironment() reads from PROMETHEUS_AGENT_TOKEN. The agent keeps " +
        "retrying"

    // The ERROR for a token the proxy at localhost refused
    const val TOKEN_REFUSED =
      "The proxy at localhost:$PROXY_AGENT_PORT refused the agent's token (StatusException: " +
        "UNAUTHENTICATED: Missing or invalid agent token). Check that the agent has a token the proxy accepts: " +
        TOKEN_SETTINGS
  }
}
