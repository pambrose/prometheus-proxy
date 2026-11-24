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

import io.prometheus.agent.AgentMetrics.Companion.LAUNCH_ID
import io.prometheus.metrics.expositionformats.ExpositionFormats
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.DataPointSnapshot
import io.prometheus.metrics.model.snapshots.GaugeSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.metrics.model.snapshots.MetricSnapshot
import io.prometheus.metrics.model.snapshots.MetricSnapshots
import java.io.OutputStream

// A PrometheusRegistry served by the agent in-process (MetricsSource.of): each scrape gets the registry's metrics in
// the text format it accepts, Prometheus's or OpenMetrics, and only the families it names, when it names any. A source
// of the default registry leaves out the agent metrics while an agent collects them, which puts them there: they're
// served at the agent's proxy path (agentMetricsAt), or on its metrics port, so each series is served once.
internal class RegistryMetricsSource(
  private val registry: PrometheusRegistry,
) : MetricsSource {
  private val formats = ExpositionFormats.init()

  override fun write(
    accept: String,
    names: Set<String>,
    out: OutputStream,
  ): String {
    val withoutAgent = registry === PrometheusRegistry.defaultRegistry && AgentMetrics.isCollected

    // The registry tests a family's two names, its Prometheus name and its exposition name, and takes the family when
    // either passes. Both of an agent family's names are the agent's, so neither passes.
    fun served(name: String) = (name in names || names.isEmpty()) && !(withoutAgent && AgentMetrics.isAgentFamily(name))

    val snapshots = if (names.isEmpty() && !withoutAgent) registry.scrape() else registry.scrape(::served)
    return formats.write(accept, out, snapshots)
  }
}

// The agent's own metrics, served in-process at the proxy path agent.metrics.proxyPath gives them (agentMetricsAt
// sets it): the agent families in the default registry, with only the series of the agent launched as [launchId],
// since every agent in the JVM shares them. An embedded agent's path has nothing else: its JVM's metrics are its
// host's, which the host's own path carries. A standalone agent's JVM is its own, so its path, while [standalone],
// serves the rest of the default registry too, as its metrics port does: the JVM metric groups its agent.metrics
// settings turn on.
internal class AgentMetricsSource(
  private val standalone: () -> Boolean = { false },
  private val launchId: () -> String,
) : MetricsSource {
  private val formats = ExpositionFormats.init()

  override fun write(
    accept: String,
    names: Set<String>,
    out: OutputStream,
  ): String {
    val launch = launchId()
    val withJvm = standalone()
    val snapshots =
      PrometheusRegistry.defaultRegistry
        .scrape { name -> (withJvm || AgentMetrics.isAgentFamily(name)) && (names.isEmpty() || name in names) }
        .mapNotNull { if (AgentMetrics.isAgentFamily(it.metadata.prometheusName)) it.seriesOf(launch) else it }
    return formats.write(accept, out, MetricSnapshots(snapshots))
  }

  private companion object {
    // The family with only the series labeled with [launchId], or null when it has none.
    fun MetricSnapshot.seriesOf(launchId: String): MetricSnapshot? {
      fun DataPointSnapshot.isOf() = labels.get(LAUNCH_ID) == launchId
      val own =
        when (this) {
          is CounterSnapshot -> CounterSnapshot(metadata, dataPoints.filter { it.isOf() })

          is GaugeSnapshot -> GaugeSnapshot(metadata, dataPoints.filter { it.isOf() })

          is HistogramSnapshot -> HistogramSnapshot(isGaugeHistogram, metadata, dataPoints.filter { it.isOf() })

          // The agent metrics are counters, gauges, and histograms; a family of another type is kept whole
          else -> this
        }
      return own.takeIf { it.dataPoints.isNotEmpty() }
    }
  }
}

// Writes [snapshots] to [out] in the format [accept] allows, and returns its content type.
private fun ExpositionFormats.write(
  accept: String,
  out: OutputStream,
  snapshots: MetricSnapshots,
): String {
  val writer = findWriter(accept)
  writer.write(out, snapshots)
  return writer.contentType
}
