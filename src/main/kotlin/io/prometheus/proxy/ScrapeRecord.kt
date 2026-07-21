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

import java.time.Instant

/**
 * One completed scrape, recorded structurally for the operational web UI.
 *
 * The `/debug` servlet's existing recent-requests queue holds pre-formatted strings — readable, but
 * carrying no agent attribution and no field boundaries, so a per-agent view cannot be built from it
 * without parsing display text back apart. This is the parallel structured record; the two are
 * populated from the same site and neither depends on the other.
 *
 * Immutable, so a snapshot can hand it to any number of WebSocket sessions without copying.
 *
 * @param agentId the agent that served the scrape, which is what makes a per-agent view possible
 * @param path the registered path scraped, without a leading slash
 * @param statusCode the HTTP status the proxy returned to Prometheus
 * @param outcome the scrape-request outcome label, matching the `proxy_scrape_requests{type}` values
 * @param durationMillis how long the agent took to fetch the target
 * @param contentLength size of the returned body, in characters
 */
internal data class ScrapeRecord(
  val agentId: String,
  val path: String,
  val statusCode: Int,
  val outcome: String,
  val durationMillis: Long,
  val contentLength: Int,
  val at: Instant = Instant.now(),
) {
  val isSuccess: Boolean get() = statusCode in 200..299
}
