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

package io.prometheus.agent

import com.google.common.collect.EvictingQueue
import io.prometheus.common.AgentValues
import io.prometheus.common.ScrapeResults
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One scrape of a path as the agent saw it, for its dashboard: what the target answered, how long the fetch took, and
 * what the agent did with the body before sending it to the proxy, which sees only the result.
 *
 * Immutable, so a snapshot can hand it to any number of dashboard sessions without copying.
 *
 * @param statusCode the target's status, or the one the agent gave a fetch that got none: 408 for its own timeout, 503
 *   for a refused connection and the like
 * @param outcome how the scrape ended, as `agent_scrape_request_count_total` names it ("success", "unsuccessful",
 *   "timed_out", "failure")
 * @param durationMillis how long the agent's fetch took, retries included
 * @param bytes the body's size as the target sent it, before the metric filter, or 0 for a scrape that got none
 * @param filteredBytes its size after the filter, before gzip, the same as [bytes] without one
 * @param gzipped whether the agent gzipped the body for the proxy (`agent.minGzipSizeBytes`)
 * @param chunked whether it went to the proxy in chunks (`agent.chunkContentSizeKbs`)
 * @param failure why the scrape failed, cut and its control characters escaped, or empty
 * @param fromProxyDashboard whether the proxy's dashboard asked for it (its Scrape now) rather than Prometheus
 */
internal data class AgentScrapeRecord(
  val statusCode: Int,
  val outcome: String,
  val durationMillis: Long,
  val bytes: Long,
  val filteredBytes: Long,
  val gzipped: Boolean,
  val chunked: Boolean,
  val failure: String,
  val fromProxyDashboard: Boolean,
  val at: Instant = Clock.System.now(),
) {
  val isSuccess: Boolean get() = statusCode in 200..299

  companion object {
    // As much of a failure reason as the dashboard's scrape-now panel shows.
    private const val MAX_FAILURE_CHARS = 512

    /**
     * The record of a scrape that came to [results] after [duration], chunked as [ScrapeResults.isChunked] says for
     * [chunkContentSizeBytes], as the gRPC service sends it.
     */
    fun of(
      results: ScrapeResults,
      duration: Duration,
      chunkContentSizeBytes: Int,
    ) = AgentScrapeRecord(
      statusCode = results.srStatusCode,
      outcome = results.scrapeCounterMsg,
      durationMillis = duration.inWholeMilliseconds,
      bytes = results.rawSize.toLong(),
      filteredBytes = results.filteredSize.toLong(),
      gzipped = results.srZipped,
      chunked = results.isChunked(chunkContentSizeBytes),
      failure = AgentValues.shownValue(results.srFailureReason, MAX_FAILURE_CHARS),
      fromProxyDashboard = results.dashboardScrape,
    )
  }
}

/**
 * The last few scrapes of each of the agent's paths, for its dashboard's history strips and Recent scrapes: the agent
 * sees a scrape the proxy doesn't, its target's own status and timing, and what its filter, gzip, and chunking did.
 *
 * Kept only while the agent's dashboard is on, as the proxy keeps its `PathHistory`, so with it off nothing is kept
 * that nothing would read. Each path keeps a ring of [historySize] records.
 *
 * A path's ring isn't dropped as it unregisters: a reconnect unregisters every path, which would wipe the history at
 * the moment an operator came to read it. Bounded on every write instead: once there are more rings than [pathCount],
 * the paths the agent knows, and [maxGonePaths] more, the path scraped least recently goes, so a discovery file that
 * churns path names can't grow it without end. The dashboard shows only the paths the agent knows, so a gone path's
 * ring is never shown.
 *
 * Thread-safe: every access takes the rings map's own monitor, a leaf, which nothing else is called under.
 *
 * @param historySize records kept per path, `agent.dashboard.pathHistorySize`
 * @param pathCount how many paths the agent knows, configured, discovered, and registered, read only as a path is
 *   first recorded
 */
internal class AgentScrapeHistory(
  private val historySize: Int,
  private val pathCount: () -> Int,
  private val maxGonePaths: Int = MAX_GONE_PATHS,
) {
  // Each ring evicts its oldest record itself. Access-ordered, so the path scraped least recently is always first, and
  // record() evicts from the front without sorting.
  private val rings = LinkedHashMap<String, EvictingQueue<AgentScrapeRecord>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

  /** Adds one scrape of [path] to its ring, keyed as the path manager keys a path, without its leading slashes. */
  fun record(
    path: String,
    record: AgentScrapeRecord,
  ) {
    val key = AgentPathManager.requestedPathKey(path)
    synchronized(rings) {
      rings[key]?.also {
        it.add(record)
        return
      }
    }
    // A path's first scrape, the only time the map grows, so the only time the bound is read, before the monitor.
    val maxPaths = pathCount() + maxGonePaths
    synchronized(rings) {
      rings.getOrPut(key) { EvictingQueue.create(historySize) }.add(record)
      // The path just recorded is at the back, so it is never the one evicted.
      val leastRecent = rings.keys.iterator()
      while (rings.size > maxPaths) {
        val _ = leastRecent.next()
        leastRecent.remove()
      }
    }
  }

  /** Each path's scrapes, **newest first**, by its key. */
  fun snapshot(): Map<String, List<AgentScrapeRecord>> =
    synchronized(rings) { rings.entries.associate { (path, ring) -> path to ring.reversed() } }

  private companion object {
    // The gone paths kept beside the ones the agent knows, so a path that leaves and comes back keeps its history.
    const val MAX_GONE_PATHS = 100
    const val INITIAL_CAPACITY = 16
    const val LOAD_FACTOR = 0.75f
  }
}
