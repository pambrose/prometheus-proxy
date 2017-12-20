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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AgentContext(proxy: Proxy, val remoteAddr: String) {

    val agentId = AGENT_ID_GENERATOR.incrementAndGet().toString()
    private val valid = AtomicBoolean(true)
    private val lastActivityTime = AtomicLong()
    private val agentName = AtomicReference<String>()
    private val hostname = AtomicReference<String>()
    private val scrapeRequestQueue: BlockingQueue<ScrapeRequestWrapper>
    private val waitMillis: Long

    val isValid: Boolean
        get() = this.valid.get()

    init {
        val queueSize = proxy.configVals.internal.scrapeRequestQueueSize
        this.scrapeRequestQueue = ArrayBlockingQueue(queueSize)
        this.waitMillis = proxy.configVals.internal.scrapeRequestQueueCheckMillis.toLong()

        this.markActivity()
    }

    fun getHostname(): String {
        return this.hostname.get()
    }

    fun setHostname(hostname: String) {
        this.hostname.set(hostname)
    }

    fun getAgentName(): String {
        return this.agentName.get()
    }

    fun setAgentName(agentName: String) {
        this.agentName.set(agentName)
    }

    fun addToScrapeRequestQueue(scrapeRequest: ScrapeRequestWrapper) {
        this.scrapeRequestQueue.add(scrapeRequest)
    }

    fun scrapeRequestQueueSize(): Int {
        return this.scrapeRequestQueue.size
    }

    fun pollScrapeRequestQueue(): ScrapeRequestWrapper? {
        try {
            return this.scrapeRequestQueue.poll(waitMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            return null
        }
    }

    fun inactivitySecs(): Long {
        return (System.currentTimeMillis() - this.lastActivityTime.get()) / 1000
    }

    fun markInvalid() {
        this.valid.set(false)
    }

    fun markActivity() {
        this.lastActivityTime.set(System.currentTimeMillis())
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
                .add("agentId", this.agentId)
                .add("valid", this.isValid)
                .add("remoteAddr", this.remoteAddr)
                .add("agentName", this.agentName)
                .add("hostname", this.hostname)
                .add("inactivitySecs", this.inactivitySecs())
                .toString()
    }

    companion object {

        private val AGENT_ID_GENERATOR = AtomicLong(0)
    }
}
