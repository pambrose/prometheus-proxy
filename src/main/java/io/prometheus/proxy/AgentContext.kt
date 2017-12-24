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

import com.google.common.base.MoreObjects
import io.prometheus.Proxy
import io.prometheus.common.AtomicBooleanDelegate
import io.prometheus.common.AtomicLongDelegate
import io.prometheus.common.AtomicReferenceDelegate
import io.prometheus.common.toSecs
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AgentContext(proxy: Proxy, private val remoteAddr: String) {

    val agentId = AGENT_ID_GENERATOR.incrementAndGet().toString()
    private val scrapeRequestQueue = ArrayBlockingQueue<ScrapeRequestWrapper>(proxy.configVals.internal.scrapeRequestQueueSize)
    private val waitMillis = proxy.configVals.internal.scrapeRequestQueueCheckMillis.toLong()

    private var lastActivityTime: Long by AtomicLongDelegate()
    var valid: Boolean by AtomicBooleanDelegate(true)
    var hostname: String? by AtomicReferenceDelegate()
    var agentName: String? by AtomicReferenceDelegate()

    val inactivitySecs: Long
        get() = (System.currentTimeMillis() - this.lastActivityTime).toSecs()

    val scrapeRequestQueueSize: Int
        get() = this.scrapeRequestQueue.size

    init {
        this.markActivity()
    }

    fun addToScrapeRequestQueue(scrapeRequest: ScrapeRequestWrapper) = this.scrapeRequestQueue.add(scrapeRequest)

    fun pollScrapeRequestQueue(): ScrapeRequestWrapper? =
            try {
                this.scrapeRequestQueue.poll(waitMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            }

    fun markInvalid() {
        this.valid = false
    }

    fun markActivity() {
        this.lastActivityTime = System.currentTimeMillis()
    }

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("agentId", this.agentId)
                    .add("valid", this.valid)
                    .add("remoteAddr", this.remoteAddr)
                    .add("agentName", this.agentName)
                    .add("hostname", this.hostname)
                    .add("inactivitySecs", this.inactivitySecs)
                    .toString()

    companion object {
        private val AGENT_ID_GENERATOR = AtomicLong(0)
    }
}