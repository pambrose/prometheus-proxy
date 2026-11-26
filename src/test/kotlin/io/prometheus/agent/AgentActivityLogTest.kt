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
import io.kotest.matchers.collections.shouldBeEmpty
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

    // Discovery retrying the paths the proxy refused, within one burst: a path past the three the line names is one of
    // the paths it counts, however often it comes back, as the line's own three are.
    "a burst should count each of its paths once, those past the sample too" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      listOf("p1", "p2", "p3", "p4", "p5", "p4", "p5", "p1").forEach { path ->
        log.record(PATH_REFUSED, path = path, detail = "not authorized", group = "NOT_AUTHORIZED")
        clock += 0.5.seconds
      }

      log.snapshot().entries.single().apply {
        count shouldBe 8
        paths shouldContainExactly ["p1", "p2", "p3"]
        pathCount shouldBe 5
      }
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

    // A connect attempt's own lines wait for its outcome: shown, in order, once it registers or fails for a new reason,
    // and moving the change count then, once, so a page is sent the feed when they appear.
    "an attempt's held lines should reach the feed only when released, in order, moving the change count once" {
      val clock = TestClock()
      val log = AgentActivityLog(capacity = 10, clock = clock)
      val attempt = log.holdAttempt()
      log.record(CONNECTED, detail = "proxy:50051", group = "proxy:50051")
      (1..4).forEach { i -> log.record(PATH_REGISTERED, path = "p$i", detail = "static", group = "STATIC") }
      log.record(PATH_REFUSED, path = "denied", detail = "not authorized", group = "NOT_AUTHORIZED")

      log.snapshot().apply {
        entries.shouldBeEmpty()
        changeCount shouldBe 0
      }

      attempt.release()
      log.snapshot().apply {
        entries.map { it.kind } shouldContainExactly [PATH_REFUSED, PATH_REGISTERED, CONNECTED]
        entries[1].apply {
          count shouldBe 4
          pathCount shouldBe 4
        }
        changeCount shouldBe 1
      }
      log.changes.value shouldBe 1
    }

    // An attempt that failed as the last one did: its lines say nothing the feed doesn't, and a page isn't sent again.
    "a discarded attempt's lines should never reach the feed, nor move the change count" {
      val log = AgentActivityLog(capacity = 10)
      val attempt = log.holdAttempt()
      log.record(CONNECTED, detail = "proxy:50051", group = "proxy:50051")
      log.record(PATH_REFUSED, path = "denied", detail = "not authorized", group = "NOT_AUTHORIZED")
      attempt.discard()
      attempt.release()

      log.snapshot().apply {
        entries.shouldBeEmpty()
        changeCount shouldBe 0
      }
    }

    // Only the attempt's own kinds wait: a failure, a failover, or a filter letting a scrape through is news at once.
    "lines of other kinds should reach the feed while an attempt holds its own, and lines after the hold at once" {
      val log = AgentActivityLog(capacity = 10)
      val attempt = log.holdAttempt()
      log.record(CONNECTED, detail = "proxy:50051", group = "proxy:50051")
      log.record(CONNECT_FAILED, detail = "proxy:50051: refused", group = "proxy:50051 refused")
      log.record(FAILED_OVER, detail = "from proxy:50051 to backup:50051", group = "proxy:50051")

      log.snapshot().entries.map { it.kind } shouldContainExactly [FAILED_OVER, CONNECT_FAILED]

      attempt.discard()
      log.record(CONNECTED, detail = "backup:50051", group = "backup:50051")
      log.snapshot().entries.map { it.kind } shouldContainExactly [CONNECTED, FAILED_OVER, CONNECT_FAILED]
    }

    // One attempt runs at a time; a hold left open, which the agent never does, is released rather than lost, and its
    // token no longer ends the new one.
    "a new hold should release one left open, whose release or discard then does nothing" {
      val log = AgentActivityLog(capacity = 10)
      val first = log.holdAttempt()
      log.record(CONNECTED, detail = "proxy:50051", group = "proxy:50051")
      val second = log.holdAttempt()
      log.record(PATH_REGISTERED, path = "a", detail = "static", group = "STATIC")
      first.discard()

      log.snapshot().entries.map { it.kind } shouldContainExactly [CONNECTED]

      second.release()
      log.snapshot().entries.map { it.kind } shouldContainExactly [PATH_REGISTERED, CONNECTED]
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
