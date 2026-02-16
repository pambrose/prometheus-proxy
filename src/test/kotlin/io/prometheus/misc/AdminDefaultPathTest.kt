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

package io.prometheus.misc

import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.prometheus.harness.HarnessConstants.PROXY_PORT
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.withPrefix

class AdminDefaultPathTest : StringSpec() {
  private val agentConfigVals by lazy { agent.agentConfigVals }
  private val proxyConfigVals by lazy { proxy.proxyConfigVals }

  companion object : HarnessSetup()

  init {
    beforeSpec {
      setupProxyAndAgent(
        proxyPort = PROXY_PORT,
        proxySetup = { startProxy(adminEnabled = true) },
        agentSetup = { startAgent(adminEnabled = true) },
      )
    }

    afterSpec {
      takeDownProxyAndAgent()
    }

    "proxy ping path should respond with pong" {
      proxyConfigVals.admin.apply {
        blockingGet("$port/$pingPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldStartWith "pong"
        }
      }
    }

    "agent ping path should respond with pong" {
      agentConfigVals.admin.apply {
        blockingGet("$port/$pingPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldStartWith "pong"
        }
      }
    }

    "proxy version path should return version info" {
      agentConfigVals.admin.apply {
        blockingGet("$port/$versionPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain "version"
        }
      }
    }

    "agent version path should return version info" {
      agentConfigVals.admin.apply {
        blockingGet("$port/$versionPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText() shouldContain "version"
        }
      }
    }

    "proxy health check path should return health status" {
      proxyConfigVals.admin.apply {
        blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText().length shouldBeGreaterThan 10
        }
      }
    }

    "agent health check path should return health status" {
      agentConfigVals.admin.apply {
        blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText().length shouldBeGreaterThan 10
        }
      }
    }

    "proxy thread dump path should return thread dump" {
      proxyConfigVals.admin.apply {
        blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText().length shouldBeGreaterThan 10
        }
      }
    }

    "agent thread dump path should return thread dump" {
      agentConfigVals.admin.apply {
        blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
          response.status shouldBe HttpStatusCode.OK
          response.bodyAsText().length shouldBeGreaterThan 10
        }
      }
    }
  }
}
