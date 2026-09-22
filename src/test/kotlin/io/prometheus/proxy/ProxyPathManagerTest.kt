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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.google.protobuf.LazyStringArrayList
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.prometheus.Proxy
import io.prometheus.common.captureLogs
import io.prometheus.grpc.PathRejectionCause
import io.prometheus.grpc.RegisterAgentRequest
import io.prometheus.grpc.registerAgentRequest
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import io.kotest.matchers.maps.shouldHaveSize as mapShouldHaveSize

@Suppress("LargeClass")
class ProxyPathManagerTest : StringSpec() {
  private fun createMockProxy(): Proxy {
    val mockManager = mockk<AgentContextManager>(relaxed = true)
    val proxy = mockk<Proxy>(relaxed = true)
    every { proxy.agentContextManager } returns mockManager
    return proxy
  }

  // Counter-based like AgentContext.AGENT_ID_GENERATOR; a timestamp+random id can collide
  // when two mocks are created in the same millisecond, and duplicate agentIds make
  // removeFromPathManager() drop every agent's registrations instead of one agent's.
  private val agentIdCounter = AtomicLong(0L)

  private fun createMockAgentContext(consolidated: Boolean = false): AgentContext {
    val context = mockk<AgentContext>(relaxed = true)
    val agentId = "agent-${agentIdCounter.incrementAndFetch()}"
    every { context.agentId } returns agentId
    every { context.consolidated } returns consolidated
    every { context.isNotValid() } returns false
    every { context.desc } returns if (consolidated) "consolidated " else ""
    return context
  }

  // A real context, which remembers the rejections it is told -- what makes a repeat log at DEBUG. consolidated
  // has a private set, so it is assigned the way an agent assigns it at registration.
  private fun createAgentContext(consolidated: Boolean = false): AgentContext =
    AgentContext("remote-${agentIdCounter.incrementAndFetch()}").apply {
      assignProperties(registerAgentRequest { this.consolidated = consolidated })
    }

  // The rejections ProxyPathManager logged during [block] whose message contains [marker], at every level.
  private fun rejectionLogs(
    marker: String,
    block: () -> Unit,
  ): List<ILoggingEvent> =
    captureLogs<ProxyPathManager>(Level.DEBUG) { block() }.filter { marker in it.formattedMessage }

  // Runs the manager's proxy.metrics { } blocks against [target], so a test can verify which metric calls it made.
  private fun routeMetrics(
    proxy: Proxy,
    target: ProxyMetrics,
  ) {
    every { proxy.metrics(any<ProxyMetrics.() -> Unit>()) } answers { firstArg<ProxyMetrics.() -> Unit>()(target) }
  }

  init {
    "addPath should add new path successfully" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.labels shouldBe """{"job":"test"}"""
      info.agentContexts.shouldHaveSize(1)
    }

    // An agent from before target-URL redaction still sends the URL raw. The proxy redacts it on the way in,
    // so the dashboard and /debug never show credentials either way.
    "addPath should store the target URL with credentials redacted" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("metrics", "{}", context, "http://admin:hunter2@target:9100/metrics?token=s3cr3t")

