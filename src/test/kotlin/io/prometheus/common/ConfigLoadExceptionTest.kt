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

package io.prometheus.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

// ConfigLoadException is public API: embedded hosts catch it. Every throw inside the project passes a cause, so
// without these the message-only constructor was never exercised.
class ConfigLoadExceptionTest : StringSpec() {
  init {
    "a message-only ConfigLoadException should carry its message and no cause" {
      val e = ConfigLoadException("Unable to load configuration from 'agent.conf'")

      e.message shouldBe "Unable to load configuration from 'agent.conf'"
      e.cause.shouldBeNull()
    }

    "a ConfigLoadException should keep the cause it was given" {
      val cause = IllegalStateException("parse error")

      ConfigLoadException("Unable to load configuration", cause).cause shouldBeSameInstanceAs cause
    }
  }
}
