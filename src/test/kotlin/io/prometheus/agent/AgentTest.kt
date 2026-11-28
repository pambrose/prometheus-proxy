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

import ch.qos.logback.classic.Level
import com.google.common.util.concurrent.RateLimiter
import com.google.common.util.concurrent.Service
import com.pambrose.common.service.AbstractGenericService
import com.pambrose.common.util.runCatchingCancellable
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldNotBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import io.prometheus.Agent
import io.ktor.server.cio.CIO as ServerCIO
import io.prometheus.common.startAndAwaitReady
import io.prometheus.common.captureLogs
import io.prometheus.common.LOOPBACK_HOST
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import io.ktor.server.response.respond
import io.ktor.server.engine.embeddedServer
import io.ktor.http.HttpStatusCode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.common.agentOptions
import io.prometheus.common.ConfigLoadException
import io.prometheus.common.ScrapeResults
import io.prometheus.common.TestPorts.AGENT_FAILED_SHUTDOWN_ADMIN_PORT
import io.prometheus.common.TestPorts.AGENT_FAILED_SHUTDOWN_METRICS_PORT
import io.prometheus.common.TestPorts.AGENT_FAILED_START_METRICS_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.grpc.ProxyServiceGrpcKt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.minusAssign
import kotlin.concurrent.atomics.plusAssign
import kotlin.concurrent.atomics.update
import kotlin.io.path.createTempFile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import ch.qos.logback.classic.Logger as LogbackLogger

@Suppress("LargeClass")
class AgentTest : StringSpec() {
  // Every agent createTestAgent builds, released after each test: an Agent opens a gRPC channel and an HTTP client
  // cache on construction, and most of these tests never start or stop it, so each would otherwise leak both.
  private val createdAgents = mutableListOf<Agent>()

  private fun createTestAgent(vararg extraArgs: String): Agent {
    val args = ["--proxy", "localhost:$PROXY_AGENT_PORT"] + extraArgs
    return Agent(
      options = agentOptions(args, exitOnMissingConfig = false),
      inProcessServerName = "agent-test-${System.nanoTime()}",
      testMode = true,
    ).also { createdAgents += it }
  }

