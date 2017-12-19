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
import com.google.common.base.Strings.isNullOrEmpty
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.servlet.ServletHolder
import java.lang.String.format

class AdminService(service: GenericService,
                   private val port: Int,
                   private val pingPath: String,
                   private val versionPath: String,
                   private val healthCheckPath: String,
                   private val threadDumpPath: String) : AbstractIdleService() {
    private val server: Server

    init {
        this.server = Server(this.port)

        val context = ServletContextHandler()
        context.contextPath = "/"
        this.server.handler = context

        if (!isNullOrEmpty(this.pingPath))
            context.addServlet(ServletHolder(PingServlet()), "/" + this.pingPath)
        if (!isNullOrEmpty(this.versionPath))
            context.addServlet(ServletHolder(VersionServlet()), "/" + this.versionPath)
        if (!isNullOrEmpty(this.healthCheckPath))
            context.addServlet(ServletHolder(HealthCheckServlet(service.healthCheckRegistry)),
                               "/" + this.healthCheckPath)
        if (!isNullOrEmpty(this.threadDumpPath))
            context.addServlet(ServletHolder(ThreadDumpServlet()),
                               "/" + this.threadDumpPath)

        this.addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    @Throws(Exception::class)
    override fun startUp() {
        this.server.start()
    }

    @Throws(Exception::class)
    public override fun shutDown() {
        this.server.stop()
    }

    override fun toString(): String {
        return MoreObjects.toStringHelper(this)
                .add("ping", format(":%d /%s", this.port, this.pingPath))
                .add("healthcheck", format(":%d /%s", this.port, this.healthCheckPath))
                .add("threaddump", format(":%d /%s", this.port, this.threadDumpPath))
                .toString()
    }
}
