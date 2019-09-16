/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.ktor.util.KtorExperimentalAPI
import io.prometheus.Proxy
import io.prometheus.common.GrpcObjects.Companion.newScrapeRequest
import io.prometheus.delegate.AtomicDelegates.nonNullableReference
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.grpc.ScrapeResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.MonoClock

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
@UseExperimental(ExperimentalTime::class)
class ScrapeRequestWrapper(proxy: Proxy,
                           val agentContext: AgentContext,
                           path: String,
                           accept: String?) {
    private val clock = MonoClock
    private val createTimeMark = clock.markNow()
    private val completeChannel = Channel<Boolean>()
    private val requestTimer = if (proxy.isMetricsEnabled) proxy.metrics.scrapeRequestLatency.startTimer() else null

    val scrapeRequest = newScrapeRequest(agentContext.agentId, SCRAPE_ID_GENERATOR.getAndIncrement(), path, accept)
    var scrapeResponse by nonNullableReference<ScrapeResponse>()

    val scrapeId: Long
        get() = scrapeRequest.scrapeId

    fun ageDuration() = createTimeMark.elapsedNow()

    fun markComplete() {
        requestTimer?.observeDuration()
        completeChannel.close()
    }

    suspend fun suspendUntilComplete(waitMillis: Duration) =
        withTimeoutOrNull(waitMillis.toLongMilliseconds()) {
            // completeChannel will eventually close and never get a value, or timeout
            try {
                completeChannel.receive()
                true
            } catch (e: ClosedReceiveChannelException) {
                true
            }
        } != null

    override fun toString() =
        toStringElements {
            add("scrapeId", scrapeRequest.scrapeId)
            add("path", scrapeRequest.path)
        }

    companion object {
        private val SCRAPE_ID_GENERATOR = AtomicLong(0)
    }
}