      manager.getAgentContextInfo("metrics").shouldNotBeNull().targetUrl shouldBe
        "http://***@target:9100/metrics?token=***"
    }

    "addPath should throw when path is blank" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)

      shouldThrow<IllegalArgumentException> {
        manager.addPath("   ", """{"job":"test"}""", createMockAgentContext())
      }.message shouldContain "Blank path"
    }

    "removePath should throw when path is blank" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)

      shouldThrow<IllegalArgumentException> { manager.removePath("   ", "agent-1") }
        .message shouldContain "Blank path"
    }

    "addPath should throw when path is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      val exception = shouldThrow<IllegalArgumentException> {
        manager.addPath("", """{"job":"test"}""", context)
      }

      exception.message shouldContain "Blank path"
    }

    // The scrape route is registered as get("/*"), which matches exactly one path segment, so a
    // multi-segment path would be advertised in service discovery yet 404 at scrape time. Reject it
    // at registration with a clear reason instead of silently registering an unreachable path.
    "addPath should reject a multi-segment path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      val rejection = manager.addPath("app/metrics", """{"job":"test"}""", context)

      rejection.shouldNotBeNull().reason shouldContain "single"
      rejection.cause shouldBe PathRejectionCause.INVALID_PATH
      manager.pathMapSize shouldBe 0
    }

    "addPath should reject a multi-segment path that has a leading slash" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      val reason = manager.addPath("/app/metrics", """{"job":"test"}""", context)?.reason

      reason.shouldNotBeNull()
      manager.pathMapSize shouldBe 0
    }

    "addPath should overwrite non-consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext()
      val context2 = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test1"}""", context1)
      manager.addPath("/metrics", """{"job":"test2"}""", context2)

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.labels shouldBe """{"job":"test2"}"""
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe context2.agentId
    }

    // A path listed twice in an agent's config registers twice from the same agent. Appending it again sent that agent
    // two requests per scrape, and Prometheus rejects the duplicate samples the merge produced.
    "a consolidated agent re-registering a path should replace its own entry rather than add a second" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)
      manager.addPath("/metrics", """{"job":"test"}""", context1)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.map { it.agentId } shouldBe [context1.agentId, context2.agentId]
    }

    // In non-consolidated mode the same re-registration "displaced" the agent from its own path, counting a
    // displacement in proxy_agent_displacement_total that never happened.
    "a non-consolidated agent re-registering its own path should not count as a displacement" {
      val proxy = createMockProxy()
      val metrics = mockk<ProxyMetrics>(relaxed = true)
      routeMetrics(proxy, metrics)
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)
      manager.addPath("/metrics", """{"job":"test"}""", context)

      verify(exactly = 0) { metrics.agentDisplacementCount }
      verify(exactly = 0) { context.invalidate() }
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.map { it.agentId } shouldBe [context.agentId]
    }

    // A live agent's path can be taken over only by an agent of the same auth identity. Otherwise an identity whose
    // path patterns overlap another's could silently replace that identity's metrics.
    "another identity should not take over a live agent's non-consolidated path" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = AgentContext("remote-owner")
      val intruder = AgentContext("remote-intruder")

      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()
      val rejection = manager.addPath("/metrics", """{"job":"test"}""", intruder, identityName = "team_b")

      rejection.shouldNotBeNull().reason shouldContain "team_a"
      rejection.cause shouldBe PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
      manager.getAgentContextInfo("/metrics")?.agentContexts?.map { it.agentId } shouldBe [owner.agentId]
      owner.isValid().shouldBeTrue()
    }

    // A redeployed agent presents the same identity, so it reclaims its paths at once.
    "an agent of the same identity should take over a live agent's non-consolidated path" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = AgentContext("remote-owner")
      val redeployed = AgentContext("remote-redeployed")

      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()
      manager.addPath("/metrics", """{"job":"test"}""", redeployed, identityName = "team_a").shouldBeNull()

      manager.getAgentContextInfo("/metrics")?.agentContexts?.map { it.agentId } shouldBe [redeployed.agentId]
    }

    // A registrant that is already gone -- invalidated, but not yet cleaned up -- must not hold its path hostage.
    "another identity should take over a path whose registrant is no longer valid" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = AgentContext("remote-owner")
      val newcomer = AgentContext("remote-newcomer")

      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()
      owner.invalidate()
      manager.addPath("/metrics", """{"job":"test"}""", newcomer, identityName = "team_b").shouldBeNull()

      manager.getAgentContextInfo("/metrics")?.agentContexts?.map { it.agentId } shouldBe [newcomer.agentId]
    }

    // An agent retries a rejection that can clear -- here a path another identity's live agent holds -- every
    // rejectedPathRetrySecs, so the proxy reports it once and logs the repeats at DEBUG.
    "a rejection repeated for the same path and agent should be logged once" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = AgentContext("remote-owner")
      val intruder = AgentContext("remote-intruder")
      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()

      val events =
        rejectionLogs("cannot take it over") {
          repeat(3) { manager.addPath("/metrics", """{"job":"test"}""", intruder, identityName = "team_b") }
        }

      events.count { it.level == Level.WARN } shouldBe 1
      events.count { it.level == Level.DEBUG } shouldBe 2
    }

    // The record lives on the agent's connection, so another agent -- or the same agent reconnected, which gets a new
    // context -- is told in its own right.
    "a rejection of the same path by another agent should be logged again" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = AgentContext("remote-owner")
      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()

      val events =
        rejectionLogs("cannot take it over") {
          manager.addPath("/metrics", """{"job":"test"}""", AgentContext("remote-1"), identityName = "team_b")
          manager.addPath("/metrics", """{"job":"test"}""", AgentContext("remote-2"), identityName = "team_b")
        }

      events.count { it.level == Level.WARN } shouldBe 2
    }

    // A conflict that clears and later re-forms is news again, so registering the path forgets its rejection.
    "a rejection after the agent registered the path should be logged again" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = AgentContext("remote-owner")
      val intruder = AgentContext("remote-intruder")
      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()

      val events =
        rejectionLogs("cannot take it over") {
          manager.addPath("/metrics", """{"job":"test"}""", intruder, identityName = "team_b")
          manager.removePath("/metrics", owner.agentId)
          manager.addPath("/metrics", """{"job":"test"}""", intruder, identityName = "team_b").shouldBeNull()
          manager.removePath("/metrics", intruder.agentId)
          manager.addPath("/metrics", """{"job":"test"}""", AgentContext("remote-next"), identityName = "team_a")
            .shouldBeNull()
          manager.addPath("/metrics", """{"job":"test"}""", intruder, identityName = "team_b")
        }

      events.count { it.level == Level.WARN } shouldBe 2
    }

    // A mismatch is an operator's config conflict between agents, like a takeover, and it clears when they leave.
    "a consolidation mismatch should be logged at WARN, then DEBUG, never ERROR" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val plainAgent = createAgentContext(consolidated = false)
      val joiner = createAgentContext(consolidated = true)
      manager.addPath("/metrics", """{"job":"test"}""", plainAgent).shouldBeNull()

      val events =
        rejectionLogs("Consolidated agent rejected") {
          repeat(3) { manager.addPath("/metrics", """{"job":"test"}""", joiner) }
        }

      events.count { it.level == Level.ERROR } shouldBe 0
      events.count { it.level == Level.WARN } shouldBe 1
      events.count { it.level == Level.DEBUG } shouldBe 2
    }

    // Tests consolidated path behavior: when multiple agents register the same path with
    // consolidated=true, they are grouped together. Prometheus scrapes are load-balanced
    // across all agents in the consolidated group. This differs from non-consolidated
    // paths where a new registration overwrites the previous one.
    "addPath should append to consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      info.agentContexts.shouldHaveSize(2)
    }

    // The same-identity rule that protects a non-consolidated path applies here too. An agent joining a consolidated
    // path answers a share of every scrape of it, so one identity merging its metrics into another's is the same
    // integrity problem as taking the path over outright.
    "another identity should not join a live agent's consolidated path" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = createAgentContext(consolidated = true)
      val intruder = createAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()
      val rejection = manager.addPath("/metrics", """{"job":"test"}""", intruder, identityName = "team_b")

      rejection.shouldNotBeNull().reason shouldContain "team_a"
      rejection.cause shouldBe PathRejectionCause.HELD_BY_ANOTHER_IDENTITY
      manager.getAgentContextInfo("/metrics")?.agentContexts?.map { it.agentId } shouldBe [owner.agentId]
    }

    // Consolidated mode's whole point: several agents of one identity backing the same path.
    "an agent of the same identity should join a consolidated path" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val first = createAgentContext(consolidated = true)
      val second = createAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", first, identityName = "team_a").shouldBeNull()
      manager.addPath("/metrics", """{"job":"test"}""", second, identityName = "team_a").shouldBeNull()

      manager.getAgentContextInfo("/metrics")?.agentContexts?.map { it.agentId } shouldBe
        [first.agentId, second.agentId]
    }

    // With agent auth off, or every agent on the legacy shared token, all agents present the same identity, so
    // consolidated paths work exactly as they always have.
    "agents should join a consolidated path as before when agent auth is disabled" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val first = createAgentContext(consolidated = true)
      val second = createAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", first).shouldBeNull()
      manager.addPath("/metrics", """{"job":"test"}""", second).shouldBeNull()

      manager.getAgentContextInfo("/metrics")?.agentContexts.shouldNotBeNull().shouldHaveSize(2)
    }

    // A path whose agents are all gone is nobody's, so another identity may take it on -- as for a non-consolidated
    // path, and so a conflict clears once the agents holding it disconnect.
    "another identity should join a consolidated path whose agents are no longer valid" {
      val manager = ProxyPathManager(createMockProxy(), isTestMode = true)
      val owner = createAgentContext(consolidated = true)
      val newcomer = createAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", owner, identityName = "team_a").shouldBeNull()
      owner.invalidate()
      manager.addPath("/metrics", """{"job":"test"}""", newcomer, identityName = "team_b").shouldBeNull()

      // The path is team_b's now, so the next agent to join is measured against that.
      manager.getAgentContextInfo("/metrics").shouldNotBeNull().identityName shouldBe "team_b"
    }

    "removePath should remove path successfully" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)
      manager.pathMapSize shouldBe 1

      val response = manager.removePath("/metrics", context.agentId)

      response.valid.shouldBeTrue()
      manager.pathMapSize shouldBe 0
    }

    "removePath should fail when path not found" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val response = manager.removePath("/nonexistent", "agent-123")

      response.valid.shouldBeFalse()
      response.reason shouldContain "path not found"
    }

    "removePath should fail when agent ID mismatch" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val response = manager.removePath("/metrics", "wrong-agent-id")

      response.valid.shouldBeFalse()
      response.reason shouldContain "invalid agentId"
    }

    "removePath should throw when path is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.removePath("", "agent-123")
      }

      exception.message shouldContain "Blank path"
    }

    "removePath should throw when agentId is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.removePath("/metrics", "")
      }

      exception.message shouldContain "Blank agentId"
    }

    // Tests partial removal from a consolidated path group. When one agent disconnects,
    // only that agent is removed from the group - the path remains registered with the
    // remaining agents. The path is only fully removed when the last agent disconnects.
    // This ensures continuous availability during rolling deployments or agent restarts.
    "removePath should remove one element from consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      val response = manager.removePath("/metrics", context1.agentId)

      response.valid.shouldBeTrue()
      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe context2.agentId
    }

    "getAgentContextInfo should return null for non-existent path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val info = manager.getAgentContextInfo("/nonexistent")

      info.shouldBeNull()
    }

    "pathMapSize should return correct count" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext()
      val context2 = createMockAgentContext()

      manager.pathMapSize shouldBe 0

      manager.addPath("/metrics1", """{"job":"test"}""", context1)
      manager.pathMapSize shouldBe 1

      manager.addPath("/metrics2", """{"job":"test"}""", context2)
      manager.pathMapSize shouldBe 2

      manager.removePath("/metrics1", context1.agentId)
      manager.pathMapSize shouldBe 1
    }

    "allPaths should return all registered paths" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics1", """{"job":"test"}""", context)
      manager.addPath("/metrics2", """{"job":"test"}""", context)
      manager.addPath("/metrics3", """{"job":"test"}""", context)

      val paths = manager.allPaths

      paths.shouldHaveSize(3)
      paths shouldContain "/metrics1"
      paths shouldContain "/metrics2"
      paths shouldContain "/metrics3"
    }

    "allPathContextInfos should atomically snapshot paths and their info" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics1", """{"job":"test1"}""", context1)
      manager.addPath("/metrics2", """{"job":"test2"}""", context2)

      val snapshot = manager.allPathContextInfos()

      // Should contain all paths with their info
      snapshot.mapShouldHaveSize(2)
      snapshot.keys shouldContain "/metrics1"
      snapshot.keys shouldContain "/metrics2"

      // Info should match what was registered
      val info1 = snapshot["/metrics1"]
      info1.shouldNotBeNull()
      info1.agentContexts.shouldHaveSize(1)
      info1.agentContexts[0].agentId shouldBe context1.agentId

      val info2 = snapshot["/metrics2"]
      info2.shouldNotBeNull()
      info2.agentContexts.shouldHaveSize(1)
      info2.agentContexts[0].agentId shouldBe context2.agentId

      // Removing a path after snapshot should not affect the snapshot
      manager.removePath("/metrics1", context1.agentId)
      snapshot.mapShouldHaveSize(2)
      snapshot["/metrics1"].shouldNotBeNull()

      // New snapshot should reflect the removal
      val snapshot2 = manager.allPathContextInfos()
      snapshot2.mapShouldHaveSize(1)
      snapshot2["/metrics1"].shouldBeNull()
      snapshot2["/metrics2"].shouldNotBeNull()
    }

    "removeFromPathManager should remove all paths for agent" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      every { proxy.agentContextManager.getAgentContext(context.agentId) } returns context

      manager.addPath("/metrics1", """{"job":"test"}""", context)
      manager.addPath("/metrics2", """{"job":"test"}""", context)
      manager.pathMapSize shouldBe 2

      manager.removeFromPathManager(context.agentId, "disconnect")

      manager.pathMapSize shouldBe 0
    }

    // ==================== removeFromPathManager Iteration Safety (Bug #4) ====================

    "removeFromPathManager should remove all paths when many paths registered for one agent" {
      // This test verifies that removeFromPathManager correctly removes ALL paths for a
      // disconnecting agent. The old code modified the map during forEach iteration, which
      // could cause ConcurrentHashMap's weakly consistent iterator to skip entries.
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      every { proxy.agentContextManager.getAgentContext(context.agentId) } returns context

      val pathCount = 50
      for (i in 1..pathCount) {
        manager.addPath("/metrics$i", """{"job":"test$i"}""", context)
      }
      manager.pathMapSize shouldBe pathCount

      manager.removeFromPathManager(context.agentId, "disconnect")

      manager.pathMapSize shouldBe 0
    }

    "removeFromPathManager should only remove paths for the specified agent" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val agent1 = createMockAgentContext()
      val agent2 = createMockAgentContext()

      every { proxy.agentContextManager.getAgentContext(agent1.agentId) } returns agent1
      every { proxy.agentContextManager.getAgentContext(agent2.agentId) } returns agent2

      // Register paths for both agents
      for (i in 1..20) {
        manager.addPath("/agent1_metrics$i", """{"job":"a1"}""", agent1)
      }
      for (i in 1..10) {
        manager.addPath("/agent2_metrics$i", """{"job":"a2"}""", agent2)
      }
      manager.pathMapSize shouldBe 30

      // Remove only agent1's paths
      manager.removeFromPathManager(agent1.agentId, "disconnect")

      // Only agent2's paths should remain
      manager.pathMapSize shouldBe 10
      for (i in 1..10) {
        manager.getAgentContextInfo("/agent2_metrics$i").shouldNotBeNull()
      }
      for (i in 1..20) {
        manager.getAgentContextInfo("/agent1_metrics$i").shouldBeNull()
      }
    }

    "removeFromPathManager should remove agent from consolidated paths without removing the path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val agent1 = createMockAgentContext(consolidated = true)
      val agent2 = createMockAgentContext(consolidated = true)

      every { proxy.agentContextManager.getAgentContext(agent1.agentId) } returns agent1

      // Both agents share consolidated paths
      for (i in 1..10) {
        manager.addPath("/shared$i", """{"job":"shared"}""", agent1)
        manager.addPath("/shared$i", """{"job":"shared"}""", agent2)
      }
      manager.pathMapSize shouldBe 10

      // Remove agent1 -- paths should remain with only agent2
      manager.removeFromPathManager(agent1.agentId, "disconnect")

      manager.pathMapSize shouldBe 10
      for (i in 1..10) {
        val info = manager.getAgentContextInfo("/shared$i")
        info.shouldNotBeNull()
        info.agentContexts.shouldHaveSize(1)
        info.agentContexts[0].agentId shouldBe agent2.agentId
      }
    }

    // ==================== AgentContextInfo.isNotValid() (Bug #8) ====================

    "non-consolidated path should be invalid when single agent is invalid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      // Register while valid, then the agent becomes invalid (disconnect/eviction) while the path is
      // still mapped -- addValidatedPath now rejects an already-invalid context (finding 7, Fix 1).
      manager.addPath("/metrics", """{"job":"test"}""", context)
      every { context.isNotValid() } returns true

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isNotValid().shouldBeTrue()
    }

    "non-consolidated path should be valid when single agent is valid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()
      // isNotValid() returns false by default from createMockAgentContext

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isNotValid().shouldBeFalse()
    }

    // Bug #8: The old code had `fun isNotValid() = !isConsolidated && agentContexts[0].isNotValid()`
    // which always returned false for consolidated paths. This meant consolidated paths with all
    // invalid agents were still considered valid, causing requests to time out instead of getting
    // an immediate error response.
    "consolidated path should be invalid when all agents are invalid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      // Register both while valid, then both agents become invalid (finding 7, Fix 1 rejects adding an
      // already-invalid context, so invalidate after registration).
      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)
      every { context1.isNotValid() } returns true
      every { context2.isNotValid() } returns true

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      // Before the fix, this returned false even with all agents invalid
      info.isNotValid().shouldBeTrue()
    }

    "consolidated path should be valid when at least one agent is valid" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      // Register both while valid, then only context1 becomes invalid (finding 7, Fix 1).
      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)
      every { context1.isNotValid() } returns true
      every { context2.isNotValid() } returns false

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      info.isNotValid().shouldBeFalse()
    }

    // ==================== toPlainText ====================

    "toPlainText should return message when no agents connected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val text = manager.toPlainText()

      text shouldBe "No agents connected."
    }

    "toPlainText should return formatted path map" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val text = manager.toPlainText()

      text shouldContain "Proxy Path Map"
      text shouldContain "/metrics"
    }

    // ==================== Consolidated/Non-Consolidated Mismatch Tests ====================

    // Bug #8: addPath now rejects consolidated/non-consolidated mismatch instead of
    // silently allowing mixed types, which could cause unexpected fan-out behavior.
    "addPath should reject consolidated agent on non-consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)
      val consolidatedContext = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", nonConsolidatedContext).shouldBeNull()
      // Consolidated agent should be rejected on a non-consolidated path
      val rejection = manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext)
      rejection.shouldNotBeNull().cause shouldBe PathRejectionCause.CONSOLIDATION_MISMATCH

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      // Only the original non-consolidated agent should be present
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe nonConsolidatedContext.agentId
    }

    "addPath should reject non-consolidated agent on consolidated path" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val consolidatedContext = createMockAgentContext(consolidated = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)

      // First register as consolidated
      manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext).shouldBeNull()
      // Non-consolidated should be rejected on a consolidated path
      val rejection = manager.addPath("/metrics", """{"job":"test2"}""", nonConsolidatedContext)
      rejection.shouldNotBeNull().cause shouldBe PathRejectionCause.CONSOLIDATION_MISMATCH

      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      // Original consolidated path should be unchanged
      info.isConsolidated.shouldBeTrue()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe consolidatedContext.agentId
    }

    // Bug #11: addPath returns a descriptive reason string on failure instead of just false
    "addPath should return reason containing 'Consolidated' when consolidated agent rejected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)
      val consolidatedContext = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", nonConsolidatedContext)
      val reason = manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext)?.reason

      reason.shouldNotBeNull()
      reason shouldContain "Consolidated"
      reason shouldContain "/metrics"
    }

    "addPath should return reason containing 'Non-consolidated' when non-consolidated agent rejected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val consolidatedContext = createMockAgentContext(consolidated = true)
      val nonConsolidatedContext = createMockAgentContext(consolidated = false)

      manager.addPath("/metrics", """{"job":"test"}""", consolidatedContext)
      val reason = manager.addPath("/metrics", """{"job":"test2"}""", nonConsolidatedContext)?.reason

      reason.shouldNotBeNull()
      reason shouldContain "Non-consolidated"
      reason shouldContain "/metrics"
    }

    "addPath should return null on success" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      val result = manager.addPath("/metrics", """{"job":"test"}""", context)

      result.shouldBeNull()
    }

    // ==================== removeFromPathManager Edge Cases ====================

    // A path's per-path metric series go when its last registration does, so a retired path stops holding series
    // in memory and on /metrics.
    "removePath should remove the path's metric series when its last registration goes away" {
      val proxy = createMockProxy()
      val metrics = mockk<ProxyMetrics>(relaxed = true)
      routeMetrics(proxy, metrics)
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()
      manager.addPath("metrics", "{}", context)

      manager.removePath("metrics", context.agentId)

      verify(exactly = 1) { metrics.removePathSeries("metrics") }
    }

    "removePath should keep a consolidated path's metric series while another agent still serves it" {
      val proxy = createMockProxy()
      val metrics = mockk<ProxyMetrics>(relaxed = true)
      routeMetrics(proxy, metrics)
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)
      manager.addPath("metrics", "{}", context1)
      manager.addPath("metrics", "{}", context2)

      manager.removePath("metrics", context1.agentId)
      verify(exactly = 0) { metrics.removePathSeries(any()) }

      manager.removePath("metrics", context2.agentId)
      verify(exactly = 1) { metrics.removePathSeries("metrics") }
    }

    // ProxyMetrics records a path's series only while the path is registered, so a scrape finishing after its path
    // is gone can't re-create them. Registration is what starts that.
    "addPath should start recording the path's metric series" {
      val proxy = createMockProxy()
      val metrics = mockk<ProxyMetrics>(relaxed = true)
      routeMetrics(proxy, metrics)
      val manager = ProxyPathManager(proxy, isTestMode = true)

      manager.addPath("metrics", "{}", createMockAgentContext())

      verify(exactly = 1) { metrics.pathRegistered("metrics") }
    }

    "a rejected addPath should not start recording metric series" {
      val proxy = createMockProxy()
      val metrics = mockk<ProxyMetrics>(relaxed = true)
      routeMetrics(proxy, metrics)
      val manager = ProxyPathManager(proxy, isTestMode = true)

      manager.addPath("app/metrics", "{}", createMockAgentContext()).shouldNotBeNull()

      verify(exactly = 0) { metrics.pathRegistered(any()) }
    }

    // An agent disconnect retires every path it alone served, and only those.
    "removeFromPathManager should remove the metric series of each path the disconnect retires" {
      val proxy = createMockProxy()
      val metrics = mockk<ProxyMetrics>(relaxed = true)
      routeMetrics(proxy, metrics)
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val leaving = createMockAgentContext()
      val staying = createMockAgentContext()
      manager.addPath("a", "{}", leaving)
      manager.addPath("b", "{}", leaving)
      manager.addPath("c", "{}", staying)

      manager.removeFromPathManager(leaving.agentId, "disconnect")

      verify(exactly = 1) { metrics.removePathSeries("a") }
      verify(exactly = 1) { metrics.removePathSeries("b") }
      verify(exactly = 0) { metrics.removePathSeries("c") }
    }

    "removeFromPathManager should throw when agentId is empty" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val exception = shouldThrow<IllegalArgumentException> {
        manager.removeFromPathManager("", "test")
      }

      exception.message shouldContain "Blank agentId"
    }

    "removeFromPathManager should handle missing agent context gracefully" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      every { proxy.agentContextManager.getAgentContext("missing-agent") } returns null

      // Should not throw
      manager.removeFromPathManager("missing-agent", "disconnect")
    }

    // Finding 7: registerPath checks the agent context validity outside the pathMap lock, so agent
    // removal can interleave and leave a path pointing at an invalidated context that no cleanup sweeps.
    // Fix 1: addValidatedPath re-checks validity inside the lock and rejects an invalidated context.
    "Finding 7: addPath should reject an already-invalidated agent context" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()
      // Simulate the context being invalidated by a racing removal between the caller's check and here.
      every { context.isNotValid() } returns true

      val rejection = manager.addPath("/metrics", """{"job":"test"}""", context)

      rejection.shouldNotBeNull().reason shouldContain "invalidated"
      rejection.cause shouldBe PathRejectionCause.INVALID_AGENT
      manager.pathMapSize shouldBe 0
      manager.getAgentContextInfo("/metrics").shouldBeNull()
    }

    // Fix 2: removeFromPathManager sweeps the pathMap by agentId even when the context is already gone
    // from the manager, so a path stranded by the race above is still cleaned up.
    "Finding 7: removeFromPathManager sweeps a stranded path even when the context is gone" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()
      val agentId = context.agentId

      manager.addPath("/metrics", """{"job":"test"}""", context)
      manager.pathMapSize shouldBe 1

      // The context has already been removed from the manager (raced disconnect).
      every { proxy.agentContextManager.getAgentContext(agentId) } returns null

      manager.removeFromPathManager(agentId, "disconnect")

      manager.pathMapSize shouldBe 0
      manager.getAgentContextInfo("/metrics").shouldBeNull()
    }

    // ==================== getAgentContextInfo Defensive Copy ====================

    "getAgentContextInfo should return a snapshot copy" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)

      // Take a snapshot
      val info1 = manager.getAgentContextInfo("/metrics")
      info1.shouldNotBeNull()
      info1.agentContexts.shouldHaveSize(1)

      // Mutate the path map by adding another agent
      manager.addPath("/metrics", """{"job":"test"}""", context2)

      // Original snapshot should be unaffected
      info1.agentContexts.shouldHaveSize(1)

      // New retrieval should reflect the update
      val info2 = manager.getAgentContextInfo("/metrics")
      info2.shouldNotBeNull()
      info2.agentContexts.shouldHaveSize(2)
    }

    // ==================== M1: agentContexts typed as immutable List ====================

    // M1: AgentContextInfo.agentContexts is an immutable List<AgentContext>. The three mutating
    // call sites (addPath, removePath, removeFromPathManager) replace the pathMap entry with a
    // copy() carrying a new list rather than mutating in place, so a data-class copy() can never
    // share a still-mutating list with the stored entry. These tests exercise that the
    // consolidated add/remove paths still behave correctly under that copy-on-write model.
    "consolidated addPath should not require unsafe cast to MutableList" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val contexts = (1..5).map { createMockAgentContext(consolidated = true) }

      // First agent creates the path
      manager.addPath("/metrics", """{"job":"test"}""", contexts[0])

      // Subsequent agents append to the consolidated list — previously required
      // (agentInfo.agentContexts as MutableList) += agentContext
      for (i in 1 until contexts.size) {
        manager.addPath("/metrics", """{"job":"test"}""", contexts[i])
      }

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(5)
      contexts.forEach { ctx ->
        info.agentContexts.map { it.agentId } shouldContain ctx.agentId
      }
    }

    "removePath from consolidated group should not require unsafe cast to MutableList" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context1 = createMockAgentContext(consolidated = true)
      val context2 = createMockAgentContext(consolidated = true)
      val context3 = createMockAgentContext(consolidated = true)

      manager.addPath("/metrics", """{"job":"test"}""", context1)
      manager.addPath("/metrics", """{"job":"test"}""", context2)
      manager.addPath("/metrics", """{"job":"test"}""", context3)

      // Remove middle agent — previously required
      // (agentInfo.agentContexts as MutableList).remove(agentContext)
      val response = manager.removePath("/metrics", context2.agentId)
      response.valid.shouldBeTrue()

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(2)
      info.agentContexts.map { it.agentId } shouldContain context1.agentId
      info.agentContexts.map { it.agentId } shouldContain context3.agentId
    }

    "removeFromPathManager on consolidated paths should not require unsafe cast to MutableList" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val agent1 = createMockAgentContext(consolidated = true)
      val agent2 = createMockAgentContext(consolidated = true)

      every { proxy.agentContextManager.getAgentContext(agent1.agentId) } returns agent1

      manager.addPath("/metrics", """{"job":"test"}""", agent1)
      manager.addPath("/metrics", """{"job":"test"}""", agent2)

      // Remove agent1 via disconnect path — previously required
      // (v.agentContexts as MutableList).removeIf { it.agentId == agentId }
      manager.removeFromPathManager(agent1.agentId, "disconnect")

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe agent2.agentId
    }

    // Bug #7: addPath logged agentContexts[0] without an empty-list guard when
    // overwriting a non-consolidated path. The fix uses firstOrNull() to prevent a
    // potential IndexOutOfBoundsException. This test exercises the overwrite log path
    // with many rapid overwrites to verify it is safe.
    "rapid non-consolidated overwrites should not crash" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = false)

      // Register and overwrite the same non-consolidated path many times in
      // succession. Each overwrite hits the log line that previously used [0].
      repeat(20) { i ->
        val context = createMockAgentContext()
        manager.addPath("/metrics", """{"job":"test-$i"}""", context)
      }

      // After all overwrites, only the last registration should remain
      manager.pathMapSize shouldBe 1
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
    }

    // ==================== toPlainText with Multiple Paths ====================

    "toPlainText should format paths with different lengths correctly" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      manager.addPath("/a", """{}""", createMockAgentContext())
      manager.addPath("/very-long-metrics-path", """{}""", createMockAgentContext())
      manager.addPath("/medium", """{}""", createMockAgentContext())

      val text = manager.toPlainText()

      text shouldContain "Proxy Path Map"
      text shouldContain "/a"
      text shouldContain "/very-long-metrics-path"
      text shouldContain "/medium"
    }

    // ==================== AgentContextInfo Tests ====================

    "AgentContextInfo toString should include key fields" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)
      val context = createMockAgentContext()

      manager.addPath("/metrics", """{"job":"test"}""", context)

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()

      val str = info.toString()
      str shouldContain "AgentContextInfo"
      str shouldContain "isConsolidated"
      str shouldContain "labels"
    }

    // ==================== Bug #6: Displaced Agent Invalidation Tests ====================

    // Bug #6: When a non-consolidated agent overwrites a path, the old agent context(s)
    // were left orphaned — still in the agentContextManager but with no paths in the
    // pathMap. The fix invalidates displaced agents that have no other registered paths.

    "overwriting non-consolidated path should invalidate displaced agent that is already disconnected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      // Use real AgentContexts so invalidate() actually works
      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      oldAgent.isValid().shouldBeTrue()

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Simulate the old agent's connection dying before the overwrite
      oldAgent.invalidate()
      oldAgent.isValid().shouldBeFalse()

      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent was already invalid and had no other paths — invalidate is a no-op but safe
      oldAgent.isValid().shouldBeFalse()
      // New agent remains valid
      newAgent.isValid().shouldBeTrue()

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe newAgent.agentId
    }

    // Bug #8 fix: A live (valid) agent displaced from its only path IS now
    // invalidated. Without this, the displaced agent stays alive indefinitely
    // via heartbeats, consuming resources with zero paths. The agent will
    // reconnect and re-register its paths if needed.
    "overwriting path should invalidate displaced agent even if still connected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      oldAgent.isValid().shouldBeTrue()

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Old agent is still connected (valid) but has no other paths
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent IS invalidated because it has zero remaining paths
      oldAgent.isValid().shouldBeFalse()
      newAgent.isValid().shouldBeTrue()

      // Path should now belong to the new agent
      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.agentContexts.shouldHaveSize(1)
      info.agentContexts[0].agentId shouldBe newAgent.agentId
    }

    "overwriting path should not invalidate displaced agent that has other paths" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      // Old agent has two paths
      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)
      manager.addPath("/health", """{"job":"test"}""", oldAgent)

      oldAgent.isValid().shouldBeTrue()

      // Overwrite only /metrics
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent should still be valid because it still has /health
      oldAgent.isValid().shouldBeTrue()
      newAgent.isValid().shouldBeTrue()

      // /metrics now points to newAgent
      val metricsInfo = manager.getAgentContextInfo("/metrics")
      metricsInfo.shouldNotBeNull()
      metricsInfo.agentContexts[0].agentId shouldBe newAgent.agentId

      // /health still points to oldAgent
      val healthInfo = manager.getAgentContextInfo("/health")
      healthInfo.shouldNotBeNull()
      healthInfo.agentContexts[0].agentId shouldBe oldAgent.agentId
    }

    "non-consolidated overwrite of consolidated path should be rejected" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val consolidated1 = AgentContext("remote-c1")
      consolidated1.assignProperties(
        mockk<RegisterAgentRequest> {
          every { launchId } returns "l1"
          every { agentName } returns "c1"
          every { hostName } returns "h1"
          every { consolidated } returns true
          every { proxyEndpointsList } returns LazyStringArrayList()
          every { currentEndpointIndex } returns 0
        },
      )
      val consolidated2 = AgentContext("remote-c2")
      consolidated2.assignProperties(
        mockk<RegisterAgentRequest> {
          every { launchId } returns "l2"
          every { agentName } returns "c2"
          every { hostName } returns "h2"
          every { consolidated } returns true
          every { proxyEndpointsList } returns LazyStringArrayList()
          every { currentEndpointIndex } returns 0
        },
      )
      val newAgent = AgentContext("remote-new")

      // Two consolidated agents share a path
      manager.addPath("/metrics", """{"job":"test"}""", consolidated1)
      manager.addPath("/metrics", """{"job":"test"}""", consolidated2)

      consolidated1.isValid().shouldBeTrue()
      consolidated2.isValid().shouldBeTrue()

      // Non-consolidated agent should be rejected (Bug #8 fix)
      manager.addPath("/metrics", """{"job":"test"}""", newAgent).shouldNotBeNull()

      // Consolidated agents should remain valid and unchanged
      consolidated1.isValid().shouldBeTrue()
      consolidated2.isValid().shouldBeTrue()

      val info = manager.getAgentContextInfo("/metrics")
      info.shouldNotBeNull()
      info.isConsolidated.shouldBeTrue()
      info.agentContexts.shouldHaveSize(2)
    }

    "overwriting should invalidate displaced dead agents with backlog and drain it" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Build up backlog on old agent
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.scrapeRequestBacklogSize shouldBe 2

      // Simulate the old agent's connection dying
      oldAgent.invalidate()
      oldAgent.isValid().shouldBeFalse()
      oldAgent.scrapeRequestBacklogSize shouldBe 0

      // Overwrite the path — old agent is already dead, invalidation is safe
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      oldAgent.isValid().shouldBeFalse()
    }

    // Bug #8 fix: displaced live agents with zero remaining paths are now
    // invalidated even if they have a backlog. The backlog is drained on
    // invalidation, and the agent will reconnect.
    "overwriting should invalidate displaced live agent and drain its backlog" {
      val proxy = createMockProxy()
      val manager = ProxyPathManager(proxy, isTestMode = true)

      val oldAgent = AgentContext("remote-old")
      val newAgent = AgentContext("remote-new")

      manager.addPath("/metrics", """{"job":"test"}""", oldAgent)

      // Build up backlog on old agent
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.writeScrapeRequest(mockk(relaxed = true))
      oldAgent.scrapeRequestBacklogSize shouldBe 2

      // Old agent is still connected but has zero remaining paths after overwrite
      manager.addPath("/metrics", """{"job":"test"}""", newAgent)

      // Old agent should be invalidated (zero paths remaining)
      oldAgent.isValid().shouldBeFalse()
      // Backlog should be drained by invalidation
      oldAgent.scrapeRequestBacklogSize shouldBe 0
    }
  }
}
