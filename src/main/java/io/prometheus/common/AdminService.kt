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

import com.codahale.metrics.servlets.HealthCheckServlet
import com.codahale.metrics.servlets.PingServlet
import com.codahale.metrics.servlets.ThreadDumpServlet
import com.google.common.base.MoreObjects
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder

class AdminService(service: GenericService,
                   private val port: Int,
                   private val pingPath: String,
                   private val versionPath: String,
                   private val healthCheckPath: String,
                   private val threadDumpPath: String) : AbstractIdleService() {
    private val server: Server = Server(port)

    init {
        val context = ServletContextHandler()
        context.contextPath = "/"
        server.handler = context

        if (pingPath.isNotBlank())
            context.addServlet(ServletHolder(PingServlet()), "/$pingPath")
        if (versionPath.isNotBlank())
            context.addServlet(ServletHolder(VersionServlet()), "/$versionPath")
        if (healthCheckPath.isNotBlank())
            context.addServlet(ServletHolder(HealthCheckServlet(service.healthCheckRegistry)), "/$healthCheckPath")
        if (threadDumpPath.isNotBlank())
            context.addServlet(ServletHolder(ThreadDumpServlet()), "/$threadDumpPath")

        addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    override fun startUp() {
        server.start()
    }

    override fun shutDown() {
        server.stop()
    }

    override fun toString() =
            MoreObjects.toStringHelper(this)
                    .add("ping", ":$port/$pingPath")
                    .add("healthcheck", ":$port/$healthCheckPath")
                    .add("threaddump", ":$port/$threadDumpPath")
                    .toString()
}
