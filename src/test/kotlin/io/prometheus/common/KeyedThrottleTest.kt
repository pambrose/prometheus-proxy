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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class KeyedThrottleTest : StringSpec() {
  init {
    "a key should pass once, and not again until its interval is over" {
      val clock = TestTimeSource()
      val throttle = KeyedThrottle<String>(1.minutes, 10, clock)

      throttle.tryAcquire("team-a").shouldBeTrue()
      throttle.tryAcquire("team-a").shouldBeFalse()
      clock += 59.seconds
      throttle.tryAcquire("team-a").shouldBeFalse()
      clock += 1.seconds
      throttle.tryAcquire("team-a").shouldBeTrue()
      throttle.tryAcquire("team-a").shouldBeFalse()
    }

    // A caller that keeps calling must still pass once an interval: were each refusal to restart the interval, a
    // client calling more often than that would never be warned about again.
    "a refused call should not restart its key's interval or take a new mark" {
      val clock = TestTimeSource()
      val timeSource = mockk<TimeSource> { every { markNow() } answers { clock.markNow() } }
      val throttle = KeyedThrottle<String>(1.minutes, 10, timeSource)

      throttle.tryAcquire("team-a").shouldBeTrue()
      repeat(3) {
        clock += 20.seconds
        throttle.tryAcquire("team-a") shouldBe (it == 2)
      }

      verify(exactly = 2) { timeSource.markNow() }
    }

    "each key should have an interval of its own" {
      val clock = TestTimeSource()
      val throttle = KeyedThrottle<String>(1.minutes, 10, clock)

      throttle.tryAcquire("team-a").shouldBeTrue()
      clock += 30.seconds
      throttle.tryAcquire("team-b").shouldBeTrue()
      throttle.tryAcquire("team-a").shouldBeFalse()
      clock += 30.seconds
      throttle.tryAcquire("team-a").shouldBeTrue()
      throttle.tryAcquire("team-b").shouldBeFalse()
    }

    // The map is ordered by when each key was last asked about, refused or not, so a key that keeps being refused stays
    // remembered and a key no one has asked about for longest goes first.
    "past its cap it should forget the key asked about least recently, which then passes as a new one would" {
      val throttle = KeyedThrottle<String>(1.minutes, 2, TestTimeSource())

      throttle.tryAcquire("a").shouldBeTrue()
      throttle.tryAcquire("b").shouldBeTrue()
      throttle.tryAcquire("a").shouldBeFalse()
      throttle.tryAcquire("c").shouldBeTrue()
      throttle.size shouldBe 2

      throttle.tryAcquire("a").shouldBeFalse()
      throttle.tryAcquire("b").shouldBeTrue()
      throttle.tryAcquire("c").shouldBeTrue()
      throttle.size shouldBe 2
    }

    "an interval or cap that isn't positive should be refused" {
      listOf(Duration.ZERO, (-1).seconds).forEach { interval ->
        shouldThrow<IllegalArgumentException> { KeyedThrottle<String>(interval, 10) }
      }
      shouldThrow<IllegalArgumentException> { KeyedThrottle<String>(1.minutes, 0) }
    }

    // Time stands still here, so of all the calls racing for one key exactly one may pass.
    "of concurrent calls for one key, exactly one should pass" {
      val throttle = KeyedThrottle<String>(1.minutes, 10, TestTimeSource())

      val passed =
        withContext(Dispatchers.Default) {
          List(8) { async { (1..1_000).count { throttle.tryAcquire("team-a") } } }.awaitAll().sum()
        }

      passed shouldBe 1
    }
  }
}
