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

package io.prometheus.agent.dashboard

import com.pambrose.common.util.runCatchingCancellable
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.prometheus.Agent
import io.prometheus.agent.PathSource
import io.prometheus.common.agentOptionsFromConfig
import kotlin.io.path.createTempFile

/**
 * [AgentSnapshot.collect], from an agent built but never started: its connection before the first connect, every
 * configured path pending, with its URL redacted and its filter, the agent's settings with no secret among them, and
 * no discovery while it is off. The states a running agent reaches, registered, refused, and retrying, and a failed
 * connect, are covered live in `AgentWebDashboardTest`.
 */
class AgentSnapshotTest : StringSpec() {
  private val created = mutableListOf<Agent>()

  private fun agent(config: String): Agent =
    Agent(agentOptionsFromConfig(config), inProcessServerName = "agent-snapshot-${System.nanoTime()}", testMode = true)
      .also { created += it }

  init {
    afterTest {
      created.forEach { agent ->
        runCatching { agent.grpcService.shutDown() }
        runCatchingCancellable { agent.agentHttpService.close() }
      }
      created.clear()
    }

    "before its first connect, the agent should be connecting, to the first of its endpoints, with no id" {
      val snapshot =
        AgentSnapshot.collect(agent("""agent.proxy.endpoints = ["proxy-a:50051", "proxy-b:50052"]"""))

      snapshot.agentId shouldBe ""
      snapshot.connection.apply {
        state shouldBe ConnectionState.CONNECTING
        endpoint shouldBe "proxy-a:50051"
        endpoints.map { it.spec } shouldContainExactly ["proxy-a:50051", "proxy-b:50052"]
        endpoints.map { it.current } shouldContainExactly [true, false]
        endpoints.all { it.failure == null }.shouldBeTrue()
        failedOver.shouldBeFalse()
        since.shouldBeNull()
        connects shouldBe 0
      }
    }

    "every configured path should be pending until the agent registers it, sorted, with its URL redacted" {
      val snapshot =
        AgentSnapshot.collect(
          agent(
            """
            agent.pathConfigs = [
              { name: "Orders", path: "/orders_metrics", url: "http://ops:s3cret@orders:9100/metrics?token=abc" }
              { name: "Billing", path: "billing_metrics", url: "http://billing:9100/metrics", labels: "{\"team\": \"b\"}" }
            ]
            """,
          ),
        )

      snapshot.paths.map { it.path } shouldContainExactly ["billing_metrics", "orders_metrics"]
      snapshot.paths.all { it.state == PathState.PENDING && it.source == PathSource.STATIC }.shouldBeTrue()
      snapshot.unregistered.size shouldBe 2
      snapshot.paths.first { it.path == "orders_metrics" }.apply {
        name shouldBe "Orders"
        url shouldNotContain "s3cret"
        url shouldNotContain "abc"
        failure.shouldBeNull()
      }
      snapshot.paths.first { it.path == "billing_metrics" }.labels shouldBe """{"team": "b"}"""
    }

    // An empty object, however it's spaced, is how the config and the agent say "no labels", so the page shows none.
    "a path's empty labels object should read as no labels" {
      val snapshot =
        AgentSnapshot.collect(
          agent(
            """
            agent.pathConfigs = [
              { name: "A", path: "a_metrics", url: "http://a/metrics", labels: "{}" }
              { name: "B", path: "b_metrics", url: "http://b/metrics", labels: "{ \n }" }
            ]
            """,
          ),
        )

      snapshot.paths.map { it.labels } shouldContainExactly ["", ""]
    }

    "a path's metric filter should carry its rules, with nothing dropped yet" {
      val snapshot =
        AgentSnapshot.collect(
          agent(
            """
            agent.pathConfigs = [{ name: "Node", path: "node_metrics", url: "http://node:9100/metrics" }]
            agent.filters = [{ path: "node_metrics", metricNameAllow: ["node_cpu.*"], metricNameDeny: ["node_cpu_guest.*"] }]
            """,
          ),
        )

      snapshot.paths.single().filter.shouldNotBeNull().apply {
        allow shouldContainExactly ["node_cpu.*"]
        deny shouldContainExactly ["node_cpu_guest.*"]
        linesDropped shouldBe 0
        bytesSaved shouldBe 0
        failedOpen.shouldBeNull()
      }
    }

    "the agent's settings should show what it resolved, and only whether a token is set" {
      val snapshot = AgentSnapshot.collect(agent("""agent { agentToken = "tok-3n7", scrapeTimeoutSecs = 7 }"""))

      snapshot.connection.tokenSet.shouldBeTrue()
      snapshot.settings.toMap()["Scrape timeout"] shouldBe "7s"
      snapshot.settings.none { (name, value) -> "tok-3n7" in name || "tok-3n7" in value }.shouldBeTrue()
    }

    "with discovery off, the snapshot should have none" {
      AgentSnapshot.collect(agent("")).discovery.shouldBeNull()
    }

    // Before its first read, discovery has its file and interval, and nothing read, failed, skipped, or in conflict.
    "with discovery on, the snapshot should carry its file and interval, before anything is read" {
      val file = createTempFile("discovery", ".conf").toFile().apply { writeText("paths = []") }
      try {
        val snapshot =
          AgentSnapshot.collect(
            agent(
              """agent.discovery { enabled = true, file.path = "${file.absolutePath}", reconcileIntervalSecs = 9 }""",
            ),
          )

        snapshot.discovery.shouldNotBeNull().apply {
          this.file shouldBe file.absolutePath
          intervalSecs shouldBe 9
          failure.shouldBeNull()
          entries shouldBe 0
          skipped.shouldBeEmpty()
          collisions.shouldBeEmpty()
          duplicates.shouldBeEmpty()
        }
      } finally {
        file.delete()
      }
    }
  }
}
