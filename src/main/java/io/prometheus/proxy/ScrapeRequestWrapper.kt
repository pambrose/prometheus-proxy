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


import brave.Span
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import com.google.common.base.Strings.isNullOrEmpty
import io.prometheus.Proxy
import io.prometheus.client.Summary
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ScrapeRequestWrapper(proxy: Proxy,
                           agentContext: AgentContext,
                           private val rootSpan: Span?,
                           path: String,
                           accept: String?) {

    private val createTime = System.currentTimeMillis()
    private val complete = CountDownLatch(1)
    private val scrapeResponseRef = AtomicReference<ScrapeResponse>()

    val agentContext: AgentContext
    private val requestTimer: Summary.Timer?
    val scrapeRequest: ScrapeRequest

    val scrapeId: Long
        get() = this.scrapeRequest.scrapeId

    val scrapeResponse: ScrapeResponse
        get() = this.scrapeResponseRef.get()

    init {
        this.agentContext = Preconditions.checkNotNull(agentContext)
        this.requestTimer = if (proxy.metricsEnabled) proxy.metrics!!.scrapeRequestLatency.startTimer() else null
        var builder = ScrapeRequest.newBuilder()
                .setAgentId(agentContext.agentId)
                .setScrapeId(SCRAPE_ID_GENERATOR.getAndIncrement())
                .setPath(path)
        if (!isNullOrEmpty(accept))
            builder = builder.setAccept(accept)
        this.scrapeRequest = builder.build()
    }

    fun annotateSpan(value: String): ScrapeRequestWrapper {
        if (this.rootSpan != null)
            this.rootSpan.annotate(value)
        return this
    }

    fun setScrapeResponse(scrapeResponse: ScrapeResponse): ScrapeRequestWrapper {
        this.scrapeResponseRef.set(scrapeResponse)
        return this
    }

    fun ageInSecs(): Long {
        return (System.currentTimeMillis() - this.createTime) / 1000
    }

    fun markComplete(): ScrapeRequestWrapper {
        if (this.requestTimer != null)
            this.requestTimer.observeDuration()
        this.complete.countDown()
        return this
    }

    fun waitUntilCompleteMillis(waitMillis: Long): Boolean {
        try {
            return this.complete.await(waitMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Ignore
        }

        return false
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
                .add("scrapeId", scrapeRequest.scrapeId)
                .add("path", scrapeRequest.path)
                .toString()
    }

    companion object {

        private val logger = LoggerFactory.getLogger(ScrapeRequestWrapper::class.java)
        private val SCRAPE_ID_GENERATOR = AtomicLong(0)
    }
}
