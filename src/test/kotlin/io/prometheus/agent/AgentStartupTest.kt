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

import com.google.common.util.concurrent.Service
import com.pambrose.common.service.AbstractGenericService
import com.pambrose.common.util.runCatchingCancellable
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.prometheus.Agent
import io.prometheus.agent.dashboard.AgentDashboardService
import io.prometheus.common.LOOPBACK_HOST
import io.prometheus.common.TestPorts.AGENT_FAILED_DASHBOARD_ADMIN_PORT
import io.prometheus.common.TestPorts.AGENT_FAILED_DASHBOARD_METRICS_PORT
import io.prometheus.common.TestPorts.AGENT_FAILED_DASHBOARD_TAKEN_PORT
import io.prometheus.common.TestPorts.AGENT_START_TIMEOUT_ADMIN_PORT
import io.prometheus.common.TestPorts.AGENT_START_TIMEOUT_DASHBOARD_PORT
import io.prometheus.common.TestPorts.EMBEDDED_FAILED_DASHBOARD_ADMIN_PORT
import io.prometheus.common.TestPorts.EMBEDDED_FAILED_DASHBOARD_TAKEN_PORT
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.common.TestPorts.ZIPKIN_UNREACHABLE_PORT
import io.prometheus.common.agentOptions
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.net.BindException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A start that fails or takes too long releases what the agent opened: a dashboard that can't bind its port stops the
 * admin, metrics, and Zipkin services `super.startUp()` started and removes the JVM shutdown hook, and a start that
 * outlasts its wait stops as soon as its startup ends (November 23 review, #5 and #12). An embedded agent's thread is a
 * daemon, so a host that fails without stopping it, or a start that never ends, can't hold up the JVM (#10). The failed
 * starts that came before these, an admin port that's taken and one started from `initBlock`, are in `AgentTest`.
 */
class AgentStartupTest : StringSpec() {
  // Every agent createTestAgent builds, released after each test, as AgentTest's are: an Agent opens a gRPC channel and
  // an HTTP client cache on construction.
  private val createdAgents = mutableListOf<Agent>()

  private fun createTestAgent(vararg extraArgs: String): Agent {
    val args = ["--proxy", "localhost:$PROXY_AGENT_PORT"] + extraArgs
    return Agent(
      options = agentOptions(args, exitOnMissingConfig = false),
      inProcessServerName = "agent-startup-test-${System.nanoTime()}",
      testMode = true,
    ).also { createdAgents += it }
  }

  // The JVM shutdown hook common-utils registered for this agent, or null. Its accessor, registeredShutDownHook, is
  // internal to common-utils, so the field is read.
  private fun Agent.registeredShutDownHook(): Thread? =
    AbstractGenericService::class.java
      .getDeclaredField("shutDownHook")
      .apply { isAccessible = true }
      .get(this) as Thread?

  // Whether nothing holds [port], bound with a listening socket as the agent's servers bind theirs.
  private fun canBind(port: Int) = runCatching { ServerSocket(port).close() }.isSuccess

  // This throwable and its causes, outermost first.
  private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

  // The running agent [agentName]'s service thread, which Guava names after the service from its startUp() on. The
  // tests run with coroutines' debug mode, which adds the coroutine to the name while the thread runs one, as the
  // agent's connection loop does.
  private fun serviceThread(agentName: String): Thread =
    Thread.getAllStackTraces().keys.single { it.name.substringBefore(" @") == "Agent $agentName" }

  init {
    afterTest {
      // runCatching: a test whose agent failed to start, or stopped, has already released these.
      createdAgents.forEach { agent ->
        runCatching { agent.grpcService.shutDown() }
        runCatchingCancellable { agent.agentHttpService.close() }
      }
      createdAgents.clear()
    }

    // The dashboard starts after super.startUp(), whose admin, metrics, and Zipkin services and JVM shutdown hook a
    // failed dashboard start left running: a standalone agent whose dashboard port was taken never exited, its /ping
    // answering 200 with no connection, and stop() returned at once for the FAILED agent (November 23 review, #5).
    "a dashboard that fails to start should stop the admin, metrics, and Zipkin services and remove the shutdown hook" {
      PrometheusRegistry.defaultRegistry.clear()
      ServerSocket(AGENT_FAILED_DASHBOARD_TAKEN_PORT).use {
        val agent =
          createTestAgent(
            "--admin",
            "--admin_port",
            "$AGENT_FAILED_DASHBOARD_ADMIN_PORT",
            "--metrics",
            "--metrics_port",
            "$AGENT_FAILED_DASHBOARD_METRICS_PORT",
            "--dashboard",
            "--dashboard_port",
            "$AGENT_FAILED_DASHBOARD_TAKEN_PORT",
            "-Dagent.internal.zipkin.enabled=true",
            "-Dagent.internal.zipkin.hostname=$LOOPBACK_HOST",
            "-Dagent.internal.zipkin.port=$ZIPKIN_UNREACHABLE_PORT",
          )

        val failure = shouldThrow<IllegalStateException> { agent.startSync() }

        // Captured as values first, so each check below reports on its own inside assertSoftly.
        val state = agent.state()
        // What startUp() threw, which Guava's exception wraps, carries what releasing after it threw.
        val releaseFailures = failure.cause?.suppressed.orEmpty().toList()
        val hook = agent.registeredShutDownHook()
        val adminPortFree = canBind(AGENT_FAILED_DASHBOARD_ADMIN_PORT)
        val metricsPortFree = canBind(AGENT_FAILED_DASHBOARD_METRICS_PORT)
        val stopFailure = runCatching { agent.stop() }.exceptionOrNull()

        assertSoftly {
          failure.causes().any { it is BindException }.shouldBeTrue()
          // Releasing a dashboard that failed to start adds nothing: a FAILED service can't be stopped, and isn't.
          releaseFailures shouldBe emptyList()
          state shouldBe Service.State.FAILED
          agent.servletService.isRunning.shouldBeFalse()
          agent.metricsService.isRunning.shouldBeFalse()
          agent.zipkinReporterService.isRunning.shouldBeFalse()
          hook.shouldBeNull()
          adminPortFree.shouldBeTrue()
          metricsPortFree.shouldBeTrue()
          agent.grpcService.channel.isShutdown.shouldBeTrue()
          stopFailure.shouldBeNull()
        }
      }
    }

    // A release step that throws doesn't keep the others from running, and what it threw reaches the caller attached to
    // the startup failure, where a failed start's release once logged it at DEBUG. Here the dashboard fails again, on
    // the case above's ports, which that case shows are released.
    "a release step that throws after a failed start should be attached to the failure, and the rest should still run" {
      PrometheusRegistry.defaultRegistry.clear()
      mockkConstructor(AgentGrpcService::class)
      try {
        every { anyConstructed<AgentGrpcService>().shutDown() } throws IllegalStateException("gRPC shutdown failed")
        ServerSocket(AGENT_FAILED_DASHBOARD_TAKEN_PORT).use {
          val agent =
            createTestAgent(
              "--admin",
              "--admin_port",
              "$AGENT_FAILED_DASHBOARD_ADMIN_PORT",
              "--dashboard",
              "--dashboard_port",
              "$AGENT_FAILED_DASHBOARD_TAKEN_PORT",
            )

          val failure = shouldThrow<IllegalStateException> { agent.startSync() }

          // Captured as values first, so each check below reports on its own inside assertSoftly.
          val releaseFailures = failure.cause?.suppressed.orEmpty().map { it.message }
          val adminPortFree = canBind(AGENT_FAILED_DASHBOARD_ADMIN_PORT)
          assertSoftly {
            releaseFailures shouldBe listOf("gRPC shutdown failed")
            agent.servletService.isRunning.shouldBeFalse()
            adminPortFree.shouldBeTrue()
            agent.registeredShutDownHook().shouldBeNull()
          }
        }
      } finally {
        unmockkConstructor(AgentGrpcService::class)
      }
    }

    // The same failure as an embedded host meets it, with startAsyncAgent(Config): the host catches the
    // IllegalStateException, and the agent's admin port must be free at once, for the host's own use or a retry.
    "startAsyncAgent should release the admin port when the agent's dashboard fails to start" {
      PrometheusRegistry.defaultRegistry.clear()
      ServerSocket(EMBEDDED_FAILED_DASHBOARD_TAKEN_PORT).use {
        val config =
          ConfigFactory.parseString(
            """
            agent {
              admin { enabled = true, port = $EMBEDDED_FAILED_DASHBOARD_ADMIN_PORT }
              dashboard { enabled = true, port = $EMBEDDED_FAILED_DASHBOARD_TAKEN_PORT }
              pathConfigs = []
            }
            """.trimIndent(),
          )

        val failure = shouldThrow<IllegalStateException> { Agent.startAsyncAgent(config, logBanner = false) }

        assertSoftly {
          failure.causes().any { it is BindException }.shouldBeTrue()
          canBind(EMBEDDED_FAILED_DASHBOARD_ADMIN_PORT).shouldBeTrue()
        }
      }
    }

    // Guava leaves a start that outlasts its wait STARTING and runs it to the end, so an embedded agent whose start
    // timed out went on to connect and serve, with no handle to stop it and its thread holding up the host's JVM
    // (November 23 review, #12). Here the dashboard's start, mocked, blocks until the wait has given up.
    "an agent whose start times out should stop, releasing what it opened, as soon as its start ends" {
      PrometheusRegistry.defaultRegistry.clear()
      val startCanEnd = CountDownLatch(1)
      mockkConstructor(AgentDashboardService::class)
      try {
        every { anyConstructed<AgentDashboardService>().startSync(any<Duration>()) } answers { startCanEnd.await() }
        val agent =
          createTestAgent(
            "--admin",
            "--admin_port",
            "$AGENT_START_TIMEOUT_ADMIN_PORT",
            "--dashboard",
            "--dashboard_port",
            "$AGENT_START_TIMEOUT_DASHBOARD_PORT",
          )

        val failure = shouldThrow<IllegalStateException> { agent.startWithin(200.milliseconds) }
        val stateAtTimeout = agent.state()
        startCanEnd.countDown()
        eventually(10.seconds) { agent.state() shouldBe Service.State.TERMINATED }

        assertSoftly {
          failure.message.orEmpty() shouldContain "200ms"
          failure.cause.shouldBeInstanceOf<TimeoutException>()
          // Guava's name for a STARTING service asked to stop once its startUp() returns.
          stateAtTimeout shouldBe Service.State.STOPPING
          agent.servletService.isRunning.shouldBeFalse()
          canBind(AGENT_START_TIMEOUT_ADMIN_PORT).shouldBeTrue()
          agent.grpcService.channel.isShutdown.shouldBeTrue()
          // The run loop never began, so the agent never tried to connect.
          agent.isConnectedToProxy.shouldBeFalse()
        }
      } finally {
        startCanEnd.countDown()
        unmockkConstructor(AgentDashboardService::class)
      }
    }

    // An embedded agent's thread, which runs its startUp() and its connection loop, is a daemon, so it can't keep its
    // host's JVM up: a Ktor app whose port was taken failed its start after the plugin had started the agent, and ran
    // on, its agent connected (November 23 review, #10). An Agent started directly, as the standalone agent is, keeps a
    // thread that holds the JVM until it stops.
    "an embedded agent should run on a daemon thread, and one started directly shouldn't" {
      val embeddedName = "daemon-test-${System.nanoTime()}"
      val embedded =
        Agent.startEmbedded(exitOnMissingConfig = false, logBanner = false) {
          agentOptions(["--proxy", "localhost:1", "--name", embeddedName], exitOnMissingConfig = false)
        }
      val embeddedThread = embedded.use { serviceThread(embeddedName) }

      val direct = createTestAgent("--name", "direct-test-${System.nanoTime()}")
      direct.startSync()
      val directThread =
        try {
          serviceThread(direct.agentName)
        } finally {
          direct.stopSync()
        }

      assertSoftly {
        embeddedThread.isDaemon.shouldBeTrue()
        directThread.isDaemon.shouldBeFalse()
      }
    }

    // An agent whose start outlasts its wait stops once its startUp() returns, as the timeout case shows, but one that
    // never returns keeps its thread. An embedded agent's is a daemon, so even then it can't hold up its host's JVM
    // (November 23 review, #10 and #12). Here the dashboard's start, mocked, blocks until the case ends.
    "an embedded agent whose start never ends should hold no thread that keeps the JVM up" {
      PrometheusRegistry.defaultRegistry.clear()
      val startCanEnd = CountDownLatch(1)
      mockkConstructor(AgentDashboardService::class)
      try {
        every { anyConstructed<AgentDashboardService>().startSync(any<Duration>()) } answers { startCanEnd.await() }
        val name = "stuck-start-${System.nanoTime()}"
        val agent =
          createTestAgent("--name", name, "--dashboard", "--dashboard_port", "$AGENT_START_TIMEOUT_DASHBOARD_PORT")
            .apply { isEmbedded = true }

        shouldThrow<IllegalStateException> { agent.startWithin(200.milliseconds) }

        // Still in startUp()
        serviceThread(name).isDaemon.shouldBeTrue()
      } finally {
        startCanEnd.countDown()
        unmockkConstructor(AgentDashboardService::class)
      }
    }
  }
}
