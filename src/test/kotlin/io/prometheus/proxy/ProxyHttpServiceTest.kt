@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import com.typesafe.config.ConfigFactory
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals
import org.junit.jupiter.api.Test

class ProxyHttpServiceTest {
  private fun createMockProxy(): Proxy {
    val config = ConfigFactory.load()
    val configVals = ConfigVals(config)

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.proxyConfigVals } returns configVals.proxy
    every { mockProxy.isZipkinEnabled } returns false
    return mockProxy
  }

  // ==================== toString Tests ====================

  @Test
  fun `toString should include port`() {
    val mockProxy = createMockProxy()
    val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)

    val str = service.toString()
    str shouldContain "port"
  }

  // ==================== Construction Tests ====================

  @Test
  fun `should create service with valid configuration`() {
    val mockProxy = createMockProxy()

    val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)
    service.httpPort shouldBe 0
  }

  @Test
  fun `httpPort should reflect configured port`() {
    val mockProxy = createMockProxy()
    val service = ProxyHttpService(mockProxy, httpPort = 9999, isTestMode = true)

    service.httpPort shouldBe 9999
  }
}
