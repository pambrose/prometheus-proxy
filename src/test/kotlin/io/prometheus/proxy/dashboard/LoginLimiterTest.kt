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

package io.prometheus.proxy.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * [LoginLimiter]: the dashboard's limit on wrong logins, so a guesser can't try passwords in parallel: so many within a
 * minute from one address, and so many from every address together, past which the logins it covers are refused.
 */
class LoginLimiterTest : StringSpec() {
  private fun limiter(clock: TestTimeSource = TestTimeSource()) =
    LoginLimiter(perAddress = 3, overall = 5, window = 1.minutes, timeSource = clock)

  init {
    "an address under the limit should not be refused" {
      limiter().apply {
        repeat(2) { recordFailure(A) }
        isBlocked(A).shouldBeFalse()
      }
    }

    "an address at its limit should be refused, and no other" {
      limiter().apply {
        repeat(3) { recordFailure(A) }
        isBlocked(A).shouldBeTrue()
        isBlocked(B).shouldBeFalse()
      }
    }

    "an address should be let in again once its oldest wrong login is a minute old" {
      val clock = TestTimeSource()
      limiter(clock).apply {
        recordFailure(A)
        clock += 30.seconds
        repeat(2) { recordFailure(A) }
        isBlocked(A).shouldBeTrue()
        clock += 31.seconds
        isBlocked(A).shouldBeFalse()
      }
    }

    "every address should be refused once the overall limit is reached" {
      limiter().apply {
        listOf(A, A, B, B, C).forEach { recordFailure(it) }
        isBlocked(D).shouldBeTrue()
      }
    }

    "the overall limit should lift once its oldest wrong login is a minute old" {
      val clock = TestTimeSource()
      limiter(clock).apply {
        listOf(A, B, C, D, E).forEach { recordFailure(it) }
        isBlocked(F).shouldBeTrue()
        clock += 1.minutes
        isBlocked(F).shouldBeFalse()
      }
    }

    "it should remember at most so many addresses, the least recent going first" {
      LoginLimiter(perAddress = 1, overall = 1_000, maxAddresses = 2).apply {
        recordFailure(A)
        recordFailure(B)
        recordFailure(C)
        isBlocked(A).shouldBeFalse()
        isBlocked(B).shouldBeTrue()
        isBlocked(C).shouldBeTrue()
      }
    }
  }

  private companion object {
    const val A = "10.0.0.1"
    const val B = "10.0.0.2"
    const val C = "10.0.0.3"
    const val D = "10.0.0.4"
    const val E = "10.0.0.5"
    const val F = "10.0.0.6"
  }
}
