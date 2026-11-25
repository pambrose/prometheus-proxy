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

package io.prometheus.agent

import com.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.Agent
import io.prometheus.common.ScrapeRequestAction
import io.prometheus.common.ScrapeResults
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlin.concurrent.atomics.AtomicReference

/**
 * Bidirectional channel pair for a single agent-to-proxy connection.
 *
 * Holds two coroutine channels: one for inbound scrape request actions (bounded by
 * [backlogCapacity]) and one for outbound scrape results (unbounded). Manages the
 * connection's disconnected state and provides a [close] method that drains pending
 * requests and closes both channels so waiting coroutines are notified.
 *
 * @param backlogCapacity maximum number of buffered scrape request actions
 * @param onUndeliveredResult what becomes of a result the connection can't send; see [dropUnsentResults]
 * @see AgentGrpcService
 * @see io.prometheus.Agent
 */
internal class AgentConnectionContext(
  // No default: the sole production caller derives this from config, and a default would silently
  // diverge if a future call site forgot to pass it (finding 31).
  val backlogCapacity: Int,
  // The agent counts each one as dropped (newConnectionContext). Tests leave it out.
  onUndeliveredResult: (ScrapeResults) -> Unit = {},
) {
  /** When the connection began, for the agent's dashboard. */
  val startedAt: Instant = Clock.System.now()

  private val firstFailure = AtomicReference<Throwable?>(null)

  /**
   * The first failure of one of the connection's tasks, which ended it, for the agent dashboard's activity feed; null
   * while none has failed, and for a connection that ended without a failure. The tasks cancelled after it don't count.
   */
  val endedBy: Throwable? get() = firstFailure.load()

  /** Notes [e], a connection task's failure, as what ended the connection, unless one came first. */
  fun noteFailure(e: Throwable) {
    // False when a failure came first, which keeps it.
    val _ = firstFailure.compareAndSet(null, e)
  }

  private var disconnected by atomicBoolean(false)
  private val scrapeRequestActionsChannel = Channel<ScrapeRequestAction>(backlogCapacity)

  // Hands onUndeliveredResult each result dropUnsentResults() finds still queued, and one the results' reader took just
  // as it was cancelled, which kotlinx's prompt cancellation would otherwise lose. Not one sendScrapeResults offers
  // after close(): trySend calls no onUndeliveredElement, and its caller counts the result itself.
  private val scrapeResultsChannel = Channel<ScrapeResults>(UNLIMITED, onUndeliveredElement = onUndeliveredResult)
  private val closeLock = Any()

  fun scrapeRequestActions() = scrapeRequestActionsChannel

  fun scrapeResults() = scrapeResultsChannel

  suspend fun sendScrapeRequestAction(scrapeRequestAction: ScrapeRequestAction) {
    scrapeRequestActionsChannel.send(scrapeRequestAction)
  }

  /**
   * Offers a computed [scrapeResults] to the outbound channel.
   *
   * Uses `trySend()` instead of `send()` to avoid `ClosedSendChannelException` when the connection
   * is closed while in-flight scrape coroutines are still completing. For an UNLIMITED channel,
   * `trySend()` succeeds immediately while the channel is open. If the channel is closed (a
   * disconnect mid-scrape), the result is dropped with a warning rather than throwing, which would
   * otherwise cancel sibling scrape coroutines.
   *
   * @return `true` if the result was accepted into the channel, `false` if it was dropped because
   *   the connection had already closed. Callers can use the return value to record a metric.
   */
  fun sendScrapeResults(scrapeResults: ScrapeResults): Boolean {
    val result = scrapeResultsChannel.trySend(scrapeResults)
    if (result.isClosed) {
      logger.warn { "Scrape result for scrapeId ${scrapeResults.srScrapeId} dropped: connection closed" }
    }
    return result.isSuccess
  }

  fun close(): Int {
    synchronized(closeLock) {
      return if (!disconnected) {
        disconnected = true
        // Close (not cancel) and drain the scrape request actions channel so that
        // callers can adjust scrapeRequestBacklogSize by the drained count.
        scrapeRequestActionsChannel.close()
        val drained = generateSequence { scrapeRequestActionsChannel.tryReceive().getOrNull() }.count()
        // Use close() instead of cancel() so buffered results can still be drained
        // by the consumer. cancel() discards all buffered items, causing the proxy
        // to time out waiting for results that were already computed.
        scrapeResultsChannel.close()
        logger.info { "AgentConnectionContext closed (drained $drained pending scrape requests)" }
        drained
      } else {
        0
      }
    }
  }

  /**
   * Drops the results still queued, which nothing will send, handing each to `onUndeliveredResult`, which counts it as
   * dropped. For the results' one reader, once it stops: after [close], so no result is queued after them, since a
   * scrape that finishes later finds the channel closed and counts its own result as dropped. Results the reader left
   * queued when a writer's stream failed were counted neither as sent nor as dropped (November #6).
   */
  fun dropUnsentResults() = scrapeResultsChannel.cancel()

  val connected get() = !disconnected

  companion object {
    private val logger = logger {}
  }
}

// The connection's scrape-request channel is sized at 2x the backlog-unhealthy threshold so the
// health check can fire (backlog >= threshold) before the channel actually blocks senders (finding 28).
private const val BACKLOG_CAPACITY_MULTIPLIER = 2

/** A connection's channels for this agent, which count a result the connection can't send as dropped (November #6). */
internal fun Agent.newConnectionContext() =
  AgentConnectionContext(
    backlogCapacity = agentConfigVals.internal.scrapeRequestBacklogUnhealthySize * BACKLOG_CAPACITY_MULTIPLIER,
    onUndeliveredResult = { updateScrapeResultCounter(it, AgentMetrics.RESULT_DROPPED) },
  )
