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

package io.prometheus.agent.dashboard

import com.pambrose.common.util.unzip
import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.HttpStatusCode
import io.prometheus.Agent
import io.prometheus.agent.AgentPathManager
import io.prometheus.agent.filter.MetricFilter
import io.prometheus.common.AgentValues
import io.prometheus.common.KeyedThrottle
import io.prometheus.common.ScrapeResults
import io.prometheus.common.dashboard.RawScrape
import io.prometheus.common.dashboard.ScrapeNowResult
import io.prometheus.common.dashboard.ScrapeNowResult.Done
import io.prometheus.common.dashboard.ScrapeNowResult.Refused
import io.prometheus.common.dashboard.ScrapeSummary
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The agent dashboard's Scrape now: one scrape of a path's target, made at once from the agent, through the code a
 * scrape the proxy asks for takes, so its result is what Prometheus would get: after the path's metric filter, with its
 * size before the filter too. It works whether or not the proxy has the path, so the target of a path the proxy refused
 * can still be checked.
 *
 * Only a path the agent has configured, discovered, or registered is scraped, at the URL the agent has for it: a
 * browser names a path, never a URL, so it can't point the agent at an address of its choosing.
 *
 * A Scrape now leaves no trace in what the agent reports: not in its `agent_scrape_*` metrics,
 * `agent_dashboard_scrapes_total`, the filter's counts, or the path's Recent scrapes, which stay a record of the
 * scrapes the proxy asked for (see `AgentHttpService.fetchForDashboard`). It is logged instead, with the address it
 * came from.
 *
 * Bounded, as the proxy's is, so clicks can't crowd out the scrapes the proxy asks for: at most [MAX_CONCURRENT] run at
 * once, a path is scraped at most once every [COOLDOWN], and the target is waited on for at most [TIMEOUT].
 *
 * @param fetch how a path's target is scraped, given its URL and filter; the agent's own fetch unless a test stands in
 */
internal class AgentScrapeNow(
  private val agent: Agent,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val fetch: suspend (String, MetricFilter?) -> ScrapeResults = { url, filter ->
    agent.agentHttpService.fetchForDashboard(url, filter, TIMEOUT)
  },
) {
  private val permits = Semaphore(MAX_CONCURRENT)

  // Which paths may be scraped from the dashboard now: each at most once every COOLDOWN. It remembers the
  // MAX_REMEMBERED_PATHS paths asked about most recently; one asked about less recently is long past its cooldown.
  private val cooldowns = KeyedThrottle<String>(COOLDOWN, MAX_REMEMBERED_PATHS, timeSource)

  /** Scrapes [path] for a browser at [client], unless the agent doesn't know the path or the limits refuse it. */
  suspend fun scrape(
    path: String,
    client: String,
  ): ScrapeNowResult {
    val key = AgentPathManager.requestedPathKey(path)
    val url = urlOf(key)
    return when {
      url == null -> {
        Refused(HttpStatusCode.NotFound, "/${AgentValues.shownValue(key)} isn't configured, discovered, or registered")
      }

      !permits.tryAcquire() -> {
        Refused(HttpStatusCode.TooManyRequests, "$MAX_CONCURRENT dashboard scrapes are running")
      }

      else -> {
        scrapeWithin(key, url, client)
      }
    }
  }

  // The URL the agent has for [key]: its registration's, which is the one the proxy's scrapes reach, or else its
  // config entry's or its discovery entry's. Null for a path the agent doesn't know.
  private fun urlOf(key: String): String? =
    agent.pathManager[key]?.url
      ?: agent.pathManager.pathConfigs.firstOrNull { it.path.removePrefix("/") == key }?.url
      ?: agent.pathDiscoveryService?.discoveredEntries?.firstOrNull { it.path.removePrefix("/") == key }?.url

  // Holds one of the permits, which it gives back.
  private suspend fun scrapeWithin(
    key: String,
    url: String,
    client: String,
  ): ScrapeNowResult =
    try {
      if (cooldowns.tryAcquire(key)) {
        val started = timeSource.markNow()
        val results = fetch(url, agent.pathManager.filtersByPath[key])
        Done(summaryOf(key, results, started.elapsedNow())).also { logScrape(it.summary, client) }
      } else {
        Refused(HttpStatusCode.TooManyRequests, "/$key was scraped from the dashboard less than $COOLDOWN ago")
      }
    } finally {
      permits.release()
    }

  private fun logScrape(
    summary: ScrapeSummary,
    client: String,
  ) {
    logger.info {
      "Dashboard scrape of /${AgentValues.shownValue(summary.path)} from $client: " +
        "${summary.statusCode.value} ${summary.outcome} in ${summary.durationMillis} ms"
    }
  }

  companion object {
    private val logger = logger {}

    const val MAX_CONCURRENT = 4
    val COOLDOWN = 5.seconds

    // How long a Scrape now waits for its target, which the target is told, as the proxy's tells the agent.
    private val TIMEOUT = 10.seconds

    private const val MAX_REMEMBERED_PATHS = 1000

    // What a scrape of [path] that came to [results] after [duration] found, as the result panel shows it: the body
    // after the path's filter, unzipped, and its size before the filter and after.
    internal fun summaryOf(
      path: String,
      results: ScrapeResults,
      duration: Duration,
    ) = ScrapeSummary.of(
      RawScrape(
        path = path,
        statusCode = HttpStatusCode.fromValue(results.srStatusCode),
        outcome = results.scrapeCounterMsg,
        duration = duration,
        bytes = results.rawSize.toLong(),
        contentType = results.srContentType.substringBefore(';').trim(),
        text = if (results.srZipped) results.srContentAsZipped.unzip() else results.srContentAsText,
        failure = results.srFailureReason,
        filteredBytes = results.filteredSize.toLong(),
      ),
    )
  }
}
