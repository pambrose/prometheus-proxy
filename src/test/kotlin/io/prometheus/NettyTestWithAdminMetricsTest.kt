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

import com.github.pambrose.common.dsl.KtorDsl.get
import com.github.pambrose.common.dsl.KtorDsl.http
import com.github.pambrose.common.dsl.KtorDsl.newHttpClient
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.sleep
import io.ktor.client.statement.readText
import io.ktor.http.HttpStatusCode
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import kotlinx.coroutines.runBlocking
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldBeGreaterThan
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.time.seconds

class NettyTestWithAdminMetricsTest : CommonTests(ProxyCallTestArgs(agent = agent,
                                                                    startPort = 10300,
                                                                    caller = simpleClassName)) {

  @Test
  fun adminDebugCallsTest() {
    newHttpClient()
        .use { httpClient ->
          runBlocking {
            http(httpClient) {
              get("8093/debug".addPrefix()) { response ->
                val body = response.readText()
                body.length shouldBeGreaterThan 100
                response.status shouldBeEqualTo HttpStatusCode.OK
              }
            }

            http(httpClient) {
              get("8092/debug".addPrefix()) { response ->
                val body = response.readText()
                body.length shouldBeGreaterThan 100
                response.status shouldBeEqualTo HttpStatusCode.OK
              }
            }
          }
        }
  }


  companion object : CommonCompanion() {

    @JvmStatic
    @BeforeAll
    fun setUp() = setItUp({
                            startProxy(adminEnabled = true,
                                       debugEnabled = true,
                                       metricsEnabled = true)
                          },
                          {
                            startAgent(adminEnabled = true,
                                       debugEnabled = true,
                                       metricsEnabled = true,
                                       chunkContentSizeKbs = 5)
                          },
                          {
                            // Wait long enough to trigger heartbeat for code coverage
                            sleep(15.seconds)
                          })

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}