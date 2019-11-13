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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.common

import com.codahale.metrics.health.HealthCheck
import com.google.common.util.concurrent.MoreExecutors
import com.sudothought.common.concurrent.GenericIdleService
import com.sudothought.common.concurrent.genericServiceListener
import com.sudothought.common.dsl.GuavaDsl.toStringElements
import io.prometheus.client.exporter.MetricsServlet
import io.prometheus.dsl.JettyDsl.server
import io.prometheus.dsl.JettyDsl.servletContextHandler
import io.prometheus.dsl.MetricsDsl.healthCheck
import mu.KLogging
import org.eclipse.jetty.servlet.ServletHolder

class MetricsService(private val port: Int,
                     private val path: String,
                     initBlock: (MetricsService.() -> Unit) = {}) : GenericIdleService() {

    private val server =
        server(port) {
            handler =
                servletContextHandler {
                    contextPath = "/"
                    addServlet(ServletHolder(MetricsServlet()), "/$path")
                }
        }
    val healthCheck =
        healthCheck {
            if (server.isRunning)
                HealthCheck.Result.healthy()
            else
                HealthCheck.Result.unhealthy("Jetty server not running")
        }

    init {
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
        initBlock(this)
    }

    override fun startUp() = server.start()

    override fun shutDown() = server.stop()

    override fun toString() =
        toStringElements {
            add("url", "http://localhost:$port/$path")
        }

    companion object : KLogging()
}
