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

import com.codahale.metrics.health.HealthCheckRegistry
import com.codahale.metrics.servlets.HealthCheckServlet
import com.codahale.metrics.servlets.PingServlet
import com.codahale.metrics.servlets.ThreadDumpServlet
import com.google.common.util.concurrent.MoreExecutors
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.dsl.GuavaDsl.toStringElements
import io.prometheus.dsl.SparkDsl.servletContextHandler
import io.prometheus.guava.GenericIdleService
import io.prometheus.guava.genericServiceListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import mu.KLogging
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletHolder

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
class AdminService(healthCheckRegistry: HealthCheckRegistry,
                   private val port: Int,
                   private val pingPath: String = "",
                   private val versionPath: String = "",
                   private val healthCheckPath: String = "",
                   private val threadDumpPath: String = "",
                   initBlock: (AdminService.() -> Unit)? = null) : GenericIdleService() {

    private val server =
        Server(port)
            .apply {
                handler =
                    servletContextHandler {
                        contextPath = "/"
                        if (pingPath.isNotBlank())
                            addServlet(ServletHolder(PingServlet()), "/$pingPath")
                        if (versionPath.isNotBlank())
                            addServlet(ServletHolder(VersionServlet()), "/$versionPath")
                        if (healthCheckPath.isNotBlank())
                            addServlet(ServletHolder(HealthCheckServlet(healthCheckRegistry)), "/$healthCheckPath")
                        if (threadDumpPath.isNotBlank())
                            addServlet(ServletHolder(ThreadDumpServlet()), "/$threadDumpPath")
                    }
            }

    init {
        addListener(genericServiceListener(this, logger), MoreExecutors.directExecutor())
        initBlock?.invoke(this)
    }

    override fun startUp() = server.start()

    override fun shutDown() = server.stop()

    override fun toString() =
        toStringElements {
            add("ping", ":$port/$pingPath")
            add("healthcheck", ":$port/$healthCheckPath")
            add("threaddump", ":$port/$threadDumpPath")
        }

    companion object : KLogging()
}
