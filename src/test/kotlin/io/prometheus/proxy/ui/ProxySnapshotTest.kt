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

package io.prometheus.proxy.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class ProxySnapshotTest : StringSpec() {
  private fun agentView(inactivitySecs: Long) =
    AgentView(
      agentId = "1",
      agentName = "team-a-01",
      hostName = "worker-3",
      remoteAddr = "10.0.1.14:5555",
      launchId = "launch-abc",
      consolidated = false,
      isValid = true,
      connectTime = Instant.now(),
      inactivity = inactivitySecs.seconds,
      backlogSize = 0,
      paths = emptyList(),
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

    // A path whose agents have all gone is exactly the "why isn't this scraping?" case the UI exists
    // for, so it must be distinguishable from a healthy path rather than silently vanishing.
    "a path with no agents should report as orphaned" {
      PathView("dead_path", emptyList(), emptyList(), isConsolidated = false, labels = "{}")
        .isOrphaned
        .shouldBeTrue()
      PathView("live_path", ["1"], ["team-a-01"], isConsolidated = false, labels = "{}")
        .isOrphaned
        .shouldBeFalse()
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
          backlogThreshold = 25,
        )
      // The proxy's own health checks treat >= threshold as unhealthy, so at-threshold is not healthy.
      health.chunkContextHealthy.shouldBeTrue()
      health.scrapeMapHealthy.shouldBeFalse()
    }
  }
}
