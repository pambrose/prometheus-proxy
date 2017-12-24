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
import com.google.common.base.Preconditions
import io.prometheus.Proxy
import io.prometheus.common.AtomicReferenceDelegate
import io.prometheus.grpc.ScrapeRequest
import io.prometheus.grpc.ScrapeResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ScrapeRequestWrapper(proxy: Proxy,
                           agentContext: AgentContext,
                           path: String,
                           accept: String?) {

    private val createTime = System.currentTimeMillis()
    private val complete = CountDownLatch(1)
    private val requestTimer = proxy.metrics?.scrapeRequestLatency?.startTimer()

    val agentContext: AgentContext = Preconditions.checkNotNull(agentContext)

    val scrapeRequest: ScrapeRequest

    val scrapeId: Long
        get() = this.scrapeRequest.scrapeId

    var scrapeResponse: ScrapeResponse? by AtomicReferenceDelegate()

    init {
        var builder =
                ScrapeRequest.newBuilder()
                        .setAgentId(agentContext.agentId)
                        .setScrapeId(SCRAPE_ID_GENERATOR.getAndIncrement())
                        .setPath(path)
        if (!accept.isNullOrBlank())
            builder = builder.setAccept(accept)
        this.scrapeRequest = builder.build()
    }

    fun ageInSecs(): Long = (System.currentTimeMillis() - this.createTime) / 1000

    fun markComplete() {
        this.requestTimer?.observeDuration()
        this.complete.countDown()
    }

    fun waitUntilCompleteMillis(waitMillis: Long): Boolean {
        try {
            return this.complete.await(waitMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Ignore
        }

        return false
    }

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("scrapeId", scrapeRequest.scrapeId)
                    .add("path", scrapeRequest.path)
                    .toString()

    companion object {
        private val SCRAPE_ID_GENERATOR = AtomicLong(0)
    }
}
