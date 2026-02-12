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

package io.prometheus.misc

import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.prometheus.common.ConfigVals
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.withPrefix

class AdminEmptyPathTest : FunSpec() {
  private val proxyConfigVals: ConfigVals.Proxy2 by lazy { proxy.configVals.proxy }

  companion object : HarnessSetup()

  init {
    beforeSpec {
      setupProxyAndAgent(
        proxyPort = PROXY_PORT,
        proxySetup = {
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
        agentSetup = { startAgent(adminEnabled = true) },
      )
    }

    afterSpec {
      takeDownProxyAndAgent()
    }

    test("proxyPingPathTest") {
      proxyConfigVals.admin.apply {
        port shouldBe 8098
        pingPath shouldBe ""

        blockingGet("$port/$pingPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.NotFound
        }
      }
    }

    test("proxyVersionPathTest") {
      proxyConfigVals.admin.apply {
        port shouldBe 8098
        versionPath shouldBe ""

        blockingGet("$port/$versionPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.NotFound
        }
      }
    }

    test("proxyHealthCheckPathTest") {
      proxyConfigVals.admin.apply {
        healthCheckPath shouldBe ""

        blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.NotFound
        }
      }
    }

    test("proxyThreadDumpPathTest") {
      proxyConfigVals.admin.apply {
        threadDumpPath shouldBe ""

        blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.NotFound
        }
      }
    }
  }
}
