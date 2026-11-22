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
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.AgentPathManager.PathContext
import io.prometheus.agent.AgentPathManager.RegistrationFailure
import io.prometheus.agent.AgentScrapeRecord
import io.prometheus.agent.PathSource
import io.prometheus.agent.ShownFailure
import io.prometheus.common.agentOptionsFromConfig
import kotlin.io.path.createTempFile

/**
 * [AgentSnapshot.collect], from an agent built but never started: its connection before the first connect, every
 * configured path pending, with its URL redacted and its filter, the agent's settings, and no discovery while it is
 * off. The states a running agent reaches, registered, refused, and retrying, and a failed
 * connect, are covered live in `AgentWebDashboardTest`.
 */
class AgentSnapshotTest : StringSpec() {
  private val created = mutableListOf<Agent>()

  private fun agent(config: String): Agent =
    Agent(agentOptionsFromConfig(config), inProcessServerName = "agent-snapshot-${System.nanoTime()}", testMode = true)
      .also { created += it }

  private fun scrape(statusCode: Int) =
    AgentScrapeRecord(
      statusCode = statusCode,
      outcome = if (statusCode == 200) "success" else "unsuccessful",
      durationMillis = 5,
      bytes = 10,
      filteredBytes = 10,
      gzipped = false,
      chunked = false,
      failure = "",
      fromProxyDashboard = false,
    )

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

    // Registered at runtime, the path is neither configured nor discovered, so only its context gives it a row.
    "a path another agent took over should keep its row, as retrying" {
      val context = PathContext(1L, "runtime_metrics", "http://ops:s3cret@t/metrics", "{}", PathSource.STATIC)
      val manager =
        mockk<AgentPathManager>(relaxed = true) {
          every { pathConfigs } returns emptyList()
          every { registeredPaths } returns emptyList()
          every { takenOverContexts } returns listOf(context)
          every { filtersByPath } returns emptyMap()
          every { pathFailures } returns
            mapOf(
              "runtime_metrics" to
                RegistrationFailure(ShownFailure.of("Taken over by b"), true, true, null, takenOver = true),
            )
        }
      val agent =
        mockk<Agent>(relaxed = true) {
          every { pathManager } returns manager
          every { scrapeHistory } returns null
          every { pathDiscoveryService } returns null
        }

      AgentSnapshot.paths(agent).single().apply {
        path shouldBe "runtime_metrics"
        state shouldBe PathState.RETRYING
        url shouldNotContain "s3cret"
        failure.shouldNotBeNull().takenOver.shouldBeTrue()
      }
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

    // The settings themselves are AgentSettingsTest's; the snapshot carries what the dashboard read once.
    "the snapshot should carry the settings it's given, and its connection only whether a token is set" {
      val agent = agent("""agent { agentToken = "tok-3n7", scrapeTimeoutSecs = 7 }""")
      val snapshot = AgentSnapshot.collect(agent, AgentSettings.of(agent))

      snapshot.connection.tokenSet.shouldBeTrue()
      snapshot.settings.flatMap { it.rows }.single { it.name == "Scrape timeout" }.value shouldBe "7s"
      AgentSnapshot.collect(agent).settings.shouldBeEmpty()
    }

    // Recorded only with the dashboard on, and joined to each path by its key, newest first, for the path's strip.
    "with the dashboard on, each path should carry its recorded scrapes, newest first" {
      val agent =
        agent(
          """
          agent.dashboard { enabled = true, pathHistorySize = 7 }
          agent.pathConfigs = [{ name = a, path = "/orders_metrics", url = "http://orders.invalid/metrics" }]
          """,
        )
      val recorded = agent.scrapeHistory.shouldNotBeNull()
      listOf(200, 503).forEach { recorded.record("orders_metrics", scrape(it)) }

      AgentSnapshot.collect(agent).apply {
        pathHistorySize shouldBe 7
        scrapeNowEnabled.shouldBeTrue()
        paths.single().apply {
          history.map { it.statusCode } shouldContainExactly [503, 200]
          lastScrape.shouldNotBeNull().statusCode shouldBe 503
          historyRuns shouldContainExactly [true to 1, false to 1]
        }
      }
    }

    "with the dashboard off, the agent should keep no history, and Scrape now should follow its setting" {
      agent("").scrapeHistory.shouldBeNull()
      AgentSnapshot.collect(agent("agent.dashboard.scrapeNowEnabled = false")).apply {
        scrapeNowEnabled.shouldBeFalse()
        paths.forEach { it.history.shouldBeEmpty() }
      }
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
