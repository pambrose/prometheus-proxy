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

import io.ktor.http.HttpStatusCode

/**
 * Why the proxy itself failed a scrape it had already sent toward an agent, rather than the agent answering it.
 *
 * Each kind carries the outcome [label] recorded in `proxy_scrape_requests_total`, the latency histogram, `/debug`, and
 * the dashboard, and the [statusCode] Prometheus receives. Without it a proxy-made failure was indistinguishable from a
 * 502 the target returned and was labelled `upstream_error`, which pointed operators at the target instead of the
 * agent connection or the proxy.
 */
internal enum class ProxyFailure(
  val label: String,
  val statusCode: HttpStatusCode,
) {
  /** The agent disconnected, was evicted or displaced, or its stream ended before it answered. */
  AGENT_DISCONNECTED("agent_disconnected", HttpStatusCode.ServiceUnavailable),

  /** The proxy is shutting down. */
  PROXY_STOPPED("proxy_stopped", HttpStatusCode.ServiceUnavailable),

  /** The agent answered, but the proxy could not use the answer: a chunk or summary failed validation, say. */
  INVALID_RESPONSE("invalid_response", HttpStatusCode.BadGateway),
}
