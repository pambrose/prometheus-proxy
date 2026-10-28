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
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import org.slf4j.LoggerFactory

// Lincheck races the HTTP handler tracking a scrape against an answer for its scrapeId from an agent it wasn't sent
// to, which scrape IDs from one process-wide counter let any agent aim at (October #21). The owner answers and the
// handler untracks too. Checked after every run: only the agent the scrape was sent to ever completes it.
class ScrapeResultOwnershipLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "a non-owner's result never completes a scrape tracked concurrently, under model checking" {
      // A result for an untracked scrapeId logs a WARN, and Logback formatting its timestamp walks the JDK's locale
      // tables -- thousands of steps model checking traces and reports as a hang.
      withLoggingOff<ScrapeRequestManager> {
        ModelCheckingOptions()
          // Lincheck 3.7 throws an NPE in SnapshotTracker while minimizing a failure found from a Kotest coroutine,
          // hiding the failure itself; report the unminimized scenario instead.
          .minimizeFailedScenario(false)
          .iterations(ITERATIONS)
          .invocationsPerIteration(INVOCATIONS)
          .threads(THREADS)
          .actorsPerThread(ACTORS_PER_THREAD)
          .check(ScrapeResultOwnershipOps::class)
      }
    }

    "a non-owner's result never completes a scrape tracked concurrently, under stress" {
      StressOptions()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(ScrapeResultOwnershipOps::class)
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
    private const val ACTORS_PER_THREAD = 2
  }
}

class ScrapeResultOwnershipOps {
  private val manager = ScrapeRequestManager()
  private val owner = AgentContext("owner")
  private val intruder = AgentContext("intruder")

  // Built before any operation runs, so the intruder can name its scrapeId before the handler tracks it.
  private val scrape = ScrapeRequestWrapper(owner, "metrics", "", "", null, false)

  // ProxyHttpRoutes.dispatchScrapeRequest's admission.
  @Operation
  fun track() {
    manager.tryAddToScrapeRequestMap(scrape, MAX_IN_FLIGHT)
  }

  // dispatchScrapeRequest's finally.
  @Operation
  fun untrack() {
    manager.removeFromScrapeRequestMap(scrape.scrapeId)
  }

  // The owner's answer, through writeResponsesToProxy.
  @Operation
  fun answer() {
    respond(owner)
  }

  // An answer for the same scrapeId from a connected agent the scrape wasn't sent to.
  @Operation
  fun forge() {
    respond(intruder)
  }

  // writeResponsesToProxy's handling of one response on [agent]'s connection: the owner check runs on the wrapper the
  // result completes (ProxyServiceImpl.ownedByConnection, comparing the connection's agentId).
  private fun respond(agent: AgentContext) {
    val results =
      ScrapeResults(
        srAgentId = agent.agentId,
        srScrapeId = scrape.scrapeId,
        srValidResponse = true,
        srStatusCode = HttpStatusCode.OK.value,
      )
    manager.assignScrapeResults(results) { wrapper -> wrapper.agentContext.agentId == agent.agentId }
  }

  @Validate
  fun validate() {
    val completedBy = scrape.scrapeResults?.srAgentId
    check(completedBy == null || completedBy == owner.agentId) {
      "Scrape ${scrape.scrapeId}, sent to ${owner.agentId}, was completed by $completedBy"
    }
  }

  companion object {
    private const val MAX_IN_FLIGHT = 2
  }
}
