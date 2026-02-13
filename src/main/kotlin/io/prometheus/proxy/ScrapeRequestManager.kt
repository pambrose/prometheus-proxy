/*
 * Copyright © 2024 Paul Ambrose (pambrose@mac.com)
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

package io.prometheus.proxy

import io.github.oshai.kotlinlogging.KotlinLogging.logger
import io.ktor.http.HttpStatusCode
import io.prometheus.common.ScrapeResults
import java.util.concurrent.ConcurrentHashMap

internal class ScrapeRequestManager {
  // Map scrape_id to ScrapeRequestWrapper
  private val scrapeRequestMap = ConcurrentHashMap<Long, ScrapeRequestWrapper>()

  val scrapeRequestMapView: Map<Long, ScrapeRequestWrapper> get() = scrapeRequestMap

  fun containsScrapeRequest(scrapeId: Long): Boolean = scrapeRequestMap.containsKey(scrapeId)

  val scrapeMapSize: Int
    get() = scrapeRequestMap.size

  fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper): ScrapeRequestWrapper? {
    val scrapeId = scrapeRequest.scrapeId
    logger.debug { "Adding scrapeId: $scrapeId to scrapeRequestMap" }
    return scrapeRequestMap.put(scrapeId, scrapeRequest)
  }

  fun assignScrapeResults(scrapeResults: ScrapeResults) {
    val scrapeId = scrapeResults.srScrapeId
    scrapeRequestMap[scrapeId]
      ?.also { wrapper ->
        wrapper.scrapeResults = scrapeResults
        wrapper.markComplete()
        wrapper.agentContext.markActivityTime(true)
      } ?: logger.warn { "Missing ScrapeRequestWrapper for scrape_id: $scrapeId (likely timed out)" }
  }

  fun failScrapeRequest(
    scrapeId: Long,
    failureReason: String,
  ) {
    scrapeRequestMap[scrapeId]
      ?.also { wrapper ->
        wrapper.scrapeResults = ScrapeResults(
          srAgentId = wrapper.agentContext.agentId,
          srScrapeId = scrapeId,
          srStatusCode = HttpStatusCode.BadGateway.value,
          srFailureReason = failureReason,
        )
        wrapper.markComplete()
        wrapper.agentContext.markActivityTime(true)
      } ?: logger.warn { "failScrapeRequest() missing ScrapeRequestWrapper for scrape_id: $scrapeId" }
  }

  fun failAllScrapeRequests(
    agentId: String,
    failureReason: String,
  ) {
    scrapeRequestMap.values
      .filter { it.agentContext.agentId == agentId }
      .forEach { failScrapeRequest(it.scrapeId, failureReason) }
  }

  fun failAllInFlightScrapeRequests(failureReason: String) {
    scrapeRequestMap.values.forEach { failScrapeRequest(it.scrapeId, failureReason) }
  }

  fun removeFromScrapeRequestMap(scrapeId: Long): ScrapeRequestWrapper? {
    logger.debug { "Removing scrapeId: $scrapeId from scrapeRequestMap" }
    return scrapeRequestMap.remove(scrapeId)
  }

  companion object {
    private val logger = logger {}
  }
}
