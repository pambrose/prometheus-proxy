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

import com.github.pambrose.common.util.simpleClassName
import io.prometheus.ProxyTests.timeoutTest
import io.prometheus.SimpleTests.addRemovePathsTest
import io.prometheus.SimpleTests.invalidAgentUrlTest
import io.prometheus.SimpleTests.invalidPathTest
import io.prometheus.SimpleTests.missingPathTest
import io.prometheus.SimpleTests.threadedAddRemovePathsTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

abstract class TestTemplate(
  private val args: ProxyCallTestArgs,
) {
  @Test
  fun proxyCallTest() = runBlocking { ProxyTests.proxyCallTest(args) }

  @Test
  fun missingPathTest() = missingPathTest(simpleClassName)

  @Test
  fun invalidPathTest() = invalidPathTest(simpleClassName)

  @Test
  fun addRemovePathsTest() = runBlocking { addRemovePathsTest(args.agent.pathManager, simpleClassName) }

  @Test
  fun threadedAddRemovePathsTest() = runBlocking { threadedAddRemovePathsTest(args.agent.pathManager, simpleClassName) }

  @Test
  fun invalidAgentUrlTest() = runBlocking { invalidAgentUrlTest(args.agent.pathManager, simpleClassName) }

  @Test
  fun timeoutTest() = runBlocking { timeoutTest(args.agent.pathManager, simpleClassName) }
}
