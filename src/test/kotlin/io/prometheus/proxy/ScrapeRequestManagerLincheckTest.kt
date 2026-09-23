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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.kotest.core.spec.style.StringSpec
import io.ktor.http.HttpStatusCode
import io.prometheus.common.Lincheck
import io.prometheus.common.ScrapeResults
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Param
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration

// Lincheck races the proxy's in-flight scrape tracking: HTTP handlers admitting requests under the in-flight limit and
// untracking them when done, against an agent's answer, a single failure, and failAllScrapeRequests on disconnect, all
// completing the same requests. Checked after every step or run:
//  - the tracked-request map never exceeds the limit, and the limit's counter agrees with the map once quiet;
//  - each completed request carries one completer's result and matching proxyFailure, never a mix of two;
//  - failAllScrapeRequests completes only the disconnecting agent's requests.
class ScrapeRequestManagerLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "scrape tracking keeps the in-flight limit and one completion per request under model checking" {
      // An answer or failure for a request its handler already untracked logs a WARN, and Logback formatting its
      // timestamp walks the JDK's locale tables -- thousands of steps model checking traces and reports as a hang.
      withLoggingOff<ScrapeRequestManager> {
        ModelCheckingOptions()
          // Lincheck 3.7 throws an NPE in SnapshotTracker while minimizing a failure found from a Kotest coroutine,
          // hiding the failure itself; report the unminimized scenario instead.
          .minimizeFailedScenario(false)
          .iterations(ITERATIONS)
          .invocationsPerIteration(INVOCATIONS)
          .threads(THREADS)
          .actorsPerThread(ACTORS_PER_THREAD)
          .check(ScrapeRequestManagerOps::class)
      }
    }

    "scrape tracking keeps the in-flight limit and one completion per request under stress" {
      StressOptions()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(ScrapeRequestManagerOps::class)
    }
  }

  companion object {
    private inline fun <reified T : Any> withLoggingOff(block: () -> Unit) {
      val logger = LoggerFactory.getLogger(T::class.java) as Logger
      val level = logger.level
      logger.level = Level.OFF
      try {
        block()
      } finally {
        logger.level = level
      }
    }

    private const val ITERATIONS = 30
    private const val INVOCATIONS = 300
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 3
  }
}

// Operations pick a request by its index in admission order; an index not yet admitted is a no-op.
@Param(name = "request", gen = IntGen::class, conf = "0:3")
class ScrapeRequestManagerOps {
  private val manager = ScrapeRequestManager()
  private val agentA = AgentContext("agent-a")
  private val agentB = AgentContext("agent-b")

  // Requests admitted, in order, and the ones whose handler has finished.
  private val admitted = CopyOnWriteArrayList<ScrapeRequestWrapper>()
  private val finished = ConcurrentHashMap.newKeySet<ScrapeRequestWrapper>()

  // Lincheck records an exception thrown by an operation as its result, not as a failure, so operations note what
  // they saw go wrong and validate reports it.
  private val violations = ConcurrentLinkedQueue<String>()

  // ProxyHttpRoutes.dispatchScrapeRequest's admission.
  @Operation
  fun admit(toAgentB: Boolean) {
    val wrapper = newWrapper(if (toAgentB) agentB else agentA)
    if (manager.tryAddToScrapeRequestMap(wrapper, MAX_IN_FLIGHT))
      admitted += wrapper
    checkLimit()
  }

  // dispatchScrapeRequest's finally: the handler has its result or gave up, and untracks the request.
  @Operation
  suspend fun finish(
    @Param(name = "request") index: Int,
  ) {
    val wrapper = admitted.getOrNull(index) ?: return
    if (!finished.add(wrapper)) return
    wrapper.awaitCompleted(Duration.ZERO)
    wrapper.closeChannel()
    manager.removeFromScrapeRequestMap(wrapper.scrapeId)
    checkLimit()
  }

  // The agent's answer arriving through writeResponsesToProxy.
  @Operation
  fun answer(
    @Param(name = "request") index: Int,
  ) {
    val wrapper = admitted.getOrNull(index) ?: return
    manager.assignScrapeResults(
      ScrapeResults(
        srAgentId = wrapper.agentContext.agentId,
        srScrapeId = wrapper.scrapeId,
        srValidResponse = true,
        srStatusCode = HttpStatusCode.OK.value,
      ),
    )
  }

  // One request failed by the proxy, as a cancelled readRequestsFromProxy does.
  @Operation
  fun fail(
    @Param(name = "request") index: Int,
  ) {
    val wrapper = admitted.getOrNull(index) ?: return
    manager.failScrapeRequest(wrapper.scrapeId, FAIL_ONE, ProxyFailure.AGENT_DISCONNECTED)
  }

  // Proxy.removeAgentContext for agent A.
  @Operation
  fun disconnectAgentA() {
    manager.failAllScrapeRequests(agentA.agentId, FAIL_ALL, ProxyFailure.AGENT_DISCONNECTED)
  }

  private fun newWrapper(agent: AgentContext) = ScrapeRequestWrapper(agent, "metrics", "", "", null, false)

  private fun checkLimit() {
    val size = manager.scrapeMapSize
    if (size > MAX_IN_FLIGHT)
      violations += "$size requests tracked, over the in-flight limit of $MAX_IN_FLIGHT"
  }

  @Validate
  fun validate() {
    checkLimit()
    check(violations.isEmpty()) { violations.joinToString("; ") }

    admitted.forEach { wrapper ->
      val results = wrapper.scrapeResults
      val failure = wrapper.proxyFailure
      if (results == null) {
        check(failure == null) { "Request ${wrapper.scrapeId} has a proxyFailure but no results" }
        return@forEach
      }
      if (failure == null) {
        check(results.srStatusCode == HttpStatusCode.OK.value && results.srFailureReason.isEmpty()) {
          "Request ${wrapper.scrapeId} has the proxy's failure results without its proxyFailure"
        }
      } else {
        check(results.srStatusCode == failure.statusCode.value && results.srFailureReason in [FAIL_ONE, FAIL_ALL]) {
          "Request ${wrapper.scrapeId} has proxyFailure $failure with results from another completion"
        }
      }
      if (results.srFailureReason == FAIL_ALL)
        check(wrapper.agentContext === agentA) { "Disconnecting agent A failed agent B's request ${wrapper.scrapeId}" }
    }

    // The limit's counter must agree with the map: exactly the free slots admit a new request.
    val free = MAX_IN_FLIGHT - manager.scrapeMapSize
    val extra = generateSequence { newWrapper(agentB).takeIf { manager.tryAddToScrapeRequestMap(it, MAX_IN_FLIGHT) } }
      .take(MAX_IN_FLIGHT + 1)
      .toList()
    extra.forEach { manager.removeFromScrapeRequestMap(it.scrapeId) }
    check(extra.size == free) { "${extra.size} requests admitted into $free free in-flight slots" }
  }

  companion object {
    private const val MAX_IN_FLIGHT = 2
    private const val FAIL_ONE = "failed"
    private const val FAIL_ALL = "agent disconnected"
  }
}
