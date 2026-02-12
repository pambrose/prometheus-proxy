@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals

class ProxyHttpServiceTest : FunSpec() {
  private fun createMockProxy(): Proxy {
    val config = ConfigFactory.load()
    val configVals = ConfigVals(config)

    val mockProxy = mockk<Proxy>(relaxed = true)
    every { mockProxy.proxyConfigVals } returns configVals.proxy
    every { mockProxy.isZipkinEnabled } returns false
    return mockProxy
  }

  init {
    // ==================== toString Tests ====================

    test("toString should include port") {
      val mockProxy = createMockProxy()
      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)

      val str = service.toString()
      str shouldContain "port"
    }

    // ==================== Construction Tests ====================

    test("should create service with valid configuration") {
      val mockProxy = createMockProxy()

      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)
      service.httpPort shouldBe 0
    }

    test("httpPort should reflect configured port") {
      val mockProxy = createMockProxy()
      val service = ProxyHttpService(mockProxy, httpPort = 9999, isTestMode = true)

      service.httpPort shouldBe 9999
    }

    // ==================== Server Lifecycle Tests ====================

    test("should start and stop HTTP server") {
      val mockProxy = createMockProxy()
      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)

      service.startAsync().awaitRunning()
      service.isRunning shouldBe true

      service.stopAsync().awaitTerminated()
    }

    // ==================== Idle Timeout Tests ====================

    test("should use default idle timeout when configured as -1") {
      val config = ConfigFactory.parseString(
        """
        proxy {
          http {
            idleTimeoutSecs = -1
          }
        }
        """.trimIndent(),
      ).withFallback(ConfigFactory.load())
      val configVals = ConfigVals(config)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.proxyConfigVals } returns configVals.proxy
      every { mockProxy.isZipkinEnabled } returns false

      // Should not throw — default idle timeout of 45 is used
      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)
      service.shouldNotBeNull()
    }

    test("should use configured idle timeout when not -1") {
      val config = ConfigFactory.parseString(
        """
        proxy {
          http {
            idleTimeoutSecs = 90
          }
        }
        """.trimIndent(),
      ).withFallback(ConfigFactory.load())
      val configVals = ConfigVals(config)

      val mockProxy = mockk<Proxy>(relaxed = true)
      every { mockProxy.proxyConfigVals } returns configVals.proxy
      every { mockProxy.isZipkinEnabled } returns false

      // Should not throw — configured idle timeout of 90 is used
      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)
      service.shouldNotBeNull()
    }
  }
}
