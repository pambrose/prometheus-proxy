/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Proxy
import io.prometheus.common.ConfigVals

class ProxyHttpServiceTest : StringSpec() {
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

    "toString should include port" {
      val mockProxy = createMockProxy()
      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)

      val str = service.toString()
      str shouldContain "port"
    }

    // ==================== Construction Tests ====================

    "should create service with valid configuration" {
      val mockProxy = createMockProxy()

      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)
      service.httpPort shouldBe 0
    }

    "httpPort should reflect configured port" {
      val mockProxy = createMockProxy()
      val service = ProxyHttpService(mockProxy, httpPort = 9999, isTestMode = true)

      service.httpPort shouldBe 9999
    }

    // ==================== Server Lifecycle Tests ====================

    "should start and stop HTTP server" {
      val mockProxy = createMockProxy()
      val service = ProxyHttpService(mockProxy, httpPort = 0, isTestMode = true)

      service.startAsync().awaitRunning()
      service.isRunning shouldBe true

      service.stopAsync().awaitTerminated()
    }

    // ==================== Idle Timeout Tests ====================

    "should use default idle timeout when configured as -1" {
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

    "should use configured idle timeout when not -1" {
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
