/*
 * Copyright © 2026 Paul Ambrose
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

import com.pambrose.common.dsl.GrpcDsl.channel
import com.pambrose.common.utils.TlsContext.Companion.PLAINTEXT_CONTEXT
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.reflection.v1.ServerReflectionGrpc
import io.grpc.reflection.v1.ServerReflectionRequest
import io.grpc.reflection.v1.ServerReflectionResponse
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.agent.AgentTokenClientInterceptor
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.proxy.AgentAuthManager.AuthEntry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ProxyGrpcServiceTest : StringSpec() {
  private fun createMockProxy(
    transportFilterDisabled: Boolean = true,
    reflectionDisabled: Boolean = true,
    handshakeTimeoutSecs: Long = -1L,
    keepAliveTimeSecs: Long = -1L,
    keepAliveTimeoutSecs: Long = -1L,
    permitKeepAliveWithoutCalls: Boolean = false,
    permitKeepAliveTimeSecs: Long = -1L,
    maxConnectionIdleSecs: Long = -1L,
    maxConnectionAgeSecs: Long = -1L,
    maxConnectionAgeGraceSecs: Long = -1L,
    authManager: AgentAuthManager? = null,
  ): Proxy {
    val mockOptions = mockk<ProxyOptions>(relaxed = true)
    every { mockOptions.certChainFilePath } returns ""
    every { mockOptions.privateKeyFilePath } returns ""
    every { mockOptions.trustCertCollectionFilePath } returns ""
    every { mockOptions.transportFilterDisabled } returns transportFilterDisabled
    every { mockOptions.reflectionDisabled } returns reflectionDisabled
    every { mockOptions.handshakeTimeoutSecs } returns handshakeTimeoutSecs
    every { mockOptions.keepAliveTimeSecs } returns keepAliveTimeSecs
    every { mockOptions.keepAliveTimeoutSecs } returns keepAliveTimeoutSecs
    every { mockOptions.permitKeepAliveWithoutCalls } returns permitKeepAliveWithoutCalls
    every { mockOptions.permitKeepAliveTimeSecs } returns permitKeepAliveTimeSecs
    every { mockOptions.maxConnectionIdleSecs } returns maxConnectionIdleSecs
    every { mockOptions.maxConnectionAgeSecs } returns maxConnectionAgeSecs
    every { mockOptions.maxConnectionAgeGraceSecs } returns maxConnectionAgeGraceSecs

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.options } returns mockOptions
    every { mockProxy.isZipkinEnabled } returns false
    if (authManager != null)
      every { mockProxy.agentAuthManager } returns authManager
    return mockProxy
  }

  // Starts an in-process ProxyGrpcService for [proxy] and runs [block] with a plaintext channel to it.
  private fun withChannelTo(
    proxy: Proxy,
    block: (ManagedChannel) -> Unit,
  ) {
    val serverName = "reflection-test-${System.nanoTime()}"
    val service = ProxyGrpcService(proxy, inProcessName = serverName)
    service.startAsync().awaitRunning()
    val channel = channel(tlsContext = PLAINTEXT_CONTEXT, inProcessServerName = serverName) {}
    try {
      block(channel)
    } finally {
      channel.shutdownNow()
      service.stopAsync().awaitTerminated()
    }
  }

  // Sends one list_services request over the reflection stream, presenting [token] through the agent's own token
  // interceptor when given, and returns the listed service names or the status the call failed with.
  private fun listServices(
    channel: ManagedChannel,
    token: String? = null,
  ): Result<List<String>> {
    val names = CompletableFuture<List<String>>()
    val stub =
      ServerReflectionGrpc.newStub(channel).let { stub ->
        if (token == null) stub else stub.withInterceptors(AgentTokenClientInterceptor(token))
      }
    val requests =
      stub.serverReflectionInfo(
        object : StreamObserver<ServerReflectionResponse> {
          override fun onNext(value: ServerReflectionResponse) {
            names.complete(value.listServicesResponse.serviceList.map { it.name })
          }

          override fun onError(t: Throwable) {
            names.completeExceptionally(t)
          }

          override fun onCompleted() {
            names.completeExceptionally(IllegalStateException("Reflection stream completed without a response"))
          }
        },
      )
    requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build())
    requests.onCompleted()
    return runCatching { names.get(5, TimeUnit.SECONDS) }
  }

  init {
    // ==================== toString Tests ====================

    "toString for InProcess server should indicate InProcess type" {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "test-server")

      val str = service.toString()
      str shouldContain "InProcess"
      str shouldContain "test-server"
    }

    "toString for Netty server should indicate Netty type and port" {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, port = PROXY_AGENT_PORT)

      val str = service.toString()
      str shouldContain "Netty"
      str shouldContain "$PROXY_AGENT_PORT"
      str shouldNotContain "InProcess"
    }

    // ==================== HealthCheck Tests ====================

    "healthCheck should not be null" {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "health-test")

      service.healthCheck.shouldNotBeNull()
    }

    // ==================== Server Configuration Tests ====================

    "should create InProcess server without throwing" {
      val mockProxy = createMockProxy()

      // Should not throw
      val service = ProxyGrpcService(mockProxy, inProcessName = "config-test")
      service.shouldNotBeNull()
    }

    "should create Netty server without throwing" {
      val mockProxy = createMockProxy()

      // Should not throw
      val service = ProxyGrpcService(mockProxy, port = 0)
      service.shouldNotBeNull()
    }

    // ==================== HealthCheck Result Tests ====================

    "healthCheck should be healthy before shutdown" {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "health-check-test")

      service.startAsync().awaitRunning()

      val result = service.healthCheck.execute()
      result.isHealthy shouldBe true

      service.stopAsync().awaitTerminated()
    }

    "healthCheck should be unhealthy after shutdown" {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "health-shutdown-test")

      service.startAsync().awaitRunning()
      service.stopAsync().awaitTerminated()

      val result = service.healthCheck.execute()
      result.isHealthy shouldBe false
      result.message shouldContain "not running"
    }

    // ==================== Server Lifecycle Tests ====================

    "InProcess server should start and stop gracefully" {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "lifecycle-test")

      service.startAsync().awaitRunning()
      service.isRunning shouldBe true

      service.stopAsync().awaitTerminated()
    }

    "Netty server should start and stop gracefully on ephemeral port" {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, port = 0)

      service.startAsync().awaitRunning()
      service.isRunning shouldBe true

      service.stopAsync().awaitTerminated()
    }

    // ==================== Server Configuration Branch Tests ====================

    "should create server with transport filter enabled" {
      val mockProxy = createMockProxy(transportFilterDisabled = false)

      val service = ProxyGrpcService(mockProxy, inProcessName = "transport-filter-test")
      service.shouldNotBeNull()
    }

    "should create server with keepalive settings" {
      // Must use Netty (port) rather than InProcess — InProcess does not support keepAlive
      val mockProxy = createMockProxy(
        handshakeTimeoutSecs = 60L,
        keepAliveTimeSecs = 120L,
        keepAliveTimeoutSecs = 20L,
        permitKeepAliveWithoutCalls = true,
        permitKeepAliveTimeSecs = 300L,
        maxConnectionIdleSecs = 600L,
        maxConnectionAgeSecs = 3600L,
        maxConnectionAgeGraceSecs = 30L,
      )

      val service = ProxyGrpcService(mockProxy, port = 0)
      service.shouldNotBeNull()
    }

    // ==================== Reflection Tests ====================
    // Reflection lists and describes every service on the agent port, so it is served only when enabled, and then only
    // to callers that pass the same agent authentication as ProxyService.

    "reflection should not be served when disabled" {
      withChannelTo(createMockProxy()) { channel ->
        val failure = listServices(channel).exceptionOrNull().shouldNotBeNull()
        Status.fromThrowable(failure).code shouldBe Status.Code.UNIMPLEMENTED
      }
    }

    "reflection should list ProxyService when enabled without agent auth" {
      withChannelTo(createMockProxy(reflectionDisabled = false)) { channel ->
        listServices(channel).getOrThrow() shouldContain ProxyServiceGrpc.SERVICE_NAME
      }
    }

    "reflection should require a valid agent token when agent auth is configured" {
      val authManager =
        AgentAuthManager.create(
          authEntries = [AuthEntry("team_a", "s3cret", ["team_a_*"])],
          legacyToken = "",
        )

      withChannelTo(createMockProxy(reflectionDisabled = false, authManager = authManager)) { channel ->
        val missingToken = listServices(channel).exceptionOrNull().shouldNotBeNull()
        val wrongToken = listServices(channel, token = "wrong").exceptionOrNull().shouldNotBeNull()
        val validToken = listServices(channel, token = "s3cret")

        Status.fromThrowable(missingToken).code shouldBe Status.Code.UNAUTHENTICATED
        Status.fromThrowable(wrongToken).code shouldBe Status.Code.UNAUTHENTICATED
        validToken.getOrThrow() shouldContain ProxyServiceGrpc.SERVICE_NAME
      }
    }
  }
}
