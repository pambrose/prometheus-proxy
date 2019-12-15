/*
 * Copyright © 2019 Paul Ambrose (pambrose@mac.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.proxy

import com.google.common.collect.Maps.newConcurrentMap
import io.prometheus.common.ScrapeResults
import mu.KLogging
import java.util.concurrent.ConcurrentMap

class ScrapeRequestManager {
  // Map scrape_id to agent_id
  val scrapeRequestMap: ConcurrentMap<Long, ScrapeRequestWrapper> = newConcurrentMap()

  val scrapeMapSize: Int
    get() = scrapeRequestMap.size

  fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper): ScrapeRequestWrapper? {
    val scrapeId = scrapeRequest.scrapeId
    logger.debug { "Adding scrapeId: $scrapeId to scrapeRequestMap" }
    return scrapeRequestMap.put(scrapeId, scrapeRequest)
  }

  fun assignScrapeResults(scrapeResults: ScrapeResults) {
    scrapeRequestMap[scrapeResults.scrapeId]
        ?.also { wrapper ->
          wrapper.scrapeResults = scrapeResults
          wrapper.markComplete()
          wrapper.agentContext.markActivityTime(true)
        } ?: logger.error { "Missing ScrapeRequestWrapper for scrape_id: ${scrapeResults.scrapeId}" }
  }

  fun removeFromScrapeRequestMap(scrapeId: Long): ScrapeRequestWrapper? {
    logger.debug { "Removing scrapeId: $scrapeId from scrapeRequestMap" }
    return scrapeRequestMap.remove(scrapeId)
  }

  companion object : KLogging()
}