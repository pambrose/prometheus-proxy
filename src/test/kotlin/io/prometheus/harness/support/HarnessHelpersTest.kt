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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.harness.support

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.prometheus.harness.HarnessConstants
import java.net.ServerSocket

// The harness helpers used to warn and carry on: a port still held after the wait logged a warning and the spec went
// on to fail at bind time, and a missing test config was silently fetched from GitHub master. Both now fail at once,
// naming the port or file.
class HarnessHelpersTest : StringSpec() {
  init {
    "awaitPortFree should return once the port is free" {
      val port = ServerSocket(0).use { it.localPort }
      awaitPortFree(port, maxAttempts = 3, delayMs = 10)
    }

    "awaitPortFree should fail when the port stays taken" {
      ServerSocket(0).use { taken ->
        val e = shouldThrow<IllegalStateException> { awaitPortFree(taken.localPort, maxAttempts = 2, delayMs = 10) }
        e.message shouldContain "${taken.localPort}"
      }
    }

    "localConfigFile should return a test config that exists" {
      HarnessConstants.localConfigFile(EXISTING_CONFIG) shouldBe EXISTING_CONFIG
    }

    "localConfigFile should fail for a missing test config rather than fetch one from GitHub" {
      val e = shouldThrow<IllegalArgumentException> { HarnessConstants.localConfigFile(MISSING_CONFIG) }
      e.message shouldContain MISSING_CONFIG
    }
  }

  companion object {
    private const val EXISTING_CONFIG = "config/test-configs/harness.conf"
    private const val MISSING_CONFIG = "config/test-configs/no-such-config.conf"
  }
}
