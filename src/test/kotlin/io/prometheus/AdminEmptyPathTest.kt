/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus

import CommonCompanion
import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import io.ktor.http.HttpStatusCode
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.common.ConfigVals
import org.amshove.kluent.shouldEqual
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.time.seconds

class AdminEmptyPathTest {

  private val proxyConfigVals: ConfigVals.Proxy2 = proxy.configVals.proxy

  @Test
  fun proxyPingPathTest() {
    proxyConfigVals.admin
        .also { admin ->
          admin.port shouldEqual 8098
          admin.pingPath shouldEqual ""

          blockingGet("${admin.port}/${admin.pingPath}".fixUrl()) { response ->
            response.status shouldEqual HttpStatusCode.NotFound
          }
        }
  }

  @Test
  fun proxyVersionPathTest() {
    proxyConfigVals.admin
        .also { admin ->
          admin.port shouldEqual 8098
          admin.versionPath shouldEqual ""

          blockingGet("${admin.port}/${admin.versionPath}".fixUrl()) { response ->
            response.status shouldEqual HttpStatusCode.NotFound
          }
        }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    proxyConfigVals.admin
        .also { admin ->
          admin.healthCheckPath shouldEqual ""

          blockingGet("${admin.port}/${admin.healthCheckPath}".fixUrl()) { response ->
            response.status shouldEqual HttpStatusCode.NotFound
          }
        }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    proxyConfigVals.admin
        .also { admin ->
          admin.threadDumpPath shouldEqual ""

          blockingGet("${admin.port}/${admin.threadDumpPath}".fixUrl()) { response ->
            response.status shouldEqual HttpStatusCode.NotFound
          }
        }
  }

  companion object : CommonCompanion() {

    @JvmStatic
    @BeforeAll
    fun setUp() = setItUp({
                            startProxy(adminEnabled = true, argv = listOf("-Dproxy.admin.port=8098",
                                                                          "-Dproxy.admin.pingPath=\"\"",
                                                                          "-Dproxy.admin.versionPath=\"\"",
                                                                          "-Dproxy.admin.healthCheckPath=\"\"",
                                                                          "-Dproxy.admin.threadDumpPath=\"\""))
                          },
                          { startAgent(adminEnabled = true).apply { awaitInitialConnection(5.seconds) } })

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}