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

package io.prometheus.common.dashboard

import io.ktor.http.HttpStatusCode
import io.prometheus.common.KeyedThrottle
import io.prometheus.common.dashboard.ScrapeNowResult.Refused
import kotlinx.coroutines.sync.Semaphore
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The limits a dashboard's scrape-now runs within, the proxy's (`ScrapeNow`) and the agent's (`AgentScrapeNow`) alike,
 * so clicks can't crowd out the scrapes Prometheus makes: at most [MAX_CONCURRENT] at once, and each path at most once
 * every [COOLDOWN].
 *
 * @param timeSource where the cooldowns' time comes from; injectable so tests move time instead of sleeping
 */
internal class ScrapeNowLimits(
  timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val permits = Semaphore(MAX_CONCURRENT)

  // Which paths may be scraped from the dashboard now: each at most once every COOLDOWN. It remembers the
  // MAX_REMEMBERED_PATHS paths asked about most recently; one asked about less recently is long past its cooldown.
  private val cooldowns = KeyedThrottle<String>(COOLDOWN, MAX_REMEMBERED_PATHS, timeSource)

  /**
   * Runs [scrape] of the path keyed [key], shown as [shown], unless [MAX_CONCURRENT] scrapes are running or the path
   * was scraped less than [COOLDOWN] ago, which it is refused for instead.
   */
  suspend fun within(
    key: String,
    shown: String,
    scrape: suspend () -> ScrapeNowResult,
  ): ScrapeNowResult {
    if (!permits.tryAcquire())
      return Refused(HttpStatusCode.TooManyRequests, "$MAX_CONCURRENT dashboard scrapes are running")
    return try {
      if (cooldowns.tryAcquire(key))
        scrape()
      else
        Refused(HttpStatusCode.TooManyRequests, "/$shown was scraped from the dashboard less than $COOLDOWN ago")
    } finally {
      permits.release()
    }
  }

  companion object {
    const val MAX_CONCURRENT = 4
    val COOLDOWN = 5.seconds

    private const val MAX_REMEMBERED_PATHS = 1000
  }
}
