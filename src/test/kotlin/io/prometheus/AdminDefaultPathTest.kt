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
import io.ktor.client.statement.*
import io.ktor.http.*
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class AdminDefaultPathTest {

  private val agentConfigVals = agent.configVals.agent
  private val proxyConfigVals = proxy.configVals.proxy

  @Test
  fun proxyPingPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.pingPath}".withPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.OK
          response.readText() shouldStartWith "pong"
        }
      }
  }

  @Test
  fun agentPingPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.pingPath}".withPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.OK
          response.readText() shouldStartWith "pong"
        }
      }
  }

  @Test
  fun proxyVersionPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.versionPath}".withPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.OK
          response.readText() shouldContain "Version"
        }
      }
  }

  @Test
  fun agentVersionPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.versionPath}".withPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.OK
          response.readText() shouldContain "Version"
        }
      }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.healthCheckPath}".withPrefix()) { response ->
          response.status shouldBeEqualTo HttpStatusCode.OK
          response.readText().length shouldBeGreaterThan 10
        }
      }
  }

  @Test
  fun agentHealthCheckPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.healthCheckPath}".withPrefix()) { response ->
          response.readText().length shouldBeGreaterThan 10
        }
      }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.threadDumpPath}".withPrefix()) { response ->
          response.readText().length shouldBeGreaterThan 10
        }
      }
  }

  @Test
  fun agentThreadDumpPathTest() {
    blockingGet("${agentConfigVals.admin.port}/${agentConfigVals.admin.threadDumpPath}".withPrefix()) { response ->
      response.readText().length shouldBeGreaterThan 10
    }
  }

  companion object : CommonCompanion() {

    @JvmStatic
    @BeforeAll
    fun setUp() =
      setItUp(
          { startProxy(adminEnabled = true) },
          { startAgent(adminEnabled = true) }
      )

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}