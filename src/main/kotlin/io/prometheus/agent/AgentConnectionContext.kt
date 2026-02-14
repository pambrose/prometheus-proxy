/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.delegate.AtomicDelegates.atomicBoolean
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.ScrapeRequestAction
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED

internal class AgentConnectionContext(
  val backlogCapacity: Int = 128,
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

  fun sendScrapeResults(scrapeResults: ScrapeResults) {
    // Use trySend() instead of send() to avoid ClosedSendChannelException when the
    // connection is closed while in-flight scrape coroutines are still completing.
    // For an UNLIMITED channel, trySend() always succeeds immediately if open.
    // If the channel is closed (disconnect), we log a warning instead of throwing,
    // which prevents the exception from cancelling sibling scrape coroutines.
    val result = scrapeResultsChannel.trySend(scrapeResults)
    if (result.isClosed) {
      logger.warn { "Scrape result for scrapeId ${scrapeResults.srScrapeId} dropped: connection closed" }
    }
  }

  fun close() {
    synchronized(closeLock) {
      if (!disconnected) {
        disconnected = true
        scrapeRequestActionsChannel.cancel()
        // Use close() instead of cancel() so buffered results can still be drained
        // by the consumer. cancel() discards all buffered items, causing the proxy
        // to time out waiting for results that were already computed.
        scrapeResultsChannel.close()
        logger.info { "AgentConnectionContext closed" }
      }
    }
  }

  val connected get() = !disconnected

  companion object {
    private val logger = logger {}
  }
}
