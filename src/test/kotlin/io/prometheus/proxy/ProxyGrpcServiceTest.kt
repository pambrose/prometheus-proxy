@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy

class ProxyGrpcServiceTest : FunSpec() {
  private fun createMockProxy(
    transportFilterDisabled: Boolean = true,
    reflectionDisabled: Boolean = false,
    handshakeTimeoutSecs: Long = -1L,
    keepAliveTimeSecs: Long = -1L,
    keepAliveTimeoutSecs: Long = -1L,
    permitKeepAliveWithoutCalls: Boolean = false,
    permitKeepAliveTimeSecs: Long = -1L,
    maxConnectionIdleSecs: Long = -1L,
    maxConnectionAgeSecs: Long = -1L,
    maxConnectionAgeGraceSecs: Long = -1L,
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
    return mockProxy
  }

  init {
    // ==================== toString Tests ====================

    test("toString for InProcess server should indicate InProcess type") {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "test-server")

      val str = service.toString()
      str shouldContain "InProcess"
      str shouldContain "test-server"
    }

    test("toString for Netty server should indicate Netty type and port") {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, port = 50051)

      val str = service.toString()
      str shouldContain "Netty"
      str shouldContain "50051"
      str shouldNotContain "InProcess"
    }

    // ==================== HealthCheck Tests ====================

    test("healthCheck should not be null") {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "health-test")

      service.healthCheck.shouldNotBeNull()
    }

    // ==================== Server Configuration Tests ====================

    test("should create InProcess server without throwing") {
      val mockProxy = createMockProxy()

      // Should not throw
      val service = ProxyGrpcService(mockProxy, inProcessName = "config-test")
      service.shouldNotBeNull()
    }

    test("should create Netty server without throwing") {
      val mockProxy = createMockProxy()

      // Should not throw
      val service = ProxyGrpcService(mockProxy, port = 0)
      service.shouldNotBeNull()
    }

    // ==================== HealthCheck Result Tests ====================

    test("healthCheck should be healthy before shutdown") {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "health-check-test")

      service.startAsync().awaitRunning()

      val result = service.healthCheck.execute()
      result.isHealthy shouldBe true

      service.stopAsync().awaitTerminated()
    }

    test("healthCheck should be unhealthy after shutdown") {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "health-shutdown-test")

      service.startAsync().awaitRunning()
      service.stopAsync().awaitTerminated()

      val result = service.healthCheck.execute()
      result.isHealthy shouldBe false
      result.message shouldContain "not running"
    }

    // ==================== Server Lifecycle Tests ====================

    test("InProcess server should start and stop gracefully") {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, inProcessName = "lifecycle-test")

      service.startAsync().awaitRunning()
      service.isRunning shouldBe true

      service.stopAsync().awaitTerminated()
    }

    test("Netty server should start and stop gracefully on ephemeral port") {
      val mockProxy = createMockProxy()
      val service = ProxyGrpcService(mockProxy, port = 0)

      service.startAsync().awaitRunning()
      service.isRunning shouldBe true

      service.stopAsync().awaitTerminated()
    }

    // ==================== Server Configuration Branch Tests ====================

    test("should create server with transport filter enabled") {
      val mockProxy = createMockProxy(transportFilterDisabled = false)

      val service = ProxyGrpcService(mockProxy, inProcessName = "transport-filter-test")
      service.shouldNotBeNull()
    }

    test("should create server with keepalive settings") {
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

    test("should create server with reflection disabled") {
      val mockProxy = createMockProxy(reflectionDisabled = true)

      val service = ProxyGrpcService(mockProxy, inProcessName = "no-reflection-test")
      service.shouldNotBeNull()
    }
  }
}
