package io.prometheus.proxy

import com.google.common.collect.Maps
import java.util.concurrent.ConcurrentMap

class ScrapeRequestManager {
    // Map scrape_id to agent_id
    val scrapeRequestMap: ConcurrentMap<Long, ScrapeRequestWrapper> = Maps.newConcurrentMap<Long, ScrapeRequestWrapper>()

    val scrapeMapSize: Int
        get() = scrapeRequestMap.size

    fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper) = scrapeRequestMap.put(scrapeRequest.scrapeId,
                                                                                          scrapeRequest)

    fun getFromScrapeRequestMap(scrapeId: Long) = scrapeRequestMap[scrapeId]

    fun removeFromScrapeRequestMap(scrapeId: Long) = scrapeRequestMap.remove(scrapeId)

}