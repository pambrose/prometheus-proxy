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

import com.pambrose.common.util.hostInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.prometheus.Proxy
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.AgentContext
import io.prometheus.proxy.PathHistory
import io.prometheus.proxy.ProxyOptions
import io.prometheus.proxy.ScrapeRecord
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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

  // Each path's records, newest first, as PathHistory.snapshot hands them over, with no departures. groupBy keeps the
  // list's order.
  private fun List<ScrapeRecord>.byPath() = PathHistory.Snapshot(scrapes = groupBy { it.path })

  // Departures alone, as PathHistory.snapshot hands over paths that left the path map before their first scrape.
  private fun departed(vararg departures: Pair<String, PathHistory.Departure>) =
    PathHistory.Snapshot(departures = departures.toMap())

  // A moment [secs] seconds into a fixed minute, so a test orders scrapes by when they ran.
  private fun at(secs: Int) = START + secs.seconds

  // [agentId]'s scrape of [path] at [at] ([secs]).
  private fun scrape(
    agentId: String,
    path: String,
    secs: Int,
  ) = DashboardFixtures.scrapeRecord(agentId = agentId, path = path, at = at(secs))

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
      DashboardFixtures.pathView(
        agentIds = ["a1", "a2"],
        agentNames = ["team-a-01", "team-a-02"],
      ).servingAgent shouldBe "team-a-01"
      DashboardFixtures.pathView(
        agentIds = emptyList(),
        agentNames = emptyList(),
        lastScrape = DashboardFixtures.scrapeRecord(agentId = "7", agentName = "departed-07"),
        isDeparted = true,
      ).servingAgent shouldBe "departed-07"
      DashboardFixtures.pathView(agentIds = emptyList(), agentNames = emptyList()).servingAgent.shouldBeNull()
    }

    // The two layouts are read against each other: an operator finds a failing path here, then looks
    // that agent up in the agent list. An internal id in one view and a name in the other would make
    // that a manual translation step, which is the whole reason servingAgent is a name.
    "a path view should identify its agent by name, never by internal id" {
      DashboardFixtures.pathView(
        agentIds = ["17"],
        agentNames = ["edge-eu-west-2"],
      ).servingAgent shouldBe "edge-eu-west-2"
    }

    // Newest first in, oldest first out: the strip reads left to right in time.
    "history runs should group consecutive like results, oldest first" {
      val history = [200, 503, 200, 200].map { DashboardFixtures.scrapeRecord(statusCode = it) }
      DashboardFixtures.pathView(history = history).apply {
        historyRuns shouldBe [true to 2, false to 1, true to 1]
      }
      DashboardFixtures.pathView().historyRuns shouldBe emptyList()
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
      // Newest-first, as PathHistory.snapshot returns them.
      val scrapes =
        [
          DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 503),
          DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 200),
          DashboardFixtures.scrapeRecord(path = "node_metrics", durationMillis = 12),
        ]

      val views = ProxySnapshot.buildPathViews(registered, scrapes.byPath())

      views.map { it.path } shouldBe ["app_metrics", "node_metrics"]
      // The 503 is first in the newest-first list, so it must win over the older 200.
      views.first().lastScrape?.statusCode shouldBe 503
      views.last().lastScrape?.durationMillis shouldBe 12
    }

    "a registered path with no scrapes yet should carry no scrape rather than being dropped" {
      val views = ProxySnapshot.buildPathViews([DashboardFixtures.pathView(path = "brand_new")], PathHistory.Snapshot())

      views.single().path shouldBe "brand_new"
      views.single().lastScrape.shouldBeNull()
      views.single().isDeparted.shouldBeFalse()
    }

    // The gap this whole layout exists to close. The proxy deletes a path the moment its last agent
    // disconnects, so without this union a target that stopped serving vanishes silently.
    "a scraped path that is no longer registered should appear as departed" {
      val scrapes =
        [
          DashboardFixtures.scrapeRecord(
            agentId = "9",
            agentName = "warehouse-01",
            path = "orphan_metrics",
            statusCode = 503,
          ),
        ]

      val views = ProxySnapshot.buildPathViews(emptyList(), scrapes.byPath())

      views.single().apply {
        path shouldBe "orphan_metrics"
        isDeparted.shouldBeTrue()
        agentIds.shouldBeEmpty()
        // The departed agent is the only attribution left, and it is what an operator needs -- by the
        // name the agent list used for it, not the id, which is why ScrapeRecord captures the name.
        servingAgent shouldBe "warehouse-01"
        lastScrape?.statusCode shouldBe 503
      }
    }

    "a still-registered path should never be marked departed, however many scrapes it has" {
      val views =
        ProxySnapshot.buildPathViews(
          [DashboardFixtures.pathView(path = "app_metrics")],
          [DashboardFixtures.scrapeRecord(path = "app_metrics")].byPath(),
        )

      views.single().isDeparted.shouldBeFalse()
    }

    // Interleaved rather than appended: a departed path is not a second-class row, and an operator
    // scanning alphabetically should find it where they expect.
    "live and departed paths should be sorted together as one list" {
      val registered = [DashboardFixtures.pathView(path = "b_live"), DashboardFixtures.pathView(path = "d_live")]
      val scrapes =
        [DashboardFixtures.scrapeRecord(path = "c_gone"), DashboardFixtures.scrapeRecord(path = "a_gone")]

      val views = ProxySnapshot.buildPathViews(registered, scrapes.byPath())

      views.map { it.path } shouldBe ["a_gone", "b_live", "c_gone", "d_live"]
      views.filter { it.isDeparted }.map { it.path } shouldBe ["a_gone", "c_gone"]
    }

    // ==================== buildPathViews: history ====================

    "a registered path should carry its whole history, newest first" {
      val history =
        [
          DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 503),
          DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 200),
          DashboardFixtures.scrapeRecord(path = "app_metrics", statusCode = 200),
        ]

      val view =
        ProxySnapshot.buildPathViews([DashboardFixtures.pathView(path = "app_metrics")], history.byPath()).single()

      view.history shouldBe history
      view.lastScrape shouldBe history.first()
    }

    // The history strip is most useful on a gone row: it shows whether the target was failing before its agent left.
    "a gone path should carry its history too" {
      val history =
        [
          DashboardFixtures.scrapeRecord(path = "orphan_metrics", statusCode = 503),
          DashboardFixtures.scrapeRecord(path = "orphan_metrics", statusCode = 200),
        ]

      val view = ProxySnapshot.buildPathViews(emptyList(), history.byPath()).single()

      view.isDeparted.shouldBeTrue()
      view.history shouldBe history
    }

    // ==================== buildPathViews: departures ====================

    // Follow-up #5: gone rows came only from scrape history, so a path whose agent left before Prometheus ever scraped
    // it vanished without a trace. Its departure is enough for a row.
    "a path that departed before its first scrape should appear as gone, with no history" {
      val departure =
        DashboardFixtures.departure(
          agentName = "warehouse-01",
          identity = "team-a",
          targetUrl = "http://warehouse/metrics",
          pathSource = "DISCOVERED",
        )

      val view = ProxySnapshot.buildPathViews(emptyList(), departed("never_scraped" to departure)).single()

      view.apply {
        path shouldBe "never_scraped"
        isDeparted.shouldBeTrue()
        agentIds.shouldBeEmpty()
        history.shouldBeEmpty()
        lastScrape.shouldBeNull()
        servingAgent shouldBe "warehouse-01"
        identity shouldBe "team-a"
        targetUrl shouldBe "http://warehouse/metrics"
        pathSource shouldBe "DISCOVERED"
      }
    }

    // The departure is what the path's entry showed as its last agent left, which on a consolidated path is that agent;
    // the newest scrape may be another agent's leg.
    "a gone path with a departure and scrapes should take its attribution from the departure, and keep its history" {
      val history =
        [
          DashboardFixtures.scrapeRecord(path = "orphan_metrics", agentName = "other-02", identity = "team-a"),
          DashboardFixtures.scrapeRecord(path = "orphan_metrics", statusCode = 503),
        ]
      val departure =
        DashboardFixtures.departure(agentName = "last-01", identity = "team-a", targetUrl = "http://last/metrics")
      val snapshot = history.byPath().copy(departures = mapOf("orphan_metrics" to departure))

      val view = ProxySnapshot.buildPathViews(emptyList(), snapshot).single()

      view.isDeparted.shouldBeTrue()
      view.servingAgent shouldBe "last-01"
      view.identity shouldBe "team-a"
      view.targetUrl shouldBe "http://last/metrics"
      view.history shouldBe history
    }

    // Follow-up #4: a departed row showed no identity, so a search for a team's identity missed its gone paths. When
    // the proxy has kept only a path's scrapes, the last one carries it, as it carries the agent's name.
    "a gone path with only scrapes should take its agent and identity from its last scrape, and have no target" {
      val scrapes =
        [
          DashboardFixtures.scrapeRecord(path = "orphan_metrics", agentName = "newest-01", identity = "team-b"),
          DashboardFixtures.scrapeRecord(path = "orphan_metrics", agentName = "older-01", identity = "team-c"),
        ]

      val view = ProxySnapshot.buildPathViews(emptyList(), scrapes.byPath()).single()

      view.servingAgent shouldBe "newest-01"
      view.identity shouldBe "team-b"
      view.targetUrl shouldBe ""
      view.agentNames.shouldBeEmpty()
    }

    // A departure from an agent that reported no name names no one; the last scrape's name is better than a blank.
    "a departure with no agent name should leave the name to the last scrape" {
      val snapshot =
        [DashboardFixtures.scrapeRecord(path = "orphan_metrics", agentName = "scraped-01")]
          .byPath()
          .copy(departures = mapOf("orphan_metrics" to DashboardFixtures.departure(agentName = "")))

      ProxySnapshot.buildPathViews(emptyList(), snapshot).single().servingAgent shouldBe "scraped-01"
    }

    // The dashboard reads the path map before the history, so a path can depart in between: it is still live in what
    // the path map said, and reads as gone only from the next refresh, never as both.
    "a departure for a path the path map still holds should add no gone row" {
      val views =
        ProxySnapshot.buildPathViews(
          [DashboardFixtures.pathView(path = "app_metrics", targetUrl = "http://app/metrics")],
          departed("app_metrics" to DashboardFixtures.departure()),
        )

      views.single().isDeparted.shouldBeFalse()
      views.single().targetUrl shouldBe "http://app/metrics"
    }

    "departed-only, scraped-only, and live paths should be sorted together as one list" {
      val snapshot =
        [DashboardFixtures.scrapeRecord(path = "c_scraped")]
          .byPath()
          .copy(departures = mapOf("a_departed" to DashboardFixtures.departure()))

      val views = ProxySnapshot.buildPathViews([DashboardFixtures.pathView(path = "b_live")], snapshot)

      views.map { it.path } shouldBe ["a_departed", "b_live", "c_scraped"]
      views.map { it.isDeparted } shouldBe [true, false, true]
    }

    // ==================== An agent's scrapes ====================

    // The detail's Recent scrapes come from every path's history, live or gone: a consolidated path's holds each of its
    // agents' scrapes, and an agent keeps its scrapes of a path it no longer serves.
    "an agent's scrapes should be its latest across every path's history, newest first" {
      val shared =
        DashboardFixtures.pathView(
          path = "shared_metrics",
          agentIds = ["1", "2"],
          history = [
            scrape(
            "2",
            "shared_metrics",
            5,
          ), scrape("1", "shared_metrics", 4), scrape("1", "shared_metrics", 1),
          ],
        )
      val own = DashboardFixtures.pathView(path = "own_metrics", history = [scrape("1", "own_metrics", 3)])
      val gone = DashboardFixtures.departedPathView(path = "gone_metrics", lastScrape = scrape("1", "gone_metrics", 2))
      val snapshot =
        DashboardFixtures.snapshot(
          agents =
            [
              DashboardFixtures.agentView(agentId = "1", paths = [own, shared]),
              DashboardFixtures.agentView(agentId = "2", agentName = "team-b-01", paths = [shared]),
            ],
          paths = [gone, own, shared],
        )

      snapshot.scrapesOf("1").map { it.path to it.at } shouldBe
        ["shared_metrics" to at(4), "own_metrics" to at(3), "gone_metrics" to at(2), "shared_metrics" to at(1)]
      snapshot.scrapesOf("2").map { it.path to it.at } shouldBe ["shared_metrics" to at(5)]
    }

    "an agent's scrapes should stop at the cap, and an agent with none, or no longer connected, should have none" {
      val busy =
        DashboardFixtures.pathView(
          path = "busy_metrics",
          agentIds = ["1", "9"],
          history = (ProxySnapshot.MAX_DETAIL_SCRAPES + 5 downTo 1).map { scrape("1", "busy_metrics", it) } +
            scrape("9", "busy_metrics", 0),
        )
      val snapshot =
        DashboardFixtures.snapshot(
          agents =
            [
              DashboardFixtures.agentView(agentId = "1", paths = [busy]),
              DashboardFixtures.agentView(agentId = "2", agentName = "idle-01", paths = emptyList()),
            ],
          paths = [busy],
        )

      snapshot.scrapesOf("1").map { it.at } shouldBe (ProxySnapshot.MAX_DETAIL_SCRAPES + 5 downTo 6).map { at(it) }
      snapshot.scrapesOf("2").shouldBeEmpty()
      // Agent 9 has gone, so the snapshot keeps none of its scrapes; its detail says it is no longer connected.
      snapshot.scrapesOf("9").shouldBeEmpty()
    }

    // Every session showing the agent renders the same snapshot, so the walk over the histories runs once, at the first
    // read.
    "a snapshot should work out its agents' scrapes once, however many read them" {
      val snapshot =
        DashboardFixtures.snapshot(
          agents = [
            DashboardFixtures.agentView(
            paths = [DashboardFixtures.pathView(lastScrape = scrape("1", "a", 1))],
          ),
          ],
        )

      snapshot.scrapesOf("1") shouldBeSameInstanceAs snapshot.scrapesOf("1")
    }

    // ==================== collect ====================

    // The wiring the tests above can't see: a recorded scrape reaches its agent's scrapes through the path history, and
    // the activity log's change count arrives with its lines. The proxy is built and never started, so binds nothing.
    "a collect should read the proxy's host, agents' scrapes from the path history, and the activity log" {
      val proxy =
        Proxy(
          options = ProxyOptions(listOf("--dashboard")),
          inProcessServerName = "snapshot-test-${System.nanoTime()}",
          testMode = true,
        )
      val log = proxy.activityLog.shouldNotBeNull()
      val agent = AgentContext("10.0.0.1").also { proxy.agentContextManager.addAgentContext(it) }
      proxy.pathManager.addPath("orders_metrics", "{}", agent).shouldBeNull()
      proxy.recordScrape(DashboardFixtures.scrapeRecord(agentId = agent.agentId, path = "orders_metrics"))

      val first = ProxySnapshot.collect(proxy)
      first.hostName shouldBe hostInfo.hostName
      first.dashboardName shouldBe "Proxy: ${hostInfo.hostName}:${proxy.scrapePort}"
      first.scrapesOf(agent.agentId).map { it.path } shouldBe ["orders_metrics"]
      first.activity shouldBe log.entries()
      first.activityChangeCount shouldBe log.snapshot().changeCount

      log.record(ActivityKind.BAD_TOKEN, detail = "An unknown agent token")
      ProxySnapshot.collect(proxy).activityChangeCount shouldBe first.activityChangeCount + 1
    }
  }

  companion object {
    private val START = Instant.parse("2026-11-15T01:00:00Z")
  }
}
