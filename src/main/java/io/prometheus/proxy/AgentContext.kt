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
import io.prometheus.common.toSecs
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AgentContext(proxy: Proxy, val remoteAddr: String) {

    val agentId = AGENT_ID_GENERATOR.incrementAndGet().toString()
    private val validRef = AtomicBoolean(true)
    private val lastActivityTime = AtomicLong()
    private val agentNameRef = AtomicReference<String>()
    private val hostnameRef = AtomicReference<String>()
    private val scrapeRequestQueue = ArrayBlockingQueue<ScrapeRequestWrapper>(proxy.configVals.internal.scrapeRequestQueueSize)
    private val waitMillis = proxy.configVals.internal.scrapeRequestQueueCheckMillis.toLong()

    var valid: Boolean
        get() = this.validRef.get()
        private set(v) = this.validRef.set(v)

    var hostname: String?
        get() = this.hostnameRef.get()
        set(v) = this.hostnameRef.set(v)


    var agentName: String?
        get() = this.agentNameRef.get()
        set(v) = this.agentNameRef.set(v)

    init {
        this.markActivity()
    }

    fun addToScrapeRequestQueue(scrapeRequest: ScrapeRequestWrapper) = this.scrapeRequestQueue.add(scrapeRequest)

    fun scrapeRequestQueueSize(): Int = this.scrapeRequestQueue.size

    fun pollScrapeRequestQueue(): ScrapeRequestWrapper? =
            try {
                this.scrapeRequestQueue.poll(waitMillis, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            }

    fun inactivitySecs(): Long = (System.currentTimeMillis() - this.lastActivityTime.get()).toSecs()

    fun markInvalid() {
        this.valid = false
    }

    fun markActivity() = this.lastActivityTime.set(System.currentTimeMillis())

    override fun toString(): String =
            MoreObjects.toStringHelper(this)
                    .add("agentId", this.agentId)
                    .add("valid", this.valid)
                    .add("remoteAddr", this.remoteAddr)
                    .add("agentName", this.agentName)
                    .add("hostname", this.hostname)
                    .add("inactivitySecs", this.inactivitySecs())
                    .toString()

    companion object {
        private val AGENT_ID_GENERATOR = AtomicLong(0)
    }
}