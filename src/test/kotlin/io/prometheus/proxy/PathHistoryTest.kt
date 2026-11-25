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
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.proxy.ProxyPathManager.AgentContextInfo
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

  // A history whose clock reads whatever `now` holds, so a test moves time instead of sleeping, and whose count of
  // registered paths is whatever `livePaths` holds.
  private inner class Fixture(
    historySize: Int = 3,
    retention: Duration = 1.hours,
    maxDeparted: Int = PathHistory.MAX_DEPARTED_PATHS,
    var livePaths: Int = 0,
  ) {
    var now = start
    val history =
      PathHistory(
        historySize = historySize,
        departedRetention = retention,
        livePathCount = { livePaths },
        clock = mockk<Clock> { every { now() } answers { now } },
        maxDepartedPaths = maxDeparted,
      )

    // Each path's scrapes, newest first, read with [registered] registered.
    fun scrapes(registered: Set<String>): Map<String, List<ScrapeRecord>> = history.snapshot(registered).scrapes

    // The paths the history holds scrapes of, read with [registered] registered, so none of those is pruned as gone.
    fun heldPaths(registered: Collection<String>): Set<String> = scrapes(registered.toSet()).keys

    // The paths the history holds departures of, read with [registered] registered, so none of those is pruned as gone.
    fun departedPaths(registered: Collection<String>): Set<String> =
      history.snapshot(registered.toSet()).departures.keys

    // [path] leaves the path map now, as an entry naming [agentName] showed it.
    fun depart(
      path: String,
      agentName: String = "agent-1",
    ) = history.recordDeparture(path, entry(agentName))
  }

  private fun names(range: IntRange) = range.map { "p$it" }

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

  // The path-map entry of a path whose one agent is named [name], as it stands when that agent leaves.
  private fun entry(
    name: String,
    identity: String = "",
    targetUrl: String = "",
    pathSource: String = "",
  ) = AgentContextInfo(
    isConsolidated = false,
    agentContexts = [mockk<AgentContext> { every { agentName } returns name }],
    targetUrl = targetUrl,
    pathSource = pathSource,
    identityName = identity,
  )

  init {
    "a path should keep only its most recent records, newest first" {
      val f = Fixture(historySize = 3)
      val records = (1..5).map { record("app", durationMillis = it.toLong()) }
      records.forEach(f.history::record)

      f.scrapes(setOf("app"))["app"]!!.map { it.durationMillis } shouldContainExactly [5L, 4L, 3L]
    }

    // The bug this class exists to fix: one queue shared by every path let a busy path push a quiet one out within
    // seconds, and with it every gone path.
    "a busy path should not push another path's history out" {
      val f = Fixture(historySize = 3)
      f.history.record(record("gone", statusCode = 503))
      repeat(500) { f.history.record(record("busy")) }

      val snapshot = f.scrapes(setOf("busy"))

      snapshot["gone"]!!.single().statusCode shouldBe 503
      snapshot["busy"]!!.size shouldBe 3
    }

    "a registered path should keep its history however old it is" {
      val f = Fixture(retention = 5.minutes)
      f.history.record(record("app"))
      f.now = start + 2.days

      f.scrapes(setOf("app")) shouldContainKey "app"
    }

    "a gone path should stay until its retention has passed since its last scrape" {
      val f = Fixture(retention = 60.minutes)
      f.history.record(record("gone"))
      f.history.record(record("gone", offset = 10.minutes))

      // Measured from the last scrape, not the first.
      f.now = start + 10.minutes + 60.minutes
      f.scrapes(emptySet()) shouldContainKey "gone"

      f.now += 1.seconds
      f.scrapes(emptySet()) shouldNotContainKey "gone"
    }

    // There is no limit on a proxy's total paths, so a discovery file that churns path names would otherwise grow
    // this for the whole retention window.
    "gone paths beyond the cap should be dropped oldest first" {
      // Room for live paths, so record's own bound leaves all four and this is snapshot's cap at work.
      val f = Fixture(maxDeparted = 2, livePaths = 10)
      f.history.record(record("oldest"))
      f.history.record(record("middle", offset = 1.seconds))
      f.history.record(record("newest", offset = 2.seconds))
      f.history.record(record("live"))

      f.scrapes(setOf("live")).keys shouldBe setOf("middle", "newest", "live")
    }

    // The write path enforces the bound itself: snapshot(), the only other pruning, runs only while a dashboard is
    // open, so a proxy nobody watched grew a ring for every path ever scraped.
    "record should hold at most the live paths plus the gone-path cap, with no snapshot in between" {
      val f = Fixture(maxDeparted = 3, livePaths = 2)
      (1..100).forEach { f.history.record(record("p$it")) }

      f.heldPaths(names(1..100)) shouldBe setOf("p96", "p97", "p98", "p99", "p100")
    }

    "record should evict the path scraped least recently, not the one added first" {
      val f = Fixture(maxDeparted = 3)
      f.history.record(record("p1"))
      f.history.record(record("p2"))
      f.history.record(record("p3"))
      // p1 is scraped again, so p2 is now the least recent.
      f.history.record(record("p1"))
      f.history.record(record("p4"))

      f.heldPaths(names(1..4)) shouldBe setOf("p1", "p3", "p4")
    }

    "record should make room for every registered path" {
      val f = Fixture(maxDeparted = 1, livePaths = 4)
      (1..5).forEach { f.history.record(record("p$it")) }
      f.heldPaths(names(1..6)).size shouldBe 5

      f.livePaths = 2
      f.history.record(record("p6"))

      f.heldPaths(names(1..6)) shouldBe setOf("p4", "p5", "p6")
    }

    "a path that comes back should keep the history it had while gone" {
      val f = Fixture(historySize = 5)
      f.history.record(record("app", statusCode = 503))
      f.scrapes(emptySet()) shouldContainKey "app"

      f.history.record(record("app", offset = 30.seconds))

      f.scrapes(setOf("app"))["app"]!!.map { it.statusCode } shouldContainExactly [200, 503]
    }

    // A record whose path keeps a leading slash still keys under the path map's key, "app". The scrape route strips
    // every leading slash, so it records none.
    "a leading slash should key a scrape under the path map's key" {
      val f = Fixture()
      f.history.record(record("/app"))

      f.scrapes(setOf("app")).keys shouldBe setOf("app")
    }

    "a history size of zero should keep nothing" {
      val f = Fixture(historySize = 0)
      f.history.record(record("app"))

      f.scrapes(setOf("app")).entries.shouldBeEmpty()
    }

    "a snapshot should be a copy that later scrapes do not change" {
      val f = Fixture(historySize = 3)
      f.history.record(record("app"))
      val before = f.scrapes(setOf("app"))

      f.history.record(record("app", statusCode = 503))

      before["app"]!!.map { it.statusCode } shouldContainExactly [200]
    }

    // ==================== Departures ====================

    // Follow-up #5: gone rows came only from scrapes, so a path whose agent left before Prometheus scraped it had no
    // row at all. The departure keeps what the row needs, the target included, which no scrape carries.
    "a path that leaves the path map should be held as departed, with what its entry showed, though never scraped" {
      val f = Fixture()
      f.now = start + 5.minutes
      val url = "http://warehouse/metrics"
      // Keyed as the path map keys a path, as a scrape is.
      f.history.recordDeparture("/gone", entry("warehouse-01", "team-a", url, "STATIC"))

      val snapshot = f.history.snapshot(emptySet())

      snapshot.scrapes.shouldBeEmpty()
      snapshot.departures shouldBe
        mapOf("gone" to PathHistory.Departure(start + 5.minutes, "warehouse-01", "team-a", url, "STATIC"))
    }

    // Measured from the later of the two, so a path whose agent left long after its last scrape isn't dropped the
    // moment it goes, and one scraped by a request still in flight as it left isn't dropped before that scrape ages.
    "a gone path should stay until its retention has passed since its departure, when it left after its last scrape" {
      val f = Fixture(retention = 60.minutes)
      f.history.record(record("gone"))
      f.now = start + 10.minutes
      f.depart("gone")

      f.now = start + 10.minutes + 60.minutes
      f.history.snapshot(emptySet()).apply {
        scrapes shouldContainKey "gone"
        departures shouldContainKey "gone"
      }

      f.now += 1.seconds
      f.history.snapshot(emptySet()).apply {
        scrapes shouldNotContainKey "gone"
        departures shouldNotContainKey "gone"
      }
    }

    "a gone path scraped after it left should stay until its retention has passed since that scrape" {
      val f = Fixture(retention = 60.minutes)
      f.depart("gone")
      f.history.record(record("gone", offset = 5.minutes))

      f.now = start + 5.minutes + 60.minutes
      f.history.snapshot(emptySet()).departures shouldContainKey "gone"

      f.now += 1.seconds
      f.history.snapshot(emptySet()).departures shouldNotContainKey "gone"
    }

    "a path that registers again should stop being departed and keep the history it had while gone" {
      val f = Fixture()
      f.history.record(record("app", statusCode = 503))
      f.depart("app")

      f.history.forgetDeparture("app")
      val snapshot = f.history.snapshot(setOf("app"))

      snapshot.departures.shouldBeEmpty()
      snapshot.scrapes["app"]!!.map { it.statusCode } shouldContainExactly [503]
    }

    // As for rings, the write path enforces the bound itself, since a snapshot may never come: the path manager records
    // a departure for every path that leaves, on a proxy whose dashboard nobody has open.
    "recordDeparture should hold at most the gone-path cap, dropping the earliest, with no snapshot in between" {
      val f = Fixture(maxDeparted = 3)
      (1..100).forEach { f.depart("p$it") }

      f.departedPaths(names(1..100)) shouldBe setOf("p98", "p99", "p100")
    }

    "a path that departs again should count as the latest departure, at its new time" {
      val f = Fixture(maxDeparted = 2)
      f.depart("p1")
      f.depart("p2")
      f.now = start + 1.minutes
      f.depart("p1", agentName = "agent-2")
      f.depart("p3")

      f.departedPaths(names(1..3)) shouldBe setOf("p1", "p3")
      f.history.snapshot(names(1..3).toSet()).departures.getValue("p1").apply {
        at shouldBe start + 1.minutes
        agentName shouldBe "agent-2"
      }
    }

    // What the page shows is one row per gone path, so the cap counts rows, and the row seen least recently, by its
    // last scrape or its departure, whichever is later, goes from both maps.
    "the gone-path cap should count a path once, whether it has scrapes, a departure, or both" {
      val f = Fixture(maxDeparted = 2, livePaths = 10)
      f.history.record(record("both"))
      f.now = start + 1.seconds
      f.depart("both")
      f.now = start + 2.seconds
      f.depart("departed")
      f.history.record(record("scraped", offset = 3.seconds))
      f.history.record(record("live"))

      f.history.snapshot(setOf("live")).apply {
        scrapes.keys shouldBe setOf("scraped", "live")
        departures.keys shouldBe setOf("departed")
      }
    }

    // The dashboard reads the path map before the history, so a path that left in between is still in the registered
    // set it passes. Dropping its departure then would lose a path never scraped for good.
    "a departure should not be dropped for a path the snapshot reads as registered" {
      val f = Fixture(retention = 1.minutes)
      f.depart("app")
      f.now = start + 1.hours

      f.history.snapshot(setOf("app")).departures shouldContainKey "app"
      f.history.snapshot(emptySet()).departures shouldNotContainKey "app"
    }
  }
}
