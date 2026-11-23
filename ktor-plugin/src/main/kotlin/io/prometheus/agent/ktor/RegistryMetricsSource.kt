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

package io.prometheus.agent.ktor

import io.prometheus.agent.MetricsSource
import io.prometheus.metrics.expositionformats.ExpositionFormats
import io.prometheus.metrics.model.registry.PrometheusRegistry
import java.io.OutputStream

// A PrometheusRegistry served by the agent in-process: each scrape gets the registry's metrics in the text format it
// accepts, Prometheus's or OpenMetrics, and only the families it names, when it names any. The Spring Boot starter's
// RegistryMetricsSource does the same in Java.
internal class RegistryMetricsSource(
  private val registry: PrometheusRegistry,
) : MetricsSource {
  private val formats = ExpositionFormats.init()

  override fun write(
    accept: String,
    names: Set<String>,
    out: OutputStream,
  ): String {
    val writer = formats.findWriter(accept)
    writer.write(out, if (names.isEmpty()) registry.scrape() else registry.scrape(names::contains))
    return writer.contentType
  }
}
