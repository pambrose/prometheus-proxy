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

import com.github.pambrose.common.dsl.KtorDsl.blockingGet
import com.github.pambrose.common.util.simpleClassName
import io.ktor.client.response.readText
import io.ktor.http.HttpStatusCode
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import io.prometheus.client.CollectorRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mu.KLogging
import org.amshove.kluent.shouldBeGreaterThan
import org.amshove.kluent.shouldContain
import org.amshove.kluent.shouldEqual
import org.amshove.kluent.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.time.seconds

class AdminDefaultPathTest {

  private val agentConfigVals = agent.configVals.agent
  private val proxyConfigVals = proxy.configVals.proxy

  @Test
  fun proxyPingPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.pingPath}".fixUrl()) { resp ->
          resp.status shouldEqual HttpStatusCode.OK
          resp.readText() shouldStartWith "pong"
        }
      }
  }

  @Test
  fun agentPingPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.pingPath}".fixUrl()) { resp ->
          resp.status shouldEqual HttpStatusCode.OK
          resp.readText() shouldStartWith "pong"
        }
      }
  }

  @Test
  fun proxyVersionPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.versionPath}".fixUrl()) { resp ->
          resp.status shouldEqual HttpStatusCode.OK
          resp.readText() shouldContain "Version"
        }
      }
  }

  @Test
  fun agentVersionPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.versionPath}".fixUrl()) { resp ->
          resp.status shouldEqual HttpStatusCode.OK
          resp.readText() shouldContain "Version"
        }
      }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.healthCheckPath}".fixUrl()) { resp ->
          resp.status shouldEqual HttpStatusCode.OK
          resp.readText().length shouldBeGreaterThan 10
        }
      }
  }

  @Test
  fun agentHealthCheckPathTest() {
    agentConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.healthCheckPath}".fixUrl()) { resp ->
          resp.readText().length shouldBeGreaterThan 10
        }
      }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        blockingGet("${admin.port}/${admin.threadDumpPath}".fixUrl()) { resp ->
          resp.readText().length shouldBeGreaterThan 10
        }
      }
  }

  @Test
  fun agentThreadDumpPathTest() {
    blockingGet("${agentConfigVals.admin.port}/${agentConfigVals.admin.threadDumpPath}".fixUrl()) { resp ->
      resp.readText().length shouldBeGreaterThan 10
    }
  }

  companion object : KLogging() {
    private lateinit var proxy: Proxy
    private lateinit var agent: Agent

    @JvmStatic
    @BeforeAll
    fun setUp() {
      CollectorRegistry.defaultRegistry.clear()

      runBlocking {
        launch(Dispatchers.Default) { proxy = startProxy(adminEnabled = true) }
        launch(Dispatchers.Default) {
          agent = startAgent(adminEnabled = true).apply { awaitInitialConnection(5.seconds) }
        }
      }
      logger.info { "Started ${proxy.simpleClassName} and ${agent.simpleClassName}" }
    }

    @JvmStatic
    @AfterAll
    fun takeDown() {
      runBlocking {
        for (service in listOf(proxy, agent)) {
          logger.info { "Stopping ${service.simpleClassName}" }
          launch(Dispatchers.Default) { service.stopSync() }
        }
      }
      logger.info { "Finished stopping ${proxy.simpleClassName} and ${agent.simpleClassName}" }
    }
  }
}
