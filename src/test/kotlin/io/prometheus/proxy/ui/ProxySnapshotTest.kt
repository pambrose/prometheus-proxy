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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class ProxySnapshotTest : StringSpec() {
  private fun agentView(
    inactivitySecs: Long,
    endpoints: List<String> = emptyList(),
    endpointIndex: Int = 0,
  ) = AgentView(
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
  }
}
