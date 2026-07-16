/*
 * Copyright © 2026 Paul Ambrose
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

import com.pambrose.common.util.simpleClassName
import io.kotest.core.spec.style.StringSpec
import io.prometheus.harness.HarnessConstants.HARNESS_CONFIG
import kotlin.time.Duration.Companion.seconds

abstract class AbstractHarnessTests(
  private val argsProvider: () -> ProxyCallTestArgs,
) : StringSpec() {
  init {
    // proxyCallTest runs a sequential and a parallel scrape phase, each guarded by
    // withTimeout(HARNESS_CONFIG.proxyCallTimeoutSecs). Kotest's per-invocation timeout
    // defaults to 60s, which would fire before the code's own guard under load — and it
    // silently caps the LARGE+ configs, whose 120-480s budgets could otherwise never run.
    // Raise the spec-level ceilings above the sum of both phases so the code's withTimeout
    // stays the real limit (and failures surface with its message, not a Kotest cutoff).
    val harnessTimeoutMillis = ((HARNESS_CONFIG.proxyCallTimeoutSecs * 2) + 60).seconds.inWholeMilliseconds
    timeout = harnessTimeoutMillis
    invocationTimeout = harnessTimeoutMillis

    "should scrape metrics through proxy" {
      HarnessTests.proxyCallTest(argsProvider())
    }

    "should return not found for missing path" {
      BasicHarnessTests.missingPathTest(
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    "should return not found for invalid path" {
      BasicHarnessTests.invalidPathTest(
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    "should add and remove paths correctly" {
      BasicHarnessTests.addRemovePathsTest(
        argsProvider().agent.pathManager,
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    "should add and remove paths correctly under concurrent access" {
      BasicHarnessTests.threadedAddRemovePathsTest(
        argsProvider().agent.pathManager,
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    "should handle invalid agent URL gracefully" {
      BasicHarnessTests.invalidAgentUrlTest(
        argsProvider().agent.pathManager,
        argsProvider().proxyPort,
        simpleClassName,
      )
    }

    "should timeout when scrape exceeds deadline" {
      HarnessTests.timeoutTest(
        argsProvider().agent.pathManager,
        simpleClassName,
        argsProvider().proxyPort,
      )
    }
  }
}
