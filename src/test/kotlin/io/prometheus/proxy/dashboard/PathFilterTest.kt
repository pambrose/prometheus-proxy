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
import io.prometheus.proxy.AgentAuthManager.Companion.LEGACY_IDENTITY_NAME
import io.prometheus.proxy.dashboard.DashboardFixtures.pathView
import io.prometheus.proxy.dashboard.DashboardFixtures.scrapeRecord

class PathFilterTest : StringSpec() {
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
  ) = PathFilter.of(query, problemsOnly = false).matches(path)

  init {
    "no filter should match every path" {
      PathFilter.NONE.matches(payments).shouldBeTrue()
      PathFilter.NONE.isActive.shouldBeFalse()
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

    // A gone path has no agentNames; its row names the agent from its last scrape, so a search should find it there.
    "a query should match the agent a departed path names" {
      val gone =
        pathView(
          path = "orphan_metrics",
          agentIds = emptyList(),
          isDeparted = true,
          lastScrape = scrapeRecord(agentName = "warehouse-01", path = "orphan_metrics"),
        )

      matches("warehouse", gone).shouldBeTrue()
    }

    // The legacy token's identity reads as "shared token" on the page, so searching for what is shown has to work.
    "a query should match the legacy token by the label the page shows" {
      matches("shared token", pathView(identity = LEGACY_IDENTITY_NAME)).shouldBeTrue()
    }

    "problems only should keep a failing or departed path and drop a healthy or unscraped one" {
      val problems = PathFilter.of(null, problemsOnly = true)

      problems.matches(pathView(lastScrape = scrapeRecord(statusCode = 503))).shouldBeTrue()
      problems.matches(pathView(isDeparted = true, lastScrape = scrapeRecord())).shouldBeTrue()
      problems.matches(pathView(lastScrape = scrapeRecord(statusCode = 200))).shouldBeFalse()
      problems.matches(pathView()).shouldBeFalse()
    }

    "a query and problems only should both have to match" {
      val failingEast = payments.copy(history = [scrapeRecord(statusCode = 500)])
      val filter = PathFilter.of("east", problemsOnly = true)

      filter.matches(failingEast).shouldBeTrue()
      filter.matches(payments).shouldBeFalse()
      filter.matches(failingEast.copy(identity = "", agentNames = ["agent-west"])).shouldBeFalse()
    }

    // Browser input, on a port with no auth, matched against every row on every push.
    "a filter from browser input should be trimmed and cut to its maximum length" {
      PathFilter.of("  east  ", problemsOnly = false).query shouldBe "east"
      PathFilter.of(null, problemsOnly = false) shouldBe PathFilter.NONE
      PathFilter.of("x".repeat(500), problemsOnly = false).query.length shouldBe PathFilter.MAX_QUERY_LENGTH
    }

    "a filter should be active once it could hide a row" {
      PathFilter.of("east", problemsOnly = false).isActive.shouldBeTrue()
      PathFilter.of("", problemsOnly = true).isActive.shouldBeTrue()
      PathFilter.of("   ", problemsOnly = false).isActive.shouldBeFalse()
    }

    "an identity should read as the page shows it" {
      identityLabel("") shouldBe null
      identityLabel(LEGACY_IDENTITY_NAME) shouldBe "shared token"
      identityLabel("east") shouldBe "east"
    }
  }
}
