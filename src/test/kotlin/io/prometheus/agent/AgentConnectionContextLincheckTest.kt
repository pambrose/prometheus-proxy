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

import io.kotest.core.spec.style.StringSpec
import io.prometheus.common.Lincheck
import io.prometheus.common.ScrapeResults
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.Options
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

// Lincheck races AgentConnectionContext's two channels against close(), keeping the agent's scrape-request backlog
// count the way the agent does: readRequestsFromProxy counts a request before queueing it and gives the count back if
// the send throws, the scrape loop gives it back once it has taken the request, and whoever closes the connection
// gives back what close() drained. The count here, unlike Agent.decrementBacklog, is not clamped at zero, so drift
// shows: it must never go negative, and must be zero once the connection is closed. Every scrape result accepted
// before the close must still be readable after it.
//
// Lincheck's random scenarios rarely leave a request queued, and untaken, when the connection closes, so the races
// that matter are also given as custom scenarios: close() draining a queued request, and a sender suspended on a full
// channel as the connection closes.
class AgentConnectionContextLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "connection context keeps the backlog count and results under model checking" {
      ModelCheckingOptions()
        // Lincheck 3.7 throws an NPE in SnapshotTracker while minimizing a failure found from a Kotest coroutine,
        // hiding the failure itself; report the unminimized scenario instead.
        .minimizeFailedScenario(false)
        .withConnectionRaces()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(AgentConnectionContextOps::class)
    }

    "connection context keeps the backlog count and results under stress" {
      StressOptions()
        .withConnectionRaces()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(AgentConnectionContextOps::class)
    }
  }

  companion object {
    private fun <O : Options<O, *>> O.withConnectionRaces(): O =
      addCustomScenario {
        // close() drains a request nobody took.
        parallel {
          thread {
            actor(AgentConnectionContextOps::queueRequest)
            actor(AgentConnectionContextOps::closeConnection)
          }
        }
      }.addCustomScenario {
        // The third request suspends on the full channel as the scrape loop and both closers race it.
        parallel {
          thread {
            actor(AgentConnectionContextOps::queueRequest)
            actor(AgentConnectionContextOps::queueRequest)
            actor(AgentConnectionContextOps::queueRequest)
          }
          thread {
            actor(AgentConnectionContextOps::takeRequest)
            actor(AgentConnectionContextOps::closeConnection)
          }
          thread {
            actor(AgentConnectionContextOps::sendResult)
            actor(AgentConnectionContextOps::closeConnection)
          }
        }
      }

    private const val ITERATIONS = 30
    private const val INVOCATIONS = 300
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 3
  }
}

class AgentConnectionContextOps {
  // A small backlog, so a queueing request can find the channel full and suspend.
  private val context = AgentConnectionContext(backlogCapacity = 2)

  // Agent.scrapeRequestBacklogSize.
  private val backlog = AtomicInteger(0)
  private val resultsAccepted = AtomicInteger(0)
  private val resultsRead = AtomicInteger(0)

  @Volatile
  private var closed = false

  // Lincheck records an exception thrown by an operation as its result, not as a failure, so operations note what
  // they saw go wrong and validateAccounting reports it.
  private val violations = ConcurrentLinkedQueue<String>()

  // AgentGrpcService.readRequestsFromProxy. Cancelled while suspended on a full channel, the send may still have
  // delivered the request (the prompt cancellation guarantee), which is the case promptCancellation explores.
  @Operation(promptCancellation = true)
  suspend fun queueRequest() {
    backlog.incrementAndGet()
    try {
      context.sendScrapeRequestAction { ScrapeResults(srAgentId = "agent", srScrapeId = 0) }
    } catch (e: Exception) {
      backlog.decrementAndGet()
      throw e
    }
    checkBacklog()
  }

  // The agent's scrape loop takes a request, runs it, and gives back its count.
  @Operation
  suspend fun takeRequest() {
    if (context.scrapeRequestActions().receiveCatching().isSuccess) {
      backlog.decrementAndGet()
      checkBacklog()
    }
  }

  // A finished scrape offers its result; the write stream reads results until the channel is closed and empty.
  @Operation
  fun sendResult() {
    if (context.sendScrapeResults(ScrapeResults(srAgentId = "agent", srScrapeId = 0)))
      resultsAccepted.incrementAndGet()
  }

  @Operation
  fun readResult() {
    if (context.scrapeResults().tryReceive().isSuccess)
      resultsRead.incrementAndGet()
  }

  // Both launchConnectionTask's completion and the write stream's shutdown close the context; only the first drains.
  @Operation
  fun closeConnection() {
    val drained = context.close()
    backlog.addAndGet(-drained)
    closed = true
    checkBacklog()
  }

  private fun checkBacklog() {
    val count = backlog.get()
    if (count < 0)
      violations += "Backlog count went negative: $count"
  }

  @Validate
  fun validateAccounting() {
    checkBacklog()
    check(violations.isEmpty()) { violations.joinToString("; ") }
    if (closed)
      check(backlog.get() == 0) { "Backlog count is ${backlog.get()} after the connection closed" }

    // Read what's left, as the write stream would; closing must not discard results already accepted.
    while (context.scrapeResults().tryReceive().isSuccess) {
      resultsRead.incrementAndGet()
    }
    check(resultsRead.get() == resultsAccepted.get()) {
      "${resultsAccepted.get()} results accepted, but only ${resultsRead.get()} readable"
    }
  }
}
