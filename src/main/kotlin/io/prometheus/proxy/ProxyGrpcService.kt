/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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
import com.github.pambrose.common.utils.TlsContext
import com.github.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import com.github.pambrose.common.utils.TlsUtils.buildServerTlsContext
import com.github.pambrose.common.utils.shutdownGracefully
import com.github.pambrose.common.utils.shutdownWithJvm
import com.google.common.util.concurrent.MoreExecutors
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.grpc.Server
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.protobuf.services.ProtoReflectionServiceV1
import io.prometheus.Proxy
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.time.Duration.Companion.seconds

internal class ProxyGrpcService(
  private val proxy: Proxy,
  private val port: Int = -1,
  private val inProcessName: String = "",
) : GenericIdleService() {
  private val tracing by lazy { proxy.zipkinReporterService.newTracing("grpc_server") }
  private val grpcTracing by lazy { GrpcTracing.create(tracing) }
  private val grpcServer: Server
  val healthCheck: HealthCheck

  init {
    val options = proxy.options
    val tlsContext =
      if (options.isTlsEnabled)
        buildServerTlsContext(
          certChainFilePath = options.certChainFilePath,
          privateKeyFilePath = options.privateKeyFilePath,
          trustCertCollectionFilePath = options.trustCertCollectionFilePath,
        )
      else
        PLAINTEXT_CONTEXT

    grpcServer = createGrpcServer(tlsContext, options)
    grpcServer.shutdownWithJvm(2.seconds)
    addListener(genericServiceListener(logger), MoreExecutors.directExecutor())

    healthCheck = healthCheck {
      if (grpcServer.isShutdown || grpcServer.isTerminated)
        HealthCheck.Result.unhealthy("gRPC server is not running")
      else
        HealthCheck.Result.healthy()
    }
  }

  private fun createGrpcServer(
    tlsContext: TlsContext,
    options: ProxyOptions,
  ): Server =
    server(
      port = port,
      tlsContext = tlsContext,
      inProcessServerName = inProcessName,
    ) {
      val proxyService = ProxyServiceImpl(proxy)
      val interceptors: List<ServerInterceptor> =
        buildList {
          if (!options.transportFilterDisabled)
            add(ProxyServerInterceptor())
          if (proxy.isZipkinEnabled)
            add(grpcTracing.newServerInterceptor())
        }

      addService(ServerInterceptors.intercept(proxyService.bindService(), interceptors))

      if (!options.transportFilterDisabled)
        addTransportFilter(ProxyServerTransportFilter(proxy))

      if (!options.reflectionDisabled)
        addService(ProtoReflectionServiceV1.newInstance())

      if (options.handshakeTimeoutSecs > -1L)
        handshakeTimeout(options.handshakeTimeoutSecs, SECONDS)

      if (options.keepAliveTimeSecs > -1L)
        keepAliveTime(options.keepAliveTimeSecs, SECONDS)

      if (options.keepAliveTimeoutSecs > -1L)
        keepAliveTimeout(options.keepAliveTimeoutSecs, SECONDS)

      if (options.permitKeepAliveWithoutCalls)
        permitKeepAliveWithoutCalls(options.permitKeepAliveWithoutCalls)

      if (options.permitKeepAliveTimeSecs > -1L)
        permitKeepAliveTime(options.permitKeepAliveTimeSecs, SECONDS)

      if (options.maxConnectionIdleSecs > -1L)
        maxConnectionIdle(options.maxConnectionIdleSecs, SECONDS)

      if (options.maxConnectionAgeSecs > -1L)
        maxConnectionAge(options.maxConnectionAgeSecs, SECONDS)

      if (options.maxConnectionAgeGraceSecs > -1L)
        maxConnectionAgeGrace(options.maxConnectionAgeGraceSecs, SECONDS)
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

  companion object {
    private val logger = logger {}
  }
}
