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
import com.google.common.base.MoreObjects
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.okhttp3.OkHttpSender

class ZipkinReporterService(private val url: String) : AbstractIdleService() {
    private val sender = OkHttpSender.create(this.url)
    private val reporter = AsyncReporter.create(this.sender)

    init {
        this.addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    fun newTracing(serviceName: String): Tracing =
            Tracing.newBuilder()
                    .localServiceName(serviceName)
                    .spanReporter(this.reporter)
                    .build()

    override fun startUp() {
        // Empty
    }

    override fun shutDown() {
        this.reporter.close()
        this.sender.close()
    }

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("url", url)
                    .toString()
}
