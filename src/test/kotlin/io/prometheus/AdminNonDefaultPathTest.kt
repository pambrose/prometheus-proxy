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
import io.prometheus.common.ConfigVals
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

class AdminNonDefaultPathTest {

  private val proxyConfigVals: ConfigVals.Proxy2 = proxy.configVals.proxy

  @Test
  fun proxyPingPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.port shouldEqual 8099
        admin.pingPath shouldEqual "pingPath2"

        blockingGet("${admin.port}/${admin.pingPath}".fixUrl()) { response ->
          response.status shouldEqual HttpStatusCode.OK
          response.readText() shouldStartWith "pong"
        }
      }
  }

  @Test
  fun proxyVersionPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.port shouldEqual 8099
        admin.versionPath shouldEqual "versionPath2"

        blockingGet("${admin.port}/${admin.versionPath}".fixUrl()) { response ->
          response.status shouldEqual HttpStatusCode.OK
          response.readText() shouldContain "Version"
        }
      }
  }

  @Test
  fun proxyHealthCheckPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.healthCheckPath shouldEqual "healthCheckPath2"

        blockingGet("${admin.port}/${admin.healthCheckPath}".fixUrl()) { response ->
          response.status shouldEqual HttpStatusCode.OK
          response.readText().length shouldBeGreaterThan 10
        }
      }
  }

  @Test
  fun proxyThreadDumpPathTest() {
    proxyConfigVals.admin
      .also { admin ->
        admin.threadDumpPath shouldEqual "threadDumpPath2"

        blockingGet("${admin.port}/${admin.threadDumpPath}".fixUrl()) { response ->
          response.readText().length shouldBeGreaterThan 10
        }
      }
  }

  companion object : KLogging() {
    private lateinit var proxy: Proxy
    private lateinit var agent: Agent

    @JvmStatic
    @BeforeAll
    fun setUp() {
      CollectorRegistry.defaultRegistry.clear()
      val proxyArgs = listOf("-Dproxy.admin.port=8099",
                             "-Dproxy.admin.pingPath=pingPath2",
                             "-Dproxy.admin.versionPath=versionPath2",
                             "-Dproxy.admin.healthCheckPath=healthCheckPath2",
                             "-Dproxy.admin.threadDumpPath=threadDumpPath2")

      runBlocking {
        launch(Dispatchers.Default) { proxy = startProxy(adminEnabled = true, argv = proxyArgs) }
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
