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

import brave.Tracer
import brave.Tracing
import com.github.kristofa.brave.Brave
import com.github.kristofa.brave.TracerAdapter
import com.google.common.base.MoreObjects
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import zipkin.Span
import zipkin.reporter.AsyncReporter
import zipkin.reporter.Sender
import zipkin.reporter.okhttp3.OkHttpSender

import java.io.IOException

class ZipkinReporterService(private val url: String, private val serviceName: String) : AbstractIdleService() {
    private val sender: Sender = OkHttpSender.create(this.url)
    private val reporter: AsyncReporter<Span> = AsyncReporter.builder(this.sender).build()
    val brave: Brave = TracerAdapter.newBrave(this.newTracer(this.serviceName))

    init {
        this.addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    fun newTracer(serviceName: String): Tracer =
            Tracing.newBuilder()
                .localServiceName(serviceName)
                .reporter(this.reporter)
                .build()
                .tracer()

    override fun startUp() {
        // Empty
    }

    @Throws(IOException::class)
    public override fun shutDown() {
        this.sender.close()
        this.reporter.close()
    }

    override fun toString(): String =
            MoreObjects.toStringHelper(this)
                    .add("serviceName", serviceName)
                    .add("url", url)
                    .toString()
}
