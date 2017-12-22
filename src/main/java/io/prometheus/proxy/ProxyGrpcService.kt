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

package io.prometheus.proxy

import com.codahale.metrics.health.HealthCheck
import com.google.common.base.MoreObjects
import com.google.common.base.Preconditions
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.inprocess.InProcessServerBuilder
import io.prometheus.Proxy
import io.prometheus.common.GenericServiceListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException

class ProxyGrpcService private constructor(proxy: Proxy, private val port: Int, private val inProcessServerName: String?) : AbstractIdleService() {
    private val inProcessServer = !inProcessServerName.isNullOrBlank()
    private val grpcServer: Server

    val healthCheck: HealthCheck
        get() = object : HealthCheck() {
            @Throws(Exception::class)
            override fun check(): HealthCheck.Result {
                return if (grpcServer.isShutdown || grpcServer.isShutdown)
                    HealthCheck.Result.unhealthy("gRPC server is not running")
                else
                    HealthCheck.Result.healthy()
            }
        }

    init {
        val interceptors = listOf<ServerInterceptor>(ProxyInterceptor())

        /*
        if (proxy.getConfigVals().grpc.metricsEnabled)
          interceptors.add(MonitoringServerInterceptor.create(proxy.getConfigVals().grpc.allMetricsReported
                                                              ? Configuration.allMetrics()
                                                              : Configuration.cheapMetricsOnly()));
        if (proxy.isZipkinEnabled() && proxy.getConfigVals().grpc.zipkinReportingEnabled)
          interceptors.add(BraveGrpcServerInterceptor.create(proxy.getZipkinReporterService().getBrave()));
        */

        val proxyService = ProxyServiceImpl(proxy)
        val serviceDef = ServerInterceptors.intercept(proxyService.bindService(), interceptors)

        this.grpcServer =
                (if (this.inProcessServer)
                    InProcessServerBuilder.forName(this.inProcessServerName)
                else
                    ServerBuilder.forPort(this.port))
                        .addService(serviceDef)
                        .addTransportFilter(ProxyTransportFilter(proxy))
                        .build()

        this.addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    @Throws(IOException::class)
    override fun startUp() {
        this.grpcServer.start()
    }

    override fun shutDown() {
        this.grpcServer.shutdown()
    }

    override fun toString() =
            with(MoreObjects.toStringHelper(this)) {
                if (inProcessServer) {
                    add("serverType", "InProcess")
                    add("serverName", inProcessServerName)
                }
                else {
                    add("serverType", "Netty")
                    add("port", port)
                }
                toString()
            }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(ProxyGrpcService::class.java)

        fun create(proxy: Proxy, grpcPort: Int) = ProxyGrpcService(proxy, grpcPort, null)

        fun create(proxy: Proxy, serverName: String) = ProxyGrpcService(proxy, -1, Preconditions.checkNotNull(serverName))
    }
}
