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

package io.prometheus.common

import brave.Tracing
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.ZipkinDsl.tracing
import org.slf4j.LoggerFactory
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.okhttp3.OkHttpSender

class ZipkinReporterService(private val url: String) : AbstractIdleService() {
    private val sender = OkHttpSender.create(url)
    private val reporter = AsyncReporter.create(sender)

    init {
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
    }

    fun newTracing(serviceName: String): Tracing =
            tracing {
                localServiceName(serviceName)
                spanReporter(reporter)
            }

    override fun startUp() {
        // Empty
    }

    override fun shutDown() {
        reporter.close()
        sender.close()
    }

    override fun toString() = toStringElements { add("url", url) }

    companion object {
        private val logger = LoggerFactory.getLogger(ZipkinReporterService::class.java)
    }
}
