/*
 *  Copyright 2017, Paul Ambrose All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.prometheus.proxy

import io.prometheus.Proxy
import io.prometheus.common.toSecs
import io.prometheus.delegate.AtomicDelegates
import io.prometheus.dsl.GuavaDsl.toStringElements
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AgentContext(proxy: Proxy, private val remoteAddr: String) {

    val agentId = AGENT_ID_GENERATOR.incrementAndGet().toString()
    private val scrapeRequestQueue = ArrayBlockingQueue<ScrapeRequestWrapper>(proxy.configVals.internal.scrapeRequestQueueSize)
    private val waitMillis = proxy.configVals.internal.scrapeRequestQueueCheckMillis.toLong()

    private var lastActivityTime = AtomicLong()
    var isValid = AtomicBoolean(true)
    var hostName: String by AtomicDelegates.notNullReference()
    var agentName: String by AtomicDelegates.notNullReference()

    val inactivitySecs: Long
        get() = (System.currentTimeMillis() - lastActivityTime.get()).toSecs()

    val scrapeRequestQueueSize: Int
        get() = scrapeRequestQueue.size

    init {
        hostName = "Unassigned"
        agentName = "Unassigned"
        markActivity()
    }

    fun addToScrapeRequestQueue(scrapeRequest: ScrapeRequestWrapper) {
        scrapeRequestQueue += scrapeRequest
    }

    fun pollScrapeRequestQueue(): ScrapeRequestWrapper? =
            try {
                scrapeRequestQueue.poll(waitMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            }

    fun markInvalid() {
        isValid.set(false)
    }

    fun markActivity() {
        lastActivityTime.set(System.currentTimeMillis())
    }

    override fun toString() =
            toStringElements {
                add("agentId", agentId)
                add("valid", isValid)
                add("remoteAddr", remoteAddr)
                add("agentName", agentName)
                add("hostName", hostName)
                add("inactivitySecs", inactivitySecs)
            }

    companion object {
        private val AGENT_ID_GENERATOR = AtomicLong(0)
    }
}