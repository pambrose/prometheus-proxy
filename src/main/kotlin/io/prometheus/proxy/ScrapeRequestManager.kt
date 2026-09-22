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

package io.prometheus.proxy

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.prometheus.common.ScrapeResults
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.minusAssign
import kotlin.concurrent.atomics.plusAssign

/**
 * Tracks in-flight scrape requests and assigns results when responses arrive.
 *
 * Maintains a concurrent map of scrape ID to [ScrapeRequestWrapper]. When a scrape
 * response (or chunked summary) arrives, the corresponding wrapper is located, populated
 * with [ScrapeResults][io.prometheus.common.ScrapeResults], and marked complete so the
 * waiting HTTP handler can return. Also supports failing individual or bulk requests
 * (e.g., on agent disconnect or chunk validation failure).
 *
 * @see ScrapeRequestWrapper
 * @see ProxyServiceImpl
 */
internal class ScrapeRequestManager {
  // Map scrape_id to ScrapeRequestWrapper.
  //
  // The View suffix names the *external* contract: callers outside this class get a read-only Map.
  // Inside the class the same name resolves to the ConcurrentHashMap backing field, so the put/remove
  // calls below really are mutating the map itself -- not a copy, and not a violation of the read-only
  // exposure the name promises everyone else.
  val scrapeRequestMapView: Map<Long, ScrapeRequestWrapper>
    field = ConcurrentHashMap<Long, ScrapeRequestWrapper>()

  fun containsScrapeRequest(scrapeId: Long): Boolean = scrapeRequestMapView.containsKey(scrapeId)

  // The agent the scrape was sent to, or null when the scrape is no longer tracked. The response RPCs
  // compare this with the connection's agent so one agent can't answer another agent's scrapes.
  fun ownerAgentId(scrapeId: Long): String? = scrapeRequestMapView[scrapeId]?.agentContext?.agentId

  val scrapeMapSize: Int
    get() = scrapeRequestMapView.size

  // Scrape requests in flight across all agents. Claimed atomically before a request is added, so concurrent
  // submissions cannot overshoot the limit the way a size check followed by a put could.
  private val inFlightCount = AtomicInt(0)

  fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper): ScrapeRequestWrapper? {
    val scrapeId = scrapeRequest.scrapeId
    logger.debug { "Adding scrapeId: $scrapeId to scrapeRequestMap" }
    return scrapeRequestMapView.put(scrapeId, scrapeRequest).also { previous ->
      if (previous == null)
        inFlightCount += 1
    }
  }

  fun assignScrapeResults(scrapeResults: ScrapeResults) {
    val scrapeId = scrapeResults.srScrapeId
    scrapeRequestMapView[scrapeId]
      ?.also { wrapper ->
        wrapper.complete(scrapeResults)
        wrapper.agentContext.markActivityTime(true)
      } ?: logger.warn { "Missing ScrapeRequestWrapper for scrape_id: $scrapeId (likely timed out)" }
  }

  // Completes a request the proxy fails itself, rather than the agent answering it; [failure] gives its outcome label
  // and status.
  fun failScrapeRequest(
    scrapeId: Long,
    failureReason: String,
    failure: ProxyFailure,
  ) {
    scrapeRequestMapView[scrapeId]
      ?.also { wrapper ->
        wrapper.complete(
          ScrapeResults(
            srAgentId = wrapper.agentContext.agentId,
            srScrapeId = scrapeId,
            srStatusCode = failure.statusCode.value,
            srFailureReason = failureReason,
          ),
          failure,
        )
        wrapper.agentContext.markActivityTime(true)
      } ?: logger.warn { "failScrapeRequest() missing ScrapeRequestWrapper for scrape_id: $scrapeId" }
  }

  fun failAllScrapeRequests(
    agentId: String,
    failureReason: String,
    failure: ProxyFailure,
  ) {
    scrapeRequestMapView.values
      .filter { it.agentContext.agentId == agentId }
      .forEach { failScrapeRequest(it.scrapeId, failureReason, failure) }
  }

  fun failAllInFlightScrapeRequests(
    failureReason: String,
    failure: ProxyFailure,
  ) {
    scrapeRequestMapView.values.forEach { failScrapeRequest(it.scrapeId, failureReason, failure) }
  }

  /**
   * Adds [scrapeRequest] only while fewer than [maxInFlight] requests are in flight across all agents.
   *
   * Bounds how many scrapes the proxy works on at once across all agents. It is a concurrency limit, not a memory
   * bound: each in-flight scrape can buffer a response up to `maxZippedContentSizeMBytes` zipped and
   * `maxUnzippedContentSizeMBytes` unzipped. Returns false, without tracking the request, when the limit is reached.
   */
  fun tryAddToScrapeRequestMap(
    scrapeRequest: ScrapeRequestWrapper,
    maxInFlight: Int,
  ): Boolean {
    while (true) {
      val current = inFlightCount.load()
      if (current >= maxInFlight)
        return false
      if (inFlightCount.compareAndSet(current, current + 1))
        break
    }
    val scrapeId = scrapeRequest.scrapeId
    logger.debug { "Adding scrapeId: $scrapeId to scrapeRequestMap" }
    // A replaced entry did not take a new slot, so give back the one just claimed.
    if (scrapeRequestMapView.put(scrapeId, scrapeRequest) != null)
      inFlightCount -= 1
    return true
  }

  fun removeFromScrapeRequestMap(scrapeId: Long): ScrapeRequestWrapper? {
    logger.debug { "Removing scrapeId: $scrapeId from scrapeRequestMap" }
    return scrapeRequestMapView.remove(scrapeId)?.also { inFlightCount -= 1 }
  }

  companion object {
    private val logger = logger {}
  }
}
