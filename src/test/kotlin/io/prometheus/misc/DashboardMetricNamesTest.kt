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

@file:Suppress("UndocumentedPublicClass", "UndocumentedPublicFunction")

package io.prometheus.misc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.mockk.every
import io.mockk.mockk
import io.prometheus.Agent
import io.prometheus.Proxy
import io.prometheus.agent.AgentHttpService
import io.prometheus.agent.AgentMetrics
import io.prometheus.agent.HttpClientCache
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.prometheus.metrics.model.snapshots.CounterSnapshot
import io.prometheus.metrics.model.snapshots.HistogramSnapshot
import io.prometheus.proxy.AgentContextManager
import io.prometheus.proxy.ProxyMetrics
import io.prometheus.proxy.ProxyPathManager
import io.prometheus.proxy.ScrapeRequestManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.concurrent.atomics.AtomicInt

// The Grafana dashboards, the alert rules and the PromQL in the docs must query series the proxy and agent expose.
// A counter is exposed only with a _total suffix, whatever name it is built with, so a query for the bare name
// matches nothing and a panel or alert silently shows no data.
class DashboardMetricNamesTest : StringSpec() {
  private fun exposedNames(): Set<String> {
    PrometheusRegistry.defaultRegistry.clear()
    ProxyMetrics(mockProxy())
    AgentMetrics(mockAgent())
    return PrometheusRegistry.defaultRegistry.scrape()
      .flatMap { snapshot ->
        val name = snapshot.metadata.name
        when (snapshot) {
          is CounterSnapshot -> listOf("${name}_total")
          is HistogramSnapshot -> listOf("${name}_bucket", "${name}_count", "${name}_sum")
          else -> listOf(name)
        }
      }.toSet()
  }

  private fun mockProxy(): Proxy {
    val pathManager = mockk<ProxyPathManager>(relaxed = true)
    every { pathManager.pathMapSize } returns 0
    return mockk<Proxy>(relaxed = true).also {
      every { it.agentContextManager } returns AgentContextManager(isTestMode = true)
      every { it.pathManager } returns pathManager
      every { it.scrapeRequestManager } returns ScrapeRequestManager()
    }
  }

  private fun mockAgent(): Agent {
    val cache = mockk<HttpClientCache>(relaxed = true)
    every { cache.currentCacheSize() } returns 0
    val httpService = mockk<AgentHttpService>(relaxed = true)
    every { httpService.httpClientCache } returns cache
    return mockk<Agent>(relaxed = true).also {
      every { it.launchId } returns "test-launch-id"
      every { it.scrapeRequestBacklogSize } returns AtomicInt(0)
      every { it.agentHttpService } returns httpService
    }
  }

  private fun read(path: String) = File(path).readText()

  // Every "expr" value in a Grafana dashboard.
  private fun dashboardExprs(element: JsonElement): List<String> =
    when (element) {
      is JsonObject -> {
        element.flatMap { (key, value) ->
          if (key == "expr" && value is JsonPrimitive) listOf(value.content) else dashboardExprs(value)
        }
      }

      is JsonArray -> {
        element.flatMap { dashboardExprs(it) }
      }

      else -> {
        emptyList()
      }
    }

  private fun dashboard(path: String) = dashboardExprs(Json.parseToJsonElement(read(path)))

  // The metric names a PromQL expression selects. Label names appear only inside {...} matchers and grouping
  // clauses, so both are removed first; agent_name, for one, is a label, not a metric.
  private fun metricNames(promql: String): Set<String> =
    promql
      .replace(MATCHERS, "")
      .replace(GROUPING, "")
      .let { stripped -> METRIC_NAME.findAll(stripped).map { it.value }.toSet() }

  // The "expr" of every alert rule in a YAML block. A block scalar (`expr: |`) is the more-indented lines below it.
  private fun alertExprs(text: String): List<String> {
    val lines = text.lines()
    return lines.mapIndexedNotNull { i, line ->
      YAML_EXPR.matchEntire(line)?.let { match ->
        val (indent, value) = match.destructured
        if (!BLOCK_SCALAR.matches(value.trim())) {
          value
        } else {
          lines
            .drop(i + 1)
            .takeWhile { it.isBlank() || it.indexOfFirst { c -> !c.isWhitespace() } > indent.length }
            .joinToString("\n")
        }
      }
    }
  }

  private val sources: Map<String, () -> List<String>> =
    mapOf(
      PROXY_DASHBOARD to { dashboard(PROXY_DASHBOARD) },
      AGENTS_DASHBOARD to { dashboard(AGENTS_DASHBOARD) },
      MONITORING_SNIPPETS to { SNIPPET.findAll(read(MONITORING_SNIPPETS)).map { it.groupValues[1] }.toList() },
      METRICS_DOC to { PROMQL_BLOCK.findAll(read(METRICS_DOC)).map { it.groupValues[1] }.toList() },
      GRAFANA_PAGE to { alertExprs(read(GRAFANA_PAGE)) },
    )

  init {
    sources.forEach { (file, queries) ->
      "every proxy and agent series queried in $file should be exposed" {
        val exposed = exposedNames()
        val queried = queries()
        queried.shouldNotBeEmpty()
        // Every query names a proxy or agent series, so a query the extractor mangled (a YAML block scalar read as
        // just "|", say) fails here instead of passing as a query with nothing to check.
        queried.filter { metricNames(it).isEmpty() }.shouldBeEmpty()
        queried.flatMap { metricNames(it) }.filterNot { it in exposed }.distinct().shouldBeEmpty()
      }
    }

    "every alert rule on the Grafana page should have its expression checked" {
      alertExprs(read(GRAFANA_PAGE)) shouldHaveSize ALERT.findAll(read(GRAFANA_PAGE)).count()
    }
  }

  companion object {
    private const val PROXY_DASHBOARD = "grafana/prometheus-proxy.json"
    private const val AGENTS_DASHBOARD = "grafana/prometheus-agents.json"
    private const val MONITORING_SNIPPETS = "src/test/kotlin/website/MonitoringExamples.txt"
    private const val METRICS_DOC = "docs/metrics-and-grafana.md"
    private const val GRAFANA_PAGE = "website/prometheus-proxy/docs/grafana.md"
    private val MATCHERS = Regex("""\{[^}]*}""")
    private val GROUPING = Regex("""\b(?:by|without|on|ignoring|group_left|group_right)\s*\([^)]*\)""")
    private val METRIC_NAME = Regex("""\b(?:proxy|agent)_[a-z_]+\b""")
    private val SNIPPET =
      Regex("""; --8<-- \[start:promql-[^\]]+]\n(.*?)\n; --8<-- \[end:""", RegexOption.DOT_MATCHES_ALL)
    private val PROMQL_BLOCK = Regex("""```promql\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    private val ALERT = Regex("""^\s*- alert:""", RegexOption.MULTILINE)
    private val YAML_EXPR = Regex("""(\s*)expr:\s*(.+)""")
    private val BLOCK_SCALAR = Regex("""[|>][-+]?""")
  }
}
