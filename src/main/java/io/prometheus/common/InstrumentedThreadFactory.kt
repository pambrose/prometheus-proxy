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

import com.google.common.base.Preconditions
import com.google.common.util.concurrent.ThreadFactoryBuilder
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import java.lang.String.format
import java.util.concurrent.ThreadFactory

class InstrumentedThreadFactory(delegate: ThreadFactory, name: String, help: String) : ThreadFactory {

    private val delegate: ThreadFactory
    private val created: Counter
    private val running: Gauge
    private val terminated: Counter

    init {
        Preconditions.checkNotNull(name)
        Preconditions.checkNotNull(help)
        this.delegate = Preconditions.checkNotNull(delegate)
        this.created = Counter.build()
                .name(format("%s_threads_created", name))
                .help(format("%s threads created", help))
                .register()
        this.running = Gauge.build()
                .name(format("%s_threads_running", name))
                .help(format("%s threads running", help))
                .register()
        this.terminated = Counter.build()
                .name(format("%s_threads_terminated", name))
                .help(format("%s threads terminated", help))
                .register()
    }

    override fun newThread(runnable: Runnable): Thread {
        val wrappedRunnable = InstrumentedRunnable(runnable)
        val thread = this.delegate.newThread(wrappedRunnable)
        this.created.inc()
        return thread
    }

    private inner class InstrumentedRunnable constructor(private val runnable: Runnable) : Runnable {
        override fun run() {
            running.inc()
            try {
                runnable.run()
            } finally {
                running.dec()
                terminated.inc()
            }
        }
    }

    companion object {
        fun newInstrumentedThreadFactory(name: String,
                                         help: String,
                                         daemon: Boolean): ThreadFactory {
            val threadFactory = ThreadFactoryBuilder().setNameFormat(name + "-%d")
                    .setDaemon(daemon)
                    .build()
            return InstrumentedThreadFactory(threadFactory, name, help)
        }
    }

}
