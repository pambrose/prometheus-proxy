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
import io.kotest.core.spec.style.FunSpec

abstract class AbstractHarnessTests(
  private val argsProvider: () -> ProxyCallTestArgs,
) : FunSpec() {
  init {
    test("should scrape metrics through proxy") {
      HarnessTests.proxyCallTest(argsProvider())
    }

    test("should return not found for missing path") {
      BasicHarnessTests.missingPathTest(
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    test("should return not found for invalid path") {
      BasicHarnessTests.invalidPathTest(
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    test("should add and remove paths correctly") {
      BasicHarnessTests.addRemovePathsTest(
        argsProvider().agent.pathManager,
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    test("should add and remove paths correctly under concurrent access") {
      BasicHarnessTests.threadedAddRemovePathsTest(
        argsProvider().agent.pathManager,
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    test("should handle invalid agent URL gracefully") {
      BasicHarnessTests.invalidAgentUrlTest(
        argsProvider().agent.pathManager,
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    test("should timeout when scrape exceeds deadline") {
      HarnessTests.timeoutTest(
        argsProvider().agent.pathManager,
        simpleClassName,
        argsProvider().proxyPort,
      )
    }
  }
}
