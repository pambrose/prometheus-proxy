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
import io.ktor.http.HttpStatusCode
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.common.ConfigVals
import org.amshove.kluent.shouldBeEqualTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AdminEmptyPathTest {

  private val proxyConfigVals: ConfigVals.Proxy2 = proxy.configVals.proxy

  @Test
  fun proxyPingPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.port shouldBeEqualTo 8098
        admin.pingPath shouldBeEqualTo ""

        blockingGet("${admin.port}/${admin.pingPath}".addPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.NotFound
        }
      }
  }

  @Test
  fun proxyVersionPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.port shouldBeEqualTo 8098
        admin.versionPath shouldBeEqualTo ""

        blockingGet("${admin.port}/${admin.versionPath}".addPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.NotFound
        }
      }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.healthCheckPath shouldBeEqualTo ""

        blockingGet("${admin.port}/${admin.healthCheckPath}".addPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.NotFound
        }
      }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.threadDumpPath shouldBeEqualTo ""

        blockingGet("${admin.port}/${admin.threadDumpPath}".addPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.NotFound
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
        { startAgent(adminEnabled = true) })

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}