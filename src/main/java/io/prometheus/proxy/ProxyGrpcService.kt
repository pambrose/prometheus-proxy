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

import brave.Tracing
import brave.grpc.GrpcTracing
import com.codahale.metrics.health.HealthCheck
import com.google.common.base.MoreObjects
import com.google.common.util.concurrent.AbstractIdleService
import com.google.common.util.concurrent.MoreExecutors
import io.grpc.Server
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.prometheus.Proxy
import io.prometheus.common.GenericServiceListener
import io.prometheus.dsl.GrpcDsl.server
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException

class ProxyGrpcService private constructor(proxy: Proxy,
                                           private val port: Int = -1,
                                           private val inProcessServerName: String = "") : AbstractIdleService() {
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

    private val tracing: Tracing?
    private val grpcTracing: GrpcTracing?
    private val grpcServer: Server

    init {
        if (proxy.isZipkinEnabled) {
            tracing = proxy.zipkinReporterService!!.newTracing("grpc_server")
            grpcTracing = GrpcTracing.create(tracing)
        }
        else {
            tracing = null
            grpcTracing = null
        }

        grpcServer = server(inProcessServerName = inProcessServerName, port = port) {
            val proxyService = ProxyServiceImpl(proxy)
            val interceptors = mutableListOf<ServerInterceptor>(ProxyInterceptor())
            if (proxy.isZipkinEnabled)
                interceptors.add(grpcTracing!!.newServerInterceptor())
            addService(ServerInterceptors.intercept(proxyService.bindService(), interceptors))
            addTransportFilter(ProxyTransportFilter(proxy))
        }

        addListener(GenericServiceListener(this), MoreExecutors.directExecutor())
    }

    @Throws(IOException::class)
    override fun startUp() {
        grpcServer.start()
    }

    override fun shutDown() {
        tracing?.close()
        grpcServer.shutdown()
    }

    override fun toString() =
            with(MoreObjects.toStringHelper(this)) {
                if (inProcessServerName.isNotEmpty()) {
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

        fun create(proxy: Proxy, grpcPort: Int) = ProxyGrpcService(proxy = proxy, port = grpcPort)
        fun create(proxy: Proxy, serverName: String) = ProxyGrpcService(proxy = proxy, inProcessServerName = serverName)
    }
}
