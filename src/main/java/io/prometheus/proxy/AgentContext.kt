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
import io.prometheus.common.Secs
import io.prometheus.common.now
import io.prometheus.delegate.AtomicDelegates.atomicMillis
import io.prometheus.delegate.AtomicDelegates.nonNullableReference
import io.prometheus.dsl.GuavaDsl.toStringElements
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
class AgentContext(proxy: Proxy, private val remoteAddr: String) {

    val agentId = AGENT_ID_GENERATOR.incrementAndGet().toString()

    private val channelSize = proxy.configVals.scrapeRequestChannelSize
    private val scrapeRequestChannel = Channel<ScrapeRequestWrapper>(channelSize)
    private val channelBacklogSize = AtomicInteger(0)

    private var lastActivityTime by atomicMillis()
    private var valid = AtomicBoolean(true)

    var hostName: String by nonNullableReference()
    var agentName: String by nonNullableReference()

    val inactivitySecs: Secs
        get() = (now() - lastActivityTime).toSecs()

    val scrapeRequestBacklogSize: Int
        get() = channelBacklogSize.get()

    init {
        hostName = "Unassigned"
        agentName = "Unassigned"
        markActivity()
    }

    suspend fun writeScrapeRequest(scrapeRequest: ScrapeRequestWrapper) {
        scrapeRequestChannel.send(scrapeRequest)
        channelBacklogSize.incrementAndGet()
    }

    suspend fun readScrapeRequest(): ScrapeRequestWrapper? =
        scrapeRequestChannel.receiveOrNull()?.also {
            channelBacklogSize.decrementAndGet()
        }

    fun isValid() = valid.get() && !scrapeRequestChannel.isClosedForReceive

    fun invalidate() {
        valid.set(false)
        scrapeRequestChannel.close()
    }

    fun markActivity() {
        lastActivityTime = now()
    }

    override fun toString() =
        toStringElements {
            add("agentId", agentId)
            add("valid", valid.get())
            add("remoteAddr", remoteAddr)
            add("agentName", agentName)
            add("hostName", hostName)
            add("inactivitySecs", inactivitySecs)
        }

    companion object {
        private val AGENT_ID_GENERATOR = AtomicLong(0)
    }
}