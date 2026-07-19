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
import io.ktor.http.HttpStatusCode
import io.prometheus.common.ScrapeResults
import java.util.concurrent.ConcurrentHashMap

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

  val scrapeMapSize: Int
    get() = scrapeRequestMapView.size

  fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper): ScrapeRequestWrapper? {
    val scrapeId = scrapeRequest.scrapeId
    logger.debug { "Adding scrapeId: $scrapeId to scrapeRequestMap" }
    return scrapeRequestMapView.put(scrapeId, scrapeRequest)
  }

  fun assignScrapeResults(scrapeResults: ScrapeResults) {
    val scrapeId = scrapeResults.srScrapeId
    scrapeRequestMapView[scrapeId]
      ?.also { wrapper ->
        wrapper.complete(scrapeResults)
        wrapper.agentContext.markActivityTime(true)
      } ?: logger.warn { "Missing ScrapeRequestWrapper for scrape_id: $scrapeId (likely timed out)" }
  }

  fun failScrapeRequest(
    scrapeId: Long,
    failureReason: String,
  ) {
    scrapeRequestMapView[scrapeId]
      ?.also { wrapper ->
        wrapper.complete(
          ScrapeResults(
            srAgentId = wrapper.agentContext.agentId,
            srScrapeId = scrapeId,
            srStatusCode = HttpStatusCode.BadGateway.value,
            srFailureReason = failureReason,
          ),
        )
        wrapper.agentContext.markActivityTime(true)
      } ?: logger.warn { "failScrapeRequest() missing ScrapeRequestWrapper for scrape_id: $scrapeId" }
  }

  fun failAllScrapeRequests(
    agentId: String,
    failureReason: String,
  ) {
    scrapeRequestMapView.values
      .filter { it.agentContext.agentId == agentId }
      .forEach { failScrapeRequest(it.scrapeId, failureReason) }
  }

  fun failAllInFlightScrapeRequests(failureReason: String) {
    scrapeRequestMapView.values.forEach { failScrapeRequest(it.scrapeId, failureReason) }
  }

  fun removeFromScrapeRequestMap(scrapeId: Long): ScrapeRequestWrapper? {
    logger.debug { "Removing scrapeId: $scrapeId from scrapeRequestMap" }
    return scrapeRequestMapView.remove(scrapeId)
  }

  companion object {
    private val logger = logger {}
  }
}
