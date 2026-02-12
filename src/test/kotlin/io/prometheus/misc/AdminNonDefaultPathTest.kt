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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.prometheus.common.ConfigVals
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.withPrefix

class AdminNonDefaultPathTest : FunSpec() {
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
              "-Dproxy.admin.port=8099",
              "-Dproxy.admin.pingPath=pingPath2",
              "-Dproxy.admin.versionPath=versionPath2",
              "-Dproxy.admin.healthCheckPath=healthCheckPath2",
              "-Dproxy.admin.threadDumpPath=threadDumpPath2",
            ),
          )
        },
        agentSetup = { startAgent(adminEnabled = true) },
      )
    }

    afterSpec {
      takeDownProxyAndAgent()
    }

    test("proxy ping path should respond with pong") {
      proxyConfigVals.admin.apply {
        port shouldBe 8099
        pingPath shouldBe "pingPath2"

        blockingGet("$port/$pingPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldStartWith "pong"
        }
      }
    }

    test("proxy version path should return version info") {
      proxyConfigVals.admin.apply {
        port shouldBe 8099
        versionPath shouldBe "versionPath2"

        blockingGet("$port/$versionPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain "version"
        }
      }
    }

    test("proxy health check path should return health status") {
      proxyConfigVals.admin.apply {
        healthCheckPath shouldBe "healthCheckPath2"

        blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText().length shouldBeGreaterThan 10
        }
      }
    }

    test("proxy thread dump path should return thread dump") {
      proxyConfigVals.admin.apply {
        threadDumpPath shouldBe "threadDumpPath2"

        blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
          response.bodyAsText().length shouldBeGreaterThan 10
        }
      }
    }
  }
}
