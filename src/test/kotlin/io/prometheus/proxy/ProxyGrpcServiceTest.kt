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
import io.grpc.Metadata
import io.grpc.ServerBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.reflection.v1.ServerReflectionGrpc
import io.grpc.reflection.v1.ServerReflectionRequest
import io.grpc.reflection.v1.ServerReflectionResponse
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyAll
import io.prometheus.Proxy
import io.prometheus.agent.AgentTokenClientInterceptor
import io.prometheus.common.DefaultObjects.EMPTY_INSTANCE
import io.prometheus.common.GrpcConstants.META_AGENT_ID_KEY
import io.prometheus.common.TestPorts.PROXY_AGENT_PORT
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.ProxyServiceGrpc
import io.prometheus.grpc.RegisterPathResponse
import io.prometheus.grpc.registerPathRequest
import io.prometheus.proxy.AgentAuthManager.AuthEntry
import io.prometheus.proxy.ProxyGrpcService.Companion.applyConnectionOptions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

  // A mocked proxy with every handshake, keepalive, and connection-age option set, each to a different value.
  private fun createConnectionOptionsProxy(): Proxy =
    createMockProxy(
      handshakeTimeoutSecs = 60L,
      keepAliveTimeSecs = 120L,
      keepAliveTimeoutSecs = 20L,
      permitKeepAliveWithoutCalls = true,
      permitKeepAliveTimeSecs = 300L,
      maxConnectionIdleSecs = 600L,
      maxConnectionAgeSecs = 3600L,
      maxConnectionAgeGraceSecs = 30L,
    )

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

    // ==================== Server Configuration Tests ====================

    "should create InProcess server without throwing" {
      val mockProxy = createMockProxy()

      shouldNotThrowAny { ProxyGrpcService(mockProxy, inProcessName = "config-test") }
    }

    "should create Netty server without throwing" {
      val mockProxy = createMockProxy()

      shouldNotThrowAny { ProxyGrpcService(mockProxy, port = 0) }
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

    "should create server with transport filter enabled without throwing" {
      val mockProxy = createMockProxy(transportFilterDisabled = false)

      shouldNotThrowAny { ProxyGrpcService(mockProxy, inProcessName = "transport-filter-test") }
    }

    // Must use Netty (port) rather than InProcess, which does not support keepalive. This shows only that the Netty
    // builder takes every option; the tests below check that each one reaches it.
    "should create Netty server with keepalive settings without throwing" {
      val mockProxy = createConnectionOptionsProxy()

      shouldNotThrowAny { ProxyGrpcService(mockProxy, port = 0) }
    }

    // ==================== Connection Option Tests ====================
    // The tests above checked only that the server was built, so an option dropped on its way to the builder went
    // unnoticed (October #45). Each option has its own value, so one passed to the wrong setter fails too.

    "each configured handshake, keepalive, and connection-age option should reach the server builder" {
      val builder = mockk<ServerBuilder<*>>(relaxed = true)

      builder.applyConnectionOptions(createConnectionOptionsProxy().options)

      verifyAll {
        builder.handshakeTimeout(60L, TimeUnit.SECONDS)
        builder.keepAliveTime(120L, TimeUnit.SECONDS)
        builder.keepAliveTimeout(20L, TimeUnit.SECONDS)
        builder.permitKeepAliveWithoutCalls(true)
        builder.permitKeepAliveTime(300L, TimeUnit.SECONDS)
        builder.maxConnectionIdle(600L, TimeUnit.SECONDS)
        builder.maxConnectionAge(3600L, TimeUnit.SECONDS)
        builder.maxConnectionAgeGrace(30L, TimeUnit.SECONDS)
      }
    }

    "connection options left unset should leave gRPC's defaults" {
      val builder = mockk<ServerBuilder<*>>(relaxed = true)

      builder.applyConnectionOptions(createMockProxy().options)

      verify { builder wasNot Called }
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

    // ==================== Announcing a connection's agent ====================
    //
    // October #26: the transport filter adds each connection's context pending, and only connectAgent announced it, so
    // a client that skipped connectAgent, learning its agentId from the agent-id header any call returns, served paths
    // while missing from the dashboard's agent list, proxy_agent_map_size, the health checks, and the connected and
    // disconnected events. Any call that passes authentication now announces it.

    "a connection that skips connectAgent should be announced by its first call" {
      val (proxy, manager) = proxyWithRealManagers()
      withChannelTo(proxy) { channel ->
        val stub = PathRegistrar(channel)

        // The first call names no agentId it could know, and is refused, but its response headers give it away.
        stub.registerPath("unknown", "metrics").rejectionCause shouldBe PathRejectionCause.BINDING_MISMATCH
        manager.agentContextSize shouldBe 1

        val response = stub.registerPath(stub.agentId, "metrics")
        response.valid.shouldBeTrue()
        manager.agentContextSize shouldBe 1
        manager.agentContextEntries.single().key shouldBe stub.agentId
      }
    }

    "a connection should stay pending until a call passes agent authentication" {
      val authManager = AgentAuthManager.create([AuthEntry("team_a", "s3cret", ["team_a_*"])], legacyToken = "")
      val (proxy, manager) = proxyWithRealManagers(authManager)
      withChannelTo(proxy) { channel ->
        val failure =
          shouldThrow<StatusRuntimeException> { PathRegistrar(channel, token = "wrong").registerPath("1", "x") }
        failure.status.code shouldBe Status.Code.UNAUTHENTICATED
        manager.agentContextSize shouldBe 0

        PathRegistrar(channel, token = "s3cret").registerPath("unknown", "team_a_metrics")
        manager.agentContextSize shouldBe 1
      }
    }

    "connectAgent should announce the connection's agent" {
      val (proxy, manager) = proxyWithRealManagers()
      withChannelTo(proxy) { channel ->
        ProxyServiceGrpc.newBlockingStub(channel).connectAgent(EMPTY_INSTANCE)
        manager.agentContextSize shouldBe 1
      }
    }
  }

  // A mocked proxy, with the transport filter on, whose agent-context and path managers are real, so a test can see
  // what a call over the wire does to them.
  private fun proxyWithRealManagers(authManager: AgentAuthManager? = null): Pair<Proxy, AgentContextManager> {
    val proxy = createMockProxy(transportFilterDisabled = false, authManager = authManager)
    val manager = AgentContextManager(isTestMode = true)
    every { proxy.agentContextManager } returns manager
    every { proxy.pathManager } returns ProxyPathManager(proxy, isTestMode = true)
    return proxy to manager
  }

  // A client that registers paths over [channel] without calling connectAgent, presenting [token] when given, and
  // keeps the agentId the proxy's last response header named.
  private class PathRegistrar(
    channel: ManagedChannel,
    token: String? = null,
  ) {
    private val headers = AtomicReference<Metadata>()
    private val stub =
      ProxyServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(headers, AtomicReference()))
        .let { if (token == null) it else it.withInterceptors(AgentTokenClientInterceptor(token)) }

    val agentId: String get() = headers.get().shouldNotBeNull().get(META_AGENT_ID_KEY).shouldNotBeNull()

    fun registerPath(
      agentId: String,
      path: String,
    ): RegisterPathResponse =
      stub.registerPath(
        registerPathRequest {
          this.agentId = agentId
          this.path = path
        },
      )
  }
}
