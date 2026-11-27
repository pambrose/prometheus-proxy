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

import io.prometheus.Proxy
import io.prometheus.common.TestPorts
import io.prometheus.proxy.ActivityEntry
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.PathHistory
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ScrapeRecord
import io.prometheus.proxy.ScrapeRequestResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import io.prometheus.common.dashboard.RowFilter

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
  /** The proxy's host in [snapshot], which the bar and the tab's title name with the scrape port. */
  const val PROXY_HOST = "proxy-host"

  /** The dashboard's name for [snapshot], as the bar and the tab's title give it. */
  val PROXY_NAME = "Proxy: $PROXY_HOST:${TestPorts.PROXY_HTTP_PORT}"

  /** [labels], a path's labels as an agent sends them, as the proxy keeps them once the path registers. */
  fun labelsOf(labels: String): JsonObject = Proxy.pathLabels(labels, reserveJobAndInstance = false).kept

  // lastScrape is shorthand for a one-record history, since a path view's last scrape is its history's newest. The
  // labels are given as an agent sends them, and kept as the proxy keeps them (see labelsOf).
  fun pathView(
    path: String = "app_metrics",
    agentIds: List<String> = ["1"],
    labels: String = "{}",
    targetUrl: String = "",
    pathSource: String = "",
    lastScrape: ScrapeRecord? = null,
    isDeparted: Boolean = false,
    agentNames: List<String> = agentIds.map { "agent-$it" },
    history: List<ScrapeRecord> = listOfNotNull(lastScrape),
    identity: String = "",
  ) = PathView(path, agentIds, labelsOf(labels), targetUrl, pathSource, isDeparted, agentNames, history, identity)

  /**
   * A path whose agent has gone, as [ProxySnapshot.buildPathViews] builds one: no agent ids, and named by its
   * departure's [agentName] when given, otherwise only by its last scrape. A null [lastScrape] is a path that left
   * before Prometheus scraped it.
   */
  fun departedPathView(
    path: String = "orphan_metrics",
    lastScrape: ScrapeRecord? = scrapeRecord(path = path),
    agentName: String? = null,
    identity: String = "",
    targetUrl: String = "",
  ) = pathView(
    path = path,
    agentIds = emptyList(),
    targetUrl = targetUrl,
    lastScrape = lastScrape,
    isDeparted = true,
    agentNames = listOfNotNull(agentName),
    identity = identity,
  )

  /** What [PathHistory] keeps of a path as it leaves the path map. */
  fun departure(
    agentName: String = "agent-1",
    identity: String = "",
    targetUrl: String = "",
    pathSource: String = "",
    at: Instant = Clock.System.now(),
  ) = PathHistory.Departure(at, agentName, identity, targetUrl, pathSource)

  fun agentView(
    agentId: String = "1",
    agentName: String = "team-a-01",
    inactivity: Duration = 2.seconds,
    paths: List<PathView> = [pathView()],
    proxyEndpoints: List<String> = emptyList(),
    currentEndpointIndex: Int = 0,
    identity: String = "",
  ) = AgentView(
    agentId = agentId,
    agentName = agentName,
    hostName = "worker-3",
    remoteAddr = "10.0.1.14:5555",
    launchId = "launch-abc",
    consolidated = false,
    isValid = true,
    connectTime = Clock.System.now() - 120.seconds,
    inactivity = inactivity,
    backlogSize = 0,
    paths = paths,
    proxyEndpoints = proxyEndpoints,
    currentEndpointIndex = currentEndpointIndex,
    identity = identity,
  )

  fun scrapeRecord(
    agentId: String = "1",
    path: String = "app_metrics",
    statusCode: Int = 200,
    outcome: String = "success",
    durationMillis: Long = 41,
    contentLength: Int = 1800,
    at: Instant = Clock.System.now(),
    agentName: String = "agent-$agentId",
    identity: String = "",
  ) = ScrapeRecord(agentId, agentName, identity, path, statusCode, outcome, durationMillis, contentLength, at)

  /** An agent's successful answer to a scrape, with [text] as its plain body. */
  fun scrapeResponse(
    text: String = "# TYPE up gauge\nup 1\n",
    durationMillis: Long = 120,
  ) = ScrapeRequestResponse(
    statusCode = HttpStatusCode.OK,
    updateMsg = "success",
    contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
    contentText = text,
    fetchDuration = durationMillis.milliseconds,
    byteCount = text.length.toLong(),
    encoding = ProxyMetrics.ENCODING_PLAIN,
  )

  fun activityEntry(
    kind: ActivityKind = ActivityKind.PATH_REGISTERED,
    agentName: String = "agent-east",
    identity: String = "",
    paths: List<String> = ["orders_metrics"],
    count: Int = 1,
    detail: String = "",
    at: Instant = Clock.System.now(),
    agentId: String = "1",
    pathCount: Int = maxOf(count, paths.size),
  ) = ActivityEntry(at, kind, agentId, agentName, identity, paths, count, detail, pathCount = pathCount)

  /**
   * A snapshot whose `paths` defaults to the agents' own paths, matching what
   * [ProxySnapshot.collect] produces for a proxy with no departed paths. An agent's Recent scrapes come from the
   * paths' histories, as [ProxySnapshot.scrapesOf] reads them, so a test gives a path a `history` to fill them.
   */
  fun snapshot(
    agents: List<AgentView> = [agentView()],
    paths: List<PathView> = agents.flatMap { it.paths }.distinct(),
    authEnabled: Boolean = false,
    activity: List<ActivityEntry> = emptyList(),
    activityChangeCount: Long = 0L,
    scrapeNowEnabled: Boolean = false,
    links: List<PathLink> = emptyList(),
    evictAfterSecs: Long? = 60,
  ) = ProxySnapshot(
    agents = agents,
    paths = paths,
    health = HealthView(agents.size, paths.size, 0, 25, 1, 25),
    evictAfterSecs = evictAfterSecs,
    scrapePort = TestPorts.PROXY_HTTP_PORT,
    authEnabled = authEnabled,
    activity = activity,
    activityChangeCount = activityChangeCount,
    scrapeNowEnabled = scrapeNowEnabled,
    links = links,
    hostName = PROXY_HOST,
  )

  /** Links into Prometheus and Grafana for the path's job label, each shown as its tool's brand icon, as the demo's. */
  val iconLinks =
    PathLink.parseAll(
      [
        PathLink.Entry("Prometheus", "http://localhost:9090/graph?job={label.job}", icon = "prometheus"),
        PathLink.Entry("Grafana", "http://localhost:3000/d/demo?var-job={label.job}", icon = "grafana"),
      ],
    )

  /** The whole page for [snapshot], as the dashboard serves it to a GET. */
  fun renderFullPage(
    snapshot: ProxySnapshot,
    layout: DashboardLayout = DashboardLayout.AGENT,
    routeBase: String = "/dashboard",
    filter: RowFilter = RowFilter.NONE,
    loginEnabled: Boolean = false,
  ) = createHTML().html {
    with(ProxyDashboardHtml) { renderPage(snapshot, null, routeBase, layout, filter, loginEnabled) }
  }
}
