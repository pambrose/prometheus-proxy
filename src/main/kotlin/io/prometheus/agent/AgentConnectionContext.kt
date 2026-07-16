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
import io.prometheus.common.ScrapeRequestAction
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED

/**
 * Bidirectional channel pair for a single agent-to-proxy connection.
 *
 * Holds two coroutine channels: one for inbound scrape request actions (bounded by
 * [backlogCapacity]) and one for outbound scrape results (unbounded). Manages the
 * connection's disconnected state and provides a [close] method that drains pending
 * requests and closes both channels so waiting coroutines are notified.
 *
 * @param backlogCapacity maximum number of buffered scrape request actions
 * @see AgentGrpcService
 * @see io.prometheus.Agent
 */
internal class AgentConnectionContext(
  // No default: the sole production caller derives this from config, and a default would silently
  // diverge if a future call site forgot to pass it (finding 31).
  val backlogCapacity: Int,
) {
  private var disconnected by atomicBoolean(false)
  private val scrapeRequestActionsChannel = Channel<ScrapeRequestAction>(backlogCapacity)
  private val scrapeResultsChannel = Channel<ScrapeResults>(UNLIMITED)
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

  val connected get() = !disconnected

  companion object {
    private val logger = logger {}
  }
}
