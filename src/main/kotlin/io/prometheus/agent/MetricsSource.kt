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

import java.io.IOException
import java.io.OutputStream

/**
 * Metrics an embedded agent reads in-process, from the app it's embedded in, rather than by scraping a URL: an app
 * hands the agent its registry with [EmbeddedAgentConfig.Builder.source], and needs no metrics endpoint, port, or
 * credentials for the agent to read it.
 *
 * The agent calls [write] for each scrape Prometheus makes of the path, and for each one either dashboard's Scrape now
 * makes, on a thread it can block, and treats what it writes as it treats a target's body: up to its
 * `http.maxContentLengthMBytes`, and through the path's metric filter. Calls can overlap, as scrapes can, so [write]
 * must be safe to call from several threads at once, as a registry's scrape is.
 *
 * At the agent's scrape timeout, the agent interrupts the call and answers the scrape without waiting for it. A call
 * that ignores the interrupt, such as one blocked on a socket read, holds its thread until it returns, and the agent
 * doesn't call the source again until then, failing its scrapes meanwhile. Whatever [write] throws, short of a
 * `VirtualMachineError` such as `OutOfMemoryError`, fails the scrape it was called for, and nothing else.
 *
 * A Prometheus client's registry writes itself in a few lines, with `ExpositionFormats` from
 * `io.prometheus:prometheus-metrics-exposition-textformats`; with Micrometer's `PrometheusMeterRegistry`, the
 * Prometheus Java client's `PrometheusRegistry` it reads:
 *
 * ```kotlin
 * val formats = ExpositionFormats.init()
 * val source =
 *   MetricsSource { accept, names, out ->
 *     val writer = formats.findWriter(accept)
 *     writer.write(out, if (names.isEmpty()) registry.scrape() else registry.scrape(names::contains))
 *     writer.contentType
 *   }
 * ```
 */
fun interface MetricsSource {
  /**
   * Writes the metrics to [out], in a format [accept] allows, and returns the content type written.
   *
   * @param accept Prometheus's `Accept` header, without the protobuf the agent can't carry: the Prometheus text format,
   *   OpenMetrics, or both, with their weights. Empty when Prometheus named neither, for the text format.
   * @param names the metric families Prometheus asked for, with `name[]` parameters, as the Prometheus client libraries
   *   read them; empty for all of them.
   * @param out where the metrics go. The agent closes nothing: it reads what was written once [write] returns.
   * @return the content type of what was written, such as `text/plain; version=0.0.4; charset=utf-8`; empty for the
   *   Prometheus text format.
   * @throws IOException when the metrics can't be written, which fails the scrape, as a target that can't be reached
   *   does. Any other exception or error does too.
   * @throws InterruptedException when the call is interrupted, as the agent does at the scrape timeout, so a source in
   *   Java can let one through from a call it makes.
   */
  @Throws(IOException::class, InterruptedException::class)
  fun write(
    accept: String,
    names: Set<String>,
    out: OutputStream,
  ): String
}

/**
 * What a path served by a [MetricsSource] shows for its target, where a scraped path shows its URL: in the agent's
 * logs and dashboard, the proxy's dashboard, and a debug scrape's results.
 */
internal const val IN_PROCESS_TARGET = "in-process"

/** What a scrape of a path reads: its [url], or its [source] when the path is served in-process. */
internal data class ScrapeTarget(
  val url: String,
  val source: MetricsSource? = null,
)
