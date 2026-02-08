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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.prometheus.common.Utils.lambda
import io.prometheus.harness.support.HarnessSetup
import io.prometheus.harness.support.TestUtils.startAgent
import io.prometheus.harness.support.TestUtils.startProxy
import io.prometheus.harness.support.withPrefix
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AdminDefaultPathTest {
  private val agentConfigVals = agent.agentConfigVals
  private val proxyConfigVals = proxy.proxyConfigVals

  @Test
  fun proxyPingPathTest() {
    proxyConfigVals.admin.apply {
      blockingGet("$port/$pingPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldStartWith "pong"
      }
    }
  }

  @Test
  fun agentPingPathTest() {
    agentConfigVals.admin.apply {
      blockingGet("$port/$pingPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldStartWith "pong"
      }
    }
  }

  @Test
  fun proxyVersionPathTest() {
    agentConfigVals.admin.apply {
      blockingGet("$port/$versionPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "version"
      }
    }
  }

  @Test
  fun agentVersionPathTest() {
    agentConfigVals.admin.apply {
      blockingGet("$port/$versionPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText() shouldContain "version"
      }
    }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    proxyConfigVals.admin.apply {
      blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  @Test
  fun agentHealthCheckPathTest() {
    agentConfigVals.admin.apply {
      blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    proxyConfigVals.admin.apply {
      blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  @Test
  fun agentThreadDumpPathTest() {
    agentConfigVals.admin.apply {
      blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
        response.status shouldBe HttpStatusCode.OK
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  companion object : HarnessSetup() {
    @JvmStatic
    @BeforeAll
    fun setUp() =
      setupProxyAndAgent(
        proxySetup = lambda { startProxy(adminEnabled = true) },
        agentSetup = lambda { startAgent(adminEnabled = true) },
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeDownProxyAndAgent()
  }
}
