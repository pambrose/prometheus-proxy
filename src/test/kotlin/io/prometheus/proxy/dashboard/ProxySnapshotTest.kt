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

package io.prometheus.proxy.dashboard

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class ProxySnapshotTest : StringSpec() {
  private fun agentView(
    inactivitySecs: Long,
    endpoints: List<String> = emptyList(),
    endpointIndex: Int = 0,
  ) = DashboardFixtures.agentView(
      inactivity = inactivitySecs.seconds,
      paths = emptyList(),
      proxyEndpoints = endpoints,
      currentEndpointIndex = endpointIndex,
    )

  init {
    "eviction countdown should be the remaining inactivity budget" {
      agentView(inactivitySecs = 2).evictionCountdownSecs(60) shouldBe 58
      agentView(inactivitySecs = 59).evictionCountdownSecs(60) shouldBe 1
    }

    // An agent past its budget is awaiting the next cleanup sweep, not owed negative time. Rendering a
    // negative countdown would read as a bug to an operator.
    "eviction countdown should floor at zero once overdue" {
      agentView(inactivitySecs = 90).evictionCountdownSecs(60) shouldBe 0
    }

    "health should compare each size against its own threshold" {
      val health =
        HealthView(
          agentCount = 3,
          pathCount = 7,
          chunkContextSize = 24,
          chunkContextThreshold = 25,
          scrapeMapSize = 25,
          scrapeMapThreshold = 25,
        )
      // The proxy's own health checks treat >= threshold as unhealthy, so at-threshold is not healthy.
      health.chunkContextHealthy.shouldBeTrue()
      health.scrapeMapHealthy.shouldBeFalse()
    }

    // ==================== Failover position ====================

    // The signal the dashboard otherwise cannot show. Each proxy sees only its own agents, so a
    // failover looks like a disappearance on one and an appearance on the other; a non-zero index is
    // what identifies the appearance as a failover rather than a fresh start.
    "an agent on a secondary endpoint should report its failover position" {
      agentView(2, ["proxy-a:50051", "proxy-b:50051"], endpointIndex = 1)
        .failoverPosition shouldBe "proxy-b:50051 (2 of 2)"
    }

    "an agent on its primary should still report position when failover is configured" {
      agentView(2, ["proxy-a:50051", "proxy-b:50051"], endpointIndex = 0)
        .failoverPosition shouldBe "proxy-a:50051 (1 of 2)"
    }

    // A single-endpoint agent has no failover story, and one predating the proto fields reports
    // nothing at all -- neither should render a position.
    "an agent without failover configured should report no position" {
      agentView(2, ["proxy-a:50051"]).failoverPosition.shouldBeNull()
      agentView(2, emptyList()).failoverPosition.shouldBeNull()
    }

    // An index the agent reports but the list cannot support must degrade rather than throw: this is
    // remote input, and an older or misbehaving agent could send anything.
    "an out-of-range index should report no position rather than throw" {
      agentView(2, ["proxy-a:50051", "proxy-b:50051"], endpointIndex = 7).failoverPosition.shouldBeNull()
      agentView(2, emptyList(), endpointIndex = 3).failoverPosition.shouldBeNull()
    }

    // ==================== Path view ====================

    // A departed path's only remaining attribution is the scrape record, since its agent list is empty
    // by definition -- so servingAgent has to fall through to it or the row says nothing useful.
    "a path view should name its serving agent, falling back to the last scrape" {
      DashboardFixtures.pathView(agentIds = ["a1", "a2"]).servingAgent shouldBe "a1"
      DashboardFixtures.pathView(
        agentIds = emptyList(),
        lastScrape = DashboardFixtures.scrapeRecord(agentId = "departed-7"),
        isDeparted = true,
      ).servingAgent shouldBe "departed-7"
      DashboardFixtures.pathView(agentIds = emptyList()).servingAgent.shouldBeNull()
    }

    "additional agent count should drive the consolidated marker and never go negative" {
      DashboardFixtures.pathView(agentIds = ["a1", "a2", "a3"]).additionalAgents shouldBe 2
      DashboardFixtures.pathView(agentIds = ["a1"]).additionalAgents shouldBe 0
      DashboardFixtures.pathView(agentIds = emptyList()).additionalAgents shouldBe 0
    }

    // ==================== buildPathViews: join and union ====================

    "each registered path should be joined to its most recent scrape" {
      val registered = [
        DashboardFixtures.pathView(
        path = "app_metrics",
      ), DashboardFixtures.pathView(path = "node_metrics"),
      ]
      // Newest-first, as Proxy.recentScrapes() returns them.
      val scrapes =
        [
          DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 503),
          DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 200),
          DashboardFixtures.scrapeRecord(path = "node_metrics", durationMillis = 12),
        ]

      val views = ProxySnapshot.buildPathViews(registered, scrapes)

      views.map { it.path } shouldBe ["app_metrics", "node_metrics"]
      // The 503 is first in the newest-first list, so it must win over the older 200.
      views.first().lastScrape?.statusCode shouldBe 503
      views.last().lastScrape?.durationMillis shouldBe 12
    }

    "a registered path with no scrapes yet should carry no scrape rather than being dropped" {
      val views = ProxySnapshot.buildPathViews([DashboardFixtures.pathView(path = "brand_new")], emptyList())

      views.single().path shouldBe "brand_new"
      views.single().lastScrape.shouldBeNull()
      views.single().isDeparted.shouldBeFalse()
    }

    // The gap this whole layout exists to close. The proxy deletes a path the moment its last agent
    // disconnects, so without this union a target that stopped serving vanishes silently.
    "a scraped path that is no longer registered should appear as departed" {
      val scrapes = [DashboardFixtures.scrapeRecord(agentId = "agent-9", path = "orphan_metrics", statusCode = 503)]

      val views = ProxySnapshot.buildPathViews(emptyList(), scrapes)

      views.single().apply {
        path shouldBe "orphan_metrics"
        isDeparted.shouldBeTrue()
        agentIds.shouldBeEmpty()
        // The departed agent is the only attribution left, and it is what an operator needs.
        servingAgent shouldBe "agent-9"
        lastScrape?.statusCode shouldBe 503
      }
    }

    "a still-registered path should never be marked departed, however many scrapes it has" {
      val views =
        ProxySnapshot.buildPathViews(
          [DashboardFixtures.pathView(path = "app_metrics")],
          [DashboardFixtures.scrapeRecord(path = "app_metrics")],
        )

      views.single().isDeparted.shouldBeFalse()
    }

    // Interleaved rather than appended: a departed path is not a second-class row, and an operator
    // scanning alphabetically should find it where they expect.
    "live and departed paths should be sorted together as one list" {
      val registered = [DashboardFixtures.pathView(path = "b_live"), DashboardFixtures.pathView(path = "d_live")]
      val scrapes =
        [DashboardFixtures.scrapeRecord(path = "c_gone"), DashboardFixtures.scrapeRecord(path = "a_gone")]

      val views = ProxySnapshot.buildPathViews(registered, scrapes)

      views.map { it.path } shouldBe ["a_gone", "b_live", "c_gone", "d_live"]
      views.filter { it.isDeparted }.map { it.path } shouldBe ["a_gone", "c_gone"]
    }
  }
}
