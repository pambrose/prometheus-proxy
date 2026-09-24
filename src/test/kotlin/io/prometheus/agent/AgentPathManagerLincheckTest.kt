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
import ch.qos.logback.classic.Logger
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.discovery.DiscoveredPath
import io.prometheus.common.Lincheck
import io.prometheus.common.TestPorts.PATH_MANAGER_LINCHECK_HTTP_PORT
import io.prometheus.harness.support.TestUtils
import io.prometheus.harness.support.TestUtils.stopAll
import io.prometheus.proxy.ProxyPathManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.lincheck.datastructures.IntGen
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// Lincheck races AgentPathManager's path changes -- registering a static path, unregistering a path, and reconciling
// the discovered paths against a desired set -- against each other, over a real gRPC connection to a proxy. All three
// run under one Mutex, so no interleaving should let the agent's local path map and the proxy's registrations drift
// apart: once every operation has finished, validate checks that exactly the paths the agent maps locally are the
// paths the proxy has registered for it. A local entry the proxy doesn't hold is a path that 404s; a registration the
// agent has forgotten is one it never unregisters -- the stuck-path bugs fixed in 4.1.0.
//
// clear() is left out: on a reconnect it runs after the proxy has already dropped the old connection's paths, so on a
// live connection it would leave the proxy holding paths the agent forgot, by design.
//
// One proxy and agent, connected in-process, serve every run: starting a pair per run would take longer than the
// runs. Each run uses its own path names and unregisters them afterwards, so the agent stays well under the proxy's
// per-agent path limit. No MockK (see HttpClientCacheLincheckTest), and stress only: a gRPC round trip is more than
// model checking can replay. The operations block, as the cache spec's do, since Lincheck's stress runner loses track
// of an operation suspended on a Mutex.
class AgentPathManagerLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "agent and proxy agree on the agent's paths under stress" {
      val proxy = TestUtils.startProxy(serverName = SERVER_NAME, proxyPort = PATH_MANAGER_LINCHECK_HTTP_PORT)
      val agent = TestUtils.startAgent(serverName = SERVER_NAME)
      try {
        agent.awaitInitialConnection(10.seconds).shouldBeTrue()
        AgentPathManagerFixture.proxy = proxy
        AgentPathManagerFixture.agent = agent
        // Rejected unregisters and path changes log at WARN and INFO on every one of thousands of runs.
        withLoggingOff(AgentPathManager::class.java, ProxyPathManager::class.java) {
          StressOptions()
            .iterations(ITERATIONS)
            .invocationsPerIteration(INVOCATIONS)
            .threads(THREADS)
            .actorsPerThread(ACTORS_PER_THREAD)
            .check(AgentPathManagerOps::class)
        }
      } finally {
        stopAll(proxy, agent)
      }
    }
  }

  companion object {
    private fun withLoggingOff(
      vararg classes: Class<*>,
      block: () -> Unit,
    ) {
      val loggers = classes.map { LoggerFactory.getLogger(it) as Logger }
      val levels = loggers.map { it.level }
      loggers.forEach { it.level = Level.OFF }
      try {
        block()
      } finally {
        loggers.zip(levels).forEach { (logger, level) -> logger.level = level }
      }
    }

    private const val SERVER_NAME = "agent-path-manager-lincheck"
    private const val ITERATIONS = 20
    private const val INVOCATIONS = 200
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 3
  }
}

// The proxy and agent every run shares; set before Lincheck starts.
object AgentPathManagerFixture {
  @Volatile
  lateinit var proxy: Proxy

  @Volatile
  lateinit var agent: Agent
}

@Param(name = "path", gen = IntGen::class, conf = "0:2")
@Param(name = "desired", gen = IntGen::class, conf = "0:3")
class AgentPathManagerOps {
  private val agent = AgentPathManagerFixture.agent
  private val proxyPaths = AgentPathManagerFixture.proxy.pathManager
  private val pathManager = AgentPathManager(agent)
  private val paths = List(PATH_COUNT) { "apm_lincheck_${RUN_IDS.incrementAndGet()}_$it" }

  // The discovery file's contents a reconcile may see: two overlapping sets, one path at a changed URL (a
  // re-registration), and an empty file.
  private val desiredSets =
    listOf(
      listOf(discovered(0, URL_A), discovered(1, URL_A)),
      listOf(discovered(1, URL_A), discovered(2, URL_A)),
      listOf(discovered(1, URL_B)),
      listOf(),
    )

  private fun discovered(
    path: Int,
    url: String,
  ) = DiscoveredPath(name = paths[path], path = paths[path], url = url, labels = "{}")

  @Operation(blocking = true)
  fun registerStatic(
    @Param(name = "path") path: Int,
  ) = runBlocking { pathManager.registerPath(paths[path], STATIC_URL) }

  @Operation(blocking = true)
  fun unregister(
    @Param(name = "path") path: Int,
  ) = runBlocking { pathManager.unregisterPath(paths[path]) }

  // PathDiscoveryService, after reading the discovery file.
  @Operation(blocking = true)
  fun reconcile(
    @Param(name = "desired") desired: Int,
  ) = runBlocking { pathManager.reconcileDiscoveredPaths(desiredSets[desired]) }

  @Validate
  fun validate() {
    val local = paths.filter { pathManager[it] != null }
    val onProxy =
      paths.filter { path ->
        proxyPaths.getAgentContextInfo(path)?.agentContexts.orEmpty().any { it.agentId == agent.agentId }
      }
    check(local == onProxy) { "The agent maps $local locally, but the proxy has $onProxy registered for it" }

    // Leave nothing behind on the shared proxy.
    runBlocking { local.forEach { pathManager.unregisterPath(it) } }
  }

  companion object {
    private const val PATH_COUNT = 3
    private const val STATIC_URL = "http://localhost:9/static"
    private const val URL_A = "http://localhost:9/a"
    private const val URL_B = "http://localhost:9/b"
    private val RUN_IDS = AtomicInteger()
  }
}
