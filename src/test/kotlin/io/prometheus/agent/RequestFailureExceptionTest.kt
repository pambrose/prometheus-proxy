/*
 * Copyright © 2026 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.agent

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class RequestFailureExceptionTest : StringSpec() {
  init {
    "should store message" {
      val exception = RequestFailureException("test error message")

      exception.message shouldBe "test error message"
    }

    "should be throwable as Exception" {
      val exception = RequestFailureException("failure")

      exception.shouldBeInstanceOf<Exception>()
    }

    "should be catchable" {
      val caught = shouldThrow<RequestFailureException> {
        throw RequestFailureException("expected failure")
      }

      caught.message shouldBe "expected failure"
    }

    "should have null cause by default" {
      val exception = RequestFailureException("no cause")

      exception.cause.shouldBeNull()
    }

    "should handle empty message" {
      val exception = RequestFailureException("")

      exception.message shouldBe ""
    }

    "should handle message with special characters" {
      val msg = "Agent (true) and Proxy (false) do not have matching transportFilterDisabled config values"
      val exception = RequestFailureException(msg)

      exception.message shouldBe msg
    }
  }
}
