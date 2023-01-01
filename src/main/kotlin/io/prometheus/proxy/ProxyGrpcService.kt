/*
 * Copyright © 2023 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import brave.grpc.GrpcTracing
import com.codahale.metrics.health.HealthCheck
import com.github.pambrose.common.concurrent.GenericIdleService
import com.github.pambrose.common.concurrent.genericServiceListener
import com.github.pambrose.common.dsl.GrpcDsl.server
import com.github.pambrose.common.dsl.GuavaDsl.toStringElements
import com.github.pambrose.common.dsl.MetricsDsl.healthCheck
import com.github.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.github.pambrose.common.utils.TlsUtils.buildServerTlsContext
import com.github.pambrose.common.utils.shutdownGracefully
import com.github.pambrose.common.utils.shutdownWithJvm
import com.google.common.util.concurrent.MoreExecutors
import io.grpc.Server
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.prometheus.Proxy
import mu.KLogging
import kotlin.time.Duration.Companion.seconds

internal class ProxyGrpcService(
  private val proxy: Proxy,
  private val port: Int = -1,
  private val inProcessName: String = ""
) : GenericIdleService() {

  val healthCheck =
    healthCheck {
      if (grpcServer.isShutdown || grpcServer.isTerminated)
        HealthCheck.Result.unhealthy("gRPC server is not running")
      else
        HealthCheck.Result.healthy()
    }

  private val grpcServer: Server

  private val tracing by lazy { proxy.zipkinReporterService.newTracing("grpc_server") }
  private val grpcTracing by lazy { GrpcTracing.create(tracing) }

  init {
    val options = proxy.options
    val tlsContext =
      if (options.certChainFilePath.isNotEmpty() || options.privateKeyFilePath.isNotEmpty())
        buildServerTlsContext(
          certChainFilePath = options.certChainFilePath,
          privateKeyFilePath = options.privateKeyFilePath,
          trustCertCollectionFilePath = options.trustCertCollectionFilePath
        )
      else
        PLAINTEXT_CONTEXT

    grpcServer =
      server(
        port = port,
        tlsContext = tlsContext,
        inProcessServerName = inProcessName
      ) {
        val proxyService = ProxyServiceImpl(proxy)
        val interceptors =
          buildList<ServerInterceptor> {
            if (!options.transportFilterDisabled)
              add(ProxyServerInterceptor())
            if (proxy.isZipkinEnabled)
              add(grpcTracing.newServerInterceptor())
          }

        addService(ServerInterceptors.intercept(proxyService.bindService(), interceptors))

        if (!options.transportFilterDisabled)
          addTransportFilter(ProxyServerTransportFilter(proxy))
      }

    grpcServer.shutdownWithJvm(2.seconds)

    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())
  }

  override fun startUp() {
    grpcServer.start()
  }

  override fun shutDown() {
    if (proxy.isZipkinEnabled)
      tracing.close()
    grpcServer.shutdownGracefully(2.seconds)
  }

  override fun toString() =
    toStringElements {
      if (inProcessName.isNotEmpty()) {
        add("serverType", "InProcess")
        add("serverName", inProcessName)
      } else {
        add("serverType", "Netty")
        add("port", port)
      }
    }

  companion object : KLogging()
}
