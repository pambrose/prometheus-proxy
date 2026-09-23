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

import io.kotest.core.spec.style.StringSpec
import io.prometheus.common.Lincheck
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.lincheck.datastructures.ModelCheckingOptions
import org.jetbrains.lincheck.datastructures.Operation
import org.jetbrains.lincheck.datastructures.StressOptions
import org.jetbrains.lincheck.datastructures.Validate
import java.util.Collections
import java.util.IdentityHashMap

// Lincheck drives AgentContext's scrape-request hand-off -- writeScrapeRequest, readScrapeRequest, and invalidate --
// from several threads, exploring interleavings (model checking) or racing them for real (stress).
//
// write() returns its outcome, and Lincheck checks each run's outcomes against some sequential order of the same calls.
// That caught writers failing on a closed context pushing a concurrent writer over maxBacklog, which then reported
// backlog_full for an agent that had disconnected (invalidate(); then write() x3 in parallel -> closed, closed, full).
//
// read() and invalidate() return nothing: invalidate() closes the notifier before it drains the queue, so a reader
// already waiting can take one more request in between, which no sequential order allows. That is benign --
// removeAgentContext's failAllScrapeRequests fails such a request if the agent never answers it -- so instead of
// linearizability, validateAccounting checks after every run that each accepted request is exactly one of: read by the
// agent's stream, completed, or still queued -- and, once invalidate() has returned and every writer is done, never
// still queued, where an HTTP handler would wait on it until the scrape timeout.
class AgentContextLincheckTest : StringSpec() {
  init {
    tags(Lincheck)

    "scrape request hand-off strands nothing under model checking" {
      ModelCheckingOptions()
        // Lincheck 3.7 throws an NPE in SnapshotTracker while minimizing a failure found from a Kotest coroutine,
        // hiding the failure itself; report the unminimized scenario instead.
        .minimizeFailedScenario(false)
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(AgentContextOps::class)
    }

    "scrape request hand-off strands nothing under stress" {
      StressOptions()
        .iterations(ITERATIONS)
        .invocationsPerIteration(INVOCATIONS)
        .threads(THREADS)
        .actorsPerThread(ACTORS_PER_THREAD)
        .check(AgentContextOps::class)
    }
  }

  companion object {
    private const val ITERATIONS = 30
    private const val INVOCATIONS = 300
    private const val THREADS = 3
    private const val ACTORS_PER_THREAD = 3
  }
}

// One Lincheck scenario runs against a fresh instance. Operations return only values that don't depend on the global
// agentId and scrapeId counters, so every run of a scenario is comparable.
class AgentContextOps {
  private val context = AgentContext("lincheck")

  // Wrappers each outcome applies to, by identity; guarded by their own monitor.
  private val accepted: MutableSet<ScrapeRequestWrapper> = Collections.newSetFromMap(IdentityHashMap())
  private val read: MutableSet<ScrapeRequestWrapper> = Collections.newSetFromMap(IdentityHashMap())

  @Volatile
  private var invalidated = false

  // Returns "queued", "full", or "closed", which dispatchScrapeRequest reports as a queued request, backlog_full, and
  // agent_disconnected.
  @Operation
  suspend fun write(): String {
    val wrapper = ScrapeRequestWrapper(context, "metrics", "", "", null, false)
    return try {
      if (context.writeScrapeRequest(wrapper, MAX_BACKLOG)) {
        synchronized(accepted) { accepted += wrapper }
        "queued"
      } else {
        "full"
      }
    } catch (_: ClosedSendChannelException) {
      "closed"
    }
  }

  // Suspends until a request is signalled, as the readRequestsFromProxy loop does.
  @Operation(cancellableOnSuspension = true)
  suspend fun read() {
    context.readScrapeRequest()?.also { wrapper -> synchronized(accepted) { read += wrapper } }
  }

  @Operation
  fun invalidate() {
    context.invalidate()
    invalidated = true
  }

  @Validate
  fun validateAccounting() {
    val (acceptedNow, readNow) = synchronized(accepted) { accepted.toList() to read.toList() }
    val readSet = Collections.newSetFromMap(IdentityHashMap<ScrapeRequestWrapper, Boolean>()).apply { addAll(readNow) }
    // Accepted, never handed to the agent, never failed: still in the queue, or lost.
    val pending = acceptedNow.filter { it !in readSet && it.scrapeResults == null }
    // A request is handed to the agent or failed, never both.
    val readAndFailed = readNow.count { it.scrapeResults != null }
    check(readAndFailed == 0) { "$readAndFailed requests were both read and failed" }

    check(pending.size == context.scrapeRequestBacklogSize) {
      "${pending.size} accepted requests are neither read nor completed, but the backlog holds " +
        "${context.scrapeRequestBacklogSize}"
    }
    check(context.scrapeRequestBacklogSize <= MAX_BACKLOG) {
      "Backlog ${context.scrapeRequestBacklogSize} is over the cap of $MAX_BACKLOG"
    }
    if (invalidated)
      check(pending.isEmpty()) { "${pending.size} requests stranded after invalidate()" }
  }

  companion object {
    private const val MAX_BACKLOG = 2
  }
}
