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

import io.ktor.http.HttpStatusCode
import io.prometheus.Proxy
import io.prometheus.proxy.ActivityKind
import io.prometheus.proxy.ProxyHttpRoutes
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ProxyPathManager.AgentContextInfo
import io.prometheus.proxy.ScrapeHeaders
import io.prometheus.proxy.ScrapeRequestResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The dashboard's scrape-now: one scrape of a path, made at once through the code a Prometheus scrape takes, so its
 * result is what Prometheus would get. A consolidated path is scraped from every agent and merged, as Prometheus sees
 * it.
 *
 * A scrape-now leaves no trace in what Prometheus reads: not in the scrape metrics, the path's History, or the agent's
 * recent scrapes, which stay a record of Prometheus's own scrapes. It goes in the activity feed instead, with the
 * address it came from, so a scrape no one at Prometheus asked for can be traced to a browser.
 *
 * Bounded, so clicks can't take the in-flight slots Prometheus's scrapes need: at most [MAX_CONCURRENT] run at once, a
 * path is scraped at most once every [COOLDOWN], and each waits at most [TIMEOUT], which the agent is told too.
 *
 * @param wait how long a scrape-now waits for its agents before it gives up on them
 * @param scrape how a path's agents are scraped; the real scrape unless a test stands in for it
 */
internal class ScrapeNow(
  private val proxy: Proxy,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val wait: Duration = TIMEOUT + TIMEOUT_GRACE,
  private val scrape: suspend (AgentContextInfo, String) -> List<ScrapeRequestResponse> = { info, path ->
    scrapeAgents(proxy, info, path)
  },
) {
  private val permits = Semaphore(MAX_CONCURRENT)

  // When each path was last scraped from the dashboard. Access-ordered and capped, so the least recently scraped path
  // goes once there are more than MAX_REMEMBERED_PATHS; one that old is long past its cooldown. Guarded by its monitor.
  private val lastScrapes =
    object : LinkedHashMap<String, TimeMark>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimeMark>) = size > MAX_REMEMBERED_PATHS
    }

  /** What a scrape-now came to: a [Done] scrape, or a request [Refused] before any scrape, and why. */
  sealed interface Result {
    val status: HttpStatusCode
  }

  data class Done(
    val summary: ScrapeSummary,
  ) : Result {
    override val status: HttpStatusCode get() = HttpStatusCode.OK
  }

  data class Refused(
    override val status: HttpStatusCode,
    val reason: String,
  ) : Result

  /** Scrapes [path] for a browser at [client], unless the path or the limits refuse it. */
  suspend fun scrape(
    path: String,
    client: String,
  ): Result {
    val key = ProxyPathManager.pathKey(path)
    val shown = ProxyPathManager.shownPath(key)
    val info = if (proxy.isRunning) proxy.pathManager.getAgentContextInfo(key) else null
    return when {
      !proxy.isRunning -> Refused(HttpStatusCode.ServiceUnavailable, "The proxy isn't running")
      info == null -> Refused(HttpStatusCode.NotFound, "/$shown isn't registered")
      info.isNotValid() -> Refused(HttpStatusCode.ServiceUnavailable, "/$shown has no agent connected")
      !permits.tryAcquire() -> Refused(HttpStatusCode.TooManyRequests, "$MAX_CONCURRENT dashboard scrapes are running")
      else -> scrapeWithin(Request(info, key, client))
    }
  }

  // One scrape-now under way: the path's agents, its key, and the browser that asked for it.
  private class Request(
    val info: AgentContextInfo,
    val key: String,
    val client: String,
  )

  // Holds one of the permits, which it gives back.
  private suspend fun scrapeWithin(request: Request): Result =
    try {
      if (claimCooldown(request.key)) {
        val responses = withTimeoutOrNull(wait) { scrape(request.info, request.key) } ?: [timedOut()]
        Done(ScrapeSummary.of(request.key, responses)).also { record(request, it.summary) }
      } else {
        val shown = ProxyPathManager.shownPath(request.key)
        Refused(HttpStatusCode.TooManyRequests, "/$shown was scraped from the dashboard less than $COOLDOWN ago")
      }
    } finally {
      permits.release()
    }

  // Whether [key] may be scraped now, marking it scraped when it may.
  private fun claimCooldown(key: String): Boolean =
    synchronized(lastScrapes) {
      val last = lastScrapes[key]
      if (last != null && last.elapsedNow() < COOLDOWN)
        return false
      lastScrapes[key] = timeSource.markNow()
      true
    }

  private fun record(
    request: Request,
    summary: ScrapeSummary,
  ) {
    val ms = summary.duration.inWholeMilliseconds
    proxy.activityLog?.record(
      ActivityKind.SCRAPED_NOW,
      request.info.agentContexts.firstOrNull(),
      request.key,
      "${summary.statusCode.value} ${summary.outcome} in $ms ms, from ${request.client}",
      group = request.client,
    )
  }

  // The answer for an agent that didn't answer at all, which the agent's own timeout would otherwise have given.
  private fun timedOut() =
    ScrapeRequestResponse(
      statusCode = HttpStatusCode.GatewayTimeout,
      updateMsg = ProxyHttpRoutes.PROXY_TIMEOUT_LABEL,
      failureReason = "No answer from the agent within $wait",
      fetchDuration = wait,
    )

  companion object {
    const val MAX_CONCURRENT = 4
    val COOLDOWN = 5.seconds

    // How long a scrape-now waits for its target, which the agent is told; the grace covers the agent's answer.
    private val TIMEOUT = 10.seconds
    private val TIMEOUT_GRACE = 5.seconds

    // Text, whatever Prometheus negotiates, since the panel shows the body.
    private val HEADERS = ScrapeHeaders(accept = "text/plain", scrapeTimeout = TIMEOUT)

    private const val MAX_REMEMBERED_PATHS = 1000
    private const val INITIAL_CAPACITY = 16
    private const val LOAD_FACTOR = 0.75f

    // Every agent of the path, at once, as the scrape route asks them.
    private suspend fun scrapeAgents(
      proxy: Proxy,
      info: AgentContextInfo,
      path: String,
    ): List<ScrapeRequestResponse> =
      coroutineScope {
        info.agentContexts
          .map { agent -> async { ProxyHttpRoutes.submitScrapeRequest(agent, proxy, path, "", HEADERS) } }
          .awaitAll()
      }
  }
}
