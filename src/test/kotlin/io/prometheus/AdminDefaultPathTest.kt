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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.common.Utils.lambda
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AdminDefaultPathTest {
  private val agentConfigVals = agent.agentConfigVals
  private val proxyConfigVals = proxy.proxyConfigVals

  @Test
  fun proxyPingPathTest() {
    with(proxyConfigVals.admin) {
      blockingGet("$port/$pingPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldStartWith "pong"
      }
    }
  }

  @Test
  fun agentPingPathTest() {
    with(agentConfigVals.admin) {
      blockingGet("$port/$pingPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldStartWith "pong"
      }
    }
  }

  @Test
  fun proxyVersionPathTest() {
    with(agentConfigVals.admin) {
      blockingGet("$port/$versionPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldContain "Version"
      }
    }
  }

  @Test
  fun agentVersionPathTest() {
    with(agentConfigVals.admin) {
      blockingGet("$port/$versionPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldContain "Version"
      }
    }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    with(proxyConfigVals.admin) {
      blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  @Test
  fun agentHealthCheckPathTest() {
    with(agentConfigVals.admin) {
      blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    with(proxyConfigVals.admin) {
      blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  @Test
  fun agentThreadDumpPathTest() {
    with(agentConfigVals.admin) {
      blockingGet("$port/$threadDumpPath".withPrefix()) { response ->
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  companion object : CommonCompanion() {
    @JvmStatic
    @BeforeAll
    fun setUp() =
      setItUp(
        proxySetup = lambda { startProxy(adminEnabled = true) },
        agentSetup = lambda { startAgent(adminEnabled = true) },
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}
