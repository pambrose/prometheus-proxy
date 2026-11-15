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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.prometheus.grpc.registerAgentRequest
import io.prometheus.proxy.ActivityKind.AGENT_CONNECTED
import io.prometheus.proxy.ActivityKind.BAD_TOKEN
import io.prometheus.proxy.ActivityKind.PATH_REGISTERED
import io.prometheus.proxy.ActivityKind.PATH_REJECTED
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ActivityLogTest : StringSpec() {
  private val start: Instant = Instant.parse("2026-11-15T12:00:00Z")
  private var now = start

  private fun log(capacity: Int = 100) = ActivityLog(capacity, clock = mockk<Clock> { every { now() } answers { now } })

  private fun agent(
    name: String,
    identity: String = "",
  ): AgentContext =
    AgentContext("10.0.1.14:5555").apply { assignProperties(registerAgentRequest { agentName = name }, identity) }

  init {
    beforeTest { now = start }

    "a log should list its lines newest first, with each event's agent and time" {
      val log = log()
      val east = agent("agent-east", identity = "east")

      log.record(AGENT_CONNECTED, east, detail = "from 10.0.1.14:5555")
      now += 10.seconds
      log.record(PATH_REGISTERED, east, "orders_metrics")

      log.entries().map { it.kind } shouldBe [PATH_REGISTERED, AGENT_CONNECTED]
      log.entries().last().apply {
        at shouldBe start
        agentId shouldBe east.agentId
        agentName shouldBe "agent-east"
        identity shouldBe "east"
        detail shouldBe "from 10.0.1.14:5555"
      }
      log.entries().first().paths shouldBe ["orders_metrics"]
    }

    "a full log should drop its oldest line" {
      val log = log(capacity = 3)

      (1..5).forEach {
        log.record(PATH_REGISTERED, agent("agent-$it"), "p$it")
        now += 10.seconds
      }

      log.entries().map { it.paths.single() } shouldBe ["p5", "p4", "p3"]
    }

    // An agent registering its paths as it starts is one line, not the whole log.
    "a burst of like events from one agent should be one line that counts them and names the first few paths" {
      val log = log()
      val east = agent("agent-east")

      (1..10).forEach {
        log.record(PATH_REGISTERED, east, "p$it")
        now += 0.2.seconds
      }

      log.entries().single().apply {
        count shouldBe 10
        paths shouldBe ["p1", "p2", "p3"]
        at shouldBe start
      }
    }

    // A fleet reconnecting at once interleaves every agent's registrations; each agent's burst is still one line.
    "bursts from agents registering at once should each be one line" {
      val log = log()
      val east = agent("agent-east")
      val west = agent("agent-west")

      (1..5).forEach {
        log.record(PATH_REGISTERED, east, "east-$it")
        log.record(PATH_REGISTERED, west, "west-$it")
      }

      log.entries().map { it.agentName to it.count } shouldBe ["agent-west" to 5, "agent-east" to 5]
    }

    "a burst should end at its window, at another agent, at another kind, or at another group" {
      val east = agent("agent-east")
      val west = agent("agent-west")

      log().apply {
        record(PATH_REGISTERED, east, "a")
        now += ActivityLog.COALESCE_WINDOW
        record(PATH_REGISTERED, east, "b")
      }.entries() shouldHaveSize 2

      log().apply {
        record(PATH_REGISTERED, east, "a")
        record(PATH_REGISTERED, west, "b")
      }.entries() shouldHaveSize 2

      log().apply {
        record(PATH_REGISTERED, east, "a")
        record(PATH_REJECTED, east, "b", group = "NOT_AUTHORIZED")
      }.entries() shouldHaveSize 2

      // Two causes of rejection stay two lines, so each line's detail is true of every path it names.
      log().apply {
        record(PATH_REJECTED, east, "a", "not authorized", group = "NOT_AUTHORIZED")
        record(PATH_REJECTED, east, "b", "held by west", group = "HELD_BY_ANOTHER_IDENTITY")
      }.entries() shouldHaveSize 2
    }

    // Anyone who can reach the agent port can send a bad token, as often as they like.
    "a source's events within its window should be counted on one line" {
      val log = log()

      fun badToken(host: String) = log.recordFromSource(host, BAD_TOKEN, "An unknown agent token, from $host")

      repeat(5) { badToken("10.9.9.9") }
      badToken("10.8.8.8")
      now += ActivityLog.SOURCE_WINDOW - 1.seconds
      badToken("10.9.9.9")

      log.entries().map { it.group to it.count } shouldBe ["10.8.8.8" to 1, "10.9.9.9" to 6]

      // Past its window, a source starts a line again.
      now += 1.seconds
      badToken("10.9.9.9")
      log.entries() shouldHaveSize 3
    }

    // Anyone with many source addresses could otherwise push everything else out of the log, one line per address.
    "past its most sources a window, the rest of the window's should be counted on one line" {
      val log = log()

      fun badToken(host: String) = log.recordFromSource(host, BAD_TOKEN, "An unknown agent token, from $host")

      (1..25).forEach { badToken("host-$it") }
      now += 30.seconds
      badToken("host-26")
      // A source with a line of its own still counts there.
      badToken("host-1")

      log.entries() shouldHaveSize ActivityLog.MAX_SOURCES_PER_WINDOW + 1
      log.entries().first().apply {
        count shouldBe 26 - ActivityLog.MAX_SOURCES_PER_WINDOW
        detail shouldBe ActivityLog.MORE_SOURCES_DETAIL
      }
      log.entries().last().count shouldBe 2

      // A new window lists its sources again.
      now += 30.seconds
      badToken("host-27")
      log.entries().first().detail shouldBe "An unknown agent token, from host-27"
    }

    "an event with no agent should name its own identity" {
      val log = log()

      log.record(ActivityKind.AGENT_REFUSED, identity = "east", detail = "2 agents already connected")

      log.entries().single().apply {
        agentId shouldBe ""
        agentName shouldBe ""
        identity shouldBe "east"
      }
    }

    "every value a line keeps should be cut" {
      val log = log()

      log.record(PATH_REJECTED, agent("n".repeat(1000)), "p".repeat(1000), "d".repeat(5000), "g".repeat(1000))

      log.entries().single().apply {
        agentName.length shouldBe ActivityLog.MAX_FIELD_CHARS
        paths.single().length shouldBe ActivityLog.MAX_FIELD_CHARS
        detail.length shouldBe ActivityLog.MAX_DETAIL_CHARS
        group.length shouldBe ActivityLog.MAX_FIELD_CHARS
      }
    }

    "a log with no room should be refused" {
      shouldThrow<IllegalArgumentException> { log(capacity = 0) }
    }

    "only refusals, rejections, bad tokens, and evictions should be problems" {
      ActivityKind.entries.filter { it.isProblem } shouldBe
        [ActivityKind.AGENT_EVICTED, ActivityKind.AGENT_REFUSED, BAD_TOKEN, PATH_REJECTED]
    }
  }
}
