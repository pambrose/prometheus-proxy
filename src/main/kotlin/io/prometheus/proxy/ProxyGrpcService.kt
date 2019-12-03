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

package io.prometheus.proxy

import brave.Tracing
import brave.grpc.GrpcTracing
import com.codahale.metrics.health.HealthCheck
import com.github.pambrose.common.concurrent.GenericIdleService
import com.github.pambrose.common.concurrent.genericServiceListener
import com.github.pambrose.common.dsl.GrpcDsl.server
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.dsl.MetricsDsl.healthCheck
import com.github.pambrose.common.utils.TlsUtils.buildServerSslContext
import com.google.common.util.concurrent.MoreExecutors
import com.salesforce.grpc.contrib.Servers
import io.grpc.Server
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.prometheus.Proxy
import mu.KLogging
import kotlin.time.seconds

class ProxyGrpcService(private val proxy: Proxy,
                       private val port: Int = -1,
                       private val inProcessName: String = "") : GenericIdleService() {

  val healthCheck =
      healthCheck {
        if (grpcServer.isShutdown || grpcServer.isShutdown)
          HealthCheck.Result.unhealthy("gRPC server is not running")
        else
          HealthCheck.Result.healthy()
      }

  private val grpcServer: Server

  private lateinit var tracing: Tracing
  private lateinit var grpcTracing: GrpcTracing

  init {
    if (proxy.isZipkinEnabled) {
      tracing = proxy.zipkinReporterService.newTracing("grpc_server")
      grpcTracing = GrpcTracing.create(tracing)
    }

    val tls = proxy.options.configVals.proxy.tls
    val sslContext =
        if (tls.certChainFilePath.isNotEmpty() || tls.privateKeyFilePath.isNotEmpty())
          buildServerSslContext(certChainFilePath = tls.certChainFilePath,
                                privateKeyFilePath = tls.privateKeyFilePath,
                                trustCertCollectionFilePath = tls.trustCertCollectionFilePath)
        else
          null

    grpcServer =
        server(port = port,
               sslContext = sslContext,
               inProcessServerName = inProcessName) {
          val proxyService = ProxyServiceImpl(proxy)
          val interceptors = mutableListOf<ServerInterceptor>(ProxyInterceptor())
          if (proxy.isZipkinEnabled)
            interceptors += grpcTracing.newServerInterceptor()
          addService(ServerInterceptors.intercept(proxyService.bindService(), interceptors))
          addTransportFilter(ProxyTransportFilter(proxy))
        }

    Servers.shutdownWithJvm(grpcServer, 2.seconds.toLongMilliseconds())

    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    grpcServer.start()
  }

  override fun shutDown() {
    if (proxy.isZipkinEnabled)
      tracing.close()
    Servers.shutdownGracefully(grpcServer, 2.seconds.toLongMilliseconds())
  }

  override fun toString() =
      toStringElements {
        if (inProcessName.isNotEmpty()) {
          add("serverType", "InProcess")
          add("serverName", inProcessName)
        }
        else {
          add("serverType", "Netty")
          add("port", port)
        }
      }

  companion object : KLogging()
}
