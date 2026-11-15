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

package io.prometheus.proxy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.proxy.dashboard.DashboardFixtures.scrapeRecord
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PathHistoryTest : StringSpec() {
  private val start: Instant = Instant.parse("2026-11-14T12:00:00Z")

  // A history whose clock reads whatever `now` holds, so a test moves time instead of sleeping.
  private class Fixture(
    historySize: Int,
    retention: Duration,
    maxDeparted: Int,
    var livePaths: Int,
    var now: Instant,
  ) {
    val history =
      PathHistory(
        historySize = historySize,
        departedRetention = retention,
        livePathCount = { livePaths },
        clock = mockk<Clock> { every { now() } answers { now } },
        maxDepartedPaths = maxDeparted,
      )
  }

  private fun fixture(
    historySize: Int = 3,
    retention: Duration = 1.hours,
    maxDeparted: Int = PathHistory.MAX_DEPARTED_PATHS,
    livePaths: Int = 0,
  ) = Fixture(historySize, retention, maxDeparted, livePaths, start)

  // Every path the history holds, read without pruning any as gone.
  private fun Fixture.heldPaths(): Set<String> = history.snapshot(registered = (1..1000).map { "p$it" }.toSet()).keys

  // A scrape at [offset] past the start.
  private fun record(
    path: String,
    offset: Duration = Duration.ZERO,
    statusCode: Int = 200,
    durationMillis: Long = 41,
  ) = scrapeRecord(
    path = path,
    statusCode = statusCode,
    durationMillis = durationMillis,
    at = start + offset,
  )

  init {
    "a path should keep only its most recent records, newest first" {
      val f = fixture(historySize = 3)
      val records = (1..5).map { record("app", durationMillis = it.toLong()) }
      records.forEach(f.history::record)

      f.history.snapshot(setOf("app"))["app"]!!.map { it.durationMillis } shouldContainExactly [5L, 4L, 3L]
    }

    // The bug this class exists to fix: one queue shared by every path let a busy path push a quiet one out within
    // seconds, and with it every gone path.
    "a busy path should not push another path's history out" {
      val f = fixture(historySize = 3)
      f.history.record(record("gone", statusCode = 503))
      repeat(500) { f.history.record(record("busy")) }

      val snapshot = f.history.snapshot(setOf("busy"))

      snapshot["gone"]!!.single().statusCode shouldBe 503
      snapshot["busy"]!!.size shouldBe 3
    }

    "a registered path should keep its history however old it is" {
      val f = fixture(retention = 5.minutes)
      f.history.record(record("app"))
      f.now = start + 2.days

      f.history.snapshot(setOf("app")) shouldContainKey "app"
    }

    "a gone path should stay until its retention has passed since its last scrape" {
      val f = fixture(retention = 60.minutes)
      f.history.record(record("gone"))
      f.history.record(record("gone", offset = 10.minutes))

      // Measured from the last scrape, not the first.
      f.now = start + 10.minutes + 60.minutes
      f.history.snapshot(emptySet()) shouldContainKey "gone"

      f.now += 1.seconds
      f.history.snapshot(emptySet()) shouldNotContainKey "gone"
    }

    // There is no limit on a proxy's total paths, so a discovery file that churns path names would otherwise grow
    // this for the whole retention window.
    "gone paths beyond the cap should be dropped oldest first" {
      // Room for live paths, so record's own bound leaves all four and this is snapshot's cap at work.
      val f = fixture(maxDeparted = 2, livePaths = 10)
      f.history.record(record("oldest"))
      f.history.record(record("middle", offset = 1.seconds))
      f.history.record(record("newest", offset = 2.seconds))
      f.history.record(record("live"))

      f.history.snapshot(setOf("live")).keys shouldBe setOf("middle", "newest", "live")
    }

    // The write path enforces the bound itself: snapshot(), the only other pruning, runs only while a dashboard is
    // open, so a proxy nobody watched grew a ring for every path ever scraped.
    "record should hold at most the live paths plus the gone-path cap, with no snapshot in between" {
      val f = fixture(maxDeparted = 3, livePaths = 2)
      (1..100).forEach { f.history.record(record("p$it")) }

      f.heldPaths() shouldBe setOf("p96", "p97", "p98", "p99", "p100")
    }

    "record should evict the path scraped least recently, not the one added first" {
      val f = fixture(maxDeparted = 3)
      f.history.record(record("p1"))
      f.history.record(record("p2"))
      f.history.record(record("p3"))
      // p1 is scraped again, so p2 is now the least recent.
      f.history.record(record("p1"))
      f.history.record(record("p4"))

      f.heldPaths() shouldBe setOf("p1", "p3", "p4")
    }

    "record should make room for every registered path" {
      val f = fixture(maxDeparted = 1, livePaths = 4)
      (1..5).forEach { f.history.record(record("p$it")) }
      f.heldPaths().size shouldBe 5

      f.livePaths = 2
      f.history.record(record("p6"))

      f.heldPaths() shouldBe setOf("p4", "p5", "p6")
    }

    "a path that comes back should keep the history it had while gone" {
      val f = fixture(historySize = 5)
      f.history.record(record("app", statusCode = 503))
      f.history.snapshot(emptySet()) shouldContainKey "app"

      f.history.record(record("app", offset = 30.seconds))

      f.history.snapshot(setOf("app"))["app"]!!.map { it.statusCode } shouldContainExactly [200, 503]
    }

    // A request for //app reaches the scrape route as path "/app"; the path map keys it as "app".
    "a leading slash should key a scrape under the path map's key" {
      val f = fixture()
      f.history.record(record("/app"))

      f.history.snapshot(setOf("app")).keys shouldBe setOf("app")
    }

    "a history size of zero should keep nothing" {
      val f = fixture(historySize = 0)
      f.history.record(record("app"))

      f.history.snapshot(setOf("app")).entries.shouldBeEmpty()
    }

    "a snapshot should be a copy that later scrapes do not change" {
      val f = fixture(historySize = 3)
      f.history.record(record("app"))
      val before = f.history.snapshot(setOf("app"))

      f.history.record(record("app", statusCode = 503))

      before["app"]!!.map { it.statusCode } shouldContainExactly [200]
    }
  }
}
