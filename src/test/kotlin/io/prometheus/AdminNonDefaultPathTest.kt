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
import io.prometheus.common.ConfigVals
import io.prometheus.common.Utils.lambda
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AdminNonDefaultPathTest {
  private val proxyConfigVals: ConfigVals.Proxy2 = proxy.configVals.proxy

  @Test
  fun proxyPingPathTest() {
    with(proxyConfigVals.admin) {
      port shouldBeEqualTo 8099
      pingPath shouldBeEqualTo "pingPath2"

      blockingGet("$port/$pingPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldStartWith "pong"
      }
    }
  }

  @Test
  fun proxyVersionPathTest() {
    with(proxyConfigVals.admin) {
      port shouldBeEqualTo 8099
      versionPath shouldBeEqualTo "versionPath2"

      blockingGet("$port/$versionPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText() shouldContain "version"
      }
    }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    with(proxyConfigVals.admin) {
      healthCheckPath shouldBeEqualTo "healthCheckPath2"

      blockingGet("$port/$healthCheckPath".withPrefix()) { response ->
        response.status shouldBeEqualTo HttpStatusCode.OK
        response.bodyAsText().length shouldBeGreaterThan 10
      }
    }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    with(proxyConfigVals.admin) {
      threadDumpPath shouldBeEqualTo "threadDumpPath2"

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
        proxySetup = lambda {
          startProxy(
            adminEnabled = true,
            argv = listOf(
              "-Dproxy.admin.port=8099",
              "-Dproxy.admin.pingPath=pingPath2",
              "-Dproxy.admin.versionPath=versionPath2",
              "-Dproxy.admin.healthCheckPath=healthCheckPath2",
              "-Dproxy.admin.threadDumpPath=threadDumpPath2",
            ),
          )
        },
        agentSetup = lambda { startAgent(adminEnabled = true) },
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}
