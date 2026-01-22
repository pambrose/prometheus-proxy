/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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
import io.ktor.utils.io.core.Closeable
import io.prometheus.common.ScrapeRequestAction
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.consumeAsFlow

internal class AgentConnectionContext : Closeable {
  private var disconnected by atomicBoolean(false)
  private val scrapeRequestActionsChannel = Channel<ScrapeRequestAction>(UNLIMITED)
  private val scrapeResultsChannel = Channel<ScrapeResults>(UNLIMITED)

  fun scrapeRequestActionsFlow() = scrapeRequestActionsChannel.consumeAsFlow()

  fun scrapeResultsFlow() = scrapeResultsChannel.consumeAsFlow()

  suspend fun sendScrapeRequestAction(scrapeRequestAction: ScrapeRequestAction) {
    scrapeRequestActionsChannel.send(scrapeRequestAction)
  }

  suspend fun sendScrapeResults(scrapeResults: ScrapeResults) {
    scrapeResultsChannel.send(scrapeResults)
  }

  override fun close() {
    disconnected = true
    scrapeRequestActionsChannel.cancel()
    scrapeResultsChannel.cancel()
  }

  val connected get() = !disconnected
}
