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
import io.kotest.matchers.shouldBe
import io.ktor.http.parametersOf
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.AgentAuthManager.Companion.LEGACY_IDENTITY_NAME
import io.prometheus.proxy.PathHistory
import io.prometheus.proxy.dashboard.DashboardFixtures.activityEntry
import io.prometheus.proxy.dashboard.DashboardFixtures.departedPathView
import io.prometheus.proxy.dashboard.DashboardFixtures.departure
import io.prometheus.proxy.dashboard.DashboardFixtures.pathView
import io.prometheus.proxy.dashboard.DashboardFixtures.scrapeRecord

class RowFilterTest : StringSpec() {
  private val payments =
    pathView(
      path = "payments_metrics",
      agentIds = ["3"],
      agentNames = ["agent-east"],
      targetUrl = "http://demo-app:8000/payments/metrics",
      identity = "east",
    )

  private fun matches(
    query: String,
    path: PathView = payments,
  ) = RowFilter.of(query, problemsOnly = false).matches(path)

  init {
    "no filter should match every path" {
      RowFilter.NONE.matches(payments).shouldBeTrue()
      RowFilter.NONE.isActive.shouldBeFalse()
    }

    "a query should match the path, an agent's name, the target URL, or the identity, ignoring case" {
      matches("PAYMENTS").shouldBeTrue()
      matches("agent-east").shouldBeTrue()
      matches("demo-app:8000").shouldBeTrue()
      matches("east").shouldBeTrue()
      matches("west").shouldBeFalse()
    }

    // A consolidated path lists every agent in agentNames, though its row names only the first.
    "a query should match any agent on a consolidated path" {
      matches("agent-west-2", pathView(agentIds = ["1", "2"], agentNames = ["agent-west-1", "agent-west-2"]))
        .shouldBeTrue()
    }

    // A gone path the proxy kept only the scrapes of has no agentNames; its row names the agent from its last scrape,
    // so a search should find it there.
    "a query should match the agent a departed path names" {
      val gone = departedPathView(lastScrape = scrapeRecord(agentName = "warehouse-01", path = "orphan_metrics"))

      matches("warehouse", gone).shouldBeTrue()
    }

    // Follow-up #4: a gone path showed no identity, so a search for a team's identity missed its gone paths, the
    // problem rows that matter most. Built the way the dashboard builds them, from a departure and from scrapes alone.
    "a query should match a departed path's identity, from its departure or its last scrape" {
      val departed =
        PathHistory.Snapshot(
          scrapes = mapOf("scraped_metrics" to [scrapeRecord(path = "scraped_metrics", identity = "team-a")]),
          departures = mapOf("never_scraped" to departure(identity = "team-a")),
        )
      val gone = ProxySnapshot.buildPathViews(emptyList(), departed)

      gone.map { it.path } shouldBe ["never_scraped", "scraped_metrics"]
      gone.all { RowFilter.of("TEAM-A", problemsOnly = true).matches(it) }.shouldBeTrue()
      gone.none { matches("team-b", it) }.shouldBeTrue()
    }

    // The legacy token's identity reads as "shared token" on the page, so a search finds what is shown, not what isn't.
    "a query should match the legacy token by the label the page shows, not its internal name" {
      matches("shared token", pathView(identity = LEGACY_IDENTITY_NAME)).shouldBeTrue()
      matches("legacy", pathView(identity = LEGACY_IDENTITY_NAME)).shouldBeFalse()
    }

    "problems only should keep a failing or departed path and drop a healthy or unscraped one" {
      val problems = RowFilter.of(null, problemsOnly = true)

      problems.matches(pathView(lastScrape = scrapeRecord(statusCode = 503))).shouldBeTrue()
      problems.matches(pathView(isDeparted = true, lastScrape = scrapeRecord())).shouldBeTrue()
      problems.matches(pathView(lastScrape = scrapeRecord(statusCode = 200))).shouldBeFalse()
      problems.matches(pathView()).shouldBeFalse()
    }

    "a query and problems only should both have to match" {
      val failingEast = payments.copy(history = [scrapeRecord(statusCode = 500)])
      val filter = RowFilter.of("east", problemsOnly = true)

      filter.matches(failingEast).shouldBeTrue()
      filter.matches(payments).shouldBeFalse()
      filter.matches(failingEast.copy(identity = "", agentNames = ["agent-west"])).shouldBeFalse()
    }

    // Browser input, on a port with no auth, matched against every row on every push.
    "a filter from browser input should be trimmed and cut to its maximum length" {
      RowFilter.of("  east  ", problemsOnly = false).query shouldBe "east"
      RowFilter.of(null, problemsOnly = false) shouldBe RowFilter.NONE
      RowFilter.of("x".repeat(500), problemsOnly = false).query.length shouldBe RowFilter.MAX_QUERY_LENGTH
      // Never between the halves of a surrogate pair, which the page would echo as a broken character.
      val emoji = "x".repeat(RowFilter.MAX_QUERY_LENGTH - 1) + "\uD83D\uDE00"
      RowFilter.of(emoji, problemsOnly = false).query shouldBe "x".repeat(RowFilter.MAX_QUERY_LENGTH - 1)
    }

    // The /paths route reads the URL's query, and the socket the same query from the page.
    "a filter from a URL's query should read the search and the problems box as the form submits them" {
      RowFilter.of(parametersOf("q" to ["east"], "problems" to ["1"])) shouldBe RowFilter("east", problemsOnly = true)
      RowFilter.of(parametersOf("problems" to ["on"])) shouldBe RowFilter.NONE
      RowFilter.of(parametersOf()) shouldBe RowFilter.NONE
    }

    "a filter should be active once it could hide a row" {
      RowFilter.of("east", problemsOnly = false).isActive.shouldBeTrue()
      RowFilter.of("", problemsOnly = true).isActive.shouldBeTrue()
      RowFilter.of("   ", problemsOnly = false).isActive.shouldBeFalse()
    }

    // ==================== Activity lines ====================

    "a query should match an activity line's event, agent, identity, paths, or detail, ignoring case" {
      val line =
        activityEntry(
          ActivityKind.PATH_REJECTED,
          agentName = "agent-east",
          identity = LEGACY_IDENTITY_NAME,
          paths = ["team_b_metrics", "team_c_metrics"],
          detail = "Agent identity 'east' is not authorized",
        )

      fun matches(query: String) = RowFilter.of(query, problemsOnly = false).matches(line)

      matches("REJECTED").shouldBeTrue()
      matches("agent-east").shouldBeTrue()
      matches("shared token").shouldBeTrue()
      matches("team_c").shouldBeTrue()
      matches("not authorized").shouldBeTrue()
      matches("west").shouldBeFalse()
    }

    "problems only should keep the activity an operator has to act on" {
      val problems = RowFilter.of(null, problemsOnly = true)

      problems.matches(activityEntry(ActivityKind.BAD_TOKEN)).shouldBeTrue()
      problems.matches(activityEntry(ActivityKind.AGENT_EVICTED)).shouldBeTrue()
      problems.matches(activityEntry(ActivityKind.AGENT_DISCONNECTED)).shouldBeFalse()
      problems.matches(activityEntry(ActivityKind.PATH_TAKEN_OVER)).shouldBeFalse()
    }

    "an identity should read as the page shows it" {
      identityLabel("") shouldBe null
      identityLabel(LEGACY_IDENTITY_NAME) shouldBe "shared token"
      identityLabel("east") shouldBe "east"
    }
  }
}
