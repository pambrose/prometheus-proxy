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

package io.prometheus.agent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.agent.AgentActivityKind.CONNECTED
import io.prometheus.agent.AgentActivityKind.CONNECT_FAILED
import io.prometheus.agent.AgentActivityKind.DISCONNECTED
import io.prometheus.agent.AgentActivityKind.FAILED_OVER
import io.prometheus.agent.AgentActivityKind.PATH_REFUSED
import io.prometheus.agent.AgentActivityKind.PATH_REGISTERED
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * [AgentActivityLog], the agent dashboard's feed of what happened to its connection, paths, discovery, and filters:
 * bounded, newest first, a burst of like events one line, the same event repeated one line however far apart, and a
 * change count a page can compare.
 */
class AgentActivityLogTest : StringSpec() {
  // A clock tests move by hand.
  private class TestClock : Clock {
    var now: Instant = Instant.parse("2026-11-21T12:00:00Z")

    override fun now(): Instant = now

    operator fun plusAssign(duration: Duration) {
      now += duration
    }
  }

  init {
    "lines should come newest first, and past the capacity the oldest should go" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 2, clock = clock)
      listOf("a", "b", "c").forEach { path ->
        log.record(PATH_REGISTERED, path = path, detail = "static")
        clock += 1.hours
      }

      log.snapshot().entries.map { it.paths } shouldContainExactly [["c"], ["b"]]
    }

    // An agent registering its paths as it connects is one line, not hundreds, even with a refusal among them.
    "a burst of like events should be one line, counting them and naming the first few paths" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      (1..5).forEach { i ->
        log.record(PATH_REGISTERED, path = "p$i", detail = "static")
        if (i == 2) log.record(PATH_REFUSED, path = "denied", detail = "not authorized", group = "not authorized")
        clock += 1.seconds
      }

      log.snapshot().entries.apply {
        this shouldHaveSize 2
        last().apply {
          kind shouldBe PATH_REGISTERED
          count shouldBe 5
          paths shouldContainExactly ["p1", "p2", "p3"]
          pathCount shouldBe 5
        }
        first().kind shouldBe PATH_REFUSED
      }
    }

    "like events further apart than the window should be lines of their own" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      log.record(PATH_REGISTERED, path = "a", detail = "discovered")
      clock += 1.hours
      log.record(PATH_REGISTERED, path = "b", detail = "discovered")

      log.snapshot().entries.map { it.paths } shouldContainExactly [["b"], ["a"]]
    }

    // An agent cycling through its endpoints through a long outage fails over the same way every time: one line.
    "the same event again should fold into the newest line however long since, and not past another" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      repeat(3) {
        log.record(FAILED_OVER, detail = "to proxy-b:50051", group = "proxy-b:50051")
        clock += 1.hours
      }
      log.record(CONNECTED, detail = "proxy-b:50051")
      log.record(FAILED_OVER, detail = "to proxy-b:50051", group = "proxy-b:50051")

      log.snapshot().entries.map { it.kind to it.count } shouldContainExactly
        [FAILED_OVER to 1, CONNECTED to 1, FAILED_OVER to 3]
    }

    // A path refused again and again is one path, however many times.
    "a repeat should count the event again, and not its path" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      repeat(3) {
        log.record(PATH_REFUSED, path = "denied", detail = "not authorized", group = "not authorized")
        clock += 1.hours
      }

      log.snapshot().entries.single().apply {
        count shouldBe 3
        pathCount shouldBe 1
      }
    }

    // A connection that drops and comes back within the burst window: its lines stay in order, rather than the new
    // connect folding into the old one and leaving "disconnected" the newest line while the agent is connected.
    "a connect or a disconnect should start a new run, which no later event folds back past" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      log.record(CONNECTED, detail = "proxy:50051", group = "proxy:50051")
      log.record(PATH_REGISTERED, path = "a", detail = "static", group = "STATIC")
      clock += 1.seconds
      log.record(DISCONNECTED, detail = "proxy:50051", group = "proxy:50051")
      clock += 1.seconds
      log.record(CONNECTED, detail = "proxy:50051", group = "proxy:50051")
      log.record(PATH_REGISTERED, path = "a", detail = "static", group = "STATIC")

      log.snapshot().entries.map { it.kind } shouldContainExactly
        [PATH_REGISTERED, CONNECTED, DISCONNECTED, PATH_REGISTERED, CONNECTED]
    }

    "a failure's reason, from outside the agent, should be cut and its control characters escaped" {
      val log = AgentActivityLog(capacity = 10)
      log.record(CONNECT_FAILED, detail = "UNAVAILABLE\u001b[2J" + "x".repeat(5000), group = "proxy:50051")

      log.snapshot().entries.single().detail.apply {
        this shouldNotContain "\u001b"
        length shouldBeLessThan 600
      }
    }

    // A page that shows the feed is sent it again only when this moves (see AgentDashboardService).
    "every line and every fold should move the change count, which the changes flow carries" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      log.snapshot().changeCount shouldBe 0
      log.record(PATH_REGISTERED, path = "a", detail = "static")
      log.record(PATH_REGISTERED, path = "b", detail = "static")
      clock += 1.hours
      log.record(CONNECTED, detail = "proxy:50051")

      log.snapshot().apply {
        changeCount shouldBe 3
        entries shouldHaveSize 2
      }
      log.changes.value shouldBe 3
    }
  }
}
