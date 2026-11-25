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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

class NonFatalTest : StringSpec() {
  init {
    "runCatchingNonFatal should return what its block returns, or the exception it throws" {
      runCatchingNonFatal {
        delay(1.milliseconds)
        42
      }.getOrNull() shouldBe 42
      runCatchingNonFatal { throw IllegalStateException("failed") }.exceptionOrNull()?.message shouldBe "failed"
    }

    // A JVM Error stops the agent, and a cancellation isn't a failure, so neither is returned as one (November #7).
    "runCatchingNonFatal should rethrow a JVM Error and a CancellationException" {
      shouldThrow<OutOfMemoryError> { runCatchingNonFatal { throw OutOfMemoryError("test") } }
      shouldThrow<StackOverflowError> { runCatchingNonFatal { throw StackOverflowError("test") } }
      shouldThrow<AssertionError> { runCatchingNonFatal { throw AssertionError("test") } }
      shouldThrow<CancellationException> { runCatchingNonFatal { throw CancellationException("test") } }
    }
  }
}
