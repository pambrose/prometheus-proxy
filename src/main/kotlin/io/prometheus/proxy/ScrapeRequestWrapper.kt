/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

import com.github.pambrose.common.delegate.AtomicDelegates.nonNullableReference
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.util.isNotNull
import io.prometheus.Proxy
import io.prometheus.common.GrpcObjects.newScrapeRequest
import io.prometheus.common.ScrapeResults
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.TimeSource.Monotonic

internal class ScrapeRequestWrapper(
  val agentContext: AgentContext,
  proxy: Proxy,
  path: String,
  encodedQueryParams: String,
  authHeader: String,
  accept: String?,
  debugEnabled: Boolean
) {
  private val clock = Monotonic
  private val createTimeMark = clock.markNow()
  private val completeChannel = Channel<Boolean>()
  private val requestTimer = if (proxy.isMetricsEnabled) proxy.metrics.scrapeRequestLatency.startTimer() else null

  val scrapeRequest =
    newScrapeRequest(
      agentContext.agentId,
      SCRAPE_ID_GENERATOR.getAndIncrement(),
      path,
      encodedQueryParams,
      authHeader,
      accept,
      debugEnabled
    )

  var scrapeResults: ScrapeResults by nonNullableReference()

  val scrapeId: Long
    get() = scrapeRequest.scrapeId

  fun ageDuration() = createTimeMark.elapsedNow()

  fun markComplete() {
    requestTimer?.observeDuration()
    completeChannel.close()
  }

  suspend fun suspendUntilComplete(waitMillis: Duration) =
    withTimeoutOrNull(waitMillis.inWholeMilliseconds) {
      // completeChannel will eventually close and never get a value, or timeout
      try {
        completeChannel.receive()
        true
      } catch (e: ClosedReceiveChannelException) {
        true
      }
    }.isNotNull()

  override fun toString() =
    toStringElements {
      add("scrapeId", scrapeRequest.scrapeId)
      add("path", scrapeRequest.path)
    }

  companion object {
    private val SCRAPE_ID_GENERATOR = AtomicLong(0L)
  }
}