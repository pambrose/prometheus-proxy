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

package io.prometheus.common

import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.ZipkinDsl.tracing
import io.prometheus.guava.GenericIdleService
import io.prometheus.guava.genericServiceListener
import mu.KLogging
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.okhttp3.OkHttpSender

class ZipkinReporterService(private val url: String, initBlock: (ZipkinReporterService.() -> Unit)? = null) :
        GenericIdleService() {
    private val sender = OkHttpSender.create(url)
    private val reporter = AsyncReporter.create(sender)

    init {
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
        initBlock?.invoke(this)
    }

    fun newTracing(serviceName: String) =
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

    companion object : KLogging()
}