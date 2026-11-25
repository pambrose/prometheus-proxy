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

package io.prometheus.common.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * [DashboardSessions]: the dashboard's logins, kept in memory, each ending 24 hours after its last request or 7 days
 * after it began, at most so many at once.
 */
class DashboardSessionsTest : StringSpec() {
  init {
    "a new session is valid, and a token it never issued, null, or empty isn't" {
      val sessions = DashboardSessions()
      sessions.isValid(sessions.create()).shouldBeTrue()
      sessions.isValid("never-issued").shouldBeFalse()
      sessions.isValid(null).shouldBeFalse()
      sessions.isValid("").shouldBeFalse()
    }

    "tokens are 43 base64url characters and differ each time" {
      val sessions = DashboardSessions()
      val tokens = (1..100).map { sessions.create() }
      tokens.forEach { it shouldMatch Regex("[A-Za-z0-9_-]{43}") }
      tokens.toSet().size shouldBe 100
    }

    "a session ends 24 hours after its last request, and a request restarts that" {
      val clock = TestTimeSource()
      val sessions = DashboardSessions(timeSource = clock)
      val token = sessions.create()
      clock += 23.hours
      sessions.isValid(token).shouldBeTrue()
      clock += 23.hours
      sessions.isValid(token).shouldBeTrue()
      clock += 24.hours + 1.seconds
      sessions.isValid(token).shouldBeFalse()
    }

    "a session ends 7 days after it began, however recently it was used" {
      val clock = TestTimeSource()
      val sessions = DashboardSessions(timeSource = clock)
      val token = sessions.create()
      repeat(13) {
        clock += 12.hours
        sessions.isValid(token).shouldBeTrue()
      }
      clock += 12.hours + 1.seconds
      sessions.isValid(token).shouldBeFalse()
      DashboardSessions.MAX_AGE_SECS shouldBe 7.days.inWholeSeconds.toInt()
    }

    // An open dashboard asks whether its session has ended on each push, which mustn't count as a request: a tab left
    // open would keep its session alive past its idle time.
    "a check that doesn't count as a request should leave the idle time running" {
      val clock = TestTimeSource()
      val sessions = DashboardSessions(timeSource = clock)
      val token = sessions.create()
      clock += 23.hours
      sessions.isValid(token, counts = false).shouldBeTrue()
      clock += 1.hours + 1.seconds
      sessions.isValid(token, counts = false).shouldBeFalse()
    }

    // Log out acts on its pages only for a token that named a session, since anyone may post one.
    "removing a session ends it, and says whether there was one" {
      val sessions = DashboardSessions()
      val token = sessions.create()
      sessions.remove(token).shouldBeTrue()
      sessions.isValid(token).shouldBeFalse()
      // Nothing to remove: no error, and nothing removed.
      sessions.remove(token).shouldBeFalse()
      sessions.remove(null).shouldBeFalse()
      sessions.remove("").shouldBeFalse()
      sessions.remove("never-issued").shouldBeFalse()
    }

    "past the cap, the least recently used session goes" {
      val sessions = DashboardSessions(maxSessions = 3)
      val a = sessions.create()
      val b = sessions.create()
      val c = sessions.create()
      sessions.isValid(a).shouldBeTrue()
      val d = sessions.create()
      sessions.size shouldBe 3
      sessions.isValid(b).shouldBeFalse()
      listOf(a, c, d).forEach { sessions.isValid(it).shouldBeTrue() }
    }

    "an expired session is forgotten" {
      val clock = TestTimeSource()
      val sessions = DashboardSessions(timeSource = clock)
      val token = sessions.create()
      clock += 25.hours
      sessions.isValid(token).shouldBeFalse()
      sessions.size shouldBe 0
    }
  }
}
