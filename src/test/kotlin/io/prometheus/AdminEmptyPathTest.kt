/*
 * Copyright © 2020 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus

import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.prometheus.common.ConfigVals
import io.prometheus.common.Utils.lambda
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.withPrefix
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AdminEmptyPathTest {
  private val proxyConfigVals: ConfigVals.Proxy2 = proxy.configVals.proxy

  @Test
  fun proxyPingPathTest() {
    proxyConfigVals.admin.apply {
      port shouldBe 8098
      pingPath shouldBe ""

      blockingGet("$port/$pingPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.NotFound
      }
    }
  }

  @Test
  fun proxyVersionPathTest() {
    proxyConfigVals.admin.apply {
      port shouldBe 8098
      versionPath shouldBe ""

      blockingGet("$port/$versionPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.NotFound
      }
    }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    proxyConfigVals.admin.apply {
      healthCheckPath shouldBe ""

      blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.NotFound
      }
    }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    proxyConfigVals.admin.apply {
      threadDumpPath shouldBe ""

      blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.NotFound
      }
    }
  }

  companion object : HarnessSetup() {
    @JvmStatic
    @BeforeAll
    fun setUp() =
      setupProxyAndAgent(
        proxySetup = lambda {
          startProxy(
            adminEnabled = true,
            args = listOf(
              "-Dproxy.admin.port=8098",
              "-Dproxy.admin.pingPath=\"\"",
              "-Dproxy.admin.versionPath=\"\"",
              "-Dproxy.admin.healthCheckPath=\"\"",
              "-Dproxy.admin.threadDumpPath=\"\"",
            ),
          )
        },
        agentSetup = lambda { startAgent(adminEnabled = true) },
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeDownProxyAndAgent()
  }
}
