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
import com.github.pambrose.common.util.simpleClassName
import com.github.pambrose.common.util.sleep
import io.prometheus.TestUtils.startAgent
import io.prometheus.TestUtils.startProxy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.time.seconds

class NettyTestWithAdminMetricsTest : CommonTests(agent,
                                                  ProxyCallTestArgs(agent,
                                                                    httpServerCount = 5,
                                                                    pathCount = 25,
                                                                    sequentialQueryCount = 200,
                                                                    parallelQueryCount = 20,
                                                                    startPort = 10900,
                                                                    caller = simpleClassName)) {

  companion object : CommonCompanion() {

    @JvmStatic
    @BeforeAll
    fun setUp() = setItUp({ startProxy(adminEnabled = true, metricsEnabled = true) },
                          { startAgent(adminEnabled = true, metricsEnabled = true, chunkContentSizeKbs = 5) },
                          {
                            // Wait long enough to trigger heartbeat for code coverage
                            sleep(15.seconds)
                          })

    @JvmStatic
    @AfterAll
    fun takeDown() = takeItDown()
  }
}