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

package io.prometheus.proxy.dashboard

import io.prometheus.proxy.ScrapeRecord
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shared builders for the dashboard view models.
 *
 * `ProxyDashboardHtmlTest` and `ProxySnapshotTest` each grew their own near-identical `AgentView` builder, so
 * adding a field to the view model meant editing both. These are the single place to do that.
 *
 * Every parameter is defaulted to a plausible value, so a test names only the field it is actually
 * about — which is what makes the assertions readable.
 */
internal object DashboardFixtures {
  fun pathView(
    path: String = "app_metrics",
    agentIds: List<String> = ["1"],
    labels: String = "{}",
    targetUrl: String = "",
    pathSource: String = "",
    lastScrape: ScrapeRecord? = null,
    isDeparted: Boolean = false,
  ) = PathView(path, agentIds, labels, targetUrl, pathSource, lastScrape, isDeparted)

  fun agentView(
    agentId: String = "1",
    agentName: String = "team-a-01",
    inactivity: Duration = 2.seconds,
    paths: List<PathView> = [pathView()],
    proxyEndpoints: List<String> = emptyList(),
    currentEndpointIndex: Int = 0,
  ) = AgentView(
    agentId = agentId,
    agentName = agentName,
    hostName = "worker-3",
    remoteAddr = "10.0.1.14:5555",
    launchId = "launch-abc",
    consolidated = false,
    isValid = true,
    connectTime = Instant.now().minusSeconds(120),
    inactivity = inactivity,
    backlogSize = 0,
    paths = paths,
    proxyEndpoints = proxyEndpoints,
    currentEndpointIndex = currentEndpointIndex,
  )

  fun scrapeRecord(
    agentId: String = "1",
    path: String = "app_metrics",
    statusCode: Int = 200,
    outcome: String = "success",
    durationMillis: Long = 41,
    contentLength: Int = 1800,
    at: Instant = Instant.now(),
  ) = ScrapeRecord(agentId, path, statusCode, outcome, durationMillis, contentLength, at)

  /**
   * A snapshot whose `paths` defaults to the agents' own paths, matching what
   * [ProxySnapshot.collect] produces for a proxy with no departed paths.
   */
  fun snapshot(
    agents: List<AgentView> = [agentView()],
    scrapes: List<ScrapeRecord> = emptyList(),
    paths: List<PathView> = agents.flatMap { it.paths }.distinct(),
  ) = ProxySnapshot(
    agents = agents,
    paths = paths,
    scrapes = scrapes,
    health = HealthView(agents.size, paths.size, 0, 25, 1, 25),
    maxAgentInactivitySecs = 60,
  )
}
