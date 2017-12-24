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

import com.codahale.metrics.health.HealthCheck
import com.google.common.base.MoreObjects
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import io.prometheus.client.exporter.MetricsServlet
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder

class MetricsService(private val port: Int, private val path: String) : AbstractIdleService() {
    private val server: Server = Server(this.port)
    val healthCheck: HealthCheck = object : HealthCheck() {
        @Throws(Exception::class)
        override fun check(): HealthCheck.Result {
            return if (server.isRunning) HealthCheck.Result.healthy() else HealthCheck.Result.unhealthy("Jetty server not running")
        }
    }

    init {
        val context = ServletContextHandler()
        context.contextPath = "/"
        this.server.handler = context
        context.addServlet(ServletHolder(MetricsServlet()), "/" + this.path)

        this.addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    override fun startUp() {
        this.server.start()
    }

    override fun shutDown() {
        this.server.stop()
    }

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("url", "http://localhost:${this.port}/${this.path}")
                    .toString()
}