  init {
    afterTest {
      // runCatching: a test that started and stopped its agent has already released these.
      createdAgents.forEach { agent ->
        runCatching { agent.grpcService.shutDown() }
        runCatchingCancellable { agent.agentHttpService.close() }
      }
      createdAgents.clear()
    }

    // ==================== Connection Task Tests ====================

    // A connection's tasks end together, or an idle readRequestsFromProxy collect holds a dead connection open. See
    // Agent.launchConnectionTask.
    "when one connection task ends, the other connection tasks should be cancelled" {
      val agent = createTestAgent()
      val connectionContext = AgentConnectionContext(8)
      lateinit var idleTask: Job

      val connectionEnded =
        withTimeoutOrNull(5.seconds) {
          coroutineScope {
            with(agent) {
              idleTask = launchConnectionTask(connectionContext, "idle read stream") { awaitCancellation() }
              launchConnectionTask(connectionContext, "failed write stream") {
                throw IOException("write stream failed")
              }
            }
          }
          true
        }

      connectionEnded shouldBe true
      // Asserted on the Job, not a finally in the task: the idle task may be cancelled before it ever starts running.
      idleTask.isCancelled.shouldBeTrue()
      connectionContext.connected.shouldBeFalse()
    }

    // An Error in a connection task ended only the connection, and the agent reconnected, running on in the state the
    // Error left it in, though the same Error in a scrape stopped it (November #7). It now fails the connection's
    // scope, so the run loop's handleConnectionFailure rethrows it and the agent stops.
    "a JVM Error in a connection task should fail the connection's scope, and still end the other tasks" {
      val agent = createTestAgent()
      val connectionContext = AgentConnectionContext(8)
      lateinit var idleTask: Job

      shouldThrow<OutOfMemoryError> {
        withTimeoutOrNull(5.seconds) {
          coroutineScope {
            with(agent) {
              idleTask = launchConnectionTask(connectionContext, "idle read stream") { awaitCancellation() }
              launchConnectionTask(connectionContext, "result stream") { throw OutOfMemoryError("test") }
            }
          }
        }
      }

      idleTask.isCancelled.shouldBeTrue()
      connectionContext.connected.shouldBeFalse()
    }

    // ==================== awaitInitialConnection Tests ====================

    "awaitInitialConnection should return false when timeout expires" {
      val agent = createTestAgent()

      val result = agent.awaitInitialConnection(100.milliseconds)

      result.shouldBeFalse()
    }

    "awaitInitialConnection should return true after latch countdown" {
      val agent = createTestAgent()

      agent.countDownInitialConnectionLatch()

      val result = agent.awaitInitialConnection(1.seconds)

      result.shouldBeTrue()
    }

    // Public-API contract: embedded hosts (exitOnMissingConfig = false) must be able to catch a
    // config-load failure instead of the JVM exiting. Locks in that startAsyncAgent propagates it.
    "startAsyncAgent throws ConfigLoadException for a missing config when exitOnMissingConfig is false" {
      val exception =
        shouldThrow<ConfigLoadException> {
          Agent.startAsyncAgent("nonexistent.conf", exitOnMissingConfig = false, logBanner = false)
        }
      exception.message shouldContain "nonexistent.conf"
    }

    // A config URL can carry credentials (http://user:pass@host/agent.conf). A failed load logged the raw URL at ERROR
    // and put it in the ConfigLoadException an embedded host catches and, likely, logs.
    "a config URL that fails to load should not reveal its credentials" {
      val server =
        embeddedServer(ServerCIO, host = LOOPBACK_HOST, port = 0) {
          routing { get("/agent.conf") { call.respond(HttpStatusCode.NotFound) } }
        }
      try {
        val port = server.startAndAwaitReady()
        val configUrl = "http://admin:hunter2@$LOOPBACK_HOST:$port/agent.conf?token=s3cr3t"
        var exception: ConfigLoadException? = null
        val logs =
          captureLogs("io.prometheus.common.BaseOptions") {
            exception = runCatching {
              Agent.startAsyncAgent(configUrl, exitOnMissingConfig = false, logBanner = false)
            }.exceptionOrNull() as? ConfigLoadException
          }.map { it.formattedMessage }

        val message = exception.shouldNotBeNull().message.orEmpty()
        message shouldContain "agent.conf"
        message shouldNotContain "hunter2"
        message shouldNotContain "s3cr3t"
        // An embedded host that logs the exception prints its cause too, whose message was the raw URL.
        exception.shouldNotBeNull().cause?.message.orEmpty() shouldNotContain "hunter2"
        logs.shouldNotBeEmpty()
        logs.forEach { line ->
          line shouldNotContain "hunter2"
          line shouldNotContain "s3cr3t"
        }
      } finally {
        server.stop(0, 0)
      }
    }

    // ==================== Failed startup Tests ====================

    // Guava calls shutDown() only after startUp() succeeds. When startUp() throws -- here because the admin port is
    // taken -- nothing released the gRPC channel and HTTP client cache the constructor built, or the metrics server
    // startUp() had already started, and stop() (what EmbeddedAgentInfo.shutdown() calls) threw on the FAILED service.
    "a failed startup should release what the agent holds and leave stop() safe to call" {
      PrometheusRegistry.defaultRegistry.clear()
      ServerSocket(0).use { takenAdminPort ->
        val agent =
          createTestAgent(
            "--metrics",
            "--metrics_port",
            "$AGENT_FAILED_START_METRICS_PORT",
            "--admin",
            "--admin_port",
            "${takenAdminPort.localPort}",
          )

        shouldThrow<IllegalStateException> { agent.startSync() }

        // Captured as values first, so each check below reports on its own inside assertSoftly.
        val state = agent.state()
        val cacheFailure =
          runCatchingCancellable {
            agent.agentHttpService.httpClientCache.getOrCreateClient(HttpClientCache.ClientKey(null, null)) {
              HttpClient(CIO)
            }
          }.exceptionOrNull()
        val stopFailure = runCatching { agent.stop() }.exceptionOrNull()
        // A handle with a stop timeout stops it on a thread of its own
        val timedStopFailure =
          runCatching { EmbeddedAgentInfo(agent, stopTimeout = 5.seconds).shutdown() }.exceptionOrNull()

        assertSoftly {
          state shouldBe Service.State.FAILED
          agent.grpcService.channel.isShutdown.shouldBeTrue()
          agent.metricsService.isRunning.shouldBeFalse()
          cacheFailure?.message.orEmpty() shouldContain "closed"
          stopFailure.shouldBeNull()
          timedStopFailure.shouldBeNull()
        }
      }
    }

    // An initBlock runs inside the constructor, before the properties declared below the init block are set, so a
    // startup it began that failed released through a still-null list: a NullPointerException hid the failure, and the
    // gRPC channel and HTTP client cache stayed open. The harness starts its agents this way.
    "a startup that fails in initBlock should throw its own failure and release what the agent holds" {
      PrometheusRegistry.defaultRegistry.clear()
      ServerSocket(0).use { takenAdminPort ->
        val args = ["--proxy", "localhost:$PROXY_AGENT_PORT", "--admin", "--admin_port", "${takenAdminPort.localPort}"]
        var built: Agent? = null
        val failure =
          shouldThrow<IllegalStateException> {
            Agent(
              options = agentOptions(args, exitOnMissingConfig = false),
              inProcessServerName = "agent-test-${System.nanoTime()}",
              testMode = true,
            ) {
              built = this
              startSync()
            }
          }

        val agent = built.shouldNotBeNull().also { createdAgents += it }
        assertSoftly {
          failure.cause.shouldNotBeInstanceOf<NullPointerException>()
          agent.state() shouldBe Service.State.FAILED
          agent.grpcService.channel.isShutdown.shouldBeTrue()
        }
      }
    }

    // ==================== Failed shutdown Tests ====================

    // shutDown() ran its steps in turn, so when the gRPC shutdown threw, the HTTP client cache stayed open and
    // super.shutDown() never stopped the admin and metrics servers, which held their ports for the life of the JVM.
    "a failed gRPC shutdown should still stop the agent's servers and close its client cache" {
      PrometheusRegistry.defaultRegistry.clear()
      mockkConstructor(AgentGrpcService::class)
      try {
        every { anyConstructed<AgentGrpcService>().shutDown() } throws IllegalStateException("gRPC shutdown failed")
        val agent =
          createTestAgent(
            "--metrics",
            "--metrics_port",
            "$AGENT_FAILED_SHUTDOWN_METRICS_PORT",
            "--admin",
            "--admin_port",
            "$AGENT_FAILED_SHUTDOWN_ADMIN_PORT",
          )
        agent.startSync()

        // Captured as values first, so each check below reports on its own inside assertSoftly.
        val stopFailure = runCatching { agent.stopSync() }.exceptionOrNull()
        val cacheFailure =
          runCatchingCancellable {
            agent.agentHttpService.httpClientCache.getOrCreateClient(HttpClientCache.ClientKey(null, null)) {
              HttpClient(CIO)
            }
          }.exceptionOrNull()

        assertSoftly {
          stopFailure.shouldNotBeNull()
          agent.state() shouldBe Service.State.FAILED
          agent.failureCause().message shouldBe "gRPC shutdown failed"
          agent.servletService.isRunning.shouldBeFalse()
          agent.metricsService.isRunning.shouldBeFalse()
          cacheFailure?.message.orEmpty() shouldContain "closed"
        }
      } finally {
        unmockkConstructor(AgentGrpcService::class)
      }
    }

    // The embedded entry point returned its handle right after startAsync(), so a startup failure left the host
    // holding what looked like a live agent. It now waits for startup and throws the failure.
    "startAsyncAgent should throw when the agent fails to start" {
      PrometheusRegistry.defaultRegistry.clear()
      ServerSocket(0).use { takenAdminPort ->
        val config =
          createTempFile("failed-start", ".conf").toFile().apply {
            writeText("agent { admin { enabled = true, port = ${takenAdminPort.localPort} }, pathConfigs = [] }")
          }
        try {
          shouldThrow<IllegalStateException> {
            Agent.startAsyncAgent(config.absolutePath, exitOnMissingConfig = false, logBanner = false)
          }.cause.shouldNotBeNull()
        } finally {
          config.delete()
        }
      }
    }

    "awaitInitialConnection should return false immediately with zero timeout" {
      val agent = createTestAgent()

      val result = agent.awaitInitialConnection(0.milliseconds)

      result.shouldBeFalse()
    }

    // ==================== Embedded shutdown lifecycle Tests ====================

    // Regression: the documented embedded shutdown path (EmbeddedAgentInfo.shutdown() -> Agent.stop())
    // must drive the Guava service lifecycle so the run() loop actually exits. Previously stop() called
    // the shutDown() hook directly, which tore down the gRPC channel/servlets but never flipped
    // isRunning, leaving the run loop reconnecting forever (a zombie thread). This starts a real Agent,
    // lets its run loop spin (failing to reach the absent in-process proxy), then asserts shutdown()
    // terminates the service rather than leaving it running.
    "EmbeddedAgentInfo.shutdown() should terminate the agent service, not leave the run loop reconnecting" {
      val agent =
        Agent(
          options = agentOptions(["--proxy", "localhost:$PROXY_AGENT_PORT"], exitOnMissingConfig = false),
          inProcessServerName = "embedded-stop-test-${System.nanoTime()}",
          testMode = true,
        ) { startAsync() }
      val info = EmbeddedAgentInfo(agent)

      try {
        // The run() loop is active, repeatedly failing to connect to the absent in-process proxy.
        eventually(5.seconds) { agent.isRunning.shouldBeTrue() }

        // shutdown() must block until the service has terminated (per its documented contract).
        info.shutdown()

        agent.isRunning.shouldBeFalse()
      } finally {
        try {
          if (agent.isRunning) agent.stopSync()
        } catch (_: Exception) {
        }
      }
    }

    // A handle with a stop timeout stops the agent on a thread of its own, without stop()'s 30-second limit, which must
    // drive the same lifecycle as stop()
    "EmbeddedAgentInfo.shutdown() with a stop timeout should terminate the agent service too" {
      val agent =
        Agent(
          options = agentOptions(["--proxy", "localhost:$PROXY_AGENT_PORT"], exitOnMissingConfig = false),
          inProcessServerName = "embedded-stop-timeout-test-${System.nanoTime()}",
          testMode = true,
        ) { startAsync() }
      val info = EmbeddedAgentInfo(agent, stopTimeout = 10.seconds)

      try {
        eventually(5.seconds) { agent.isRunning.shouldBeTrue() }

        info.shutdown()

        agent.state() shouldBe Service.State.TERMINATED
      } finally {
        try {
          if (agent.isRunning) agent.stopSync()
        } catch (_: Exception) {
        }
      }
    }

    // An embedded agent's host stops it after the work that needs it (EmbeddedAgentInfo is AutoCloseable), where a JVM
    // shutdown hook stopped it on SIGTERM alongside the host's own hooks, in no set order. An Agent built and started
    // directly, as the standalone agent is, keeps the hook. The spy on common-utils' hook factory calls the real one.
    "an embedded agent registers no JVM shutdown hook, and one started directly does" {
      mockkObject(AbstractGenericService.Companion)
      try {
        val embedded =
          Agent.startEmbedded(exitOnMissingConfig = false, logBanner = false) {
            agentOptions(["--proxy", "localhost:1"], exitOnMissingConfig = false)
          }
        embedded.use {
          verify(exactly = 0) {
            AbstractGenericService.shutDownHookAction(match { it is Agent && it.launchId == embedded.launchId })
          }
        }

        val direct = createTestAgent()
        direct.startSync()
        try {
          verify(exactly = 1) { AbstractGenericService.shutDownHookAction(direct) }
        } finally {
          direct.stopSync()
        }
      } finally {
        unmockkObject(AbstractGenericService.Companion)
      }
    }

    // ==================== Finding 3: reconnect pacing must not depend on the log level ====================

    // The reconnect rate limiter's acquire() must run every reconnect cycle regardless of log level.
    // Previously acquire() lived inside a logger.info { } lambda, which kotlin-logging skips when the
    // level is above INFO -- silently disabling reconnect pacing (hot reconnect loop) under --log_level warn.
    // Raising the Agent logger to WARN and asserting acquire() is still invoked catches that regression.
    "Finding 3: reconnectPause acquires a permit even when the info log level is disabled" {
      val agent = createTestAgent()

      val mockLimiter = mockk<RateLimiter>()
      every { mockLimiter.acquire() } returns 0.0
      agent.reconnectLimiter = mockLimiter

      val agentLogger = LoggerFactory.getLogger("io.prometheus.Agent") as LogbackLogger
      val originalLevel = agentLogger.level
      agentLogger.level = Level.WARN
      try {
        agent.reconnectPause()
        verify(exactly = 1) { mockLimiter.acquire() }
      } finally {
        agentLogger.level = originalLevel
      }
    }

    // A JVM Error stops the agent, so there's no reconnect to pace: the run loop waited out reconnectPauseSecs first,
    // logging "Waited ... to reconnect", as the service still read as running until the Error left run().
    "a JVM Error that stops the agent shouldn't wait out the reconnect pause first" {
      val limiter = mockk<RateLimiter>()
      every { limiter.acquire() } returns 0.0
      val stub = mockk<ProxyServiceGrpcKt.ProxyServiceCoroutineStub>()
      coEvery { stub.connectAgent(any(), any()) } throws OutOfMemoryError("test")
      lateinit var agent: Agent

      val logs =
        captureLogs("io.prometheus.Agent") {
          agent =
            Agent(
              options = agentOptions(["--proxy", "localhost:$PROXY_AGENT_PORT"], exitOnMissingConfig = false),
              inProcessServerName = "agent-error-pause-test-${System.nanoTime()}",
              testMode = true,
            ) {
              reconnectLimiter = limiter
              grpcService.grpcStub = stub
              grpcService.unaryDeadlineSecs = 0
              startAsync()
            }
          eventually(5.seconds) { agent.state() shouldBe Service.State.FAILED }
        }.map { it.formattedMessage }

      agent.failureCause().shouldBeInstanceOf<OutOfMemoryError>()
      verify(exactly = 0) { limiter.acquire() }
      logs.filter { it.startsWith("Waited") }.shouldBeEmpty()
    }

    // ==================== Finding 2: heartbeat-failure threshold before reconnect ====================

    // nextHeartbeatFailureCount drives the heartbeat loop's disconnect decision. SUCCESS resets the
    // running failure count; EVICTED disconnects immediately; consecutive transient failures accumulate
    // and force a disconnect (null) once they reach maxFailures -- so a half-open transport (heartbeats
    // failing while the read stream stays silently suspended) no longer leaves the agent a zombie.

    "Finding 2: nextHeartbeatFailureCount resets to 0 on SUCCESS" {
      val agent = createTestAgent()
      agent.nextHeartbeatFailureCount(HeartBeatResult.SUCCESS, consecutiveFailures = 2, maxFailures = 3) shouldBe 0
    }

    "Finding 2: nextHeartbeatFailureCount signals immediate disconnect on EVICTED" {
      val agent = createTestAgent()
      agent.nextHeartbeatFailureCount(HeartBeatResult.EVICTED, consecutiveFailures = 0, maxFailures = 3)
        .shouldBeNull()
    }

    "Finding 2: nextHeartbeatFailureCount accumulates transient failures below the threshold" {
      val agent = createTestAgent()
      val transient = HeartBeatResult.TRANSIENT_FAILURE
      agent.nextHeartbeatFailureCount(transient, consecutiveFailures = 0, maxFailures = 3) shouldBe 1
      agent.nextHeartbeatFailureCount(transient, consecutiveFailures = 1, maxFailures = 3) shouldBe 2
    }

    "Finding 2: nextHeartbeatFailureCount signals disconnect at the failure threshold" {
      val agent = createTestAgent()
      agent.nextHeartbeatFailureCount(HeartBeatResult.TRANSIENT_FAILURE, consecutiveFailures = 2, maxFailures = 3)
        .shouldBeNull()
    }

    // ==================== heartbeat deadline ====================

    // A heartbeat that inherits the 30s unary deadline takes about 90s to detect a silently dropped connection
    // (three failures), longer than the proxy's 60s eviction. Heartbeats get their own deadline: the heartbeat
    // interval, capped at the unary deadline and never below 1s.

    "heartbeatDeadlineSecs should use the heartbeat interval when it is shorter than the unary deadline" {
      createTestAgent().heartbeatDeadlineSecs(heartbeatMaxInactivitySecs = 5, unaryDeadlineSecs = 30) shouldBe 5L
    }

    "heartbeatDeadlineSecs should be capped at the unary deadline" {
      createTestAgent().heartbeatDeadlineSecs(heartbeatMaxInactivitySecs = 120, unaryDeadlineSecs = 30) shouldBe 30L
    }

    // Disabled unary deadlines apply to heartbeats too, as to every other unary RPC; 0 applies no deadline.
    "heartbeatDeadlineSecs should apply no deadline when unary deadlines are disabled" {
      createTestAgent().heartbeatDeadlineSecs(heartbeatMaxInactivitySecs = 5, unaryDeadlineSecs = 0) shouldBe 0L
    }

    // A zero deadline would fail every heartbeat instantly and force a reconnect loop.
    "heartbeatDeadlineSecs should never be below one second" {
      createTestAgent().heartbeatDeadlineSecs(heartbeatMaxInactivitySecs = 0, unaryDeadlineSecs = 30) shouldBe 1L
    }

    // ==================== updateScrapeCounter Tests ====================

    "updateScrapeCounter should not fail for empty type" {
      val agent = createTestAgent()

      // With metrics disabled (default), empty type should be a no-op
      agent.updateScrapeCounter("")
    }

    "updateScrapeCounter should not fail for non-empty type with metrics disabled" {
      val agent = createTestAgent()

      // With metrics disabled (default), non-empty type hits the metrics guard and exits
      agent.updateScrapeCounter("success")
    }

    // ==================== Dashboard scrape counting Tests ====================

    // The result of a scrape the proxy's dashboard asked for isn't counted in agent_scrape_result_count_total, which
    // counts only Prometheus's scrapes; agent_dashboard_scrapes_total counts the scrape instead. The agent isn't
    // started, so its metrics are built but serve no port.
    "updateScrapeResultCounter should count a result under its type, but not a dashboard scrape's" {
      val agent = createTestAgent("-Dagent.metrics.enabled=true")
      try {
        val results = ScrapeResults(srAgentId = "agent", srScrapeId = 1L)
        agent.updateScrapeResultCounter(results, "non-gzipped")
        agent.updateScrapeResultCounter(results.asDashboardScrape(), "non-gzipped")
        agent.updateScrapeResultCounter(results.asDashboardScrape(), "dropped")
        agent.updateDashboardScrapeCounter()

        // Every type starts at zero; only Prometheus's scrape counted, the dashboard's two left out.
        agent.metrics.scrapeResultCount.collect().dataPoints.associate { it.labels.get("type") to it.value } shouldBe
          mapOf("non-gzipped" to 1.0, "gzipped" to 0.0, "chunked" to 0.0, "dropped" to 0.0)
        agent.metrics.dashboardScrapeCount.labelValues(agent.launchId).get() shouldBe 1.0
      } finally {
        agent.metrics.close()
      }
    }

    // The production wiring of what a connection can't send: each result is counted as dropped (November #6). The
    // agent isn't started, so its metrics are built but serve no port.
    "a connection's results it can't send should count as dropped" {
      val agent = createTestAgent("-Dagent.metrics.enabled=true")
      try {
        val context = agent.newConnectionContext()
        (1L..2L).forEach { context.sendScrapeResults(ScrapeResults(srAgentId = "agent", srScrapeId = it)) }
        context.close()
        context.dropUnsentResults()

        agent.metrics.scrapeResultCount.labelValues(agent.launchId, AgentMetrics.RESULT_DROPPED).get() shouldBe 2.0
      } finally {
        agent.metrics.close()
      }
    }

    // ==================== markMsgSent Tests ====================

    "markMsgSent should complete without error" {
      val agent = createTestAgent()

      // Should not throw
      agent.markMsgSent()
    }

    // ==================== serviceName Tests ====================

    "serviceName should return Agent agentName format" {
      val agent = createTestAgent("--name", "my-test-agent")

      // serviceName() is protected, so verify via reflection
      val method = agent.javaClass.getDeclaredMethod("serviceName")
      method.isAccessible = true
      val name = method.invoke(agent) as String

      name shouldBe "Agent my-test-agent"
    }

    // ==================== agentName Tests ====================

    "agentName should use provided name from options" {
      val agent = createTestAgent("--name", "custom-agent")

      agent.agentName shouldBe "custom-agent"
    }

    "agentName should fallback to Unnamed-hostname when name is blank" {
      val agent = createTestAgent()

      agent.agentName shouldContain "Unnamed-"
    }

    // ==================== proxyHost Tests ====================

    "proxyHost should return hostname colon port format" {
      val agent = createTestAgent()

      val host = agent.proxyHost

      host shouldContain ":"
      host.shouldNotBeEmpty()
    }

    // ==================== metrics Tests ====================

    "metrics should not invoke lambda when metrics disabled" {
      val agent = createTestAgent()

      var invoked = false
      agent.metrics { invoked = true }

      invoked.shouldBeFalse()
    }

    "metrics should invoke lambda when metrics enabled" {
      val mockMetrics = mockk<AgentMetrics>(relaxed = true)
      val mockAgent = mockk<Agent>(relaxed = true)
      every { mockAgent.isMetricsEnabled } returns true
      every { mockAgent.metrics } returns mockMetrics
      every { mockAgent.metrics(any<AgentMetrics.() -> Unit>()) } answers {
        firstArg<AgentMetrics.() -> Unit>().invoke(mockMetrics)
      }

      var invoked = false
      mockAgent.metrics { invoked = true }

      invoked.shouldBeTrue()
    }

    // ==================== Bug #12: currentCacheSize non-blocking ====================

    // Bug #12: A health check once used runBlocking { getCacheStats().totalEntries }, which could block its thread
    // while waiting for the cache mutex. currentCacheSize() reads an atomic copy of the size instead, without the
    // mutex; the agent_client_cache_size gauge reads it.
    "httpClientCache.currentCacheSize should return 0 for empty cache" {
      val agent = createTestAgent()

      val size = agent.agentHttpService.httpClientCache.currentCacheSize()

      size shouldBe 0
    }

    "httpClientCache.currentCacheSize should not require coroutine context" {
      val agent = createTestAgent()

      // currentCacheSize() reads an atomic copy of the size, so it works from any thread without runBlocking or a
      // coroutine context. getCacheStats() takes the cache's mutex, which needs one.
      val thread = Thread {
        val size = agent.agentHttpService.httpClientCache.currentCacheSize()
        size shouldBe 0
      }
      thread.start()
      thread.join(5000)

      thread.isAlive.shouldBeFalse()
    }

    // ==================== Construction / launchId / toString Tests ====================

    "launchId should be a 15-character string" {
      val agent = createTestAgent()

      agent.launchId.length shouldBe 15
      agent.launchId.shouldNotBeEmpty()
    }

    "toString should contain agentName and proxyHost" {
      val agent = createTestAgent("--name", "tostring-agent")

      val str = agent.toString()

      str shouldContain "agentName"
      str shouldContain "tostring-agent"
      str shouldContain "proxyHost"
    }

    // ==================== Bug #5: scrapeRequestBacklogSize should not go negative ====================

    "scrapeRequestBacklogSize should start at zero" {
      val agent = createTestAgent()

      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "scrapeRequestBacklogSize increment and decrement should balance to zero" {
      val agent = createTestAgent()

      agent.scrapeRequestBacklogSize += 1
      agent.scrapeRequestBacklogSize += 1
      agent.scrapeRequestBacklogSize += 1
      agent.scrapeRequestBacklogSize.load() shouldBe 3

      agent.scrapeRequestBacklogSize -= 1
      agent.scrapeRequestBacklogSize -= 1
      agent.scrapeRequestBacklogSize -= 1
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    // Bug #5: Verifies that coroutineScope guarantees all finally blocks run before
    // returning, so a counter incremented in launch and decremented in finally will
    // always return to zero -- no store(0) needed.
    "scrapeRequestBacklogSize pattern should return to zero after coroutineScope completes" {
      val counter = AtomicInt(0)

      coroutineScope {
        repeat(10) {
          launch {
            counter += 1
            try {
              delay(10.milliseconds)
            } finally {
              counter -= 1
            }
          }
        }
      }

      // After coroutineScope, all children have completed including finally blocks
      counter.load() shouldBe 0
    }

    // Bug #5: Simulates the previous bug: a store(0) between in-flight increments
    // and their corresponding decrements drives the counter negative.
    "store(0) during in-flight decrements would produce negative counter" {
      val counter = AtomicInt(0)

      // Simulate: 3 scrapes in flight
      counter += 1
      counter += 1
      counter += 1
      counter.load() shouldBe 3

      // Simulate: reconnect resets to 0 while decrements are still pending
      counter.store(0)
      counter.load() shouldBe 0

      // Simulate: in-flight finally blocks decrement after the reset
      counter -= 1
      counter -= 1
      counter -= 1
      counter.load() shouldBe -3 // Bug! Counter is negative
    }

    // Bug #5: Without store(0), the counter naturally returns to zero.
    "without store(0) counter naturally returns to zero" {
      val counter = AtomicInt(0)

      // Simulate: 3 scrapes in flight
      counter += 1
      counter += 1
      counter += 1
      counter.load() shouldBe 3

      // No store(0) -- just let the finally blocks run
      counter -= 1
      counter -= 1
      counter -= 1
      counter.load() shouldBe 0
    }

    // Bug #5: Balanced increments and decrements leave the counter the backlog health check reads at zero or above.
    "scrapeRequestBacklogSize should not go negative after balanced increments and decrements" {
      val agent = createTestAgent()

      // Simulate balanced increment/decrement cycles
      repeat(5) {
        agent.scrapeRequestBacklogSize += 1
        agent.scrapeRequestBacklogSize -= 1
      }

      agent.scrapeRequestBacklogSize.load() shouldBeGreaterThanOrEqual 0
    }

    // Bug #20: decrementBacklog should clamp at zero to prevent negative values
    "Bug #20: decrementBacklog should not go below zero" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.store(2)

      // Decrementing by more than current value should clamp at 0
      agent.decrementBacklog(5)
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "Bug #20: decrementBacklog should decrement normally when value is sufficient" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.store(10)

      agent.decrementBacklog(3)
      agent.scrapeRequestBacklogSize.load() shouldBe 7

      agent.decrementBacklog(7)
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "Bug #20: decrementBacklog from zero should stay at zero" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.load() shouldBe 0

      agent.decrementBacklog(1)
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    "Bug #20: concurrent decrementBacklog should never go negative" {
      val agent = createTestAgent()
      agent.scrapeRequestBacklogSize.store(50)

      coroutineScope {
        repeat(100) {
          launch(Dispatchers.IO) {
            agent.decrementBacklog(1)
          }
        }
      }

      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    // ==================== Bug #8: handleConnectionFailure should rethrow JVM Errors ====================

    // Bug #8: Before the fix, the reconnect loop's onFailure handler caught all Throwable
    // (via runCatchingCancellable + else branch) including Error subclasses like
    // OutOfMemoryError and StackOverflowError. These were logged at warn level and
    // the agent continued running in a potentially corrupted state.
    //
    // The fix adds an `is Error` branch that re-throws, causing the agent to terminate
    // on fatal JVM errors instead of swallowing them.

    "Bug #8: handleConnectionFailure should rethrow StackOverflowError" {
      val agent = createTestAgent()

      shouldThrow<StackOverflowError> {
        agent.handleConnectionFailure(StackOverflowError("test stack overflow"))
      }
    }

    "Bug #8: handleConnectionFailure should rethrow OutOfMemoryError" {
      val agent = createTestAgent()

      shouldThrow<OutOfMemoryError> {
        agent.handleConnectionFailure(OutOfMemoryError("test OOM"))
      }
    }

    "Bug #8: handleConnectionFailure should rethrow VirtualMachineError subclasses" {
      val agent = createTestAgent()

      shouldThrow<InternalError> {
        agent.handleConnectionFailure(InternalError("test internal error"))
      }
    }

    "Bug #8: handleConnectionFailure should rethrow AssertionError" {
      val agent = createTestAgent()

      // AssertionError is an Error subclass
      shouldThrow<AssertionError> {
        agent.handleConnectionFailure(AssertionError("test assertion"))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for RequestFailureException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(RequestFailureException("invalid response"))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for StatusRuntimeException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(StatusRuntimeException(Status.UNAVAILABLE))
      }
    }

    // A call the proxy refuses after the connect it took, as once its token changes, is the agent's token, at ERROR,
    // as a refused connect is; any other status is the disconnect it always was, at INFO.
    "handleConnectionFailure should log a refused token at ERROR, naming the setting, and another status at INFO" {
      val agent = createTestAgent("--agent_token", "wrong-token")

      val events =
        captureLogs<Agent>(Level.INFO) {
          agent.handleConnectionFailure(
            StatusRuntimeException(Status.UNAUTHENTICATED.withDescription("Missing or invalid agent token")),
          )
          agent.handleConnectionFailure(StatusException(Status.UNAUTHENTICATED))
          agent.handleConnectionFailure(StatusRuntimeException(Status.UNAVAILABLE))
        }

      events.map { it.level } shouldBe listOf(Level.ERROR, Level.ERROR, Level.INFO)
      events[0].formattedMessage.apply {
        this shouldStartWith "The proxy at localhost:$PROXY_AGENT_PORT refused the agent's token " +
          "(StatusRuntimeException: UNAUTHENTICATED: Missing or invalid agent token)"
        this shouldContain "PROMETHEUS_AGENT_TOKEN"
        this shouldNotContain "wrong-token"
      }
      events[2].formattedMessage shouldContain "Disconnected from proxy"
    }

    "Bug #8: handleConnectionFailure should not throw for StatusException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(StatusException(Status.UNAVAILABLE))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for RuntimeException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(RuntimeException("network error"))
      }
    }

    "Bug #8: handleConnectionFailure should not throw for IOException" {
      val agent = createTestAgent()

      shouldNotThrow<Throwable> {
        agent.handleConnectionFailure(IOException("connection reset"))
      }
    }

    // ==================== Bug #1: Bounded Coroutine Creation Tests ====================

    // Agent.processScrapeRequests itself, fed through a connection's channel: it takes a permit before it launches a
    // scrape, so the channel fills, and the proxy's stream waits, while maxConcurrentHttpClients scrapes run. Taking
    // the permit inside the launch (Bug #1) ran no more scrapes at once, but the loop drained the channel at once,
    // launching a coroutine for every request the proxy sent.
    "Bug #1: processScrapeRequests should run at most maxConcurrentHttpClients scrapes, and take no more meanwhile" {
      val maxConcurrency = 2
      val capacity = 2
      val agent = createTestAgent("--max_concurrent_clients", "$maxConcurrency")
      val context = AgentConnectionContext(backlogCapacity = capacity)
      val floodSize = 20
      // As readRequestsFromProxy counts each request it queues
      agent.scrapeRequestBacklogSize.store(floodSize)
      // Each scrape waits on the gate, so how many run is fixed until it opens
      val gate = CompletableDeferred<Unit>()
      val running = AtomicInt(0)
      val mostRunning = AtomicInt(0)
      val sent = AtomicInt(0)

      withTimeout(10.seconds) {
        coroutineScope {
          val scrapes = launch(Dispatchers.Default) { with(agent) { processScrapeRequests(context) } }
          val sender =
            launch(Dispatchers.Default) {
              repeat(floodSize) { i ->
                context.sendScrapeRequestAction {
                  val now = running.incrementAndFetch()
                  mostRunning.update { maxOf(it, now) }
                  try {
                    gate.await()
                  } finally {
                    running.decrementAndFetch()
                  }
                  ScrapeResults(srAgentId = "agent", srScrapeId = i.toLong())
                }
                sent.incrementAndFetch()
              }
            }

          // The scrapes running, the request the loop took waiting for a permit, and the channel full behind it
          val held = maxConcurrency + 1 + capacity
          eventually(5.seconds) {
            running.load() shouldBe maxConcurrency
            sent.load() shouldBe held
          }
          // Still so a moment later: nothing takes another request until a scrape ends
          delay(200.milliseconds)
          running.load() shouldBe maxConcurrency
          sent.load() shouldBe held

          gate.complete(Unit)
          sender.join()
          // Closed, not drained as AgentConnectionContext.close() drains it, so the loop runs what's left, then ends
          context.scrapeRequestActions().close()
          scrapes.join()
        }
      }

      mostRunning.load() shouldBe maxConcurrency
      generateSequence { context.scrapeResults().tryReceive().getOrNull() }.count() shouldBe floodSize
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }

    // A scrape that throws still gives back its permit and its place in the backlog: with one permit, the next scrape
    // runs only once the failed one has released it. The scope is a supervisor's here, so the failure doesn't cancel
    // the loop, as it ends the connection in Agent.
    "processScrapeRequests should release a failed scrape's permit and its place in the backlog" {
      val agent = createTestAgent("--max_concurrent_clients", "1")
      val context = AgentConnectionContext(backlogCapacity = 4)
      agent.scrapeRequestBacklogSize.store(2)
      val failures = mutableListOf<Throwable>()
      val handler = CoroutineExceptionHandler { _, e -> synchronized(failures) { failures += e } }
      context.sendScrapeRequestAction { throw IOException("scrape failed") }
      context.sendScrapeRequestAction { ScrapeResults(srAgentId = "agent", srScrapeId = 2) }
      context.scrapeRequestActions().close()

      val finished =
        withTimeoutOrNull(5.seconds) {
          withContext(handler) {
            supervisorScope { with(agent) { processScrapeRequests(context) } }
          }
        }

      finished.shouldNotBeNull()
      failures.map { it.message } shouldBe listOf("scrape failed")
      context.scrapeResults().tryReceive().getOrNull()?.srScrapeId shouldBe 2L
      agent.scrapeRequestBacklogSize.load() shouldBe 0
    }
  }
}
