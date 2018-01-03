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


import com.google.common.base.Preconditions
import io.prometheus.Proxy
import io.prometheus.delegate.AtomicDelegates
import io.prometheus.dsl.GuavaDsl.toStringElements
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
    private val requestTimer = if (proxy.isMetricsEnabled) proxy.metrics.scrapeRequestLatency.startTimer() else null

    val agentContext: AgentContext = Preconditions.checkNotNull(agentContext)

    var scrapeResponse: ScrapeResponse by AtomicDelegates.notNullReference()

    val scrapeRequest: ScrapeRequest =
            ScrapeRequest.newBuilder()
                    .run {
                        agentId = agentContext.agentId
                        scrapeId = SCRAPE_ID_GENERATOR.getAndIncrement()
                        this.path = path
                        if (!accept.isNullOrBlank())
                            this.accept = accept
                        build()
                    }

    val scrapeId: Long
        get() = scrapeRequest.scrapeId

    fun ageInSecs(): Long = (System.currentTimeMillis() - createTime) / 1000

    fun markComplete() {
        requestTimer?.observeDuration()
        complete.countDown()
    }

    fun waitUntilCompleteMillis(waitMillis: Long): Boolean {
        try {
            return complete.await(waitMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            // Ignore
        }

        return false
    }

    override fun toString() =
            toStringElements {
                add("scrapeId", scrapeRequest.scrapeId)
                add("path", scrapeRequest.path)
            }

    companion object {
        private val SCRAPE_ID_GENERATOR = AtomicLong(0)
    }
}
