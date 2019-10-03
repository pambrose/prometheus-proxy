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
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import java.util.concurrent.ConcurrentMap
import kotlin.time.ExperimentalTime

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
@ExperimentalTime
@ObsoleteCoroutinesApi
class ScrapeRequestManager {
    // Map scrape_id to agent_id
    val scrapeRequestMap: ConcurrentMap<Long, ScrapeRequestWrapper> = newConcurrentMap<Long, ScrapeRequestWrapper>()

    val scrapeMapSize: Int
        get() = scrapeRequestMap.size

    fun addToScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper) =
        scrapeRequestMap.put(scrapeRequest.scrapeId, scrapeRequest)

    fun getFromScrapeRequestMap(scrapeId: Long) = scrapeRequestMap[scrapeId]

    fun removeFromScrapeRequestMap(scrapeRequest: ScrapeRequestWrapper) =
        scrapeRequestMap.remove(scrapeRequest.scrapeId)
}