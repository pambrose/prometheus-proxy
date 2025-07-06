/*
 * Copyright © 2025 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.harness.support

import com.github.pambrose.common.util.simpleClassName
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

abstract class AbstractTests(
  private val args: ProxyCallTestArgs,
) {
  @Test
  fun proxyCallTest() = runBlocking { ProxyTests.proxyCallTest(args) }

  @Test
  fun missingPathTest() = SimpleTests.missingPathTest(simpleClassName)

  @Test
  fun invalidPathTest() = SimpleTests.invalidPathTest(simpleClassName)

  @Test
  fun addRemovePathsTest() = runBlocking { SimpleTests.addRemovePathsTest(args.agent.pathManager, simpleClassName) }

  @Test
  fun threadedAddRemovePathsTest() =
    runBlocking { SimpleTests.threadedAddRemovePathsTest(args.agent.pathManager, simpleClassName) }

  @Test
  fun invalidAgentUrlTest() = runBlocking { SimpleTests.invalidAgentUrlTest(args.agent.pathManager, simpleClassName) }

  @Test
  fun timeoutTest() = runBlocking { ProxyTests.timeoutTest(args.agent.pathManager, simpleClassName) }
}
