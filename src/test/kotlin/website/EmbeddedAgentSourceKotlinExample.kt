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

package website

// --8<-- [start:embedded-kotlin-source]
import io.prometheus.agent.MetricsSource
import io.prometheus.agent.embeddedAgent
import io.prometheus.metrics.expositionformats.ExpositionFormats
import io.prometheus.metrics.model.registry.PrometheusRegistry

// The app's registry, which the agent reads in-process: Micrometer's PrometheusMeterRegistry gives its own with
// getPrometheusRegistry(). Written in the format the scrape asks for, and only the families it names, if it names any.
fun registrySource(registry: PrometheusRegistry): MetricsSource {
  val formats = ExpositionFormats.init()
  return MetricsSource { accept, names, out ->
    val writer = formats.findWriter(accept)
    writer.write(out, if (names.isEmpty()) registry.scrape() else registry.scrape(names::contains))
    writer.contentType
  }
}

// The app needs no /metrics endpoint for the agent, and no credentials for one: the agent reads the registry itself.
fun startInProcessAgent(
  registry: PrometheusRegistry,
  proxyEndpoint: String,
) = embeddedAgent {
  name = "my-app"
  proxy = proxyEndpoint
  source(path = "my_app_metrics", source = registrySource(registry))
}.start(logBanner = false)
// --8<-- [end:embedded-kotlin-source]
