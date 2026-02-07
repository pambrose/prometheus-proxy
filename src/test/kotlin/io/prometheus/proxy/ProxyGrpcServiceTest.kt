@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import org.junit.jupiter.api.Test

class ProxyGrpcServiceTest {
  private fun createMockProxy(): Proxy {
    val mockOptions = mockk<ProxyOptions>(relaxed = true)
    every { mockOptions.certChainFilePath } returns ""
    every { mockOptions.privateKeyFilePath } returns ""
    every { mockOptions.trustCertCollectionFilePath } returns ""
    every { mockOptions.transportFilterDisabled } returns true
    every { mockOptions.reflectionDisabled } returns false
    every { mockOptions.handshakeTimeoutSecs } returns -1L
    every { mockOptions.keepAliveTimeSecs } returns -1L
    every { mockOptions.keepAliveTimeoutSecs } returns -1L
    every { mockOptions.permitKeepAliveWithoutCalls } returns false
    every { mockOptions.permitKeepAliveTimeSecs } returns -1L
    every { mockOptions.maxConnectionIdleSecs } returns -1L
    every { mockOptions.maxConnectionAgeSecs } returns -1L
    every { mockOptions.maxConnectionAgeGraceSecs } returns -1L

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.options } returns mockOptions
    every { mockProxy.isZipkinEnabled } returns false
    return mockProxy
  }

  // ==================== toString Tests ====================

  @Test
  fun `toString for InProcess server should indicate InProcess type`() {
    val mockProxy = createMockProxy()
    val service = ProxyGrpcService(mockProxy, inProcessName = "test-server")

    val str = service.toString()
    str shouldContain "InProcess"
    str shouldContain "test-server"
  }

  @Test
  fun `toString for Netty server should indicate Netty type and port`() {
    val mockProxy = createMockProxy()
    val service = ProxyGrpcService(mockProxy, port = 50051)

    val str = service.toString()
    str shouldContain "Netty"
    str shouldContain "50051"
    str shouldNotContain "InProcess"
  }

  // ==================== HealthCheck Tests ====================

  @Test
  fun `healthCheck should not be null`() {
    val mockProxy = createMockProxy()
    val service = ProxyGrpcService(mockProxy, inProcessName = "health-test")

    service.healthCheck.shouldNotBeNull()
  }

  // ==================== Server Configuration Tests ====================

  @Test
  fun `should create InProcess server without throwing`() {
    val mockProxy = createMockProxy()

    // Should not throw
    val service = ProxyGrpcService(mockProxy, inProcessName = "config-test")
    service.shouldNotBeNull()
  }

  @Test
  fun `should create Netty server without throwing`() {
    val mockProxy = createMockProxy()

    // Should not throw
    val service = ProxyGrpcService(mockProxy, port = 0)
    service.shouldNotBeNull()
  }
}
