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
import io.prometheus.agent.embeddedAgent
import io.prometheus.metrics.model.registry.PrometheusRegistry

// A worker with no HTTP server of its own: the agent reads its metrics from the registry, in-process, for each scrape,
// and serves its own beside them. Without PROMETHEUS_PROXY, as on a developer's machine, the worker runs without it.
fun runWorker(
  version: String,
  work: () -> Unit,
) {
  embeddedAgent {
    name = "worker-$version"
    fromEnvironment()
    // Where the worker's metrics are: Micrometer's PrometheusMeterRegistry gives its own as prometheusRegistry
    source(path = "worker_metrics", registry = PrometheusRegistry.defaultRegistry)
    // The agent's own, for the Agents dashboard
    agentMetricsAt(path = "worker_agent_metrics")
  }.startIfConfigured(logBanner = false).use { work() }
}
// --8<-- [end:embedded-kotlin-source]
